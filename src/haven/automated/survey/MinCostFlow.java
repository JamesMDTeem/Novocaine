package haven.automated.survey;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Minimum-cost flow by successive shortest paths, sized for a lattice of surveys.
 *
 * Bellman-Ford rather than Dijkstra because the residual graph carries negative costs on its
 * reverse edges, and with a handful of nodes the difference does not matter. Every augmentation
 * saturates one of the source or sink edges, so it finishes in at most a couple of dozen rounds.
 *
 * <p>Capacities and costs are doubles because the quantities here are soil volumes in raw client z,
 * not counts. {@link #INF} stands in for an uncapacitated edge; it is only ever compared and
 * subtracted from, never added to another capacity, so a merely enormous value is safe.
 *
 * <p>Edges are numbered in the order they were added, and {@link #flowOn} reads a single edge's
 * flow back out by that number. The planner needs it: the cost alone says what the carrying is
 * worth, and only the individual flows say which surplus actually feeds which shortfall.
 */
public class MinCostFlow {
    public static final double INF = 1e18;

    private final int[] head, nxt, dst;
    private final double[] cap, cst;
    private int cnt = 0;

    /** @param edges the number of {@link #edge} calls, not the number of directed entries. */
    public MinCostFlow(int nodes, int edges) {
        head = new int[nodes];
        Arrays.fill(head, -1);
        nxt = new int[edges * 2];
        dst = new int[edges * 2];
        cap = new double[edges * 2];
        cst = new double[edges * 2];
    }

    public void edge(int u, int v, double c, double w) {
        dst[cnt] = v; cap[cnt] = c; cst[cnt] = w; nxt[cnt] = head[u]; head[u] = cnt++;
        dst[cnt] = u; cap[cnt] = 0; cst[cnt] = -w; nxt[cnt] = head[v]; head[v] = cnt++;
    }

    /**
     * How much flow ended up on the edge added by the n-th {@link #edge} call.
     *
     * Paired forward and reverse entries sit at 2n and 2n+1, and the reverse entry accumulates
     * exactly what was pushed forward, so it is the answer with no extra bookkeeping.
     */
    public double flowOn(int n) {
        return cap[(n * 2) ^ 1];
    }

    public int edgeCount() {
        return cnt / 2;
    }

    /** Total cost of a maximum flow from s to t. */
    public double mincost(int s, int t) {
        int n = head.length;
        double total = 0;
        double[] dist = new double[n];
        int[] pe = new int[n];
        boolean[] inq = new boolean[n];
        while (true) {
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            Arrays.fill(pe, -1);
            Arrays.fill(inq, false);
            dist[s] = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(s);
            inq[s] = true;
            while (!q.isEmpty()) {
                int u = q.poll();
                inq[u] = false;
                for (int e = head[u]; e != -1; e = nxt[e]) {
                    if (cap[e] <= 1e-9)
                        continue;
                    int v = dst[e];
                    double nd = dist[u] + cst[e];
                    if (nd < dist[v] - 1e-9) {
                        dist[v] = nd;
                        pe[v] = e;
                        if (!inq[v]) {
                            inq[v] = true;
                            q.add(v);
                        }
                    }
                }
            }
            if (pe[t] < 0)
                return total;
            double push = Double.POSITIVE_INFINITY;
            for (int v = t; v != s; v = dst[pe[v] ^ 1])
                push = Math.min(push, cap[pe[v]]);
            for (int v = t; v != s; v = dst[pe[v] ^ 1]) {
                cap[pe[v]] -= push;
                cap[pe[v] ^ 1] += push;
            }
            total += push * dist[t];
        }
    }
}
