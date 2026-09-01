package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Resource;
import haven.automated.helpers.CollisionGeom;
import haven.automated.helpers.HitBoxes;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.SharedFile;
import haven.automated.pathfinder.World;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the character has actually SEEN, tile by tile, remembered across sessions.
 *
 * This replaces guessing at the world from two thin sources - map-file terrain, which knows about
 * water and nothing else, and a list of wall-shaped gobs learned only during travel - with one
 * dense record of observation. The distinction it finally makes properly is between "there is
 * nothing here" and "nobody has looked here", which every routing decision turns on and which
 * neither of the old sources could express.
 *
 * Three things were wrong with what came before, and all three are the same mistake seen from
 * different angles:
 *
 * IT ONLY LOOKED WHILE TRAVELLING. The old sweep ran from exactly three places, all inside
 * travel: the start of a journey, after passing a gateway, and after a leg failed. So the record
 * grew only at the moments a bot was already in trouble, and the walls it walked past the rest of
 * the time - which is nearly all of them - were rendered on screen and never written down.
 *
 * IT ONLY RECOGNISED WALLS BY NAME. Anything under an {@code arch/} path whose name mentioned a
 * wall or a fence counted, and nothing else did. Measured against this install's own collision
 * data that is thirteen resources out of two hundred and thirty-one: every cupboard, kiln, chest,
 * stockpile, cart and boulder was invisible to routing, so routes were planned straight through
 * the furniture and the local pathfinder had to improvise around it on arrival. Here anything with
 * a collision box is solid, because it is.
 *
 * IT COULD NOT SAY WHAT IT DID NOT KNOW. Unseen ground read as walkable, so a route would happily
 * run through a palisade nobody had been near, and a reachability test could always find an
 * imaginary way round by wandering off the edge of the explored map. With observation recorded,
 * "we have looked at this" is a fact rather than a guess about radii.
 *
 * Recorded at TILE resolution, which is the resolution routing now works at. Finer would describe
 * gaps a character cannot fit through anyway, and the local pathfinder - which does exact geometry
 * against the same collision boxes - remains the authority on whether a particular step fits. This
 * layer chooses which side of the base to walk round; it does not choose footsteps.
 */
public class Observed {
    private static final String FILE = "botmap.json";
    private static final Object LOCK = new Object();
    private static final int VERSION = 1;

    /** Nobody has looked. Passable on sufferance, and expensive - see {@link Router}. */
    public static final byte UNSEEN = 0;
    /** Looked at, nothing solid standing on it. */
    public static final byte OPEN = 1;
    /** Something with a collision box stands here. */
    public static final byte SOLID = 2;
    /** A gateway. Solid until somebody opens it, which is the task layer's job, so: passable. */
    public static final byte GATE = 3;
    /**
     * A wall, fence or palisade - solid, and told apart from ordinary solids on purpose.
     *
     * Routing treats this and {@link #SOLID} identically, because both stop a character. The
     * difference is for {@link Barriers#walledOffFrom}, which asks whether something stands inside
     * somebody's enclosure: that question is about walls, and running its connected-component
     * analysis over cupboards and boulders as well would join a barn to a palisade and call a
     * hillside a base.
     */
    public static final byte WALL = 4;

    /**
     * Crossable, but with nowhere to STOP on it - the walkable channel runs off to one side.
     *
     * One byte was being asked two different questions and could only answer one. "Can a character
     * stand at this tile's centre" is the right question for a WAYPOINT, because a waypoint is a
     * tile centre and the server throws away a click that lands in an object. "Can a character get
     * across this tile" is the right question for a STEP, and the two come apart precisely where a
     * gap is tight: a channel a character fits through, running not through the middle of the tile
     * but along one edge of it.
     *
     * Answering the first for both is what produced the worst route in the logs - three tiles from
     * the water barrel with four such tiles in between, and a hundred and five tile detour around
     * the cheese racks to reach it. Every one of those four was crossable.
     *
     * So: SOLID when the object covers the whole tile, TIGHT when it only covers the middle. The
     * router may cross a TIGHT tile and is charged for the privilege; nothing may put a waypoint on
     * one, and {@link #solid} reports it as solid because every OTHER caller is asking the
     * standability question.
     */
    public static final byte TIGHT = 5;

    /**
     * How far out the character is taken to be able to SEE, in tiles.
     *
     * MEASURED, by {@link Sight}, from the distance at which objects are added to and removed from
     * the object cache - the transitions, so the radius itself rather than a sample of what happens
     * to be loaded. Over ten thousand of them: the furthest an object appears is 62.1 tiles, and
     * the furthest along either axis ALONE is 45.3 by 45.4. Those two numbers together say the
     * shape: 45 times root two is 64, so the region is a SQUARE of about forty-five tiles, and 62
     * is its corner rather than any radius.
     *
     * Which is why this is a square too, and why it may be as wide as forty-four. Being short is
     * not free, and this was short: at thirty-eight, the ring of ground between thirty-eight and
     * forty-five tiles was fully loaded - every object in it known, every wall, every barrel - and
     * recorded as never looked at, because the sweep did not reach that far. Unseen ground is
     * passable to the router by design, so routes went through it, and what the player saw was a
     * bot walking through a palisade a tile or two beyond the edge of the screen. The record was
     * not wrong about that ground; it had simply never been asked.
     *
     * Held one tile inside the measurement, because the cost of being short is a tile re-observed a
     * moment later and the cost of being long is ground recorded as empty because whatever stands
     * on it had not arrived yet.
     *
     * Worth writing down because it is easy to get from the wrong place, and every plausible source
     * is wrong. nurgling2's equivalent uses twenty-five tiles. The client's own pathfinder window is
     * forty-four, which is the TILE streaming range - terrain reaches a hundred and forty tiles, so
     * that is not it either. And what a player sees on screen is about forty, which is the RENDER
     * distance: the object cache holds a good deal more than is drawn.
     */
    private static final int SEES = 44;

    /**
     * The same figure, for anything that needs to ask "is that ground in view right now".
     *
     * Exposed because the alternative is every caller picking its own number, which is how this one
     * came to be measured in the first place - see the note above. {@link Place#observable} uses it
     * to tell "the area is empty" apart from "the area is out of range", which are the same scan
     * result and completely different facts.
     */
    public static int sees() {
        return SEES;
    }

    /** How often the sweep actually looks, in milliseconds. */
    private static final long SWEEP_MS = 1000;
    /** How often a changed record is written out, in milliseconds. */
    private static final long SAVE_MS = 5000;

    /** Nudge off a box's far edge, which belongs to the next tile along. See {@link #footprint}. */
    private static final double EDGE = 0.001;
    /** Tiles across. A collision box bigger than this is bad data, not a bigger object. */
    private static final int MAX_SPAN = 12;

    /**
     * The character's own half-width in world units - three of a tile's eleven.
     *
     * An ordinary object blocks a tile when it comes within this of the tile's CENTRE, which is the
     * only point on a tile the router ever aims at. See {@link #footprint} for why that rule, and
     * not "how much of the tile is covered", and not "every tile the grown box touches".
     *
     * Only walls and gateways are built on the tile grid. Everything a player puts down - a
     * stockpile, a barrel, a cupboard - lands wherever it was dropped, so a small object routinely
     * straddles an edge and clips a sliver off two or four tiles at once; blocking all of them makes
     * the object two or four times its real size and seals one-tile gaps a character walks through
     * without noticing. Walls are exempt and keep every tile they touch.
     *
     * Under-blocking is the safe direction and this is the layer to do it in: the local pathfinder
     * does exact geometry against the same boxes when the bot arrives, so a sliver missed here costs
     * a step around it. Over-blocking has no such second opinion - it deletes the route.
     */
    private static final double HALFWIDTH = World.HALFWIDTH;

    /**
     * WHAT is standing on each tile, beside the byte grid that says only THAT something is.
     *
     * The byte grid answers the routing question - can a character be here - in one byte per tile,
     * and that is the right shape for a search that reads millions of them. It is the wrong shape
     * for every other question. A map dump of a base is a solid block of {@code #} in which a house,
     * a palisade, a chest and a storage well are indistinguishable, so a route that walks into
     * something cannot be told from a route that walks into something ELSE - and diagnosing these
     * failures has meant guessing which, repeatedly and wrongly.
     *
     * So the same sweep that stamps the grid also records the resource behind each occupied tile.
     * Segment -> tile -> a short human label ("house", "well", "pclay", "palisade"). Nothing routes
     * off this; it exists so the logs can say what was hit.
     *
     * Session-scoped rather than persisted: the byte grid is the thing that has to survive a relog,
     * and an object list that outlived the objects would be worse than none. It refills the moment
     * anything is in sight.
     */
    private static final java.util.Map<Long, java.util.Map<Coord, String>> objects = new HashMap<>();

    /**
     * The exact rotated collision polygon of every remembered WALL, in segment-relative world units,
     * keyed by the wall gob's origin tile. This is the continuous layer that {@link #segmentHits}
     * serves: the byte grid says a wall is somewhere on this tile, and this says precisely where, so
     * a line that skirts a palisade by a couple of units can be pronounced clear instead of refused
     * for crossing the wall's whole tile.
     *
     * Session-scoped like {@link #objects} and for the same reason: refills from the sweep the moment
     * the wall is in sight, and a bot unstick-ing is always within sight of what it is backing away
     * from. The byte grid keeps the persisted WALL for the router; this layer only sharpens the
     * line-of-sight test.
     */
    private static final java.util.Map<Long, java.util.Map<Coord, java.util.List<Coord2d[]>>> exactWalls = new HashMap<>();

    /**
     * The exact rotated collision polygon of every remembered piece of FURNITURE (anything with a
     * hitbox that is neither a wall nor a gate), in segment-relative world units, keyed by the gob's
     * origin tile. Walls keep their own store ({@link #exactWalls}) because a wall's tile record is
     * deliberately over-covered; furniture is the opposite case — a rotated log stamps a whole tile
     * solid because its bounding box clips the tile CENTRE, while the true shape leaves a corner of
     * that tile free to stand on. {@link #solidPolygonsAt} + {@link #objectsHit} let the standable
     * check exempt exactly that corner instead of refusing the tile.
     *
     * Same session scope, sweep refill, and wipe-then-stamp rule as {@link #exactWalls}.
     */
    private static final java.util.Map<Long, java.util.Map<Coord, java.util.List<Coord2d[]>>> exactSolids = new HashMap<>();

    /** How many times the exact shape cleared an aim the tile record had certified solid — the
     *  over-conservatism counter, logged by the standable check so the validation run can see how
     *  often the record had been lying. */
    public static final java.util.concurrent.atomic.AtomicInteger exemptionCount = new java.util.concurrent.atomic.AtomicInteger();

    /** Throttle for the exemption-count heartbeat, so a furniture-heavy sweep does not spam. */
    private static volatile long lastExemptLog = 0;

    /** The stored polygons on a tile, or null if the record has no exact shape there. */
    public static java.util.List<Coord2d[]> solidPolygonsAt(long seg, Coord tile) {
        synchronized (LOCK) {
            java.util.Map<Coord, java.util.List<Coord2d[]>> segSolids = exactSolids.get(seg);
            return (segSolids == null) ? null : segSolids.get(tile);
        }
    }

    /** Whether the disc of radius {@code r} centred at {@code p} (segment-relative world units)
     *  overlaps any remembered furniture polygon. The standable question the tile record can only
     *  approximate. */
    public static boolean objectsHit(long seg, Coord2d p, double r) {
        synchronized (LOCK) {
            java.util.Map<Coord, java.util.List<Coord2d[]>> segSolids = exactSolids.get(seg);
            if (segSolids == null)
                return false;
            for (java.util.List<Coord2d[]> polys : segSolids.values())
                for (Coord2d[] poly : polys)
                    if (CollisionGeom.segmentHitsRadius(poly, p, p, r))
                        return true;
            return false;
        }
    }

    /**
     * Whether the segment a→b (segment-relative world coords) comes within {@code r} of any
     * remembered wall polygon. This replaces the tile-quantised "does the line cross a WALL tile"
     * test: a wall tile is eleven units wide, so a character backing out next to a palisade had every
     * one of its eight escape headings refused for touching a tile the wall only occupied the edge
     * of. The exact answer lets the heading that passes a couple of units clear through.
     */
    public static boolean segmentHits(long seg, Coord2d a, Coord2d b, double r) {
        synchronized (LOCK) {
            java.util.Map<Coord, java.util.List<Coord2d[]>> segWalls = exactWalls.get(seg);
            if (segWalls == null)
                return false;
            for (java.util.List<Coord2d[]> polys : segWalls.values())
                for (Coord2d[] poly : polys)
                    if (CollisionGeom.segmentHitsRadius(poly, a, b, r))
                        return true;
            return false;
        }
    }

    /** Labels already announced, so the log records each kind of thing once, not once per sweep. */
    private static final Set<String> announced = new HashSet<>();

    /** How many tiles each kind of thing accounts for, for the log. */
    private static java.util.Map<String, Integer> tally(java.util.Map<Coord, String> m) {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        for (String v : m.values())
            out.merge(v, 1, Integer::sum);
        return out;
    }

    /** What is standing on a tile, or null if nothing is recorded there. */
    public static String objectAt(long seg, Coord segTile) {
        synchronized (LOCK) {
            java.util.Map<Coord, String> m = objects.get(seg);
            return (m == null) ? null : m.get(segTile);
        }
    }

    /** Everything recorded in a segment, by tile. A copy - safe to iterate. */
    public static java.util.Map<Coord, String> objectsIn(long seg) {
        synchronized (LOCK) {
            java.util.Map<Coord, String> m = objects.get(seg);
            return (m == null) ? new HashMap<>() : new HashMap<>(m);
        }
    }

    /**
     * A resource name reduced to something a person reads at a glance.
     *
     * The last path element, with the variant digits and material suffixes taken off, so
     * "gfx/terobjs/arch/timberhouse" reads as "timberhouse" and four granite boulders collapse to
     * one word instead of four.
     */
    static String label(String res) {
        String tail = res.substring(res.lastIndexOf('/') + 1);
        tail = tail.replaceAll("[0-9]+$", "").replaceAll("-[a-z]$", "");
        return tail.isEmpty() ? res : tail;
    }

    private static final int GRID = MCache.cmaps.x * MCache.cmaps.y;

    /**
     * [LEAKDBG] Grids held in memory, for the heap-leak hunt. This one is expected to grow - the
     * botmap is a record of everywhere the crew has been - so the point of logging it is to
     * establish how fast, and to take it off the suspect list rather than leave it there.
     */
    public static int gridsz() {
        synchronized (LOCK) {
            if (map == null)
                return (0);
            int n = 0;
            for (Map<Coord, byte[]> seg : map.values())
                n += seg.size();
            return (n);
        }
    }

    /** segment -> segment grid coord -> one byte per tile, row-major. */
    private static Map<Long, Map<Coord, byte[]>> map;
    /**
     * The same shape, one bit per tile, set where THIS RUN has looked.
     *
     * The difference between what we know and what we have seen for ourselves, which is what makes
     * a crew's shared file safe to write to. See {@link #sync}.
     */
    private static Map<Long, Map<Coord, BitSet>> looked = new HashMap<>();
    private static boolean dirty = false;
    private static long swept = 0;
    private static long fileAt = -1;
    private static Thread saver = null;

    private Observed() {}

    // ------------------------------------------------------------------ asking

    /** What is known about a segment tile: one of {@link #UNSEEN}, {@link #OPEN} and so on. */
    public static byte at(long seg, Coord segTile) {
        synchronized (LOCK) {
            load();
            byte[] g = grid(seg, Terrain.floorDiv(segTile, MCache.cmaps), false);
            if (g == null)
                return UNSEEN;
            Coord in = segTile.sub(Terrain.floorDiv(segTile, MCache.cmaps).mul(MCache.cmaps));
            return g[(in.y * MCache.cmaps.x) + in.x];
        }
    }

    /**
     * One grid's raw tiles, or null if nothing has been seen in it.
     *
     * For a search that is about to ask about tens of thousands of tiles and should not take the
     * lock for each one. The array is live rather than a copy: a sweep running alongside may
     * change a byte under the reader, which is harmless - byte reads do not tear, and a search
     * seeing one tile a second fresher or staler than its neighbours cannot be wrong in any way
     * that matters.
     */
    static byte[] gridOf(long seg, Coord gc) {
        synchronized (LOCK) {
            load();
            return grid(seg, gc, false);
        }
    }

    /** True if something solid - and not a gateway - is known to stand here. */
    public static boolean solid(long seg, Coord segTile) {
        byte s = at(seg, segTile);
        // TIGHT included on purpose: every caller of this is asking "can we be here" - an aim to
        // nudge, a spot to stand, a place to work - and the answer for a tile with no standable
        // middle is no. The one caller asking "can we cross here" is Router.World.passable, which
        // inlines its own test rather than coming through here. See TIGHT.
        return (s == SOLID) || (s == WALL) || (s == TIGHT);
    }

    /** Every wall and gateway tile known in a segment, for the enclosure inference. */
    public static Set<Coord> barriersIn(long seg) {
        return tilesIn(seg, WALL, GATE);
    }

    /** True if a gateway stands here. */
    public static boolean gate(long seg, Coord segTile) {
        return at(seg, segTile) == GATE;
    }

    /**
     * True if this tile has ever been looked at with objects loaded.
     *
     * The question the old code kept trying to answer with a radius and getting wrong in both
     * directions. It is a recorded fact, so it can simply be asked.
     */
    public static boolean seen(long seg, Coord segTile) {
        return at(seg, segTile) != UNSEEN;
    }

    /** Every gateway tile known in a segment. The caller decides which is worth walking to. */
    public static Set<Coord> gatesIn(long seg) {
        return tilesIn(seg, GATE, GATE);
    }

    /** All segments that have any recorded data. */
    public static Set<Long> allSegments() {
        synchronized (LOCK) {
            load();
            return new HashSet<>(map.keySet());
        }
    }

    private static Set<Coord> tilesIn(long seg, byte a, byte b) {
        Set<Coord> out = new HashSet<>();
        synchronized (LOCK) {
            load();
            Map<Coord, byte[]> byseg = map.get(seg);
            if (byseg == null)
                return out;
            for (Map.Entry<Coord, byte[]> e : byseg.entrySet()) {
                byte[] g = e.getValue();
                Coord base = e.getKey().mul(MCache.cmaps);
                for (int i = 0; i < g.length; i++) {
                    if ((g[i] == a) || (g[i] == b))
                        out.add(base.add(i % MCache.cmaps.x, i / MCache.cmaps.x));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ looking

    /**
     * Records what is in sight. Called from the client's own tick, throttled.
     *
     * The sweep runs on the caller's thread because it is only a pass over the loaded gobs and
     * some array writes. The WRITE does not, because it re-reads the file to merge and doing that
     * from the client's tick would stutter it.
     */
    public static void tick(GameUI gui) {
        long now = System.currentTimeMillis();
        if ((now - swept) < SWEEP_MS)
            return;
        swept = now;
        try {
            observe(gui);
            // Shares this cadence rather than keeping a timer of its own; it wants the same rate
            // and the same "there is a world to look at" checks.
            Sight.tick(gui);
        } catch (RuntimeException e) {
            // Never let one bad gob take the client's tick down with it.
        }
        startSaver();
    }

    private static synchronized void startSaver() {
        if (saver != null)
            return;
        saver = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SAVE_MS);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    save();
                } catch (RuntimeException e) {
                    // Try again next pass rather than killing the thread.
                }
            }
        }, "botmap-saver");
        saver.setDaemon(true);
        saver.start();
    }

    /**
     * One look at the world.
     *
     * Two kinds of evidence, treated differently on purpose.
     *
     * NEGATIVE evidence - "there is nothing here" - is only worth anything where we could have
     * seen something, so the square within {@link #SEES} is wiped to {@link #OPEN} first and then
     * stamped. That is what makes the record self-correcting: a wall that has been torn down stops
     * being recorded the next time anybody walks past, where the old union-forever store would
     * have insisted on it for ever.
     *
     * POSITIVE evidence - "this thing is standing here" - is worth something wherever it arrives,
     * so solids and gateways outside that square are stamped too, and nothing outside it is ever
     * cleared. Being told about a wall is useful even from further away than we would trust an
     * absence.
     */
    public static void observe(GameUI gui) {
        if ((gui == null) || (gui.map == null) || (gui.ui == null) || (gui.ui.sess == null))
            return;
        Gob me = gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if ((me == null) || (here == null))
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

        /* Gateways collected first and stamped last. A wall segment's collision box overlaps its
         * own gate's, so whichever is written second wins - and in a single pass that was decided
         * by the order the object cache happened to hand the gobs over, which walled gates shut
         * with their own posts about half the time. */
        Set<Coord> solids = new HashSet<>(), walls = new HashSet<>(), gates = new HashSet<>();
        /* Crossable but not stoppable - see TIGHT. Gathered across every gob rather than per gob,
         * because a tile only counts as tight if NOTHING has swallowed it: two objects can each
         * leave a different edge free and between them leave nothing at all. Stamped after the
         * solids, and only where a solid did not already claim the tile. */
        Set<Coord> tights = new HashSet<>();
        java.util.Map<Coord, String> found = new HashMap<>();
        java.util.Map<Coord, java.util.List<Coord2d[]>> newExact = new HashMap<>();
        java.util.Map<Coord, java.util.List<Coord2d[]>> newSolids = new HashMap<>();
        Coord2d off = here.sc.sub(me.rc);
        for (Gob g : gobs) {
            try {
                Resource res = g.getres();
                if ((res == null) || mobile(res.name))
                    continue;
                if (phantom(res.name))
                    continue;
                HitBoxes.CollisionBoxSecondary[] boxes = HitBoxes.collisionBoxMap.get(res.name);
                if (boxes == null) {
                    /* No hitbox on file does NOT mean no obstacle - it means we do not know the
                     * SHAPE of one we can plainly see. Dropping the gob entirely recorded the
                     * ground under it as open, and that is the difference between our record and
                     * the server that every "walks into it, then re-plans" report comes down to:
                     * the route is drawn through a storage well, a potter's wheel or the fringe of
                     * a chest, the click is thrown away because it landed on the object, the leg
                     * fails, and the re-plan plots the same line again because the record still
                     * says open. It is also why a journey arrives as three short hops instead of
                     * one - each collision costs a leg and a re-plan.
                     *
                     * So record what we do know: something is standing on that tile. One tile is
                     * the conservative floor, not the truth - a big object covers more - but a tile
                     * of honest obstacle beats a hole in the record, and the router will route
                     * around it instead of into it.
                     *
                     * Forage is the exception, and has to be: herbs and crops are walked over, and
                     * blocking them would wall a bot off from the very things it is sent to pick.
                     *
                     * The real repair is a hitbox for these resources in hitboxes.db; until then
                     * this keeps the record on the safe side of wrong. */
                    if (walkOver(res.name))
                        continue;
                    solids.add(g.rc.add(off).floor(MCache.tilesz));
                    continue;
                }
                Barriers.Kind k = Barriers.kind(res.name);
                Set<Coord> into = (k == Barriers.Kind.GATE) ? gates
                    : (k == Barriers.Kind.WALL) ? walls : solids;
                // kind() is null for anything that is not part of a barrier - see HALFWIDTH.
                Set<Coord> tiles = footprint(g, off, boxes, k == null, tights);
                into.addAll(tiles);
                // Remember the exact shape too, for the continuous checks. Walls are the one thing
                // the tile grid deliberately over-covers (a palisade is a whole tile), so their tile
                // record is wrong in the direction that refuses a line reality allows - that is the
                // lineClear exemption. Furniture is the opposite: a rotated log stamps a tile solid
                // because its bounding box clips the tile centre, while the true shape leaves a
                // corner free to stand on - that is the standable exemption. Gates are never stored
                // (their live state, not their shape, decides).
                if (k != Barriers.Kind.GATE) {
                    Coord2d pos = g.rc.add(off);
                    java.util.List<Coord2d[]> exact = new java.util.ArrayList<>();
                    for (HitBoxes.CollisionBoxSecondary box : boxes) {
                        if ((box == null) || (box.coords == null) || (box.coords.length < 3) || !box.hitAble)
                            continue;
                        exact.add(CollisionGeom.worldPolygon(box.coords, pos, g.a));
                    }
                    if (!exact.isEmpty()) {
                        Coord originTile = pos.floor(MCache.tilesz);
                        if (k == Barriers.Kind.WALL)
                            newExact.computeIfAbsent(originTile, t -> new java.util.ArrayList<>()).addAll(exact);
                        else
                            newSolids.computeIfAbsent(originTile, t -> new java.util.ArrayList<>()).addAll(exact);
                    }
                }
                // The same tiles, remembered by WHAT put them there - see the objects registry.
                String lbl = label(res.name);
                for (Coord t : tiles)
                    found.put(t, lbl);
                for (Coord t : tights)
                    found.putIfAbsent(t, lbl);
            } catch (RuntimeException e) {
                // Includes Loading: a gob whose resource has not arrived is picked up next sweep.
            }
        }

        Coord mine = here.sc.floor(MCache.tilesz);
        /* How far out we may claim ground is EMPTY, from how far out objects have actually been
         * delivered - not from a constant.
         *
         * A constant was wrong in a way that only shows up once it is nearly right. Objects arrive
         * in chunks rather than sliding smoothly: cross one tile and a band several tiles deep
         * appears at the leading edge and vanishes behind, so the real boundary is not a fixed
         * radius but one that jumps about within a band. A measurement of its MAXIMUM - which is
         * what SEES was raised to - is therefore the one value guaranteed to be outside it most of
         * the time. And wiping past the boundary is not a small error: it records ground as empty
         * BECAUSE nothing has loaded there, which is the precise opposite of what it means, and the
         * ground most affected is where a wall sits at the edge of sight. The wall is erased, a
         * route is planned through the gap, the wall re-appears as the bot approaches, and the
         * route changes underneath it. That is a bot walking at a palisade and turning away, and
         * doing it again from the other side.
         *
         * So the sweep asks what has arrived. The furthest object on each axis is a lower bound on
         * the boundary that costs nothing to compute and cannot be stale, and staying inside it
         * means every tile wiped is one we could have seen something on.
         *
         * In empty country this shrinks a long way, and that is correct rather than unfortunate:
         * with nothing loaded there is no evidence of emptiness to record. Unseen ground is still
         * passable to the router, just no longer asserted. */
        /* A rectangle, not a square: each side is bounded by what has actually been delivered on
         * that side. See loadedReach - a square let a well-furnished direction authorise wiping an
         * empty one, and erased the wall the bot was standing in front of. */
        int[] r = loadedReach(gobs, me);
        final int west = r[0], east = r[1], north = r[2], south = r[3];
        synchronized (LOCK) {
            load();
            for (int y = -north; y <= south; y++) {
                for (int x = -west; x <= east; x++)
                    set(here.seg, mine.add(x, y), OPEN);
            }
            /* The object registry follows the same wipe-then-stamp rule as the grid, and for the
             * same reason: inside the swept box our knowledge is current, so anything recorded there
             * and no longer seen is gone. Outside it we know nothing new and must not touch what is
             * remembered. */
            java.util.Map<Coord, String> reg = objects.computeIfAbsent(here.seg, s -> new HashMap<>());
            for (int y = -north; y <= south; y++) {
                for (int x = -west; x <= east; x++)
                    reg.remove(mine.add(x, y));
            }
            reg.putAll(found);
            // The exact wall store follows the same wipe-then-stamp rule: inside the swept box our
            // knowledge is current, so walls no longer seen there leave it; outside it nothing is
            // touched. Keyed by the wall's origin tile so the wipe reaches the same tiles as the grid.
            java.util.Map<Coord, java.util.List<Coord2d[]>> segExact = exactWalls.computeIfAbsent(here.seg, s -> new HashMap<>());
            for (int y = -north; y <= south; y++) {
                for (int x = -west; x <= east; x++)
                    segExact.remove(mine.add(x, y));
            }
            segExact.putAll(newExact);
            // Furniture's exact shapes follow the same wipe-then-stamp rule, keyed by origin tile.
            java.util.Map<Coord, java.util.List<Coord2d[]>> segSolids = exactSolids.computeIfAbsent(here.seg, s -> new HashMap<>());
            for (int y = -north; y <= south; y++) {
                for (int x = -west; x <= east; x++)
                    segSolids.remove(mine.add(x, y));
            }
            segSolids.putAll(newSolids);
            /* Each KIND announced once. A line per sweep would be several a second saying the same
             * thing; a line the first time a house or a storage well is ever recorded is the thing
             * actually worth having, because it says what the solid blocks in a map dump are made
             * of - which is exactly what could not be told before. */
            for (java.util.Map.Entry<String, Integer> e : tally(found).entrySet()) {
                if (announced.add(e.getKey()))
                    haven.automated.nbots.core.NLog.log("observed.log", "first sighting of "
                        + e.getKey() + " - " + e.getValue() + " tile(s) in this sweep");
            }
            // Heartbeat for the over-conservatism counter: how often the exact shape overrode a
            // tile the record had certified solid. Zero means the tile record never lied; a number
            // means the exact store is doing its job and the standable check is trusting it.
            long nowEx = System.currentTimeMillis();
            if ((exemptionCount.get() > 0) && ((nowEx - lastExemptLog) >= 10000)) {
                lastExemptLog = nowEx;
                haven.automated.nbots.core.NLog.log("observed.log", "exemption count now "
                    + exemptionCount.get() + " (exact shape overrode a solid tile record)");
            }
            /* Stamped weakest first. A wall segment's box overlaps its own gate's and a gate's
             * posts, so whichever is written last wins, and a gateway recorded as solid seals a
             * base for good. Tight ground, then furniture, then walls, then gateways.
             *
             * Tight goes FIRST because it is the weakest claim of the four - "something is in the
             * middle of this" - and any of the others standing on the same tile is a stronger one
             * that must survive. Two objects each leaving a different edge free leave no way
             * across between them, and the second one's SOLID overwriting the first one's TIGHT is
             * exactly how that comes out right. */
            for (Coord t : tights)
                set(here.seg, t, TIGHT);
            for (Coord t : solids)
                set(here.seg, t, SOLID);
            for (Coord t : walls)
                set(here.seg, t, WALL);
            for (Coord t : gates)
                set(here.seg, t, GATE);
        }
    }

    /**
     * Things that walk about, and are therefore not the map.
     *
     * This is the one exception to "anything with a collision box is solid", and it has to be, in a
     * way the old name test hid: the check it replaced skipped only OUR OWN character, because
     * {@code isPlgob} means "is the gob the camera follows". Every other character, and every
     * animal, has a collision box - one of the two hundred and thirty-one this install knows is
     * {@code gfx/borka/body} and fourteen more are wildlife - so all of them were being written into
     * a PERSISTENT, SHARED record of where the walls are.
     *
     * That is worse than it sounds, and specifically worse for a crew. Inside sight the next sweep
     * wipes the mistake, so it looks harmless while you watch it; but the wipe only reaches
     * {@link #SEES}, so whatever a bot happened to be beside as it walked out of range froze there
     * for good, and got saved, and got shared. A crewmate standing at a barrel became a wall in
     * everybody's map. Hours of walking would silt the file up with ghosts of each other.
     *
     * Losing them costs nothing, because nothing wanted them here: wildlife is routed around by
     * {@link Hazards} at path time from live positions, and other characters by {@link Crowd},
     * both of which read the world as it is now rather than as it was remembered.
     *
     * Two kritter resources are placed objects rather than creatures, and those stay.
     */
    static boolean mobile(String res) {
        if (res.startsWith("gfx/borka/"))
            return true;
        if (!res.startsWith("gfx/kritter/"))
            return false;
        return !(res.endsWith("/anthill") || res.endsWith("/wildbeehive"));
    }

    /**
     * Every segment tile a gob's collision box stands on.
     *
     * Floored from the box's ABSOLUTE position rather than by flooring the offsets and adding the
     * gob's own tile. {@code floor(a) + floor(b)} is not {@code floor(a + b)}, and for these boxes
     * it never is: a palisade segment's box is one tile square and centred on the gob, so its
     * offsets run -5.5 to +5.5, which floor to -1 and 0 - two tiles per axis, four tiles for a
     * wall one tile square. That doubled every wall, post and gate in the map and shifted it a
     * tile north-west, while still covering the real tile, which is why it looked plausible.
     *
     * The far edge is exclusive. A box spanning exactly one tile ends on a tile boundary, and a
     * boundary belongs to the next tile along.
     */
    private static Set<Coord> footprint(Gob g, Coord2d off, HitBoxes.CollisionBoxSecondary[] boxes,
                                        boolean partial) {
        return footprint(g, off, boxes, partial, new HashSet<>());
    }

    /**
     * @param tight collects tiles that are crossable but have no standable middle. See {@link
     *              #TIGHT}. Walls never produce any - they are whole tiles by construction.
     */
    private static Set<Coord> footprint(Gob g, Coord2d off, HitBoxes.CollisionBoxSecondary[] boxes,
                                        boolean partial, Set<Coord> tight) {
        Set<Coord> out = new HashSet<>();
        Coord2d pos = g.rc.add(off);
        if (!partial)
            out.add(pos.floor(MCache.tilesz));
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
            /* An object blocks a tile when a character CANNOT STAND AT ITS CENTRE - the box grown
             * by the character's half-width, tested against the one point that matters.
             *
             * Two rules were tried before this and both were wrong in the same place. Asking how
             * much of the tile the raw box covers, and blocking at a third of it, answers a question
             * nobody asks: a storage well can put two and a half units of itself into the next tile
             * along - a fifth of it, comfortably under the threshold, so the tile reads open - and
             * still leave nowhere within three units of that tile's centre to put a character. The
             * route aims there, the server refuses the click before a search starts, the leg fails,
             * the re-plan draws the same line because the record still says open, and the journey
             * arrives in three broken hops. Growing the box and then keeping every tile it OVERLAPS
             * fixes that and breaks more than it mends: it turns a one-tile palisade segment into
             * three tiles by three and seals the one-tile gaps a character walks through.
             *
             * Every waypoint this record feeds is a tile centre, so make the record answer for tile
             * centres exactly. A palisade segment keeps its own tile and leaves its neighbours alone
             * (their centres are eleven units off, well clear of eight and a half); the well's south
             * neighbour is blocked, because its centre really is inside the well plus a character.
             *
             * Walls are exempt and keep every tile they touch. They are the one thing built ON the
             * grid, they are already whole tiles, and under-blocking one is a licence to route
             * through a palisade built a few units off true.
             *
             * Three units, the same figure Router.HALFWIDTH and Map.plbbox use. Note that Router's
             * own line checks sweep the same half-width along a leg: that is the CONTINUOUS layer
             * doing it for the space between waypoints, this is the grid layer doing it for the
             * waypoints themselves. Neither is redundant, and neither should be widened to cover
             * for the other - do it in both and the character is modelled at twice its real width,
             * which is the previous rule's second bug. */
            double grow = partial ? HALFWIDTH : 0;
            Coord2d blo = pos.add(minx - grow, miny - grow);
            Coord2d bhi = pos.add(maxx + grow, maxy + grow);
            Coord lo = blo.floor(MCache.tilesz);
            Coord hi = pos.add((maxx + grow) - EDGE, (maxy + grow) - EDGE).floor(MCache.tilesz);
            if (((hi.x - lo.x) > MAX_SPAN) || ((hi.y - lo.y) > MAX_SPAN))
                continue;
            for (int y = lo.y; y <= hi.y; y++) {
                for (int x = lo.x; x <= hi.x; x++) {
                    if (!partial) {
                        out.add(new Coord(x, y));
                        continue;
                    }
                    if (!holds(blo, bhi, grow, x, y))
                        continue;
                    /* The middle is taken; whether the WHOLE tile is decides which kind of taken.
                     *
                     * A tile the grown box covers entirely cannot be crossed. A tile it only
                     * covers the middle of can - along whichever edge the box leaves free - and
                     * calling those two the same thing is what put a hundred-tile detour between
                     * the bot and a barrel three tiles away. See TIGHT. */
                    (swallows(blo, bhi, grow, x, y) ? out : tight).add(new Coord(x, y));
                }
            }
        }
        return out;
    }

    /**
     * How far out objects have actually been delivered, in tiles, in EACH of the four directions:
     * {@code [west, east, north, south]}.
     *
     * Per direction, because a single symmetric figure is the bug this method used to have. It took
     * {@code max|dx|} and {@code max|dy|} - each mixing a direction with its opposite - and wiped a
     * SQUARE of that half-width all round. So one object to the east and one to the south together
     * authorised wiping eight tiles NORTH, where nothing had loaded at all.
     *
     * That is not a corner case, it is where a bot stands: inside a base beside its own north wall,
     * the furniture behind it supplies the whole reach, and the palisade in front - not yet
     * delivered, because it is at the edge of sight - is recorded as OPEN. The router then plans
     * straight through it. Observed live: a route aimed at x=-10565, seven tiles west of the only
     * gateway, refused four times against the palisade and wedged 188u short, with ten gobs loaded.
     *
     * Erasing a real wall is much worse than remembering a torn-down one - it records ground as
     * empty BECAUSE nothing has loaded there, which is the exact opposite of what it means - so
     * every direction is bounded by its own evidence and nothing else's.
     *
     * Anything that moves is left out. A wolf trotting past at fifty tiles is not evidence that the
     * ground at fifty tiles has been delivered - it is evidence about the wolf, which was somewhere
     * else a moment ago.
     */
    private static int[] loadedReach(List<Gob> gobs, Gob me) {
        double w = 0, e = 0, n = 0, s = 0;
        for (Gob g : gobs) {
            try {
                Resource res = g.getres();
                if ((res == null) || mobile(res.name))
                    continue;
                double dx = g.rc.x - me.rc.x, dy = g.rc.y - me.rc.y;
                if (dx < 0)
                    w = Math.max(w, -dx);
                else
                    e = Math.max(e, dx);
                if (dy < 0)
                    n = Math.max(n, -dy);
                else
                    s = Math.max(s, dy);
            } catch (RuntimeException e0) {
                // Not resolved yet; it says nothing either way.
            }
        }
        return new int[] {tiles(w), tiles(e), tiles(n), tiles(s)};
    }

    /**
     * A delivery distance in units as a number of tiles we are willing to call swept - one tile
     * INSIDE the furthest thing seen, since that object proves its own tile arrived and nothing
     * whatever about the one beyond it.
     */
    private static int tiles(double units) {
        return Math.min(SEES, Math.max(0, (int) (units / MCache.tilesz.x) - 1));
    }

    /**
     * Things a character walks straight over, so an unknown hitbox on one means "no obstacle"
     * rather than "obstacle of unknown shape".
     *
     * Kept deliberately short. The cost of leaving something off this list is a tile of ground
     * needlessly avoided; the cost of putting something on it wrongly is the bot walking into that
     * thing for ever, which is the failure this whole path exists to stop - so anything not plainly
     * walk-over stays off.
     */
    /**
     * Things carrying a collision box that the SERVER does not actually stop anyone with.
     *
     * A different question from {@link #walkOver}, which answers "we do not know this thing's
     * shape". Here the shape is on file and correct, and the ground is still walkable - so the box
     * must not reach the grid at all, or we record an obstacle the game does not have and route
     * around a fire the character would have strolled straight through.
     *
     * Hearth fires ({@code pow}) are the case this exists for, and EVERY hearth fire belongs here,
     * not only ours: none of them collide, so none of them block. Whose it is matters only when
     * choosing one to travel to, which is a question for the task layer and has nothing to do with
     * the map.
     *
     * Kept as short and as specific as {@link #walkOver}, and for the same reason: an entry here is
     * a promise that walking into the thing is free, and a wrong promise is a bot pushing at
     * something solid for ever.
     */
    private static boolean phantom(String name) {
        return name.equals("gfx/terobjs/pow") || name.equals("gfx/terobjs/powr");
    }

    private static boolean walkOver(String name) {
        return name.startsWith("gfx/terobjs/herbs/")
            || name.startsWith("gfx/terobjs/plants/")
            || name.startsWith("gfx/terobjs/items/")
            || name.startsWith("gfx/invobjs/");
    }

    /**
     * Whether an axis-aligned box contains a tile's CENTRE - the tile's one standable point.
     *
     * Called with the box already grown by the character's half-width, so "contains the centre"
     * reads as "a character standing in the middle of this tile would be inside the object". See
     * {@link #footprint}.
     */
    private static boolean holds(Coord2d lo, Coord2d hi, double grow, int tx, int ty) {
        /* The CENTRE, and it has to be the centre.
         *
         * This was briefly changed to "the box overlaps the tile at all", to catch a destination
         * that was refused on a tile we called clear. That was wrong, and wrong in a way worth
         * recording, because the reasoning sounded good.
         *
         * A character has ONE hitbox and collision is ONE test, so any point it can pass through is
         * a point it can stand on. There is no such thing as passable-but-not-standable space, and
         * a rule that marks a tile unstandable because a box clips its corner - while its centre is
         * perfectly free - is not describing the world. TIGHT is not about the character at all: it
         * is about ADDRESSING. A waypoint can only ever be a tile centre, because that is the only
         * point the grid can name, so the question this answers is "is the one point we are able to
         * aim at inside the object" - and the answer for a corner-clipped tile is no.
         *
         * Crossing is checked continuously and separately - clear()/along() sample the real line at
         * quarter-tile against the real half-width - so nothing is lost by keeping this narrow. The
         * grid answers where we may STOP; the line check answers where we may GO.
         *
         * The overlap version tripled the tight tiles in a route (5 -> 27 on one leg), which was
         * enough to expose an unbounded loop in Router.simplify and to leave the LP assistant
         * unable to stand next to bushes it had picked from all session.
         *
         * The destination case it was meant to fix is real and is NOT a grid problem: a destination
         * is an arbitrary world point, not a tile centre, so it wants an exact test against the box
         * rather than a coarser rule about its tile. That belongs in the continuous layer. */
        double cx = (tx * MCache.tilesz.x) + (MCache.tilesz.x / 2);
        double cy = (ty * MCache.tilesz.y) + (MCache.tilesz.y / 2);
        /* Distance to the box, against a ROUND character - not "inside the box grown by grow".
         *
         * Growing a rectangle by a square over-blocks its corners, and the character is not square.
         * Observed in play: turning on the spot lets the corners of our modelled box enter an
         * object while the game goes on treating the position as legal, which is what a DISC of
         * radius {@link #HALFWIDTH} looks like when you have been drawing a square around it. The
         * error is worst exactly at a corner and is worth 3*(sqrt(2)-1), about 1.24 units - a
         * ninth of a tile, which is plenty to lose a tile that was standable all along.
         *
         * So: grow the box by a disc, which is a rounded rectangle, and test the centre against it.
         * The nearest-point-on-box distance below is that test written the cheap way. Sides behave
         * exactly as before - it is only corners that stop being over-claimed.
         *
         * lo/hi arrive already grown by grow, so un-grow them to recover the real box. */
        double x0 = lo.x + grow, x1 = hi.x - grow;
        double y0 = lo.y + grow, y1 = hi.y - grow;
        double dx = Math.max(0, Math.max(x0 - cx, cx - x1));
        double dy = Math.max(0, Math.max(y0 - cy, cy - y1));
        return ((dx * dx) + (dy * dy)) <= (grow * grow);
    }

    /**
     * Whether an axis-aligned box contains a tile ENTIRELY - so there is no way across it either.
     *
     * The companion to {@link #holds}, and the difference between the two is the whole of {@link
     * #TIGHT}. Called with the box already grown by the character's half-width, so "contains the
     * tile" reads as "a character could not be anywhere on this tile, not even passing through".
     */
    private static boolean swallows(Coord2d lo, Coord2d hi, double grow, int tx, int ty) {
        /* Round, like {@link #holds} - the character is a disc, and a tile only has no way across
         * it if EVERY point of it is within the character's half-width of the box. For a rectangle
         * the extreme points are the four corners, so testing those settles it.
         *
         * Was the square-grown box, which is the same over-claim {@code holds} used to make and in
         * the same place: at the corners. It matters less here - a tile has to be fully covered to
         * be SOLID, so the corners are rarely what decides it - but leaving the two tests
         * disagreeing means a tile can be too enclosed to stand on and not enclosed enough to be
         * solid by DIFFERENT geometry, which is not a distinction anyone intended.
         *
         * lo/hi arrive already grown by grow; un-grow to recover the real box. */
        double bx0 = lo.x + grow, bx1 = hi.x - grow;
        double by0 = lo.y + grow, by1 = hi.y - grow;
        double x0 = tx * MCache.tilesz.x, y0 = ty * MCache.tilesz.y;
        double x1 = x0 + MCache.tilesz.x, y1 = y0 + MCache.tilesz.y;
        double r2 = grow * grow;
        return within(bx0, bx1, by0, by1, x0, y0, r2) && within(bx0, bx1, by0, by1, x1, y0, r2)
            && within(bx0, bx1, by0, by1, x0, y1, r2) && within(bx0, bx1, by0, by1, x1, y1, r2);
    }

    /** Whether a point is within {@code sqrt(r2)} of the box - the disc test {@link #holds} uses. */
    private static boolean within(double bx0, double bx1, double by0, double by1,
                                  double px, double py, double r2) {
        double dx = Math.max(0, Math.max(bx0 - px, px - bx1));
        double dy = Math.max(0, Math.max(by0 - py, py - by1));
        return ((dx * dx) + (dy * dy)) <= r2;
    }

    /** Caller holds {@link #LOCK}. */
    private static void set(long seg, Coord segTile, byte v) {
        Coord gc = Terrain.floorDiv(segTile, MCache.cmaps);
        byte[] g = grid(seg, gc, true);
        Coord in = segTile.sub(gc.mul(MCache.cmaps));
        int i = (in.y * MCache.cmaps.x) + in.x;
        // Marked whether or not the value changed: having looked and found what we expected is
        // still having looked, and it is the looking that entitles us to overrule the file.
        mask(seg, gc).set(i);
        if (g[i] != v) {
            g[i] = v;
            dirty = true;
        }
    }

    /** Caller holds {@link #LOCK}. */
    private static BitSet mask(long seg, Coord gc) {
        return looked.computeIfAbsent(seg, k -> new HashMap<>())
            .computeIfAbsent(gc, k -> new BitSet(GRID));
    }

    /** Caller holds {@link #LOCK}. */
    private static byte[] grid(long seg, Coord gc, boolean make) {
        Map<Coord, byte[]> byseg = map.get(seg);
        if (byseg == null) {
            if (!make)
                return null;
            byseg = new HashMap<>();
            map.put(seg, byseg);
        }
        byte[] g = byseg.get(gc);
        if ((g == null) && make) {
            g = new byte[GRID];
            byseg.put(gc, g);
        }
        return g;
    }

    // ------------------------------------------------------------------ persistence

    private static Path file() {
        return Paths.get(FILE);
    }

    /** Caller holds {@link #LOCK}. */
    private static void load() {
        if (map != null)
            return;
        map = new HashMap<>();
        read(map);
    }

    private static boolean read(Map<Long, Map<Coord, byte[]>> into) {
        try {
            Path f = file();
            if (!Files.exists(f))
                return true;
            JSONObject root = new JSONObject(
                new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            if (root.optInt("v", 0) != VERSION)
                return true;   // an older shape; start again rather than misread it
            JSONArray segs = root.optJSONArray("segs");
            if (segs == null)
                return true;
            for (int i = 0; i < segs.length(); i++) {
                JSONObject so = segs.getJSONObject(i);
                long seg = so.getLong("seg");
                Map<Coord, byte[]> byseg = into.computeIfAbsent(seg, k -> new HashMap<>());
                JSONArray grids = so.optJSONArray("grids");
                if (grids == null)
                    continue;
                for (int j = 0; j < grids.length(); j++) {
                    JSONObject go = grids.getJSONObject(j);
                    JSONArray gc = go.getJSONArray("gc");
                    byte[] g = unrle(go.getJSONArray("rle"));
                    if (g != null)
                        byseg.put(new Coord(gc.getInt(0), gc.getInt(1)), g);
                }
            }
            return true;
        } catch (IOException e) {
            // Locked by another client mid-write, or a disk hiccup. Not grounds for treating what
            // is in the file as gone - see save().
            return false;
        } catch (RuntimeException e) {
            NLog.crash("parsing " + FILE, e);
            return true;
        }
    }

    /**
     * Reconciles with the file: takes the crew's word for everything we have not looked at
     * ourselves, then writes back if we have anything of our own to add.
     *
     * A crew is several clients, and the naive shape - load once at startup, own a copy, write it
     * out - is wrong in both directions at once. Outwards, a plain rewrite means the last to save
     * wins and everything the others saw is lost. Inwards, nobody ever learns anything after
     * startup, so two bots working the same base spend the session with two divergent maps of it.
     *
     * Merging on the value alone does not fix it, and the version that did ("anything beats
     * UNSEEN") had a failure mode worth naming, because it is the one that bites a crew hardest. A
     * client writes back every tile it holds, including the thousands it loaded at startup and has
     * not been near since. So when one bot walks past a wall that has come down and records the
     * ground as open, the next save by any OTHER client - which still holds the old wall, untouched,
     * from its own startup read - puts it straight back. The correction and the stale copy trade
     * places for as long as both clients run, and which one a route sees is down to who saved last.
     *
     * So the unit of authority is not the value, it is HAVING LOOKED. A client asserts only the
     * tiles it has observed this run; everything else it holds is a cache of the file and is
     * replaced by it. Two clients can only disagree about ground they have both looked at, and then
     * they are both current and either answer is right.
     *
     * Sharing falls out of the same move: adopting the file on every pass means one bot's discovery
     * is in every crewmate's router within a few seconds, without a protocol, a server, or anything
     * to keep in sync.
     */
    private static void save() {
        synchronized (LOCK) {
            if (map == null)
                return;
            /* Re-reading a file nobody has touched, to merge in what it already told us, is the
             * one thing here that could cost real time - it happens every few seconds for the
             * length of a session. The modification time settles it for nothing. */
            long stamp = stamp();
            if (!dirty && (stamp != UNKNOWN) && (stamp == fileAt))
                return;

            if (!dirty) {
                /* Nothing of ours to say - we only came to adopt what a crewmate wrote. A reader
                 * cannot corrupt anybody, so it does not queue behind the lock. */
                Map<Long, Map<Coord, byte[]>> disk = new HashMap<>();
                if (read(disk)) {
                    fileAt = stamp;
                    adopt(disk);
                }
                return;
            }

            /* We are going to write, so the read-merge-write has to be atomic against the other
             * clients doing exactly the same thing every few seconds. Two clients out of one
             * install directory is the normal case for a crew, and without the lock their merges
             * interleave: each reads before the other's write lands, and whichever renames last
             * silently drops the other's exploration. The AccessDeniedException that turned up in
             * a friend's crash.log was the loud half of that same race - Windows refusing the
             * rename while the other process had the file open. */
            try (SharedFile.Held held = SharedFile.lock(file())) {
                if (held == null) {
                    NLog.log("observed.log", "couldn't lock " + FILE
                        + " to save; still dirty, retrying on the next pass");
                    return;
                }
                /* Only re-read when somebody else has written since we last
                 * did. What is on disk is otherwise what we last wrote, already
                 * folded into the map we are about to write out - reading it
                 * back would merge our own state into itself.
                 *
                 * Worth checking because the read is not cheap: this file
                 * reaches several megabytes on a well-explored world, and
                 * parsing it allocates it several times over as a String and
                 * then as a tree of JSON objects. Doing that every five seconds
                 * for the length of a session made this thread the second
                 * largest allocator in the client, at 134MB/s against the
                 * render thread's 370 - which is a curious place for a
                 * background bookkeeping task to be, and it was buying GC
                 * pauses for everybody.
                 *
                 * Re-stamped inside the lock rather than trusting the check
                 * above it: another client can write in the window between
                 * wanting the lock and holding it, and that write is exactly
                 * the one we must not drop. */
                long locked = stamp();
                if ((locked == UNKNOWN) || (locked != fileAt)) {
                    Map<Long, Map<Coord, byte[]>> disk = new HashMap<>();
                    if (!read(disk))
                        return;    // cannot see what we would be overwriting, so do not. Still dirty.
                    adopt(disk);
                }
                fileAt = locked;

                JSONArray segs = new JSONArray();
                for (Map.Entry<Long, Map<Coord, byte[]>> se : map.entrySet()) {
                    JSONArray grids = new JSONArray();
                    for (Map.Entry<Coord, byte[]> ge : se.getValue().entrySet()) {
                        JSONObject go = new JSONObject();
                        go.put("gc", new JSONArray(new int[] {ge.getKey().x, ge.getKey().y}));
                        go.put("rle", rle(ge.getValue()));
                        grids.put(go);
                    }
                    JSONObject so = new JSONObject();
                    so.put("seg", se.getKey());
                    so.put("grids", grids);
                    segs.put(so);
                }
                JSONObject root = new JSONObject();
                root.put("v", VERSION);
                root.put("segs", segs);
                SharedFile.writeAtomic(file(), root.toString().getBytes(StandardCharsets.UTF_8));
                dirty = false;
                // Our own write moved it on; without this every pass would re-read what we wrote.
                fileAt = stamp();
            } catch (IOException | RuntimeException e) {
                NLog.crash("saving " + FILE, e);
            }
        }
    }

    /**
     * Folds what is on disk into what we hold, letting our own observations win.
     *
     * A tile this run has actually looked at is ours to state; every other tile we defer to the
     * file on, which is how a crewmate's discovery reaches this client without a protocol. Caller
     * holds {@link #LOCK}, and - if it intends to write - the cross-process lock too.
     */
    private static void adopt(Map<Long, Map<Coord, byte[]>> disk) {
        for (Map.Entry<Long, Map<Coord, byte[]>> se : disk.entrySet()) {
            Map<Coord, byte[]> ours = map.computeIfAbsent(se.getKey(), k -> new HashMap<>());
            for (Map.Entry<Coord, byte[]> ge : se.getValue().entrySet()) {
                byte[] theirs = ge.getValue();
                byte[] mine = ours.get(ge.getKey());
                if (mine == null) {
                    ours.put(ge.getKey(), theirs);
                    continue;
                }
                BitSet seen = mask(se.getKey(), ge.getKey());
                for (int i = 0; i < mine.length; i++) {
                    if (!seen.get(i))
                        mine[i] = theirs[i];
                }
            }
        }
    }

    /** A modification time that can't be asked for. Never equal to the last one, so never skipped. */
    private static final long UNKNOWN = Long.MIN_VALUE;

    /** When the file last changed, -1 if there isn't one, {@link #UNKNOWN} if it can't be told. */
    private static long stamp() {
        try {
            Path f = file();
            return Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : -1;
        } catch (IOException | RuntimeException e) {
            return UNKNOWN;
        }
    }

    /**
     * Run-length encoded, because a grid is ten thousand tiles and almost all of them say the same
     * thing as the one before. A base with walls through it comes out as a few hundred numbers.
     */
    private static JSONArray rle(byte[] g) {
        JSONArray out = new JSONArray();
        int i = 0;
        while (i < g.length) {
            int j = i;
            while ((j < g.length) && (g[j] == g[i]))
                j++;
            out.put((int) g[i]);
            out.put(j - i);
            i = j;
        }
        return out;
    }

    private static byte[] unrle(JSONArray a) {
        if (a == null)
            return null;
        byte[] g = new byte[GRID];
        int at = 0;
        for (int i = 0; (i + 1) < a.length(); i += 2) {
            byte v = (byte) a.getInt(i);
            int n = a.getInt(i + 1);
            for (int k = 0; (k < n) && (at < g.length); k++)
                g[at++] = v;
        }
        return g;
    }

    /**
     * Forgets everything seen. For when a base has been torn down and rebuilt.
     *
     * Clears what we have looked at as well, and it has to: keeping the mask would leave us
     * asserting a blank map over ground we no longer hold any observation of, which would wipe the
     * file for every other client too. Dropping both means the next pass adopts whatever the crew
     * has and we start over from what we can actually see.
     */
    public static void reset() {
        synchronized (LOCK) {
            map = new HashMap<>();
            looked = new HashMap<>();
            // The object registry too, or "forget everything" would leave the labels for a base that
            // has just been torn down attached to tiles the grid no longer says anything about.
            objects.clear();
            announced.clear();
            fileAt = UNKNOWN;
            dirty = true;
        }
        save();
    }

    /**
     * Returns a defensive copy of the loaded map data for diagnostic tools.
     * Does not trigger a load if the map is not already in memory.
     *
     * @return Map of segment -> (grid coordinate -> tile state array), or null if not loaded.
     */
    public static Map<Long, Map<Coord, byte[]>> loadMapData() {
        synchronized (LOCK) {
            if (map == null)
                return null;
            Map<Long, Map<Coord, byte[]>> copy = new HashMap<>();
            for (Map.Entry<Long, Map<Coord, byte[]>> segEntry : map.entrySet()) {
                Map<Coord, byte[]> segCopy = new HashMap<>();
                for (Map.Entry<Coord, byte[]> gridEntry : segEntry.getValue().entrySet()) {
                    segCopy.put(gridEntry.getKey(), gridEntry.getValue().clone());
                }
                copy.put(segEntry.getKey(), segCopy);
            }
            return copy;
        }
    }
}
