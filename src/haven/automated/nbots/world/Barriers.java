package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Resource;
import haven.automated.helpers.HitBoxes;
import haven.automated.nbots.core.NLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walls the client has seen, remembered by segment so they are still known once they unload.
 *
 * The map file persists tiles but not gobs, and a palisade is a gob. That gap is what let a bot
 * plan a route straight through a wall it had walked past five minutes earlier: the tiles under a
 * palisade are perfectly good grass, and nothing else remembered otherwise. Hurricane's pathfinder
 * does know about walls, but only the ones currently loaded, which is far too late - by the time
 * the wall is in view the route committing to it has already been chosen.
 *
 * So walls are learned. Every time travel runs it sweeps the loaded gobs, and anything that looks
 * like a wall has its tile recorded against the segment. The record is written out, so the second
 * trip past a base already knows the shape of it.
 *
 * GATES ARE RECORDED SEPARATELY AND DO NOT BLOCK. That is the whole point of keeping them apart: a
 * router that treats a gateway as solid walks around the outside of a palisade rather than through
 * it, which is exactly the behaviour that looks broken. A gate tile stays walkable and the task
 * layer deals with opening it.
 *
 * Detection is by resource path rather than an enumerated list of wall names. Anything under
 * .../arch/ whose name mentions a wall, palisade or fence counts. Guessing the exact resource
 * names would be a list that silently rots every time a new wall type is added, and the failure
 * mode of that list is the bug this class exists to fix.
 */
public class Barriers {
    private static final String FILE = "botbarriers.json";
    private static final Object LOCK = new Object();

    /** Wall words. Only consulted for resources under an arch/ path - see {@link #kind}. */
    private static final String[] WALLWORDS = {"palisade", "wall", "fence", "pole"};

    /** Tiles across. A barrier piece is a few; a bigger box is bad data, not a longer wall. */
    private static final int MAX_SPAN = 8;

    /** segment -> segment tiles a wall stands on. */
    private static Map<Long, Set<Coord>> walls;
    /** segment -> segment tiles a gateway stands on. */
    private static Map<Long, Set<Coord>> gates;
    private static boolean dirty = false;

    public enum Kind {WALL, GATE}

    /**
     * What this resource is, or null for anything that is not part of a barrier.
     *
     * Gates are tested first because every gate name also contains a wall word - a palisade gate is
     * "palisade" plus "gate" - so ordering is what keeps gateways from being recorded as solid.
     */
    public static Kind kind(String resname) {
        if (resname == null)
            return null;
        String n = resname.toLowerCase();
        if (!n.contains("/arch/"))
            return null;
        if (n.endsWith("gate") || n.contains("gate"))
            return Kind.GATE;
        for (String w : WALLWORDS) {
            if (n.contains(w))
                return Kind.WALL;
        }
        return null;
    }

    /** True if a wall - not a gateway - is known to stand on this segment tile. */
    public static boolean blocks(long seg, Coord segTile) {
        synchronized (LOCK) {
            load();
            Set<Coord> s = walls.get(seg);
            return (s != null) && s.contains(segTile);
        }
    }

    /** Every known gateway tile in a segment. The caller decides which is worth walking to. */
    public static Set<Coord> gatesIn(long seg) {
        synchronized (LOCK) {
            load();
            Set<Coord> s = gates.get(seg);
            return (s == null) ? new HashSet<>() : new HashSet<>(s);
        }
    }

    /**
     * Records every barrier gob currently loaded.
     *
     * Cheap enough to call once per travel hop: it is a pass over the loaded gobs, and the write
     * only happens when something was actually new.
     */
    public static void learn(GameUI gui) {
        if (gui == null || gui.map == null || gui.ui == null || gui.ui.sess == null)
            return;
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if (here == null)
            return;
        List<Gob> gobs = new ArrayList<>();
        try {
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob g : gui.ui.sess.glob.oc)
                    gobs.add(g);
            }
        } catch (RuntimeException e) {
            return;
        }
        Map<Gob, Kind> kinds = new LinkedHashMap<>();
        for (Gob g : gobs) {
            try {
                Resource res = g.getres();
                Kind k = (res == null) ? null : kind(res.name);
                if (k != null)
                    kinds.put(g, k);
            } catch (RuntimeException e) {
                // Includes Loading: a gob whose resource hasn't arrived is picked up next sweep.
            }
        }

        /* Every gateway first, then walls with the gateway tiles held out. A wall segment's
         * footprint overlaps its own gate's, so in a single pass the result depended on the order
         * the object cache happened to hand the gobs over - and half the time that walls a gate
         * shut with its own posts. */
        Set<Coord> gateTiles = new HashSet<>();
        for (Map.Entry<Gob, Kind> e : kinds.entrySet()) {
            if (e.getValue() == Kind.GATE)
                gateTiles.addAll(footprint(gui, here, e.getKey()));
        }

        boolean added;
        synchronized (LOCK) {
            load();
            Set<Coord> gs = gates.computeIfAbsent(here.seg, x -> new HashSet<>());
            Set<Coord> ws = walls.computeIfAbsent(here.seg, x -> new HashSet<>());
            added = gs.addAll(gateTiles);
            added |= ws.removeAll(gateTiles);
            for (Map.Entry<Gob, Kind> e : kinds.entrySet()) {
                if (e.getValue() != Kind.WALL)
                    continue;
                for (Coord t : footprint(gui, here, e.getKey())) {
                    if (!gateTiles.contains(t))
                        added |= ws.add(t);
                }
            }
            if (added)
                dirty = true;
        }
        if (added)
            save();
    }

    /**
     * Every segment tile a barrier gob stands on.
     *
     * Recording only the gob's own tile defeated the point of the class. A palisade SEGMENT is one
     * gob several tiles long, so a wall came out as a dotted line with multi-tile holes in it, and
     * the router quite reasonably planned straight through the holes - which is the "walks at the
     * wall as though it isn't there" behaviour, with the wall genuinely half-recorded rather than
     * missing. The collision box used here is the same data the local pathfinder blocks on, so a
     * wall now stops a route exactly where it stops a footstep.
     */
    private static Set<Coord> footprint(GameUI gui, WorldAnchor here, Gob g) {
        Set<Coord> out = new HashSet<>();
        Coord base = segTile(gui, here, g.rc);
        if (base == null)
            return out;
        out.add(base);
        HitBoxes.CollisionBoxSecondary[] boxes;
        try {
            Resource res = g.getres();
            boxes = (res == null) ? null : HitBoxes.collisionBoxMap.get(res.name);
        } catch (RuntimeException e) {
            return out;
        }
        if (boxes == null)
            return out;
        double cos = Math.cos(g.a), sin = Math.sin(g.a);
        for (HitBoxes.CollisionBoxSecondary box : boxes) {
            if ((box == null) || (box.coords == null) || (box.coords.length == 0))
                continue;
            double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
            double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
            for (Coord2d c : box.coords) {
                // The box is stored unrotated, in the gob's own frame.
                double rx = (c.x * cos) - (c.y * sin);
                double ry = (c.x * sin) + (c.y * cos);
                minx = Math.min(minx, rx);
                maxx = Math.max(maxx, rx);
                miny = Math.min(miny, ry);
                maxy = Math.max(maxy, ry);
            }
            Coord lo = new Coord2d(minx, miny).floor(MCache.tilesz);
            Coord hi = new Coord2d(maxx, maxy).floor(MCache.tilesz);
            // A barrier piece is a few tiles at most; anything larger is a bad box, not a wall.
            if (((hi.x - lo.x) > MAX_SPAN) || ((hi.y - lo.y) > MAX_SPAN))
                continue;
            for (int y = lo.y; y <= hi.y; y++) {
                for (int x = lo.x; x <= hi.x; x++)
                    out.add(base.add(x, y));
            }
        }
        return out;
    }

    /**
     * A live world position as a segment tile, using the player's own anchor as the reference
     * point. Both are in the same segment by construction - they are metres apart on screen - so
     * this is just an offset, and it avoids a map-file lookup per gob.
     */
    private static Coord segTile(GameUI gui, WorldAnchor here, Coord2d wc) {
        try {
            Coord2d me = gui.map.player().rc;
            Coord2d rel = wc.sub(me).add(here.sc);
            return rel.floor(MCache.tilesz);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ persistence

    private static Path file() {
        return Paths.get(FILE);
    }

    private static void load() {
        if (walls != null)
            return;
        walls = new HashMap<>();
        gates = new HashMap<>();
        try {
            Path f = file();
            if (!Files.exists(f))
                return;
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            read(root.optJSONArray("walls"), walls);
            read(root.optJSONArray("gates"), gates);
        } catch (IOException | RuntimeException e) {
            NLog.crash("loading " + FILE, e);
        }
    }

    private static void read(JSONArray arr, Map<Long, Set<Coord>> into) {
        if (arr == null)
            return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            long seg = o.getLong("seg");
            JSONArray ts = o.optJSONArray("t");
            Set<Coord> set = into.computeIfAbsent(seg, k -> new HashSet<>());
            if (ts != null) {
                // Flat [x0,y0,x1,y1,...]: a wall is thousands of tiles and an object per tile
                // turns a base into a megabyte of punctuation.
                for (int j = 0; (j + 1) < ts.length(); j += 2)
                    set.add(new Coord(ts.getInt(j), ts.getInt(j + 1)));
            }
        }
    }

    private static void save() {
        synchronized (LOCK) {
            if (!dirty)
                return;
            try {
                JSONObject root = new JSONObject();
                root.put("walls", write(walls));
                root.put("gates", write(gates));
                Path dst = file();
                Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
                Files.write(tmp, root.toString().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty = false;
            } catch (IOException | RuntimeException e) {
                NLog.crash("saving " + FILE, e);
            }
        }
    }

    private static JSONArray write(Map<Long, Set<Coord>> from) {
        JSONArray out = new JSONArray();
        for (Map.Entry<Long, Set<Coord>> e : from.entrySet()) {
            JSONArray ts = new JSONArray();
            for (Coord c : e.getValue()) {
                ts.put(c.x);
                ts.put(c.y);
            }
            JSONObject o = new JSONObject();
            o.put("seg", e.getKey());
            o.put("t", ts);
            out.put(o);
        }
        return out;
    }

    /** Forgets everything learned. For when a base has been torn down and rebuilt. */
    public static void reset() {
        synchronized (LOCK) {
            walls = new HashMap<>();
            gates = new HashMap<>();
            dirty = true;
        }
        save();
    }
}
