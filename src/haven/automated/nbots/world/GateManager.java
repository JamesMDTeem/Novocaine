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
import haven.automated.pathfinder.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static haven.OCache.posres;

/**
 * Consolidated gate management for bot navigation.
 *
 * Gate handling in the Haven client is complex: gates report their open/closed state through a
 * {@link ResDrawable} byte, different gates respond to clicks differently (some show a flower
 * menu, some toggle directly), and getting through requires squaring up to the opening before
 * stepping through. All of this logic lived in {@code Gates} alongside a lot of coordinate
 * geometry that is hard to follow.
 *
 * This class owns the gate lifecycle: discovery, state tracking, and the pass-through action.
 * It provides a {@link GateInfo} class that ties a gob to its tile position and current state,
 * making it easier for callers (Router, BotNav) to reason about gates without juggling raw gobs.
 *
 * {@link Gates} remains as a thin compatibility layer for existing callers; new code should use
 * this class directly.
 */
public class GateManager {

    /** Pixels per map tile, from the {@link World} seam. Every distance in this class is stated in tiles. */
    private static final double TILE = World.TILE;
    /** Close enough for a right-click on the gate to land. */
    private static final double REACH = TILE * 3.5;
    /** How far from the failed leg a gateway is still worth walking to. */
    private static final double SEARCH = TILE * 45.0;
    /** How far past the gateway to step before calling it "through". */
    private static final double THROUGH = TILE * 3.0;
    /** How near a shut gateway has to be before it is worth stopping the walk for. */
    private static final double NEAR = TILE * 8.0;
    /** Polls (of 25ms) to wait for a gate to finish swinging. */
    private static final int SWING_TICKS = 80;
    /** Polls to wait for the server to answer a right-click on a gate at all. */
    private static final int ANSWER_TICKS = 40;
    /** How far to either side of the line a gateway may sit and still be in the way. */
    private static final double CORRIDOR = TILE * 6.0;
    /** Wider corridor for after a leg has already failed. */
    private static final double WIDE_CORRIDOR = TILE * 15.0;
    /** How far past the gateway to step, as a fraction of journey length. */
    private static final double SIDEWAYS = 0.25;
    /** How near a gateway has to be to the leg's destination to count as BEING that destination. */
    private static final double AT_DEST = TILE * 3.0;

    /**
     * Information about a single gate: its gob, its tile position, and whether it is open.
     *
     * Immutable holder for gate state used by {@link Router.World} and {@link BotNav}.
     */
    public static final class GateInfo {
        private final Gob gob;
        private final Coord tile;
        private final boolean open;
        private final long lastSeen;

        public GateInfo(Gob gob, Coord tile, boolean open, long lastSeen) {
            this.gob = gob;
            this.tile = tile;
            this.open = open;
            this.lastSeen = lastSeen;
        }

        /** ID of the gate gob. */
        public long id() { return gob.id; }
        public Gob gob() { return gob; }
        public Coord tile() { return tile; }
        public boolean isOpen() { return open; }
        public long lastSeen() { return lastSeen; }
    }

    // ---------------------------------------------------------------- reading a gate

    /**
     * True if {@code g} is a gate gob.
     */
    public static boolean isGate(Gob g) {
        try {
            Resource res = (g == null) ? null : g.getres();
            return (res != null) && (Barriers.kind(res.name) == Barriers.Kind.GATE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True if this gate stands open.
     *
     * Unknown answers OPEN, which is the safe direction: the cost of believing a shut gate is
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

    /**
     * Every gate gob currently loaded.
     */
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
     * Discover gate gobs on a given segment, returning their current state.
     *
     * This is what {@link Router.World} calls to build its passable-gates set, and what
     * {@link BotNav} calls to know which gates exist in the current segment.
     *
     * @param gui    the game UI (may be null)
     * @param seg    the segment to search
     * @return       list of gate info, empty if no gui or segment mismatch
     */
    public static List<GateInfo> findGatesOnSegment(GameUI gui, long seg) {
        List<GateInfo> out = new ArrayList<>();
        if (gui == null || gui.map == null)
            return out;
        Gob me = gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if (me == null || here == null)
            return out;
        Coord2d off = here.sc.sub(me.rc);
        for (Gob g : loaded(gui)) {
            WorldAnchor gAnchor = WorldAnchor.capture(gui, g.rc);
            if (gAnchor == null || gAnchor.seg != seg)
                continue;
            Coord tile = g.rc.add(off).floor(MCache.tilesz);
            out.add(new GateInfo(g, tile, isOpen(g), System.nanoTime()));
        }
        return out;
    }

    /**
     * Tiles occupied by loaded gate gobs, in segment-relative tile space.
     *
     * Used by {@link Router.World} to treat gate tiles as passable even when the Observed record
     * is stale (e.g. recorded before the gate was placed).
     */
    public static Set<Coord> getGateTiles(GameUI gui) {
        Set<Coord> out = new HashSet<>();
        if ((gui == null) || (gui.map == null))
            return out;
        Gob me = gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if ((me == null) || (here == null))
            return out;
        Coord2d off = here.sc.sub(me.rc);
        for (Gob g : loaded(gui))
            out.add(g.rc.add(off).floor(MCache.tilesz));
        return out;
    }

    // ---------------------------------------------------------------- picking a gate

    /**
     * The gateway most worth walking to on the way to {@code dest}, or null if none is.
     */
    public static Gob towards(GameUI gui, Coord2d dest, Set<Long> skip) {
        /* SHUT ones only, which is the whole of what a gateway problem is.
         *
         * This used to consider open gateways too, and that was the single worst behaviour in the
         * navigation stack. An open gateway is not an obstacle: {@link Router.World#passable} lets
         * the search walk straight through it, so a route that needs one already has one. Picking it
         * anyway meant the bot ABANDONED its journey to walk to a doorway - the log is full of
         * "using #X (open) to reach <somewhere else>" followed by "at open #X - re-planning from the
         * gateway", which is the round trip achieving precisely nothing and then starting over.
         * Journeys to a water place died this way, leaving the bot standing at a fence running the
         * fill-from-barrel logic where there was no barrel.
         *
         * A shut gateway is a real obstacle and still handled: that is what this returns now. */
        return pick(gui, dest, skip, true, false);
    }

    /**
     * The SHUT gateway standing between us and {@code dest}, or null if nothing is.
     */
    public static Gob blocking(GameUI gui, Coord2d dest, Set<Long> skip) {
        if (!NBotConfig.on(NBotConfig.Key.useGates))
            return null;
        return pick(gui, dest, skip, true, true);
    }

    /**
     * The shut gateway this leg is ROUTED THROUGH, or null if it isn't routed through one.
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
     * @param strict   hold candidates to {@link #NEAR}, prefer nearest rather than cheapest.
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
            if (locked(g.id))
                continue;
            double toGate = me.rc.dist(g.rc);
            if (toGate > (strict ? NEAR : SEARCH))
                continue;
            /* An OPEN gateway we are already standing at is not an obstacle, so it must never be
             * picked. Its tiles are passable to the router already, and `pass` is hard-coded to
             * refuse exactly this case ("is open and we are already at it"), so a pick here always
             * wastes a replan - and with MAX_REPLANS=3 in BotNav, a couple of those plus one real
             * failure make the whole journey report "couldn't walk to <dest>". The observed run
             * had the bot standing on an open gate and opening EVERY journey with "using #<that
             * gate>" - it scored minimum cost (toGate=0) so it won for every destination. A SHUT
             * gate at our feet is the one legitimate exception: that really is the thing blocking
             * us, and arriving at it is exactly when `pass` is meant to open it. */
            if (isOpen(g) && (toGate <= REACH))
                continue;
            if (wallBetween(gui, me.rc, g.rc))
                continue;
            double onwards = g.rc.dist(dest);
            boolean itIsTheGate = onwards <= AT_DEST;
            if (itIsTheGate && ourSide(g, me.rc, dest))
                continue;
            if (!itIsTheGate) {
                if ((onwards >= direct) && !blocked(gui, me.rc, dest))
                    continue;
                if (!between(me.rc, dest, g.rc, strict ? CORRIDOR : WIDE_CORRIDOR))
                    continue;
            }
            double cost = toGate + onwards;
            if (strict)
                cost = toGate;
            if (cost < bestcost) {
                bestcost = cost;
                best = g;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- using a gate

    /**
     * Walks through the gateway between us and {@code dest}, opening it if it is shut.
     *
     * @return boolean (true = gate passed, false = no gate usable).
     */
    public static boolean pass(BotNav nav, GameUI gui, Coord2d dest, long which, Set<Long> skip,
                               String log) throws InterruptedException {
        if (!NBotConfig.on(NBotConfig.Key.useGates))
            return false;
        Gob gate = (which == 0) ? null : nav.gob(which);
        if ((gate != null) && !isGate(gate))
            gate = null;
        if (gate == null)
            gate = towards(gui, dest, skip);
        if (gate == null) {
            List<Gob> near = loaded(gui);
            int remembered = 0;
            WorldAnchor here = WorldAnchor.capturePlayer(gui);
            if (here != null)
                remembered = Observed.gatesIn(here.seg).size();
            NLog.log(log, "gate: nothing usable between here and " + fmt(dest)
                + " (" + near.size() + " loaded, " + remembered + " gate tiles remembered)");
            for (String s : rejections(gui, dest, skip, true))
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
        Coord2d from = me.rc;

        if (wasOpen) {
            /* Nothing to do, wherever it is. An open gateway is a doorway the route already walks
             * through on its own, so there is no such thing as "using" one - and walking to it to
             * re-plan from there is a detour that achieves nothing but arriving somewhere we were
             * not going. It was the commonest line in the log and the reason journeys to a place
             * ended at whatever gate happened to lie off to one side of them.
             *
             * A gate that was shut when it was chosen and has been opened since (by a crewmate, or
             * by us on a previous pass) lands here too, and the answer is the same: the obstacle is
             * gone, so carry on with the ordinary route rather than treating it as an event. */
            NLog.log(log, "gate: #" + id + " is open - not an obstacle, carrying on");
            return refuse(skip, id);
        }

        Coord2d ahead = square(gate, from);
        if (ahead != null)
            nav.stepTo(ahead, TILE * 1.5);

        if (!nav.approach(gate, REACH)) {
            NLog.log(log, "gate: couldn't get to #" + id);
            return refuse(skip, id);
        }

        if (!toggle(nav, gui, id, true, log)) {
            lock(id);
            NLog.log(log, "gate: #" + id + " wouldn't open - locked, most likely;"
                + " leaving it alone for the rest of the session");
            return refuse(skip, id);
        }

        Gob live = nav.gob(id);
        Gob use = (live == null) ? gate : live;
        Coord2d through = beyond(use, from, dest);
        /* Do NOT click the gateway's own centre on the way through, which is what stood here.
         * It reads as the obvious intermediate hop and it is the one coordinate that cannot
         * work: the gob's collision box is centred on it, so pfLeftClick throws the click away
         * before a search starts, and EVERY crossing in the log paid a "pathfinder refused
         * <the gate>" for it. Three rounds were spent trying to nudge the aim off that tile
         * instead - hopeless by construction, since a gateway is the only gap in a wall, so
         * both the backward and the sideways search find nothing but more wall.
         *
         * Standing square-on the near side is what the hop was actually for, and the step
         * beyond walks through the opening on its own. Re-squared from where we are NOW rather
         * than from {@code from}: approaching the gate has moved us since. */
        Gob at = nav.player();
        Coord2d nearside = square(use, (at == null) ? from : at.rc);
        if (nearside != null)
            nav.stepTo(nearside, TILE * 1.5);
        boolean crossed = nav.stepTo(through, TILE * 2.5);
        Gob now = nav.player();
        boolean past = (now != null) && passed(use, from, now.rc);
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

        if ((past || ((across(use) == null) && crossed))
                && NBotConfig.on(NBotConfig.Key.closeGates)) {
            if (!toggle(nav, gui, id, false, log))
                NLog.log(log, "gate: couldn't close #" + id + " behind us");
        }

        if (!crossed)
            NLog.log(log, "gate: #" + id + " is open now - re-planning through it");
        return true;
    }

    // ---------------------------------------------------------------- geometry helpers

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

    private static boolean ourSide(Gob gate, Coord2d me, Coord2d dest) {
        Coord2d n = across(gate);
        if ((n == null) || (me == null) || (dest == null))
            return false;
        double sMe = (n.x * (me.x - gate.rc.x)) + (n.y * (me.y - gate.rc.y));
        double sDest = (n.x * (dest.x - gate.rc.x)) + (n.y * (dest.y - gate.rc.y));
        if (Math.abs(sDest) < (MCache.tilesz.x / 2))
            return false;
        return (sMe * sDest) > 0;
    }

    /**
     * Whether a gateway lies usefully between us and where we are going.
     *
     * Two bounds here used to scale with the LENGTH OF THE LEG, which made gateways least available
     * exactly when they matter most - when the destination is close and on the other side of your
     * own wall. On an eight tile line the sideways allowance came to about two tiles, so a gate
     * eight tiles along the same wall was dismissed as "off to the side of the line"; the log has
     * that verdict on gates the bot then spent two failed legs walking at the wall beside. Behind a
     * wall, "on the way" cannot mean "nearly on the straight line", because the whole point of a
     * gateway is that the straight line does not work.
     *
     * So both bounds get a floor that does not shrink. Sideways keeps the proportional rule for long
     * journeys, where a gate a third of the way off really is a different route, but never tightens
     * below {@link #NEAR}. And a gate slightly PAST the destination stays eligible when a wall lies
     * between us and that destination - going through it and doubling back is what a person does at
     * a walled compound, and refusing on {@code along >= 1} alone rejected it out of hand.
     */
    private static boolean between(Coord2d me, Coord2d dest, Coord2d gate, double corridor) {
        Coord2d v = dest.sub(me);
        double len = v.abs();
        if (len < 1.0)
            return false;
        Coord2d w = gate.sub(me);
        double along = ((w.x * v.x) + (w.y * v.y)) / (len * len);
        if (along <= 0.0)
            return false;
        // Past the far end is allowed only as far as one gate's approach beyond it, so this stays a
        // gateway ON the way rather than any gateway in the general direction.
        double past = 1.0 + (NEAR / len);
        if (along >= past)
            return false;
        double offx = w.x - (v.x * along), offy = w.y - (v.y * along);
        return Math.hypot(offx, offy) <= Math.min(corridor, Math.max(NEAR, len * SIDEWAYS));
    }

    private static boolean refuse(Set<Long> skip, long id) {
        if (skip != null)
            skip.add(id);
        return false;
    }

    private static boolean wallBetween(GameUI gui, Coord2d from, Coord2d to) {
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        Gob me = ((gui == null) || (gui.map == null)) ? null : gui.map.player();
        if ((here == null) || (me == null) || (from == null) || (to == null))
            return false;
        Coord2d off = here.sc.sub(me.rc);
        int steps = Math.max(1, (int) Math.ceil((from.dist(to) / MCache.tilesz.x) * 2));
        for (int i = 1; i < steps; i++) {
            Coord2d at = from.add(to.sub(from).mul((double) i / steps));
            if ((Observed.at(here.seg, at.add(off).floor(MCache.tilesz)) == Observed.WALL)
                    && !BotNav.occupied(gui, at))
                return true;
        }
        return false;
    }

    private static Coord2d square(Gob gate, Coord2d from) {
        Coord2d n = across(gate);
        if ((n == null) || (from == null))
            return null;
        double side = (n.x * (from.x - gate.rc.x)) + (n.y * (from.y - gate.rc.y));
        if (Math.abs(side) < (MCache.tilesz.x / 2))
            return null;
        return gate.rc.add(n.mul((side > 0) ? THROUGH : -THROUGH));
    }

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
        return ((was * is) < 0) && (Math.abs(is) >= (MCache.tilesz.x / 2));
    }

    private static Coord2d beyond(Gob gate, Coord2d from, Coord2d dest) {
        Coord2d n = across(gate);
        if (n == null) {
            Coord2d dir = gate.rc.sub(from);
            double len = dir.abs();
            return (len < 1.0) ? gate.rc : gate.rc.add(dir.div(len).mul(THROUGH));
        }
        double s = (n.x * (from.x - gate.rc.x)) + (n.y * (from.y - gate.rc.y));
        double side = -s;
        if (Math.abs(s) < (MCache.tilesz.x / 2))
            side = (n.x * (dest.x - gate.rc.x)) + (n.y * (dest.y - gate.rc.y));
        if (side < 0)
            n = new Coord2d(-n.x, -n.y);
        return gate.rc.add(n.mul(THROUGH));
    }

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
            if (Math.abs((maxx - minx) - (maxy - miny)) < 1.0)
                return null;
            boolean shortIsX = (maxx - minx) < (maxy - miny);
            double ux = shortIsX ? 1 : 0, uy = shortIsX ? 0 : 1;
            double cos = Math.cos(a), sin = Math.sin(a);
            return new Coord2d((ux * cos) - (uy * sin), (ux * sin) + (uy * cos));
        }
        return null;
    }

    // ---------------------------------------------------------------- toggle / lock state

    /**
     * Right-clicks the gate and waits for it to reach the wanted state.
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

    /** Gateways that would not open, remembered for the rest of the session. */
    private static final Set<Long> lockedGates =
        Collections.synchronizedSet(new HashSet<>());

    private static void lock(long id) {
        lockedGates.add(id);
    }

    private static boolean locked(long id) {
        return lockedGates.contains(id);
    }

    /**
     * Why each loaded gateway was not the one, in the same order {@link #pick} asks the questions.
     *
     * The order is the whole point, and getting it wrong made this dump a source of WRONG diagnoses
     * rather than a cure for them. It used to leave out the shut-only filter entirely, so every OPEN
     * gateway fell through to whichever geometric test happened to catch it - and a dump reading
     * "fourteen rejected, off to the side of the line", nine of them open, sent a reading of these
     * logs off after the corridor geometry when the corridor had never been consulted. A gateway
     * standing open is not rejected by geometry; it is not a candidate at all, because there is
     * nothing to open and nothing blocking us.
     *
     * @param shutOnly what the caller passed to {@link #pick}, so the two agree.
     */
    private static List<String> rejections(GameUI gui, Coord2d dest, Set<Long> skip,
                                           boolean shutOnly) {
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
            else if (shutOnly && isOpen(g))
                why = "already open - it is a gap, not an obstacle, so there is nothing to pass";
            else if (locked(g.id))
                why = "would not open earlier this session";
            else if (toGate > SEARCH)
                why = String.format("%.0ft away, past the %.0ft search radius",
                    toGate / MCache.tilesz.x, SEARCH / MCache.tilesz.x);
            else if (isOpen(g) && (toGate <= REACH))
                why = "open and we are already standing at it - not what is blocking us";
            else if (wallBetween(gui, me.rc, g.rc))
                why = "there is a wall between us and it - we cannot even get to it";
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
