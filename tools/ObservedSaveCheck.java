/*
 * Botmap save/merge semantics.
 *
 * Symptom under test: the client's tick blocking on the botmap saver. 187 of 210 captured
 * stall stacks sat at Observed.observe waiting to enter LOCK while save() held it across an
 * 8MB JSON parse, the merge, and an 8MB re-encode - stalls of 300ms to 1.3s, every five
 * seconds, on every client in a crew.
 *
 * The fix moves the parse and the encode off LOCK. That is only safe if the merge rule still
 * holds when adopt() runs LATER than the read that produced its input, and if nothing observed
 * during the encode can be lost. Those are the two properties this harness pins down, along
 * with the equivalence of adopt()'s new bulk-copy fast path to the per-tile loop it replaces.
 *
 * NOT part of the client build - build.xml compiles src/ only, and this lives in tools/ so it
 * can never reach a release jar. It runs in a TEMPORARY WORKING DIRECTORY, because
 * Observed.file() resolves "botmap.json" against the cwd and a real crew map must never be
 * what a test writes over. Run on demand (from the repo root, PowerShell):
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\obscheck tools\ObservedSaveCheck.java
 *   java -cp "$env:TEMP\obscheck;$CP" haven.automated.nbots.world.ObservedSaveCheck
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven.automated.nbots.world;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import haven.Coord;
import haven.MCache;

public class ObservedSaveCheck {
    static int failures = 0;
    static final int GRID = MCache.cmaps.x * MCache.cmaps.y;
    static final long SEG = 77L;
    static final Coord GC = new Coord(0, 0);

    static void ok(boolean cond, String what) {
        System.out.println((cond ? "  PASS  " : "  FAIL  ") + what);
        if (!cond)
            failures++;
    }

    /* ---- reflection plumbing; everything under test is private static ---- */

    static Field fld(String name) throws Exception {
        Field f = Observed.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static Method mth(String name, Class<?>... sig) throws Exception {
        Method m = Observed.class.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<Long, Map<Coord, byte[]>> mapField() throws Exception {
        return (Map<Long, Map<Coord, byte[]>>) fld("map").get(null);
    }

    @SuppressWarnings("unchecked")
    static Map<Long, Map<Coord, BitSet>> lookedField() throws Exception {
        return (Map<Long, Map<Coord, BitSet>>) fld("looked").get(null);
    }

    /** Installs a known world: one segment, one grid, holding {@code mine}. */
    static void install(byte[] mine, BitSet mask) throws Exception {
        Map<Long, Map<Coord, byte[]>> m = new HashMap<>();
        Map<Coord, byte[]> seg = new HashMap<>();
        seg.put(GC, mine);
        m.put(SEG, seg);
        fld("map").set(null, m);

        Map<Long, Map<Coord, BitSet>> lk = new HashMap<>();
        if (mask != null) {
            Map<Coord, BitSet> ls = new HashMap<>();
            ls.put(GC, mask);
            lk.put(SEG, ls);
        }
        fld("looked").set(null, lk);
    }

    static Map<Long, Map<Coord, byte[]>> disk(byte[] theirs) {
        Map<Long, Map<Coord, byte[]>> d = new HashMap<>();
        Map<Coord, byte[]> seg = new HashMap<>();
        seg.put(GC, theirs);
        d.put(SEG, seg);
        return d;
    }

    static byte[] filled(byte v) {
        byte[] g = new byte[GRID];
        Arrays.fill(g, v);
        return g;
    }

    /** The merge rule exactly as it read before the fast path was added. */
    static byte[] reference(byte[] mine, byte[] theirs, BitSet mask) {
        byte[] out = mine.clone();
        for (int i = 0; i < out.length; i++) {
            if ((mask == null) || !mask.get(i))
                out[i] = theirs[i];
        }
        return out;
    }

    /* ---------------------------- the checks ---------------------------- */

    /** The bulk-copy fast path must agree with the per-tile loop for every shape of mask. */
    static void checkAdoptEquivalence() throws Exception {
        Method adopt = mth("adopt", Map.class);
        String[] names = {"no mask at all", "empty mask", "one tile looked at",
                          "scattered tiles looked at", "every tile looked at"};
        for (int cse = 0; cse < names.length; cse++) {
            BitSet mask;
            switch (cse) {
                case 0: mask = null; break;
                case 1: mask = new BitSet(GRID); break;
                case 2: mask = new BitSet(GRID); mask.set(4242); break;
                case 3:
                    mask = new BitSet(GRID);
                    for (int i = 0; i < GRID; i += 37)
                        mask.set(i);
                    break;
                default:
                    mask = new BitSet(GRID);
                    mask.set(0, GRID);
                    break;
            }
            byte[] mine = filled(Observed.SOLID);
            byte[] theirs = filled(Observed.OPEN);
            byte[] want = reference(mine, theirs, mask);
            install(mine, mask);
            adopt.invoke(null, disk(theirs));
            ok(Arrays.equals(mapField().get(SEG).get(GC), want),
               "adopt matches the per-tile rule: " + names[cse]);
        }
    }

    /**
     * adopt() must not manufacture looked-entries for grids this run never went near.
     * It used to call mask(), which is computeIfAbsent, and so grew one BitSet per grid on
     * disk - thousands of them, kept for the session, every one answering "no" to everything.
     */
    static void checkNoMaskPollution() throws Exception {
        install(filled(Observed.SOLID), null);
        mth("adopt", Map.class).invoke(null, disk(filled(Observed.OPEN)));
        Map<Long, Map<Coord, BitSet>> lk = lookedField();
        int n = 0;
        for (Map<Coord, BitSet> seg : lk.values())
            n += seg.size();
        ok(n == 0, "adopt creates no looked-mask entries for grids never observed (found " + n + ")");
    }

    /**
     * THE safety property for moving read() off LOCK.
     *
     * The client keeps observing while the saver parses the file. Those observations land
     * between the read and the adopt. They must survive: adopt() tests the looked-mask as it
     * stands when it runs, and set() marks that mask, so a tile observed inside the window is
     * already protected by the time the merge asks.
     */
    static void checkLateAdoptKeepsObservations() throws Exception {
        install(filled(Observed.UNSEEN), null);
        // The saver has read the file; disk says this ground is open.
        Map<Long, Map<Coord, byte[]>> d = disk(filled(Observed.OPEN));
        // ...and only now does the client see a wall at tile (5,7) and record it.
        Method set = mth("set", long.class, Coord.class, byte.class);
        set.invoke(null, SEG, new Coord(5, 7), Observed.WALL);
        int i = (7 * MCache.cmaps.x) + 5;
        // The merge happens afterwards, as it now does.
        mth("adopt", Map.class).invoke(null, d);
        byte[] got = mapField().get(SEG).get(GC);
        ok(got[i] == Observed.WALL,
           "a tile observed during the parse window is not overwritten by the file");
        ok(got[0] == Observed.OPEN,
           "ground not observed this run still takes the file's value");
    }

    /** The snapshot must not alias the live arrays, or the encode would see torn state. */
    static void checkSnapshotIsDeep() throws Exception {
        install(filled(Observed.OPEN), null);
        @SuppressWarnings("unchecked")
        Map<Long, Map<Coord, byte[]>> snap =
            (Map<Long, Map<Coord, byte[]>>) mth("snapshot").invoke(null);
        mapField().get(SEG).get(GC)[9] = Observed.SOLID;
        ok(snap.get(SEG).get(GC)[9] == Observed.OPEN,
           "snapshot is a deep copy - later observation cannot alter it mid-encode");
    }

    /** encode() off the lock must still produce a file read() understands, byte for byte. */
    static void checkEncodeRoundTrip() throws Exception {
        byte[] mine = filled(Observed.OPEN);
        for (int i = 0; i < GRID; i += 11)
            mine[i] = Observed.WALL;
        mine[0] = Observed.GATE;
        mine[GRID - 1] = Observed.TIGHT;
        install(mine, null);
        @SuppressWarnings("unchecked")
        Map<Long, Map<Coord, byte[]>> snap =
            (Map<Long, Map<Coord, byte[]>>) mth("snapshot").invoke(null);
        byte[] out = (byte[]) mth("encode", Map.class).invoke(null, snap);
        Files.write(Paths.get("botmap.json"), out);
        Map<Long, Map<Coord, byte[]>> back = new HashMap<>();
        boolean read = (Boolean) mth("read", Map.class).invoke(null, back);
        ok(read, "read() accepts what encode() produced");
        ok((back.get(SEG) != null) && Arrays.equals(back.get(SEG).get(GC), mine),
           "encode/read round-trips the record unchanged");
    }

    /**
     * The generation counter is what stops an observation made during the encode from being
     * lost: save() only clears dirty when the counter has not moved.
     */
    static void checkGenerationTracksChanges() throws Exception {
        install(filled(Observed.UNSEEN), null);
        Method set = mth("set", long.class, Coord.class, byte.class);
        Field gen = fld("gen");

        long before = gen.getLong(null);
        set.invoke(null, SEG, new Coord(1, 1), Observed.WALL);
        long afterChange = gen.getLong(null);
        ok(afterChange != before, "recording a NEW value moves the generation counter");

        set.invoke(null, SEG, new Coord(1, 1), Observed.WALL);
        ok(gen.getLong(null) == afterChange,
           "re-observing the same value does not, so an unchanged record is not rewritten");

        // What save() concludes from it.
        long snapGen = gen.getLong(null);
        set.invoke(null, SEG, new Coord(2, 2), Observed.SOLID);
        ok((gen.getLong(null) != snapGen),
           "an observation after the snapshot leaves dirty set, so it goes out next pass");
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("obscheck");
        System.setProperty("user.dir", tmp.toString());
        /* Belt and braces: Paths.get() reads the JVM's real cwd, not the property, so the
         * round-trip check writes botmap.json wherever this was launched from. Refuse to run
         * anywhere a real one is sitting rather than overwrite a crew's map. */
        Path here = Paths.get("botmap.json");
        if (Files.exists(here) && (Files.size(here) > 0)) {
            System.err.println("refusing to run: " + here.toAbsolutePath()
                + " exists. Run this from an empty directory - it writes botmap.json.");
            System.exit(2);
        }
        System.out.println("Observed save/merge checks (cwd " + Paths.get("").toAbsolutePath() + ")");
        checkAdoptEquivalence();
        checkNoMaskPollution();
        checkLateAdoptKeepsObservations();
        checkSnapshotIsDeep();
        checkEncodeRoundTrip();
        checkGenerationTracksChanges();
        try {
            Files.deleteIfExists(here);
        } catch (Exception e) {
            // leaving a temp file behind is not a test failure
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
