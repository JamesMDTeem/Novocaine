package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.ResDrawable;
import haven.Resource;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.Widgets;

import java.util.ArrayList;
import java.util.List;

import static haven.OCache.posres;

/**
 * Getting through a gateway rather than around the wall it is in.
 *
 * {@link Barriers} already keeps gates out of the wall map, so a planned route happily leads
 * straight at one - and then stops dead, because a SHUT gate is solid to the local pathfinder
 * exactly as a wall is. Every bot that walked out of a base and came back met this: it routed
 * correctly to the gateway it had left through, arrived, and then spent its whole stall budget
 * walking into a closed gate. Nothing in the client opens one.
 *
 * How a gate reports its state is worth writing down, because it is not obvious and it is the only
 * reliable signal: the gob carries a {@link ResDrawable} whose {@code sdt} byte 0 is 1 when the
 * gate stands open. That is the same test the local pathfinder uses to decide whether to put a
 * collision box there ({@code pathfinder.Map.analyzeGobHitBoxes}) and the same one
 * {@code HitBoxes.checkHitAble} uses, so reading it here means all three agree about what "open"
 * means rather than this one guessing.
 *
 * Opening is done by right-clicking and then watching for EITHER outcome: some gateways answer with
 * a flower menu to pick "Open" from, others simply toggle. Rather than deciding which kind this is
 * from the resource name - a list that would rot - the click is issued and both are waited for.
 *
 * Closing behind is deliberately opt-in and deliberately best-effort. Leaving a base open because a
 * bot was stopped mid-trip is a real cost to the player, but so is a bot that abandons its errand
 * because it could not shut a gate, so a failed close is logged and the journey continues.
 */
public class Gates {
    /** Close enough for a right-click on the gate to land. */
    private static final double REACH = 11 * 3.5;
    /** How far from the failed leg a gateway is still worth walking to. */
    private static final double SEARCH = 11 * 45.0;
    /** How far past the gateway to step before calling it "through". */
    private static final double THROUGH = 11 * 5.0;
    /** Polls (of 25ms) to wait for a gate to finish swinging. */
    private static final int SWING_TICKS = 80;

    private Gates() {}

    // ------------------------------------------------------------------ reading a gate

    public static boolean isGate(Gob g) {
        try {
            Resource res = (g == null) ? null : g.getres();
            return (res != null) && (Barriers.kind(res.name) == Barriers.Kind.GATE);
        } catch (RuntimeException e) {
            // Includes Loading: a gob whose resource hasn't arrived isn't a gate we can act on yet.
            return false;
        }
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
        try {
            ResDrawable rd = (g == null) ? null : g.getattr(ResDrawable.class);
            return (rd == null) || (rd.sdt.checkrbuf(0) == 1);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Every gate gob currently loaded. */
    public static List<Gob> loaded(GameUI gui) {
        List<Gob> out = new ArrayList<>();
        if (gui == null || gui.map == null)
            return out;
        try {
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob g : gui.ui.sess.glob.oc) {
                    if (isGate(g))
                        out.add(g);
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

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
    public static Gob towards(GameUI gui, Coord2d dest) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if (me == null || dest == null)
            return null;
        double direct = me.rc.dist(dest);
        Gob best = null;
        double bestcost = Double.MAX_VALUE;
        for (Gob g : loaded(gui)) {
            double toGate = me.rc.dist(g.rc);
            if (toGate > SEARCH)
                continue;
            double onwards = g.rc.dist(dest);
            // Going through it has to actually get us closer, or it is a gate in the wrong wall.
            if (onwards >= direct)
                continue;
            double cost = toGate + onwards;
            if (cost < bestcost) {
                bestcost = cost;
                best = g;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ using one

    /**
     * Walks through the gateway between us and {@code dest}, opening it if it is shut.
     *
     * @return true if we ended up on the far side, so the caller should re-plan and carry on.
     *         False means there was no gate worth using, or using it did not work - in both cases
     *         the caller should fall back on whatever it would have done without gates at all.
     */
    public static boolean pass(BotNav nav, GameUI gui, Coord2d dest, String log)
            throws InterruptedException {
        if (!NBotConfig.on(NBotConfig.Key.useGates))
            return false;
        Gob gate = towards(gui, dest);
        if (gate == null) {
            NLog.log(log, "gate: nothing usable between here and " + fmt(dest));
            return false;
        }
        long id = gate.id;
        boolean wasOpen = isOpen(gate);
        NLog.log(log, "gate: using #" + id + " at " + fmt(gate.rc)
            + " (" + (wasOpen ? "open" : "shut") + ") on the way to " + fmt(dest));

        Gob me = gui.map.player();
        if (me == null)
            return false;
        // Captured BEFORE approaching, because the far side is defined by which side we came from,
        // and once we are stood in the gateway that information is gone.
        Coord2d from = me.rc;

        if (!nav.approach(gate, REACH)) {
            NLog.log(log, "gate: couldn't get to #" + id);
            return false;
        }

        if (!wasOpen && !toggle(nav, gui, id, true, log)) {
            NLog.log(log, "gate: #" + id + " wouldn't open - leaving it alone");
            return false;
        }

        Gob live = nav.gob(id);
        Coord2d through = beyond((live == null) ? gate.rc : live.rc, from);
        boolean crossed = nav.stepTo(through, 11 * 2.5);
        NLog.log(log, "gate: step through to " + fmt(through) + (crossed ? " ok" : " FAILED"));

        /* Only shut what we opened. A gate the player left standing open is theirs, and a bot that
         * tidies it away has changed the base rather than passed through it. */
        if (crossed && !wasOpen && NBotConfig.on(NBotConfig.Key.closeGates)) {
            if (!toggle(nav, gui, id, false, log))
                NLog.log(log, "gate: couldn't close #" + id + " behind us");
        }
        return crossed;
    }

    /**
     * A point on the far side of the gate, three tiles past it along the line we approached on.
     *
     * Aiming at the destination instead would be wrong whenever the gateway is not square-on to it,
     * which is most of the time - the step would clip the wall beside the opening. The line we came
     * in on is by construction perpendicular enough to get through.
     */
    private static Coord2d beyond(Coord2d gate, Coord2d from) {
        Coord2d dir = gate.sub(from);
        double len = dir.abs();
        if (len < 1.0)
            return gate;
        return gate.add(dir.div(len).mul(THROUGH));
    }

    /**
     * Right-clicks the gate and waits for it to reach the wanted state.
     *
     * Both answers are handled because both happen: the click may open a flower menu to choose from,
     * or it may simply toggle. Waiting for the STATE rather than for the click to be acknowledged is
     * what makes that difference not matter, and it is also the only way to notice that the
     * character lacks permission on this gate - which shows up as a click that changes nothing.
     */
    private static boolean toggle(BotNav nav, GameUI gui, long id, boolean want, String log)
            throws InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Gob g = nav.gob(id);
            if (g == null)
                return false;
            if (isOpen(g) == want)
                return true;
            gui.map.wdgmsg("click", Coord.z, g.rc.floor(posres), 3, 0, 0, (int) g.id,
                g.rc.floor(posres), 0, -1);
            // Give a menu a moment to appear, and take the matching petal if one does.
            nav.pause(8);
            FlowerMenu fm = Widgets.find(gui.ui.root, FlowerMenu.class);
            if (fm != null) {
                String wanted = want ? "Open" : "Close";
                boolean picked = false;
                for (FlowerMenu.Petal p : fm.opts) {
                    if (wanted.equalsIgnoreCase(p.name)) {
                        fm.wdgmsg("cl", p.num, 0);
                        picked = true;
                        break;
                    }
                }
                if (!picked) {
                    NLog.log(log, "gate: menu had no \"" + wanted + "\" - offered " + petals(fm));
                    fm.wdgmsg("cl", -1);
                }
                nav.waitUntil(() -> Widgets.find(gui.ui.root, FlowerMenu.class) == null, 40);
            }
            final boolean target = want;
            nav.waitUntil(() -> {
                Gob now = nav.gob(id);
                return (now == null) || (isOpen(now) == target);
            }, SWING_TICKS);
            Gob now = nav.gob(id);
            if ((now == null) || (isOpen(now) == want))
                return true;
            NLog.log(log, "gate: #" + id + " still "
                + (want ? "shut" : "open") + " after attempt " + (attempt + 1));
        }
        return false;
    }

    private static String petals(FlowerMenu fm) {
        StringBuilder sb = new StringBuilder("[");
        for (FlowerMenu.Petal p : fm.opts) {
            if (sb.length() > 1)
                sb.append(", ");
            sb.append(p.name);
        }
        return sb.append(']').toString();
    }

    static String fmt(Coord2d c) {
        return (c == null) ? "nowhere" : ("(" + (int) c.x + "," + (int) c.y + ")");
    }
}
