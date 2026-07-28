package haven.automated.nbots.world;

import haven.Coord;
import haven.GameUI;
import haven.MCache;
import haven.MapFile;

import java.util.HashMap;
import java.util.Map;

/**
 * Whether a tile can be walked on, answered from the map FILE rather than from what is loaded.
 *
 * This is the piece that makes routing possible at all. Hurricane's pathfinder works on an 88-tile
 * window built from live gobs and tiles, so it can only answer questions about ground the client is
 * currently rendering. A bot walking back to a barrel three hundred tiles away needs an answer
 * about ground it has not seen since it left, and asking MCache for it would only make the client
 * request those grids from the server.
 *
 * MapFile already stores what is needed: every explored grid is persisted with its tileset
 * resource NAMES and its tile indices, keyed by segment, which is the same coordinate space
 * {@link WorldAnchor} works in. So terrain the character has ever walked past stays answerable
 * forever, offline, without a single server request.
 *
 * Unknown ground answers WALKABLE, deliberately. This layer picks a rough line across the
 * continent; the local pathfinder still gets its veto tile by tile when the bot actually gets
 * there. Refusing to route across never-explored ground would strand a bot that only has to cross
 * a corner of it, which is the worse failure - being wrong here costs a detour, being wrong the
 * other way costs the trip.
 *
 * It is not, however, free. A route that crosses unexplored ground is a guess, and a guess that
 * went wrong is what put a bot in the water: it routed around a river onto ground it had never
 * loaded, the deep water carried on there, and it swam. So {@link #known} says which answers were
 * read and which were assumed, and {@link Router} charges more for the assumed ones - which keeps
 * the shortcut available when it is the only way through, and stops it being taken merely because
 * nothing was there to argue.
 *
 * Gobs are NOT in the map file, so walls and gates are not visible here. Those are learned and
 * persisted separately; see {@link Barriers}, which this class consults so that callers only ever
 * ask one question.
 */
public class Terrain {
    private static final Object LOCK = new Object();

    /** Ground that is fine. */
    static final byte DRY = 0;
    /** Wadeable or swimmable - passable, but only when the run is willing to get wet. */
    static final byte SHALLOW = 1;
    /** Impassable to anyone on foot, whatever the settings say. */
    static final byte DEEP = 2;

    /**
     * segment -> segment grid coord -> per-tile water class. Only successful reads are cached.
     *
     * The CLASS is cached rather than the yes/no answer, because the answer depends on the water
     * setting and the setting can be changed between one shift and the next. Caching "walkable"
     * would have to be thrown away every time that happened - or, worse, quietly wouldn't be.
     */
    private static final Map<Long, Map<Coord, byte[]>> cache = new HashMap<>();

    /**
     * True if this segment tile can be walked on.
     *
     * Shallow water follows the same setting the local pathfinder does, so the two layers agree:
     * a route planned across a ford is one the pathfinder will actually walk, and with the setting
     * on neither will offer it. Deep water is refused by both regardless.
     *
     * @param segTile a tile coordinate in segment space, as {@link WorldAnchor#sc} divides down to.
     */
    public static boolean walkable(GameUI gui, long seg, Coord segTile) {
        return ground(gui, seg, segTile) && !Barriers.blocks(seg, segTile);
    }

    /**
     * The GROUND alone - water and nothing else. Separate from {@link #walkable} because a
     * gateway's node has to be able to ask about the ground under it without the wall it stands in
     * answering first; see {@link Router}.
     */
    public static boolean ground(GameUI gui, long seg, Coord segTile) {
        Coord gc = floorDiv(segTile, MCache.cmaps);
        byte[] g = grid(gui, seg, gc);
        if (g == null)
            return true;
        Coord in = segTile.sub(gc.mul(MCache.cmaps));
        byte w = g[(in.y * MCache.cmaps.x) + in.x];
        if (w == DEEP)
            return false;
        return (w != SHALLOW) || !haven.automated.pathfinder.Map.BLOCK_WATER;
    }

    /**
     * True if the map file could actually answer for this tile.
     *
     * The distinction {@link #walkable} deliberately throws away: a false here means the "yes" it
     * gave was an assumption. Cheap to ask alongside it, since both go through the same one-grid
     * cache and a route asks about tiles in the same grid over and over.
     */
    public static boolean known(GameUI gui, long seg, Coord segTile) {
        return grid(gui, seg, floorDiv(segTile, MCache.cmaps)) != null;
    }

    /** True if the map file records impassable water here - the answer no setting changes. */
    public static boolean deep(GameUI gui, long seg, Coord segTile) {
        Coord gc = floorDiv(segTile, MCache.cmaps);
        byte[] g = grid(gui, seg, gc);
        if (g == null)
            return false;
        Coord in = segTile.sub(gc.mul(MCache.cmaps));
        return g[(in.y * MCache.cmaps.x) + in.x] == DEEP;
    }

    /**
     * Integer division that rounds towards negative infinity.
     *
     * Coord.div truncates towards zero, which is the same thing only for positive coordinates.
     * Segment coordinates are signed and routinely negative - the origin is wherever the character
     * first logged in on that continent - so truncating would fold the tiles either side of an
     * axis into the same grid and read walkability off the wrong one.
     */
    static Coord floorDiv(Coord c, Coord d) {
        return new Coord(Math.floorDiv(c.x, d.x), Math.floorDiv(c.y, d.y));
    }

    /**
     * One grid's per-tile water classes, or null if the map file cannot answer for it yet.
     *
     * For a search that is about to ask about tens of thousands of tiles: going through
     * {@link #ground} per tile takes a lock and divides down to a grid every time, which turns a
     * few milliseconds of arithmetic into a second of lock traffic.
     */
    public static byte[] classes(GameUI gui, long seg, Coord gc) {
        return grid(gui, seg, gc);
    }

    private static byte[] grid(GameUI gui, long seg, Coord gc) {
        synchronized (LOCK) {
            Map<Coord, byte[]> byseg = cache.get(seg);
            if (byseg != null) {
                byte[] hit = byseg.get(gc);
                if (hit != null)
                    return hit;
            }
        }
        byte[] built = build(gui, seg, gc);
        if (built != null) {
            synchronized (LOCK) {
                cache.computeIfAbsent(seg, k -> new HashMap<>()).put(gc, built);
            }
        }
        return built;
    }

    /**
     * Reads one grid out of the map file.
     *
     * A null return means "not known right now" and is NOT cached: the grid may simply not have
     * finished loading from disk yet, and caching that would make the miss permanent for the rest
     * of the session.
     */
    private static byte[] build(GameUI gui, long seg, Coord gc) {
        MapFile file = WorldAnchor.mapfile(gui);
        if (file == null)
            return null;
        try {
            MapFile.Segment s;
            file.lock.readLock().lock();
            try {
                s = file.segments.get(seg);
            } finally {
                file.lock.readLock().unlock();
            }
            if (s == null)
                return null;
            MapFile.Grid g = s.grid(gc).get();
            if (g == null || g.tilesets == null)
                return null;
            byte[] out = new byte[MCache.cmaps.x * MCache.cmaps.y];
            for (int y = 0; y < MCache.cmaps.y; y++) {
                for (int x = 0; x < MCache.cmaps.x; x++) {
                    int t = g.gettile(new Coord(x, y));
                    String name = ((t >= 0) && (t < g.tilesets.length) && (g.tilesets[t] != null)
                        && (g.tilesets[t].res != null)) ? g.tilesets[t].res.name : null;
                    byte w = DRY;
                    if (haven.automated.pathfinder.Map.isDeep(name))
                        w = DEEP;
                    else if (haven.automated.pathfinder.Map.isShallow(name))
                        w = SHALLOW;
                    out[(y * MCache.cmaps.x) + x] = w;
                }
            }
            return out;
        } catch (RuntimeException e) {
            // Loading is itself a RuntimeException here - a grid still coming off disk lands in
            // exactly this branch, which is the "not known right now" the caller wants.
            return null;
        }
    }

    /** Drops the cache. For when the map file has been rewritten under us. */
    public static void forget() {
        synchronized (LOCK) {
            cache.clear();
        }
    }
}
