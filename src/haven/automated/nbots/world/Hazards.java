package haven.automated.nbots.world;

import haven.Config;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.OCache;
import haven.Resource;
import haven.automated.pathfinder.Map;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the dangerous animals are, and how much room to give them.
 *
 * This started life inside the LP assistant (LpTargets/LpPlanner), which is the wrong home for it
 * the moment a second feature needs it: every bot that walks anywhere needs to not walk into a
 * bear, and having the bot package import the LP package to find that out inverts the dependency.
 * The LP classes now delegate here, so there is one roster and one set of margins rather than two
 * that drift.
 *
 * Worth recording that nurgling2 has no equivalent - its aggro* classes are bots that go and
 * ATTACK things, and its worker bots simply walk into whatever is in the way. Routing around
 * wildlife is ours.
 */
public class Hazards {
    /**
     * How far a dangerous beast's aggro reaches, in map units. Mirrors the 120f circle the client
     * already draws around them (Gob.updateDangerousBeastRadii), so the number the bot avoids and
     * the circle the player sees are the same thing.
     */
    public static final double RADIUS = 120.0;

    /**
     * How much clearance a bot keeps from a beast when choosing somewhere to WORK: the full
     * diameter of that circle, so a target sitting just outside the ring still isn't approached.
     * Walking to the edge of a bear's aggro range and then swinging an axe for thirty seconds is
     * how you get killed by a bear - treating the radius as if it were twice as big is cheap
     * insurance, and the only cost is skipping targets that happen to sit near one.
     */
    public static final double KEEPOUT = 2 * RADIUS;

    /**
     * How wide a berth a bot's PATHING gives a beast - the aggro circle plus a quarter.
     *
     * The two numbers answer different questions. KEEPOUT asks "may I stand here for thirty seconds
     * swinging an axe while that thing wanders?", and doubling the radius is cheap there because
     * the only cost is skipping a target. This one asks "may I walk past at range?", where the same
     * doubling would wall off a 480-unit-wide disc of ground - routinely every route to everything
     * - and turn a detour back into the refusal it replaced.
     */
    public static final double PATH_CLEARANCE = 1.25 * RADIUS;

    private Hazards() {}

    /**
     * One of the beasts that will chase and kill you - the same roster the client draws a red
     * radius circle for. Matched by suffix, as Gob.updateDangerousBeastRadii does, so variants
     * resolve the same way there and here.
     */
    public static boolean isDangerous(String res) {
        // Every beast on the roster lives under gfx/kritter, so this one comparison rejects the
        // trees, bushes and boulders that make up nearly every gob on screen before the suffix scan
        // below - which runs for every loaded gob, a couple of times a second, while a bot is
        // approaching something.
        if (res == null || !res.startsWith("gfx/kritter/"))
            return false;
        for (String beast : Config.beastResPaths) {
            if (res.endsWith(beast))
                return true;
        }
        return false;
    }

    private static String resname(Gob gob) {
        if (gob == null)
            return null;
        try {
            Resource res = gob.getres();
            return (res == null) ? null : res.name;
        } catch (Loading l) {
            return null;
        }
    }

    /**
     * Every live dangerous beast currently in view.
     *
     * Knocked-out beasts are excluded: they can't chase anything, and treating a downed bear as a
     * no-go area would wall off the ground around the thing a hunting bot most wants to reach.
     */
    public static List<Gob> all(GameUI gui) {
        List<Gob> out = new ArrayList<>();
        if (gui == null)
            return out;
        OCache oc = gui.ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc) {
                if (isDangerous(resname(gob)) && !Boolean.TRUE.equals(gob.knocked))
                    out.add(gob);
            }
        }
        return out;
    }

    /** The nearest dangerous beast within {@code margin} of a point, or null. */
    public static Gob within(GameUI gui, haven.Coord2d c, double margin) {
        Gob nearest = null;
        double best = margin;
        for (Gob gob : all(gui)) {
            double d = gob.rc.dist(c);
            if (d < best) {
                best = d;
                nearest = gob;
            }
        }
        return nearest;
    }

    /** The nearest dangerous beast inside the work keep-out margin of a point, or null. */
    public static Gob near(GameUI gui, haven.Coord2d c) {
        return within(gui, c, KEEPOUT);
    }

    /** Whether a candidate target sits close enough to a beast that going for it isn't worth it. */
    public static boolean nearAny(Gob target, List<Gob> hazards) {
        if (target == null)
            return false;
        for (Gob beast : hazards) {
            if (target.rc.dist(beast.rc) < KEEPOUT)
                return true;
        }
        return false;
    }

    /**
     * The beasts' aggro rings expressed as pathfinder keep-out circles, so a route can be planned
     * AROUND them instead of the approach simply being abandoned.
     *
     * Any circle the character is already inside is left out: the pathfinder's own starting cell
     * would be inside a blocked region and no route out of it would exist. Getting clear of one is
     * the caller's job (BotNav.retreatFrom), and once it has, the ring reappears here.
     */
    public static Map.Keepout[] keepouts(GameUI gui, haven.Coord2d from) {
        List<Map.Keepout> out = new ArrayList<>();
        for (Gob gob : all(gui)) {
            if (from != null && gob.rc.dist(from) <= PATH_CLEARANCE)
                continue;
            out.add(new Map.Keepout(gob.rc, PATH_CLEARANCE));
        }
        return out.toArray(new Map.Keepout[0]);
    }
}
