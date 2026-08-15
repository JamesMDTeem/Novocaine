package haven.automated.invsort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides where every item should end up. Nothing here knows how to get there.
 *
 * Two rules earn their keep:
 *
 * <b>Largest first.</b> The old sorter assigned targets in display order — by name — with size
 * playing no part. The 1x1s are the majority and usually sort earlier alphabetically, so they got
 * scattered across the grid first and cut the free space into pieces too small for the 2x2 that
 * came later. That is not a rare accident: the pre-sort arrangement is standing proof that a
 * packing exists, and first-fit by name is under no obligation to find one. Packing big rectangles
 * while the grid is still whole is.
 *
 * <b>Pin, don't abort.</b> When an item genuinely fits nowhere, the old code broke out of the
 * assignment loop, which left every later item with a target equal to its current position — and
 * those inherited targets were never written into the occupancy grid, so a later assignment could
 * be handed cells that an unplaced item was still sitting on. Here the misfit is pinned where it
 * already is, its rectangle reserved, and the pass restarts. That terminates: every restart pins
 * one more item, and the all-pinned state is the original layout, which fits by construction.
 */
public class InvLayout {
    /** Where one piece ends up. Slot coordinates. */
    public static class Spot {
        public final int x, y;

        public Spot(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public boolean at(InvSnapshot.Piece p) {
            return p.x == x && p.y == y;
        }
    }

    /**
     * @param pinned piece ids that must keep their current position, or null for none
     * @return target per piece id; never null, because the all-pinned layout always succeeds
     */
    public static Spot[] place(InvSnapshot snap, boolean[] pinned) {
        int n = maxId(snap) + 1;
        boolean[] pin = (pinned == null) ? new boolean[n] : pinned.clone();

        /* Bounded by the piece count: each turn of this loop pins one more piece, and once every
         * piece is pinned the layout is the arrangement we started from. */
        for (int guard = 0; guard <= snap.count() + 1; guard++) {
            Spot[] out = attempt(snap, pin);
            if (out != null)
                return out;
        }
        throw new IllegalStateException("layout failed to converge");
    }

    /** One pass. Returns null if some piece fit nowhere, after pinning it for the next pass. */
    private static Spot[] attempt(InvSnapshot snap, boolean[] pin) {
        boolean[][] taken = new boolean[snap.width][snap.height];
        for (int x = 0; x < snap.width; x++) {
            for (int y = 0; y < snap.height; y++)
                taken[x][y] = snap.masked(x, y);
        }
        Spot[] out = new Spot[pin.length];

        /* Pinned pieces claim their ground before anything else is placed - that is what makes the
         * restart converge rather than just reshuffling the same conflict. */
        for (InvSnapshot.Piece p : snap.pieces) {
            if (pin[p.id]) {
                out[p.id] = new Spot(p.x, p.y);
                mark(taken, p.x, p.y, p.w, p.h);
            }
        }

        for (InvSnapshot.Piece p : order(snap)) {
            if (pin[p.id])
                continue;
            Spot spot = firstFit(taken, snap, p.w, p.h);
            if (spot == null) {
                pin[p.id] = true;
                return null;
            }
            out[p.id] = spot;
            mark(taken, spot.x, spot.y, p.w, p.h);
        }
        return out;
    }

    /**
     * Area descending, then display order. Display order alone is what fragmented the grid; area
     * alone would shuffle equal-sized items arbitrarily between sorts, which reads as the sort
     * being broken even when the packing is fine.
     */
    private static List<InvSnapshot.Piece> order(InvSnapshot snap) {
        List<InvSnapshot.Piece> ordered = new ArrayList<>(snap.pieces);
        final List<InvSnapshot.Piece> display = snap.pieces;
        ordered.sort(Comparator
                .comparingInt((InvSnapshot.Piece p) -> -p.area())
                .thenComparingInt(display::indexOf));
        return ordered;
    }

    private static Spot firstFit(boolean[][] taken, InvSnapshot snap, int w, int h) {
        for (int y = 0; y + h <= snap.height; y++) {
            for (int x = 0; x + w <= snap.width; x++) {
                if (free(taken, x, y, w, h))
                    return new Spot(x, y);
            }
        }
        return null;
    }

    private static boolean free(boolean[][] taken, int x, int y, int w, int h) {
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                if (taken[x + dx][y + dy])
                    return false;
            }
        }
        return true;
    }

    private static void mark(boolean[][] taken, int x, int y, int w, int h) {
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++)
                taken[x + dx][y + dy] = true;
        }
    }

    private static int maxId(InvSnapshot snap) {
        int max = -1;
        for (InvSnapshot.Piece p : snap.pieces)
            max = Math.max(max, p.id);
        return max;
    }
}
