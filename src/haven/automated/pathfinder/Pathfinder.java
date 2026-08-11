package haven.automated.pathfinder;


import haven.Coord;
import haven.Coord2d;
import haven.Coordf;
import haven.Gob;
import haven.LinMove;
import haven.Loading;
import haven.MCache;
import haven.MapView;
import haven.OCache;
import haven.Pair;
import haven.automated.helpers.HitBoxes;

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
            do {
                moveinterupted = false;
                pathfind(mv.player().rc.floor());
            } while (moveinterupted && !terminate);
        } catch (Loading l) {
            refusal = Refusal.LOADING;
        } catch (RuntimeException e) {
            refusal = Refusal.FAILED;
            refusalDetail = String.valueOf(e);
        }
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
        for (Gob gob : gobs) {
                if (gob.isPlgob(this.mv.ui.gui))
                    continue;
                if (this.gob != null && this.gob.id == gob.id)
                    continue;
                if (gob.getres() != null && isInsideBoundBox(gob.rc.floor(), gob.a, gob.getres().name, player.rc.floor())) {
                    HitBoxes.CollisionBoxSecondary[] collisionBoxes = HitBoxes.collisionBoxMap.get(gob.getres().name);
                    if (collisionBoxes != null) {
                        for (HitBoxes.CollisionBoxSecondary collisionBox : collisionBoxes) {
                            if (collisionBox.hitAble && collisionBox.coords.length > 2) {
                                double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                                double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
                                for (Coord2d coord : collisionBox.coords) {
                                    minX = Math.min(minX, coord.x);
                                    minY = Math.min(minY, coord.y);
                                    maxX = Math.max(maxX, coord.x);
                                    maxY = Math.max(maxY, coord.y);
                                }
                                Coord2d topLeft = new Coord2d(minX, minY);
                                Coord2d bottomRight = new Coord2d(maxX, maxY);
                                m.excludeGob(topLeft.floor(), bottomRight.floor(), gob);
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
                        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
                        for (Coord2d coord : collisionBox.coords) {
                            minX = Math.min(minX, coord.x);
                            minY = Math.min(minY, coord.y);
                            maxX = Math.max(maxX, coord.x);
                            maxY = Math.max(maxY, coord.y);
                        }
                        Coord2d topLeft = new Coord2d(minX, minY);
                        Coord2d bottomRight = new Coord2d(maxX, maxY);
                        m.excludeGob(topLeft.floor(), bottomRight.floor(), this.gob);
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
        if (!it.hasNext())
            refusal = Refusal.NO_ROUTE;
        while (it.hasNext() && !moveinterupted && !terminate) {
            Edge e = it.next();
            mc = new Coord2d(src.x + e.dest.x - Map.origin, src.y + e.dest.y - Map.origin).floor(posres);

            if (action != null && !it.hasNext())
                mv.ui.gui.act(action);

            if (gob != null && !it.hasNext())
                mv.wdgmsg("click", Coord.z, mc, clickb, modflags, 0, (int) gob.id, gob.rc.floor(posres), 0, meshid);
            else
                mv.wdgmsg("click", Coord.z, mc, 1, 0);

            // wait for gob to start moving
            long moveWaitStart = System.currentTimeMillis();
            while (!player.isMoving() && !terminate) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e1) {
                    return;
                }
                if (System.currentTimeMillis() - moveWaitStart > RESPONSE_TIMEOUT)
                    return;
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

    public static boolean isInsideBoundBox(Coord gobRc, double gobA, String resName, Coord point) {
        if (HitBoxes.collisionBoxMap.get(resName) != null) {
            HitBoxes.CollisionBoxSecondary[] collisionBoxes = HitBoxes.collisionBoxMap.get(resName);
            for (HitBoxes.CollisionBoxSecondary collisionBox : collisionBoxes) {
                if (collisionBox.hitAble) {
                    if (collisionBox.coords.length > 3) {
                        double minX = Double.MAX_VALUE;
                        double minY = Double.MAX_VALUE;
                        double maxX = Double.MIN_VALUE;
                        double maxY = Double.MIN_VALUE;

                        for (Coord2d coord : collisionBox.coords) {
                            minX = Math.min(minX, coord.x);
                            minY = Math.min(minY, coord.y);
                            maxX = Math.max(maxX, coord.x);
                            maxY = Math.max(maxY, coord.y);
                        }
                        Coord2d topLeft = new Coord2d(minX, minY);
                        Coord2d bottomRight = new Coord2d(maxX, maxY);

                        final Coordf relative = new Coordf(point.sub(gobRc)).rotate(-gobA);
                        if (relative.x >= topLeft.x && relative.x <= bottomRight.x &&
                                relative.y >= topLeft.y && relative.y <= bottomRight.y) {
                            return true;
                        }

                    }
                    if (collisionBox.coords.length == 3) {
                        double minX = Double.MAX_VALUE;
                        double minY = Double.MAX_VALUE;
                        double maxX = Double.MIN_VALUE;
                        double maxY = Double.MIN_VALUE;

                        for (Coord2d coord : collisionBox.coords) {
                            if (coord.x < minX) {
                                minX = coord.x;
                            }
                            if (coord.y < minY) {
                                minY = coord.y;
                            }
                            if (coord.x > maxX) {
                                maxX = coord.x;
                            }
                            if (coord.y > maxY) {
                                maxY = coord.y;
                            }
                        }
                        Coord2d topLeft = new Coord2d(minX, minY);
                        Coord2d bottomRight = new Coord2d(maxX, maxY);
                        final Coordf relative = new Coordf(point.sub(gobRc)).rotate(-gobA);
                        if (relative.x >= topLeft.x && relative.x <= bottomRight.x &&
                                relative.y >= topLeft.y && relative.y <= bottomRight.y) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
