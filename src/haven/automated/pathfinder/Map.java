package haven.automated.pathfinder;


import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.Pair;
import haven.ResDrawable;
import haven.Resource;
import haven.automated.helpers.HitBoxes;

import java.awt.*;
import java.util.List;
import java.util.*;

/**
 * Core pathfinding data structures and constants for the Havend map.
 * This class defines the grid representation, cell types, and geographic parameters
 * for A* route calculation. Field naming follows the original Havend conventions.
 */
public class Map implements World {
    public final static byte CELL_FREE = 0;
    public final static byte CELL_BLK = 1 << 1;
    public final static byte CELL_WP = 1 << 2;
    public final static byte CELL_SRC = 1 << 3;
    public final static byte CELL_DST = 1 << 4;
    public final static byte CELL_TO = 1 << 6;

    /** Size of one tile in world units (pixels). Matches {@code MCache.tilesz}. */
    /* int, and it must stay int: the arithmetic below relies on it. {@code TILE / 2} on lines 209-210
     * is integer division and yields 5, which is deliberately NOT {@link #HALF_TILE}'s 5.5. Taking
     * the seam's double and casting keeps the one physical fact in one place without changing any of
     * that. */
    private final static int TILE = (int) World.TILE;
    /** Half a tile in world units, as a double for centre calculations. */
    private final static double HALF_TILE = TILE / 2.0;
    /** Radius in tiles around the player that keep-out zones never block. */
    private final static int KEEPOUT_PLAYER_RADIUS_TILES = 3;
    /** Half-height of a regular gate collision box in grid cells. */
    private final static int GATE_HALF_HEIGHT = TILE;

    private final static int origintile = 44;
    public final static int origin = origintile * TILE;
    public final static int sz = origin * 2;
    /* The character disc's radius in WORLD units, which is the unit everything it is added to here
     * carries (gcx/gcy come from origin - x * TILE). Same physical fact as Observed.HALFWIDTH; NOT
     * the same number as Router.HALFWIDTH, which is this divided by TILE because that file counts in
     * tiles. Left non-final because it always was; nothing in the tree assigns it. */
    public static int plbbox = (int) World.HALFWIDTH;
    private final static int way = plbbox + 2;
    private final static int clr = way + 1;
    private final static int concaveclr = 2;
    private final static int tomaxside = 33;
    private final static int mapborder = 4;

    private final static int tbbax = -2;
    private final static int tbbay = -2;
    private final static int tbbbx = 2;
    private final static int tbbby = 2;

    private final byte[][] map = new byte[sz][sz];
    private final TraversableObstacle[][] pomap = new TraversableObstacle[sz][sz];
    private final ArrayList<TraversableObstacle> tocandidates = new ArrayList<TraversableObstacle>(300);
    public Coord plc;
    private final Coord endc;
    private final MCache mcache;
    private Vertex vxstart;
    private Vertex vxend;

    private final Dbg dbg;
    private final static boolean DEBUG = false;
    public final static boolean DEBUG_TIMINGS = false;
    /** Treat cliffs/ledges as impassable geography. See cliffAt(). */
    public static boolean BLOCK_CLIFFS = true;

    /**
     * Water nobody crosses on foot. Blocked always, for bots and for the player's own clicks.
     *
     * The ocean pair belongs here and used to be in the opt-in set instead, which meant a route
     * across open sea was refused only while some bot happened to have water avoidance on. There
     * is no setting under which swimming the deep is the right answer.
     */
    private final static Set<String> DEEP = new HashSet<>(Arrays.asList(
            "gfx/tiles/deep", "gfx/tiles/odeep", "gfx/tiles/odeeper"));

    /**
     * Water a character can wade or swim across. Blocked only when {@link #BLOCK_WATER} is on.
     *
     * Kept apart from {@link #DEEP} because the two are genuinely different questions and lumping
     * them cost real routes: a ford or a shoreline is passable and is often exactly where the
     * destination IS - a water place drawn on a river bank is on these tiles - so refusing them
     * outright reported "no route" for somewhere the character could simply walk to.
     *
     * Deliberately NOT "is a water tile" either: bog, fen and swamp water are walkable ground with
     * a wet texture, and blocking those would wall off whole regions.
     */
    private final static Set<String> SHALLOW = new HashSet<>(Arrays.asList(
            "gfx/tiles/water", "gfx/tiles/owater"));

    /** Impassable water - the kind no setting makes crossable. */
    public static boolean isDeep(String tilesetName) {
        return tilesetName != null && DEEP.contains(tilesetName);
    }

    /** Shallow enough to wade or swim; crossing it is a choice, not a mistake. */
    public static boolean isShallow(String tilesetName) {
        return tilesetName != null && SHALLOW.contains(tilesetName);
    }

    /** Either kind. What a caller wants when the question really is "is this water". */
    public static boolean isWater(String tilesetName) {
        return isDeep(tilesetName) || isShallow(tilesetName);
    }

    /**
     * Ground that is not water and still cannot be walked: cave mouths, the nil tile, and rock faces.
     *
     * Split out of {@link #initGeography}, where these three names were spelled inline and were
     * therefore known ONLY to the click path. The planner reads the map file through
     * {@code nbots.world.Terrain}, which classified water and nothing else, so a long-range route was
     * planned straight across a rock wall or a cave mouth and the click was refused on arrival - the
     * certified-then-refused loop. Both layers now ask this one method, so they agree by construction.
     *
     * Unconditional, like {@link #DEEP} and unlike {@link #SHALLOW}: there is no setting under which
     * walking into a rock face is the answer.
     */
    public static boolean isImpassableGround(String tilesetName) {
        return (tilesetName != null)
            && (tilesetName.equals("gfx/tiles/cave")
                || tilesetName.equals("gfx/tiles/nil")
                || tilesetName.startsWith("gfx/tiles/rocks/"));
    }

    /**
     * Route around water instead of swimming through it. Off by default - see WATER.
     *
     * Global rather than per-search because a Pathfinder is constructed inside MapView, which this
     * fork deliberately doesn't touch. The cost of that shortcut is that a manual click issued while
     * a bot is running also avoids water, which is harmless.
     *
     * Read this; do not assign to it. Set it through {@link #avoidWater} and {@link #wade}.
     */
    public static volatile boolean BLOCK_WATER = false;

    /** Everything that currently wants water routed around, and everything that wants to wade. */
    private static final Set<Object> avoiders = new HashSet<>(), waders = new HashSet<>();

    /**
     * Registers or withdraws an interest in avoiding water.
     *
     * OWNERSHIP, not save-and-restore, and the difference is a bug this actually had. Each bot used
     * to record the flag's previous value on starting and write it back on stopping, which is
     * correct for one bot and wrong the moment two overlap: a crew bot begins a shift while the
     * value is false, the LP assistant then starts its run and sets it true, and the crew bot's next
     * shift end - a few seconds later, since shifts are short - restores the false it captured
     * before the LP bot existed. The LP bot goes on running with water avoidance silently off and
     * swims across the river after a shoreline apple. Nothing in its own log says anything changed,
     * because from its point of view nothing did.
     *
     * With a set of owners there is no previous value to restore and no ordering to get wrong: water
     * is avoided while anybody wants it avoided, and each owner speaks only for itself.
     *
     * @param owner any object that identifies the caller for as long as it cares - a bot instance.
     */
    public static void avoidWater(Object owner, boolean want) {
        set(avoiders, owner, want);
    }

    /**
     * Suspends water avoidance while somebody has to stand IN water - filling from a lake.
     *
     * Separate from clearing the flag so it composes: whoever is wading says so and says when they
     * have finished, and the avoiders they interrupted are still on record and get their answer back
     * without having to have kept a copy of it.
     */
    public static void wade(Object owner, boolean want) {
        set(waders, owner, want);
    }

    private static void set(Set<Object> which, Object owner, boolean want) {
        synchronized (avoiders) {
            if (want)
                which.add(owner);
            else
                which.remove(owner);
            BLOCK_WATER = !avoiders.isEmpty() && waders.isEmpty();
        }
    }

    /** A circle in world coordinates that a path must stay out of. */
    public static final class Keepout {
        public final Coord2d c;
        public final double r;

        public Keepout(Coord2d c, double r) {
            this.c = c;
            this.r = r;
        }
    }

    private final static Keepout[] NO_KEEPOUTS = new Keepout[0];
    private static volatile Keepout[] keepouts = NO_KEEPOUTS;

    /**
     * Marks circles the next searches must route around - the LP bot uses this for the aggro rings
     * of dangerous beasts, so a bear standing between the character and a berry bush produces a
     * detour rather than a refusal.
     *
     * Same global-static caveat as BLOCK_WATER. Pass null (or an empty array) to clear. Never
     * include a circle the character is currently STANDING IN: every route out of it would be
     * blocked and the search would simply fail. Backing out of one is the caller's job.
     */
    public static void keepout(Keepout[] zones) {
        keepouts = (zones == null) ? NO_KEEPOUTS : zones;
    }

    /** What is currently standing, so a diagnostic can say whether one of these was the objection. */
    public static Keepout[] keepouts() {
        return keepouts;
    }

    /**
     * The {@link World} seam's live-window form of the questions. This class owns the window the
     * character actually moves on, so it answers from the same predicates {@link #initGeography}
     * rasters: the tile's own class (deep, rock/cave/void), water when {@link #BLOCK_WATER} is on,
     * cliffs when {@link #BLOCK_CLIFFS}, and the current keep-out circles. Gob collision boxes are
     * deliberately NOT part of the answer here: they are carved per-search by
     * {@link #analyzeGobHitBoxes}, which is what makes this a live window rather than a snapshot.
     */
    private String blockReason(Coord2d wc) {
        Coord tc = wc.floor(MCache.tilesz);
        int t = mcache.gettile(tc);
        Resource res = mcache.tilesetr(t);
        if (res == null)
            return null;
        String name = res.name;
        if (isDeep(name))
            return "deep water";
        if (isImpassableGround(name))
            return "rock, cave or void";
        if (isShallow(name)) {
            if (BLOCK_WATER)
                return "shallow water";
        }
        if (BLOCK_CLIFFS && cliffAt(tc))
            return "cliff";
        if (keepouts.length > 0 && inKeepout(keepouts, tc))
            return "keep-out circle";
        return null;
    }

    /** The seam's question: may a disc centred at {@code wc} STOP here. */
    @Override
    public boolean standable(Coord2d wc) {
        return blockReason(wc) == null;
    }

    /** The seam's question: may a route CROSS here. */
    @Override
    public boolean passable(Coord2d wc) {
        return blockReason(wc) == null;
    }

    /** The seam's question: routing cost through here. An impassable point is never costed. */
    @Override
    public int cost(Coord2d wc) {
        return blockReason(wc) == null ? 1 : Integer.MAX_VALUE / 2;
    }

    /** The seam's question: why this point was refused, or null when nothing objects. */
    @Override
    public String why(Coord2d wc) {
        return blockReason(wc);
    }

    public Map(Coord plc, Coord endc, MCache mcache) {
        this.plc = plc;
        this.endc = endc;
        this.mcache = mcache;
        dbg = new Dbg(DEBUG);
        dbg.init();

    }

    private void initGeography() {
        Coord pltc = new Coord(plc.x / TILE, plc.y / TILE);

        int dx = (int) (plc.x / (double) TILE * TILE - pltc.x * TILE) - TILE / 2;
        int dy = (int) (plc.y / (double) TILE * TILE - pltc.y * TILE) - TILE / 2;

        // Sampled once: the setter can be called from another thread mid-scan, and half a scan
        // done against one set of zones and half against another would produce a map that is
        // neither.
        final boolean blockWater = BLOCK_WATER;
        final Keepout[] zones = keepouts;

        for (int x = -origintile; x < origintile; x++) {
            for (int y = -origintile; y < origintile; y++) {
                Coord tc = pltc.sub(x, y);
                int t = mcache.gettile(tc);
                Resource res = mcache.tilesetr(t);
                if (res == null)
                    continue;

                String name = res.name;
                boolean blocked = isDeep(name) || isImpassableGround(name);
                if (!blocked && blockWater)
                    blocked = isShallow(name);
                if (!blocked && BLOCK_CLIFFS)
                    blocked = cliffAt(tc);
                if (!blocked && zones.length > 0)
                    blocked = inKeepout(zones, tc);
                if (!blocked)
                    continue;

                int gcx = origin - (x * TILE) - dx;
                int gcy = origin - (y * TILE) - dy;

                // exclude destination tile
                if (endc.x < gcx + tbbax + plbbox && endc.x > gcx + tbbax - plbbox &&
                        endc.y < gcy + tbbby + plbbox && endc.y > gcy + tbbay - plbbox) {
                    continue;
                }

                // bounding box
                Coord ca = new Coord(gcx + tbbax - plbbox, gcy + tbbay - plbbox);
                Coord cb = new Coord(gcx + tbbbx + plbbox, gcy + tbbay - plbbox);
                Coord cc = new Coord(gcx + tbbbx + plbbox, gcy + tbbby + plbbox);
                Coord cd = new Coord(gcx + tbbax - plbbox, gcy + tbbby + plbbox);

                // calculate waypoints located on the angular bisector of the corner
                int wax = ca.x - 1;
                int way = ca.y - 1;
                int wbx = cb.x + 1;
                int wby = cb.y - 1;
                int wcx = cc.x + 1;
                int wcy = cc.y + 1;
                int wdx = cd.x - 1;
                int wdy = cd.y + 1;

                // exclude tiles near map edges so we won't need to do bounds checks all over the place
                if (wax - mapborder < 0 || way - mapborder < 0 || wax + mapborder >= sz || way + mapborder >= sz ||
                        wbx - mapborder < 0 || wby - mapborder < 0 || wbx + mapborder >= sz || wby + mapborder >= sz ||
                        wcx - mapborder < 0 || wcy - mapborder < 0 || wcx + mapborder >= sz || wcy + mapborder >= sz ||
                        wdx - mapborder < 0 || wdy - mapborder < 0 || wdx + mapborder >= sz || wdy + mapborder >= sz)
                    continue;

                // plot bounding box
                Utils.plotTile(map, ca, cb, cd);

                if (map[wax][way] == CELL_FREE)
                    map[wax][way] = CELL_WP;
                if (map[wbx][wby] == CELL_FREE)
                    map[wbx][wby] = CELL_WP;
                if (map[wcx][wcy] == CELL_FREE)
                    map[wcx][wcy] = CELL_WP;
                if (map[wdx][wdy] == CELL_FREE)
                    map[wdx][wdy] = CELL_WP;

                dbg.rect(ca.x, ca.y, cb.x, cb.y, cc.x, cc.y, cd.x, cd.y, Color.CYAN);
            }
        }

        // Block the map border so a route never steps off the explored map.
        for (int i = 0; i < sz; i++) {
            map[i][mapborder] = CELL_BLK;
            map[i][sz - mapborder] = CELL_BLK;
            map[mapborder][i] = CELL_BLK;
            map[sz - mapborder][i] = CELL_BLK;

            dbg.dot(i, mapborder, Color.CYAN);
            dbg.dot(i, sz - mapborder, Color.CYAN);
            dbg.dot(mapborder, i, Color.CYAN);
            dbg.dot(sz - mapborder, i, Color.CYAN);
        }
    }

    /**
     * A cliff/ledge on this tile, i.e. a height break the character can't walk over.
     *
     * Without this, geography was decided purely by TILE TYPE (deep water, cave, nil, rocks), and
     * elevation was invisible to the search: a ridge between two levels is the same grass tile on
     * both sides, so A* would happily plan a straight line through a cliff face and the character
     * would walk into it and stop. Same predicate the client already uses to draw cliffs (see
     * MapSource's minimap render and Ridges.cliffHighlightMat), so what the pathfinder refuses to
     * cross is exactly what the player sees as a cliff.
     */
    private boolean cliffAt(Coord tc) {
        try {
            return haven.resutil.Ridges.brokenp(mcache, tc);
        } catch (Loading l) {
            // Grid not loaded yet - treat as open rather than blocking a tile we can't judge. The
            // path is re-planned on the next click anyway, by which point it'll usually be in.
            return false;
        }
    }

    /**
     * Whether this tile falls inside one of the caller's keep-out circles.
     *
     * The tiles immediately around the character are never blocked, however deep inside a circle
     * they are. Blocking them would make the search's own starting cell impassable, and Pathfinder
     * reacts to that by nudging the character a few pixels and retrying - so a beast that strayed
     * next to us would produce a twitch-in-place loop instead of a detour. Callers are expected not
     * to hand us a circle we are standing in at all (see keepout()); this is the backstop for the
     * case where one wanders onto us between the call and the search.
     */
    private boolean inKeepout(Keepout[] zones, Coord tc) {
        // Tile coords are TILE world units across, so this is the tile's centre in world space.
        double wx = tc.x * TILE + HALF_TILE, wy = tc.y * TILE + HALF_TILE;
        double px = plc.x, py = plc.y;
        double ddx = wx - px, ddy = wy - py;
        if ((ddx * ddx) + (ddy * ddy) < (KEEPOUT_PLAYER_RADIUS_TILES * TILE) * (KEEPOUT_PLAYER_RADIUS_TILES * TILE))
            return false;
        for (Keepout k : zones) {
            double kx = wx - k.c.x, ky = wy - k.c.y;
            if ((kx * kx) + (ky * ky) < k.r * k.r)
                return true;
        }
        return false;
    }

    public void analyzeGobHitBoxes(Gob gob) {
        if (gob.getres() == null) {
            return;
        }

        if (HitBoxes.collisionBoxMap.get(gob.getres().name) != null) {
            HitBoxes.CollisionBoxSecondary[] collisionBoxSecondaries = HitBoxes.collisionBoxMap.get(gob.getres().name);
            if (gob.getres().name.contains("/pow")) {
                Resource res = gob.getres();
                ResDrawable rd = gob.getattr(ResDrawable.class);
                if (rd != null) {
                    if (res.name.endsWith("/pow") && (rd.sdt.checkrbuf(0) != 33 && rd.sdt.checkrbuf(0) != 17)) {
                        addGobToList(new Coord(-4, -4), new Coord(4, 4), gob);
                    }
                }
            } else if (gob.getres().name.contains("gate")){
                Resource res = gob.getres();
                ResDrawable rd = gob.getattr(ResDrawable.class);
                if (rd != null){
                    if (res.name.contains("gate") && (rd.sdt.checkrbuf(0) != 1)){
                        if(gob.getres().name.contains("big")){
                            addGobToList(new Coord(-5, -16), new Coord(5, 16), gob);
                        } else {
                            addGobToList(new Coord(-5, -GATE_HALF_HEIGHT), new Coord(5, GATE_HALF_HEIGHT), gob);
                        }
                    }
                }
            } else {
                for (HitBoxes.CollisionBoxSecondary collisionBox : collisionBoxSecondaries) {
                    // A resource may expose several collision parts. An unwalkable or malformed
                    // part must not hide the valid parts that follow it.
                    if (!collisionBox.hitAble || collisionBox.coords == null || collisionBox.coords.length < 3)
                        continue;

                    {
                        double minX = Double.MAX_VALUE;
                        double minY = Double.MAX_VALUE;
                        double maxX = Double.MIN_VALUE;
                        double maxY = Double.MIN_VALUE;

                        for (Coord2d coord : collisionBox.coords) {
                            minX = Math.min(minX, coord.x);
                            minY = Math.min(minY, coord.y);
                            maxX = Math.max(maxX, coord.x);
                            maxY = Math.max(maxY, coord.y);
                        }
                        addGobToList(new Coord2d(minX, minY).floor(), new Coord2d(maxX, maxY).floor(), gob);
                    }
                }
            }
        }
    }

    public void addGobToList(Coord topLeftPoint, Coord bottomRightPoint, Gob gob) {
        int gcx = origin - (plc.x - gob.rc.floor().x);
        int gcy = origin - (plc.y - gob.rc.floor().y);


        int rotadj = 0;
        if (gob.a != 0 && gob.a != Math.PI && gob.a != Math.PI / 2.0 && gob.a != (3 * Math.PI) / 2) {
            rotadj = 1;
        }
        Coord ca, cb, cc, cd, wa, wb, wc, wd, clra, clrb, clrc, clrd;

        if (Math.abs(topLeftPoint.x) + Math.abs(bottomRightPoint.x) == Math.abs(topLeftPoint.y) + Math.abs(bottomRightPoint.y) && rotadj == 0) {
            // bounding box
            ca = new Coord(gcx + topLeftPoint.x - plbbox, gcy + topLeftPoint.y - plbbox);
            cb = new Coord(gcx + bottomRightPoint.x + plbbox, gcy + topLeftPoint.y - plbbox);
            cc = new Coord(gcx + bottomRightPoint.x + plbbox, gcy + bottomRightPoint.y + plbbox);
            cd = new Coord(gcx + topLeftPoint.x - plbbox, gcy + bottomRightPoint.y + plbbox);

            // calculate waypoints located on the angular bisector of the corner
            wa = new Coord(gcx + topLeftPoint.x - way, gcy + topLeftPoint.y - way);
            wb = new Coord(gcx + bottomRightPoint.x + way, gcy + topLeftPoint.y - way);
            wc = new Coord(gcx + bottomRightPoint.x + way, gcy + bottomRightPoint.y + way);
            wd = new Coord(gcx + topLeftPoint.x - way, gcy + bottomRightPoint.y + way);

            // calculate TO clearance vertices
            clra = new Coord(gcx + topLeftPoint.x - clr, gcy + topLeftPoint.y - clr);
            clrb = new Coord(gcx + bottomRightPoint.x + clr, gcy + topLeftPoint.y - clr);
            clrc = new Coord(gcx + bottomRightPoint.x + clr, gcy + bottomRightPoint.y + clr);
            clrd = new Coord(gcx + topLeftPoint.x - clr, gcy + bottomRightPoint.y + clr);
        } else {
            // rotate the bounding box.
            // Rotates around the gob origin, not the pixel centre - close enough for routing.
            double cos = Math.cos(gob.a);
            double sin = Math.sin(gob.a);
            ca = Utils.rotate(gcx + topLeftPoint.x - plbbox, gcy + topLeftPoint.y - plbbox, gcx, gcy, cos, sin);
            cb = Utils.rotate(gcx + bottomRightPoint.x + plbbox, gcy + topLeftPoint.y - plbbox, gcx, gcy, cos, sin);
            cc = Utils.rotate(gcx + bottomRightPoint.x + plbbox, gcy + bottomRightPoint.y + plbbox, gcx, gcy, cos, sin);
            cd = Utils.rotate(gcx + topLeftPoint.x - plbbox, gcy + bottomRightPoint.y + plbbox, gcx, gcy, cos, sin);

            // calculate waypoints located on the angular bisector of the corner
            wa = Utils.rotate(gcx + topLeftPoint.x - way - rotadj, gcy + topLeftPoint.y - way - rotadj, gcx, gcy, cos, sin);
            wb = Utils.rotate(gcx + bottomRightPoint.x + way + rotadj, gcy + topLeftPoint.y - way - rotadj, gcx, gcy, cos, sin);
            wc = Utils.rotate(gcx + bottomRightPoint.x + way + rotadj, gcy + bottomRightPoint.y + way + rotadj, gcx, gcy, cos, sin);
            wd = Utils.rotate(gcx + topLeftPoint.x - way - rotadj, gcy + bottomRightPoint.y + way + rotadj, gcx, gcy, cos, sin);

            // calculate TO clearance vertices
            clra = Utils.rotate(gcx + topLeftPoint.x - clr - rotadj, gcy + topLeftPoint.y - clr - rotadj, gcx, gcy, cos, sin);
            clrb = Utils.rotate(gcx + bottomRightPoint.x + clr - rotadj, gcy + topLeftPoint.y - clr - rotadj, gcx, gcy, cos, sin);
            clrc = Utils.rotate(gcx + bottomRightPoint.x + clr + rotadj, gcy + bottomRightPoint.y + clr + rotadj, gcx, gcy, cos, sin);
            clrd = Utils.rotate(gcx + topLeftPoint.x - clr - rotadj, gcy + bottomRightPoint.y + clr + rotadj, gcx, gcy, cos, sin);
        }

        // exclude gobs near map edges so we won't need to do bounds checks all over the place
        if (wa.x - mapborder < 0 || wa.y - mapborder < 0 || wa.x + mapborder >= sz || wa.y + mapborder >= sz ||
                wb.x - mapborder < 0 || wb.y - mapborder < 0 || wb.x + mapborder >= sz || wb.y + mapborder >= sz ||
                wc.x - mapborder < 0 || wc.y - mapborder < 0 || wc.x + mapborder >= sz || wc.y + mapborder >= sz ||
                wd.x - mapborder < 0 || wd.y - mapborder < 0 || wd.x + mapborder >= sz || wd.y + mapborder >= sz)
            return;

        if (map[wa.x][wa.y] == CELL_FREE)
            map[wa.x][wa.y] = CELL_WP;
        if (map[wb.x][wb.y] == CELL_FREE)
            map[wb.x][wb.y] = CELL_WP;
        if (map[wc.x][wc.y] == CELL_FREE)
            map[wc.x][wc.y] = CELL_WP;
        if (map[wd.x][wd.y] == CELL_FREE)
            map[wd.x][wd.y] = CELL_WP;

        // plot bounding box
        HashMap<Integer, Utils.MinMax> raster = Utils.plotRect(map, ca, cb, cc, cd, CELL_BLK);

        // store traversable obstacles candidates
        if (bottomRightPoint.x <= tomaxside && bottomRightPoint.y <= tomaxside)
            tocandidates.add(new TraversableObstacle(wa, wb, wc, wd, clra, clrb, clrc, clrd, raster));

        dbg.rect(ca.x, ca.y, cb.x, cb.y, cc.x, cc.y, cd.x, cd.y, Color.CYAN);
    }

    public void excludeGob(Coord topLeftPoint, Coord bottomRightPoint, Gob gob) {
        int gcx = origin - (plc.x - gob.rc.floor().x);
        int gcy = origin - (plc.y - gob.rc.floor().y);

        // rotate the bounding box.
        // Rotates around the gob origin, not the pixel centre - close enough for routing.
        double cos = Math.cos(gob.a);
        double sin = Math.sin(gob.a);
        Coord ca = Utils.rotate(gcx + topLeftPoint.x - plbbox, gcy + topLeftPoint.y - plbbox, gcx, gcy, cos, sin);
        Coord cb = Utils.rotate(gcx + bottomRightPoint.x + plbbox, gcy + topLeftPoint.y - plbbox, gcx, gcy, cos, sin);
        Coord cc = Utils.rotate(gcx + bottomRightPoint.x + plbbox, gcy + bottomRightPoint.y + plbbox, gcx, gcy, cos, sin);
        Coord cd = Utils.rotate(gcx + topLeftPoint.x - plbbox, gcy + bottomRightPoint.y + plbbox, gcx, gcy, cos, sin);

        // exclude the gob if it's near map edges so we won't need to do bounds checks all later on
        if (ca.x - mapborder < 0 || ca.y - mapborder < 0 || ca.x + mapborder >= sz || ca.y + mapborder >= sz ||
                cb.x - mapborder < 0 || cb.y - mapborder < 0 || cb.x + mapborder >= sz || cb.y + mapborder >= sz ||
                cc.x - mapborder < 0 || cc.y - mapborder < 0 || cc.x + mapborder >= sz || cc.y + mapborder >= sz ||
                cd.x - mapborder < 0 || cd.y - mapborder < 0 || cd.x + mapborder >= sz || cd.y + mapborder >= sz)
            return;

        Utils.plotRect(map, ca, cb, cc, cd, CELL_FREE);
        dbg.rect(ca.x, ca.y, cb.x, cb.y, cc.x, cc.y, cd.x, cd.y, Color.PINK);
    }

    private void sanitizeWaypoints() {
        for (int i = 0; i < sz; i++) {
            for (int j = 0; j < sz; j++) {
                if (map[i][j] != CELL_WP)
                    continue;

                // remove concave and blocked vertices
                // Known rough edge: slightly misbehaves with rotated rectangles.
                if ((map[i + concaveclr][j] & (CELL_BLK | CELL_TO)) != 0 ||
                        (map[i - concaveclr][j] & (CELL_BLK | CELL_TO)) != 0 ||
                        (map[i][j + concaveclr] & (CELL_BLK | CELL_TO)) != 0 ||
                        (map[i][j - concaveclr] & (CELL_BLK | CELL_TO)) != 0) {
                    map[i][j] = CELL_FREE;
                    continue;
                }
                dbg.dot(i, j, Color.RED);
            }
        }
    }

    // Identifies all obstacles which can be navigated around.
    // Known limitations: only axis-aligned boxes are handled (not convex polygons), and
    // the visibility scan is approximate - a flood-fill approach would be more robust.
    private void identTraversableObstacles() {
        for (TraversableObstacle sm : tocandidates) {
            if (!Utils.isVisible(map, dbg, sm.clra.x, sm.clra.y, sm.clrb.x, sm.clrb.y, (byte) (CELL_BLK | CELL_TO)) ||
                    !Utils.isVisible(map, dbg, sm.clrb.x, sm.clrb.y, sm.clrc.x, sm.clrc.y, (byte) (CELL_BLK | CELL_TO)) ||
                    !Utils.isVisible(map, dbg, sm.clrc.x, sm.clrc.y, sm.clrd.x, sm.clrd.y, (byte) (CELL_BLK | CELL_TO)) ||
                    !Utils.isVisible(map, dbg, sm.clrd.x, sm.clrd.y, sm.clra.x, sm.clra.y, (byte) (CELL_BLK | CELL_TO)))
                continue;

            map[sm.wa.x][sm.wa.y] = CELL_FREE;
            map[sm.wb.x][sm.wb.y] = CELL_FREE;
            map[sm.wc.x][sm.wc.y] = CELL_FREE;
            map[sm.wd.x][sm.wd.y] = CELL_FREE;

            for (int y : sm.raster.keySet()) {
                Utils.MinMax mm = sm.raster.get(y);
                for (int x = mm.min; x <= mm.max; x++) {
                    map[x][y] = Map.CELL_TO;
                    pomap[x][y] = sm;
                }
            }
        }
    }

    private List<Vertex> getVertices() {
        List<Vertex> vertices = new ArrayList<Vertex>(300);

        vxstart = new Vertex(origin, origin);
        vertices.add(vxstart);
        map[origin][origin] = CELL_SRC;
        dbg.dot(origin, origin, Color.GREEN);

        vxend = new Vertex(endc.x, endc.y);
        vertices.add(vxend);
        map[endc.x][endc.y] = CELL_DST;
        dbg.dot(endc.x, endc.y, Color.BLUE);

        for (int i = 0; i < sz; i++) {
            for (int j = 0; j < sz; j++) {
                if (map[i][j] == CELL_WP)
                    vertices.add(new Vertex(i, j));
            }
        }

        return vertices;
    }

    private void buildVisGraph(List<Vertex> vertices, byte block) {
        int visedges = 0;
        int edges = 0;

        for (int i = 0; i < vertices.size(); i++) {
            for (int j = i + 1; j < vertices.size(); j++) {
                Vertex vert1 = vertices.get(i);
                Vertex vert2 = vertices.get(j);

                edges += 2;

                if (Utils.isVisible(map, dbg, vert1.x, vert1.y, vert2.x, vert2.y, block)) {
                    int dx = vert1.x - vert2.x;
                    int dy = vert1.y - vert2.y;
                    double distance = Math.sqrt(dx * dx + dy * dy);

                    visedges += 2;
                    vert1.edges.add(new Edge(vert1, vert2, distance));
                    vert2.edges.add(new Edge(vert2, vert1, distance));
                }
            }
        }

        if (DEBUG_TIMINGS)
            System.out.println("Edges: " + visedges + " / " + edges + " (vxs: " + vertices.size() + ")");
    }


    private List<Vertex> recalcVertices(Iterable<Edge> path) {
        List<Vertex> vertices = new ArrayList<Vertex>();
        boolean pathclear = true;

        Iterator<Edge> it = path.iterator();
        while (it.hasNext()) {
            Edge e = it.next();
            dbg.line(e.src.x, e.src.y, e.dest.x, e.dest.y, Color.MAGENTA);

            Set<TraversableObstacle> obs = Utils.getObstructions(pomap, e.src.x, e.src.y, e.dest.x, e.dest.y);
            for (TraversableObstacle o : obs) {
                vertices.add(new Vertex(o.wa.x, o.wa.y));
                vertices.add(new Vertex(o.wb.x, o.wb.y));
                vertices.add(new Vertex(o.wc.x, o.wc.y));
                vertices.add(new Vertex(o.wd.x, o.wd.y));

                vertices.add(new Vertex((o.wa.x + o.wb.x) / 2, (o.wa.y + o.wb.y) / 2));
                vertices.add(new Vertex((o.wb.x + o.wc.x) / 2, (o.wb.y + o.wc.y) / 2));
                vertices.add(new Vertex((o.wc.x + o.wd.x) / 2, (o.wc.y + o.wd.y) / 2));
                vertices.add(new Vertex((o.wd.x + o.wa.x) / 2, (o.wd.y + o.wa.y) / 2));

                dbg.dot(o.wa.x, o.wa.y, Color.PINK);
                dbg.dot(o.wb.x, o.wb.y, Color.PINK);
                dbg.dot(o.wc.x, o.wc.y, Color.PINK);
                dbg.dot(o.wd.x, o.wd.y, Color.PINK);

                dbg.dot((o.wa.x + o.wb.x) / 2, (o.wa.y + o.wb.y) / 2, Color.PINK);
                dbg.dot((o.wb.x + o.wc.x) / 2, (o.wb.y + o.wc.y) / 2, Color.PINK);
                dbg.dot((o.wc.x + o.wd.x) / 2, (o.wc.y + o.wd.y) / 2, Color.PINK);
                dbg.dot((o.wd.x + o.wa.x) / 2, (o.wd.y + o.wa.y) / 2, Color.PINK);
                pathclear = false;
            }

            if (e.src.x == origin && e.src.y == origin)
                continue;

            vertices.add(new Vertex(e.src.x, e.src.y));
        }

        return pathclear ? null : vertices;
    }

    private Iterable<Edge> findPath() {
        Iterable<Edge> path = new AStar().route(vxstart, vxend);

        List<Vertex> vertices = recalcVertices(path);
        if (vertices == null)
            return path;

        vxstart = new Vertex(origin, origin);
        vertices.add(vxstart);
        vxend = new Vertex(endc.x, endc.y);
        vertices.add(vxend);

        buildVisGraph(vertices, (byte) (CELL_BLK | CELL_TO));

        return new AStar().route(vxstart, vxend);
    }

    public Iterable<Edge> main() {
        long start = System.nanoTime();
        initGeography();
        if (DEBUG_TIMINGS)
            System.out.println("            Geography: " + (double) (System.nanoTime() - start) / 1000000.0 + " ms.");

        start = System.nanoTime();
        identTraversableObstacles();
        if (DEBUG_TIMINGS)
            System.out.println("Traversable Obstacles: " + (double) (System.nanoTime() - start) / 1000000.0 + " ms.");

        start = System.nanoTime();
        sanitizeWaypoints();
        if (DEBUG_TIMINGS)
            System.out.println("Vertices Sanitization: " + (double) (System.nanoTime() - start) / 1000000.0 + " ms.");

        // clear area around starting position in case char is on the bounding box boundary
        if (map[origin][origin - 1] == CELL_BLK)
            map[origin][origin - 1] = CELL_FREE;
        if (map[origin - 1][origin - 1] == CELL_BLK)
            map[origin - 1][origin - 1] = CELL_FREE;
        if (map[origin + 1][origin - 1] == CELL_BLK)
            map[origin + 1][origin - 1] = CELL_FREE;
        if (map[origin - 1][origin] == CELL_BLK)
            map[origin - 1][origin] = CELL_FREE;
        if (map[origin + 1][origin] == CELL_BLK)
            map[origin + 1][origin] = CELL_FREE;
        if (map[origin - 1][origin + 1] == CELL_BLK)
            map[origin - 1][origin + 1] = CELL_FREE;
        if (map[origin][origin + 1] == CELL_BLK)
            map[origin][origin + 1] = CELL_FREE;
        if (map[origin + 1][origin + 1] == CELL_BLK)
            map[origin + 1][origin + 1] = CELL_FREE;


        // test if direct path is clear
        if (Utils.isVisible(map, dbg, origin, origin, endc.x, endc.y, (byte) (CELL_BLK | CELL_TO))) {
            List<Edge> clearpath = new ArrayList<>(1);
            clearpath.add(new Edge(new Vertex(origin, origin), new Vertex(endc.x, endc.y), 0));
            if (DEBUG_TIMINGS)
                System.out.println("!!!Clear path found!!!");
            return clearpath;
        }

        // test if direct path blocked only by traversable obstacles
        if (Utils.isVisible(map, dbg, origin, origin, endc.x, endc.y, CELL_BLK)) {
            if (DEBUG_TIMINGS)
                System.out.println("   !!!Only TO block!!!");

            Set<TraversableObstacle> obs = Utils.getObstructions(pomap, origin, origin, endc.x, endc.y);
            List<Vertex> tovertexes = new ArrayList<>();

            vxstart = new Vertex(origin, origin);
            tovertexes.add(vxstart);
            vxend = new Vertex(endc.x, endc.y);
            tovertexes.add(vxend);

            for (TraversableObstacle o : obs) {
                tovertexes.add(new Vertex(o.wa.x, o.wa.y));
                tovertexes.add(new Vertex(o.wb.x, o.wb.y));
                tovertexes.add(new Vertex(o.wc.x, o.wc.y));
                tovertexes.add(new Vertex(o.wd.x, o.wd.y));

                tovertexes.add(new Vertex((o.wa.x + o.wb.x) / 2, (o.wa.y + o.wb.y) / 2));
                tovertexes.add(new Vertex((o.wb.x + o.wc.x) / 2, (o.wb.y + o.wc.y) / 2));
                tovertexes.add(new Vertex((o.wc.x + o.wd.x) / 2, (o.wc.y + o.wd.y) / 2));
                tovertexes.add(new Vertex((o.wd.x + o.wa.x) / 2, (o.wd.y + o.wa.y) / 2));

                dbg.dot(o.wa.x, o.wa.y, Color.PINK);
                dbg.dot(o.wb.x, o.wb.y, Color.PINK);
                dbg.dot(o.wc.x, o.wc.y, Color.PINK);
                dbg.dot(o.wd.x, o.wd.y, Color.PINK);

                dbg.dot((o.wa.x + o.wb.x) / 2, (o.wa.y + o.wb.y) / 2, Color.PINK);
                dbg.dot((o.wb.x + o.wc.x) / 2, (o.wb.y + o.wc.y) / 2, Color.PINK);
                dbg.dot((o.wc.x + o.wd.x) / 2, (o.wc.y + o.wd.y) / 2, Color.PINK);
                dbg.dot((o.wd.x + o.wa.x) / 2, (o.wd.y + o.wa.y) / 2, Color.PINK);
            }

            start = System.nanoTime();
            buildVisGraph(tovertexes, CELL_BLK);
            if (DEBUG_TIMINGS)
                System.out.println("     Visibility Graph: " + (double) (System.nanoTime() - start) / 1000000.0 + " ms.");

            start = System.nanoTime();
            Iterable<Edge> path = findPath();
            if (DEBUG_TIMINGS)
                System.out.println("              Routing: " + (double) (System.nanoTime() - start) / 1000000.0 + " ms.");

            Iterator<Edge> it = path.iterator();
            while (it.hasNext()) {
                Edge e = it.next();
                dbg.line(e.src.x, e.src.y, e.dest.x, e.dest.y, Color.ORANGE);
                dbg.dot(e.src.x, e.src.y, Color.BLUE);
                dbg.dot(e.dest.x, e.dest.y, Color.BLUE);
            }

            return path;
        }

        //---------------------------------------------------------------------------------
        start = System.nanoTime();
        List<Vertex> vertices = getVertices();
        if (DEBUG_TIMINGS)
            System.out.println("   Vertices Retrieval: " + (double) (System.nanoTime() - start) / 1000000.0 + " ms.");

        start = System.nanoTime();
        buildVisGraph(vertices, CELL_BLK);
        if (DEBUG_TIMINGS)
            System.out.println("     Visibility Graph: " + (double) (System.nanoTime() - start) / 1000000.0 + " ms.");

        start = System.nanoTime();
        Iterable<Edge> path = findPath();
        if (DEBUG_TIMINGS)
            System.out.println("              Routing: " + (double) (System.nanoTime() - start) / 1000000.0 + " ms.");

        Iterator<Edge> it = path.iterator();
        while (it.hasNext()) {
            Edge e = it.next();
            dbg.line(e.src.x, e.src.y, e.dest.x, e.dest.y, Color.ORANGE);
            dbg.dot(e.src.x, e.src.y, Color.BLUE);
            dbg.dot(e.dest.x, e.dest.y, Color.BLUE);
        }

        return path;
    }

    public boolean isOriginBlocked() {
        return map[origin][origin] == CELL_BLK || map[origin][origin] == CELL_TO;
    }

    // 3 pixels away from origin
    public Pair<Integer, Integer> getFreeLocation() {
        if (map[origin + 3][origin] == CELL_FREE)
            return new Pair<Integer, Integer>(origin + 3, origin);
        else if (map[origin - 3][origin] == CELL_FREE)
            return new Pair<Integer, Integer>(origin - 3, origin);
        else if (map[origin][origin + 3] == CELL_FREE)
            return new Pair<Integer, Integer>(origin, origin + 3);
        else if (map[origin][origin - 3] == CELL_FREE)
            return new Pair<Integer, Integer>(origin, origin - 3);

        return null;
    }


    public void dbgdump() {
        dbg.save();
        Dbg dbg = new Dbg(DEBUG);
        dbg.init();
        dbg.fill(map);
    }
}
