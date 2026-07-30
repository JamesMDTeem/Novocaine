package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Moving;

import static haven.OCache.posres;

/**
 * Moving the character the way the game itself moves it, as the floor under everything else.
 *
 * Every bot in this tree walks by calling {@code MapView.pfLeftClick}, which runs Hurricane's
 * client-side pathfinder: an 88-tile visibility graph built from collision boxes, A*-ed over, and
 * then played back as a series of clicks. It is a good optimiser and a catastrophic primitive,
 * because it has four distinct ways to decline to move at all, all of them silent:
 *
 * - our OWN position is inside a collision box ({@code Map.isOriginBlocked}). Boxes are inflated by
 *   the player's own width so that a path can be planned for a point rather than a disc, so simply
 *   standing next to a thing puts us inside one. Its escape hatch, {@code getFreeLocation}, probes
 *   four points at THREE UNITS - a quarter of a tile - against boxes that are eight or more units
 *   thick, and a shut gate is deliberately blocked as a slab thirty-two units long. It comes back
 *   empty, and the pathfinder returns having issued nothing.
 * - the destination is inside a box: no edges from the goal vertex, empty path, nothing issued.
 * - a keep-out we are standing in blocks the start vertex, with the same result.
 * - any tile in the 88-tile window belongs to a grid still loading, so {@code MCache.gettile}
 *   throws {@code Loading} out of the pathfinder thread, which dies where it stands.
 *
 * All four look identical from outside: the character does not move and nothing is logged. Travel
 * reads that as "no headway", the gate layer reads "no headway" as "a shut gateway is in the way",
 * and a bot standing in its own base sets off to open a gate. Worse, the first case is a DEADLOCK -
 * the bot walks up to a gate to open it, is now inside the gate's blocked slab, and from there
 * cannot issue a single move, including the move that would take it back out again.
 *
 * A vanilla player never meets any of this, because a vanilla player's click goes straight to the
 * server: {@code wdgmsg("click", ...)} with button one, and the server replies with a {@link
 * haven.LinMove} - a start point and a velocity. The server ALWAYS accepts it. That is the
 * primitive, and everything else is an optimisation on top of it.
 *
 * So this class owns the primitive, and the two obligations that come with taking it:
 *
 * WE MUST CHECK THE LINE OURSELVES. Server movement is linear and the server is happy to swim -
 * that is precisely how a bot "paths straight across a river" - so a point may only be handed over
 * once {@link #lineClear} has walked the segment across our own terrain, wall and hazard records.
 * The pathfinder was doing that job; taking the primitive means taking the job.
 *
 * AND WE KNOW EXACTLY WHEN IT IS DONE. {@link Moving} is set when the server starts the character
 * and deleted when it stops it ({@code LinMove.$linstep} deletes the attribute on the stop
 * message). It is not a guess, a poll of velocity, or a thread that might still be alive; it is the
 * server saying so.
 */
public class Walk {
    private Walk() {}

    /** How finely a straight line is sampled before it is trusted, in world units. */
    private static final double SAMPLE = 11 * 0.5;
    /**
     * The furthest a straight line is worth checking and handing over.
     *
     * This is a fallback for getting a bot moving again, not a replacement for routing. Beyond a
     * few tiles the sampling below is doing the local pathfinder's job worse than it does, and
     * every unit past here is a unit walked without anything having planned it.
     */
    public static final double MAX_DIRECT = 11 * 8.0;
    /** Polls (of 25ms) to wait for the server to acknowledge a move by starting one. */
    private static final int START_TICKS = 12;
    /** Polls to wait for a move to finish. A long leg at walking pace, with room to spare. */
    private static final int RUN_TICKS = 400;
    /** How far an unstick step tries to travel, and the headings it tries in turn. */
    private static final double NUDGE = 11 * 2.5;
    private static final int NUDGE_HEADINGS = 8;

    /** True while the server says the character is moving. The only authority on this. */
    public static boolean moving(GameUI gui) {
        Gob me = player(gui);
        return (me != null) && (me.getattr(Moving.class) != null);
    }

    private static Gob player(GameUI gui) {
        return ((gui == null) || (gui.map == null)) ? null : gui.map.player();
    }

    /** The server's own move order. Linear, and never refused. */
    public static void click(GameUI gui, Coord2d wc) {
        if ((gui != null) && (gui.map != null) && (wc != null))
            gui.map.wdgmsg("click", Coord.z, wc.floor(posres), 1, 0);
    }

    /**
     * Walks a straight line to a point using the server's movement, and waits it out.
     *
     * Does NOT check the line - callers must have satisfied themselves with {@link #lineClear}
     * first, because this will cheerfully swim. Kept separate so that the one caller which knows
     * the line is fine for another reason (stepping off something we are standing on) does not
     * have to prove it twice.
     */
    public static boolean straightTo(BotNav nav, GameUI gui, Coord2d dest, double tol)
            throws InterruptedException {
        if (dest == null)
            return false;
        click(gui, dest);
        /* Moving APPEARING is the server accepting the order; Moving going away again is it
         * finishing. Waiting for the first before watching for the second is what stops the gap
         * before the reply arriving being read as "already stopped, so we must be there" - the
         * same mistake the old fixed settling pauses were papering over, fixed properly. */
        nav.waitUntil(() -> moving(gui), START_TICKS);
        nav.waitUntil(() -> {
            Gob me = player(gui);
            return (me == null) || (me.rc.dist(dest) <= tol) || !moving(gui);
        }, RUN_TICKS);
        Gob me = player(gui);
        return (me != null) && (me.rc.dist(dest) <= tol);
    }

    /**
     * Whether a straight line is safe to hand to the server.
     *
     * Sampled every half tile against the same records the router plans on, so a line this approves
     * is a line the router would have drawn: {@link Terrain} for ground and deep water, {@link
     * Observed} for walls we have learned, {@link Hazards} for anything that would eat us, and the
     * live collision boxes for whatever is standing in the way right now.
     *
     * The learned wall was missing from that list for a long time while this comment claimed it was
     * there. It matters more than any of the others, because it is the only one of them that can say
     * anything about ground the server has not sent objects for yet - and a line handed to the server
     * is walked in a STRAIGHT line whether or not a palisade is on it.
     *
     * Refuses outright when the map file cannot place us. That is the conservative direction and
     * deliberately so - the cost of refusing is that a wedged bot stays wedged for another moment,
     * and the cost of allowing is a character swimming an ocean because nothing could say no.
     */
    public static boolean lineClear(GameUI gui, Coord2d from, Coord2d to) {
        Gob me = player(gui);
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if ((me == null) || (here == null) || (from == null) || (to == null))
            return false;
        double len = from.dist(to);
        if (len > MAX_DIRECT)
            return false;
        // The player stands in both coordinate spaces at once, so one subtraction converts the rest.
        Coord2d off = here.sc.sub(me.rc);
        int steps = Math.max(1, (int) Math.ceil(len / SAMPLE));
        /* A gateway anywhere on the line means this is a gate manoeuvre, and a gate manoeuvre is
         * ALLOWED to pass close to the wall its gateway stands in - squaring up and stepping through
         * both run within a tile of the posts. Established first so the wall test below cannot refuse
         * the one line that has to be allowed to go near a wall. Without this the bot could not
         * approach a gate at all, which is a worse failure than the one being fixed. */
        boolean throughGateway = false;
        for (int i = 0; i <= steps; i++) {
            Coord2d p = from.add(to.sub(from).mul((double) i / steps));
            if (Observed.gate(here.seg, p.add(off).floor(MCache.tilesz))) {
                throughGateway = true;
                break;
            }
        }
        for (int i = 0; i <= steps; i++) {
            Coord2d p = from.add(to.sub(from).mul((double) i / steps));
            Coord tile = p.add(off).floor(MCache.tilesz);
            if (!Terrain.walkable(gui, here.seg, tile))
                return false;
            /* The WALL we have learned, which this never asked about and its own doc claimed it did.
             *
             * Everything else here is either live or about the ground: `occupied` reads collision
             * boxes, which exist only for gobs that are currently LOADED. So a palisade a few tiles
             * beyond the objects the server has sent is invisible here - the line is pronounced clear,
             * the server is handed it, and server movement is LINEAR, so the character walks into the
             * wall. That is exactly "it doesn't see it as invalid because it is just outside render",
             * and the wall being remembered all along did not help because nothing on this path was
             * reading the memory.
             *
             * Intermediate samples only, like `occupied` below and for the same reason: the ends of a
             * line are allowed to be against something, or nothing could ever walk up to a wall or
             * step away from one. */
            if ((i > 0) && (i < steps) && !throughGateway
                    && (Observed.at(here.seg, tile) == Observed.WALL))
                return false;
            if (Hazards.within(gui, p, Hazards.PATH_CLEARANCE) != null)
                return false;
            // The first and last samples are where we already are and where we mean to end up;
            // both are allowed to be against something, or nothing could ever leave a barrel.
            if ((i > 0) && (i < steps) && BotNav.occupied(gui, p))
                return false;
        }
        return true;
    }

    /**
     * Gets a character that cannot move moving again, by stepping somewhere - anywhere - clear.
     *
     * The case this exists for is standing against a shut gate, which blocks a slab three tiles
     * long, so every path the client pathfinder is asked for starts inside a wall and comes back
     * empty. Nothing in the client recovers from that; the bot simply stops for good.
     *
     * Tries headings in turn rather than probing four points a quarter of a tile away, which is
     * what {@code Map.getFreeLocation} does and why it never finds anything: the boxes it is
     * trying to escape are several tiles across.
     *
     * @param toward where we were trying to get to, tried first so that getting unstuck is also
     *               progress where it can be. Null to fan out from due east.
     * @return true if a step was taken, which is the caller's cue to re-plan from the new position.
     */
    public static boolean unstick(BotNav nav, GameUI gui, Coord2d toward)
            throws InterruptedException {
        Gob me = player(gui);
        if (me == null)
            return false;
        /* Headings are tried starting from the way we actually wanted to go and fanning out from
         * there, so the step is a short leg of the journey when the journey allows it and a plain
         * escape when it does not. Being wrong about the heading costs a couple of tiles; not
         * moving costs the whole errand. */
        double bias = 0;
        if (toward != null) {
            Coord2d v = toward.sub(me.rc);
            if (v.abs() >= 1.0)
                bias = Math.atan2(v.y, v.x);
        }
        for (int i = 0; i < NUDGE_HEADINGS; i++) {
            double a = bias + ((i * 2 * Math.PI) / NUDGE_HEADINGS);
            Coord2d to = me.rc.add(new Coord2d(Math.cos(a), Math.sin(a)).mul(NUDGE));
            if (!lineClear(gui, me.rc, to))
                continue;
            if (straightTo(nav, gui, to, 11 * 1.0))
                return true;
            // Moved at all? Even a partial step is out of whatever we were inside.
            Gob now = player(gui);
            if ((now != null) && (now.rc.dist(me.rc) > 11 * 0.5))
                return true;
        }
        return false;
    }
}
