package haven.automated.combat;

import haven.Coord;
import haven.Coord2d;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Gob;
import haven.Utils;

import static haven.OCache.posres;

/**
 * Acts on the live advice, in the fight we are already in: throws its card, aims at the target
 * it picks, and backs off when it says to.
 *
 * STILL NOT A HUNTER. It does not start fights, look for things to fight, or leave one. A person
 * aggroes; this plays the fight out. Everything it does is what {@link LiveAdvice} decided - the
 * card the fight view rings, the "switch target" and "back off" lines drawn above the bar - so
 * what it is about to do is on screen before it does it.
 *
 * ON THE TICK, NOT A THREAD. It reads the fight view's cooldown and relations, which the message
 * loop writes and the tick loop reads, so it runs in Fightview.tick beside the advice it consumes.
 *
 * THROWING - all of these at once:
 *  - the advice was planned with this target, and wants no other first;
 *  - our cooldown is over (Fightview.atkct) and the card's own slot is ready;
 *  - the server has answered the last card sent (a cooldown began after it), or a second has
 *    passed without one - a use that walks us into range starts nothing until the blow, and a
 *    refused one never does, so waiting for the answer forever would stall the bot;
 *  - the plan was made from a fight state seen after that card went out;
 *  - we have not offered this opponent peace. That is the person deciding the fight is over.
 * A "use" is followed by a "rel" on the next frame - the pair a key press and release send.
 *
 * AIMING. When the advice wants another opponent first, it is bumped to the front of the fight
 * view - the message the relation-cycling key sends - at most once every few seconds, and nothing
 * is thrown until an answer planned with the new target arrives.
 *
 * BACKING OFF. The advice says so only against a creature we outrun, when the next blow could take
 * more than its cap and no restoration on the bar answers it. Standing still lets openings fall
 * and moving stops them ("Being in Combat and Moving halts this restoration"), so this walks
 * straight away to just outside the creature's reach and then HOLDS STILL. It comes back when our
 * openings are down, when the blow is back under the cap, after a time limit, or at once if the
 * creature keeps closing - that means we did not outrun it after all, and running only hands it
 * our back. Coming back needs no walking of its own: the next card thrown walks us into range.
 *
 * Off whenever the client starts, whatever it was when it closed.
 */
public final class AutoFighter {
    private AutoFighter() {}

    /* How long to wait for the server to answer a card before sending the next one. */
    private static final double ANSWER_S = 1.0;
    /* How long an answer planned before our last card may still be used, when no newer state
     * arrives - a fight where nothing moved plans again on LiveAdvice's heartbeat well inside it. */
    private static final long FRESH_MS = 1500;
    /* The shortest time between two target switches. */
    private static final long SWITCH_MS = 3000;
    /* Back in once the largest of our openings is down to this, or the blow is this share of its cap. */
    private static final int RESUME_OPEN = 8;
    private static final double RESUME_SHARE = 0.6;
    /* The longest a retreat holds, how often a step away may be re-sent, how many re-sends mean
     * it is keeping up with us, and how long not to try again after that. */
    private static final long RETREAT_MAX_MS = 25000, STEP_MS = 1500, NO_RETREAT_MS = 20000;
    private static final int CHASED_LIMIT = 2;

    /* Tick thread only. */
    private static double sentAt = -1;
    private static long sentWall = 0;
    private static int held = -1;
    private static Fightsess heldOn = null;
    private static String said = null;
    private static long lastSwitch = 0;
    private static boolean retreating = false;
    private static long retreatSince = 0, lastStep = 0, noRetreatUntil = 0;
    private static long retreatFrom = 0;
    private static double retreatTo = 0;
    private static int chased = 0;

    /** Whether the auto-fighter is switched on. */
    public static boolean on() {
        haven.CheckBox c = haven.OptWnd.combatAutoFightCheckBox;
        return((c != null) && c.a);
    }

    /** One frame of the bot. Called from Fightview.tick after LiveAdvice.observe. */
    public static void tick(GameUI gui) {
        try {
            if(held >= 0) {
                if(heldOn != null)
                    heldOn.wdgmsg("rel", held);
                held = -1;
                heldOn = null;
            }
            if(!on() || (gui == null)) {
                reset();
                return;
            }
            Fightview fv = gui.fv;
            Fightsess fs = gui.fs;
            if((fv == null) || (fs == null) || (fv.current == null) || (fs.actions == null)) {
                reset();
                return;
            }
            Fightview.Relation rel = fv.current;
            long wall = System.currentTimeMillis();
            LiveAdvice.Now n = LiveAdvice.get(rel.gobid);

            if(retreating) {
                if(!holdRetreat(gui, fv, n, wall))
                    return;
            }
            if(n == null)
                return;
            if(n.wantsSwitch()) {
                if((wall - lastSwitch) >= SWITCH_MS) {
                    fv.wdgmsg("bump", (int)n.targetGob);
                    lastSwitch = wall;
                    say(gui, "aiming at " + n.targetName);
                }
                return;
            }
            if((rel.gst & 1) != 0) {
                say(gui, "you offered peace to this one, so it is not attacked");
                return;
            }
            if(n.retreat) {
                if((wall >= noRetreatUntil) && (n.threatGob != 0)) {
                    retreating = true;
                    retreatSince = wall;
                    retreatFrom = n.threatGob;
                    retreatTo = n.standOff;
                    chased = 0;
                    stepAway(gui);
                    say(gui, n.why);
                }
                return;
            }
            double now = Utils.rtime();
            if(now < fv.atkct)
                return;
            if((sentAt >= 0) && (fv.atkcs < sentAt) && ((now - sentAt) < ANSWER_S))
                return;
            if(n.moveRes == null) {
                say(gui, "no card to throw - " + n.why);
                return;
            }
            if((n.observedAt < sentWall) && ((wall - sentWall) < FRESH_MS))
                return;
            int slot = slotOf(fs, n.moveRes);
            if(slot < 0) {
                say(gui, "the planned card is not on the bar");
                return;
            }
            Fightsess.Action a = fs.actions[slot];
            if((a != null) && (now < a.ct))
                return;
            fs.wdgmsg("use", slot, 1, 0);
            held = slot;
            heldOn = fs;
            sentAt = now;
            sentWall = wall;
            said = null;
        } catch(Exception e) {
            /* the bot must never break the tick loop */
        }
    }

    /**
     * One frame of a retreat. Returns true when it has ended and the fight carries on this frame,
     * false while it holds.
     */
    private static boolean holdRetreat(GameUI gui, Fightview fv, LiveAdvice.Now n, long wall) {
        String done = null;
        int widest = 0;
        haven.combat.log.Openings o =
            CombatRecorder.readOpenings(fv.buffs.children(haven.Buff.class));
        widest = Math.max(Math.max(o.green, o.blue), Math.max(o.yellow, o.red));
        Gob threat = gob(gui, retreatFrom);
        Gob me = (gui.map == null) ? null : gui.map.player();
        if(widest <= RESUME_OPEN)
            done = "openings are down - back in";
        else if((n != null) && !Double.isNaN(n.danger) && (n.danger <= (RESUME_SHARE * n.dangerCap)))
            done = "the big blow is off the table - back in";
        else if((wall - retreatSince) > RETREAT_MAX_MS)
            done = "held off long enough - back in";
        else if((threat == null) || (me == null))
            done = "lost sight of it - back in";
        else if((me.rc.dist(threat.rc) < (retreatTo - 2.0)) && ((wall - lastStep) >= STEP_MS)) {
            if(++chased > CHASED_LIMIT) {
                done = "it keeps up with us - fighting on";
                noRetreatUntil = wall + NO_RETREAT_MS;
            } else {
                stepAway(gui);
            }
        }
        if(done == null)
            return(false);
        retreating = false;
        say(gui, done);
        return(true);
    }

    /* Walks straight away from the threat to the stand-off distance - the map click the
     * client's own combat distancing tool sends. */
    private static void stepAway(GameUI gui) {
        Gob threat = gob(gui, retreatFrom);
        Gob me = (gui.map == null) ? null : gui.map.player();
        if((threat == null) || (me == null))
            return;
        double angle = threat.rc.angle(me.rc);
        Coord2d to = new Coord2d(threat.rc.x + (retreatTo * Math.cos(angle)),
                                 threat.rc.y + (retreatTo * Math.sin(angle)));
        gui.map.wdgmsg("click", Coord.z, to.floor(posres), 1, 0);
        lastStep = System.currentTimeMillis();
    }

    private static Gob gob(GameUI gui, long id) {
        try {
            return(gui.ui.sess.glob.oc.getgob(id));
        } catch(Exception e) {
            return(null);
        }
    }

    private static void reset() {
        sentAt = -1;
        said = null;
        retreating = false;
        chased = 0;
    }

    private static int slotOf(Fightsess fs, String res) {
        for(int i = 0; i < fs.actions.length; i++) {
            Fightsess.Action a = fs.actions[i];
            if(a == null)
                continue;
            try {
                if(res.equals(a.res.get().name))
                    return(i);
            } catch(Exception e) {
                /* a card still loading cannot be the one */
            }
        }
        return(-1);
    }

    private static void say(GameUI gui, String what) {
        if(what.equals(said))
            return;
        said = what;
        try {
            gui.msg("Auto-Fighter: " + what, java.awt.Color.ORANGE);
        } catch(Exception e) {
            /* a bot that cannot print is still a bot */
        }
    }
}
