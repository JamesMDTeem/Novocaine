/*
 * Checks haven.automated.invsort — the pure half of the inventory sorter.
 *
 * NOT part of the client build: build.xml compiles src/ only, so a test class living here can
 * never end up in a shipped jar. Run on demand:
 *
 *   javac -d %TEMP%\invsort src\haven\automated\invsort\*.java tools\InvSortCheck.java
 *   java -cp %TEMP%\invsort InvSortCheck
 *
 * The important part is not the fixed cases but replay: every plan is executed against a model of
 * the server's own take and drop rules, and two things are asserted — that no operation would be
 * refused, and that the inventory actually ends up sorted. The second matters most. An earlier
 * draft passed replay on every trial simply because a plan that moves nothing is never refused.
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import haven.automated.invsort.InvLayout;
import haven.automated.invsort.InvPlan;
import haven.automated.invsort.InvSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class InvSortCheck {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? want == null : got.equals(want);
        System.out.printf("  %-52s %-12s %s%n", what, got, ok ? "ok" : "WANT " + want);
        if (!ok)
            failures++;
    }

    // -- a model of what the server will accept -------------------------------------------------

    /**
     * Replays a plan. Throws on anything the server would refuse: taking with a full cursor,
     * dropping with an empty one, dropping out of bounds or onto a masked cell, dropping a
     * multi-tile item onto occupied space, or any swap involving a multi-tile item.
     */
    static class Server {
        final InvSnapshot snap;
        final int[][] grid;
        final int[] cx, cy;
        int held = -1;

        Server(InvSnapshot snap, int n) {
            this.snap = snap;
            grid = new int[snap.width][snap.height];
            for (int x = 0; x < snap.width; x++) {
                for (int y = 0; y < snap.height; y++)
                    grid[x][y] = -1;
            }
            cx = new int[n];
            cy = new int[n];
            for (InvSnapshot.Piece p : snap.pieces) {
                cx[p.id] = p.x;
                cy[p.id] = p.y;
                fill(p.x, p.y, p.w, p.h, p.id);
            }
        }

        void fill(int x, int y, int w, int h, int v) {
            for (int dx = 0; dx < w; dx++) {
                for (int dy = 0; dy < h; dy++)
                    grid[x + dx][y + dy] = v;
            }
        }

        void apply(InvPlan.Op op) {
            if (op.kind == InvPlan.TAKE) {
                if (held >= 0)
                    throw new IllegalStateException("take with a full cursor");
                InvSnapshot.Piece p = snap.byId(op.piece);
                if (grid[cx[op.piece]][cy[op.piece]] != op.piece)
                    throw new IllegalStateException("take of an item that is not where the plan thinks");
                fill(cx[op.piece], cy[op.piece], p.w, p.h, -1);
                held = op.piece;
                return;
            }
            if (held < 0)
                throw new IllegalStateException("drop with an empty cursor");
            InvSnapshot.Piece p = snap.byId(held);
            int occupant = -1;
            for (int dx = 0; dx < p.w; dx++) {
                for (int dy = 0; dy < p.h; dy++) {
                    int x = op.x + dx, y = op.y + dy;
                    if (x < 0 || y < 0 || x >= snap.width || y >= snap.height)
                        throw new IllegalStateException("drop out of bounds");
                    if (snap.masked(x, y))
                        throw new IllegalStateException("drop onto a masked cell");
                    int id = grid[x][y];
                    if (id >= 0) {
                        if (occupant >= 0 && occupant != id)
                            throw new IllegalStateException("drop overlapping two items");
                        occupant = id;
                    }
                }
            }
            if (occupant < 0) {
                fill(op.x, op.y, p.w, p.h, held);
                cx[held] = op.x;
                cy[held] = op.y;
                held = -1;
                return;
            }
            // A swap. The server is only known to do this for 1x1 onto 1x1.
            if (!p.single() || !snap.byId(occupant).single())
                throw new IllegalStateException("swap involving a multi-tile item");
            fill(cx[occupant], cy[occupant], 1, 1, -1);
            fill(op.x, op.y, 1, 1, held);
            cx[held] = op.x;
            cy[held] = op.y;
            held = occupant;
        }
    }

    /** Runs a plan and returns the resulting server state, or fails loudly. */
    static Server replay(InvSnapshot snap, InvPlan.Plan plan) {
        Server s = new Server(snap, plan.targets.length);
        for (InvPlan.Op op : plan.ops)
            s.apply(op);
        if (s.held >= 0)
            throw new IllegalStateException("plan ended with an item on the cursor");
        return s;
    }

    /** Every piece that was not pinned must have ended up on its target. */
    static boolean sorted(InvSnapshot snap, InvPlan.Plan plan, Server s) {
        for (InvSnapshot.Piece p : snap.pieces) {
            if (plan.pinned.contains(p.id))
                continue;
            InvLayout.Spot t = plan.targets[p.id];
            if (s.cx[p.id] != t.x || s.cy[p.id] != t.y)
                return false;
        }
        return true;
    }

    static boolean layoutSane(InvSnapshot snap, InvLayout.Spot[] targets) {
        int[][] seen = new int[snap.width][snap.height];
        for (int[] row : seen)
            java.util.Arrays.fill(row, -1);
        for (InvSnapshot.Piece p : snap.pieces) {
            InvLayout.Spot t = targets[p.id];
            for (int dx = 0; dx < p.w; dx++) {
                for (int dy = 0; dy < p.h; dy++) {
                    int x = t.x + dx, y = t.y + dy;
                    if (x < 0 || y < 0 || x >= snap.width || y >= snap.height)
                        return false;
                    if (snap.masked(x, y) || seen[x][y] >= 0)
                        return false;
                    seen[x][y] = p.id;
                }
            }
        }
        return true;
    }

    // -- building snapshots ---------------------------------------------------------------------

    static InvSnapshot grid(int w, int h, boolean[] mask, int[][] spec) {
        List<InvSnapshot.Piece> ps = new ArrayList<>();
        for (int i = 0; i < spec.length; i++)
            ps.add(new InvSnapshot.Piece(i, spec[i][0], spec[i][1], spec[i][2], spec[i][3]));
        return new InvSnapshot(w, h, mask, ps);
    }

    public static void main(String[] args) {
        fixedCases();
        regressions();
        sweep();
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void fixedCases() {
        System.out.println("layout and plan basics");

        // Two 1x1s in swapped positions: the textbook permutation cycle.
        InvSnapshot swap = grid(2, 1, null, new int[][] {{1, 1, 1, 0}, {1, 1, 0, 0}});
        InvPlan.Plan p = InvPlan.compute(swap);
        check("swapped pair: layout sane", layoutSane(swap, p.targets), true);
        Server s = replay(swap, p);
        check("swapped pair: replays legally", true, true);
        check("swapped pair: ends sorted", sorted(swap, p, s), true);
        check("swapped pair: nothing pinned", p.pinned.size(), 0);

        // A 2x2 that has to displace 1x1s sitting on its destination.
        InvSnapshot big = grid(4, 4, null, new int[][] {
            {1, 1, 0, 0}, {1, 1, 1, 0}, {2, 2, 2, 2}});
        InvPlan.Plan bp = InvPlan.compute(big);
        check("2x2 through 1x1s: layout sane", layoutSane(big, bp.targets), true);
        Server bs = replay(big, bp);
        check("2x2 through 1x1s: ends sorted", sorted(big, bp, bs), true);

        // Already in order: a plan that moves nothing at all.
        InvSnapshot done = grid(2, 1, null, new int[][] {{1, 1, 0, 0}, {1, 1, 1, 0}});
        InvPlan.Plan dp = InvPlan.compute(done);
        check("already sorted: emits no operations", dp.ops.size(), 0);

        // Empty and single-item grids.
        InvSnapshot empty = grid(3, 3, null, new int[][] {});
        check("empty inventory: no operations", InvPlan.compute(empty).ops.size(), 0);

        // Masked cells must never be used.
        boolean[] mask = new boolean[4];
        mask[0] = true;
        InvSnapshot masked = grid(2, 2, mask, new int[][] {{1, 1, 1, 0}, {1, 1, 0, 1}});
        InvPlan.Plan mp = InvPlan.compute(masked);
        check("masked cell: layout avoids it", layoutSane(masked, mp.targets), true);
        Server ms = replay(masked, mp);
        check("masked cell: replays legally", true, true);
        check("masked cell: ends sorted", sorted(masked, mp, ms), true);
    }

    static void regressions() {
        System.out.println("\nthe four bugs, each pinned to a case");

        /* 1. findFreeCell only ever returned a cell from a row's leading free prefix, so a grid
         *    whose every row starts occupied reported "inventory too full". */
        InvSnapshot prefix = grid(3, 3, null, new int[][] {
            {1, 1, 0, 0}, {1, 1, 0, 1}, {1, 1, 0, 2}, {2, 2, 1, 0}});
        InvPlan.Plan pp = InvPlan.compute(prefix);
        Server ps = replay(prefix, pp);
        check("every row starts occupied: still sorts", sorted(prefix, pp, ps), true);
        check("every row starts occupied: nothing pinned", pp.pinned.size(), 0);

        /* 2. Targets assigned in display order let the 1x1s fragment the grid before the large
         *    item was placed. Largest-first must keep a packing that demonstrably exists. */
        InvSnapshot frag = grid(4, 3, null, new int[][] {
            {1, 1, 0, 0}, {1, 1, 1, 0}, {1, 1, 2, 0}, {1, 1, 3, 0}, {2, 2, 0, 1}});
        InvPlan.Plan fp = InvPlan.compute(frag);
        check("large item still gets placed", fp.pinned.contains(4), false);
        check("fragmentation case: layout sane", layoutSane(frag, fp.targets), true);
        Server fs = replay(frag, fp);
        check("fragmentation case: ends sorted", sorted(frag, fp, fs), true);

        /* 3 & 4. A three-cycle among 1x1s - the shape whose chain ran forever, flooding the
         *        server, because nothing marked a piece as already placed. */
        InvSnapshot cycle = grid(3, 1, null, new int[][] {
            {1, 1, 1, 0}, {1, 1, 2, 0}, {1, 1, 0, 0}});
        InvPlan.Plan cp = InvPlan.compute(cycle);
        check("three-cycle: operation count is bounded", cp.ops.size() <= 8, true);
        Server cs = replay(cycle, cp);
        check("three-cycle: ends sorted", sorted(cycle, cp, cs), true);

        /* A completely full grid needing a permutation: the case that has no free cell to
         * manoeuvre through and can only be solved by 1x1 swap chains. */
        InvSnapshot full = grid(2, 2, null, new int[][] {
            {1, 1, 1, 1}, {1, 1, 0, 1}, {1, 1, 1, 0}, {1, 1, 0, 0}});
        InvPlan.Plan flp = InvPlan.compute(full);
        Server fls = replay(full, flp);
        check("packed grid: ends sorted", sorted(full, flp, fls), true);
        check("packed grid: nothing pinned", flp.pinned.size(), 0);
    }

    /**
     * Random layouts, replayed. Covers the shapes no hand-written case thinks of — and asserts the
     * plan sorts, not merely that it is legal.
     */
    static void sweep() {
        System.out.println("\nrandomised sweep");
        int trials = 0, sortedOk = 0, pinnedTotal = 0;
        int singlesOnlyTrials = 0, singlesOnlyPinned = 0, trialsWithAnyPin = 0;
        Random rnd = new Random(20260815L);
        StringBuilder firstFailure = new StringBuilder();

        for (int t = 0; t < 4000; t++) {
            int w = 2 + rnd.nextInt(6), h = 2 + rnd.nextInt(6);
            boolean[] mask = new boolean[w * h];
            if (rnd.nextInt(4) == 0) {
                for (int i = 0; i < mask.length; i++)
                    mask[i] = rnd.nextInt(8) == 0;
            }
            // Place random items into free space so the starting layout is always self-consistent.
            int[][] cells = new int[w][h];
            for (int[] col : cells)
                java.util.Arrays.fill(col, -1);
            List<InvSnapshot.Piece> ps = new ArrayList<>();
            int want = 1 + rnd.nextInt(8);
            for (int i = 0; i < want; i++) {
                int pw = rnd.nextInt(4) == 0 ? 2 : 1;
                int ph = rnd.nextInt(4) == 0 ? 2 : 1;
                Integer[] spot = findSpot(cells, mask, w, h, pw, ph, rnd);
                if (spot == null)
                    continue;
                int id = ps.size();
                ps.add(new InvSnapshot.Piece(id, pw, ph, spot[0], spot[1]));
                for (int dx = 0; dx < pw; dx++) {
                    for (int dy = 0; dy < ph; dy++)
                        cells[spot[0] + dx][spot[1] + dy] = id;
                }
            }
            if (ps.isEmpty())
                continue;
            InvSnapshot snap = new InvSnapshot(w, h, mask, ps);
            trials++;
            try {
                InvPlan.Plan plan = InvPlan.compute(snap);
                if (!layoutSane(snap, plan.targets))
                    throw new IllegalStateException("layout overlaps or leaves the grid");
                Server srv = replay(snap, plan);
                pinnedTotal += plan.pinned.size();
                if (!plan.pinned.isEmpty())
                    trialsWithAnyPin++;
                /* The load-bearing assertion. "Ended sorted" ignores pinned pieces, so a plan that
                 * pins everything would pass it trivially. With only 1x1 items the swap chain can
                 * always realise any permutation, even in a completely full grid - so pinning one
                 * there is not conservatism, it is a bug. */
                boolean allSingles = true;
                for (InvSnapshot.Piece pc : snap.pieces)
                    allSingles &= pc.single();
                if (allSingles) {
                    singlesOnlyTrials++;
                    singlesOnlyPinned += plan.pinned.size();
                }
                if (sorted(snap, plan, srv))
                    sortedOk++;
                else if (firstFailure.length() == 0)
                    firstFailure.append("unsorted result on ").append(w).append('x').append(h);
            } catch (RuntimeException e) {
                if (firstFailure.length() == 0)
                    firstFailure.append(e.getMessage()).append(" on ").append(w).append('x').append(h);
                failures++;
            }
        }
        check("trials that replayed without a refusal", failures, 0);
        check("trials that ended fully sorted", sortedOk, trials);
        check("1x1-only grids never pin anything", singlesOnlyPinned, 0);
        System.out.printf("  (%d trials, %d with a pin, %d pinned items; %d 1x1-only trials)%n",
                trials, trialsWithAnyPin, pinnedTotal, singlesOnlyTrials);
        if (firstFailure.length() > 0)
            System.out.println("  first failure: " + firstFailure);
    }

    static Integer[] findSpot(int[][] cells, boolean[] mask, int w, int h, int pw, int ph, Random rnd) {
        List<Integer[]> spots = new ArrayList<>();
        for (int y = 0; y + ph <= h; y++) {
            for (int x = 0; x + pw <= w; x++) {
                boolean ok = true;
                for (int dx = 0; dx < pw && ok; dx++) {
                    for (int dy = 0; dy < ph && ok; dy++) {
                        if (cells[x + dx][y + dy] >= 0 || mask[(y + dy) * w + (x + dx)])
                            ok = false;
                    }
                }
                if (ok)
                    spots.add(new Integer[] {x, y});
            }
        }
        return spots.isEmpty() ? null : spots.get(rnd.nextInt(spots.size()));
    }
}
