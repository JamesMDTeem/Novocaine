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

    /** Nudge off a box's far edge, which belongs to the next tile along. See {@link #footprint}. */
    private static final double EDGE = 0.001;

    /**
     * How this file's tiles were worked out. Bumped when that changes, and anything older is
     * dropped rather than read.
     *
     * Version 1's footprints were doubled in both directions - see {@link #footprint} - so a file
     * written by it describes walls a tile thick as two tiles thick, offset by one. There is no
     * un-doubling that: the two tiles are indistinguishable once written, and a wall learned wrong
     * is worse than a wall not learned at all, because the bot acts on it with confidence. Anything
     * dropped here is re-learned by walking past it, which is a minute's work the bot does anyway.
     */
    private static final int VERSION = 2;

    /**
     * How much of a side of a barrier's bounding box has to be built on before that side counts
     * as a wall of an enclosure rather than a coincidence.
     */
    private static final double SIDE_COVER = 0.6;
    /** Smallest enclosure worth inferring, in tiles each way. Below this it is a pen, not a base. */
    private static final int MIN_RING = 16;

    /** segment -> segment tiles a wall stands on. */
    private static Map<Long, Set<Coord>> walls;
    /** segment -> segment tiles a gateway stands on. */
    private static Map<Long, Set<Coord>> gates;
    /** segment -> enclosures inferred from partly-learned walls. Null until worked out. */
    private static Map<Long, List<Ring>> rings;
    private static boolean dirty = false;

    /** A wall known only in part, completed into the enclosure it is evidently part of. */
    public static final class Ring {
        public final Coord lo, hi;

        Ring(Coord lo, Coord hi) {
            this.lo = lo;
            this.hi = hi;
        }

        /** Whether a block of tiles straddles this ring's perimeter. */
        boolean crossedBy(Coord blo, Coord bhi) {
            boolean spanx = (blo.x <= hi.x) && (bhi.x >= lo.x);
            boolean spany = (blo.y <= hi.y) && (bhi.y >= lo.y);
            if (!spanx || !spany)
                return false;
            return ((blo.x <= lo.x) && (bhi.x >= lo.x)) || ((blo.x <= hi.x) && (bhi.x >= hi.x))
                || ((blo.y <= lo.y) && (bhi.y >= lo.y)) || ((blo.y <= hi.y) && (bhi.y >= hi.y));
        }
    }

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

    /** True if a gateway stands on this segment tile. The counterpart to {@link #blocks}. */
    public static boolean isGateTile(long seg, Coord segTile) {
        synchronized (LOCK) {
            load();
            Set<Coord> s = gates.get(seg);
            return (s != null) && s.contains(segTile);
        }
    }

    /**
     * What a block of tiles amounts to, as far as getting past it goes.
     *
     * One question rather than three because the answers are ordered and the order is the point: a
     * gateway beats a wall standing in the same block, and a wall we have actually seen beats one
     * we have only worked out.
     *
     * @param lo, hi inclusive corners of the block, in segment tiles.
     */
    public static Block classify(long seg, Coord lo, Coord hi) {
        synchronized (LOCK) {
            load();
            Set<Coord> g = gates.get(seg);
            Set<Coord> w = walls.get(seg);
            boolean wall = false;
            for (int y = lo.y; y <= hi.y; y++) {
                for (int x = lo.x; x <= hi.x; x++) {
                    Coord t = new Coord(x, y);
                    if ((g != null) && g.contains(t))
                        return Block.GATE;
                    wall |= (w != null) && w.contains(t);
                }
            }
            if (wall)
                return Block.WALL;
            for (Ring r : ringsIn(seg)) {
                if (r.crossedBy(lo, hi))
                    return Block.RING;
            }
            return Block.CLEAR;
        }
    }

    public enum Block {
        /** Nothing in the way. */
        CLEAR,
        /** A wall we have seen. */
        WALL,
        /** A gateway. Passable, once somebody opens it. */
        GATE,
        /** Where a partly-learned wall must continue if it encloses anything. See {@link #infer}. */
        RING
    }

    /**
     * The enclosures the learned walls imply, worked out once and kept until something new is
     * learned.
     *
     * A base is a castle: the wall goes all the way round. A bot, though, only ever learns the
     * stretches it has walked past, and a bot that works in one corner learns one corner. This
     * character's record is a case in point - the whole south wall and the whole east wall, a
     * hundred tiles of each, and not one tile of the north or the west, because there has never
     * been any reason to go there. To the router that is not a base at all: it is two walls with
     * open country above and beside them, and the shortest way in is round the top. Which is what
     * the bot did, every time, and why it kept meeting wall it had never met before - each trip
     * learned a little more of the north side and the next one aimed just past what it had learned.
     *
     * So a barrier that has built out two ADJACENT sides of its own bounding box is taken to
     * enclose that box. Two adjacent sides is the least evidence that means anything: one side is
     * a plain wall between two fields and implies nothing, while a corner is only a corner if
     * something turns it. Gateways still win over the inferred sides, so the ways in stay open -
     * and being wrong here costs a route through where the router finds nothing and travel falls
     * back to walking at the target, which is exactly what it does today.
     */
    private static List<Ring> ringsIn(long seg) {
        if (rings == null)
            rings = new HashMap<>();
        List<Ring> hit = rings.get(seg);
        if (hit == null) {
            hit = infer(seg);
            rings.put(seg, hit);
        }
        return hit;
    }

    private static List<Ring> infer(long seg) {
        List<Ring> out = new ArrayList<>();
        Set<Coord> all = new HashSet<>();
        Set<Coord> w = walls.get(seg);
        Set<Coord> g = gates.get(seg);
        if (w != null)
            all.addAll(w);
        if (g != null)
            all.addAll(g);
        if (all.isEmpty())
            return out;

        Set<Coord> seen = new HashSet<>();
        for (Coord start : all) {
            if (!seen.add(start))
                continue;
            // One barrier, taken as everything touching it - a palisade's segments and posts and
            // gates are separate gobs standing shoulder to shoulder.
            List<Coord> comp = new ArrayList<>();
            List<Coord> stack = new ArrayList<>();
            stack.add(start);
            while (!stack.isEmpty()) {
                Coord c = stack.remove(stack.size() - 1);
                comp.add(c);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        Coord n = c.add(dx, dy);
                        if (all.contains(n) && seen.add(n))
                            stack.add(n);
                    }
                }
            }
            Coord lo = comp.get(0), hi = comp.get(0);
            for (Coord c : comp) {
                lo = new Coord(Math.min(lo.x, c.x), Math.min(lo.y, c.y));
                hi = new Coord(Math.max(hi.x, c.x), Math.max(hi.y, c.y));
            }
            int width = (hi.x - lo.x) + 1, height = (hi.y - lo.y) + 1;
            if ((width < MIN_RING) || (height < MIN_RING))
                continue;
            Set<Coord> tiles = new HashSet<>(comp);
            double north = cover(tiles, lo.x, hi.x, lo.y, true);
            double south = cover(tiles, lo.x, hi.x, hi.y, true);
            double west = cover(tiles, lo.y, hi.y, lo.x, false);
            double east = cover(tiles, lo.y, hi.y, hi.x, false);
            boolean corner =
                   ((north >= SIDE_COVER) && (east >= SIDE_COVER))
                || ((east >= SIDE_COVER) && (south >= SIDE_COVER))
                || ((south >= SIDE_COVER) && (west >= SIDE_COVER))
                || ((west >= SIDE_COVER) && (north >= SIDE_COVER));
            if (corner)
                out.add(new Ring(lo, hi));
        }
        return out;
    }

    /**
     * How much of one side of a bounding box is built on, as a fraction.
     *
     * Counted within a tile either way, because a wall does not run exactly along the extreme it
     * defines - it wanders a tile as it turns a corner or takes in a gateway, and demanding the
     * exact line would score a solid wall at nearly nothing.
     */
    private static double cover(Set<Coord> tiles, int from, int to, int at, boolean horizontal) {
        int on = 0;
        for (int i = from; i <= to; i++) {
            boolean any = false;
            for (int d = -1; (d <= 1) && !any; d++)
                any = tiles.contains(horizontal ? new Coord(i, at + d) : new Coord(at + d, i));
            if (any)
                on++;
        }
        return (double) on / Math.max(1, (to - from) + 1);
    }

    /**
     * True if this live world point stands inside an enclosure that the player does not.
     *
     * Not the same question as "can it be reached", and asked separately for that reason: routing
     * treats a gateway as passable, because opening one is somebody else's job, so everything
     * inside a base IS reachable and always will be. What this asks is whether getting there means
     * going through somebody's wall - and for a bot that collects things, the answer being yes is
     * a reason not to bother. What grows inside a palisade was put there; it is the base's timber
     * and the base's berries, not forage.
     *
     * Both sides are asked, so a bot standing in its own base still works there.
     */
    public static boolean walledOffFrom(GameUI gui, Coord2d point) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if ((me == null) || (here == null) || (point == null))
            return false;
        // The player is in both spaces at once, so one subtraction converts the other point.
        Coord there = point.add(here.sc.sub(me.rc)).floor(MCache.tilesz);
        Coord mine = here.sc.floor(MCache.tilesz);
        synchronized (LOCK) {
            load();
            Ring theirs = ringAt(here.seg, there);
            return (theirs != null) && (ringAt(here.seg, mine) != theirs);
        }
    }

    /** The inferred enclosure a segment tile lies within, or null. Caller holds {@link #LOCK}. */
    private static Ring ringAt(long seg, Coord tile) {
        for (Ring r : ringsIn(seg)) {
            if ((tile.x >= r.lo.x) && (tile.x <= r.hi.x)
                && (tile.y >= r.lo.y) && (tile.y <= r.hi.y))
                return r;
        }
        return null;
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
            if (added) {
                dirty = true;
                // Every inference rests on what is known, so a new stretch of wall retires the
                // lot. Cheap: they are worked out again on the next question asked.
                rings = null;
            }
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
        Coord2d pos = segPos(gui, here, g.rc);
        if (pos == null)
            return out;
        out.add(pos.floor(MCache.tilesz));
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
            /* Floored from the box's ABSOLUTE position, not by flooring the offsets and adding
             * the gob's tile. floor(a) + floor(b) is not floor(a + b), and for these boxes it is
             * never equal: a palisade segment's box is exactly one tile square and centred on the
             * gob, so its offsets run -5.5 to +5.5, which floor to -1 and 0 - two tiles per axis,
             * four tiles for a wall one tile square. Every wall, post and gate in the map came out
             * doubled in both directions and shifted a tile north-west, still covering its real
             * tile, which is why it all looked plausible. It is the reason a palisade read as two
             * tiles thick, an air lock's one-tile side stubs read as two, and a place drawn to
             * stop short of a wall was found to be standing on it.
             *
             * The far edge is exclusive. A box spanning exactly one tile ends on the boundary, and
             * a boundary belongs to the next tile along - so without this the correct arithmetic
             * still claims one tile too many. */
            Coord lo = pos.add(minx, miny).floor(MCache.tilesz);
            Coord hi = pos.add(maxx - EDGE, maxy - EDGE).floor(MCache.tilesz);
            // A barrier piece is a few tiles at most; anything larger is a bad box, not a wall.
            if (((hi.x - lo.x) > MAX_SPAN) || ((hi.y - lo.y) > MAX_SPAN))
                continue;
            for (int y = lo.y; y <= hi.y; y++) {
                for (int x = lo.x; x <= hi.x; x++)
                    out.add(new Coord(x, y));
            }
        }
        return out;
    }

    /**
     * A live world position in SEGMENT world units, using the player's own anchor as the reference
     * point. Both are in the same segment by construction - they are metres apart on screen - so
     * this is just an offset, and it avoids a map-file lookup per gob.
     *
     * Deliberately not floored to a tile here. Where a gob sits WITHIN its tile decides which
     * tiles its box covers, and a barrier is not always centred on one - a two-tile gate sits on
     * the boundary between them. Rounding first throws that away and then the box has to be
     * guessed at relative to a tile it may not be centred in.
     */
    private static Coord2d segPos(GameUI gui, WorldAnchor here, Coord2d wc) {
        try {
            Coord2d me = gui.map.player().rc;
            return wc.sub(me).add(here.sc);
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
            if (root.optInt("v", 1) != VERSION) {
                NLog.log("nbot.log", "barriers: dropping a v" + root.optInt("v", 1)
                    + " wall record - its footprints were worked out wrongly, re-learning");
                dirty = true;
                return;
            }
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
                root.put("v", VERSION);
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
            rings = null;
            dirty = true;
        }
        save();
    }
}
