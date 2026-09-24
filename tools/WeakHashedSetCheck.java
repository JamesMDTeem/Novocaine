/*
 * WeakHashedSet after its slots were given a mixed hash and its shrink was fixed.
 *
 * Checks that every live element is still found through growth, deaths, the bounded reap and the
 * (now working) shrink; that intern() still returns one canonical object per equal value; and that
 * a hash whose low bits are all equal - the worst case for the old raw-bit indexing - no longer
 * builds long probe runs. Run from the repo root (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;lib\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\whscheck tools\WeakHashedSetCheck.java
 *   java -cp "$env:TEMP\whscheck;$CP" haven.WeakHashedSetCheck
 */
package haven;

import java.lang.reflect.Field;
import java.util.*;

public class WeakHashedSetCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    /** A value whose hash has its low 12 bits always zero. */
    static final class Clustered {
        final int v;

        Clustered(int v) {this.v = v;}

        public int hashCode() {return(v << 12);}

        public boolean equals(Object o) {return((o instanceof Clustered) && (((Clustered)o).v == v));}
    }

    static int tablen(WeakHashedSet<?> s) throws Exception {
        Field f = WeakHashedSet.class.getDeclaredField("tab");
        f.setAccessible(true);
        return(((Object[])f.get(s)).length);
    }

    static int longestRun(WeakHashedSet<?> s) throws Exception {
        Field f = WeakHashedSet.class.getDeclaredField("tab");
        f.setAccessible(true);
        Object[] tab = (Object[])f.get(s);
        int best = 0, cur = 0;
        for(int i = 0; i < tab.length * 2; i++) {
            if(tab[i % tab.length] != null) {
                cur++;
                best = Math.max(best, cur);
            } else {
                cur = 0;
            }
        }
        return(Math.min(best, tab.length));
    }

    public static void main(String[] args) throws Exception {
        int n = 50000;
        WeakHashedSet<Clustered> set = new WeakHashedSet<>(Hash.eq);
        List<Clustered> hold = new ArrayList<>();
        long t0 = System.nanoTime();
        for(int i = 0; i < n; i++) {
            Clustered c = new Clustered(i);
            hold.add(c);
            set.add(c);
        }
        long tadd = System.nanoTime() - t0;
        int found = 0;
        t0 = System.nanoTime();
        for(int i = 0; i < n; i++)
            if(set.find(new Clustered(i)) == hold.get(i))
                found++;
        long tfind = System.nanoTime() - t0;
        check(found == n, "every element is found by an equal value (" + found + "/" + n + ")");
        int run = longestRun(set);
        System.out.printf("  %d adds %.0f ms, %d finds %.0f ms, longest run %d in %d slots%n",
                          n, tadd / 1e6, n, tfind / 1e6, run, tablen(set));
        check(run < 64, "a hash with 12 equal low bits builds no long probe run (" + run + ")");

        Clustered again = set.intern(new Clustered(123));
        check(again == hold.get(123), "intern() returns the canonical object");

        /* kill two thirds, let the reaper catch up through ordinary traffic */
        List<Clustered> keep = new ArrayList<>();
        for(int i = 0; i < n; i++)
            if((i % 3) == 0)
                keep.add(hold.get(i));
        hold = null;
        int big = tablen(set);
        for(int round = 0; round < 20; round++) {
            System.gc();
            Thread.sleep(50);
            for(int i = 0; i < 2000; i++) {
                Clustered tmp = new Clustered(1_000_000 + i);
                set.add(tmp);
                set.remove(tmp);
            }
        }
        found = 0;
        for(Clustered c : keep)
            if(set.find(new Clustered(c.v)) == c)
                found++;
        check(found == keep.size(), "the survivors are all still found after reaping (" + found + "/" + keep.size() + ")");
        check(set.size() == keep.size(), "size counts only the survivors once reaped (" + set.size() + " vs " + keep.size() + ")");
        keep.subList(1, keep.size()).clear();
        for(int round = 0; round < 40; round++) {
            System.gc();
            Thread.sleep(50);
            for(int i = 0; i < 2000; i++) {
                Clustered tmp = new Clustered(2_000_000 + i);
                set.add(tmp);
                set.remove(tmp);
            }
        }
        check(tablen(set) < big, "the table shrinks once nearly everything has died (" + big + " -> " + tablen(set) + ")");
        check(set.find(new Clustered(keep.get(0).v)) == keep.get(0), "and the last survivor is still found");

        /* random churn against a reference set */
        Random r = new Random(7);
        WeakHashedSet<Clustered> s2 = new WeakHashedSet<>(Hash.eq);
        Map<Integer, Clustered> ref = new HashMap<>();
        boolean ok = true;
        for(int i = 0; i < 200000; i++) {
            int v = r.nextInt(5000);
            if(r.nextInt(3) == 0) {
                Clustered c = ref.remove(v);
                if(c != null && !s2.remove(c))
                    ok = false;
            } else {
                Clustered c = ref.computeIfAbsent(v, Clustered::new);
                if(s2.intern(c) != c)
                    ok = false;
            }
        }
        for(Clustered c : ref.values())
            if(s2.find(new Clustered(c.v)) != c)
                ok = false;
        check(ok, "200,000 random interns and removes agree with a HashMap");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
