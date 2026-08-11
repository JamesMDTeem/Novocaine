package haven.automated.nbots.world;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.NLog;
import haven.automated.pathfinder.Map;
import haven.automated.pathfinder.Pathfinder;

/**
 * The approach side of movement: closing on a gob through wildlife and other characters, and the
 * keep-out circles that make the pathfinder route around them.
 *
 * Holds its own {@link #lastKeepouts} state; everything else it needs it takes from the owning
 * {@link BotNav}, whose public methods are the seam every caller (tasks, {@link Walk}) goes
 * through.
 */
public class Approach {

    private final BotNav nav;
    private final String log;

    /**
     * The keep-out circles {@link #publishKeepouts} last put in force, kept after they are dropped
     * from the shared map so {@link #ringedOff} can still ask what they were.
     */
    private Map.Keepout[] lastKeepouts = new Map.Keepout[0];

    public Approach(BotNav nav, String log) {
        this.nav = nav;
        this.log = log;
    }

    // ------------------------------------------------------------------ keep-outs

    /**
     * Publishes the circles the next searches must route around: dangerous wildlife (reusing the LP
     * assistant's rings, so both features agree on what counts as dangerous and how much room it
     * needs) and, when enabled, the other characters' personal space.
     *
     * Refreshed per re-path rather than once per walk, because both kinds of obstacle move: a stale
     * ring routes us around where a bear used to be and straight through where it is now.
     */
    void publishKeepouts(Coord2d from) {
        Map.Keepout[] beasts = Hazards.keepouts(nav.gui, from);
        Map.Keepout[] people = NBotConfig.on(NBotConfig.Key.avoidOthers)
            ? Crowd.keepouts(nav.gui, from) : new Map.Keepout[0];
        Map.Keepout[] all = Crowd.merge(beasts, people);
        /* Kept past the clear below so a refusal can be checked against the circles that were in
         * force when it happened - see learnRefusal, which must not file a tile as furniture when
         * one of our own rings is what the search actually objected to. */
        lastKeepouts = (all == null) ? new Map.Keepout[0] : all;
        Map.keepout(all);
    }

    /** Drops every keep-out. Must be called on every exit path - see {@link #approach}. */
    public void clearKeepouts() {
        Map.keepout(null);
    }

    /**
     * True if one of the circles we published could itself be the reason a search gave up short of
     * {@code dest}, either by sitting on the destination or by lying across the way to it.
     *
     * A keep-out is invisible to every other record: {@link Observed} does not hold it, the router
     * does not consult it, and the tile under it stays honestly open. So a bear wandering past a
     * doorway makes the pathfinder refuse a perfectly good tile, and without this test that tile is
     * filed as blocked and stays that way long after the bear has gone - which is how open ground
     * inside a walled base ended up unreachable.
     */
    boolean ringedOff(Coord2d from, Coord2d dest) {
        for (Map.Keepout k : lastKeepouts) {
            if (k == null)
                continue;
            if (dest.dist(k.c) <= k.r)
                return true;
            Coord2d span = dest.sub(from);
            double len2 = (span.x * span.x) + (span.y * span.y);
            Coord2d rel = k.c.sub(from);
            /* Nearest point on the segment to the circle's middle; the clamp keeps that point
             * between the two ends rather than out along the infinite line. */
            double t = (len2 < 1e-6) ? 0.0
                : Math.max(0.0, Math.min(1.0, ((rel.x * span.x) + (rel.y * span.y)) / len2));
            if (from.add(span.mul(t)).dist(k.c) <= k.r)
                return true;
        }
        return false;
    }

    /**
     * True if a keep-out circle now intersects the corridor from {@code from} to {@code to}.
     *
     * Called proactively at the start of each leg in {@link BotNav#travelTo} to catch a beast or
     * character that has drifted onto the planned path between legs. The reactive check inside
     * {@link BotNav#walkStraight} catches one that steps onto the leg DURING the walk; this catches
     * one already in place when the leg begins, which is the case the user reported.
     *
     * Uses the same segment-circle intersection math as {@link #ringedOff}, checking every
     * circle currently in {@link #lastKeepouts}.
     *
     * @param from the current position
     * @param to   the leg destination (next waypoint, or the final destination on the last leg)
     * @return true if any keep-out circle intersects the segment
     */
    boolean checkPathBlocked(Coord2d from, Coord2d to) {
        if (from == null || to == null)
            return false;
        return ringedOff(from, to);
    }

    // ------------------------------------------------------------------ approach

    /**
     * Walks to within {@code reach} of a gob, re-pathing as either of us moves, routing around
     * wildlife and other characters.
     *
     * @return true if we ended up close enough to act on it.
     */
    public boolean approach(Gob gob, double reach) throws InterruptedException {
        nav.hazardBlocked = false;
        try {
            return approach0(gob, reach);
        } finally {
            // Keep-outs are a process-wide setting and the player's own clicks go through the same
            // pathfinder, so a leftover ring would silently reroute them long after the bot stopped
            // caring. Never leave one standing.
            clearKeepouts();
        }
    }

    private boolean approach0(Gob gob, double reach) throws InterruptedException {
        if (gob == null)
            return false;
        long id = gob.id;
        Coord2d aimed = null;
        double best = Double.MAX_VALUE;
        int stalled = 0;
        int retreats = 0;
        int unsticks = 0;
        // The search behind the last path issued, so arrival can be told from never having set off.
        Pathfinder walk = null;

        for (int i = 0; i < 60; i++) {
            Gob target = nav.gob(id);
            Gob me = nav.player();
            if (target == null || me == null)
                return false;

            double dist = me.rc.dist(target.rc);
            if (dist <= reach)
                return true;

            // A beast that has wandered onto the target since it was chosen. Standing there to work
            // is what the keep-out margin exists to prevent, so stop - but the caller should defer
            // rather than discard it, since beasts move on.
            Gob atTarget = Hazards.within(nav.gui, target.rc, Hazards.KEEPOUT);
            if (atTarget != null) {
                nav.hazardBlocked = true;
                nav.cancelWalk();
                return false;
            }

            // One that has closed on US. It can't be handed to the pathfinder as a no-go circle
            // while we're standing inside it - the search would have no legal first move - so step
            // out of it first and re-path on the next pass.
            Gob onUs = Hazards.within(nav.gui, me.rc, Hazards.PATH_CLEARANCE);
            if (onUs != null) {
                if (++retreats > BotNav.RETREAT_LIMIT) {
                    nav.hazardBlocked = true;
                    nav.cancelWalk();
                    return false;
                }
                retreatFrom(onUs);
                aimed = null;  // we've moved; whatever we aimed at is stale
                continue;
            }

            if (dist < best - 1.0) {
                best = dist;
                stalled = 0;
            }

            if (aimed == null || aimed.dist(target.rc) > BotNav.DRIFT) {
                if (aimed != null && ++stalled > BotNav.NO_PROGRESS_LIMIT) {
                    NLog.log(log, "giving up approach to #" + id + ": " + stalled
                        + " re-paths without closing (still " + (int) dist + "u)");
                    nav.cancelWalk();
                    return false;
                }
                publishKeepouts(me.rc);
                // clickb=1 walks without acting on arrival; what to do there is the caller's call.
                nav.gui.map.pfRightClick(target, -1, 1, 0, null);
                walk = nav.gui.map.pf;
                aimed = target.rc;
                /* Refused outright, and from here it will go on being refused: the commonest
                 * reason is that we are standing inside a collision box, which is exactly where
                 * walking up to something leaves us. Step clear and re-path rather than spending
                 * the whole attempt budget re-issuing a click nothing will act on. A gate is the
                 * case that matters - a bot wedged against one cannot approach it to open it, and
                 * cannot walk away from it either. */
                if ((walk != null) && (walk.refusal == Pathfinder.Refusal.STUCK)
                    && (++unsticks <= BotNav.UNSTICK_LIMIT)) {
                    NLog.log(log, "cannot path to #" + id + " (" + walk.why()
                        + ") - stepping clear and trying again");
                    Walk.unstick(nav, nav.gui, target.rc);
                    aimed = null;
                    continue;
                }
            }

            // Let a freshly-issued path get going before judging whether we're still walking -
            // otherwise the gap between the click and the pathfinder thread starting reads as
            // "stopped, so we must have arrived" and the loop spins at poll speed.
            nav.pause(10);

            // Then wait a slice rather than the whole path, so a target that moves is noticed while
            // we're still walking. Ends early on arrival or when the walk is over.
            nav.waitUntil(() -> {
                Gob g = nav.gob(id);
                Gob p = nav.player();
                if (g == null || p == null)
                    return true;
                if (p.rc.dist(g.rc) <= reach)
                    return true;
                return !nav.walking();
            }, 12);

            /* The walk finished and the target hasn't moved: as close as pathing will get us, so
             * count it as arrival even though we are further out than `reach`. pfRightClick paths
             * to the edge of the gob's HITBOX, and a big tree's trunk is wider than two tiles, so
             * a strict distance test would leave a bot circling every large thing it approached.
             *
             * BUT ONLY WITHIN THE TARGET'S OWN BULK. Without that bound this said "arrived"
             * wherever the walk happened to stop, which is a completely different claim - the walk
             * stops when the pathfinder can find no way, and the commonest reason for that is
             * water. So a bot stood on a river bank was told it had arrived, and then did the
             * thing it does on arrival: right-clicked a gob sixty tiles away, which the SERVER
             * obligingly walked it to, straight through the river. Every "it swam across for an
             * apple" report is this line. The log has the tamer version of the same fault too -
             * filling from a barrel forty units away with a reach of twenty-two.
             *
             * AND ONLY IF A WALK ACTUALLY HAPPENED, which is the exact form of that same bound. A
             * search that finds no way returns an empty path, so not one move is issued and `mc`
             * is never set: the character has not walked as far as it can, it has not moved. The
             * distance test can only approximate that, and approximates it worst where it matters
             * - across a narrow river, where the far bank is genuinely near. */
            Gob now = nav.gob(id);
            Gob here = nav.player();
            if (!nav.walking() && now != null && here != null && aimed != null
                && walk != null && walk.mc != null
                && aimed.dist(now.rc) <= BotNav.DRIFT
                && here.rc.dist(now.rc) <= (reach + BotNav.bulk(now) + BotNav.STOP_SLACK))
                return true;
        }
        Gob me = nav.player(), target = nav.gob(id);
        if (me != null && target != null && me.rc.dist(target.rc) <= reach)
            return true;
        nav.cancelWalk();
        return false;
    }

    /**
     * Walks directly away from a beast until we're outside the ring the pathfinder has to treat as
     * a no-go area. Uses pfLeftClick rather than a raw move so the retreat still goes around trees
     * and water, but with no keep-out published - by definition we are inside the one that matters.
     */
    private void retreatFrom(Gob beast) throws InterruptedException {
        Gob me = nav.player();
        if (me == null || beast == null)
            return;
        Coord2d away = me.rc.sub(beast.rc);
        double d = away.abs();
        // Dead-centre on the beast has no "away" direction; any heading beats standing still.
        away = (d < 1.0) ? new Coord2d(1, 0) : away.div(d);
        Coord2d dest = beast.rc.add(away.mul(Hazards.PATH_CLEARANCE + BotNav.RETREAT_MARGIN));

        clearKeepouts();
        nav.gui.map.pfLeftClick(dest.floor(), null);
        nav.waitUntil(() -> {
            Gob p = nav.player();
            Gob b = nav.gob(beast.id);
            if (p == null || b == null)
                return true;
            return p.rc.dist(b.rc) > Hazards.PATH_CLEARANCE + BotNav.RETREAT_MARGIN;
        }, 200);
        nav.cancelWalk();
    }
}
