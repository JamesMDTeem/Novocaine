package haven.automated.combat;

import haven.Fightsess;
import haven.GameUI;
import haven.Resource;
import haven.Widget;

/**
 * Throws one card, over and over, so a controlled spar measures one thing at a time.
 *
 * WHY SO LITTLE. The client already does most of what a combat bot would do: using a card
 * walks you into range, throws it, and keeps throwing it until another is chosen. So the
 * whole job here is to pick a slot and keep it picked - anything more elaborate would be
 * a second combat AI, and a second AI is exactly what a measurement must not have. A test
 * where one side is making decisions is a test of the decisions.
 *
 * That is also why the loop re-sends rather than sending once. A movement command cancels
 * the repeat, and in a spar plenty of things issue one; re-sending on the card's cooldown
 * means an interrupted run resumes instead of quietly stopping half way through and
 * leaving a log that looks like a finished measurement.
 *
 * WHAT IT DELIBERATELY DOES NOT DO. It does not choose targets, retreat, react, or switch
 * cards on a condition. Every test in data/combat/spar-tests.json is built so that one
 * side spams one card and the other holds still, because that is the only arrangement
 * where the number that comes out is a property of the card rather than of the bot.
 *
 * The recording is already handled: CombatRecorder writes both sides' openings,
 * initiative and damage for every step of any fight, so a spar needs no instrumentation
 * of its own. Switch this on, let it run, and read the log afterwards.
 */
public class SparBot implements Runnable {
    /** One bot at a time - two would fight over the same action bar. */
    private static SparBot running = null;

    private final GameUI gui;
    /** Action-bar slot to throw, or -1 to hold still and throw nothing. */
    private volatile int slot;
    /** How long to keep going, in milliseconds, so a forgotten bot stops by itself. */
    private final long until;
    private volatile boolean stop = false;

    private SparBot(GameUI gui, int slot, long seconds) {
        this.gui = gui;
        this.slot = slot;
        this.until = System.currentTimeMillis() + (seconds * 1000L);
    }

    /**
     * Start throwing the card in `slot`, or hold still when it is negative.
     *
     * The still side of a test is as much a part of it as the throwing side, and it is
     * not the same as simply not running the bot: a character with the bot on and no card
     * chosen is a character that will not wander off or retaliate.
     */
    public static synchronized void start(GameUI gui, int slot, long seconds) {
        stop();
        SparBot b = new SparBot(gui, slot, seconds);
        running = b;
        Thread t = new Thread(b, "spar-bot");
        t.setDaemon(true);
        t.start();
        say(gui, (slot < 0)
            ? ("spar bot: holding still for " + seconds + "s")
            : ("spar bot: throwing slot " + slot + " for " + seconds + "s"));
    }

    public static synchronized void stop() {
        if(running != null) {
            running.stop = true;
            running = null;
        }
    }

    public static synchronized boolean active() {
        return(running != null);
    }

    public void run() {
        try {
            while(!stop && (System.currentTimeMillis() < until)) {
                Fightsess fs = session();
                if(fs == null) {
                    /* Not in a fight yet. The spar is started by hand, so this just waits
                     * for that to happen rather than trying to start one. */
                    Thread.sleep(250);
                    continue;
                }
                if(slot >= 0)
                    throwCard(fs);
                Thread.sleep(200);
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized(SparBot.class) {
                if(running == this)
                    running = null;
            }
            say(gui, "spar bot: stopped");
        }
    }

    /**
     * Send the use, but only when the slot is actually ready.
     *
     * Spamming a card on cooldown is not harmless: every attempt is a message, and a
     * measurement that floods the server is a measurement taken under conditions nobody
     * will reproduce. The action carries its own cooldown window, so the check is local.
     */
    private void throwCard(Fightsess fs) {
        Fightsess.Action[] acts = fs.actions;
        if((acts == null) || (slot >= acts.length))
            return;
        Fightsess.Action a = acts[slot];
        if(a == null)
            return;
        double now = haven.Utils.rtime();
        if(now < a.ct)
            return;                     /* still cooling down */
        /* The same message the keyboard sends, with no modifiers and no map coordinate -
         * the no-hit branch, which is what pressing the key away from the ground does. */
        fs.wdgmsg("use", slot, 1, 0);
    }

    private Fightsess session() {
        if(gui == null)
            return(null);
        for(Widget w = gui.child; w != null; w = w.next) {
            if(w instanceof Fightsess)
                return((Fightsess)w);
        }
        return(null);
    }

    /** What is actually on the bar right now, so a test can be set up against it. */
    public static String bar(GameUI gui) {
        StringBuilder sb = new StringBuilder();
        Fightsess fs = null;
        for(Widget w = (gui == null) ? null : gui.child; w != null; w = w.next) {
            if(w instanceof Fightsess)
                fs = (Fightsess)w;
        }
        if(fs == null)
            return("not in a fight");
        Fightsess.Action[] acts = fs.actions;
        for(int i = 0; (acts != null) && (i < acts.length); i++) {
            String nm = "-";
            try {
                if((acts[i] != null) && (acts[i].res != null) && (acts[i].res.get() != null))
                    nm = acts[i].res.get().name;
            } catch(Resource.Loading l) {
                nm = "(loading)";
            }
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(i).append(':').append(nm);
        }
        return(sb.toString());
    }

    private static void say(GameUI gui, String s) {
        try {
            if(gui != null)
                gui.msg(s, java.awt.Color.WHITE);
        } catch(Exception e) {
            /* A bot that cannot print is still a bot. */
        }
    }
}
