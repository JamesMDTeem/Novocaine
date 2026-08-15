package haven.automated.invsort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns "where everything should end up" into an ordered list of take/drop operations, every one of
 * which the server will accept.
 *
 * The old sorter emitted messages while it was still deciding, so a decision that went wrong left
 * the model inconsistent with messages already in flight. Here the whole sequence is built and
 * checked first; nothing is sent until it exists. That is what makes a refused move impossible
 * rather than merely unlikely.
 *
 * <b>Why the runaway loop cannot come back.</b> The old chain-swap was {@code while(handu != null)}
 * with a drop inside and no bound — if the chain cycled it flooded the server, which is what took
 * the game down. The fix is not a counter. Targets are pairwise disjoint, so every cell is the
 * target of at most one piece; a drop therefore never displaces a piece that is already home, and
 * every drop puts exactly one piece permanently in place. The chain is a permutation cycle and
 * closes on the cell it vacated at the start. The bound on drops follows from the structure rather
 * than being imposed on top of it.
 *
 * <b>What is deliberately not attempted.</b> A drop onto an occupied cell exchanges the cursor's
 * item for the occupant. That is relied on only for 1x1 onto 1x1. Whether the server performs it
 * for a multi-tile item has never been exercised, and guessing wrong is exactly what leaves a large
 * item stuck on the cursor and desyncs everything after it. Multi-tile items move only into space
 * this plan knows to be empty; where that is impossible they are pinned and the layout is
 * recomputed around them.
 */
public class InvPlan {
    public static final int TAKE = 0, DROP = 1;

    /** One protocol message. TAKE carries a piece; DROP carries a destination cell. */
    public static class Op {
        public final int kind;
        public final int piece;
        public final int x, y;

        Op(int kind, int piece, int x, int y) {
            this.kind = kind;
            this.piece = piece;
            this.x = x;
            this.y = y;
        }

        public String toString() {
            return (kind == TAKE) ? ("take#" + piece) : ("drop#" + piece + "@" + x + "," + y);
        }
    }

    public static class Plan {
        public final List<Op> ops;
        public final InvLayout.Spot[] targets;
        /** Pieces that could not be moved and stayed put. Normal in a well-filled container. */
        public final List<Integer> pinned;

        Plan(List<Op> ops, InvLayout.Spot[] targets, List<Integer> pinned) {
            this.ops = ops;
            this.targets = targets;
            this.pinned = pinned;
        }

        public boolean movesAnything() {
            return !ops.isEmpty();
        }
    }

    /**
     * Builds a complete plan, pinning whatever cannot be moved and recomputing around it.
     * Terminates: each retry pins one more piece, and the all-pinned layout needs no moves at all.
     */
    public static Plan compute(InvSnapshot snap) {
        int n = idBound(snap);
        boolean[] pin = new boolean[n];
        for (int attempt = 0; attempt <= snap.count() + 1; attempt++) {
            InvLayout.Spot[] targets = InvLayout.place(snap, pin);
            Attempt made = build(snap, targets);
            if (made.blocked < 0) {
                List<Integer> pinned = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if (pin[i])
                        pinned.add(i);
                }
                return new Plan(made.ops, targets, pinned);
            }
            pin[made.blocked] = true;
        }
        throw new IllegalStateException("plan failed to converge");
    }

    private static class Attempt {
        final List<Op> ops;
        final int blocked;

        Attempt(List<Op> ops, int blocked) {
            this.ops = ops;
            this.blocked = blocked;
        }
    }

    /**
     * Where everything is right now, as the plan builds. Positions live here rather than on the
     * pieces: a piece evicted out of a large item's way is no longer where the snapshot says it is,
     * and reading its original coordinates after that would corrupt the occupancy grid.
     */
    private static class Board {
        final InvSnapshot snap;
        final int[][] occ;
        final int[] cx, cy;

        Board(InvSnapshot snap, int n) {
            this.snap = snap;
            this.occ = new int[snap.width][snap.height];
            for (int x = 0; x < snap.width; x++) {
                for (int y = 0; y < snap.height; y++)
                    occ[x][y] = -1;
            }
            this.cx = new int[n];
            this.cy = new int[n];
            for (InvSnapshot.Piece p : snap.pieces) {
                cx[p.id] = p.x;
                cy[p.id] = p.y;
                fill(p.x, p.y, p.w, p.h, p.id);
            }
        }

        void lift(int id) {
            InvSnapshot.Piece p = snap.byId(id);
            fill(cx[id], cy[id], p.w, p.h, -1);
        }

        void settle(int id, int x, int y) {
            InvSnapshot.Piece p = snap.byId(id);
            cx[id] = x;
            cy[id] = y;
            fill(x, y, p.w, p.h, id);
        }

        private void fill(int x, int y, int w, int h, int v) {
            for (int dx = 0; dx < w; dx++) {
                for (int dy = 0; dy < h; dy++) {
                    if (inside(x + dx, y + dy))
                        occ[x + dx][y + dy] = v;
                }
            }
        }

        boolean clear(int x, int y, int w, int h, int self) {
            for (int dx = 0; dx < w; dx++) {
                for (int dy = 0; dy < h; dy++) {
                    if (!inside(x + dx, y + dy) || snap.masked(x + dx, y + dy))
                        return false;
                    int id = occ[x + dx][y + dy];
                    if (id >= 0 && id != self)
                        return false;
                }
            }
            return true;
        }

        boolean inside(int x, int y) {
            return x >= 0 && y >= 0 && x < snap.width && y < snap.height;
        }

        boolean home(int id, InvLayout.Spot t) {
            return cx[id] == t.x && cy[id] == t.y;
        }
    }

    private static Attempt build(InvSnapshot snap, InvLayout.Spot[] targets) {
        List<Op> ops = new ArrayList<>();
        Board b = new Board(snap, targets.length);
        boolean[] placed = new boolean[targets.length];
        int[] evictions = {0};

        /* Largest first, matching the layout. Big items claim their ground while the grid is still
         * whole; leaving them until the 1x1s have scattered is what forced the old eviction dance. */
        for (InvSnapshot.Piece p : byAreaThenDisplay(snap)) {
            if (placed[p.id])
                continue;
            InvLayout.Spot t = targets[p.id];
            if (b.home(p.id, t)) {
                placed[p.id] = true;
                continue;
            }
            if (b.clear(t.x, t.y, p.w, p.h, p.id)) {
                move(b, ops, p.id, t.x, t.y);
                placed[p.id] = true;
                continue;
            }
            if (p.single()) {
                if (!chain(snap, targets, b, placed, ops, p))
                    return new Attempt(ops, p.id);
                continue;
            }
            /* A multi-tile item whose destination is occupied. Clear the blockers into space known
             * to be empty - never a swap - and if even one has nowhere legal to go, pin this item
             * and let the layout work around it. */
            if (!evict(snap, targets, b, placed, ops, p, evictions))
                return new Attempt(ops, p.id);
            if (!b.clear(t.x, t.y, p.w, p.h, p.id))
                return new Attempt(ops, p.id);
            move(b, ops, p.id, t.x, t.y);
            placed[p.id] = true;
        }
        return new Attempt(ops, -1);
    }

    private static void move(Board b, List<Op> ops, int id, int x, int y) {
        b.lift(id);
        ops.add(new Op(TAKE, id, 0, 0));
        ops.add(new Op(DROP, id, x, y));
        b.settle(id, x, y);
    }

    /**
     * Follows one permutation cycle: take the piece, then keep dropping whatever is in hand onto
     * its own target, which hands back the occupant, until a drop lands somewhere empty — the cell
     * the chain vacated at the start. Returns false only if the invariant that makes this terminate
     * has been violated, which means a layout bug rather than a full container.
     */
    private static boolean chain(InvSnapshot snap, InvLayout.Spot[] targets, Board b,
                                 boolean[] placed, List<Op> ops, InvSnapshot.Piece start) {
        b.lift(start.id);
        ops.add(new Op(TAKE, start.id, 0, 0));
        int held = start.id;
        for (int step = 0; step <= snap.count(); step++) {
            InvLayout.Spot t = targets[held];
            if (!b.inside(t.x, t.y) || snap.masked(t.x, t.y))
                return false;
            int displaced = b.occ[t.x][t.y];
            /* Refuse to swap with anything already home or larger than one slot - the first would
             * mean two pieces share a target, the second is the unverified server behaviour. */
            if (displaced >= 0 && (placed[displaced] || !snap.byId(displaced).single()))
                return false;
            ops.add(new Op(DROP, held, t.x, t.y));
            b.settle(held, t.x, t.y);
            placed[held] = true;
            if (displaced < 0)
                return true;
            held = displaced;
        }
        return false;
    }

    /**
     * Moves 1x1 items out of a multi-tile item's destination into cells that are empty now and are
     * nobody else's destination. Bounded so a pathological grid falls back to pinning rather than
     * shuffling forever.
     */
    private static boolean evict(InvSnapshot snap, InvLayout.Spot[] targets, Board b,
                                 boolean[] placed, List<Op> ops, InvSnapshot.Piece want,
                                 int[] evictions) {
        InvLayout.Spot t = targets[want.id];
        for (int dx = 0; dx < want.w; dx++) {
            for (int dy = 0; dy < want.h; dy++) {
                if (!b.inside(t.x + dx, t.y + dy) || snap.masked(t.x + dx, t.y + dy))
                    return false;
                int id = b.occ[t.x + dx][t.y + dy];
                if (id < 0 || id == want.id)
                    continue;
                if (placed[id] || !snap.byId(id).single())
                    return false;
                if (++evictions[0] > snap.count())
                    return false;
                int[] spot = spareCell(snap, targets, b, want);
                if (spot == null)
                    return false;
                move(b, ops, id, spot[0], spot[1]);
            }
        }
        return true;
    }

    /** An empty, unmasked cell that is not inside any multi-tile item's destination. */
    private static int[] spareCell(InvSnapshot snap, InvLayout.Spot[] targets, Board b,
                                   InvSnapshot.Piece want) {
        for (int y = 0; y < snap.height; y++) {
            for (int x = 0; x < snap.width; x++) {
                if (snap.masked(x, y) || b.occ[x][y] >= 0)
                    continue;
                if (reservedForMulti(snap, targets, x, y))
                    continue;
                return new int[] {x, y};
            }
        }
        return null;
    }

    private static boolean reservedForMulti(InvSnapshot snap, InvLayout.Spot[] targets, int x, int y) {
        for (InvSnapshot.Piece p : snap.pieces) {
            if (p.single())
                continue;
            InvLayout.Spot t = targets[p.id];
            if (x >= t.x && x < t.x + p.w && y >= t.y && y < t.y + p.h)
                return true;
        }
        return false;
    }

    private static List<InvSnapshot.Piece> byAreaThenDisplay(InvSnapshot snap) {
        List<InvSnapshot.Piece> ordered = new ArrayList<>(snap.pieces);
        final List<InvSnapshot.Piece> display = snap.pieces;
        ordered.sort(Comparator
                .comparingInt((InvSnapshot.Piece p) -> -p.area())
                .thenComparingInt(display::indexOf));
        return ordered;
    }

    private static int idBound(InvSnapshot snap) {
        int max = -1;
        for (InvSnapshot.Piece p : snap.pieces)
            max = Math.max(max, p.id);
        return max + 1;
    }
}
