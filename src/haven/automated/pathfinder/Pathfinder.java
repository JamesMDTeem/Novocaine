package haven.automated.pathfinder;


import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.Gob;
import haven.LinMove;
import haven.Loading;
import haven.MCache;
import haven.MapView;
import haven.Moving;
import haven.OCache;
import haven.Pair;
import haven.ResDrawable;
import haven.Resource;
import haven.automated.helpers.CollisionGeom;
import haven.automated.helpers.HitBoxes;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.Widgets;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static haven.OCache.posres;

/**
 * Runs A* navigation on a background thread and drives the player along the route.
 *
 * A caller constructs one with a destination (optionally a target gob and the click flags for
 * reaching it), starts it as a thread, and {@link #run()} keeps re-searching until the
 * destination is reached or {@code terminate} is set. Each search builds a fresh {@link Map}
 * centred on the current position, excludes the collision boxes of nearby gobs - and of the
 * target gob itself, when one is set - runs the search, and turns the winning path into a
 * sequence of movement clicks. {@link #pathWaypoints} exposes the last route (with the start
 * position prepended) for diagnostics.
 *
 * A journey that cannot be made is reported through {@link Refusal} and the registered
 * {@link PFListener}s instead of failing silently; see the enum's comments for what each
 * refusal means and how a caller is expected to react.
 */
public class Pathfinder implements Runnable {
    private OCache oc;
    private MCache map;
    private MapView mv;
    private Coord dest;
    /* Both flags are written by whichever thread cancels or restarts a search and read by this
     * search's own thread. Without volatile the reading thread is free to hoist the load out of a
     * loop, so a cancelled search can keep running and then publish its result over the state the
     * next hop has already set up. interrupt() is not a reliable substitute: the A* phase
     * ({@link Map#main}) never blocks, so an interrupt arriving there cannot stop it until the
     * loop next touches the flag - which is the point of the flags being checked there. The
     * refusal fields below are volatile for the same reason. */
    public volatile boolean terminate = false;
    public volatile boolean moveinterupted = false;
    private int meshid;
    private int clickb;
    private Gob gob;
    private String action;
    public Coord mc;
    private int modflags;
    private int interruptedRetries = 5;
    private static final int RESPONSE_TIMEOUT = 800;
    private long avgOverrun = 0;
    public List<Coord2d> pathWaypoints = new ArrayList<>();

    /**
     * Why a search issued no movement.
     *
     * The four are genuinely different and want opposite things done about them, which is the
     * whole reason this is an enum and not a flag. Reading them as one thing has cost real rounds:
     * treating NO_ROUTE as "something odd happened" means never opening the gate that is causing
     * it, and treating STUCK as "there is a wall here" means walking off to open a gate when the
     * only thing wrong is where we are standing.
     */
    public enum Refusal {
        /**
         * We are inside a collision box and {@code Map.getFreeLocation} found nowhere to step.
         * Says nothing whatever about the route - the search never got as far as looking. The
         * caller has to move, by some means other than this, before anything else can work.
         */
        STUCK("we are standing inside something and it cannot find a way out"),
        /**
         * The search ran and found nothing. This IS evidence about the world: within the
         * eighty-eight tile window there is no way from here to there, and by far the commonest
         * reason is a shut gate or a wall between the two. A caller with gate handling should use
         * it - it is a better trigger than waiting for a walk to stall, because it is available
         * before the wasted hops rather than after them.
         */
        /** The search found a real path and sent the first move click, but the server never
         *  started the character moving within {@link #RESPONSE_TIMEOUT}. Not STUCK - the search
         *  served us a path, so we are not boxed in - and not NO_ROUTE - there is a path. The
         *  client model and the server disagree at the standing spot (typically a tight fit, or a
         *  gob the raster skipped because its resource was still loading). The caller's remedy is
         *  the same as STUCK: move by some means other than this search. */
        NO_MOVE("the server did not answer the move order"),
        NO_ROUTE("no way from here to there"),
        /** A grid in the window had not arrived. Nothing is wrong; ask again in a moment. */
        LOADING("part of the map is still loading"),
        /** Something threw. Recorded rather than swallowed so it cannot masquerade as the others. */
        FAILED("the search failed");

        public final String why;

        Refusal(String why) {
            this.why = why;
        }
    }

    /** Why this search issued no movement, or null if it issued some. */
    public volatile Refusal refusal = null;
    /** The detail behind {@link #refusal} when there is any, for the log. */
    public volatile String refusalDetail = null;
    /** How many gobs the last {@link #pathfind} snapshotted, for the slow-search log line. */
    private volatile int gobsSeen = 0;

    /** Set when {@link #run} returns, however it ends. Callers wait on this rather than racing a
     *  freshly-issued search - one that is still shuffling its way out of a blocked origin has
     *  neither a path nor a refusal yet, and judging it then reads as "died for no reason". */
    public volatile boolean done = false;

    /** What went wrong, phrased for a log line, or null if nothing did. */
    public String why() {
        Refusal r = refusal;
        if (r == null)
            return null;
        return (refusalDetail == null) ? r.why : (r.why + ": " + refusalDetail);
    }

    public Pathfinder(MapView mv, Coord dest, String action) {
        this.dest = dest;
        this.action = action;
        this.oc = mv.glob.oc;
        this.map = mv.glob.map;
        this.mv = mv;
    }

    public Pathfinder(MapView mv, Coord dest, Gob gob, int meshid, int clickb, int modflags, String action) {
        this.dest = dest;
        this.meshid = meshid;
        this.clickb = clickb;
        this.gob = gob;
        this.modflags = modflags;
        this.action = action;
        this.oc = mv.glob.oc;
        this.map = mv.glob.map;
        this.mv = mv;
    }

    private final Set<PFListener> listeners = new CopyOnWriteArraySet<PFListener>();
    public final void addListener(final PFListener listener) {
        listeners.add(listener);
    }

    public final void removeListener(final PFListener listener) {
        listeners.remove(listener);
    }

    private final void notifyListeners() {
        for (PFListener listener : listeners) {
            listener.pfDone(this);
        }
    }

    @Override
    public void run() {
        /* initGeography reads MCache tile by tile over the whole 88-tile window, and MCache throws
         * Loading for any grid it does not have - so one grid still coming in from the server used
         * to end this thread where it stood, with no path, no movement and no trace. That is a
         * routine condition near the edge of what is loaded, not an error, and a caller that knows
         * it happened can simply ask again in a moment. */
        try {
            /* Timed so a slow search names itself: the walk layer waits on this thread before
             * the click goes out, so a search that stalls is exactly the "stands there thinking"
             * pause a player sees between the route and the first step. */
            long startedAt = System.nanoTime();
            do {
                moveinterupted = false;
                pathfind(mv.player().rc.floor());
            } while (moveinterupted && !terminate);
            long ms = (System.nanoTime() - startedAt) / 1_000_000;
            if (ms >= 500)
                NLog.log("pf", "client pathfind took " + ms + "ms (" + gobsSeen
                    + " gobs in the snapshot; refusal=" + refusal + ")");
        } catch (Loading l) {
            refusal = Refusal.LOADING;
        } catch (RuntimeException e) {
            refusal = Refusal.FAILED;
            refusalDetail = String.valueOf(e);
        }
        done = true;
        notifyListeners();
    }

    public void pathfind(Coord src) {
        long starttotal = System.nanoTime();
        Map m = new Map(src, dest, map);
        Gob player = mv.player();

        long start = System.nanoTime();
        List<Gob> gobs;
        synchronized (oc) {
            gobs = new ArrayList<Gob>();
            for (Gob gob : oc)
                gobs.add(gob);
        }
        gobsSeen = gobs.size();
        for (Gob gob : gobs) {
                if (gob.isPlgob(this.mv.ui.gui))
                    continue;
                if (this.gob != null && this.gob.id == gob.id)
                    continue;
                if (gob.getres() != null && isInsideBoundBox(gob, player.rc.floor())) {
                    HitBoxes.CollisionBoxSecondary[] collisionBoxes = HitBoxes.collisionBoxMap.get(gob.getres().name);
                    if (collisionBoxes != null) {
                        for (HitBoxes.CollisionBoxSecondary collisionBox : collisionBoxes) {
                            if (collisionBox.hitAble && collisionBox.coords.length > 2) {
                                m.excludeGob(collisionBox.coords, gob);
                            }
                        }
                    }
                }
                m.analyzeGobHitBoxes(gob);
        }

        if (m.isOriginBlocked()) {
            Pair<Integer, Integer> freeloc = m.getFreeLocation();
            if (freeloc == null) {
                /* Standing inside a collision box with no way out that this can see - and it
                 * cannot see much, since getFreeLocation probes four points three units away
                 * against boxes that are eight or more units thick, and a shut gate is blocked as
                 * a slab thirty-two units long. This is the deadlock: every path from here is
                 * refused, including the one that would take us back out. Callers have to step
                 * clear by some other means; see haven.automated.nbots.world.Walk. */
                refusal = Refusal.STUCK;
                terminate = true;
                m.dbgdump();
                return;
            }
            mc = new Coord2d(src.x + freeloc.a - Map.origin, src.y + freeloc.b - Map.origin).floor(posres);
            mv.wdgmsg("click", Coord.z, mc, 1, 0);
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {}
            moveinterupted = true;
            m.dbgdump();
            return;
        }

        if (this.gob != null) {
            HitBoxes.CollisionBoxSecondary[] collisionBoxes = HitBoxes.collisionBoxMap.get(this.gob.getres().name);
            if (collisionBoxes != null) {
                for (HitBoxes.CollisionBoxSecondary collisionBox : collisionBoxes) {
                    if (collisionBox.hitAble && collisionBox.coords.length > 2) {
                        m.excludeGob(collisionBox.coords, this.gob);
                    }
                }
            }
        }

        if (Map.DEBUG_TIMINGS)
            System.out.println("      Gobs Processing: " + (double) (System.nanoTime() - start) / 1000000.0 + " ms.");

        Iterable<Edge> path = m.main();

        if (Map.DEBUG_TIMINGS)
            System.out.println("--------------- Total: " + (double) (System.nanoTime() - starttotal) / 1000000.0 + " ms.");

        m.dbgdump();

        pathWaypoints.clear();
        pathWaypoints.add(player.rc);
        for (Edge e : path) {
            Coord2d waypoint = new Coord2d(src.x + e.dest.x - Map.origin, src.y + e.dest.y - Map.origin);
            pathWaypoints.add(waypoint);
        }

        Iterator<Edge> it = path.iterator();
        /* An empty path is the search having found nothing, not a journey of length zero. The
         * loop below simply never runs, so no click is sent and the thread ends having done
         * nothing - which is why "the destination is inside a barrel" and "there is a river in
         * the way" both used to surface as an unexplained failure to set off. */
        if (!it.hasNext()) {
            refusal = Refusal.NO_ROUTE;
            StringBuilder diag = new StringBuilder("NO_ROUTE src=").append(src)
                    .append(" dest=").append(dest)
                    .append(" gobs=").append(gobs.size());
            List<Gob> beasts = haven.automated.nbots.world.Hazards.all(mv.ui.gui);
            if (!beasts.isEmpty()) {
                diag.append(" beasts=");
                for (Gob b : beasts) {
                    diag.append(haven.automated.nbots.world.Hazards.resname(b)).append('@')
                            .append((int) b.rc.dist(player.rc)).append("u ");
                }
            }
            NLog.log("pf", diag.toString());
        }
        int edgeIdx = 0;
        int pathSize = 0;
        for (Edge ignored : path)
            pathSize++;
        while (it.hasNext() && !moveinterupted && !terminate) {
            Edge e = it.next();
            int edgeNo = ++edgeIdx;
            Coord2d waypoint = new Coord2d(src.x + e.dest.x - Map.origin, src.y + e.dest.y - Map.origin);
            mc = waypoint.floor(posres);

            /* A waypoint on the tile we are already standing on is a no-op click: the
             * server never answers "move to where I am", the 800ms wait times out, and
             * the walk is declared NO_MOVE. The search's first waypoint can land here
             * when the character is pressed against a gob (its offset corner shares the
             * player's tile). Skip that edge - the rest of the path is still valid.
             * Compare TILES, not posres cells: posres is 11/1024 of a tile, so the
             * character's fractional rc (.00488 etc.) can floor onto a different posres
             * cell than an integer waypoint on the very same tile. */
            if (waypoint.floor(MCache.tilesz).equals(mv.player().rc.floor(MCache.tilesz)) && (gob == null || it.hasNext())) {
                if (pathWaypoints.size() > 1) {
                    pathWaypoints.remove(1);
                    pathWaypoints.set(0, player.rc);
                }
                continue;
            }

            if (action != null && !it.hasNext())
                mv.ui.gui.act(action);

            /* A stale FlowerMenu swallows the move click: the server is waiting on the
             * menu's answer, so the click never becomes a move and the walk times out
             * into NO_MOVE (see the 16:21 probe dumps, where flowerMenuOpen=true at
             * click time). Dismiss any menu that is still up before the click so the
             * order actually lands. */
            long menuGuardStart = System.currentTimeMillis();
            while (Widgets.find(mv.ui.root, haven.FlowerMenu.class) != null
                    && System.currentTimeMillis() - menuGuardStart < 2000) {
                haven.FlowerMenu fm = Widgets.find(mv.ui.root, haven.FlowerMenu.class);
                if (fm != null)
                    fm.wdgmsg("cl", -1);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    return;
                }
            }

            /* The last edge to a gob is an interaction click, not a move: the caller
             * (openMenuOn) passes clickb=3 so the gob's FlowerMenu opens where we stand.
             * The character is not supposed to move, so isMoving() must not be the test
             * of success - the menu appearing is. (16:37 probe: menu open, char still,
             * and the walk was declared NO_MOVE, which sent the STUCK remedy to back out
             * with raw steps - the "small run forward into it".) */
            boolean interaction = (gob != null && !it.hasNext());
            if (interaction)
                mv.wdgmsg("click", Coord.z, mc, clickb, modflags, 0, (int) gob.id, gob.rc.floor(posres), 0, meshid);
            else
                mv.wdgmsg("click", Coord.z, mc, 1, 0);

            // wait for the click to land: a move starts the character walking, and an
            // interaction opens the target's menu. Either is the order being answered.
            long moveWaitStart = System.currentTimeMillis();
            while (!player.isMoving() && !terminate
                    && !(interaction && Widgets.find(mv.ui.root, haven.FlowerMenu.class) != null)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e1) {
                    return;
                }
                if (System.currentTimeMillis() - moveWaitStart > RESPONSE_TIMEOUT) {
                    /* The move order was never answered. That is a verdict, not a stall: the
                     * search served a real path and the server declined to take the first step
                     * from where we are standing. Leave it silent and the caller re-paths
                     * forever - the character pressed against a gob re-issues the same refused
                     * click eleven times and the walk gives up without ever saying why. Name it
                     * so a STUCK remedy (backing out with raw steps) can fire on the first pass. */
                    refusal = Refusal.NO_MOVE;
                    terminate = true;
                    /* [DEBUG-nomove] Discriminating probe for the 15:43 sandthorn freeze: is a
                     * FlowerMenu still open (menu swallowed the click), is the char mid-move, and
                     * which boxes sit within 12u of the char? See the NO_MOVE plan. */
                    try {
                        StringBuilder dbg = new StringBuilder();
                        dbg.append("NO_MOVE edge=").append(edgeNo).append('/').append(pathSize)
                            .append(" interaction=").append(interaction).append(" clickb=").append(clickb)
                            .append(" gob=").append((gob == null) ? "none" : ("#" + gob.id + " " + gob.getres().name));
                        dbg.append(" mc=").append(mc).append(" (world=").append(mc.mul(posres))
                            .append(") char=").append(player.rc)
                            .append(" src=").append(src);
                        int wpn = 0;
                        for (Coord2d wp : pathWaypoints) {
                            if (wpn++ > 4)
                                break;
                            dbg.append("\n  wp").append(wpn).append("=").append(wp);
                        }
                        boolean menuOpen = Widgets.find(mv.ui.root, haven.FlowerMenu.class) != null;
                        dbg.append(" flowerMenuOpen=").append(menuOpen);
                        dbg.append(" movingAttr=").append(player.getattr(haven.Moving.class) != null);
                        for (Gob near : gobs) {
                            if (near.id == player.id || near.isPlgob(mv.ui.gui))
                                continue;
                            if (player.rc.dist(near.rc) <= 12.0) {
                                String res = (near.getres() != null) ? near.getres().name : "<nores>";
                                dbg.append("\n  near #").append(near.id).append(" ").append(res)
                                    .append(" rc=").append(near.rc);
                                HitBoxes.CollisionBoxSecondary[] boxes = (near.getres() == null)
                                    ? null : HitBoxes.collisionBoxMap.get(near.getres().name);
                                if (boxes != null) {
                                    for (HitBoxes.CollisionBoxSecondary box : boxes) {
                                        if (!box.hitAble)
                                            continue;
                                        double bx1 = Double.MAX_VALUE, by1 = Double.MAX_VALUE,
                                            bx2 = -Double.MAX_VALUE, by2 = -Double.MAX_VALUE;
                                        for (Coord2d c : box.coords) {
                                            bx1 = Math.min(bx1, c.x); by1 = Math.min(by1, c.y);
                                            bx2 = Math.max(bx2, c.x); by2 = Math.max(by2, c.y);
                                        }
                                        dbg.append(" box=").append((int) bx1).append(',').append((int) by1)
                                            .append('-').append((int) bx2).append(',').append((int) by2);
                                    }
                                }
                            }
                        }
                        NLog.log("pf", "[DEBUG-nomove] " + dbg);
                    } catch (Exception dbgex) {
                        NLog.log("pf", "[DEBUG-nomove] probe threw: " + dbgex);
                    }
                    return;
                }
            }

            Coord2d destWorld = mc.mul(posres);
            long segmentStart = System.currentTimeMillis();
            long estimate = estimateTravelTimeWorld(player.rc, destWorld, player);
            long lead = Math.min(50, (long) (estimate * 0.05));
            long wait = Math.max(0, estimate - lead - avgOverrun);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e1) {
                    return;
                }
            }


            while (!moveinterupted && !terminate) {
                if (!player.isMoving()) {
                    break;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e1) {
                    return;
                }
            }

            long actual = System.currentTimeMillis() - segmentStart;
            long overrun = actual - estimate;
            avgOverrun = (avgOverrun + overrun) / 2;

            if (pathWaypoints.size() > 1) {
                pathWaypoints.remove(1);
                pathWaypoints.set(0, player.rc);
            }

            if (moveinterupted) {
                interruptedRetries--;
                if (interruptedRetries == 0)
                    terminate = true;
                m.dbgdump();
                return;
            }
        }
        terminate = true;
    }

    private long estimateTravelTimeWorld(Coord2d curPos, Coord2d destPos, Gob player) {
        LinMove lm = player.getLinMove();
        double speed = (lm != null) ? lm.getv() : 0; // world units per second
        if (speed <= 0) {
            return RESPONSE_TIMEOUT;
        }
        double dist = curPos.dist(destPos);
        return (long)((dist / speed) * 1000.0);
    }

    /**
     * Whether {@code point} lies inside the exact collision box of {@code gob}.
     *
     * Takes the gob rather than its position/resource so it can judge a gateway by its LIVE
     * state: an open gate is passable, exactly as the pathfinder treats it (see
     * {@link Map#analyzeGobHitBoxes}). The box map's {@code hitAble} flag cannot be trusted for
     * gates - {@code HitBoxes.checkHitAble} freezes the gate's state at first sighting, so a
     * gate seen shut stays solid on the bot's side even after it swings open.
     *
     * A gob whose resource has not arrived yet reports "not inside", never "solid": every caller
     * asks "can a disc be here", and guessing solid would refuse ground that is almost certainly
     * fine (the pathfinder itself cannot test such a gob either).
     */
    public static boolean isInsideBoundBox(Gob gob, Coord point) {
        Resource res = gob.getres();
        if (res == null)
            return false;
        String resName = res.name;
        if (resName.contains("gate")) {
            try {
                ResDrawable rd = gob.getattr(ResDrawable.class);
                if (rd != null && rd.sdt.checkrbuf(0) == 1)
                    return false;
            } catch (RuntimeException e) {
                return false;
            }
        }
        if (HitBoxes.collisionBoxMap.get(resName) != null) {
            HitBoxes.CollisionBoxSecondary[] collisionBoxes = HitBoxes.collisionBoxMap.get(resName);
            for (HitBoxes.CollisionBoxSecondary collisionBox : collisionBoxes) {
                if (collisionBox.hitAble && collisionBox.coords.length >= 3) {
                    /* Cheap reject first: rotation preserves every vertex's distance from the gob
                     * origin, so the whole rotated box lies inside a circle of radius maxR around
                     * gob.rc - and a query point outside that circle can never be inside. Without
                     * this, every caller that tests a whole snapshot of gobs (standable()'s
                     * blockedThere probes, the player-inside scan in pathfind) rotates and
                     * allocates for every gob in the segment on every hop, and the per-hop lag
                     * that cost came from those probes. */
                    double maxR = 0;
                    for (Coord2d c : collisionBox.coords)
                        maxR = Math.max(maxR, Math.hypot(c.x, c.y));
                    double dx = point.x - gob.rc.x;
                    double dy = point.y - gob.rc.y;
                    if ((dx * dx + dy * dy) > (maxR * maxR))
                        continue;
                    Coord2d[] world = CollisionGeom.worldPolygon(collisionBox.coords, gob.rc, gob.a);
                    if (CollisionGeom.pointInConvex(world, point.x, point.y))
                        return true;
                }
            }
        }
        return false;
    }
}
