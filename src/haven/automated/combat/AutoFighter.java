package haven.automated.combat;

import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Utils;

/**
 * Acts on the live advice, in the fight we are already in: selects its card and aims at the
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
 * SELECT DURING THE COOLDOWN, NOT AFTER IT. The game keeps a SELECTED card - the one the bar draws
 * with the selection frame (Fightsess.use, which the server sends back) - and swings it again
 * each time the cooldown ends. The first version waited for the cooldown to end before sending
 * the planned card, so whenever the plan changed card the server had already swung the old one,
 * or swung the new one a round trip late: the lag James saw when the bot swapped moves. Now the
 * planned card is selected as soon as the plan names it, and the server swings it the instant the
 * cooldown is up. When the planned card is already the selected one nothing is sent at all.
 *
 * WHICH PLAN. Selecting early means selecting from a plan made during the cooldown, so the plan
 * is made for the moment the cooldown ends (Prediction.adviseLive's readyIn) - the opponents'
 * swings before then are in it. A plan made before our last swing began is not trusted for the
 * next one unless the cooldown is nearly up and nothing newer has arrived.
 *
 * WHEN NOTHING SWINGS. A movement command cancels the repeat, and an opponent out of reach needs
 * the use re-sent to walk us in, so if the cooldown has been over for a moment and no new one has
 * begun, the selected card is sent again.
 *
 * AIMING. When the advice wants another opponent first, it is bumped to the front of the fight
 * view - the message the relation-cycling key sends - at most once every few seconds, and nothing
 * is selected until an answer planned with the new target arrives.
 *
 * Never against an opponent we offered peace. A "use" is followed by a "rel" on the next frame -
 * the pair a key press and release send. Off whenever the client starts.
 */
public final class AutoFighter {
    private AutoFighter() {}

    /* The shortest time between two target switches. */
    private static final long SWITCH_MS = 3000;
    /* The shortest time between two selections of the same slot, while the server has not yet
     * echoed the first - a round trip, with room. */
    private static final double RESELECT_S = 0.4;
    /* A plan older than our last swing's start may still be used this close to the cooldown's end. */
    private static final double STALE_OK_S = 0.15;
    /* How long after the cooldown ends without a new one before the selection is re-sent. */
    private static final double STALL_S = 0.35;

    /* Tick thread only. */
    private static int held = -1;
    private static Fightsess heldOn = null;
    private static int picked = -1;
    private static double pickedAt = -1;
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
            if(n.moveRes == null) {
                say(gui, "no card to throw - " + n.why);
                return;
            }
            double now = Utils.rtime();
            boolean cooling = now < fv.atkct;
            /* A plan from before our last swing began describes openings that swing has since
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
            if(slot != fs.use) {
                /* A different card: select it now, so it is the one swung at the cooldown's end. */
                if((slot != picked) || ((now - pickedAt) >= RESELECT_S))
                    send(fs, slot, now);
                return;
            }
            /* Already selected. The server swings it by itself - unless the repeat was cancelled
             * or we are out of reach, which shows as a cooldown that ended and nothing after it. */
            if(!cooling && ((now - fv.atkct) >= STALL_S) && ((now - pickedAt) >= RESELECT_S)) {
                Fightsess.Action a = fs.actions[slot];
                if((a == null) || (now >= a.ct))
                    send(fs, slot, now);
            }
        } catch(Exception e) {
            /* the bot must never break the tick loop */
        }
    }

    private static void send(Fightsess fs, int slot, double now) {
        fs.wdgmsg("use", slot, 1, 0);
        held = slot;
        heldOn = fs;
        picked = slot;
        pickedAt = now;
    }

    private static void reset() {
        picked = -1;
        pickedAt = -1;
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
