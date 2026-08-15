package haven.automated.invsort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An inventory's geometry, frozen as plain numbers.
 *
 * This package imports nothing from haven on purpose. Sorting is three decisions — where things
 * should end up, in what order to move them, and what to send — and only the last one needs a
 * widget. Keeping the first two over plain ints means they can be run and checked without a client
 * (see tools/InvSortCheck.java), which is the only way the move planner's termination argument is
 * worth anything.
 *
 * The snapshot also exists for a second reason. The sorter runs on a Defer worker, and the old code
 * read live widget state there — walking inv.lchild and reading each WItem's c and sz while the UI
 * thread was free to add and remove those same widgets. Taking the reading on the UI thread and
 * handing the worker an immutable value removes that race entirely.
 */
public class InvSnapshot {
    /** One item, reduced to a rectangle. {@code id} indexes the executor's parallel WItem list. */
    public static class Piece {
        public final int id;
        public final int w, h;
        public final int x, y;

        public Piece(int id, int w, int h, int x, int y) {
            this.id = id;
            this.w = Math.max(1, w);
            this.h = Math.max(1, h);
            this.x = x;
            this.y = y;
        }

        public int area() {
            return w * h;
        }

        public boolean single() {
            return w == 1 && h == 1;
        }

        public boolean covers(int cx, int cy) {
            return cx >= x && cx < x + w && cy >= y && cy < y + h;
        }
    }

    public final int width, height;
    private final boolean[] masked;
    /** Display order — the order the player wants to read them in, and the tie-break for placement. */
    public final List<Piece> pieces;

    public InvSnapshot(int width, int height, boolean[] masked, List<Piece> pieces) {
        this.width = width;
        this.height = height;
        this.masked = (masked == null) ? new boolean[width * height] : masked;
        this.pieces = Collections.unmodifiableList(new ArrayList<>(pieces));
    }

    public boolean masked(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height)
            return true;
        int i = y * width + x;
        return i < masked.length && masked[i];
    }

    public int count() {
        return pieces.size();
    }

    /** Pieces by id, for the planner's bookkeeping. Ids are dense and assigned by the caller. */
    public Piece byId(int id) {
        for (Piece p : pieces) {
            if (p.id == id)
                return p;
        }
        throw new IllegalArgumentException("no piece " + id);
    }
}
