package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.automated.nbots.core.NBotConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Getting through a gateway rather than around the wall it is in.
 *
 * @deprecated Use {@link GateManager} directly. This class remains as a thin delegation
 *             wrapper for backward compatibility.
 *
 * {@link Barriers} already keeps gates out of the wall map, so a planned route happily leads
 * straight at one - and then stops dead, because a SHUT gate is solid to the local pathfinder
 * exactly as a wall is. Every bot that walked out of a base and came back met this: it routed
 * correctly to the gateway it had left through, arrived, and then spent its whole stall budget
 * walking into a closed gate. Nothing in the client opens one.
 *
 * The fix is a layer that reads the live gob list (not just the recorded map) and, when a gate
 * is in the way, walks to it, right-clicks, and waits for the server to answer. That layer is
 * {@link GateManager}; this class delegates to it.
 *
 * GATE STATE PERSISTENCE. A gate is a gob that the client can see and click. The map file has no
 * notion of gate state, so when the client restarts the gate is back to its default (usually shut).
 * The gate record in Observed is updated from the live gob on every sweep, so after a few seconds
 * of play the record is correct again - but a fresh start always sees shut gates until observation
 * catches up.
 *
 * PICKING A GATE. The key question is always which gate to use, because there may be several. The
 * answer depends on where we are, where we are going, and which gates are shut. Three queries:
 *
 * - {@link #towards}: the best SHUT gate on the way to a destination. Shut only, because an open
 *   one is a gap the route already walks through and has nothing to be done to it - picking those
 *   too is what ended journeys at whichever gateway happened to lie off to one side of them.
 * - {@link #blocking}: the shut gate between us and a destination. Used when a leg is stuck, to
 *   tell whether the fix is to open a gate or to walk somewhere else.
 * - {@link #onRoute}: the gate a planned route passes through. Used after a leg failure to check
 *   whether the route still goes through the same gate, because a gate that switched state between
 *   planning and arrival is the one failure mode worth detecting.
 *
 * THE THREE-STEP PASS. Getting through a gate is not a single click. The bot must:
 *
 * 1. Walk to within reach of the gate (the local pathfinder handles this, because the gate is
 *    passable in the map once it is open - the question is only whether it IS open).
 * 2. Click the gate to open it, and wait for the server to answer.
 * 3. Walk through the opening.
 *
 * Step one is the caller's job (travelTo handles it by treating a gate as the leg destination).
 * Step two is {@link #pass}, which handles the click-and-wait loop including the flower menu for
 * gates that show one. Step three is also the caller's job: after {@link #pass} returns true,
 * the bot is on the other side and the leg can continue.
 *
 * FAILING Gracefully. A bot that cannot open a gate should not spin forever trying. {@link #pass}
 * gives up after a few attempts and returns false, at which point the caller retries the leg
 * around the gate (which may or may not work, depending on whether the gate is the only way).
 * The same logic applies in reverse for closing a gate behind us: if we cannot close it, we log
 * it and move on.
 *
 * SHUTTING A GATE. Some gates should be shut behind the bot - air locks, dungeon entrances, etc.
 * The config flag {@code closeGates} controls this. When enabled, after passing through a gate the
 * bot walks back to it and clicks it shut. This is done AFTER the leg completes, not during,
 * because it could not shut a gate, so a failed close is logged and the journey continues.
 */
@Deprecated
public class Gates {

    // ------------------------------------------------------------------ reading a gate

    /**
     * True if {@code g} is a gate gob.
     */
    public static boolean isGate(Gob g) {
        return GateManager.isGate(g);
    }

    /**
     * True if this gate stands open.
     *
     * Unknown answers OPEN, which is the safe direction here: the cost of believing a shut gate is
     * open is one wasted walk that the leg-failure path already handles, while the cost of
     * believing an open gate is shut is a bot that stops to "open" a gateway it could have walked
     * through, and then closes it in the player's face.
     */
    public static boolean isOpen(Gob g) {
        return GateManager.isOpen(g);
    }

    /** Every gate gob currently loaded. */
    public static List<Gob> loaded(GameUI gui) {
        return GateManager.loaded(gui);
    }

    // ---------------------------------------------------------------- picking a gate

    /**
     * The gateway most worth walking to on the way to {@code dest}, or null if none is.
     *
     * Scored on the whole journey through it - how far to reach it plus how far remains after -
     * rather than on how near it is to us. Nearest-first picks the gate behind us as readily as the
     * one ahead, and a gate that does not shorten the trip is not on our way at all, which is what
     * the second test rejects.
     *
     * A palisade is several gate gobs wide when it has a double gate, so ties fall to the nearer.
     */
    public static Gob towards(GameUI gui, Coord2d dest, Set<Long> skip) {
        return GateManager.towards(gui, dest, skip);
    }

    /**
     * The SHUT gateway standing between us and {@code dest}, or null if nothing is.
     *
     * Asked BEFORE walking rather than after failing to, and that is the whole difference. Gate
     * handling used to be reached only when a leg gave up, on the reasoning that a bot stopped
     * dead in front of a wall has plainly met a shut gate - but a bot in front of a wall is not
     * stopped dead. The local pathfinder is good at its job: a shut gate is just another solid
     * thing to it, so it walks AROUND, and if the wall has an end it will find it. What that looks
     * like from outside is a bot that ignores the gateway three tiles away and sets off down the
     * palisade, which is precisely what was reported, every time, on both sides of the gate.
     *
     * It gets worse the longer it goes on: the leg only fails once seven hops have made no
     * headway, and those seven hops are spent wandering. By the time the gate code finally ran the
     * bot was fifty tiles from the gateway - past {@link #SEARCH} - so it truthfully reported that
     * there was no gateway near enough to use. The gate was never the part that was broken.
     *
     * Held to {@link #DETOUR}, so this only claims gates genuinely on the line. Being wrong here
     * costs a walk to a fence that was never in the way.
     */
    public static Gob blocking(GameUI gui, Coord2d dest, Set<Long> skip) {
        return GateManager.blocking(gui, dest, skip);
    }

    /**
     * The shut gateway this leg is ROUTED THROUGH, or null if it isn't routed through one.
     *
     * The other two ways of choosing a gate are heuristics about geometry - is one near, does one
     * project onto the line - and both have been wrong in both directions. This is not a heuristic:
     * the router plans over tiles and a gateway's tiles are passable to it, so a leg whose line
     * crosses one is a leg the router decided to send through it. Reading that back is not second-
     * guessing the route, it is carrying it out.
     *
     * Which is what lets this be asked BEFORE walking rather than after failing to. The earlier
     * proactive check had to be withdrawn because it fired on any gate near the line, and the way
     * past an air lock is often beside its side stubs - so it walked the bot to a gateway the route
     * had deliberately gone round. But waiting for a stall does not work either, and cannot: a shut
     * gate is just another solid to the local pathfinder, which goes AROUND it perfectly happily.
     * The bot therefore keeps moving, never stalls, and spends the whole shift walking up and down
     * inside its own wall while nothing ever asks whether the gate in its route is shut. Every
     * "it won't open the gate any more" report is that.
     *
     * A leg's line crossing a gate tile settles it, because a leg is only ever a straight run the
     * router has already certified as clear - so if a gateway is on it, going through the gateway
     * is the plan.
     */
    public static Gob onRoute(GameUI gui, Coord2d from, Coord2d to, Set<Long> skip) {
        return GateManager.onRoute(gui, from, to, skip);
    }

    // ---------------------------------------------------------------- passing a gate

    /**
     * Opens (if shut) the gateway between here and {@code dest}, walks through it, and returns
     * whether we ended up on the far side.
     *
     * @return true if we ended up on the far side, so the caller should re-plan and carry on.
     *         False means there was no gate worth using, or using it did not work - in both cases
     *         the caller should fall back on whatever it would have done without gates at all.
     */
    public static boolean pass(BotNav nav, GameUI gui, Coord2d dest, long which, Set<Long> skip,
                               String log) throws InterruptedException {
        return GateManager.pass(nav, gui, dest, which, skip, log);
    }

    // ---------------------------------------------------------------- formatting

    /** Format a coordinate for logging. */
    static String fmt(Coord2d c) {
        return GateManager.fmt(c);
    }
}
