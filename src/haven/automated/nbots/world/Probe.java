package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Resource;
import haven.automated.pathfinder.Pathfinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Says what every record we keep believes about a point, in one line.
 *
 * The gap this fills is not a shortage of logging - the logs are enormous - it is that every line
 * in them reports a CONCLUSION. "no way from here to there", eight hundred times in a shift, is the
 * pathfinder's verdict with none of the evidence behind it, and the four records that could
 * disagree about a tile ({@link Observed}, {@link Terrain}, the live collision boxes, and the
 * keep-out circles) are each perfectly capable of being the only one that says no. Working out
 * which took a decode of the saved map, an offset derived from a place anchor, and a re-run of the
 * router in another language. Twice.
 *
 * So: when something refuses a point, ask this and print what it says. The answer is nine words and
 * it names the record that objected.
 *
 * Deliberately reads every source even after one has already said no. A point that is water AND has
 * a barrel on it is a different fix from one that is only water, and a diagnostic that stops at the
 * first objection hides the second.
 */
public class Probe {
    private Probe() {}

    /** A one-line verdict from every record, for a live world point. */
    public static String explain(GameUI gui, Coord2d wc) {
        if ((gui == null) || (wc == null))
            return "no world";
        WorldAnchor me = WorldAnchor.capturePlayer(gui);
        Gob p = gui.map.player();
        if ((me == null) || (p == null))
            return "no anchor";
        Coord2d sc = wc.add(me.sc.sub(p.rc));
        Coord tile = sc.floor(MCache.tilesz);
        StringBuilder sb = new StringBuilder();
        sb.append("tile ").append(tile.x).append(',').append(tile.y);
        sb.append(" seen=").append(state(Observed.at(me.seg, tile)));
        sb.append(" ground=").append(ground(gui, me.seg, tile));
        sb.append(" box=").append(box(gui, wc));
        sb.append(" keepout=").append(inKeepout(wc) ? "yes" : "no");
        sb.append(" ").append((int) p.rc.dist(wc) / MCache.tilesz.x).append("t away");
        return sb.toString();
    }

    /**
     * Everything the records say along a straight line, summarised.
     *
     * For the case the single-point version cannot see: a destination that is perfectly fine with
     * something impassable in front of it. Reports the first objection and how far along it is,
     * since that distance is the whole question when a route is suspected of cutting through
     * ground that was never looked at.
     */
    public static String line(GameUI gui, Coord2d from, Coord2d to) {
        if ((gui == null) || (from == null) || (to == null))
            return "no line";
        WorldAnchor me = WorldAnchor.capturePlayer(gui);
        Gob p = gui.map.player();
        if ((me == null) || (p == null))
            return "no anchor";
        Coord2d off = me.sc.sub(p.rc);
        double len = from.dist(to);
        int steps = Math.max(1, (int) Math.ceil(len / (MCache.tilesz.x / 2.0)));
        int unseen = 0, wet = 0, solid = 0, unknown = 0;
        String first = null;
        for (int i = 0; i <= steps; i++) {
            Coord2d at = from.add(to.sub(from).mul((double) i / steps));
            Coord tile = at.add(off).floor(MCache.tilesz);
            byte s = Observed.at(me.seg, tile);
            boolean bad = false;
            if (s == Observed.UNSEEN) {
                unseen++;
                bad = true;
            }
            if ((s == Observed.SOLID) || (s == Observed.WALL)) {
                solid++;
                bad = true;
            }
            if (!Terrain.known(gui, me.seg, tile)) {
                unknown++;
                bad = true;
            } else if (!Terrain.ground(gui, me.seg, tile)) {
                wet++;
                bad = true;
            }
            if (bad && (first == null)) {
                first = String.format("first objection %dt out at %d,%d (%s%s%s)",
                    (int) (from.dist(at) / MCache.tilesz.x), tile.x, tile.y,
                    state(s), Terrain.known(gui, me.seg, tile) ? "" : "/no map file",
                    Terrain.known(gui, me.seg, tile) && !Terrain.ground(gui, me.seg, tile)
                        ? "/water" : "");
            }
        }
        return String.format("%d samples over %dt: %d unseen, %d unmapped, %d water, %d solid%s",
            steps + 1, (int) (len / MCache.tilesz.x), unseen, unknown, wet, solid,
            (first == null) ? "; nothing in the way" : ("; " + first));
    }

    /**
     * A picture of what is believed around a point, one character per tile.
     *
     * Worth the width in a log file. A route that goes wrong goes wrong somewhere specific, and a
     * column of characters shows a gateway sealed by its own posts, or a wall with a hole in it, or
     * a lake nobody has recorded, in one glance - none of which reads out of a list of coordinates.
     *
     * Water is overlaid on the object record rather than shown separately, because the question
     * being asked of the picture is always "could a character be here", and either answers it.
     */
    public static String map(GameUI gui, Coord2d centre, int radius) {
        WorldAnchor me = WorldAnchor.capturePlayer(gui);
        Gob p = (gui.map == null) ? null : gui.map.player();
        if ((me == null) || (p == null) || (centre == null))
            return "no map";
        Coord mid = centre.add(me.sc.sub(p.rc)).floor(MCache.tilesz);
        Coord self = me.sc.floor(MCache.tilesz);
        StringBuilder sb = new StringBuilder();
        sb.append("map around ").append(mid.x).append(',').append(mid.y)
            .append(" (@ = us, X = the point, . unseen, ~ water, ? no map file,")
            .append(" o open, # solid, W wall, G gate)");
        for (int dy = -radius; dy <= radius; dy++) {
            sb.append(System.lineSeparator()).append(String.format("%6d ", mid.y + dy));
            for (int dx = -radius; dx <= radius; dx++) {
                Coord t = new Coord(mid.x + dx, mid.y + dy);
                if (t.equals(self))
                    sb.append('@');
                else if ((dx == 0) && (dy == 0))
                    sb.append('X');
                else
                    sb.append(glyph(gui, me.seg, t));
            }
        }
        return sb.toString();
    }

    private static char glyph(GameUI gui, long seg, Coord t) {
        byte s = Observed.at(seg, t);
        if (s == Observed.WALL)
            return 'W';
        if (s == Observed.GATE)
            return 'G';
        if (s == Observed.SOLID)
            return '#';
        if (!Terrain.known(gui, seg, t))
            return '?';
        if (!Terrain.ground(gui, seg, t))
            return '~';
        return (s == Observed.UNSEEN) ? '.' : 'o';
    }

    private static String state(byte s) {
        switch (s) {
            case Observed.OPEN:  return "open";
            case Observed.SOLID: return "solid";
            case Observed.GATE:  return "gate";
            case Observed.WALL:  return "wall";
            default:             return "never looked";
        }
    }

    private static String ground(GameUI gui, long seg, Coord tile) {
        if (!Terrain.known(gui, seg, tile))
            return "no map file";
        if (Terrain.deep(gui, seg, tile))
            return "deep water";
        return Terrain.ground(gui, seg, tile) ? "dry" : "shallow water (avoided)";
    }

    /** Which loaded object's collision box covers this point, if any. */
    private static String box(GameUI gui, Coord2d wc) {
        Coord at = wc.floor();
        List<Gob> gobs = new ArrayList<>();
        try {
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob g : gui.ui.sess.glob.oc)
                    gobs.add(g);
            }
        } catch (RuntimeException e) {
            return "?";
        }
        for (Gob g : gobs) {
            try {
                if (g.isPlgob(gui))
                    continue;
                Resource res = g.getres();
                if ((res != null) && Pathfinder.isInsideBoundBox(g.rc.floor(), g.a, res.name, at))
                    return res.name + "#" + g.id;
            } catch (RuntimeException e) {
                // A gob whose resource has not arrived cannot be tested; it is not the answer.
            }
        }
        return "none";
    }

    private static boolean inKeepout(Coord2d wc) {
        for (haven.automated.pathfinder.Map.Keepout k : haven.automated.pathfinder.Map.keepouts()) {
            if (wc.dist(k.c) <= k.r)
                return true;
        }
        return false;
    }
}
