package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapWnd;

/**
 * A position in the world that stays meaningful after the place stops being rendered.
 *
 * The problem this solves: everything else in these bots addresses the world through {@code Gob.rc}
 * or a raw {@code Coord2d}, both of which are expressed relative to the CURRENT session's floating
 * map origin. Walk far enough that the water barrel unloads and its gob is gone; walk far enough
 * that the client re-bases its coordinates and the raw Coord2d you wrote down points at open field.
 * Either way "go back to the barrel" has nothing left to aim at - which is exactly the case the
 * water refill has to survive, since the whole point is that the work site and the water are far
 * enough apart to be a journey.
 *
 * The fix is to store the position the way the client's own persistent map already stores things:
 * as an offset inside a map SEGMENT. {@link MapFile#gridinfo} maps a live grid id to
 * {@code (segment, segment-relative grid coord)}, and segment coordinates don't move - they are
 * what {@link MapFile.Marker} is anchored by, which is why a custom map marker still points at the
 * right hill after a relog. So an anchor records the segment plus a position within it, and
 * resolves back to live world coordinates by asking where the PLAYER currently is in that same
 * segment and applying the difference. Only the player's own grid has to be loaded for that, never
 * the anchor's.
 *
 * Anchors are immutable and safe to hand between the bot threads and the UI thread.
 */
public class WorldAnchor {
    /** The map segment (continent) this position belongs to. */
    public final long seg;
    /** Position within the segment, in world units (tiles * MCache.tilesz), not tiles. */
    public final Coord2d sc;

    public WorldAnchor(long seg, Coord2d sc) {
        this.seg = seg;
        this.sc = sc;
    }

    /**
     * Records a live world position as a segment anchor, or null if the map file doesn't yet know
     * which segment we're standing in.
     *
     * A null return is normal rather than exceptional - gridinfo is written as the map is explored,
     * so a grid the client has only just streamed in may not have its entry yet. Callers are
     * expected to retry on the next pass rather than treat it as a failure.
     */
    public static WorldAnchor capture(GameUI gui, Coord2d wc) {
        MapFile file = mapfile(gui);
        if (file == null || wc == null)
            return null;
        try {
            MCache mcache = gui.ui.sess.glob.map;
            Coord tc = wc.floor(MCache.tilesz);
            Coord gc = tc.div(MCache.cmaps);
            long gridid = mcache.getgrid(gc).id;

            MapFile.GridInfo info;
            file.lock.readLock().lock();
            try {
                info = file.gridinfo.get(gridid);
            } finally {
                file.lock.readLock().unlock();
            }
            if (info == null)
                return null;

            // The grid's segment-space tile origin, plus where inside the grid the tile sits, plus
            // where inside the tile the exact point sits. Keeping the sub-tile part matters for a
            // barrel: a tile is 11 units across and the fill action wants us adjacent to the gob,
            // not merely on the right tile.
            Coord segtile = info.sc.mul(MCache.cmaps).add(tc.sub(gc.mul(MCache.cmaps)));
            Coord2d intra = wc.sub(tc.mul(MCache.tilesz));
            return new WorldAnchor(info.seg, segtile.mul(MCache.tilesz).add(intra));
        } catch (Loading | NullPointerException e) {
            return null;
        }
    }

    /**
     * Another live position in the same segment, worked out from this anchor rather than from the
     * map file.
     *
     * The inverse of {@link #resolve}, and it exists because {@link #capture} needs the DESTINATION'S
     * grid to be loaded and in gridinfo, which for anything more than a screen away it routinely is
     * not. That turned a transient streaming state into a permanent verdict: {@code plan} gave up
     * with "the map file can't place it yet", travel fell back to a single probe hop, and the journey
     * was abandoned. One logged session hit that twenty-four times and the cleanup bot cleared
     * nothing at all in thirty-two minutes - not because anywhere was unreachable, but because the
     * router was never asked.
     *
     * It does not need the map file. Live world coordinates are one continuous space per session, so
     * the difference between two live positions IS the difference between their segment positions;
     * apply it to a segment position we already trust - the player's, whose grid is loaded by
     * definition - and the answer is exact, for free, at any distance.
     *
     * The one thing it cannot do is notice that the target is in a DIFFERENT segment, since it
     * assumes the one it was called on. So it stays a fallback: ask {@link #capture} first, which is
     * authoritative when the grid is there, and come here when it is not.
     *
     * @param mine this anchor's position in live world coordinates (i.e. where the player is)
     * @param wc   the live position to convert
     */
    public WorldAnchor offsetTo(Coord2d mine, Coord2d wc) {
        if ((mine == null) || (wc == null))
            return null;
        return new WorldAnchor(seg, sc.add(wc.sub(mine)));
    }

    /** The player's own position as a segment anchor, or null if it can't be resolved right now. */
    public static WorldAnchor capturePlayer(GameUI gui) {
        if (gui == null || gui.map == null || gui.map.player() == null)
            return null;
        return capture(gui, gui.map.player().rc);
    }

    /**
     * Where this anchor is in live world coordinates right now, or null if it can't be worked out.
     *
     * Null means one of three things, all of which callers handle the same way (wait and retry, or
     * report that the destination is unreachable): the map file isn't ready, the player's own grid
     * has no segment record yet, or - the interesting case - the player is in a DIFFERENT segment
     * from the anchor. The last one is genuine: segments are separate coordinate spaces (different
     * continents, or two halves of a map that haven't been stitched together yet), and there is no
     * offset between them to apply. Walking from one to the other isn't something a bot can plan.
     */
    public Coord2d resolve(GameUI gui) {
        if (gui == null || gui.map == null)
            return null;
        haven.Gob me = gui.map.player();
        if (me == null)
            return null;
        WorldAnchor here = capture(gui, me.rc);
        if (here == null || here.seg != seg)
            return null;
        return me.rc.add(sc.sub(here.sc));
    }

    /** True if this anchor is in the same segment as the player and so can be resolved at all. */
    public boolean reachable(GameUI gui) {
        return resolve(gui) != null;
    }

    /**
     * The anchor of a named custom map marker, or null if there's no such marker.
     *
     * Lets a player point a bot at a water source (or anything else) using the map UI they already
     * know, instead of a bot-specific coordinate picker: drop a marker, name it, tell the bot the
     * name. Markers already carry exactly the (segment, tile) pair an anchor needs.
     *
     * Matched case-insensitively on the whole name, so "water" finds a marker called "Water".
     */
    public static WorldAnchor ofMarker(GameUI gui, String name) {
        MapFile file = mapfile(gui);
        if (file == null || name == null || name.isEmpty())
            return null;
        file.lock.readLock().lock();
        try {
            for (MapFile.Marker m : file.markers) {
                if (m.nm != null && m.nm.equalsIgnoreCase(name))
                    // Marker tile coords address the tile, so aim at its centre rather than its
                    // top-left corner - half a tile off is enough to miss a barrel's reach.
                    return new WorldAnchor(m.seg, m.tc.mul(MCache.tilesz).add(MCache.tilesz.div(2)));
            }
        } finally {
            file.lock.readLock().unlock();
        }
        return null;
    }

    static MapFile mapfile(GameUI gui) {
        if (gui == null)
            return null;
        MapWnd wnd = gui.mapfile;
        return (wnd == null) ? null : wnd.file;
    }

    // Persisted into the client's preference store by the bots that remember a source across
    // sessions. Deliberately a flat string rather than anything structured - it's two numbers and a
    // point, and a hand-editable pref is easier to reason about than a serialized blob.
    public String store() {
        return Long.toHexString(seg) + ":" + sc.x + ":" + sc.y;
    }

    public static WorldAnchor parse(String s) {
        if (s == null || s.isEmpty())
            return null;
        String[] p = s.split(":");
        if (p.length != 3)
            return null;
        try {
            return new WorldAnchor(Long.parseUnsignedLong(p[0], 16),
                new Coord2d(Double.parseDouble(p[1]), Double.parseDouble(p[2])));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String toString() {
        return "anchor(seg " + Long.toHexString(seg) + " @ " + (int) sc.x + "," + (int) sc.y + ")";
    }
}
