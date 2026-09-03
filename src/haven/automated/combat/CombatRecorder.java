package haven.automated.combat;

import haven.Client;
import haven.OptWnd;
import haven.combat.log.CombatEvent;
import haven.combat.log.CombatLogWriter;
import haven.combat.log.Openings;

import haven.Equipory;
import haven.Glob;
import haven.ItemInfo;
import haven.WItem;
import haven.res.ui.tt.armor.Armor;
import haven.res.ui.tt.q.quality.Quality;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side collector for combat telemetry.
 *
 * This is the executor half of the pure haven.combat.log package: it is the only part that touches
 * haven types. Everything it writes is produced by CombatEvent, so the schema stays pinned by
 * tools/CombatLogCheck.java.
 *
 * Every entry point is a no-op unless the OptWnd toggle is on, and none of them ever throws.
 */
public final class CombatRecorder {
    private static final AtomicLong seq = new AtomicLong(0);
    private static volatile CombatLogWriter writer = null;
    private static volatile long t0 = 0;
    private static volatile String lastSample = null;
    private static volatile String lastFoes = null;
    private static volatile String lastBuffs = null;
    /* Our own side of the fight, built once from the same numbers the header is built from,
     * so a prediction can never describe a different character than the log says fought. */
    private static volatile Prediction.Me me = null;
    /* The last state sampled, held so that onMove can predict against the fight as it stood
     * BEFORE the move - which is the same rule the analysis uses when it reads a move's gain,
     * and for the same reason: the state that arrives after a move already contains it. */
    private static volatile int[] lastFoeOpen = null;
    private static volatile int lastMyIp = 0;
    private static volatile long lastFoeGob = -1;
    private static volatile String foeRes = null;
    /* Opponents whose resource has already been logged, so the tick loop names each one once.
     * Concurrent because it is written from the UI thread and cleared from start(), which the
     * message loop calls. */
    private static final java.util.Set<Long> named =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());

    private CombatRecorder() {}

    /* Checks alive(), not just non-null: a mid-run IO error kills the drain thread but leaves
     * `writer` set, so a plain null check would report healthy while writing nothing. */
    public static boolean active() {
        CombatLogWriter w = writer;
        return((w != null) && w.alive());
    }

    /** Milliseconds since this combat started - the timebase every event shares. */
    public static long now() {
        return(System.currentTimeMillis() - t0);
    }

    /**
     * The deck as it stands, card resource to level, read live off the fight window.
     *
     * The same place CombatDeckDump reads, and for the same reason: the level is what the
     * deck weighting is computed from, and the weighting divides every cooldown and
     * multiplies every attack weight. Reading the dump file instead would mean a
     * prediction depended on a probe having fired recently enough.
     */
    private static Map<String, Integer> readDeck(haven.GameUI gui) {
        Map<String, Integer> out = new java.util.LinkedHashMap<String, Integer>();
        try {
            if((gui == null) || (gui.chrwdg == null) || (gui.chrwdg.fight == null))
                return(out);
            /* A copy: ALL is mutated from the message loop as the server sends actions. */
            for(haven.FightWnd.Action a :
                    new ArrayList<haven.FightWnd.Action>(gui.chrwdg.fight.ALL)) {
                try {
                    if(a.u > 0)
                        out.put(a.res.get().name, a.u);
                } catch(Exception e) {
                    /* a still-loading action costs that card, not the deck */
                }
            }
        } catch(Exception e) {
        }
        return(out);
    }

    public static synchronized void start(String charName, long meGob, long foeGob, String foeRes,
                                          Glob glob, Equipory eq) {
        start(charName, meGob, foeGob, foeRes, glob, eq, null);
    }

    public static synchronized void start(String charName, long meGob, long foeGob, String foeRes,
                                          Glob glob, Equipory eq, haven.GameUI gui) {
        if(!OptWnd.combatTelemetryCheckBox.a)
            return;
        if(writer != null)
            stop("superseded");
        /* gameDir is only assigned on some launch paths; Paths.get(null, ...) throws NPE, and
         * relying on that to fall into the catch below would make control flow depend on an
         * exception in code whose entire job is to never disturb the client. */
        if(Client.gameDir == null)
            return;
        try {
            t0 = System.currentTimeMillis();
            lastSample = null;
            lastFoes = null;
            lastBuffs = null;
            named.clear();
            me = null;
            lastFoeOpen = null;
            lastMyIp = 0;
            lastFoeGob = -1;
            CombatRecorder.foeRes = foeRes;
            String safe = (charName == null ? "unknown" : charName.replaceAll("[^A-Za-z0-9_-]", "_"));
            Path p = Paths.get(Client.gameDir, "CombatLogs",
                               t0 + "-" + safe + "-" + seq.incrementAndGet() + ".jsonl");
            writer = new CombatLogWriter(p, 4096);
        } catch(Exception e) {
            writer = null;
            return;
        }
        /* The header is best-effort and deliberately separate from opening the file: a stat or
         * equipment read that trips over a still-loading resource must leave a log that still
         * records the fight, not no log at all. */
        try {
            List<String> gear = new ArrayList<String>();
            int[] arm = readGear(eq, gear);
            SortedMap<String, Integer> comp = readAttrs(glob, true);
            log(CombatEvent.begin(0, t0, CombatEvent.SCHEMA, charName, meGob, foeGob, foeRes,
                                  readAttrs(glob, false), comp, arm[0], arm[1]));
            for(String g : gear)
                log(g);
            /* The COMPUTED attributes, not the base ones: a prediction has to use the numbers
             * the server is actually fighting with, and food and buffs move them. */
            String[] hands = readHands(eq);
            me = Prediction.me(comp, arm[0], arm[1],
                               new String[] {hands[0], hands[2]},
                               new double[] {
                                   (hands[1] == null) ? 0 : Double.parseDouble(hands[1]),
                                   (hands[3] == null) ? 0 : Double.parseDouble(hands[3])},
                               readDeck(gui));
        } catch(Exception e) {
            /* a header we could not build is still better than a lost fight */
        }
    }

    /** Every attribute the server has sent, sorted so two logs diff cleanly. */
    private static SortedMap<String, Integer> readAttrs(Glob glob, boolean comp) {
        SortedMap<String, Integer> out = new TreeMap<String, Integer>();
        if(glob == null)
            return(out);
        try {
            for(Map.Entry<String, Glob.CAttr> e : glob.cattrs().entrySet())
                out.put(e.getKey(), comp ? e.getValue().comp : e.getValue().base);
        } catch(Exception e) {
        }
        return(out);
    }

    /**
     * Appends one gear event per equipped slot to `out` and returns {hard, soft}. Broken items
     * are reported with their nominal armour but excluded from the totals, matching what the
     * game actually applies (see Equipory's armour-class readout).
     */
    private static int[] readGear(Equipory eq, List<String> out) {
        int hard = 0, soft = 0;
        /* No equipment widget means we could not look, which is not the same fact as
         * wearing nothing. Report it as unknown so the analysis cannot read a blind spot
         * as a measurement of zero armour. */
        if(eq == null)
            return(new int[] {-1, -1});
        for(int i = 0; i < eq.slots.length; i++) {
            WItem w = eq.slots[i];
            if(w == null)
                continue;
            /* Per slot, so one still-loading item costs that slot and not the rest of the set. */
            try {
                String res = w.item.getres().name;
                double ql = 0;
                int h = 0, sf = 0;
                boolean broken = false;
                for(ItemInfo info : w.item.info()) {
                    if(info instanceof Quality)
                        ql = ((Quality)info).q;
                    else if(info instanceof Armor) {
                        h = ((Armor)info).hard;
                        sf = ((Armor)info).soft;
                    } else if(info instanceof haven.res.ui.tt.wear.Wear) {
                        haven.res.ui.tt.wear.Wear wr = (haven.res.ui.tt.wear.Wear)info;
                        broken = ((wr.m - wr.d) == 0);
                    }
                }
                if(!broken) {
                    hard += h;
                    soft += sf;
                }
                out.add(CombatEvent.gear(0, i, res, ql, h, sf, broken));
            } catch(Exception e) {
            }
        }
        return(new int[] {hard, soft});
    }

    /**
     * BOTH hands, as {res, ql} pairs - a slot we could not read comes back null.
     *
     * Both, and not the first one found, which is what this did and which quietly cost
     * every weapon-based prediction for anyone carrying a shield. Slot 6 held a round
     * shield and slot 7 the sword; the loop returned at slot 6, the shield resolved to no
     * weapon, and every weapon move then declined to predict - a silent, total failure
     * that looked exactly like a fight where nothing happened to be predictable.
     *
     * Nothing here decides which hand holds the weapon. Both resources go to
     * {@link Prediction}, which looks each up in the weapon table and takes whichever one
     * is in it - so a shovel, a shield and an empty hand all fall out on the same rule,
     * and the judgement stays in the data rather than in a list of resource names
     * maintained here.
     */
    private static String[] readHands(Equipory eq) {
        String[] out = new String[] {null, null, null, null};
        if(eq == null)
            return(out);
        for(int i = 6; (i <= 7) && (i < eq.slots.length); i++) {
            WItem w = eq.slots[i];
            if(w == null)
                continue;
            try {
                double ql = 0;
                for(ItemInfo info : w.item.info()) {
                    if(info instanceof Quality)
                        ql = ((Quality)info).q;
                }
                out[(i - 6) * 2] = w.item.getres().name;
                out[((i - 6) * 2) + 1] = Double.toString(ql);
            } catch(Exception e) {
            }
        }
        return(out);
    }

    public static void log(String line) {
        CombatLogWriter w = writer;
        if(w != null)
            w.offer(line);
    }

    /** The tooltip the combat bar renders, or null if the resource has no tooltip layer. */
    public static String moveName(haven.Resource res) {
        try {
            haven.Resource.Tooltip tt = res.layer(haven.Resource.tooltip);
            return((tt == null) ? null : tt.t);
        } catch(Exception e) {
            return(null);
        }
    }

    /**
     * Writes down what the model expected this move to do, before anything happens.
     *
     * Ours only. An opponent's move is thrown with an attack weight and a deck no log records,
     * so there is nothing to predict from - and a prediction against invented inputs would
     * enter the residuals as a model error rather than as the gap it is.
     *
     * Silent whenever any input is unresolved. That is most of the time today: 27 of 35
     * opponents in the pack have no recovered combat skill, so the model has nothing to
     * predict against them WITH. A prediction from defaults would be worse than none, because
     * it would look exactly like a measurement.
     */
    private static void predict(String actor, String moveRes, long gobId) {
        if(!"me".equals(actor))
            return;
        Prediction.Me m = me;
        int[] open = lastFoeOpen;
        /* The cached state has to belong to the opponent this move was aimed at. A target
         * switch between the last sample and this move would otherwise predict against the
         * previous creature's openings. */
        if((m == null) || (open == null) || (gobId != lastFoeGob))
            return;
        Prediction.Expect e = Prediction.of(m, foeRes, moveRes, open, lastMyIp);
        if(e == null)
            return;
        log(CombatEvent.predict(now(), gobId, moveRes, e.pack, e.opened, e.dealt,
                                e.grievous, e.cooldown));
    }

    /**
     * Records an opponent appearing, leaving, or becoming the one being sampled.
     *
     * See {@link CombatEvent#foe} for why: the header names one opponent and the client samples
     * one relation, so without this a multi-opponent fight reads as a single opponent whose
     * openings jump for no reason.
     */
    public static void onFoe(long gobId, String res, String how) {
        if(!active())
            return;
        try {
            /* The pack is keyed by species, so the resource of whichever opponent is being
             * sampled is what a prediction needs. Following the sampled one rather than the
             * header's means a retargeted fight predicts against the creature in front of us. */
            if((res != null) && !"gone".equals(how))
                foeRes = res;
            log(CombatEvent.foe(now(), gobId, res, how));
        } catch(Exception e) {
            /* never propagate into the message loop */
        }
    }

    /**
     * Names an opponent the first time its resource resolves, and never again.
     *
     * A relation can arrive before the gob it refers to, and then its res reads null - which is
     * how a ninety-second three-opponent fight came to identify none of them. This runs off the
     * tick loop, so it costs a set lookup per opponent per frame and writes one line per fight
     * per opponent.
     */
    public static void nameFoe(long gobId, String res) {
        if(!active() || (res == null))
            return;
        if(!named.add(gobId))
            return;
        onFoe(gobId, res, "name");
    }

    public static void onMove(String actor, String moveRes, String moveName,
                              double cooldownTicks, long gobId) {
        if(!active())
            return;
        try {
            log(CombatEvent.move(now(), actor, moveRes, moveName, cooldownTicks, gobId));
            predict(actor, moveRes, gobId);
        } catch(Exception e) {
            /* never propagate into the message loop */
        }
    }

    /* The "colour code" on a damage message is a packed RGBA4444 value - GobDamageInfo decodes
     * these same constants with Utils.col16 - so 61455 is 0xf00f, opaque red, and so on. 35071
     * (0x88ff, blue) is Initiative, which the client does not render but which is logged here:
     * it is a free signal and cannot be recovered retroactively. Anything unrecognised is
     * reported as its hex colour rather than a decimal, because the colour is the only clue to
     * what it means: 0xffff, opaque white, turns up once per kill on the killer's own gob. */
    private static String channel(int c) {
        switch(c) {
        case 61455: return("SHP");
        case 64527: return("HHP");
        case 36751: return("ARM");
        case 35071: return("IP");
        default:    return(String.format(java.util.Locale.ROOT, "#%04x", c));
        }
    }

    public static void onDamage(long gobId, int colourCode, int value) {
        if(!active())
            return;
        try {
            log(CombatEvent.damage(now(), gobId, channel(colourCode), value));
        } catch(Exception e) {
            /* never propagate into the object-delta path */
        }
    }

    public static void sample(Openings mine, Openings foe, int myIp, int foeIp,
                              int hp, double stam, double energy, double dist, long gobId,
                              double mySpeed, double foeSpeed, int gst, String tile) {
        if(!active())
            return;
        try {
            /* Gate on the value, not the clock: openings change rarely relative to frame rate,
             * and a per-frame stream would bloat the log without adding information. Distance IS
             * part of the key, quantised to whole units: the dominant player strategy against
             * animals is strike, withdraw out of attack range to restore openings for free, then
             * re-engage. During that withdrawal openings/IP/HP are all static, so without distance
             * in the key no sample would fire at all and the range trace - the signal that
             * actually explains the animal's behaviour - would be unrecoverable. The emitted event
             * still carries full-precision dist; only the gate key is quantised. The foe gob id is
             * also part of the key so a target switch always emits a fresh sample instead of being
             * suppressed as an unchanged state. */
            /* Speed is part of the key, quantised, because starting or stopping is a state
             * change worth a sample even when nothing else moved - and because the whole
             * point of recording it is to catch the moments we were actually withdrawing. */
            String key = gobId + ":" + mine.toJson() + foe.toJson() + myIp + ":" + foeIp
                + ":" + hp + ":" + (long)dist + ":" + (long)mySpeed + ":" + (long)foeSpeed
                + ":" + gst + ":" + tile;
            if(key.equals(lastSample))
                return;
            lastSample = key;
            lastFoeOpen = new int[] {foe.green, foe.blue, foe.yellow, foe.red};
            lastMyIp = myIp;
            lastFoeGob = gobId;
            log(CombatEvent.state(now(), mine, foe, myIp, foeIp, hp, stam, energy, dist, gobId,
                                  mySpeed, foeSpeed, gst, tile));
        } catch(Exception e) {
            /* never propagate into tick() */
        }
    }

    /**
     * Every opponent's openings, sampled together.
     *
     * Separate from sample() because it answers a different question. sample() records the
     * duel we are in; this records who ELSE is being opened, which is the only evidence a log
     * carries about another player's attacks - their moves never enter our fightview and so
     * are never logged at all. A rise on a creature we are not hitting is proof that somebody
     * else is swinging, and that is what decides whether a gain on our own target was ours.
     *
     * Gated on the values, like sample(), because these change rarely against a frame rate.
     *
     * @param packed gob and four openings per relation, five entries each
     */
    public static void sampleFoes(long[] packed) {
        if(!active() || (packed == null) || (packed.length == 0))
            return;
        try {
            StringBuilder k = new StringBuilder();
            for(long v : packed)
                k.append(v).append(':');
            String key = k.toString();
            if(key.equals(lastFoes))
                return;
            lastFoes = key;
            log(CombatEvent.foes(now(), packed));
        } catch(Exception e) {
            /* never propagate into tick() */
        }
    }

    /**
     * An overlay appearing on another player's body.
     *
     * A player's combat move is announced by a brief icon over them, and nothing else in a
     * log says what somebody other than us did - their moves are not in our fightview. Until
     * the resource that carries the icon is identified from real fights, every overlay on a
     * player gob is recorded and the analysis side decides which one matters.
     *
     * Not value-gated: an overlay is an event, and two identical ones in a row are two moves.
     * The player filter is what keeps the volume down.
     */
    public static void onGobOverlay(long gobId, String gobRes, String olRes) {
        if(!active() || (gobRes == null) || (olRes == null))
            return;
        try {
            log(CombatEvent.overlay(now(), gobId, gobRes, olRes));
        } catch(Exception e) {
            /* never propagate into the object-delta path */
        }
    }

    /**
     * The buff resources standing on a combatant - which is where a stance lives.
     *
     * readOpenings below walks this same list and keeps only the four opening paginae, so
     * a stance card has been visible on every sample and discarded every time. It is the
     * missing term in an opponent's defence weight: skill x block multiplier x mu, and the
     * block multiplier runs from Bloodlust's 75% of Unarmed to Shield Up's 250% of Melee.
     * Without it, players in this corpus measure anywhere from 3 to 393 and the number
     * gets recorded as a property of the person.
     *
     * Value-gated per combatant, since buffs change rarely against a frame rate.
     */
    public static void sampleBuffs(long gobId, String who,
                                   java.util.Collection<haven.Buff> buffs) {
        if(!active() || (buffs == null))
            return;
        try {
            java.util.List<String> names = new java.util.ArrayList<String>();
            for(haven.Buff b : buffs) {
                try {
                    if((b.res != null) && (b.res.get() != null))
                        names.add(b.res.get().name);
                } catch(Exception e) {
                    /* a still-loading resource is skipped, not fatal */
                }
            }
            if(names.isEmpty())
                return;
            java.util.Collections.sort(names);
            String key = who + gobId + names;
            if(key.equals(lastBuffs))
                return;
            lastBuffs = key;
            log(CombatEvent.buffs(now(), gobId, who,
                                  names.toArray(new String[names.size()])));
        } catch(Exception e) {
            /* never propagate into tick() */
        }
    }

    public static Openings readOpenings(java.util.Collection<haven.Buff> buffs) {
        int g = 0, b = 0, y = 0, r = 0;
        for(haven.Buff buff : buffs) {
            try {
                if((buff.res == null) || (buff.res.get() == null))
                    continue;
                Double m = buff.ameteri.get();
                if(m == null)
                    continue;
                int v = (int)(100 * m);
                String n = buff.res.get().name;
                if(n.equals("paginae/atk/offbalance"))     g = v;
                else if(n.equals("paginae/atk/dizzy"))     b = v;
                else if(n.equals("paginae/atk/reeling"))   y = v;
                else if(n.equals("paginae/atk/cornered"))  r = v;
            } catch(Exception e) {
                /* a still-loading resource is skipped, not fatal */
            }
        }
        return(new Openings(g, b, y, r));
    }

    public static synchronized void stop(String outcome) {
        CombatLogWriter w = writer;
        if(w == null)
            return;
        try {
            w.offer(CombatEvent.end(now(), outcome));
        } catch(Exception e) {
            /* never propagate */
        }
        writer = null;
        try {
            w.close();
        } catch(Exception e) {
            /* never propagate */
        }
    }
}
