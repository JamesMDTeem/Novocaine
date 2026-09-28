import haven.automated.combat.Prediction;

import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * What one live-advice plan costs, in time and memory, against crowds of increasing size.
 *
 *   javac -nowarn -d OUT -sourcepath src tools\PlanCost.java
 *   (copy data\combat\*.json into OUT\haven\combat\data)
 *   java -cp OUT PlanCost
 *
 * NOT PART OF ANY DEFAULT RUN. For judging a change to the planner's cost: it prints the answer
 * beside the cost, so a faster planner can be shown to be the SAME planner (2026-09-27, the
 * copy-on-write step in Optimizer). The allocation figure is the planning thread's own
 * (com.sun.management.ThreadMXBean), median of the timed repeats after warm-up.
 */
public class PlanCost {
    public static void main(String[] args) {
        SortedMap<String, Integer> attrs = new TreeMap<>();
        attrs.put("str", 300);
        attrs.put("agi", 300);
        attrs.put("unarmed", 150);
        attrs.put("melee", 300);
        Map<String, Integer> deck = new LinkedHashMap<>();
        for(String c : new String[] {"barrage:5", "fullcircle:4", "cleave:1", "qdodge:5", "sidestep:5", "zigzag:5", "shield:1"})
            deck.put("paginae/atk/" + c.split(":")[0], Integer.parseInt(c.split(":")[1]));
        Prediction.Me me = Prediction.me(attrs, 150, 110, new String[] {"gfx/invobjs/small/hirdsword", "gfx/invobjs/small/roundshield"},
                                         new double[] {100, 30}, deck).holding(new String[] {"paginae/atk/shield"}, true);
        com.sun.management.ThreadMXBean tb = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        String[][] cases = {{"gfx/kritter/bat/bat", "1"}, {"gfx/kritter/bat/bat", "3"}, {"gfx/kritter/bat/bat", "8"},
                            {"gfx/kritter/cavelouse/cavelouse", "3"}};
        for(String[] c : cases) {
            int n = Integer.parseInt(c[1]);
            List<Prediction.Seen> foes = new ArrayList<>();
            for(int i = 0; i < n; i++)
                foes.add(new Prediction.Seen(i + 1, c[0], new int[] {10 * (i % 3), 0, 0, 20}, 0, 0, 6 + i, 0, null, true));
            int[] mine = {25, 10, 0, 30};
            Prediction.Live l = null;
            for(int w = 0; w < 3; w++)
                l = Prediction.adviseLive(me, null, mine, 400, 450, foes, 60, 2500);
            long[] ms = new long[5], by = new long[5];
            for(int r = 0; r < 5; r++) {
                long b0 = tb.getThreadAllocatedBytes(Thread.currentThread().getId());
                long t0 = System.nanoTime();
                l = Prediction.adviseLive(me, null, mine, 400, 450, foes, 60, 2500);
                ms[r] = (System.nanoTime() - t0) / 1000000;
                by[r] = tb.getThreadAllocatedBytes(Thread.currentThread().getId()) - b0;
            }
            Arrays.sort(ms);
            Arrays.sort(by);
            System.out.printf("%d x %-10s %5d ms %7.1f MB   -> %s, %d ticks, %.2f hp (%s)%n", n,
                              c[0].substring(c[0].lastIndexOf('/') + 1), ms[2], by[2] / 1e6, l.moveRes, l.ticks, l.hpLost, l.why);
        }
    }
}
