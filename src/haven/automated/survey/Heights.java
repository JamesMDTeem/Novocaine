package haven.automated.survey;

import haven.Coord;
import haven.GameUI;
import haven.Loading;
import haven.MCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A block of terrain heights in raw client z, and the rectangle sums the planner asks of it.
 *
 * Vertices, not tiles: a survey covering an NxN block of tiles owns an (N+1)x(N+1) block of
 * vertices ({@code Area.corni} in the survey resource's {@code mkwidget}), and levelling moves
 * vertices rather than tiles. Neighbouring surveys therefore share their boundary row and column,
 * which is a real effect the planner has to account for rather than round away - see
 * {@link SurveyPlanner#nets}.
 *
 * <p>Deliberately free of {@link GameUI} and every widget: {@link #load} builds one from a captured
 * field, so the whole planner can be exercised against real terrain with no game running. Only
 * {@link #read} touches a live client.
 *
 * <p>Heights are kept in raw client z rather than the survey window's quantised {@code dz}, because
 * the quantisation factor is server-supplied and has to be observed from a live window. Nothing
 * that merely ranks partitions needs it.
 */
public class Heights {
    public final Coord ul;
    public final int w, h;
    public final double[] z;
    /** Vertices that were not loaded; those read 0 and make every number derived from them suspect. */
    public final int missing;

    /** Inclusive-rectangle sums in constant time; the partition search asks for thousands. */
    private final double[] psum;

    public Heights(Coord ul, int w, int h, double[] z, int missing) {
        this.ul = ul;
        this.w = w;
        this.h = h;
        this.z = z;
        this.missing = missing;
        this.psum = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                psum[(y + 1) * (w + 1) + x + 1] = z[y * w + x]
                    + psum[y * (w + 1) + x + 1] + psum[(y + 1) * (w + 1) + x]
                    - psum[y * (w + 1) + x];
            }
        }
    }

    /** Sum of z over the inclusive vertex rectangle [x0,x1] x [y0,y1]. */
    public double sum(int x0, int y0, int x1, int y1) {
        int a = x1 + 1, b = y1 + 1;
        return psum[b * (w + 1) + a] - psum[y0 * (w + 1) + a]
             - psum[b * (w + 1) + x0] + psum[y0 * (w + 1) + x0];
    }

    public double mean() {
        return sum(0, 0, w - 1, h - 1) / (w * (double) h);
    }

    /** Soil above a flat level t: sum of max(0, z - t). The survey window's "units of soil to dig". */
    public double dig(double t) {
        double acc = 0;
        for (double v : z)
            if (v > t)
                acc += v - t;
        return acc;
    }

    /**
     * The grid the player is standing on, as a 101x101 vertex field.
     *
     * The extra row and column are shared with the neighbouring grids and come from them, so they
     * are the ones that go missing when a neighbour is not loaded. They are counted rather than
     * silently zeroed, because a plan built from zeroed vertices looks perfectly reasonable and is
     * entirely wrong.
     */
    public static Heights read(GameUI gui) {
        MCache map = gui.ui.sess.glob.map;
        Coord pt = gui.map.player().rc.floor(MCache.tilesz);
        Coord ul = map.getgridt(pt).ul;
        int w = MCache.cmaps.x + 1, h = MCache.cmaps.y + 1;
        double[] z = new double[w * h];
        int missing = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                try {
                    z[y * w + x] = map.getfz(ul.add(x, y));
                } catch (Loading l) {
                    missing++;
                }
            }
        }
        return new Heights(ul, w, h, z, missing);
    }

    /**
     * A captured field: tab-separated, one row per line - exactly what {@code :surv dump} logs.
     *
     * The origin is not recorded in the file, so it comes back as {@link Coord#z}. That is fine for
     * everything the planner computes, which is all relative to the field, but a plan built from a
     * fixture carries fixture coordinates and is not something to hand to a character.
     */
    public static Heights load(Path tsv) {
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(tsv));
            lines.removeIf(String::isEmpty);
            if (lines.isEmpty())
                throw new IllegalArgumentException(tsv + " is empty");
            int h = lines.size(), w = lines.get(0).split("\t").length;
            double[] z = new double[w * h];
            for (int y = 0; y < h; y++) {
                String[] parts = lines.get(y).split("\t");
                if (parts.length != w)
                    throw new IllegalArgumentException(
                        "row " + y + " has " + parts.length + " columns, expected " + w);
                for (int x = 0; x < w; x++)
                    z[y * w + x] = Double.parseDouble(parts[x]);
            }
            return new Heights(Coord.z, w, h, z, 0);
        } catch (IOException e) {
            throw new RuntimeException("could not read " + tsv, e);
        }
    }
}
