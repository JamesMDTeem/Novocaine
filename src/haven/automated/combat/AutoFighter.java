package haven.automated.combat;

import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Utils;

/**
 * Acts on the live advice, in the fight we are already in: presses its card and aims at the
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
 * ONE PRESS PER SWING, DURING THE COOLDOWN. The game swings a card again at the end of a
 * cooldown only while it is held down or pressed again - a "use" followed by its "rel" is one
 * press. Two versions got that wrong, and the logs measured both (the gap between one cooldown
 * ending and the next swing):
 *  - the first sent the card only once the cooldown was over: every swing a round trip late,
 *    about 180 ms;
 *  - the second pressed a new card during the cooldown but treated a card already selected as
 *    one the game would repeat by itself. It would not, so every repeat of the same card waited
 *    for the stall fallback - 540 ms late, where a person holding the key is 1 ms late and a
 *    change of card was already 4 ms.
 * So every swing that begins gets the planned card pressed during its cooldown, whether or not
 * it is the same card, and a plan that changes its mind before the cooldown ends presses the new
 * one. The game swings it the instant the cooldown is up.
 *
 * WHICH PLAN. Pressing during the cooldown means pressing from a plan made during it, so the plan
 * is made for the moment the cooldown ends (Prediction.adviseLive's readyIn). A plan made before
 * the current swing began is not trusted unless the cooldown is nearly up and nothing newer has
 * arrived.
 *
 * WHEN NOTHING SWINGS. A movement command cancels a queued card, and an opponent out of reach needs
 * the use re-sent to walk us in, so if a cooldown has been over for a moment and no new one has
 * begun, the card is pressed again.
 *
 * AIMING. When the advice wants another opponent first, it is bumped to the front of the fight
 * view - the message the relation-cycling key sends - at most once every few seconds, and nothing
 * is pressed until an answer planned with the new target arrives.
 *
 * PEACE. Never against an opponent we offered peace to - unless the offer was Auto-Reaggro's or
 * Auto Peace Animals' (see {@link #peaceIsTactic}). Off whenever the client starts.
 */
public final class AutoFighter {
    private AutoFighter() {}

    /* The shortest time between two target switches. */
    private static final long SWITCH_MS = 3000;
    /* The shortest time between two presses of different cards within one cooldown, so a plan
     * wavering between two near-equal cards cannot turn into a stream of messages. */
    private static final double CHANGE_S = 0.25;
    /* The shortest time between two presses in the stall fallback - a round trip, with room. */
    private static final double RESEND_S = 0.4;
    /* A plan older than the current swing's start may still be used this close to the cooldown's end. */
    private static final double STALE_OK_S = 0.15;
    /* How long after the cooldown ends without a new one before the card is pressed again. */
    private static final double STALL_S = 0.35;

    /* Tick thread only. The card last pressed and when; the swing (Fightview.atkcs) it was pressed
     * for; the slot awaiting its release; the last reason shown. */
    private static int held = -1;
    private static Fightsess heldOn = null;
    private static int pressed = -1;
    private static double pressedAt = -1;
    private static double pressedFor = -1;
    private static String said = null;
    private static long lastSwitch = 0;

    /** Whether the auto-fighter is switched on. */
    public static boolean on() {
        haven.CheckBox c = haven.OptWnd.combatAutoFightCheckBox;
        return((c != null) && c.a);
    }

    /** Whether our peace offer to this relation came from Auto-Reaggro or Auto Peace Animals. */
    public static boolean peaceIsTactic(Fightview.Relation rel) {
        return(rel.autopeaced || ((rel.autogive != null) && (rel.autogive.state == 1)));
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
            /* A peace offer the player made to end the fight. Two client features offer peace as a
             * TACTIC, and those are fought through: Auto-Reaggro (P, Relation.autogive) offers peace
             * so a fleeing animal is re-aggroed the moment the relation drops, and Auto Peace Animals
             * (Relation.autopeaced) offers it at the start. Reading either as "fight over" stopped the
             * bot a second or two into James's reaggro fights. A peace offer made by hand while one
             * of those is active cannot be told apart, and is fought through too. */
            if(((rel.gst & 1) != 0) && !peaceIsTactic(rel)) {
                say(gui, "you offered peace to this one, so it is not attacked");
                return;
            }
            if(n.moveRes == null) {
                say(gui, "no card to throw - " + n.why);
                return;
            }
            double now = Utils.rtime();
            boolean cooling = now < fv.atkct;
            /* A plan from before the current swing began describes openings that swing has since
             * changed. Wait for a newer one, unless the cooldown is about to end without it. */
            boolean stale = (fv.atkcs > 0) && (n.observedRt < fv.atkcs);
            if(stale && cooling && ((fv.atkct - now) > STALE_OK_S))
                return;
            int slot = slotOf(fs, n.moveRes);
            if(slot < 0) {
                say(gui, "the planned card is not on the bar");
                return;
            }
            said = null;
            /* A swing has begun since our last press: press for the next one. */
            if(fv.atkcs > pressedFor) {
                send(fs, slot, now, fv.atkcs);
                return;
            }
            /* The plan changed its mind within this cooldown: press the new card instead. */
            if((slot != pressed) && ((now - pressedAt) >= CHANGE_S)) {
                send(fs, slot, now, fv.atkcs);
                return;
            }
            /* Nothing has swung since the cooldown ended - a cancelled queue, or out of reach. */
            if(!cooling && ((now - fv.atkct) >= STALL_S) && ((now - pressedAt) >= RESEND_S)) {
                Fightsess.Action a = fs.actions[slot];
                if((a == null) || (now >= a.ct))
                    send(fs, slot, now, fv.atkcs);
            }
        } catch(Exception e) {
            /* the bot must never break the tick loop */
        }
    }

    private static void send(Fightsess fs, int slot, double now, double swing) {
        fs.wdgmsg("use", slot, 1, 0);
        held = slot;
        heldOn = fs;
        pressed = slot;
        pressedAt = now;
        pressedFor = swing;
    }

    private static void reset() {
        pressed = -1;
        pressedAt = -1;
        pressedFor = -1;
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
