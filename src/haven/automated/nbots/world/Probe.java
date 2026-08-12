package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Resource;
import haven.automated.helpers.HitBoxes;
import haven.automated.pathfinder.Pathfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
        /* A legend of what the solid blocks in this window are, so a house, a well and a chest
         * stop collapsing into the same '#'. Letters are assigned in sorted-label order, so two
         * dumps of the same ground render the same way. Only kinds inside the window earn a
         * letter, so a far-away timberhouse does not take 'a' away from a well that matters. */
        Map<String, Character> kinds = new TreeMap<>();
        char[] letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
        for (Map.Entry<Coord, String> e : Observed.objectsIn(me.seg).entrySet()) {
            Coord t = e.getKey();
            if ((t.x >= mid.x - radius) && (t.x <= mid.x + radius)
                && (t.y >= mid.y - radius) && (t.y <= mid.y + radius)
                && !kinds.containsKey(e.getValue()))
                kinds.put(e.getValue(), letters[kinds.size() % letters.length]);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("map around ").append(mid.x).append(',').append(mid.y)
            .append(" (@ = us, X = the point, . unseen, ~ water, ? no map file,")
            .append(" o open, + crossable but nothing standable, # solid, W wall, G gate)");
        if (!kinds.isEmpty()) {
            sb.append("  objects:");
            for (Map.Entry<String, Character> e : kinds.entrySet())
                sb.append(' ').append(e.getValue()).append('=').append(e.getKey());
        }
        for (int dy = -radius; dy <= radius; dy++) {
            sb.append(System.lineSeparator()).append(String.format("%6d ", mid.y + dy));
            for (int dx = -radius; dx <= radius; dx++) {
                Coord t = new Coord(mid.x + dx, mid.y + dy);
                if (t.equals(self))
                    sb.append('@');
                else if ((dx == 0) && (dy == 0))
                    sb.append('X');
                else
                    sb.append(glyph(gui, me.seg, t, kinds));
            }
        }
        return sb.toString();
    }

    private static char glyph(GameUI gui, long seg, Coord t, Map<String, Character> kinds) {
        byte s = Observed.at(seg, t);
        /* A gateway keeps its own marker no matter what the object registry says is on the tile:
         * a shut gate is the one solid thing that is SUPPOSED to be there, and 'G' is what the
         * eye looks for in a dump when a route stops at a wall. */
        if (s == Observed.GATE)
            return 'G';
        if ((s == Observed.WALL) || (s == Observed.SOLID)) {
            Character k = (kinds == null) ? null : kinds.get(Observed.objectAt(seg, t));
            if (k != null)
                return k;
            return (s == Observed.WALL) ? 'W' : '#';
        }
        if (!Terrain.known(gui, seg, t))
            return '?';
        if (!Terrain.ground(gui, seg, t))
            return '~';
        /* Its own glyph, and it has to have one. A tight tile is passable, so without this it drew
         * as plain open ground - and a dump that shows a clear lane where the record actually says
         * "crossable, nothing standable" would hide the exact thing these dumps are read for. */
        if (s == Observed.TIGHT)
            return '+';
        return (s == Observed.UNSEEN) ? '.' : 'o';
    }

    /**
     * Every loaded object with a real collision box near a point, and what OUR record says about
     * the ground its box covers.
     *
     * The comparison this exists to make visible: the client pathfinder does exact geometry on
     * arrival, while {@link Observed} quantises to whole tiles and deliberately under-records
     * (it answers for tile centres only). Every refusal this tree has spent a session misreading was
     * "the client threw the click away and our record says open" - the record and the boxes
     * disagreed, and the log could only name one of them. This names both, per gob: the real box
     * in world units, whether the refused point is inside it, and which tiles it covers that our
     * record still calls open. When the "record open where the box stands" line is empty the
     * record was the honest one and the refusal is about something else.
     */
    public static String objectsNear(GameUI gui, Coord2d wc, int radius) {
        if ((gui == null) || (gui.map == null) || (wc == null))
            return "no world";
        WorldAnchor me = WorldAnchor.capturePlayer(gui);
        Gob p = gui.map.player();
        if ((me == null) || (p == null))
            return "no anchor";
        Coord2d off = me.sc.sub(p.rc);
        Coord2d segPt = wc.add(off);
        Coord cTile = segPt.floor(MCache.tilesz);
        List<Gob> gobs = new ArrayList<>();
        try {
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob g : gui.ui.sess.glob.oc)
                    gobs.add(g);
            }
        } catch (RuntimeException e) {
            return "no gobs loaded";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("gobs near ").append(cTile.x).append(',').append(cTile.y)
            .append(" within ").append(radius).append(" tiles:");
        sb.append("  our record there: ").append(state(Observed.at(me.seg, cTile)));
        String there = Observed.objectAt(me.seg, cTile);
        if (there != null)
            sb.append(" (").append(there).append(')');
        double window = radius * MCache.tilesz.x;
        for (Gob g : gobs) {
            try {
                Resource res = g.getres();
                if ((res == null) || g.isPlgob(gui))
                    continue;
                Coord2d segGob = g.rc.add(off);
                HitBoxes.CollisionBoxSecondary[] boxes = HitBoxes.collisionBoxMap.get(res.name);
                double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
                double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
                boolean geometry = false;
                if (boxes != null) {
                    double cos = Math.cos(g.a), sin = Math.sin(g.a);
                    for (HitBoxes.CollisionBoxSecondary box : boxes) {
                        if ((box == null) || !box.hitAble || (box.coords == null) || (box.coords.length == 0))
                            continue;
                        for (Coord2d c : box.coords) {
                            double rx = (c.x * cos) - (c.y * sin);
                            double ry = (c.x * sin) + (c.y * cos);
                            minx = Math.min(minx, rx);
                            miny = Math.min(miny, ry);
                            maxx = Math.max(maxx, rx);
                            maxy = Math.max(maxy, ry);
                            geometry = true;
                        }
                    }
                }
                /* Nearest approach of the point to the box's world-space AABB; for a gob with no
                 * blocking geometry, the distance to the gob itself. */
                double near;
                if (geometry) {
                    double dx = Math.max(Math.max(minx + segGob.x - segPt.x, segPt.x - (maxx + segGob.x)), 0);
                    double dy = Math.max(Math.max(miny + segGob.y - segPt.y, segPt.y - (maxy + segGob.y)), 0);
                    near = Math.sqrt((dx * dx) + (dy * dy));
                } else {
                    near = segGob.dist(segPt);
                }
                if (near > window)
                    continue;
                sb.append(System.lineSeparator()).append("  #").append(g.id)
                    .append(' ').append(Observed.label(res.name))
                    .append(" @ ").append((int) segGob.x).append(',').append((int) segGob.y)
                    .append(" rot=").append((int) Math.toDegrees(g.a)).append('°');
                if (Observed.mobile(res.name))
                    sb.append("  (mobile - the record excludes it on purpose)");
                if (!geometry) {
                    /* No blocking shape on file - the ground under it was recorded as one solid
                     * tile, which is what {@code Observed.observe} does when a box is unknown. */
                    Coord t = segGob.floor(MCache.tilesz);
                    sb.append(System.lineSeparator()).append("    no hitbox on file; our record at ")
                        .append(t.x).append(',').append(t.y).append(": ")
                        .append(state(Observed.at(me.seg, t)));
                    continue;
                }
                Coord lo = segGob.add(minx, miny).floor(MCache.tilesz);
                Coord hi = segGob.add(maxx - 0.0001, maxy - 0.0001).floor(MCache.tilesz);
                int covered = 0, blocked = 0;
                StringBuilder open = new StringBuilder();
                for (int y = lo.y; y <= hi.y; y++) {
                    for (int x = lo.x; x <= hi.x; x++) {
                        covered++;
                        byte s = Observed.at(me.seg, new Coord(x, y));
                        if ((s == Observed.SOLID) || (s == Observed.WALL) || (s == Observed.GATE))
                            blocked++;
                        else if (open.length() <= 200)
                            open.append(" (").append(x).append(',').append(y).append(')');
                    }
                }
                boolean destIn = Pathfinder.isInsideBoundBox(g, wc.floor());
                sb.append(System.lineSeparator()).append("    real box ")
                    .append(String.format("%.1f", minx + segGob.x)).append(',')
                    .append(String.format("%.1f", miny + segGob.y)).append('-')
                    .append(String.format("%.1f", maxx + segGob.x)).append(',')
                    .append(String.format("%.1f", maxy + segGob.y))
                    /* Two DIFFERENT questions, and printing them as a ratio has now been misread as
                     * a bug twice - once by a human and once by an agent that went on to "fix" it
                     * and broke pathing. "touches" is the naive box/tile overlap; "unstandable" is
                     * what Observed records, which asks only about tile CENTRES (and about a ROUND
                     * character). A tile the box clips the corner of is touched and is genuinely
                     * standable, so the two numbers are SUPPOSED to differ. Say so in the line
                     * rather than leaving a suggestive fraction to be interpreted. */
                    .append("  touches ").append(covered).append(" tile(s), of which ")
                    .append(blocked).append(" unstandable in our record")
                    .append(" (differing is normal - see below)")
                    .append("  dest inside: ").append(destIn ? "yes" : "no");
                if (blocked < covered)
                    sb.append(System.lineSeparator())
                        .append("    touched but standable (box clips them; centre is clear):")
                        .append(open);
            } catch (RuntimeException e) {
                // A gob whose resource has not arrived says nothing either way.
            }
        }
        return sb.toString();
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
                if ((res != null) && Pathfinder.isInsideBoundBox(g, at))
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
