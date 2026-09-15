package haven.automated.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The advisor's answer, kept current for the fight view to draw and the auto-fighter to act on.
 *
 * READ FROM THE FIGHT VIEW, NOT FROM THE RECORDER. The first version planned from the recorder's
 * samples, and those exist only while Record Combat Telemetry is on and describe the one relation
 * the client samples. {@link #observe} reads the fight itself every frame instead - our openings
 * and hitpoints, every opponent's openings, initiative on both sides, distance and the damage
 * already drawn on it, and the cards actually on the bar - so advice needs no logging and a crowd
 * plans as a crowd.
 *
 * WHY A WORKER. The search is a beam over the whole deck, run once per opponent we might aim at,
 * and observe() runs on the tick loop. Requests are coalesced - only the newest state is ever
 * planned - so a slow search skips states rather than queueing them, and the fight view reads a
 * finished answer without waiting on anything.
 *
 * NOT THE AUDITED ADVICE, DELIBERATELY. The log's advice ({@link CombatRecorder}'s advise) stays the
 * pure question - fastest kill, known opponents only - because it is scored against what a person
 * threw. This one has to answer in every fight and keep us standing, so it goes through
 * {@link Prediction#adviseLive}: a stand-in for a creature the pack does not know, whatever deck is
 * on the bar, a reserve of our hitpoints, the target worth hitting, and the next blow. An answer
 * that leaned on a stand-in says so in {@link Now#proxied}, and the fight view draws it amber.
 *
 * Nothing here is written to the log and nothing here acts - {@link AutoFighter} does that.
 */
public final class LiveAdvice {
    private LiveAdvice() {}

    /** One finished answer, planned while one opponent was the fight view's target. */
    public static final class Now {
        /** The relation that was the target when the fight was read. */
        public final long gobId;
        /**
         * The card to throw at that target next, or null - when there is no plan, when the plan
         * is to back off, or when it wants a different target first. {@link #why} says which.
         */
        public final String moveRes;
        /**
         * Soft hitpoints the model expects each card on the bar to take off the target from the
         * state planned, keyed by card resource. Priced only against a creature the pack knows -
         * never through the stand-in - so an absent card means "no answer", not zero.
         */
        public final Map<String, Double> dealt;
        /** Wall time the answer was finished, for staleness. */
        public final long at;
        /** Wall time of the fight state it was planned from, and the same moment on the client's render clock. */
        public final long observedAt;
        public final double observedRt;
        public final String why;
        /** Opponents planned against, and how many of them through a stand-in. */
        public final int planned, proxied;
        /** What the chosen plan expects to cost us, and what it was allowed to. */
        public final double hpLost, budget;
        /** The opponent the plan attacks first - {@link #gobId} unless it wants a switch - and its name. */
        public final long targetGob;
        public final String targetName;
        /** The worst blow that could land on our openings as they stood, and its cap; NaN when unknown. */
        public final double danger, dangerCap;

        Now(long gobId, String moveRes, Map<String, Double> dealt, long at, long observedAt,
            double observedRt, String why, int planned, int proxied, double hpLost, double budget,
            long targetGob, String targetName, double danger, double dangerCap) {
            this.gobId = gobId;
            this.moveRes = moveRes;
            this.dealt = dealt;
            this.at = at;
            this.observedAt = observedAt;
            this.observedRt = observedRt;
            this.why = why;
            this.planned = planned;
            this.proxied = proxied;
            this.hpLost = hpLost;
            this.budget = budget;
            this.targetGob = targetGob;
            this.targetName = targetName;
            this.danger = danger;
            this.dangerCap = dangerCap;
        }

        /** Whether the plan wants a different opponent aimed at before anything is thrown. */
        public boolean wantsSwitch() {
            return((targetGob != 0) && (targetGob != gobId));
        }
    }

    /* An answer older than this is from a fight observe() has stopped describing. */
    private static final long STALE_MS = 3000;
    /* How often an unchanged fight is planned again. A change plans at once; this bounds how old
     * the answer can be when the auto-fighter's cooldown ends in a fight where nothing moved. */
    private static final long HEARTBEAT_MS = 250;
    /* Opponents planned against at most: the target and the nearest others. A cost cap, the same
     * one the logged advice carries - the search walks every opponent at every step, and it now
     * runs once per opponent we might aim at. */
    private static final int CROWD = 4;
    private static final int BEAM = 60;
    private static final long HORIZON = 2500;

    private static final class Foe {
        final long gob;
        final String res;
        final int[] open;
        final int ip, oip, gst;
        final double dist;
        final double taken;

        Foe(long gob, String res, int[] open, int ip, int oip, int gst, double dist, double taken) {
            this.gob = gob;
            this.res = res;
            this.open = open;
            this.ip = ip;
            this.oip = oip;
            this.gst = gst;
            this.dist = dist;
            this.taken = taken;
        }
    }

    private static final class Job {
        final Prediction.Me me;
        final Map<String, Integer> bar;
        final int[] mine;
        final double shp, mhp;
        final List<Foe> foes;
        final long observedAt;
        final double observedRt;
        /* Ticks until our cooldown ends, as the fight view had it. */
        final long readyIn;
        final long generation;

        Job(Prediction.Me me, Map<String, Integer> bar, int[] mine, double shp, double mhp,
            List<Foe> foes, long observedAt, double observedRt, long readyIn, long generation) {
            this.me = me;
            this.bar = bar;
            this.mine = mine;
            this.shp = shp;
            this.mhp = mhp;
            this.foes = foes;
            this.observedAt = observedAt;
            this.observedRt = observedRt;
            this.readyIn = readyIn;
            this.generation = generation;
        }
    }

    private static final Object lock = new Object();
    private static Job pending = null;
    private static Thread worker = null;
    /* Bumped by forget(), so an answer planned for the fight that just ended is thrown away
     * rather than drawn over the next one. */
    private static volatile long generation = 0;
    private static volatile Now current = null;

    /* The fight being observed - tick thread only. Our side is built once per fight, like the
     * recorder's, and retried each second until the character sheet and gear have loaded. */
    private static Prediction.Me fightMe = null;
    private static long nextMeTry = 0;
    private static String lastKey = null;
    private static long lastRequest = 0;
    private static String barKey = null;
    private static Map<String, Integer> barDeck = null;

    /**
     * The answer for this target, or null when there is none, it is stale, or it was made while
     * someone else was the target.
     */
    public static Now get(long gobId) {
        Now n = current;
        if((n == null) || (n.gobId != gobId))
            return(null);
        if((System.currentTimeMillis() - n.at) > STALE_MS)
            return(null);
        return(n);
    }

    /** Whether anything wants an answer at all: the drawn advice, the damage numbers, or the bot. */
    public static boolean wanted() {
        haven.CheckBox advice = haven.OptWnd.combatMoveAdviceCheckBox;
        haven.CheckBox damage = haven.OptWnd.showDamagePredictUICheckBox;
        return(((advice != null) && advice.a) || ((damage != null) && damage.a)
               || AutoFighter.on());
    }

    /* Forgets the fight, when it ends or nothing wants advice. Tick thread. */
    private static void forget() {
        fightMe = null;
        nextMeTry = 0;
        lastKey = null;
        barKey = null;
        barDeck = null;
        distillKey = null;
        distilled = null;
        synchronized(lock) {
            generation++;
            pending = null;
            current = null;
        }
    }

    /**
     * Reads the fight as it stands and asks for a plan when it changed. Called from
     * Fightview.tick every frame; never blocks, and never throws into the tick loop.
     */
    public static void observe(haven.GameUI gui, haven.Fightview fv) {
        try {
            if((gui == null) || (fv == null))
                return;
            if(fv.lsrel.isEmpty() || (fv.current == null) || !wanted()) {
                if((fightMe != null) || (current != null) || (lastKey != null))
                    forget();
                return;
            }
            long wall = System.currentTimeMillis();
            if(fightMe == null) {
                if(wall < nextMeTry)
                    return;
                Prediction.Me m = CombatRecorder.buildMe(gui);
                if((m == null) || !m.usable()) {
                    nextMeTry = wall + 1000;
                    return;
                }
                fightMe = m;
            }

            List<haven.Buff> ours = new ArrayList<haven.Buff>(fv.buffs.children(haven.Buff.class));
            String[] stance = buffNames(ours);
            /* Our held stance decides our block weight - see Prediction.Me.buffs. */
            fightMe.buffs = stance;
            haven.combat.log.Openings mo = CombatRecorder.readOpenings(ours);
            int[] mine = {mo.green, mo.blue, mo.yellow, mo.red};
            Map<String, Integer> bar = bar(gui);

            haven.Gob self = null;
            try {
                self = (gui.map == null) ? null : gui.ui.sess.glob.oc.getgob(gui.map.plgob);
            } catch(Exception e) {
                self = null;
            }
            Foe target = null;
            List<Foe> others = new ArrayList<Foe>();
            for(haven.Fightview.Relation rel : fv.lsrel) {
                Foe f = foe(gui, self, rel);
                if(f == null)
                    continue;
                if(rel == fv.current)
                    target = f;
                else
                    others.add(f);
            }
            if(target == null)
                return;
            /* The nearest others first, and the ones we cannot place last: a sweep reaches the
             * near ones, and the near ones are the ones about to swing. */
            Collections.sort(others, (x, y) -> Double.compare(
                                 Double.isNaN(x.dist) ? Double.MAX_VALUE : x.dist,
                                 Double.isNaN(y.dist) ? Double.MAX_VALUE : y.dist));
            List<Foe> foes = new ArrayList<Foe>();
            foes.add(target);
            for(int i = 0; (i < others.size()) && (foes.size() < CROWD); i++)
                foes.add(others.get(i));

            double shp = haven.IMeter.characterShp, mhp = haven.IMeter.characterMhp;
            StringBuilder k = new StringBuilder();
            k.append(target.gob).append('|').append(mine[0]).append(',').append(mine[1])
                .append(',').append(mine[2]).append(',').append(mine[3]).append('|')
                .append(shp).append('/').append(mhp).append('|').append(barKey).append('|');
            for(String s : stance)
                k.append(s).append(',');
            for(Foe f : foes) {
                k.append('|').append(f.gob).append(':').append(f.open[0]).append(',')
                    .append(f.open[1]).append(',').append(f.open[2]).append(',')
                    .append(f.open[3]).append(':').append(f.ip).append('/').append(f.oip)
                    .append(':').append(f.gst).append(':')
                    .append(Double.isNaN(f.dist) ? -1 : (long)f.dist).append(':')
                    .append((long)f.taken);
            }
            String key = k.toString();
            if(key.equals(lastKey) && ((wall - lastRequest) < HEARTBEAT_MS))
                return;
            lastKey = key;
            lastRequest = wall;
            /* Planned for when our cooldown ends - the auto-fighter picks the card during it. Left
             * out of the key: it changes every frame, and the heartbeat carries it forward. */
            double rt = haven.Utils.rtime();
            double left = fv.atkct - rt;
            long readyIn = (left > 0) ? (long)Math.ceil(left / 0.06) : 0;
            request(new Job(fightMe, bar, mine, shp, mhp, foes, wall, rt, readyIn, generation));
        } catch(Exception e) {
            /* advice must never break the tick loop */
        }
    }

    private static String[] buffNames(List<haven.Buff> buffs) {
        List<String> names = new ArrayList<String>();
        for(haven.Buff b : buffs) {
            try {
                if((b.res != null) && (b.res.get() != null))
                    names.add(b.res.get().name);
            } catch(Exception e) {
                /* a still-loading buff is skipped */
            }
        }
        Collections.sort(names);
        return(names.toArray(new String[names.size()]));
    }

    /**
     * The cards on the bar, each at the level the fight window holds it at (0 when it does not
     * say). Re-read only when what is on the bar changes, which is a deck switch.
     */
    private static Map<String, Integer> bar(haven.GameUI gui) {
        haven.Fightsess fs = gui.fs;
        if((fs == null) || (fs.actions == null))
            return(null);
        List<String> names = new ArrayList<String>();
        for(haven.Fightsess.Action a : fs.actions) {
            if(a == null)
                continue;
            try {
                names.add(a.res.get().name);
            } catch(Exception e) {
                /* a card still loading is left off until it has */
            }
        }
        String key = names.toString();
        if(!key.equals(barKey)) {
            Map<String, Integer> levels = CombatRecorder.readDeck(gui);
            Map<String, Integer> out = new LinkedHashMap<String, Integer>();
            for(String n : names) {
                Integer l = levels.get(n);
                out.put(n, Integer.valueOf((l == null) ? 0 : l.intValue()));
            }
            barDeck = Collections.unmodifiableMap(out);
            barKey = key;
        }
        return(barDeck);
    }

    private static Foe foe(haven.GameUI gui, haven.Gob self, haven.Fightview.Relation rel) {
        try {
            haven.Gob g = gui.ui.sess.glob.oc.getgob(rel.gobid);
            if((g == null) || (g.getres() == null))
                return(null);
            haven.combat.log.Openings o =
                CombatRecorder.readOpenings(rel.buffs.children(haven.Buff.class));
            double dist = (self == null) ? Double.NaN : self.getc().dist(g.getc());
            /* A peace offer Auto-Reaggro or Auto Peace Animals made is a tactic, not the player
             * ending the fight, so it does not take the opponent out of the targets - see
             * AutoFighter.peaceIsTactic. */
            int gst = AutoFighter.peaceIsTactic(rel) ? (rel.gst & ~1) : rel.gst;
            return(new Foe(rel.gobid, g.getres().name,
                           new int[] {o.green, o.blue, o.yellow, o.red}, rel.ip, rel.oip,
                           gst, dist, haven.GobDamageInfo.shpTaken(rel.gobid)));
        } catch(Exception e) {
            /* an opponent whose gob or resource has not arrived is left out of this plan */
            return(null);
        }
    }

    /**
     * Asks for a plan from this state. Never blocks: the newest request replaces any that has
     * not started, and one already running finishes and is then superseded.
     */
    private static void request(Job job) {
        synchronized(lock) {
            if(job.generation != generation)
                return;
            pending = job;
            if(worker == null) {
                worker = new Thread(LiveAdvice::run, "combat-advice");
                worker.setDaemon(true);
                worker.setPriority(Thread.NORM_PRIORITY - 1);
                worker.start();
            }
            lock.notifyAll();
        }
    }

    private static void run() {
        while(true) {
            Job job;
            synchronized(lock) {
                while(pending == null) {
                    try {
                        lock.wait();
                    } catch(InterruptedException e) {
                        return;
                    }
                }
                job = pending;
                pending = null;
            }
            try {
                Now n = plan(job);
                synchronized(lock) {
                    if(job.generation == generation)
                        current = n;
                }
                /* After the answer is out, not before it: choosing the cards for a new matchup
                 * costs a few tenths of a second once, and the first answer should not wait. */
                refine(job);
            } catch(Throwable t) {
                /* a plan that fails costs this answer, never the client */
            }
        }
    }

    private static List<Prediction.Seen> seen(Job job) {
        List<Prediction.Seen> seen = new ArrayList<Prediction.Seen>();
        for(Foe f : job.foes) {
            /* A person's cards as seen, looked up here rather than on the tick thread because
             * the first lookup reads the remembered decks from disk. */
            Map<String, Integer> deck = Prediction.isPlayerRes(f.res)
                ? CombatRecorder.seenDeck(f.gob) : null;
            /* Bit 1 of the relation state is OUR olive branch: offered peace, so not a target. */
            seen.add(new Prediction.Seen(f.gob, f.res, f.open, f.ip, f.oip, f.dist, f.taken, deck,
                                         (f.gst & 1) == 0));
        }
        return(seen);
    }

    /* The cards chosen for the current matchup (Prediction.distill), and the matchup they were
     * chosen for. Written by the worker; cleared by forget(). */
    private static volatile String distillKey = null;
    private static volatile java.util.Set<String> distilled = null;

    /* A matchup is the bar and who is in the fight - the target, then the rest by kind. Openings,
     * health and distance move every second and do not change which cards are worth holding. */
    private static String matchup(Job job) {
        StringBuilder k = new StringBuilder(String.valueOf(job.bar)).append('|');
        List<String> rest = new ArrayList<String>();
        for(int i = 0; i < job.foes.size(); i++) {
            if(i == 0)
                k.append(job.foes.get(0).res);
            else
                rest.add(job.foes.get(i).res);
        }
        Collections.sort(rest);
        return(k.append('|').append(rest).toString());
    }

    /** Chooses the cards for this matchup once, when it is new. Worker thread. */
    private static void refine(Job job) {
        String key = matchup(job);
        if(key.equals(distillKey) || (job.generation != generation))
            return;
        java.util.Set<String> cards = Prediction.distill(job.me, job.bar, job.mine, job.shp,
                                                         job.mhp, seen(job), BEAM, HORIZON);
        if(job.generation != generation)
            return;
        distilled = cards;
        distillKey = key;
    }

    private static Now plan(Job job) {
        List<Prediction.Seen> seen = seen(job);
        java.util.Set<String> planCards = matchup(job).equals(distillKey) ? distilled : null;
        Prediction.Live live = Prediction.adviseLive(job.me, job.bar, job.mine, job.shp, job.mhp,
                                                     seen, BEAM, HORIZON, job.readyIn, planCards);
        Foe t = job.foes.get(0);
        Map<String, Double> dealt = new LinkedHashMap<String, Double>();
        Iterable<String> cards = ((job.bar != null) && !job.bar.isEmpty())
            ? job.bar.keySet() : job.me.levels.keySet();
        for(String c : cards) {
            Prediction.Expect x = Prediction.of(job.me, t.res, c, t.open, t.ip, true);
            if(x != null)
                dealt.put(c, Double.valueOf(x.dealt));
        }
        Foe aim = ((live.target >= 0) && (live.target < job.foes.size()))
            ? job.foes.get(live.target) : t;
        /* A card planned against another target is not a card to throw at this one. */
        String move = (aim == t) ? live.moveRes : null;
        return(new Now(t.gob, move, Collections.unmodifiableMap(dealt),
                       System.currentTimeMillis(), job.observedAt, job.observedRt, live.why,
                       live.planned,
                       live.proxied, live.hpLost, live.budget, aim.gob,
                       Prediction.shortName(aim.res), live.danger, live.dangerCap));
    }
}
