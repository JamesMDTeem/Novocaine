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
    /* Opponents planned against at most: the target, ours, then the nearest others. It was 4, and
     * that was the advice's whole blind spot in a crowd: the 2026-09-19 bat dungeon put up to 26
     * on us at once, and planned against 4 the advice expected 25 soft hitpoints where 361 went.
     * Every one of these acts on us in every plan; only Prediction.TARGETS of them are searched as
     * the one to hit first, which is what keeps the cost in hand (2026-09-21). */
    private static final int CROWD = 32;
    /* How recently another person must have thrown a card to count as fighting beside us. */
    private static final long ON_US_WINDOW_MS = 10000;
    private static final int BEAM = 60;
    private static final long HORIZON = 2500;

    private static final class Foe {
        final long gob;
        final String res;
        final int[] open;
        final int ip, oip, gst;
        final double dist;
        final double taken;
        final double agiLo, agiHi;
        /* Seconds since it last acted, -1 when it has not yet - see Prediction.firstAct. */
        final double sinceAct;

        Foe(long gob, String res, int[] open, int ip, int oip, int gst, double dist, double taken,
            double agiLo, double agiHi, double sinceAct) {
            this.sinceAct = sinceAct;
            this.agiLo = agiLo;
            this.agiHi = agiHi;
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
        /* Where the opponent WE chose sits in `foes` - 0 unless the server moved `current`. */
        final int incumbent;
        /* The share of each creature's attacks aimed at us - Combatant.onUs, see ON_US_WINDOW_MS. */
        final double onUs;

        Job(Prediction.Me me, Map<String, Integer> bar, int[] mine, double shp, double mhp,
            List<Foe> foes, long observedAt, double observedRt, long readyIn, long generation,
            int incumbent, double onUs) {
            this.onUs = onUs;
            this.incumbent = incumbent;
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
    /* THE TARGET WE CHOSE, kept apart from the fight view's `current`, which the server moves to
     * a new aggro on its own - 38% of arrivals, a median 146 ms after the relation appears
     * (2026-09-21). Tick thread only; cleared with the fight. */
    private static long chosen = 0;
    private static long lastCurrent = 0;
    private static final Map<Long, Long> appeared = new java.util.HashMap<Long, Long>();
    /* A switch to a relation younger than this is the server's, not ours. The measured lag is a
     * median 146 ms and a p90 of 2.3 s - the slow tail is creatures still pathing in. */
    private static final long SERVER_SWITCH_MS = 2500;
    private static String barKey = null;
    private static Map<String, Integer> barDeck = null;
    /* The advice's inputs as last written to the log, so the row goes in on a change and not
     * every frame - see CombatEvent.advin. */
    private static String lastInputs = null;

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

    /**
     * Whether anything wants the PLAN - the ringed card or the auto-fighter - rather than only the
     * damage numbers. The numbers are one Prediction.of per card on the bar; the plan is a beam
     * search over every opponent in the fight plus a distill pass that costs tenths of a second
     * per new matchup, re-run on every change and every heartbeat. Damage prediction is on by
     * default and advice is not, so running the search for the numbers alone put that load on
     * every client in every fight: a report on 2026-09-24 of frame rates collapsing in fights
     * with advice and the auto-fighter both off.
     */
    static boolean planWanted() {
        haven.CheckBox advice = haven.OptWnd.combatMoveAdviceCheckBox;
        return(((advice != null) && advice.a) || AutoFighter.on());
    }

    /* Forgets the fight, when it ends or nothing wants advice. Tick thread. */
    private static void forget() {
        chosen = 0;
        lastCurrent = 0;
        appeared.clear();
        fightMe = null;
        nextMeTry = 0;
        lastKey = null;
        barKey = null;
        barDeck = null;
        lastInputs = null;
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
            /* OUR SIDE IS NOT BUILT ONCE AND KEPT. It was, and a snapshot taken in a frame where
             * the hands did not read - an item still loading, the equipment widget not there yet -
             * left us BARE-HANDED for the whole fight. Every card whose damage comes from the
             * weapon is then dropped from the deck (Prediction.needsWeapon), which leaves the
             * unarmed cards, and the advice spends the fight on one of those. So while no weapon
             * has resolved it is rebuilt each second, and a weapon swapped mid-fight is picked up
             * the same way. */
            if((fightMe == null) || !fightMe.armed) {
                if(wall >= nextMeTry) {
                    Prediction.Me m = CombatRecorder.buildMe(gui);
                    nextMeTry = wall + 1000;
                    if((m != null) && m.usable() && ((fightMe == null) || m.armed))
                        fightMe = m;
                }
                if(fightMe == null)
                    return;
            }

            List<haven.Buff> ours = new ArrayList<haven.Buff>(fv.buffs.children(haven.Buff.class));
            String[] stance = buffNames(ours);
            /* Our held stance decides our block weight - see Prediction.Me.buffs. */
            fightMe.buffs = stance;
            haven.combat.log.Openings mo = CombatRecorder.readOpenings(ours);
            int[] mine = {mo.green, mo.blue, mo.yellow, mo.red};
            Map<String, Integer> bar = bar(gui);
            /* What the advice is planning from, into the fight's own log, when any of it changes
             * - see CombatEvent.advin for why a log that could not say this was a problem. */
            String inputs = fightMe.armed + "|" + fightMe.weaponDamage + "|" + barKey + "|" + distilled;
            if(!inputs.equals(lastInputs)) {
                lastInputs = inputs;
                CombatRecorder.logAdviceInputs(fightMe.armed, fightMe.weapon(), fightMe.weaponDamage,
                                               (bar == null) ? null : bar.keySet(), distilled);
            }

            haven.Gob self = null;
            try {
                self = (gui.map == null) ? null : gui.ui.sess.glob.oc.getgob(gui.map.plgob);
            } catch(Exception e) {
                self = null;
            }
            /* Where we stand decides how big a creature is - a batcave bat against a mine bat is
             * eight times the size (Pack.Opponent.hpByTile). Read the way Fightview.tileUnder
             * reads it for the log; an unloaded grid leaves it as it was. */
            try {
                if(self != null)
                    fightMe.at(gui.ui.sess.glob.map.tileTypeName(
                                   gui.ui.sess.glob.map.gettile(self.rc.floor(haven.MCache.tilesz))));
            } catch(Exception e) {
                /* no tile: the kills over every tile stand in */
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
            /* WHO WE ARE FIGHTING BY CHOICE. A change of `current` to a relation that appeared
             * moments ago is the server handing us the newest aggro; anything else - our click,
             * the auto-fighter's bump, the old target gone - is a real change of target. */
            for(haven.Fightview.Relation rel : fv.lsrel) {
                if(!appeared.containsKey(rel.gobid))
                    appeared.put(rel.gobid, wall);
            }
            if(target.gob != lastCurrent) {
                Long born = appeared.get(target.gob);
                boolean servers = (chosen != 0) && (born != null)
                    && ((wall - born.longValue()) <= SERVER_SWITCH_MS);
                boolean stillHere = false;
                for(Foe f : others)
                    stillHere |= (f.gob == chosen);
                if(!(servers && stillHere))
                    chosen = target.gob;
                lastCurrent = target.gob;
            }
            if(chosen == 0)
                chosen = target.gob;
            List<Foe> foes = new ArrayList<Foe>();
            foes.add(target);
            int incumbent = 0;
            for(int i = 0; i < others.size(); i++) {
                if(others.get(i).gob == chosen) {
                    foes.add(others.get(i));
                    incumbent = 1;
                }
            }
            for(int i = 0; (i < others.size()) && (foes.size() < CROWD); i++) {
                if(others.get(i).gob != chosen)
                    foes.add(others.get(i));
            }

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
            long readyIn = (left > 0) ? (long)Math.ceil(haven.combat.Formulas.secondsToTicks(left)) : 0;
            /* ONE OF US AND N OTHERS FIGHTING: each creature's attacks are spread over all of us.
             * Replaying the 2026-09-21 bat-dungeon room with every creature staged at a share of
             * 1 predicted 3-7 times the soft damage that landed on each member; at one over the
             * four fighting it predicted 1.07 times. Solo it is 1, which changes nothing. */
            long selfGob = (gui.map == null) ? 0 : gui.map.plgob;
            double onUs = 1.0 / (1 + CombatRecorder.alliesFighting(selfGob, ON_US_WINDOW_MS));
            request(new Job(fightMe, bar, mine, shp, mhp, foes, wall, rt, readyIn, generation,
                            incumbent, onUs));
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
                           gst, dist, haven.GobDamageInfo.shpTaken(rel.gobid),
                           rel.minAgi, rel.maxAgi,
                           (rel.lastact == null) ? -1 : Math.max(0, haven.Utils.rtime() - rel.lastuse)));
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
            long t0 = System.nanoTime();
            try {
                boolean planning = planWanted();
                Now n = planning ? plan(job) : numbers(job);
                synchronized(lock) {
                    if(job.generation == generation)
                        current = n;
                }
                /* After the answer is out, not before it: choosing the cards for a new matchup
                 * costs a few tenths of a second once, and the first answer should not wait. */
                if(planning)
                    refine(job);
            } catch(Throwable t) {
                /* a plan that fails costs this answer, never the client */
            }
            /* Rest as long as the pass took before taking the next job. One pass against a pack
             * of eight bats measured 760 ms and 1.8 GB allocated (tools/PlanCost-style timing,
             * 2026-09-24); back to back, that allocation rate is what the collector cannot keep
             * up with, and the frames stall instead. Half a core at most; the next pass plans
             * from the newest fight state, since pending only ever holds the latest job. */
            long spent = (System.nanoTime() - t0) / 1000000;
            if(spent > 20) {
                try {
                    Thread.sleep(Math.min(spent, 2000));
                } catch(InterruptedException e) {
                    return;
                }
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
                                         (f.gst & 1) == 0, f.agiLo, f.agiHi).acted(f.sinceAct)
                         .aimed(job.onUs));
        }
        return(seen);
    }

    /* The cards chosen for the current matchup (Prediction.distill), and the matchup they were
     * chosen for. Written by the worker; cleared by forget(). */
    private static volatile String distillKey = null;
    private static volatile java.util.Set<String> distilled = null;

    /* A matchup is the bar and who is in the fight - the target, then the rest by kind. Openings,
     * health and distance move every second and do not change which cards are worth holding.
     *
     * THE FIRST FEW, NOT THE CROWD. Which cards to hold is a question about who we are hitting,
     * and it costs a search per candidate subset. Keyed on the whole crowd, every bat a denmother
     * spawns would be a new matchup and a fresh distill (2026-09-21, CROWD 4 -> 32). */
    private static final int MATCHUP = 4;

    private static String matchup(Job job) {
        StringBuilder k = new StringBuilder(String.valueOf(job.bar)).append('|');
        List<String> rest = new ArrayList<String>();
        for(int i = 0; i < Math.min(MATCHUP, job.foes.size()); i++) {
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
        List<Prediction.Seen> few = seen(job);
        if(few.size() > MATCHUP)
            few = new ArrayList<Prediction.Seen>(few.subList(0, MATCHUP));
        java.util.Set<String> cards = Prediction.distill(job.me, job.bar, job.mine, job.shp,
                                                         job.mhp, few, BEAM, HORIZON);
        if(job.generation != generation)
            return;
        distilled = cards;
        distillKey = key;
    }

    /** The damage each card on the bar would do to the target, and nothing else: no plan. */
    private static Now numbers(Job job) {
        Foe t = job.foes.get(0);
        Map<String, Double> dealt = dealt(job, t);
        return(new Now(t.gob, null, Collections.unmodifiableMap(dealt), System.currentTimeMillis(),
                       job.observedAt, job.observedRt, "damage numbers only", 0, 0, Double.NaN,
                       Double.NaN, t.gob, Prediction.shortName(t.res), Double.NaN, Double.NaN));
    }

    private static Map<String, Double> dealt(Job job, Foe t) {
        Map<String, Double> dealt = new LinkedHashMap<String, Double>();
        Iterable<String> cards = ((job.bar != null) && !job.bar.isEmpty())
            ? job.bar.keySet() : job.me.levels.keySet();
        for(String c : cards) {
            Prediction.Expect x = Prediction.of(job.me, t.res, c, t.open, t.ip, true);
            if(x != null)
                dealt.put(c, Double.valueOf(x.dealt));
        }
        return(dealt);
    }

    private static Now plan(Job job) {
        List<Prediction.Seen> seen = seen(job);
        java.util.Set<String> planCards = matchup(job).equals(distillKey) ? distilled : null;
        Foe t = job.foes.get(0);
        /* The card on screen for this target, held unless another is clearly better - see
         * Prediction.adviseLive. Only a fresh answer for the same target counts. */
        Now last = current;
        String held = ((last != null) && (last.gobId == t.gob) && (last.moveRes != null)
                       && ((System.currentTimeMillis() - last.at) <= STALE_MS)) ? last.moveRes : null;
        Prediction.Live live = Prediction.adviseLive(job.me, job.bar, job.mine, job.shp, job.mhp,
                                                     seen, BEAM, HORIZON, job.readyIn, planCards, held,
                                                     job.incumbent);
        Map<String, Double> dealt = dealt(job, t);
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
