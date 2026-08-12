package haven.automated.helpers;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Utils;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.world.WorldAnchor;

/**
 * Fires the hearth-fire travel action, bounded per run and measured, so that any bot can offer it
 * as a travel option without gambling the character's weariness budget.
 *
 * The action is {@code paginae/act/travel-hearth}. Weariness is a flat 0.1 per travel against a
 * cap (observed live at 22.7/57.0, i.e. around 343 travels), and it is NOT readable from the
 * client - the travel window is a server-shipped resource widget whose code layer this tree does
 * not have, and none of the 51 {@code Glob.CAttr}s carried it when they were all dumped. So this
 * helper never tries to read it. Instead it bounds hearth travels per run ({@link #BUDGET}, ten is
 * under 2% of the cap) and logs every use.
 *
 * The other missing piece is the channel duration: how long a hearth travel actually takes, start
 * to arrival. The emergency hearth-home paths in the legacy bots and {@code Upkeep} used to fire
 * the action and sleep a flat 8 seconds blind. {@link #travel} replaces that with a measured wait,
 * and the passive watcher ({@link #noteAct}, fed by {@code GameUI.wdgmsg}) catches MANUAL hearth
 * travels too - the player clicks the paginae button, the watcher sees the {@code act} leave, then
 * sees the character's position jump a whole map and logs the elapsed time, the landing anchor and
 * the jump distance. That is the channel-duration data the travel-vs-walk decision needs, without
 * anyone having to arrange a bot at low health to collect it.
 *
 * Emergency callers must never be blocked by the budget: {@link #travel} fires unconditionally and
 * just records the use. The budget is the guard for OPTIONAL travel (the future "teleport when it
 * beats walking" decision), which checks {@link #canTravel} before calling.
 */
public class HearthTravel {
    /** Hearth travels permitted per run, on top of the unreadable weariness cap. */
    public static final int BUDGET = 10;

    /** How long to watch the player's position for the teleport to land, in milliseconds. */
    private static final long ARRIVAL_TIMEOUT_MS = 12_000L;

    /** A move of more than one tile counts as the teleport landing. */
    private static final double MOVE_TILE = 1.0;

    /** Settle time after the position first moves, so the recorded rc is the final one. */
    private static final long SETTLE_MS = 500L;

    /** A position change this large within one watch sample is a teleport, not walking. */
    private static final double JUMP_TILES = 3.0;

    /** How often the watcher samples the player's position, in milliseconds. */
    private static final long WATCH_PERIOD_MS = 200L;

    /** An act this long ago can no longer be the start of the jump just seen. */
    private static final long ACT_WINDOW_MS = 60_000L;

    /** How long sustained movement is accumulated before a speed figure is logged. */
    private static final long SPEED_WINDOW_MS = 10_000L;

    /** Only a move slower than this counts as sustained movement (faster = teleport/jump). */
    private static final double SPEED_MAX_TILES_PER_S = 6.0;

    /**
     * The character's travel speed at gear 3, in units per second: the in-game speedometer under
     * the character reads roughly 49-50 u/s (user-supplied, 2026-08-11). Tiles are 11 units, so
     * that is ~4.5 tiles/s - comfortably under the watcher's 6-tile cap and far under the 3-tile
     * per 200ms sample that reads as a teleport jump.
     */
    private static final double WALK_U_PER_S = 49.5;

    /**
     * Measured hearth-travel channel duration, in milliseconds: two manual travels logged
     * 4800ms and 4814ms (2026-08-11), so the channel is ~4.8s.
     */
    private static final long CHANNEL_MS = 4800L;

    /**
     * A floor on top of the channel time, in seconds, so the travel-vs-walk decision never fires
     * for a short trip: the teleport also costs 0.1 weariness and strands the character at the
     * hearth, so it should only win by a clear margin.
     */
    private static final double FLOOR_S = 5.0;

    private static final String LOG = "hearth.log";

    /**
     * Preference key for the hearth's location. The hearth does not move, so the last landing seen
     * is remembered across sessions: an optional hearth travel on a fresh client launch must be
     * able to say "yes, hearth travel is faster" without having watched a landing yet, or it never
     * fires on the very trips it exists for.
     *
     * The hearth is a PER-CHARACTER thing — every character places its own hearthfire — so the
     * stored key carries the character id, the same per-character convention Hurricane's own
     * {@code mapfile/<chrid>} pref (and {@code LpLog}'s {@code lp/<chrid>.json}) uses. The pref
     * store itself ({@code Hurricane-prefs.xml}) is per-install; the character id makes it per
     * character. Stored as the flat {@link WorldAnchor#store()} string, matching the contract
     * {@code WorldAnchor} documents for remembered anchors.
     */
    private static final String PREF_HEARTH = "nova.hearth-anchor";

    /** The character id the cached anchor belongs to, or null when the plain key is in use. */
    private static volatile String hearthFor = null;

    private static int used = 0;

    /** When the last hearth-travel act left the client (manual or bot), or -1. */
    private static volatile long travelActAt = -1;

    /** True while {@link #travel} is doing its own measured wait, so the watcher stays quiet. */
    private static volatile boolean botInFlight = false;

    /**
     * Where the hearth is, from the last landing seen (bot travel or manual). Null until one is
     * known this session; the preference store fills it on the first query of a fresh launch.
     */
    private static volatile WorldAnchor hearthAnchor = null;

    private static volatile Thread watcher = null;

    /**
     * The most recent GameUI a {@code wdgmsg} act was seen on. The watcher re-resolves this each
     * cycle rather than keeping the GameUI it started with: a character relog builds a NEW GameUI
     * (new chrid), and the watcher lives for the whole client session, so a manual hearth travel
     * on the second character must be recorded under THAT character's key.
     */
    private static volatile GameUI currentGui = null;

    private HearthTravel() {}

    /** How many hearth travels remain in the per-run budget. */
    public static synchronized int remaining() {
        return Math.max(0, BUDGET - used);
    }

    /** Whether an OPTIONAL hearth travel is still within the per-run budget. */
    public static synchronized boolean canTravel() {
        return used < BUDGET;
    }

    /**
     * The preference key the hearth anchor is stored under, per character. The store is
     * per-install ({@code Hurricane-prefs.xml}); the character id makes it per character, the same
     * way {@code mapfile/<chrid>} and {@code LpLog} do it. A character id unknown (not yet on the
     * client) falls back to the plain key, which is exactly the pre-character-key state.
     */
    private static String prefKey(GameUI gui) {
        if (gui != null && gui.chrid != null && !gui.chrid.isEmpty())
            return PREF_HEARTH + "/" + gui.chrid;
        return PREF_HEARTH;
    }

    /**
     * The known hearth location for the current character, from this session's last landing or
     * the preference store.
     *
     * The preference-store read happens once per character per session, because the hearth does
     * not move; {@link #remember} rewrites it whenever a landing is actually seen, keeping the
     * store and the session state in step. Relogging as another character reads that character's
     * own anchor, since every character places its own hearthfire.
     */
    public static synchronized WorldAnchor hearth(GameUI gui) {
        String key = prefKey(gui);
        if (hearthFor == null || !hearthFor.equals(key)) {
            hearthAnchor = WorldAnchor.parse(Utils.getpref(key, null));
            hearthFor = key;
        }
        return hearthAnchor;
    }

    /** Records a landing as the current character's hearth, in memory and in the preference store. */
    private static synchronized void remember(GameUI gui, WorldAnchor landing) {
        if (landing == null)
            return;
        String key = prefKey(gui);
        hearthFor = key;
        hearthAnchor = landing;
        Utils.setpref(key, landing.store());
    }

    /**
     * Whether hearth travel beats walking to a destination that is {@code walkingDist} units
     * away, per the measured numbers.
     *
     * Walking that distance takes {@code walkingDist / WALK_U_PER_S} seconds; hearth travel takes
     * the {@code CHANNEL_MS} channel plus the {@link #FLOOR_S} margin (so a short trip, or one
     * where the two are close, stays on foot - the teleport costs 0.1 weariness and strands the
     * character at the hearth). Feed it {@code Router.walkingDistance} for the distance.
     *
     * @param walkingDist the on-foot distance in world units, or -1 when it cannot be told
     */
    public static boolean beatsWalking(double walkingDist) {
        if (walkingDist < 0)
            return false;
        double walkS = walkingDist / WALK_U_PER_S;
        return walkS > (CHANNEL_MS / 1000.0) + FLOOR_S;
    }

    /**
     * Whether hearth travel beats walking to a live target, folded in beside
     * {@code Router.walkingDistance} so a caller gets the whole decision in one call.
     *
     * This is the OPTIONAL-travel decision: it respects the per-run budget ({@link #canTravel}),
     * the {@link #FLOOR_S} margin, and asks the router for the on-foot distance rather than
     * guessing from the straight line. It never fires the action - callers travel only after this
     * says yes, and the emergency hearth-home paths call {@link #travel} directly, past any budget.
     *
     * The comparison counts the whole cost of hearth travel, not just the channel: teleporting
     * strands the character at the hearth, so whatever is left between the hearth and the target
     * has to be walked there and back. The three-way comparison is therefore
     * walk-here-to-target versus channel plus walk-hearth-to-target plus the margin. A target that
     * is not on the hearth's side of the map (no hearth landing seen yet, or the hearth anchor is
     * on another segment) makes the teleport's cost unknowable, and the answer is conservatively
     * false - walk.
     *
     * @return true when hearth travel wins AND the budget allows it; false otherwise
     */
    public static boolean betterThanWalking(GameUI gui, haven.Coord2d target, int margin) {
        if (!canTravel())
            return false;
        double walkingDist = haven.automated.nbots.world.Router.walkingDistance(gui, target, margin);
        if (walkingDist < 0) {
            /* The router cannot tell - the destination's grid is not in the live cache, or it is
             * on another segment. Its own contract says callers fall back to the straight line,
             * which is what it would have returned had the ground been flat and empty. This is
             * not a corner case: a destination far enough to be worth a hearth is far enough that
             * its grid has not been streamed in, so without the fallback the optional-travel
             * decision would die exactly on the trips it exists for. */
            haven.Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
            if (me == null)
                return false;
            walkingDist = me.rc.dist(target);
        }
        WorldAnchor home = hearth(gui);
        if (home == null)
            return false;
        haven.Coord2d hearth = home.resolve(gui);
        if (hearth == null)
            return false;
        double walkingS = walkingDist / WALK_U_PER_S;
        double hearthS = (CHANNEL_MS / 1000.0) + (hearth.dist(target) / WALK_U_PER_S) + FLOOR_S;
        return walkingS > hearthS;
    }

    /** Resets the per-run budget; call at the start of a bot's run. */
    public static synchronized void resetRun() {
        used = 0;
    }

    /**
     * Whether hearth travel home beats walking home from where the character is now.
     *
     * The end-of-shift question: the shift is over, the character is wherever the last target
     * left it, and home is the hearth. Landing is the destination, so there is no onward walk to
     * price - the comparison is just the on-foot walk back against the channel plus the floor.
     * Unknown hearth, or no route, answers false (walk home, as before).
     *
     * @param gui  the client
     * @param margin standable-around margin for the router, in tiles' worth of units
     */
    public static boolean homeBeatsWalking(GameUI gui, int margin) {
        if (!canTravel())
            return false;
        WorldAnchor home = hearth(gui);
        if (home == null)
            return false;
        Coord2d hearth = home.resolve(gui);
        if (hearth == null)
            return false;
        double walk = haven.automated.nbots.world.Router.walkingDistance(gui, hearth, margin);
        return beatsWalking(walk);
    }

    /**
     * Hooks every {@code act} message the client sends, so a MANUAL hearth travel (the player
     * clicking the paginae button) is measured too. {@code GameUI.wdgmsg} calls this for every act.
     *
     * The bot path ({@code gui.act("travel", "hearth")}) arrives as {@code ["travel","hearth"]};
     * the paginae button arrives as {@code ["travel-hearth", mods, ...]}. Either is the start of a
     * channel whose length the watcher then measures against the next position jump.
     *
     * The watcher starts on ANY act, hearth or not: the sustained-movement lines it logs are the
     * walking-speed figure the travel-vs-walk decision needs, and a session that never hearths
     * should still produce them.
     */
    public static void noteAct(GameUI gui, String msg, Object... args) {
        if ((args == null) || (args.length == 0) || !(args[0] instanceof String))
            return;
        currentGui = gui;
        startWatcher(gui);
        String a0 = (String) args[0];
        boolean hearth = "travel-hearth".equals(a0)
            || ("travel".equals(a0) && args.length >= 2 && "hearth".equals(args[1]));
        if (hearth)
            travelActAt = System.currentTimeMillis();
    }

    /**
     * Fires the hearth-fire travel action and waits for the character to land.
     *
     * The wait is measured rather than a blind sleep: the player's position is polled for the
     * teleport's one-tile-plus jump, and the elapsed time plus the landing {@link WorldAnchor} are
     * logged to {@code logs/hearth.log}. If the position never moves inside {@link #ARRIVAL_TIMEOUT_MS}
     * (already at the hearth, travel refused, an unreadable client state) the wait still ends and
     * the elapsed time is logged as-is - the caller behaves exactly as if the old flat sleep had
     * run.
     *
     * Fires unconditionally even past the budget, because an emergency hearth-home must never be
     * denied; the use is recorded and logged regardless.
     *
     * @return the elapsed time in milliseconds, or -1 if the player could not be read at all
     */
    public static long travel(GameUI gui) throws InterruptedException {
        long started = System.currentTimeMillis();
        Coord2d before = playerRc(gui);
        if (before == null)
            return -1;
        botInFlight = true;
        try {
            gui.act("travel", "hearth");
            long deadline = started + ARRIVAL_TIMEOUT_MS;
            boolean landed = false;
            while (!landed && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                Coord2d now = playerRc(gui);
                if (now != null && now.dist(before) > MOVE_TILE * MCache.tilesz.x) {
                    Thread.sleep(SETTLE_MS);
                    landed = true;
                }
            }
            long elapsed = System.currentTimeMillis() - started;
            WorldAnchor landing = WorldAnchor.capturePlayer(gui);
            if (landing != null)
                remember(gui, landing);
            synchronized (HearthTravel.class) {
                used++;
            }
            NLog.log(LOG, String.format(
                "travel: elapsed=%dms landed=%s (%s)%s",
                elapsed,
                landing == null ? "?" : landing.seg + "@" + landing.sc,
                landed ? "arrived" : "no position move seen",
                " budget=" + remaining()));
            return elapsed;
        } finally {
            botInFlight = false;
        }
    }

    private static void startWatcher(GameUI gui) {
        Thread t = watcher;
        if (t == null) {
            synchronized (HearthTravel.class) {
                t = watcher;
                if (t == null) {
                    t = new Thread(() -> watch(gui), "HearthTravel-watch");
                    t.setDaemon(true);
                    watcher = t;
                    t.start();
                }
            }
        }
    }

    /**
     * The passive measurement loop: samples the player's position, spots the hearth travel's
     * landing jump, and logs channel duration + landing anchor + jump distance. Also accumulates
     * sustained movement to give the walking-speed figure the travel-vs-walk decision needs.
     *
     * One daemon thread for the life of the client; it only writes when something worth recording
     * happened, so it is normally silent.
     */
    private static void watch(GameUI gui) {
        Coord2d prev = null;
        long movedSince = System.currentTimeMillis();
        double movedDist = 0;
        while (true) {
            try {
                Thread.sleep(WATCH_PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
            gui = currentGui;
            if (gui == null)
                continue;
            Coord2d rc = playerRc(gui);
            if (rc == null) {
                prev = null;
                continue;
            }
            long now = System.currentTimeMillis();
            if (prev != null) {
                double dist = rc.dist(prev);
                if (dist > JUMP_TILES * MCache.tilesz.x) {
                    long actAt = travelActAt;
                    if (actAt >= 0 && !botInFlight && (now - actAt) <= ACT_WINDOW_MS) {
                        WorldAnchor landing = WorldAnchor.capturePlayer(gui);
                        if (landing != null)
                            remember(gui, landing);
                        NLog.log(LOG, String.format(
                            "manual travel: channel=%dms jump=%.0fu landed=%s",
                            now - actAt, dist,
                            landing == null ? "?" : landing.seg + "@" + landing.sc));
                    }
                    travelActAt = -1;
                    prev = rc;
                    continue;
                }
                double speed = dist / (WATCH_PERIOD_MS / 1000.0);
                if (speed <= SPEED_MAX_TILES_PER_S * MCache.tilesz.x) {
                    movedDist += dist;
                    if ((now - movedSince) >= SPEED_WINDOW_MS) {
                        double uPerS = movedDist / ((now - movedSince) / 1000.0);
                        NLog.log(LOG, String.format("sustained movement: %.1fu/s over %ds", uPerS,
                            (now - movedSince) / 1000));
                        movedSince = now;
                        movedDist = 0;
                    }
                } else {
                    movedSince = now;
                    movedDist = 0;
                }
            }
            prev = rc;
        }
    }

    private static Coord2d playerRc(GameUI gui) {
        Gob me = (gui != null && gui.map != null) ? gui.map.player() : null;
        return me == null ? null : me.rc;
    }
}
