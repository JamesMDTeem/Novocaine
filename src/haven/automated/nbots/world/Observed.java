package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Resource;
import haven.automated.helpers.HitBoxes;
import haven.automated.nbots.core.NLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the character has actually SEEN, tile by tile, remembered across sessions.
 *
 * This replaces guessing at the world from two thin sources - map-file terrain, which knows about
 * water and nothing else, and a list of wall-shaped gobs learned only during travel - with one
 * dense record of observation. The distinction it finally makes properly is between "there is
 * nothing here" and "nobody has looked here", which every routing decision turns on and which
 * neither of the old sources could express.
 *
 * Three things were wrong with what came before, and all three are the same mistake seen from
 * different angles:
 *
 * IT ONLY LOOKED WHILE TRAVELLING. The old sweep ran from exactly three places, all inside
 * travel: the start of a journey, after passing a gateway, and after a leg failed. So the record
 * grew only at the moments a bot was already in trouble, and the walls it walked past the rest of
 * the time - which is nearly all of them - were rendered on screen and never written down.
 *
 * IT ONLY RECOGNISED WALLS BY NAME. Anything under an {@code arch/} path whose name mentioned a
 * wall or a fence counted, and nothing else did. Measured against this install's own collision
 * data that is thirteen resources out of two hundred and thirty-one: every cupboard, kiln, chest,
 * stockpile, cart and boulder was invisible to routing, so routes were planned straight through
 * the furniture and the local pathfinder had to improvise around it on arrival. Here anything with
 * a collision box is solid, because it is.
 *
 * IT COULD NOT SAY WHAT IT DID NOT KNOW. Unseen ground read as walkable, so a route would happily
 * run through a palisade nobody had been near, and a reachability test could always find an
 * imaginary way round by wandering off the edge of the explored map. With observation recorded,
 * "we have looked at this" is a fact rather than a guess about radii.
 *
 * Recorded at TILE resolution, which is the resolution routing now works at. Finer would describe
 * gaps a character cannot fit through anyway, and the local pathfinder - which does exact geometry
 * against the same collision boxes - remains the authority on whether a particular step fits. This
 * layer chooses which side of the base to walk round; it does not choose footsteps.
 */
public class Observed {
    private static final String FILE = "botmap.json";
    private static final Object LOCK = new Object();
    private static final int VERSION = 1;

    /** Nobody has looked. Passable on sufferance, and expensive - see {@link Router}. */
    public static final byte UNSEEN = 0;
    /** Looked at, nothing solid standing on it. */
    public static final byte OPEN = 1;
    /** Something with a collision box stands here. */
    public static final byte SOLID = 2;
    /** A gateway. Solid until somebody opens it, which is the task layer's job, so: passable. */
    public static final byte GATE = 3;
    /**
     * A wall, fence or palisade - solid, and told apart from ordinary solids on purpose.
     *
     * Routing treats this and {@link #SOLID} identically, because both stop a character. The
     * difference is for {@link Barriers#walledOffFrom}, which asks whether something stands inside
     * somebody's enclosure: that question is about walls, and running its connected-component
     * analysis over cupboards and boulders as well would join a barn to a palisade and call a
     * hillside a base.
     */
    public static final byte WALL = 4;

    /**
     * How far out the character is taken to be able to SEE, in tiles.
     *
     * Measured in game rather than reasoned about: standing still with hitboxes drawn, gobs render
     * out to about thirty-nine or forty tiles in every direction. Held one tile inside that, since
     * the cost of being short is a tile re-observed a moment later and the cost of being long is
     * ground recorded as empty because whatever stands on it had not arrived yet.
     *
     * Worth writing down because it is easy to get from the wrong place. nurgling2's equivalent
     * uses twenty-five tiles, and the client's own pathfinder window is forty-four; the first is
     * conservative and the second is the TILE streaming range, which runs further out than the gob
     * range. Neither is this number.
     */
    private static final int SEES = 38;

    /** How often the sweep actually looks, in milliseconds. */
    private static final long SWEEP_MS = 1000;
    /** How often a changed record is written out, in milliseconds. */
    private static final long SAVE_MS = 5000;

    /** Nudge off a box's far edge, which belongs to the next tile along. See {@link #footprint}. */
    private static final double EDGE = 0.001;
    /** Tiles across. A collision box bigger than this is bad data, not a bigger object. */
    private static final int MAX_SPAN = 12;

    private static final int GRID = MCache.cmaps.x * MCache.cmaps.y;

    /** segment -> segment grid coord -> one byte per tile, row-major. */
    private static Map<Long, Map<Coord, byte[]>> map;
    /**
     * The same shape, one bit per tile, set where THIS RUN has looked.
     *
     * The difference between what we know and what we have seen for ourselves, which is what makes
     * a crew's shared file safe to write to. See {@link #sync}.
     */
    private static Map<Long, Map<Coord, BitSet>> looked = new HashMap<>();
    private static boolean dirty = false;
    private static long swept = 0;
    private static long fileAt = -1;
    private static Thread saver = null;

    private Observed() {}

    // ------------------------------------------------------------------ asking

    /** What is known about a segment tile: one of {@link #UNSEEN}, {@link #OPEN} and so on. */
    public static byte at(long seg, Coord segTile) {
        synchronized (LOCK) {
            load();
            byte[] g = grid(seg, Terrain.floorDiv(segTile, MCache.cmaps), false);
            if (g == null)
                return UNSEEN;
            Coord in = segTile.sub(Terrain.floorDiv(segTile, MCache.cmaps).mul(MCache.cmaps));
            return g[(in.y * MCache.cmaps.x) + in.x];
        }
    }

    /**
     * One grid's raw tiles, or null if nothing has been seen in it.
     *
     * For a search that is about to ask about tens of thousands of tiles and should not take the
     * lock for each one. The array is live rather than a copy: a sweep running alongside may
     * change a byte under the reader, which is harmless - byte reads do not tear, and a search
     * seeing one tile a second fresher or staler than its neighbours cannot be wrong in any way
     * that matters.
     */
    static byte[] gridOf(long seg, Coord gc) {
        synchronized (LOCK) {
            load();
            return grid(seg, gc, false);
        }
    }

    /** True if something solid - and not a gateway - is known to stand here. */
    public static boolean solid(long seg, Coord segTile) {
        byte s = at(seg, segTile);
        return (s == SOLID) || (s == WALL);
    }

    /** Every wall and gateway tile known in a segment, for the enclosure inference. */
    public static Set<Coord> barriersIn(long seg) {
        return tilesIn(seg, WALL, GATE);
    }

    /** True if a gateway stands here. */
    public static boolean gate(long seg, Coord segTile) {
        return at(seg, segTile) == GATE;
    }

    /**
     * True if this tile has ever been looked at with objects loaded.
     *
     * The question the old code kept trying to answer with a radius and getting wrong in both
     * directions. It is a recorded fact, so it can simply be asked.
     */
    public static boolean seen(long seg, Coord segTile) {
        return at(seg, segTile) != UNSEEN;
    }

    /** Every gateway tile known in a segment. The caller decides which is worth walking to. */
    public static Set<Coord> gatesIn(long seg) {
        return tilesIn(seg, GATE, GATE);
    }

    private static Set<Coord> tilesIn(long seg, byte a, byte b) {
        Set<Coord> out = new HashSet<>();
        synchronized (LOCK) {
            load();
            Map<Coord, byte[]> byseg = map.get(seg);
            if (byseg == null)
                return out;
            for (Map.Entry<Coord, byte[]> e : byseg.entrySet()) {
                byte[] g = e.getValue();
                Coord base = e.getKey().mul(MCache.cmaps);
                for (int i = 0; i < g.length; i++) {
                    if ((g[i] == a) || (g[i] == b))
                        out.add(base.add(i % MCache.cmaps.x, i / MCache.cmaps.x));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ looking

    /**
     * Records what is in sight. Called from the client's own tick, throttled.
     *
     * The sweep runs on the caller's thread because it is only a pass over the loaded gobs and
     * some array writes. The WRITE does not, because it re-reads the file to merge and doing that
     * from the client's tick would stutter it.
     */
    public static void tick(GameUI gui) {
        long now = System.currentTimeMillis();
        if ((now - swept) < SWEEP_MS)
            return;
        swept = now;
        try {
            observe(gui);
            // Shares this cadence rather than keeping a timer of its own; it wants the same rate
            // and the same "there is a world to look at" checks.
            Sight.tick(gui);
        } catch (RuntimeException e) {
            // Never let one bad gob take the client's tick down with it.
        }
        startSaver();
    }

    private static synchronized void startSaver() {
        if (saver != null)
            return;
        saver = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SAVE_MS);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    save();
                } catch (RuntimeException e) {
                    // Try again next pass rather than killing the thread.
                }
            }
        }, "botmap-saver");
        saver.setDaemon(true);
        saver.start();
    }

    /**
     * One look at the world.
     *
     * Two kinds of evidence, treated differently on purpose.
     *
     * NEGATIVE evidence - "there is nothing here" - is only worth anything where we could have
     * seen something, so the square within {@link #SEES} is wiped to {@link #OPEN} first and then
     * stamped. That is what makes the record self-correcting: a wall that has been torn down stops
     * being recorded the next time anybody walks past, where the old union-forever store would
     * have insisted on it for ever.
     *
     * POSITIVE evidence - "this thing is standing here" - is worth something wherever it arrives,
     * so solids and gateways outside that square are stamped too, and nothing outside it is ever
     * cleared. Being told about a wall is useful even from further away than we would trust an
     * absence.
     */
    public static void observe(GameUI gui) {
        if ((gui == null) || (gui.map == null) || (gui.ui == null) || (gui.ui.sess == null))
            return;
        Gob me = gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if ((me == null) || (here == null))
            return;
        List<Gob> gobs = new ArrayList<>();
        try {
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob g : gui.ui.sess.glob.oc)
                    gobs.add(g);
            }
        } catch (RuntimeException e) {
            return;
        }

        /* Gateways collected first and stamped last. A wall segment's collision box overlaps its
         * own gate's, so whichever is written second wins - and in a single pass that was decided
         * by the order the object cache happened to hand the gobs over, which walled gates shut
         * with their own posts about half the time. */
        Set<Coord> solids = new HashSet<>(), walls = new HashSet<>(), gates = new HashSet<>();
        Coord2d off = here.sc.sub(me.rc);
        for (Gob g : gobs) {
            try {
                Resource res = g.getres();
                if ((res == null) || mobile(res.name))
                    continue;
                HitBoxes.CollisionBoxSecondary[] boxes = HitBoxes.collisionBoxMap.get(res.name);
                if (boxes == null)
                    continue;
                Barriers.Kind k = Barriers.kind(res.name);
                Set<Coord> into = (k == Barriers.Kind.GATE) ? gates
                    : (k == Barriers.Kind.WALL) ? walls : solids;
                into.addAll(footprint(g, off, boxes));
            } catch (RuntimeException e) {
                // Includes Loading: a gob whose resource has not arrived is picked up next sweep.
            }
        }

        Coord mine = here.sc.floor(MCache.tilesz);
        synchronized (LOCK) {
            load();
            for (int y = -SEES; y <= SEES; y++) {
                for (int x = -SEES; x <= SEES; x++)
                    set(here.seg, mine.add(x, y), OPEN);
            }
            /* Stamped weakest first. A wall segment's box overlaps its own gate's and a gate's
             * posts, so whichever is written last wins, and a gateway recorded as solid seals a
             * base for good. Furniture, then walls, then gateways. */
            for (Coord t : solids)
                set(here.seg, t, SOLID);
            for (Coord t : walls)
                set(here.seg, t, WALL);
            for (Coord t : gates)
                set(here.seg, t, GATE);
        }
    }

    /**
     * Things that walk about, and are therefore not the map.
     *
     * This is the one exception to "anything with a collision box is solid", and it has to be, in a
     * way the old name test hid: the check it replaced skipped only OUR OWN character, because
     * {@code isPlgob} means "is the gob the camera follows". Every other character, and every
     * animal, has a collision box - one of the two hundred and thirty-one this install knows is
     * {@code gfx/borka/body} and fourteen more are wildlife - so all of them were being written into
     * a PERSISTENT, SHARED record of where the walls are.
     *
     * That is worse than it sounds, and specifically worse for a crew. Inside sight the next sweep
     * wipes the mistake, so it looks harmless while you watch it; but the wipe only reaches
     * {@link #SEES}, so whatever a bot happened to be beside as it walked out of range froze there
     * for good, and got saved, and got shared. A crewmate standing at a barrel became a wall in
     * everybody's map. Hours of walking would silt the file up with ghosts of each other.
     *
     * Losing them costs nothing, because nothing wanted them here: wildlife is routed around by
     * {@link Hazards} at path time from live positions, and other characters by {@link Crowd},
     * both of which read the world as it is now rather than as it was remembered.
     *
     * Two kritter resources are placed objects rather than creatures, and those stay.
     */
    private static boolean mobile(String res) {
        if (res.startsWith("gfx/borka/"))
            return true;
        if (!res.startsWith("gfx/kritter/"))
            return false;
        return !(res.endsWith("/anthill") || res.endsWith("/wildbeehive"));
    }

    /**
     * Every segment tile a gob's collision box stands on.
     *
     * Floored from the box's ABSOLUTE position rather than by flooring the offsets and adding the
     * gob's own tile. {@code floor(a) + floor(b)} is not {@code floor(a + b)}, and for these boxes
     * it never is: a palisade segment's box is one tile square and centred on the gob, so its
     * offsets run -5.5 to +5.5, which floor to -1 and 0 - two tiles per axis, four tiles for a
     * wall one tile square. That doubled every wall, post and gate in the map and shifted it a
     * tile north-west, while still covering the real tile, which is why it looked plausible.
     *
     * The far edge is exclusive. A box spanning exactly one tile ends on a tile boundary, and a
     * boundary belongs to the next tile along.
     */
    private static Set<Coord> footprint(Gob g, Coord2d off, HitBoxes.CollisionBoxSecondary[] boxes) {
        Set<Coord> out = new HashSet<>();
        Coord2d pos = g.rc.add(off);
        out.add(pos.floor(MCache.tilesz));
        double cos = Math.cos(g.a), sin = Math.sin(g.a);
        for (HitBoxes.CollisionBoxSecondary box : boxes) {
            if ((box == null) || (box.coords == null) || (box.coords.length == 0))
                continue;
            double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
            double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
            for (Coord2d c : box.coords) {
                // The box is stored unrotated, in the gob's own frame.
                double rx = (c.x * cos) - (c.y * sin);
                double ry = (c.x * sin) + (c.y * cos);
                minx = Math.min(minx, rx);
                maxx = Math.max(maxx, rx);
                miny = Math.min(miny, ry);
                maxy = Math.max(maxy, ry);
            }
            Coord lo = pos.add(minx, miny).floor(MCache.tilesz);
            Coord hi = pos.add(maxx - EDGE, maxy - EDGE).floor(MCache.tilesz);
            if (((hi.x - lo.x) > MAX_SPAN) || ((hi.y - lo.y) > MAX_SPAN))
                continue;
            for (int y = lo.y; y <= hi.y; y++) {
                for (int x = lo.x; x <= hi.x; x++)
                    out.add(new Coord(x, y));
            }
        }
        return out;
    }

    /** Caller holds {@link #LOCK}. */
    private static void set(long seg, Coord segTile, byte v) {
        Coord gc = Terrain.floorDiv(segTile, MCache.cmaps);
        byte[] g = grid(seg, gc, true);
        Coord in = segTile.sub(gc.mul(MCache.cmaps));
        int i = (in.y * MCache.cmaps.x) + in.x;
        // Marked whether or not the value changed: having looked and found what we expected is
        // still having looked, and it is the looking that entitles us to overrule the file.
        mask(seg, gc).set(i);
        if (g[i] != v) {
            g[i] = v;
            dirty = true;
        }
    }

    /** Caller holds {@link #LOCK}. */
    private static BitSet mask(long seg, Coord gc) {
        return looked.computeIfAbsent(seg, k -> new HashMap<>())
            .computeIfAbsent(gc, k -> new BitSet(GRID));
    }

    /** Caller holds {@link #LOCK}. */
    private static byte[] grid(long seg, Coord gc, boolean make) {
        Map<Coord, byte[]> byseg = map.get(seg);
        if (byseg == null) {
            if (!make)
                return null;
            byseg = new HashMap<>();
            map.put(seg, byseg);
        }
        byte[] g = byseg.get(gc);
        if ((g == null) && make) {
            g = new byte[GRID];
            byseg.put(gc, g);
        }
        return g;
    }

    // ------------------------------------------------------------------ persistence

    private static Path file() {
        return Paths.get(FILE);
    }

    /** Caller holds {@link #LOCK}. */
    private static void load() {
        if (map != null)
            return;
        map = new HashMap<>();
        read(map);
    }

    private static boolean read(Map<Long, Map<Coord, byte[]>> into) {
        try {
            Path f = file();
            if (!Files.exists(f))
                return true;
            JSONObject root = new JSONObject(
                new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            if (root.optInt("v", 0) != VERSION)
                return true;   // an older shape; start again rather than misread it
            JSONArray segs = root.optJSONArray("segs");
            if (segs == null)
                return true;
            for (int i = 0; i < segs.length(); i++) {
                JSONObject so = segs.getJSONObject(i);
                long seg = so.getLong("seg");
                Map<Coord, byte[]> byseg = into.computeIfAbsent(seg, k -> new HashMap<>());
                JSONArray grids = so.optJSONArray("grids");
                if (grids == null)
                    continue;
                for (int j = 0; j < grids.length(); j++) {
                    JSONObject go = grids.getJSONObject(j);
                    JSONArray gc = go.getJSONArray("gc");
                    byte[] g = unrle(go.getJSONArray("rle"));
                    if (g != null)
                        byseg.put(new Coord(gc.getInt(0), gc.getInt(1)), g);
                }
            }
            return true;
        } catch (IOException e) {
            // Locked by another client mid-write, or a disk hiccup. Not grounds for treating what
            // is in the file as gone - see save().
            return false;
        } catch (RuntimeException e) {
            NLog.crash("parsing " + FILE, e);
            return true;
        }
    }

    /**
     * Reconciles with the file: takes the crew's word for everything we have not looked at
     * ourselves, then writes back if we have anything of our own to add.
     *
     * A crew is several clients, and the naive shape - load once at startup, own a copy, write it
     * out - is wrong in both directions at once. Outwards, a plain rewrite means the last to save
     * wins and everything the others saw is lost. Inwards, nobody ever learns anything after
     * startup, so two bots working the same base spend the session with two divergent maps of it.
     *
     * Merging on the value alone does not fix it, and the version that did ("anything beats
     * UNSEEN") had a failure mode worth naming, because it is the one that bites a crew hardest. A
     * client writes back every tile it holds, including the thousands it loaded at startup and has
     * not been near since. So when one bot walks past a wall that has come down and records the
     * ground as open, the next save by any OTHER client - which still holds the old wall, untouched,
     * from its own startup read - puts it straight back. The correction and the stale copy trade
     * places for as long as both clients run, and which one a route sees is down to who saved last.
     *
     * So the unit of authority is not the value, it is HAVING LOOKED. A client asserts only the
     * tiles it has observed this run; everything else it holds is a cache of the file and is
     * replaced by it. Two clients can only disagree about ground they have both looked at, and then
     * they are both current and either answer is right.
     *
     * Sharing falls out of the same move: adopting the file on every pass means one bot's discovery
     * is in every crewmate's router within a few seconds, without a protocol, a server, or anything
     * to keep in sync.
     */
    private static void save() {
        synchronized (LOCK) {
            if (map == null)
                return;
            /* Re-reading a file nobody has touched, to merge in what it already told us, is the
             * one thing here that could cost real time - it happens every few seconds for the
             * length of a session. The modification time settles it for nothing. */
            long stamp = stamp();
            if (!dirty && (stamp != UNKNOWN) && (stamp == fileAt))
                return;

            Map<Long, Map<Coord, byte[]>> disk = new HashMap<>();
            if (!read(disk))
                return;    // cannot see what we would be overwriting, so do not. Still dirty.
            fileAt = stamp;
            for (Map.Entry<Long, Map<Coord, byte[]>> se : disk.entrySet()) {
                Map<Coord, byte[]> ours = map.computeIfAbsent(se.getKey(), k -> new HashMap<>());
                for (Map.Entry<Coord, byte[]> ge : se.getValue().entrySet()) {
                    byte[] theirs = ge.getValue();
                    byte[] mine = ours.get(ge.getKey());
                    if (mine == null) {
                        ours.put(ge.getKey(), theirs);
                        continue;
                    }
                    BitSet seen = mask(se.getKey(), ge.getKey());
                    for (int i = 0; i < mine.length; i++) {
                        if (!seen.get(i))
                            mine[i] = theirs[i];
                    }
                }
            }
            if (!dirty)
                return;    // we came to listen; there was nothing of ours to say
            try {
                JSONArray segs = new JSONArray();
                for (Map.Entry<Long, Map<Coord, byte[]>> se : map.entrySet()) {
                    JSONArray grids = new JSONArray();
                    for (Map.Entry<Coord, byte[]> ge : se.getValue().entrySet()) {
                        JSONObject go = new JSONObject();
                        go.put("gc", new JSONArray(new int[] {ge.getKey().x, ge.getKey().y}));
                        go.put("rle", rle(ge.getValue()));
                        grids.put(go);
                    }
                    JSONObject so = new JSONObject();
                    so.put("seg", se.getKey());
                    so.put("grids", grids);
                    segs.put(so);
                }
                JSONObject root = new JSONObject();
                root.put("v", VERSION);
                root.put("segs", segs);
                Path dst = file();
                Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
                Files.write(tmp, root.toString().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty = false;
                // Our own write moved it on; without this every pass would re-read what we wrote.
                fileAt = stamp();
            } catch (IOException | RuntimeException e) {
                NLog.crash("saving " + FILE, e);
            }
        }
    }

    /** A modification time that can't be asked for. Never equal to the last one, so never skipped. */
    private static final long UNKNOWN = Long.MIN_VALUE;

    /** When the file last changed, -1 if there isn't one, {@link #UNKNOWN} if it can't be told. */
    private static long stamp() {
        try {
            Path f = file();
            return Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : -1;
        } catch (IOException | RuntimeException e) {
            return UNKNOWN;
        }
    }

    /**
     * Run-length encoded, because a grid is ten thousand tiles and almost all of them say the same
     * thing as the one before. A base with walls through it comes out as a few hundred numbers.
     */
    private static JSONArray rle(byte[] g) {
        JSONArray out = new JSONArray();
        int i = 0;
        while (i < g.length) {
            int j = i;
            while ((j < g.length) && (g[j] == g[i]))
                j++;
            out.put((int) g[i]);
            out.put(j - i);
            i = j;
        }
        return out;
    }

    private static byte[] unrle(JSONArray a) {
        if (a == null)
            return null;
        byte[] g = new byte[GRID];
        int at = 0;
        for (int i = 0; (i + 1) < a.length(); i += 2) {
            byte v = (byte) a.getInt(i);
            int n = a.getInt(i + 1);
            for (int k = 0; (k < n) && (at < g.length); k++)
                g[at++] = v;
        }
        return g;
    }

    /**
     * Forgets everything seen. For when a base has been torn down and rebuilt.
     *
     * Clears what we have looked at as well, and it has to: keeping the mask would leave us
     * asserting a blank map over ground we no longer hold any observation of, which would wipe the
     * file for every other client too. Dropping both means the next pass adopts whatever the crew
     * has and we start over from what we can actually see.
     */
    public static void reset() {
        synchronized (LOCK) {
            map = new HashMap<>();
            looked = new HashMap<>();
            fileAt = UNKNOWN;
            dirty = true;
        }
        save();
    }
}
