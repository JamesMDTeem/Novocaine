package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.automated.helpers.HitBoxes;
import haven.automated.pathfinder.Pathfinder;

import static haven.OCache.posres;

/**
 * The command layer of walking: issuing clicks and waiting on them.
 *
 * The floor of the movement split. Every other movement class - and the bots themselves - walk
 * by issuing a command through here and waiting until it has visibly taken effect, so there is
 * exactly one place that knows what "stop" means, one place that knows what "moving" means, and
 * one place that reads the collision-box geometry the pathfinder itself blocks on.
 */
public class MovementCommand {
    private static final int POLL_MS = 25;

    private final GameUI gui;
    private final BotNav.Abort abort;

    public MovementCommand(GameUI gui, BotNav.Abort abort) {
        this.gui = gui;
        this.abort = abort;
    }

    /**
     * Polls {@code cond} every {@link #POLL_MS} up to maxTicks. Throws as soon as the bot is
     * stopped, which is what makes Stop feel immediate rather than "after the current wait".
     */
    public void waitUntil(BotNav.Cond cond, int maxTicks) throws InterruptedException {
        for (int i = 0; i < maxTicks; i++) {
            if (!abort.running() || Thread.interrupted())
                throw new InterruptedException();
            try {
                if (cond.check())
                    return;
            } catch (Loading l) {
                // Not resolvable this tick; keep waiting.
            }
            Thread.sleep(POLL_MS);
        }
    }

    /** Plain delay that still honours Stop. */
    public void pause(int ticks) throws InterruptedException {
        waitUntil(() -> false, ticks);
    }

    // ------------------------------------------------------------------ state

    public Gob player() {
        return (gui.map == null) ? null : gui.map.player();
    }

    public Gob gob(long id) {
        return gui.ui.sess.glob.oc.getgob(id);
    }

    /**
     * Whether the character is going anywhere.
     *
     * The server's own answer first - see {@link Walk#moving} - because it is the only one that is
     * not an inference. {@code getv() > 0} asked the same attribute for its speed, which reads a
     * standing character and a character the server has stopped identically to one whose velocity
     * has not been filled in yet. The pathfinder thread still counts: while it is alive there are
     * more legs of its route to come, so the character is between moves rather than finished.
     */
    public boolean walking() {
        return Walk.moving(gui)
            || ((gui.map != null) && (gui.map.pfthread != null) && gui.map.pfthread.isAlive());
    }

    /** Move-to-self: the standard way to interrupt a repeating in-place action. */
    public void stopAction() {
        Gob p = player();
        if (p != null)
            gui.map.wdgmsg("click", Coord.z, p.rc.floor(posres), 1, 0);
    }

    /**
     * Stops walking for good, pathfinder included.
     *
     * stopAction() alone ends the current MOVE, but the Pathfinder thread is still alive and simply
     * issues the next leg - so a bot that only called that would keep walking towards whatever it
     * was trying to walk away from. pfLeftClick/pfRightClick do this teardown themselves before
     * starting a new search, which is why re-pathing needs no equivalent; only abandoning does.
     */
    public void cancelWalk() {
        synchronized (Pathfinder.class) {
            if (gui.map.pf != null) {
                gui.map.pf.terminate = true;
                if (gui.map.pfthread != null)
                    gui.map.pfthread.interrupt();
            }
        }
        stopAction();
    }

    /**
     * How far a gob's own solid part reaches from its middle.
     *
     * The pathfinder walks to the EDGE of a collision box, so "how close did we get" has to be
     * read against the thing's size or every large tree reads as unreached. Taken from the same
     * box data the pathfinder blocks on, so the two agree about how big things are; anything with
     * no box recorded is a point, which is the safe way to be wrong here.
     */
    /**
     * How far a point is from the NEAREST part of a gob's solid box, rather than from a circle
     * drawn round it.
     *
     * {@link #bulk} is the CIRCUMSCRIBED radius - the furthest corner - and for anything round that
     * is the same answer. For a felled log it is not remotely: a log's box is a long thin
     * rectangle, so its circumscribed circle is set by half the LENGTH, and standing beside one
     * puts a character deep inside that circle while still a tile clear of the timber. Subtracting
     * bulk from a centre distance then reports zero or less, and every caller that reads it as "we
     * are touching it" is wrong by most of a tile - in the direction that matters, because it is
     * the direction the character actually approaches from when the log lies across its path.
     *
     * Exact rather than approximate, and cheaply so. The box data is axis-aligned in the gob's own
     * frame and the gob carries its rotation, so instead of rotating four corners into the world
     * this rotates the QUERY POINT back into the box's frame - one sin and one cos - and then the
     * nearest point on an axis-aligned rectangle is two subtractions. The lossy step everything
     * else in this stack takes, quantising a rotated shape to an axis-aligned box and that box to
     * tiles, is skipped entirely.
     *
     * Zero when the point is inside the box. A gob with no box recorded is a point, which is what
     * {@link #bulk} does and the safe way to be wrong.
     */
    public static double faceGap(Gob g, Coord2d from) {
        try {
            if ((g == null) || (from == null))
                return 0;
            Resource res = g.getres();
            HitBoxes.CollisionBoxSecondary[] boxes = (res == null) ? null
                : HitBoxes.collisionBoxMap.get(res.name);
            if (boxes == null)
                return from.dist(g.rc);
            // Into the gob's frame: translate to its origin, then un-rotate by its angle.
            Coord2d rel = from.sub(g.rc);
            double cos = Math.cos(-g.a), sin = Math.sin(-g.a);
            double lx = (rel.x * cos) - (rel.y * sin);
            double ly = (rel.x * sin) + (rel.y * cos);
            double best = Double.MAX_VALUE;
            for (HitBoxes.CollisionBoxSecondary box : boxes) {
                if ((box == null) || (box.coords == null) || (box.coords.length == 0))
                    continue;
                double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
                double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
                for (Coord2d c : box.coords) {
                    minx = Math.min(minx, c.x);
                    miny = Math.min(miny, c.y);
                    maxx = Math.max(maxx, c.x);
                    maxy = Math.max(maxy, c.y);
                }
                // Nearest point on the rectangle, which is zero on every axis the point is inside.
                double dx = Math.max(0, Math.max(minx - lx, lx - maxx));
                double dy = Math.max(0, Math.max(miny - ly, ly - maxy));
                best = Math.min(best, Math.hypot(dx, dy));
            }
            return (best == Double.MAX_VALUE) ? from.dist(g.rc) : best;
        } catch (RuntimeException e) {
            // Includes Loading: an unresolved gob is a point, same as bulk treats it.
            return (from == null) ? 0 : from.dist(g.rc);
        }
    }

    public static double bulk(Gob g) {
        try {
            Resource res = (g == null) ? null : g.getres();
            HitBoxes.CollisionBoxSecondary[] boxes = (res == null) ? null
                : HitBoxes.collisionBoxMap.get(res.name);
            if (boxes == null)
                return 0;
            double far = 0;
            for (HitBoxes.CollisionBoxSecondary box : boxes) {
                if ((box == null) || (box.coords == null))
                    continue;
                for (Coord2d c : box.coords)
                    far = Math.max(far, Math.hypot(c.x, c.y));
            }
            return far;
        } catch (RuntimeException e) {
            // Includes Loading: treat an unresolved gob as a point rather than guessing big.
            return 0;
        }
    }
}
