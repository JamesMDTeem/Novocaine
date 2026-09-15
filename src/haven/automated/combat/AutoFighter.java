package haven.automated.combat;

import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Utils;

/**
 * Acts on the live advice, in the fight we are already in: throws its card and aims at the
 * target it picks.
 *
 * STILL NOT A HUNTER. It does not start fights, look for things to fight, move us, or leave. A
 * person aggroes and walks; this plays the cards. Everything it does is what {@link LiveAdvice}
 * decided - the card the fight view rings, the "switch target" line drawn above the bar - so what
 * it is about to do is on screen before it does it.
 *
 * IT NEVER MOVES US. A version that backed off out of reach to let openings fall was built and
 * taken out (James, 2026-09-15): a creature keeps tracking us wherever we go, and standing still
 * is only what lets the openings decay on their own. Walking away buys nothing an animal will
 * allow, so defence here is cards alone.
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

    /* Tick thread only. */
    private static double sentAt = -1;
    private static long sentWall = 0;
    private static int held = -1;
    private static Fightsess heldOn = null;
    private static String said = null;
    private static long lastSwitch = 0;

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

    private static void reset() {
        sentAt = -1;
        said = null;
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
