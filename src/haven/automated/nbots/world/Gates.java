package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.ResDrawable;
import haven.Resource;
import haven.automated.helpers.HitBoxes;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.Widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    /**
     * How far past the gateway to step before calling it "through".
     *
     * Three tiles, which clears a gate's own two-tile footprint and stops. Five put the far side of
     * an AIR LOCK's inner gate inside the outer wall, three tiles beyond the chamber - so the step
     * aimed at solid ground, got as near as it could, and left the bot shut in the chamber, which
     * then re-planned back out through the gate it had just come in by.
     */
    private static final double THROUGH = 11 * 3.0;
    /**
     * How near a shut gateway has to be before it is worth stopping the walk for.
     *
     * This is what keeps a proactive gate check from overruling the route. The router already
     * decided which gateway this journey uses, and where it decided to go ROUND one - an air lock's
     * side stubs are only a few tiles long, so the way past is usually beside them - the straight
     * line to the next waypoint still crosses the gate. Acting on that walked the bot twenty tiles
     * to a gateway nothing was asking for, opened it, and stepped into the chamber.
     *
     * Near enough to have plainly arrived at the gateway, then. A leg that genuinely leads through
     * one ends at its block, so this fires with a few tiles to spare, and before the walk starts
     * swinging sideways.
     */
    private static final double NEAR = 11 * 8.0;
    /** Polls (of 25ms) to wait for a gate to finish swinging. */
    private static final int SWING_TICKS = 80;
    /**
     * Polls to wait for the server to answer a right-click on a gate at all - with a menu, or by
     * starting to swing it. Generous, because it is a ceiling on a wait that ends on the answer
     * rather than a delay that is always paid.
     */
    private static final int ANSWER_TICKS = 40;
    /**
     * How far to either side of the line a gateway may sit and still be in the way.
     *
     * Only applied when a gate is picked BEFORE anything has gone wrong ({@link #blocking}), where
     * being wrong costs a pointless walk to a gate nothing needed. Once a leg has actually failed
     * the trade is the other way round - a long walk to a gateway beats not getting there - so
     * {@link #towards} does not apply it.
     */
    private static final double CORRIDOR = 11 * 6.0;
    /**
     * The same, for a gateway picked AFTER a leg has already failed.
     *
     * Wider, because by then a real detour to a gateway is worth making - but not absent, which is
     * what it was. With no corridor at all the only tests were "within forty-five tiles of us" and
     * "nearer the target than we are", and a gate forty tiles to the side of the route passes both
     * comfortably. The log has the bot walking to one such gate, failing to open it, and going back
     * again, while the wall it actually needed to cross was somewhere else entirely.
     */
    private static final double WIDE_CORRIDOR = 11 * 15.0;
    /**
     * The most of a journey's own length a gateway may sit off to the side of it. See
     * {@link #between} - a fixed corridor cannot tell a short trip from a long one, and on a short
     * one it lets in gateways that are plainly in another direction.
     */
    private static final double SIDEWAYS = 0.25;

    /**
     * How near a gateway has to be to the leg's destination to count as BEING that destination.
     *
     * Three tiles. A big gate's collision box is one tile by three, so the tile the router picked
     * can sit a tile and a half from the gob's own centre before anything has gone wrong, and the
     * router's waypoint is a tile centre rather than the gob's position.
     */
    private static final double AT_DEST = 11 * 3.0;

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
    public static Gob towards(GameUI gui, Coord2d dest, Set<Long> skip) {
        return pick(gui, dest, skip, false, false);
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
        if (!NBotConfig.on(NBotConfig.Key.useGates))
            return null;
        return pick(gui, dest, skip, true, true);
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
        if (!NBotConfig.on(NBotConfig.Key.useGates))
            return null;
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        Gob me = ((gui == null) || (gui.map == null)) ? null : gui.map.player();
        if ((here == null) || (me == null) || (from == null) || (to == null))
            return null;
        Coord2d off = here.sc.sub(me.rc);
        double len = from.dist(to);
        int steps = Math.max(1, (int) Math.ceil((len / MCache.tilesz.x) * 2));
        for (int i = 0; i <= steps; i++) {
            Coord2d at = from.add(to.sub(from).mul((double) i / steps));
            if (!Observed.gate(here.seg, at.add(off).floor(MCache.tilesz)))
                continue;
            // A remembered gate tile is not a gob. Find the one standing on it, which is the only
            // thing that can say whether it is open and the only thing that can be told to open.
            Gob best = null;
            double bestd = Double.MAX_VALUE;
            for (Gob g : loaded(gui)) {
                if ((skip != null) && skip.contains(g.id))
                    continue;
                double d = g.rc.dist(at);
                if ((d < bestd) && (d <= AT_DEST)) {
                    bestd = d;
                    best = g;
                }
            }
            if ((best != null) && !isOpen(best))
                return best;
        }
        return null;
    }

    /**
     * @param shutOnly consider only gateways that are actually shut.
     * @param strict   hold candidates to {@link #DETOUR}, and prefer the NEAREST rather than the
     *                 cheapest whole journey. Both only make sense when picking a gate before
     *                 anything has gone wrong.
     */
    private static Gob pick(GameUI gui, Coord2d dest, Set<Long> skip, boolean shutOnly,
                            boolean strict) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if (me == null || dest == null)
            return null;
        double direct = me.rc.dist(dest);
        Gob best = null;
        double bestcost = Double.MAX_VALUE;
        for (Gob g : loaded(gui)) {
            if ((skip != null) && skip.contains(g.id))
                continue;
            if (shutOnly && isOpen(g))
                continue;
            double toGate = me.rc.dist(g.rc);
            if (toGate > (strict ? NEAR : SEARCH))
                continue;
            double onwards = g.rc.dist(dest);
            /* The gateway IS where we are trying to get to.
             *
             * Not a special case so much as the commonest one, now that routing goes through gates:
             * a gate tile is passable to the router, so A* runs through the gap and the turn it
             * makes there becomes a waypoint - and the waypoint is the gate tile itself. Travel
             * then walks at it, which is a click inside a shut gate's collision box, which the
             * local pathfinder refuses; and the gate check, asked whether any gateway lies BETWEEN
             * here and there, correctly answered no about the gateway it was standing in front of.
             * Both tests below reject it: a gate at the destination is not nearer the destination
             * than the destination, and it projects onto the very end of the line rather than
             * inside it.
             *
             * That is the whole of "gates never open". The bot walks at the gap, is refused,
             * reports no headway, asks about gateways, is told there are none, and paces. */
            boolean itIsTheGate = onwards <= AT_DEST;
            /* Being AT the leg's end excuses a gateway from the two tests below, but being at its
             * START must not. Both tests are skipped because a gate at the destination projects
             * onto the very end of the line - `along` near 1, which `between` refuses - and that is
             * the case worth rescuing. A gate BEHIND us has `along` at or below 0, which `between`
             * refuses for an entirely different and perfectly good reason, and `onwards <= AT_DEST`
             * cannot tell the two apart because it measures distance and not direction.
             *
             * Inside an AIR LOCK that is the difference between working and not. The chamber is
             * four rows and the two gates stand five apart, so a leg ending mid-chamber is within
             * three tiles of BOTH of them: both take this branch, both skip `between`, and
             * nearest-first then picks the gate the bot has just come through and shut behind
             * itself. Modelled on this base's south air lock, with the bot at row 1151 and the leg
             * ending at row 1152 or 1153, the inner gate wins on 11 units against the outer gate's
             * 44 - so the bot turns round, opens the gate back into the base, and never reaches the
             * outer one. A leg ending on the outer gate tile, on the last chamber row, or anywhere
             * outside is judged correctly; it is only the mid-chamber ones that invert.
             *
             * Confirmed in the log at 13:58:06, on a leg that was nowhere near an air lock: the bot
             * at tile (1045,1149) stalled two tiles short of (1043,1149) - both INSIDE the base -
             * and the inner gate at (1042,1150) scored `onwards` 15.6u, took this branch, and was
             * opened. `between` would have thrown it out on its own (it projects to 1.57 along the
             * leg, past the end), which is why the exemption and not the tests is the fault. */
            if (itIsTheGate && ourSide(g, me.rc, dest))
                continue;
            if (!itIsTheGate) {
                /* Going through it has to actually get us closer, or it is a gate in the wrong
                 * wall - but only where "closer" means anything, and across a wall it does not.
                 *
                 * Straight-line distance to a destination on the other side of a palisade measures
                 * a line nobody can walk, so comparing it against the line out through a gateway
                 * rejects the gateway for being further away in a direction that is not available.
                 * That is how a bot standing beside a perfectly good gate, twelve tiles from it,
                 * was told that going through it would leave it further from the target than it
                 * already was, and turned round. When the direct line is blocked, being further
                 * along it is not evidence of anything. */
                if ((onwards >= direct) && !blocked(gui, me.rc, dest))
                    continue;
                if (!between(me.rc, dest, g.rc, strict ? CORRIDOR : WIDE_CORRIDOR))
                    continue;
            }
            double cost = toGate + onwards;
            /* Nearest first when the gate is being chosen up front, because gateways come in
             * SERIES: the common way to build one is an air lock, two gates a few tiles apart with
             * a chamber between. Both lie on the same line to anywhere beyond, so "cheapest whole
             * journey" scores them within a few units of each other and the far one wins as often
             * as not - and the far one is behind the near one, so walking at it means walking into
             * the near one. They have to be taken in the order they stand in. */
            if (strict)
                cost = toGate;
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
    public static boolean pass(BotNav nav, GameUI gui, Coord2d dest, long which, Set<Long> skip,
                               String log) throws InterruptedException {
        if (!NBotConfig.on(NBotConfig.Key.useGates))
            return false;
        /* A gateway the caller has already identified is USED, not re-chosen. The two ways of
         * choosing do not agree and cannot be made to: travel wants the nearest SHUT gate on the
         * line, because that is the one physically stopping it, while this scores the whole
         * journey over gates in any state. With an air lock in the way they picked different gates
         * of the same pair, and that was a loop with no exit - travel found the shut gate, this
         * walked to the open one beside it, reported honest progress, and travel found the same
         * shut gate again. Three times in two seconds, then the shift ended. */
        Gob gate = (which == 0) ? null : nav.gob(which);
        if ((gate != null) && !isGate(gate))
            gate = null;
        if (gate == null)
            gate = towards(gui, dest, skip);
        if (gate == null) {
            /* Spelled out because "nothing usable" has three quite different causes and they want
             * different things done about them: no gateway anywhere near, one that is near but
             * leads the wrong way, or - the one that looks identical from outside and is not a
             * gate problem at all - a gateway this character has walked through before and
             * remembers the tiles of, but which is too far off to have loaded as a gob yet. */
            List<Gob> near = loaded(gui);
            int remembered = 0;
            WorldAnchor here = WorldAnchor.capturePlayer(gui);
            if (here != null)
                remembered = Barriers.gatesIn(here.seg).size();
            NLog.log(log, "gate: nothing usable between here and " + fmt(dest)
                + " (" + near.size() + " loaded, " + remembered + " gate tiles remembered)");
            // Why EACH one was turned down. The count alone says a gateway was considered and
            // rejected without saying which test threw it out, and the three tests want completely
            // different things done about them.
            for (String s : rejections(gui, dest, skip))
                NLog.log(log, "    " + s);
            return false;
        }
        long id = gate.id;
        boolean wasOpen = isOpen(gate);
        NLog.log(log, "gate: using #" + id + " at " + fmt(gate.rc)
            + " (" + (wasOpen ? "open" : "shut") + ") to reach " + fmt(dest));

        Gob me = gui.map.player();
        if (me == null)
            return false;
        // Captured BEFORE approaching, because the far side is defined by which side we came from,
        // and once we are stood in the gateway that information is gone.
        Coord2d from = me.rc;

        /* An OPEN gateway needs nothing done to it, and treating it as though it did is how this
         * spent its whole budget achieving nothing: it walked to a gate that was already standing
         * open, blind-stepped five tiles past it, missed, and did the same thing three more times.
         *
         * Standing at an open gateway and still not getting anywhere means the gateway is not what
         * is in the way, so say so and let the caller get on with the real problem. Not yet at it
         * is a different answer: walking to it is progress in itself, because a route planned from
         * inside the gap goes through the gap, which is exactly what the caller re-plans for. */
        if (wasOpen) {
            if (me.rc.dist(gate.rc) <= REACH) {
                NLog.log(log, "gate: #" + id + " is open and we are already at it"
                    + " - it is not what is blocking us");
                return refuse(skip, id);
            }
            if (!nav.approach(gate, REACH)) {
                NLog.log(log, "gate: couldn't get to open #" + id);
                return refuse(skip, id);
            }
            NLog.log(log, "gate: at open #" + id + " - re-planning from the gateway");
            return true;
        }

        /* Line up square with the opening BEFORE closing on it, not after opening it.
         *
         * The lining-up step was doing its job in the wrong order. Walking at the gate first and
         * straightening up afterwards means the approach is the part that comes in at whatever
         * angle we happened to arrive from - and a shallow one meets the post, which stands proud
         * of the gap, before it reaches the gate at all. Straight on from a distance worked and
         * arriving from the side did not, which is exactly the shape of an approach that was never
         * squared. Doing it first costs the same two tiles and makes every arrival identical. */
        Coord2d ahead = square(gate, from);
        if (ahead != null)
            nav.stepTo(ahead, 11 * 1.5);

        if (!nav.approach(gate, REACH)) {
            NLog.log(log, "gate: couldn't get to #" + id);
            return refuse(skip, id);
        }

        if (!toggle(nav, gui, id, true, log)) {
            NLog.log(log, "gate: #" + id + " wouldn't open - leaving it alone");
            return refuse(skip, id);
        }

        Gob live = nav.gob(id);
        Gob use = (live == null) ? gate : live;
        /* Line up square with the opening, then walk through it: a point on the gate's own centre
         * line on OUR side, then the middle, then out the far side.
         *
         * Aiming straight at the middle is not enough and this is why. The posts that flank a
         * gateway stand PROUD of the opening - the barrier is wider than the gap it contains - so a
         * line coming in at a shallow angle meets a post before it reaches the middle, however
         * exactly the middle was aimed at. What decides whether a walk fits through a three-tile
         * gap is the angle it arrives at, and the only angle guaranteed to fit is square on.
         *
         * Three steps rather than two, then, and the first is the one that matters: it costs a
         * couple of tiles of walking and turns every approach into the same approach. */
        // Squaring up happened before the approach, so by here we are already on the gate's own
        // centre line: middle, then out. Repeating the line-up would only walk us back off it.
        Coord2d through = beyond(use, from, dest);
        nav.stepTo(use.rc, 11 * 1.5);
        boolean crossed = nav.stepTo(through, 11 * 2.5);
        Gob now = nav.player();
        boolean past = (now != null) && passed(use, from, now.rc);
        /* The numbers, not just a verdict, because "ok" for a step that arrived on our OWN side is
         * what hid the wrong-side bug for three rounds: the line read ok, the coordinate printed
         * beside it was behind the wall, and nothing said the two disagreed.
         *
         * A verdict alone cannot be trusted here either way round. `crossed` only means the aim was
         * reached within two and a half tiles of a point three tiles out, so it is satisfied from
         * half a tile past the opening, while `passed` wants a full tile clear - so `crossed`
         * without `past` covers both "never left our own side" and "through, but only just". Those
         * want opposite things done and printing one message for both would send the next round of
         * work back at `beyond` for no reason. */
        double wasAcross = sideOf(use, from);
        double nowAcross = (now == null) ? Double.NaN : sideOf(use, now.rc);
        String verdict;
        if (past)
            verdict = crossed ? " ok" : " short, but we are through";
        else if ((wasAcross * nowAcross) < 0)
            verdict = " through, but not yet a tile clear - leaving it open";
        else
            verdict = crossed ? " reached the aim BUT WE ARE STILL ON THIS SIDE" : " FAILED";
        NLog.log(log, "gate: step through to " + fmt(through) + verdict
            + String.format(" (across the wall: was %+.0fu, now %+.0fu)", wasAcross, nowAcross));

        /* Only shut what we opened. A gate the player left standing open is theirs, and a bot that
         * tidies it away has changed the base rather than passed through it.
         *
         * Judged on which SIDE of the gateway we ended up, not on whether the blind step landed
         * where it aimed. The step is a guess at where the far side is and misses often - the gate
         * is still swinging when it is issued - and gating the close on it meant a bot that walked
         * out of its own gate perfectly well left it standing open behind it, because the aim was
         * three tiles further on than it got. Which side of a line we are on is not a guess.
         *
         * That is what this says and it was not what it did: the condition was widened to
         * `crossed || past` rather than replaced, so arriving where we aimed still shut the gate on
         * its own. With a step-through aimed at our own side that is a gate shut by a bot that
         * never went through it, restoring the exact state it started from - which is why the
         * report was open, turn back, close, reopen, four times over. Leaving it OPEN instead is
         * self-healing: `onRoute` only ever returns gates that are shut, so the re-plan below walks
         * straight through the standing-open gateway.
         *
         * `crossed` survives for one case only, where it is the sole evidence available: a gate
         * whose collision box we cannot read has no axis, so `passed` is false forever and such a
         * gate would otherwise never be closed again. Neither gate resource in this install is in
         * that state - this is for an unknown future one. */
        if ((past || ((across(use) == null) && crossed))
                && NBotConfig.on(NBotConfig.Key.closeGates)) {
            if (!toggle(nav, gui, id, false, log))
                NLog.log(log, "gate: couldn't close #" + id + " behind us");
        }

        /* A missed step is still worth reporting as progress, because the thing that mattered
         * happened: the gate is open now and it was shut before. The step is a guess at where the
         * far side is - a line taken from wherever we started, which is only roughly square-on to
         * the opening - whereas a route re-planned from here is not a guess at all. */
        if (!crossed)
            NLog.log(log, "gate: #" + id + " is open now - re-planning through it");
        return true;
    }

    /**
     * Notes that this gateway was no use, and says so.
     *
     * Without it, every one of the failure paths above leaves the gate exactly as attractive as it
     * was: the caller re-plans, the same scoring picks the same gate, and it is walked to again.
     * The gate that is already open and still not letting us anywhere is the clearest case - that
     * is a statement about the gate being the wrong answer, and repeating it cannot make it right.
     * Scoped to one journey, because a gate nobody could open now may simply have had somebody
     * standing in it.
     */
    /**
     * Whether a gateway actually stands between two points, rather than merely somewhere nearer to
     * the second than the first is.
     *
     * "Nearer than we are" is a much weaker claim than it sounds, and the difference cost a bot its
     * afternoon: this character's water place is two tiles inside its own south wall, so a gateway
     * a dozen tiles along that wall - and BEYOND the water, on the far side of it - was nearer to
     * the water than the bot ever was. The bot walked to it from inside the base, opened it, and
     * stepped out through it to get to somewhere it was already on the right side of.
     *
     * So the gate is projected onto the line instead. It has to fall between the two ends of it,
     * and within {@link #CORRIDOR} of it. Both halves matter - the first throws out gateways past
     * the destination, the second gateways off along the wall - and neither is expressible as a
     * distance, which is why the ratio this replaces could not say it.
     */
    /** True if the straight line between two live points crosses something we know is solid. */
    private static boolean blocked(GameUI gui, Coord2d from, Coord2d to) {
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        Gob me = ((gui == null) || (gui.map == null)) ? null : gui.map.player();
        if ((here == null) || (me == null) || (from == null) || (to == null))
            return false;
        Coord2d off = here.sc.sub(me.rc);
        int steps = Math.max(1, (int) Math.ceil((from.dist(to) / MCache.tilesz.x) * 2));
        for (int i = 0; i <= steps; i++) {
            Coord2d at = from.add(to.sub(from).mul((double) i / steps));
            if (Observed.solid(here.seg, at.add(off).floor(MCache.tilesz)))
                return true;
        }
        return false;
    }

    /**
     * True if the leg's destination is on the SAME side of this gateway as we are.
     *
     * The question the AT_DEST branch in {@link #pick} has to ask, and asked with the same
     * predicate {@link #beyond} steps by, so the two cannot drift apart: project both ends onto the
     * wall's normal and compare signs. Same sign means the gateway is not on the way to anywhere -
     * we and the place we are going are already on one side of it.
     *
     * Distance cannot answer this. AT_DEST measures how NEAR the gateway is to the leg's end, so it
     * accepts a gateway three tiles beyond that end and a gateway three tiles behind it equally,
     * and one of those is a wall to walk through while the other is a wall to ignore.
     *
     * A destination IN the opening has no side, and that is exactly the case AT_DEST exists to
     * rescue - the router aims at gate tiles - so it must not be rejected here.
     */
    private static boolean ourSide(Gob gate, Coord2d me, Coord2d dest) {
        Coord2d n = across(gate);
        if ((n == null) || (me == null) || (dest == null))
            return false;   // no axis to judge by; leave the old behaviour alone
        double sMe = (n.x * (me.x - gate.rc.x)) + (n.y * (me.y - gate.rc.y));
        double sDest = (n.x * (dest.x - gate.rc.x)) + (n.y * (dest.y - gate.rc.y));
        if (Math.abs(sDest) < (MCache.tilesz.x / 2))
            return false;   // the destination IS the gateway
        return (sMe * sDest) > 0;
    }

    private static boolean between(Coord2d me, Coord2d dest, Coord2d gate, double corridor) {
        Coord2d v = dest.sub(me);
        double len = v.abs();
        if (len < 1.0)
            return false;
        Coord2d w = gate.sub(me);
        double along = ((w.x * v.x) + (w.y * v.y)) / (len * len);
        if ((along <= 0.0) || (along >= 1.0))
            return false;
        double offx = w.x - (v.x * along), offy = w.y - (v.y * along);
        /* Held to a share of the journey as well as to a fixed width, because "within six tiles of
         * the line" means quite different things depending on how long the line is. On a two
         * hundred tile trip six tiles to the side is on the way; on a THIRTEEN tile trip it is a
         * different direction. The bot standing inside its base, thirteen tiles from the water,
         * had its own air lock's inner gate score four tiles off the line and a quarter of the way
         * along it, and dutifully went and opened it. Nothing is lost by being strict here - a
         * gateway rejected before the leg fails is still found afterwards, by {@link #towards},
         * which is where a real detour to a gate is supposed to be decided. */
        return Math.hypot(offx, offy) <= Math.min(corridor, len * SIDEWAYS);
    }

    private static boolean refuse(Set<Long> skip, long id) {
        if (skip != null)
            skip.add(id);
        return false;
    }

    /**
     * A point on the far side of the gate, a few tiles past it and SQUARE-ON to the wall.
     *
     * The line we walked in on used to be good enough, on the reasoning that it must be roughly
     * perpendicular or we could not have got to the gate. It is not: a bot that arrives along the
     * wall, or that is nudged sideways on the way, comes in at an angle, and a step taken at that
     * angle lands in the corner post. Posts sit at both ends of a gateway and their hitbox is a
     * full tile, so there is not much room to be wrong in.
     *
     * The wall's direction is read off the gate's own collision box. Measured from this install's
     * hitboxes, a big gate's box is one tile by three - the three run ALONG the wall, spanning the
     * opening the posts bracket, and the one tile is the wall's thickness. So the short axis of the
     * box, rotated by the gob's own facing, is the way through, and the long axis is the direction
     * that would hit a post.
     *
     * Signed AWAY FROM THE SIDE WE ARE ON, because that is what "through" means. The earlier form
     * signed by the destination on the reasoning that arriving along a wall makes our own side
     * nearly perpendicular to the answer and so decides it on rounding error. That reasoning is
     * answered by refusing to guess inside half a tile of the opening rather than by asking a
     * different question: the destination's side is only the far side when the gateway lies between
     * the two, and two of the three pickers do not guarantee that.
     */
    /**
     * True if {@code now} is on the opposite side of the gateway from {@code from}.
     *
     * The honest form of "did we get through", against which the step-through's own arrival test is
     * only a proxy. Projected onto the gate's short axis - the way through - so it does not care
     * how far past we got, only that the sign changed and we are clear of the swing.
     */
    /**
     * One line per loaded gateway saying which test turned it down.
     *
     * Mirrors {@link #pick}'s conditions rather than sharing code with it, which is a duplication
     * worth having: the alternative is threading a reason out of a loop that runs on every hop of
     * every journey, and this runs once, only when the answer was already "nothing".
     */
    private static List<String> rejections(GameUI gui, Coord2d dest, Set<Long> skip) {
        List<String> out = new ArrayList<>();
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if ((me == null) || (dest == null))
            return out;
        double direct = me.rc.dist(dest);
        for (Gob g : loaded(gui)) {
            double toGate = me.rc.dist(g.rc);
            double onwards = g.rc.dist(dest);
            String why;
            if ((skip != null) && skip.contains(g.id))
                why = "given up on earlier this journey";
            else if (toGate > SEARCH)
                why = String.format("%.0ft away, past the %.0ft search radius",
                    toGate / MCache.tilesz.x, SEARCH / MCache.tilesz.x);
            else if ((onwards <= AT_DEST) && ourSide(g, me.rc, dest))
                why = String.format("near the destination but on OUR side of it"
                    + " (we are %+.0fu across the wall, the target %+.0fu) - not on the way",
                    sideOf(g, me.rc), sideOf(g, dest));
            else if (onwards <= AT_DEST)
                why = "AT the destination - should have been taken";
            else if ((onwards >= direct) && !blocked(gui, me.rc, dest))
                why = String.format("going through it leaves us %.0ft from the target,"
                    + " no better than the %.0ft we are at now, and the direct line is open",
                    onwards / MCache.tilesz.x, direct / MCache.tilesz.x);
            else if (!between(me.rc, dest, g.rc, WIDE_CORRIDOR))
                why = "off to the side of the line, or past the far end of it";
            else
                why = "usable - and something else rejected it";
            out.add("gate #" + g.id + " at " + fmt(g.rc) + " (" + (isOpen(g) ? "open" : "shut")
                + ", " + (int) (toGate / MCache.tilesz.x) + "t away): " + why);
        }
        return out;
    }

    /**
     * A point square on to the gateway, on the same side of it as {@code from}.
     *
     * Null when the gate's axis cannot be worked out, in which case the caller simply skips the
     * lining-up step and does what it did before.
     */
    private static Coord2d square(Gob gate, Coord2d from) {
        Coord2d n = across(gate);
        if ((n == null) || (from == null))
            return null;
        double side = (n.x * (from.x - gate.rc.x)) + (n.y * (from.y - gate.rc.y));
        // Half a tile, the same width `beyond` uses for the same judgement. It was 1.0 WORLD UNIT,
        // a twelfth of a tile, which never fired - so this never once declined to guess.
        if (Math.abs(side) < (MCache.tilesz.x / 2))
            return null;   // already in the opening; lining up would mean backing out of it
        return gate.rc.add(n.mul((side > 0) ? THROUGH : -THROUGH));
    }

    /**
     * Signed distance from the gateway's own centre line, measured ACROSS the wall.
     *
     * Which sign means which side is arbitrary - it follows the gob's facing - but it is consistent
     * for one gate, and that is all any caller here needs: same sign is the same side. NaN when the
     * gate has no readable axis. Every side judgement in this class goes through this, so none of
     * them can disagree about where the wall is.
     */
    private static double sideOf(Gob gate, Coord2d p) {
        Coord2d n = across(gate);
        if ((n == null) || (p == null))
            return Double.NaN;
        return (n.x * (p.x - gate.rc.x)) + (n.y * (p.y - gate.rc.y));
    }

    private static boolean passed(Gob gate, Coord2d from, Coord2d now) {
        Coord2d n = across(gate);
        if ((n == null) || (from == null) || (now == null))
            return false;
        double was = (n.x * (from.x - gate.rc.x)) + (n.y * (from.y - gate.rc.y));
        double is = (n.x * (now.x - gate.rc.x)) + (n.y * (now.y - gate.rc.y));
        // A tile clear of the gateway itself, so a character standing in the opening does not read
        // as through and get the gate shut on it.
        /* Clear of the SLAB, not clear of a whole tile.
         *
         * A big gate's collision box is 5.49u to either side of its centre line - measured from this
         * install's own hitboxes - so a character 6u past that line is out of the gateway and the
         * gate can shut behind it. Demanding a full tile (11u) asks for twice the gate's own
         * half-thickness, and the step-through routinely stops short of that: the observed pass came
         * out at 7u, which is comfortably clear of the slab and was still judged as not through, so
         * the gate was left standing open. Half a tile is the same width every other side judgement
         * in this class now uses, and it is above the slab with a little to spare. */
        return ((was * is) < 0) && (Math.abs(is) >= (MCache.tilesz.x / 2));
    }

    private static Coord2d beyond(Gob gate, Coord2d from, Coord2d dest) {
        Coord2d n = across(gate);
        if (n == null) {
            Coord2d dir = gate.rc.sub(from);
            double len = dir.abs();
            return (len < 1.0) ? gate.rc : gate.rc.add(dir.div(len).mul(THROUGH));
        }
        /* Signed AWAY from the side we are standing on, because that is what "through" means.
         *
         * This was signed by the DESTINATION, which is a different question with a different
         * answer: it asks which side the place we are going is on, and that is only the far side
         * when the gateway actually lies between the two. Two pickers routinely hand over a leg
         * whose end is on OUR side - `pick`'s AT_DEST branch takes any gate within three tiles of
         * the leg's end with no betweenness test at all, and `blocking` takes one that merely
         * projects onto a leg run along the inside of its own wall. In both cases the
         * destination's answer is our own side, so the step-through resolved to the very point
         * `square` had just squared up on, three tiles back the way we came. The gate was opened,
         * the bot turned round, and the close below shut it again behind nobody.
         *
         * Which side we are on cannot be the wrong side by construction, and it is not a guess.
         * The destination is consulted only when we are stood in the opening and so have no side
         * of our own to be opposite to - the case `square` declines to guess at. The old guard for
         * that was 1.0 world unit against an eleven-unit tile, which is a twelfth of a tile and
         * never fired; half a tile is the honest width of "in the gateway". */
        double s = (n.x * (from.x - gate.rc.x)) + (n.y * (from.y - gate.rc.y));
        double side = -s;
        if (Math.abs(s) < (MCache.tilesz.x / 2))
            side = (n.x * (dest.x - gate.rc.x)) + (n.y * (dest.y - gate.rc.y));
        if (side < 0)
            n = new Coord2d(-n.x, -n.y);
        return gate.rc.add(n.mul(THROUGH));
    }

    /**
     * A unit vector across the wall this gate stands in - the short axis of its collision box, in
     * world orientation. Null when the box isn't known, which leaves the caller its old guess.
     */
    private static Coord2d across(Gob gate) {
        HitBoxes.CollisionBoxSecondary[] boxes;
        double a;
        try {
            Resource res = gate.getres();
            if (res == null)
                return null;
            boxes = HitBoxes.collisionBoxMap.get(res.name);
            a = gate.a;
        } catch (RuntimeException e) {
            return null;
        }
        if (boxes == null)
            return null;
        for (HitBoxes.CollisionBoxSecondary box : boxes) {
            if ((box == null) || (box.coords == null) || (box.coords.length == 0))
                continue;
            double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
            double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
            for (Coord2d c : box.coords) {
                minx = Math.min(minx, c.x);
                maxx = Math.max(maxx, c.x);
                miny = Math.min(miny, c.y);
                maxy = Math.max(maxy, c.y);
            }
            // A square box says nothing about which way the wall runs, so leave it to the caller.
            if (Math.abs((maxx - minx) - (maxy - miny)) < 1.0)
                return null;
            boolean shortIsX = (maxx - minx) < (maxy - miny);
            double ux = shortIsX ? 1 : 0, uy = shortIsX ? 0 : 1;
            double cos = Math.cos(a), sin = Math.sin(a);
            return new Coord2d((ux * cos) - (uy * sin), (ux * sin) + (uy * cos));
        }
        return null;
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
            /* Wait for the ANSWER, whichever of the two answers it turns out to be: a menu to pick
             * from, or the gate simply swinging. Both are server replies to this click and one of
             * them always comes, so this returns on the round trip rather than on a clock.
             *
             * The fifth of a second it replaces was a guess at "long enough for a menu to appear",
             * and a guess in the dangerous direction. A menu that arrived a moment late was missed
             * entirely; the state then never changed, because the menu was still sitting there
             * waiting to be answered; so the attempt was judged a failure and the gate was clicked
             * AGAIN - which opens a second menu, or toggles a gate that the first click had already
             * begun to open. That is the double-tapping, and gates being left open or shut against
             * what was asked for is the same fault seen from the other end. */
            final boolean target = want;
            nav.waitUntil(() -> {
                if (Widgets.find(gui.ui.root, FlowerMenu.class) != null)
                    return true;
                Gob now = nav.gob(id);
                return (now == null) || (isOpen(now) == target);
            }, ANSWER_TICKS);
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
