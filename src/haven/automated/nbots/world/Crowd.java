package haven.automated.nbots.world;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.Resource;
import haven.automated.pathfinder.Map;

import java.util.ArrayList;
import java.util.List;

/**
 * What the other characters in view are doing, inferred purely by watching them.
 *
 * This is the half of multi-bot coordination that needs no agreement and no shared state: every
 * client already receives every nearby character as a gob, complete with position and animation
 * pose, so "someone is chipping that boulder" is directly observable. It therefore works against
 * ANY other client - a friend's Hurricane, a stranger, a second copy of this fork - and not just
 * against instances that opted into the claim registry ({@link WorkClaims}), which is the exact
 * half that can't be observed and needs a channel.
 *
 * The two are deliberately layered rather than alternatives: observation decides whether a target
 * is worth approaching at all, and the registry then settles which of OUR instances gets which
 * standing position, so two bots that decide simultaneously can't both walk to the same spot.
 *
 * Nothing here blocks a target merely because someone is standing on it. Several characters can
 * work one boulder or one tree at once as long as they have room, which is why the unit of
 * exclusion is a {@link WorkSlots} position and not the object.
 */
public class Crowd {
    /** The resource every player character is drawn from. */
    private static final String PLAYER_RES = "gfx/borka/body";

    /**
     * Animation poses that mean "this character is working on something adjacent to it". Kept
     * broad on purpose: mis-reading an idle character as busy costs one skipped slot, while
     * mis-reading a busy one as idle costs two characters trying to occupy the same spot.
     */
    private static final String[] WORK_POSES = {
        "pickan",      // mining / chipping stone
        "treechop",    // felling
        "chopping",    // bush / branch work
        "shoveldig",   // digging, stump clearing
        "dig",
        "sawing",
        "bldg",        // construction
    };

    /**
     * How close another character has to be to a target before we credit them with working it.
     * Generous relative to actual reach (~2 tiles) because their gob position lags what the server
     * thinks, and because a character mid-swing drifts around the object.
     */
    public static final double WORKING_RANGE = 11 * 4.0;

    /**
     * Radius the pathfinder should treat another character as an obstacle within.
     *
     * Characters are NOT solid in this game - you can walk through one - so nothing forces this;
     * it exists so bots don't converge into the same pixel and end up shoving each other around a
     * work site. Kept small (about one tile) so it produces a slight detour rather than a refusal
     * on a crowded site.
     */
    public static final double PERSONAL_SPACE = 11 * 1.2;

    private Crowd() {}

    /** Every other player character currently in view. Never includes us. */
    public static List<Gob> others(GameUI gui) {
        List<Gob> out = new ArrayList<>();
        if (gui == null || gui.map == null)
            return out;
        long me = gui.map.plgob;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (g.id == me)
                    continue;
                try {
                    Resource res = g.getres();
                    if (res != null && PLAYER_RES.equals(res.name))
                        out.add(g);
                } catch (Loading | NullPointerException ignored) {
                }
            }
        }
        return out;
    }

    /** True if this character's animation says it is working on something. */
    public static boolean isWorking(Gob g) {
        if (g == null)
            return false;
        try {
            for (String pose : g.getPoses()) {
                if (pose == null)
                    continue;
                for (String w : WORK_POSES) {
                    if (pose.contains(w))
                        return true;
                }
            }
        } catch (Loading | NullPointerException ignored) {
        }
        return false;
    }

    /**
     * How many other characters appear to be working on the thing at {@code wc}.
     *
     * The list is passed in rather than fetched here because callers ask this once per candidate
     * target while scanning a whole site: fetching it internally would rebuild the same list, under
     * the object-cache lock, once per tree in the forest.
     */
    public static int workersOn(List<Gob> others, Coord2d wc) {
        if (wc == null)
            return 0;
        int n = 0;
        for (Gob g : others) {
            if (g.rc.dist(wc) <= WORKING_RANGE && isWorking(g))
                n++;
        }
        return n;
    }

    /**
     * True if another character is standing close enough to {@code spot} that we'd be arriving on
     * top of them. Used to reject a work slot before claiming it, so an unclaimed slot that a
     * non-participating client is already using still doesn't get taken.
     */
    public static boolean occupied(List<Gob> others, Coord2d spot, double radius) {
        if (spot == null)
            return false;
        for (Gob g : others) {
            if (g.rc.dist(spot) < radius)
                return true;
        }
        return false;
    }

    /** Convenience for the one-off case, where rebuilding the list costs nothing. */
    public static boolean occupied(GameUI gui, Coord2d spot, double radius) {
        return occupied(others(gui), spot, radius);
    }

    /**
     * Keep-out circles for every other character, for handing to the pathfinder.
     *
     * Deliberately built EXCLUDING anyone we're already standing on top of: the pathfinder cannot
     * plan a route out of a region it has been told is impassable, so a circle containing our own
     * position turns every search into a failure. (Map.inKeepout has its own backstop for the case
     * where someone walks onto us after the circles were published; this avoids provoking it.)
     */
    public static Map.Keepout[] keepouts(GameUI gui, Coord2d from) {
        List<Gob> them = others(gui);
        List<Map.Keepout> out = new ArrayList<>(them.size());
        for (Gob g : them) {
            if (from != null && from.dist(g.rc) <= PERSONAL_SPACE)
                continue;
            out.add(new Map.Keepout(g.rc, PERSONAL_SPACE));
        }
        return out.toArray(new Map.Keepout[0]);
    }

    /**
     * Merges two keep-out sets. Bots need both the wildlife rings the LP assistant already
     * publishes and the other characters' personal space, and {@link Map#keepout} takes a single
     * array that replaces whatever was there before.
     */
    public static Map.Keepout[] merge(Map.Keepout[] a, Map.Keepout[] b) {
        if (a == null || a.length == 0)
            return (b == null) ? new Map.Keepout[0] : b;
        if (b == null || b.length == 0)
            return a;
        Map.Keepout[] out = new Map.Keepout[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
