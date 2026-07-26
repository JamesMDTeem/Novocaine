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
 * Gobs are NOT in the map file, so walls and gates are not visible here. Those are learned and
 * persisted separately; see {@link Barriers}, which this class consults so that callers only ever
 * ask one question.
 */
public class Terrain {
    private static final Object LOCK = new Object();

    /** segment -> segment grid coord -> per-tile walkability. Only successful reads are cached. */
    private static final Map<Long, Map<Coord, boolean[]>> cache = new HashMap<>();

    /**
     * True if this segment tile can be walked on.
     *
     * @param segTile a tile coordinate in segment space, as {@link WorldAnchor#sc} divides down to.
     */
    public static boolean walkable(GameUI gui, long seg, Coord segTile) {
        Coord gc = floorDiv(segTile, MCache.cmaps);
        boolean[] g = grid(gui, seg, gc);
        if (g == null)
            return true;
        Coord in = segTile.sub(gc.mul(MCache.cmaps));
        return g[(in.y * MCache.cmaps.x) + in.x] && !Barriers.blocks(seg, segTile);
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

    private static boolean[] grid(GameUI gui, long seg, Coord gc) {
        synchronized (LOCK) {
            Map<Coord, boolean[]> byseg = cache.get(seg);
            if (byseg != null) {
                boolean[] hit = byseg.get(gc);
                if (hit != null)
                    return hit;
            }
        }
        boolean[] built = build(gui, seg, gc);
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
    private static boolean[] build(GameUI gui, long seg, Coord gc) {
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
            boolean[] out = new boolean[MCache.cmaps.x * MCache.cmaps.y];
            for (int y = 0; y < MCache.cmaps.y; y++) {
                for (int x = 0; x < MCache.cmaps.x; x++) {
                    int t = g.gettile(new Coord(x, y));
                    String name = ((t >= 0) && (t < g.tilesets.length) && (g.tilesets[t] != null)
                        && (g.tilesets[t].res != null)) ? g.tilesets[t].res.name : null;
                    out[(y * MCache.cmaps.x) + x] = !haven.automated.pathfinder.Map.isWater(name);
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
