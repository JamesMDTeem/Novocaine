package haven.automated.combat;

import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Utils;

/**
 * Throws the card the live advice picks, in the fight we are already in.
 *
 * THE FIRST STEP OF A COMBAT BOT, AND ONLY THE FIRST. It does not start fights, choose targets,
 * move, offer peace or leave. A person aggroes and aims; this plays the cards against whoever is
 * aimed at. Every card it throws is the one {@link LiveAdvice} picked - the same answer the fight
 * view draws round the card - so what it is about to do is on screen before it does it. That
 * answer is the fastest kill that keeps a reserve of our hitpoints, which is where its
 * restorations come from: when the fastest line would cost more than we can spare, the plan
 * that closes our openings is the one handed over.
 *
 * ON THE TICK, NOT A THREAD. SparBot sends from a thread of its own. This reads the fight view's
 * cooldown and relations, which the message loop writes and the tick loop reads, so it runs in
 * Fightview.tick beside the advice it consumes and never races either of them.
 *
 * WHEN IT THROWS - all of these at once:
 *  - our cooldown is over (Fightview.atkct) and the card's own slot is ready;
 *  - the server has answered the last card sent (a cooldown began after it), or a second has
 *    passed without one - a use that walks us into range starts nothing until the blow, and a
 *    refused one never does, so waiting for the answer forever would stall the bot;
 *  - the plan was made from a fight state seen after that card went out, so a card is never
 *    chosen against openings that predate our own last blow;
 *  - we have not offered this opponent peace. That is the person deciding the fight is over, and
 *    a bot that swung through it would be taking the decision back.
 *
 * A "use" is followed by a "rel" on the next frame - the pair a key press and release send - so
 * the server never holds a card it would keep throwing after the advice has moved on.
 *
 * Off whenever the client starts, whatever it was when it closed: a bot that resumed fighting by
 * itself after a restart is not something anybody should discover in a fight.
 */
public final class AutoFighter {
    private AutoFighter() {}

    /* How long to wait for the server to answer a card before sending the next one. */
    private static final double ANSWER_S = 1.0;
    /* How long an answer planned before our last card may still be used, when no newer state
     * arrives - a fight where nothing moved plans again on LiveAdvice's heartbeat well inside it. */
    private static final long FRESH_MS = 1500;

    /* Tick thread only. The last card sent, in both clocks; the slot awaiting its release; and
     * the last reason shown, so a stuck state is said once rather than every frame. */
    private static double sentAt = -1;
    private static long sentWall = 0;
    private static int held = -1;
    private static Fightsess heldOn = null;
    private static String said = null;

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
                said = null;
                return;
            }
            Fightview fv = gui.fv;
            Fightsess fs = gui.fs;
            if((fv == null) || (fs == null) || (fv.current == null) || (fs.actions == null)) {
                sentAt = -1;
                said = null;
                return;
            }
            Fightview.Relation rel = fv.current;
            if((rel.gst & 1) != 0) {
                say(gui, "you offered peace to this one, so it is not attacked");
                return;
            }
            double now = Utils.rtime();
            if(now < fv.atkct)
                return;
            if((sentAt >= 0) && (fv.atkcs < sentAt) && ((now - sentAt) < ANSWER_S))
                return;
            LiveAdvice.Now n = LiveAdvice.get(rel.gobid);
            if(n == null)
                return;
            if(n.moveRes == null) {
                say(gui, "no card to throw - " + n.why);
                return;
            }
            if((n.observedAt < sentWall) && ((System.currentTimeMillis() - sentWall) < FRESH_MS))
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
            sentWall = System.currentTimeMillis();
            said = null;
        } catch(Exception e) {
            /* the bot must never break the tick loop */
        }
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
