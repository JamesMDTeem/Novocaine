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
 * <h2>Segment ids are private to one client, so the grid id is recorded too</h2>
 *
 * A segment id is {@code rnd.nextLong()}, picked by whichever client first walked onto unmapped
 * ground ({@link MapFile} "creating new segment"). It identifies a stretch of world only inside
 * the map file that invented it. That is fine while an anchor stays in the client that captured
 * it, and wrong the moment one is written to a file a CREW shares: a place drawn by one character
 * carried a segment id the other characters' map files had never heard of, {@link #resolve}
 * compared it against their own and returned null, and {@link Places#nearest} skips a place it
 * cannot resolve - so the area showed up in everyone's Bot Places list, with its roles ticked,
 * and every bot but the one that drew it reported there was nowhere tagged for water. A real
 * botplaces.json had "Water" on segment 508e650b14c823d1 and everything else on
 * f41511251cc75885, two coordinate spaces in one shared file.
 *
 * So an anchor also records the GRID it sits in, by the id the SERVER assigns, plus the offset
 * within that grid. Grid ids are the same number in every client connected to the world, which
 * makes them the one durable name for a position across a crew. Resolution then asks the LOCAL
 * map file which segment that grid is in, instead of trusting the segment the anchor was written
 * with - so it no longer matters which client drew the place.
 *
 * Anchors are immutable and safe to hand between the bot threads and the UI thread.
 */
public class WorldAnchor {
    /**
     * The map segment (continent) this position belongs to, AS THE CAPTURING CLIENT NAMED IT.
     *
     * Still carried because the {@link Observed} terrain layer is keyed by it and because
     * {@link #offsetTo} works in segment space, but it is no longer the primary way an anchor is
     * resolved - see the class notes. Never compare one client's segment id with another's.
     */
    public final long seg;
    /** Position within the segment, in world units (tiles * MCache.tilesz), not tiles. */
    public final Coord2d sc;
    /**
     * The server's id for the grid this position sits in, or 0 when it isn't known.
     *
     * Zero for anchors from {@link #offsetTo} (which never sees a live grid) and for anchors read
     * back from files written before this was recorded. Those fall back to the old segment
     * comparison, which is right within one client and no worse than it was across several.
     */
    public final long gid;
    /** Position within {@link #gid}'s grid, in world units. Meaningless when {@code gid} is 0. */
    public final Coord2d off;

    public WorldAnchor(long seg, Coord2d sc) {
        this(seg, sc, 0, Coord2d.z);
    }

    public WorldAnchor(long seg, Coord2d sc, long gid, Coord2d off) {
        this.seg = seg;
        this.sc = sc;
        this.gid = gid;
        this.off = (off == null) ? Coord2d.z : off;
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
            // Where in the GRID the point sits, alongside where in the segment. The grid id is
            // what another client can look up; the segment part is what this one resolves fastest
            // from, and what the Observed terrain layer is keyed by.
            Coord2d ingrid = wc.sub(gc.mul(MCache.cmaps).mul(MCache.tilesz));
            return new WorldAnchor(info.seg, segtile.mul(MCache.tilesz).add(intra), gridid, ingrid);
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
        // Ask OUR map file where the anchor's grid is, rather than believing the segment id the
        // anchor was written with - which belongs to whichever client drew it. See class notes.
        //
        // First because it is the cheap one: two hash lookups against a grid id, where the loaded-
        // grid scan below walks every streamed-in grid. That matters more than it looks - contains()
        // resolves the anchor once per GOB when a place is scanned, so a linear scan here is a
        // per-frame cost multiplied by the population of the base.
        Coord2d live = byGridinfo(gui, me);
        if (live != null)
            return live;
        // Then the anchor's own grid, if it happens to be streamed in: exact rather than inferred,
        // and the one path that needs no map file at all - so it still answers on a client whose
        // map file has never recorded this ground.
        live = byLoadedGrid(gui);
        if (live != null)
            return live;
        // Anchors written before grid ids were recorded, and anchors from offsetTo. Correct within
        // the client that made them; across a crew it fails exactly as it always did. Reached with
        // a grid id too, since a map file that has lost the grid's entry can still be right about
        // the segment the anchor named.
        WorldAnchor here = capture(gui, me.rc);
        if (here == null || here.seg != seg)
            return null;
        return me.rc.add(sc.sub(here.sc));
    }

    /**
     * This anchor's position via its own grid, when that grid happens to be loaded.
     *
     * Linear over the loaded grids, which is why {@link #resolve} tries the map file first.
     */
    private Coord2d byLoadedGrid(GameUI gui) {
        if (gid == 0)
            return null;
        try {
            MCache.Grid g = gui.ui.sess.glob.map.gridbyid(gid);
            return (g == null) ? null : g.ul.mul(MCache.tilesz).add(off);
        } catch (Loading | NullPointerException e) {
            return null;
        }
    }

    /**
     * This anchor's position via the LOCAL map file's opinion of where its grid sits.
     *
     * Both grids - the anchor's and the player's - are looked up in the same map file, so the two
     * segment ids being compared were assigned by the same client and the comparison means
     * something. That is the whole difference from the old path, which compared a stored id
     * against a local one. Null when this client has never mapped the anchor's grid, or when the
     * two grids really are in different segments (separate continents, or two halves of a map that
     * have not been stitched together yet) - there is genuinely no offset to apply then.
     */
    private Coord2d byGridinfo(GameUI gui, haven.Gob me) {
        if (gid == 0)
            return null;
        MapFile file = mapfile(gui);
        if (file == null)
            return null;
        try {
            MCache mcache = gui.ui.sess.glob.map;
            Coord mygc = me.rc.floor(MCache.tilesz).div(MCache.cmaps);
            long mygid = mcache.getgrid(mygc).id;
            MapFile.GridInfo mine, theirs;
            file.lock.readLock().lock();
            try {
                mine = file.gridinfo.get(mygid);
                theirs = file.gridinfo.get(gid);
            } finally {
                file.lock.readLock().unlock();
            }
            if ((mine == null) || (theirs == null) || (mine.seg != theirs.seg))
                return null;
            // Grid coords within one segment, so the difference is exact and distance-independent.
            Coord dg = theirs.sc.sub(mine.sc);
            Coord2d myul = mygc.mul(MCache.cmaps).mul(MCache.tilesz);
            return myul.add(dg.mul(MCache.cmaps).mul(MCache.tilesz)).add(off);
        } catch (Loading | NullPointerException e) {
            return null;
        }
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
    // sessions, and into botplaces.json by Place. Deliberately a flat string rather than anything
    // structured - it's a few numbers and a point, and a hand-editable value is easier to reason
    // about than a serialized blob.
    //
    // Six fields when the grid is known, three when it isn't. Values written by the older
    // three-field code are read here unchanged.
    //
    // It does NOT go the other way - the old parse required exactly three fields and rejects a
    // six-field value outright - which is why {@link Place} does not use this for the file a CREW
    // shares. See segpart()/gridpart(): botplaces.json keeps the two halves in separate JSON keys
    // so a client on an older build reads the places instead of dropping them.
    public String store() {
        String head = segpart();
        return (gid == 0) ? head : (head + ":" + gridpart());
    }

    /**
     * Just the segment half, in the three-field form every build of this client has ever read.
     *
     * For files several clients share: a crew does not update every client in the same minute, and
     * a place an older client cannot PARSE is a place it silently drops from the list entirely -
     * which would be a worse bug than the one this all exists to fix. Written on its own, the
     * worst an old build does is behave exactly as it does today.
     */
    public String segpart() {
        return Long.toHexString(seg) + ":" + sc.x + ":" + sc.y;
    }

    /** Just the grid half, for storing alongside {@link #segpart}. Empty when there is no grid. */
    public String gridpart() {
        return (gid == 0) ? "" : (Long.toHexString(gid) + ":" + off.x + ":" + off.y);
    }

    /** Rebuilds an anchor from the two halves, tolerating a missing or unparseable grid half. */
    public static WorldAnchor parse(String segpart, String gridpart) {
        WorldAnchor a = parse(segpart);
        if ((a == null) || (gridpart == null) || gridpart.isEmpty())
            return a;
        String[] p = gridpart.split(":");
        if (p.length != 3)
            return a;
        try {
            return new WorldAnchor(a.seg, a.sc, Long.parseUnsignedLong(p[0], 16),
                new Coord2d(Double.parseDouble(p[1]), Double.parseDouble(p[2])));
        } catch (NumberFormatException e) {
            return a;
        }
    }

    public static WorldAnchor parse(String s) {
        if (s == null || s.isEmpty())
            return null;
        String[] p = s.split(":");
        if ((p.length != 3) && (p.length != 6))
            return null;
        try {
            long seg = Long.parseUnsignedLong(p[0], 16);
            Coord2d sc = new Coord2d(Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            if (p.length == 3)
                return new WorldAnchor(seg, sc);
            return new WorldAnchor(seg, sc, Long.parseUnsignedLong(p[3], 16),
                new Coord2d(Double.parseDouble(p[4]), Double.parseDouble(p[5])));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String toString() {
        return "anchor(seg " + Long.toHexString(seg) + " @ " + (int) sc.x + "," + (int) sc.y
            + ((gid == 0) ? ", no grid" : (", grid " + Long.toHexString(gid)
                + " +" + (int) off.x + "," + (int) off.y)) + ")";
    }
}
