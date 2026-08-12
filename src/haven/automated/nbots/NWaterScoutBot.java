package haven.automated.nbots;

import haven.CheckBox;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Label;
import haven.Loading;
import haven.MCache;
import haven.RadioGroup;
import haven.Resource;
import haven.UI;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.world.Crowd;
import haven.automated.pathfinder.Pathfinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static haven.OCache.posres;

/**
 * Boat scouting that follows a coastline OR a river.
 *
 * The technique is the stock Ocean Scouting Bot's and it's a good one: probe outwards in a ring of
 * directions, find the one where nothing but navigable water lies ahead, and steer there - which
 * traces the edge of the water body, revealing map as you go. What it hard-codes is that "water"
 * means one specific ocean tile ({@code gfx/tiles/odeep}) and that the probes reach nine tiles out.
 * On a river both assumptions fail: fresh water isn't that tile, so every direction reads as land
 * and the bot never moves; and even if it did, a river is narrower than the probe, so no direction
 * would ever come back clear.
 *
 * So the two things that are actually mode-specific - which tiles count as navigable, and how far
 * to look - become a mode. Ocean keeps the original reach. River uses a much shorter probe and a
 * tighter clearance, which is what lets it thread a channel a few tiles wide.
 *
 * Two bugs in the stock version are fixed here rather than carried over. Its danger checks read
 *
 *   {@code name.endsWith("/walrus") || name.endsWith("/orca") && t.dist(...) < 11 * 14}
 *
 * and {@code &&} binds tighter than {@code ||}, so the distance test only ever applied to orcas:
 * a single walrus anywhere in view marked EVERY probed tile dangerous, no direction ever came back
 * clear, and the bot fell through to its random-wander fallback for the rest of the session. Both
 * checks are written with the grouping they plainly meant. The stock bot is untouched.
 *
 * Other characters are treated as obstacles too, so two scouts working the same shoreline steer
 * around each other instead of converging.
 */
public class NWaterScoutBot extends NBot {
    private static final String LOG = "nbot-scout.log";

    // Distances in this bot are expressed in pixel units where one tile = 11px (MCache.tilesz).
    private static final int TILE = (int) MCache.tilesz.x;
    private static final int CLICK_STEP = 44;                 // px to nudge the boat per click
    private static final double FLEE_STEP = TILE * 4.0;       // px to step away from a hazard
    private static final int RANDOM_TILE_RADIUS = TILE * 30;  // px range for a random tile
    private static final int SCAN_RADIUS = TILE * 25;         // px range for the per-cycle gob scan
    private static final int SPOKES = 20;                     // directions swept per heading search
    private static final int DEAD_END_LIMIT = 20;             // dead ends in a row before backing out
    /** Which body of water the bot is following, and how tightly. */
    private enum Mode {
        /**
         * Open sea. Only the DEEP ocean tile counts as navigable, which is what makes the bot hug
         * the shelf edge rather than wander the shallows - the same choice the stock bot makes.
         */
        OCEAN(new String[] {"gfx/tiles/odeep"}, 5.0, 20, 2),
        /**
         * Rivers and lakes. Both fresh-water depths are navigable by boat. Probes reach about two
         * tiles instead of nine and clear a one-tile margin instead of two, because a river channel
         * is often only three or four tiles wide and the ocean settings would find no clear
         * heading anywhere in it.
         */
        RIVER(new String[] {"gfx/tiles/water", "gfx/tiles/deep"}, 3.0, 8, 1);

        final Set<String> tiles;
        final double step;
        final int steps;
        final int clearance;

        Mode(String[] tiles, double step, int steps, int clearance) {
            this.tiles = new HashSet<>(Arrays.asList(tiles));
            this.step = step;
            this.steps = steps;
            this.clearance = clearance;
        }

        double reach() {
            return step * steps;
        }
    }

    /** Creatures that will wreck a boat. Kept per-mode: an orca is not a river problem. */
    private static final String[] SEA_HAZARDS = {"/walrus", "/orca", "/spermwhale", "/narwhal"};
    private static final String[] RIVER_HAZARDS = {"/beaver", "/beaverking", "/oldbeaver", "/grizzlybeaver"};

    /** How far a hazard's presence spoils a heading, and how close is close enough to flee. */
    private static final double HAZARD_AVOID = TILE * 14;
    private static final double HAZARD_FLEE = TILE * 11;
    private final MCache mcache;
    private final Random random = new Random();

    private volatile Mode mode = Mode.OCEAN;
    private volatile int turn = 1;

    private double ang = 0;
    private int deadEnds;

    /*
     * The world as of the start of this cycle, split three ways and gathered ONCE.
     *
     * A single heading search probes 20 directions by up to 20 steps, each checked with a margin
     * of surrounding tiles - order ten thousand points. Anything consulted per point therefore has
     * to be a plain list walk over a small set; rebuilding a gob list (under the object-cache lock)
     * inside that loop would be several million locked scans per cycle. Splitting by purpose also
     * keeps each walk short: only things with a hitbox can block a boat, and only a handful of
     * species are worth fleeing.
     */
    private List<Gob> solids = new ArrayList<>();
    private List<Gob> hazards = new ArrayList<>();
    private List<Gob> others = new ArrayList<>();

    public NWaterScoutBot(GameUI gui) {
        super(gui, "NWaterScoutBot", "Water Scout (crew)", LOG, UI.scale(280, 148));
        this.mcache = gui.map.glob.map;

        add(new Label("Follow the edge of:"), UI.scale(10, 22));
        RadioGroup modes = new RadioGroup(this) {
            @Override
            public void changed(int btn, String label) {
                mode = (btn == 0) ? Mode.OCEAN : Mode.RIVER;
                ang = 0;
                deadEnds = 0;
            }
        };
        modes.add("Ocean (deep water)", UI.scale(new Coord(16, 40)));
        modes.add("River or lake (fresh water)", UI.scale(new Coord(16, 58)));
        modes.check(0);

        add(new Label("Keep the deeper water on your:"), UI.scale(10, 78));
        add(new CheckBox("Left (clockwise)") {
            {
                a = true;
            }

            public void set(boolean val) {
                turn = val ? 1 : -1;
                a = val;
            }
        }, UI.scale(16, 96));
        pack();
    }

    @Override
    protected String title() {
        return "Water Scout";
    }

    // ------------------------------------------------------------------ the patrol

    @Override
    protected Outcome work() throws InterruptedException {
        if (!navigable(playerPos()))
            return Outcome.failed("not on " + (mode == Mode.OCEAN ? "deep ocean" : "fresh")
                + " water - get the boat onto the water you want scouted first");
        NLog.log(LOG, "scouting in " + mode + " mode, turning " + (turn == 1 ? "clockwise" : "anticlockwise"));

        long steered = 0;
        while (running()) {
            if (!upkeep())
                return Outcome.failed(fatalStop);
            scan();

            // Too many dead ends in a row means we've worked into a corner - a bay, or the head of
            // a river. Strike out towards open water and pick the edge up again from there.
            if (deadEnds > DEAD_END_LIMIT) {
                setStatus("Backing out of a dead end.");
                Coord2d open = randomTile(true);
                if (open != null)
                    nudge(open);
                deadEnds = 0;
                nav.pause(12);
                continue;
            }

            Coord2d heading = nextHeading();
            if (heading != null) {
                // Having committed to a heading, swing the search back around so the next scan
                // starts by looking towards the shore again rather than continuing to spiral.
                ang -= turn * Math.PI / 2;
                gui.map.wdgmsg("click", Coord.z, heading.floor(posres), 1, 0);
                setStatus("Following the edge (" + (++steered) + " legs)");
            } else {
                Gob threat = hazardNear(playerPos(), HAZARD_FLEE);
                if (threat != null) {
                    setStatus("Backing off from " + shortname(threat) + ".");
                    flee(threat);
                } else {
                    setStatus("Lost the edge - casting about.");
                    Coord2d water = randomTile(false);
                    if (water != null)
                        nudge(water);
                }
                nav.pause(12);
            }
            nav.pause(8);
        }
        return Outcome.ok();
    }

    /**
     * The next point to steer to: the first direction, sweeping around from the current heading,
     * in which nothing but navigable water lies within the probe's reach.
     *
     * @return a world point to click, or null if every direction is blocked.
     */
    private Coord2d nextHeading() {
        Coord2d me = playerPos();
        if (me == null)
            return null;
        double start = ang;
        int spokes = SPOKES;
        while (turn == 1 ? ang <= start + 2 * Math.PI : ang >= start - 2 * Math.PI) {
            boolean blocked = false;
            for (int i = 1; i <= mode.steps && !blocked; i++) {
                if (obstructed(me.add(offset(ang, i * mode.step))))
                    blocked = true;
            }
            if (!blocked) {
                deadEnds++;
                return me.add(offset(ang, mode.reach()));
            }
            deadEnds = 0;
            ang += turn * 2 * Math.PI / spokes;
        }
        return null;
    }

    private Coord2d offset(double a, double len) {
        return new Coord2d(-Math.cos(-a) * len, Math.sin(-a) * len);
    }

    /**
     * Whether a probe point is unusable: land, a solid object, another character, or the vicinity
     * of something that eats boats. Checked with a margin around the point rather than at the point
     * itself, so the boat doesn't shave the shore.
     */
    private boolean obstructed(Coord2d p) {
        int rad = mode.clearance;
        for (int i = -rad; i <= rad; i++) {
            for (int j = -rad; j <= rad; j++) {
                Coord2d t = p.add(i * TILE, j * TILE);
                if (!navigable(t) || solidAt(t) || hazardNear(t, HAZARD_AVOID) != null)
                    return true;
                if (Crowd.occupied(others, t, Crowd.PERSONAL_SPACE * 2))
                    return true;
            }
        }
        return false;
    }

    private boolean navigable(Coord2d wc) {
        if (wc == null)
            return false;
        try {
            Resource res = mcache.tilesetr(mcache.gettile(wc.floor(MCache.tilesz)));
            return res != null && mode.tiles.contains(res.name);
        } catch (Loading e) {
            // An unloaded tile is not something to steer into on faith.
            return false;
        }
    }

    private boolean solidAt(Coord2d p) {
        Coord pc = p.floor();
        for (Gob g : solids) {
            try {
                Resource res = g.getres();
                if (res != null && Pathfinder.isInsideBoundBox(g, pc))
                    return true;
            } catch (Loading | NullPointerException ignored) {
            }
        }
        return false;
    }

    /**
     * The nearest boat-wrecking creature within {@code margin} of a point, or null.
     *
     * The species filter is applied when the list is built (see {@link #scan}); what's left here is
     * the distance test, applied to EVERY hazard rather than only the last species in the list -
     * which is the grouping the stock version's condition meant but didn't say. See the class
     * comment.
     */
    private Gob hazardNear(Coord2d p, double margin) {
        if (p == null)
            return null;
        for (Gob g : hazards) {
            if (g.rc.dist(p) < margin)
                return g;
        }
        return null;
    }

    private void flee(Gob threat) {
        Coord2d me = playerPos();
        if (me == null || threat == null)
            return;
        Coord2d away = me.sub(threat.rc);
        double d = away.abs();
        away = (d < 1.0) ? new Coord2d(1, 0) : away.div(d);
        gui.map.wdgmsg("click", Coord.z, me.add(away.mul(FLEE_STEP)).floor(posres), 1, 0);
    }

    /** One short move towards a point, without engaging the pathfinder - this is a boat on water. */
    private void nudge(Coord2d towards) {
        Coord2d me = playerPos();
        if (me == null)
            return;
        Coord2d dir = towards.sub(me);
        double d = dir.abs();
        if (d < 1.0)
            return;
        gui.map.wdgmsg("click", Coord.z, me.add(dir.div(d).mul(CLICK_STEP)).floor(posres), 1, 0);
    }

    /**
     * A random nearby tile that either is or isn't navigable, used to break out of a corner. Gives
     * up after a bounded number of tries rather than looping until it finds one, since inside a
     * fully-enclosed pond there may be no such tile at all.
     */
    private Coord2d randomTile(boolean wantLand) {
        Coord2d base = playerPos();
        if (base == null)
            return null;
        int radius = RANDOM_TILE_RADIUS;
        for (int i = 0; i < 400; i++) {
            Coord2d c = base.add(random.nextInt(radius * 2) - radius, random.nextInt(radius * 2) - radius);
            if (navigable(c) != wantLand)
                return c;
        }
        return null;
    }

    // ------------------------------------------------------------------ world

    private Coord2d playerPos() {
        Gob me = (gui.map == null) ? null : gui.map.player();
        return (me == null) ? null : me.rc;
    }

    /** Refreshes the three per-cycle gob lists. See the field comment for why they're split. */
    private void scan() {
        Coord2d me = playerPos();
        if (me == null)
            return;
        List<Gob> solid = new ArrayList<>();
        List<Gob> haz = new ArrayList<>();
        String[] species = (mode == Mode.OCEAN) ? SEA_HAZARDS : RIVER_HAZARDS;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (g.id == gui.map.plgob)
                    continue;
                double d = me.dist(g.rc);
                if (d < 3 || d > SCAN_RADIUS)
                    continue;
                if (g.collisionBox != null && g.collisionBox.fx != null)
                    solid.add(g);
                // Creatures don't all carry a rendered collision box, and a walrus is exactly what
                // this bot most needs to notice - so hazards are matched by name, separately, and
                // filtered here rather than once per probe point.
                String name = resname(g);
                if (name == null)
                    continue;
                for (String s : species) {
                    if (name.endsWith(s)) {
                        haz.add(g);
                        break;
                    }
                }
            }
        }
        solids = solid;
        hazards = haz;
        others = NBotConfig.on(NBotConfig.Key.avoidOthers) ? Crowd.others(gui) : new ArrayList<>();
    }

    /** Was inherited from NBot; the base class is now bare, so the scout keeps its own. */
    private static String resname(Gob g) {
        try {
            Resource res = g.getres();
            return (res == null) ? null : res.name;
        } catch (Loading | NullPointerException e) {
            return null;
        }
    }

    private static String shortname(Gob g) {
        String n = resname(g);
        if (n == null)
            return "something";
        int i = n.lastIndexOf('/');
        return (i < 0) ? n : n.substring(i + 1);
    }
}
