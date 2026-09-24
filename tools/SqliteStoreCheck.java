/*
 * The SQLite map/resource store as an overlay on the old file store.
 *
 * What has to hold for switching to be safe on a real install:
 *   - everything in the old store reads back byte for byte through the overlay, and is copied in;
 *   - a write lands in SQLite and never in the old store;
 *   - a zero-length entry (MapFile's segment tombstone, a zoom-grid invalidation) reads back as
 *     zero bytes, not as the old store's older value;
 *   - a store stream that is never closed writes nothing;
 *   - the background carry-over copies every map entry, and never overwrites a newer one;
 *   - a real MapFile loads through the overlay: the same segments and the same grids;
 *   - two clients on one file do not trip over each other.
 * It also times a read from each store.
 *
 * It reads the REAL map folder under %APPDATA% and never writes to it: the overlay only ever
 * reads the old store. The SQLite files go to a temporary folder. Run from the repo root
 * (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\sqlcheck tools\SqliteStoreCheck.java
 *   java -cp "$env:TEMP\sqlcheck;$CP" haven.SqliteStoreCheck [sample]
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven;

import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SqliteStoreCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    static SqliteCache open(Path file, ResCache legacy) throws Exception {
        Constructor<SqliteCache> c = SqliteCache.class.getDeclaredConstructor(Path.class, ResCache.class);
        c.setAccessible(true);
        try {
            return c.newInstance(file, legacy);
        } catch(InvocationTargetException e) {
            throw (Exception)e.getCause();
        }
    }

    static byte[] read(ResCache c, String name) throws IOException {
        try(InputStream in = c.fetch(name)) {
            return in.readAllBytes();
        }
    }

    static void write(ResCache c, String name, byte[] data) throws IOException {
        try(OutputStream out = c.store(name)) {
            out.write(data);
        }
    }

    public static void main(String[] args) throws Exception {
        int sample = (args.length > 0) ? Integer.parseInt(args[0]) : 3000;
        Path tmp = Files.createTempDirectory("sqlcheck");
        HashDirCache old = HashDirCache.get(URI.create("http://game.havenandhearth.com/java/"));

        System.out.println("listing the old map store...");
        long t0 = System.nanoTime();
        List<String> names = new ArrayList<>();
        for(Iterator<String> i = old.list(); i.hasNext();) {
            String n = i.next();
            if(n.startsWith("map/"))
                names.add(n);
        }
        System.out.printf("  %d map entries in %.1fs%n", names.size(), (System.nanoTime() - t0) / 1e9);
        check(names.size() > 0, "the old store has a map to carry over");
        Collections.shuffle(names, new Random(1));
        List<String> some = names.subList(0, Math.min(sample, names.size()));

        /* 1. read-through, byte for byte, and copied in */
        SqliteCache sq = open(tmp.resolve("map.sqlite"), old);
        int same = 0;
        long told = 0, tnew = 0;
        for(String n : some) {
            long a = System.nanoTime();
            byte[] want = read(old, n);
            long b = System.nanoTime();
            byte[] got = read(sq, n);
            told += b - a;
            if(Arrays.equals(want, got))
                same++;
        }
        check(same == some.size(), "every sampled entry reads back through the overlay byte for byte (" + same + "/" + some.size() + ")");
        int held = 0;
        Method get = SqliteCache.class.getDeclaredMethod("get", String.class);
        get.setAccessible(true);
        for(String n : some)
            if(get.invoke(sq, n) != null)
                held++;
        check(held == some.size(), "and each one was copied into SQLite (" + held + ")");
        for(String n : some) {
            long a = System.nanoTime();
            read(sq, n);
            tnew += System.nanoTime() - a;
        }
        System.out.printf("  mean read: old store %.0f us, SQLite %.0f us%n", told / 1e3 / some.size(), tnew / 1e3 / some.size());

        /* 2. writes land in SQLite only */
        String fresh = "map/sqlcheck-" + System.nanoTime() + "/grid-1";
        write(sq, fresh, new byte[] {1, 2, 3});
        boolean oldMiss;
        try {
            read(old, fresh);
            oldMiss = false;
        } catch(FileNotFoundException e) {
            oldMiss = true;
        }
        check(oldMiss, "a write never reaches the old store");
        check(Arrays.equals(read(sq, fresh), new byte[] {1, 2, 3}), "and reads back from SQLite");

        /* 3. a tombstone over an entry the old store still has */
        String victim = names.get(names.size() - 1);
        check(read(old, victim).length > 0, "(the old store has a non-empty " + victim + ")");
        sq.store(victim).close();
        check(read(sq, victim).length == 0, "a zero-length entry reads back as zero bytes, not the old value");

        /* 4. an unclosed stream writes nothing */
        String aborted = "map/sqlcheck/aborted";
        OutputStream half = sq.store(aborted);
        half.write(new byte[] {9, 9});
        boolean miss;
        try {
            read(sq, aborted);
            miss = false;
        } catch(FileNotFoundException e) {
            miss = true;
        }
        check(miss, "a store stream never closed writes nothing");

        /* 5. a miss everywhere is FileNotFoundException */
        try {
            read(sq, "map/sqlcheck/nowhere");
            check(false, "a name in neither store is a miss");
        } catch(FileNotFoundException e) {
            check(true, "a name in neither store is a miss");
        }

        /* 6. the carry-over */
        byte[] mine = {7, 7, 7};
        String kept = names.get(names.size() - 2);
        write(sq, kept, mine);
        Method migrate = SqliteCache.class.getDeclaredMethod("migrate", HashDirCache.class);
        migrate.setAccessible(true);
        Field mig = SqliteCache.class.getDeclaredField("migration");
        mig.setAccessible(true);
        t0 = System.nanoTime();
        migrate.invoke(sq, old);
        while(!String.valueOf(mig.get(sq)).startsWith("done") && !String.valueOf(mig.get(sq)).startsWith("failed"))
            Thread.sleep(500);
        System.out.printf("  carry-over: %s (%.1fs)%n", mig.get(sq), (System.nanoTime() - t0) / 1e9);
        int copied = 0;
        for(String n : names)
            if(get.invoke(sq, n) != null)
                copied++;
        check(copied == names.size(), "the carry-over copied every map entry (" + copied + "/" + names.size() + ")");
        check(Arrays.equals(read(sq, kept), mine), "without overwriting one written since");
        check(read(sq, victim).length == 0, "or a tombstone written since");
        SqliteCache again = open(tmp.resolve("map.sqlite"), old);
        migrate.invoke(again, old);
        check(String.valueOf(mig.get(again)).startsWith("done"), "and it does not run a second time");

        /* 7. a real MapFile through the overlay */
        Set<String> worlds = new TreeSet<>();
        for(String n : names) {
            int e = n.indexOf('/', 4);
            if(e > 4)
                worlds.add(n.substring(4, e));
        }
        for(String w : worlds) {
            SqliteCache fresh2 = open(tmp.resolve("world-" + w.hashCode() + ".sqlite"), old);
            MapFile viaNew = MapFile.load(fresh2, w);
            Set<Long> want = new HashSet<>();
            try(StreamMessage data = new StreamMessage(old.fetch("map/" + w + "/index"))) {
                int ver = data.uint8();
                if(ver == 1) {
                    for(int i = 0, no = data.int32(); i < no; i++)
                        want.add(data.int64());
                }
            } catch(FileNotFoundException e) {
                continue;
            }
            check(new HashSet<>(viaNew.knownsegs).equals(want), "world " + w + ": the index loads through the overlay (" + want.size() + " segments)");
            int grids = 0, gridsOk = 0;
            viaNew.lock.readLock().lock();
            try {
                for(Long sid : want) {
                    MapFile.Segment seg = viaNew.segments.get(sid);
                    if(seg == null)
                        continue;
                    for(Long gid : new ArrayList<>(seg.map.values())) {
                        if(grids >= 200)
                            break;
                        grids++;
                        if(Arrays.equals(read(old, "map/" + w + "/grid-" + Long.toHexString(gid)),
                                         read(fresh2, "map/" + w + "/grid-" + Long.toHexString(gid))))
                            gridsOk++;
                    }
                }
            } finally {
                viaNew.lock.readLock().unlock();
            }
            check(grids == gridsOk, "world " + w + ": its grids read back identically (" + gridsOk + "/" + grids + ")");
        }

        /* 8. two clients on one file */
        SqliteCache a = open(tmp.resolve("shared.sqlite"), null), b = open(tmp.resolve("shared.sqlite"), null);
        AtomicInteger errors = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(8);
        long end = System.currentTimeMillis() + 3000;
        for(int t = 0; t < 8; t++) {
            final SqliteCache c = ((t % 2) == 0) ? a : b;
            final int id = t;
            ex.submit(() -> {
                Random r = new Random(id);
                while(System.currentTimeMillis() < end) {
                    String n = "map/w/grid-" + r.nextInt(500);
                    try {
                        if(r.nextBoolean())
                            write(c, n, new byte[1 + r.nextInt(2000)]);
                        else
                            read(c, n);
                    } catch(FileNotFoundException e) {
                    } catch(Exception e) {
                        errors.incrementAndGet();
                        System.out.println("  " + e);
                    }
                }
            });
        }
        ex.shutdown();
        ex.awaitTermination(30, TimeUnit.SECONDS);
        check(errors.get() == 0, "two clients reading and writing one file for 3s (" + errors.get() + " errors)");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
