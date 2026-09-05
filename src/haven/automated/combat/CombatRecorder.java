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
    private static volatile Path curPath = null;
    private static volatile long t0 = 0;
    private static volatile String lastSample = null;
    /* When the last state line went out, for the heartbeat in sample(). */
    private static volatile long lastBeat = 0;
    private static volatile String lastFoes = null;
    /* Per combatant, keyed by who+gob. A single shared key was wrong in both directions,
     * and which one it was wrong in depended on how many combatants carried buffs.
     *
     * With two or more writers - a foe holding a stance, then us - the tick loop's
     * alternation meant a key never equalled its successor and nothing was ever
     * suppressed: 29 of the 30 schema-8 logs carry more buffs lines than states, the
     * worst 20734 against 220. With ONE writer the same key suppressed correctly, which
     * is the thirtieth log: a foe with no buffs returns early, leaving only "me", and it
     * carries 2 buffs lines against 33 states.
     *
     * So the gate was not merely noisy - it was noisy or lossy according to something
     * neither the gate nor the reader could see. Per combatant, it is one thing. */
    private static final java.util.Map<String, String> lastBuffs =
        new java.util.concurrent.ConcurrentHashMap<String, String>();
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
    /* Everyone in this fight - every relation, plus us. The health delta is a WORLD
     * channel: it carries object damage state for walls and trees as readily as a
     * creature's hitpoints, so without this the log would fill with the scenery. Written
     * from the message loop and read from the object-delta path, hence concurrent. */
    private static final java.util.Set<Long> combatants =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    /* Last health seen per gob, so a delta that restates the same quarter costs nothing.
     * Health arrives far more often than it changes. */
    private static final java.util.Map<Long, Integer> lastHp =
        new java.util.concurrent.ConcurrentHashMap<Long, Integer>();
    /* Per opponent, so one creature's narrowing bracket never suppresses another's - the
     * fault the single shared buffs key had. */
    private static final java.util.Map<Long, String> lastAgi =
        new java.util.concurrent.ConcurrentHashMap<Long, String>();
    private static volatile String lastAtkRes = null;

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
            /* Reset with the rest of the sampler state, or the first fight after a
             * long pause opens with a heartbeat it did not earn. */
            lastBeat = 0;
            lastFoes = null;
            lastBuffs.clear();
            named.clear();
            combatants.clear();
            lastHp.clear();
            lastAgi.clear();
            lastAtkRes = null;
            if(meGob >= 0)
                combatants.add(meGob);
            if(foeGob >= 0)
                combatants.add(foeGob);
            me = null;
            lastFoeOpen = null;
            lastMyIp = 0;
            lastFoeGob = -1;
            CombatRecorder.foeRes = foeRes;
            String safe = (charName == null ? "unknown" : charName.replaceAll("[^A-Za-z0-9_-]", "_"));
            Path p = Paths.get(Client.gameDir, "CombatLogs",
                               t0 + "-" + safe + "-" + seq.incrementAndGet() + ".jsonl");
            writer = new CombatLogWriter(p, 4096);
            curPath = p;
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
            /* Who is with us. Changes no gate - see CombatEvent.party - but it is what
             * makes "was that a friend or a stranger" a question the corpus can answer
             * rather than one it has to assume. */
            try {
                if((glob != null) && (glob.party != null)) {
                    java.util.List<Long> ps = new ArrayList<Long>(glob.party.memb.keySet());
                    if(!ps.isEmpty()) {
                        long[] arr = new long[ps.size()];
                        for(int i = 0; i < arr.length; i++)
                            arr[i] = ps.get(i).longValue();
                        log(CombatEvent.party(0, arr));
                    }
                }
            } catch(Exception e) {
                /* a party we could not read is not a fight we lose */
            }
            String[] hands = readHands(eq);
            /* The game's own figures for whatever is in each hand, ahead of the wiki
             * table. An empty map is a hand holding no weapon, or one whose tips had not
             * loaded - Prediction falls back to the table for that hand rather than
             * treating a missing tip as a weapon with no damage. */
            List<Map<String, Double>> wstats = new ArrayList<Map<String, Double>>();
            for(int i = 6; i <= 7; i++)
                wstats.add(readWeaponStats(((eq == null) || (i >= eq.slots.length))
                                           ? null : eq.slots[i]));
            for(int i = 0; i < wstats.size(); i++) {
                String res = hands[i * 2];
                if((res != null) && !wstats.get(i).isEmpty())
                    log(CombatEvent.weapon(0, 6 + i, res, wstats.get(i)));
            }
            me = Prediction.me(comp, arm[0], arm[1],
                               new String[] {hands[0], hands[2]},
                               new double[] {
                                   (hands[1] == null) ? 0 : Double.parseDouble(hands[1]),
                                   (hands[3] == null) ? 0 : Double.parseDouble(hands[3])},
                               readDeck(gui), wstats);
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

    /**
     * The weapon's own numbers, as the SERVER states them on the item.
     *
     * This is what the wiki table was standing in for. Every weapon tooltip carries its
     * figures as {@link haven.res.ui.tt.wpn.info.WeaponInfo} subclasses - Damage, Armpen,
     * Coolmod, Grievous, Range - each holding one public numeric field, and Armpen and
     * Grievous have already been divided by a hundred, so they arrive as the 0..1
     * fractions the model wants rather than as percentages to convert.
     *
     * It retires a real join and its whole failure class. Matching a resource basename
     * against a wiki page title - "gfx/invobjs/bronzesword" against "Bronze Sword" - misses
     * silently for anything not in the table, and four of the twenty-six weapons have no
     * recorded penetration at all, so those declined to predict for want of a number the
     * item was carrying the whole time. It also reaches two terms the table never had:
     * Coolmod, the weapon's own attack-cooldown modifier, and Grievous as a WEAPON
     * property distinct from the per-card one.
     *
     * Keyed on the class rather than the field, because the field name is not unique -
     * Armpen and Grievous both call theirs "deg". Read reflectively so that a resource
     * class loaded from the server, rather than the copy compiled into this jar, is still
     * read correctly: both resolve WeaponInfo to the same vendored supertype, and only the
     * subclass identity differs.
     */
    private static Map<String, Double> readWeaponStats(WItem w) {
        Map<String, Double> out = new java.util.LinkedHashMap<String, Double>();
        if(w == null)
            return(out);
        try {
            for(ItemInfo info : w.item.info()) {
                if(!(info instanceof haven.res.ui.tt.wpn.info.WeaponInfo))
                    continue;
                try {
                    String k = info.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
                    for(java.lang.reflect.Field f : info.getClass().getFields()) {
                        Class<?> t = f.getType();
                        if((t == int.class) || (t == double.class) || (t == float.class)
                           || (t == long.class)) {
                            out.put(k, ((Number)f.get(info)).doubleValue());
                            break;
                        }
                    }
                } catch(Exception e) {
                    /* one unreadable tip costs that figure, not the weapon */
                }
            }
        } catch(Exception e) {
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
            /* Kept after "del" as well. A gob that has just died still receives a final
             * health delta, and that is the one reading that closes its interval from
             * above rather than bounding it from below. */
            combatants.add(gobId);
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

    /**
     * A combatant's health from the object channel - which creatures never send.
     *
     * Measured, not assumed: nineteen fights across four species produced zero of these
     * with this method wired up and its gate verified to hold both gobs. OD_HEALTH is
     * object decay, not creature hitpoints. See CombatEvent.health for why the path is
     * kept rather than deleted.
     *
     * The two gates stay correct whatever does arrive: only gobs already known to be in
     * this fight, so a decaying wall in view cannot reach the log, and only on a CHANGE,
     * because the resolution is five values wide.
     *
     * @param hp the fraction the client computed, 0.0 to 1.0 in quarter steps
     */
    public static void onHealth(long gobId, float hp) {
        if(!active() || !combatants.contains(gobId))
            return;
        try {
            /* Logged as the server's own quarter, not as a fraction: 0.75 invites a reader
             * to believe three-quarters was measured, when what was measured is "3". */
            int q = Math.round(hp * 4.0f);
            Integer was = lastHp.put(gobId, q);
            if((was != null) && (was.intValue() == q))
                return;
            log(CombatEvent.health(now(), gobId, q));
        } catch(Exception e) {
            /* never propagate into the object-delta path */
        }
    }

    /**
     * The client's own agility bracket for an opponent, when it narrows.
     *
     * Fightsess derives this from each attack's reported cooldown against the card's base,
     * and tightens it as the fight goes on. It is the only quantity in this system with a
     * second, independent estimate available - the offline estimators recover agility their
     * own way - and the whole class of error this project has made is a quantity with no
     * second opinion. Logged when it moves, which is a handful of lines a fight.
     */
    public static void onAgility(long gobId, double min, double max) {
        if(!active())
            return;
        try {
            /* (0, 2) is Relation's own "nothing known yet", not a measurement. */
            if((min <= 0) && (max >= 2))
                return;
            String key = gobId + ":" + min + ":" + max;
            if(key.equals(lastAgi.get(gobId)))
                return;
            lastAgi.put(gobId, key);
            log(CombatEvent.agility(now(), gobId, min, max));
        } catch(Exception e) {
            /* never propagate into tick() */
        }
    }

    /**
     * Three resources the server sends that this client reads and then discards.
     *
     * Recorded without interpretation, exactly as overlays are: their meaning is
     * undocumented, nothing here consumes them, and a guess written into a model is worse
     * than a fact written into a log. One real fight names them.
     */
    public static void onAtkRes(String blk, String batk, String iatk) {
        if(!active())
            return;
        try {
            String key = blk + "|" + batk + "|" + iatk;
            if(key.equals(lastAtkRes))
                return;
            lastAtkRes = key;
            log(CombatEvent.atkres(now(), blk, batk, iatk));
        } catch(Exception e) {
            /* never propagate into the message loop */
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

    /* How long a fight may go without a state line. Chosen against what it is for: the
     * opening decay's time constant is somewhere near 15 s and a point is lost every 2.4 s
     * at low openings, so a cadence of half a second brackets each step to well inside the
     * quantity being measured, and costs about two lines a second in a fight that is
     * otherwise silent. */
    private static final long HEARTBEAT = 500;

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
            /* THE VALUE GATE HIDES THE ONE THING THAT ONLY TIME REVEALS. Openings decay on
             * their own, and a decay changes the value - so it does fire a sample - but it
             * fires it whenever the next frame happens to land, not when the decay
             * happened. Measured that way, the dwell between one point and the next has an
             * interquartile spread of 1.75 times its own median, and our side reads a time
             * constant of 20.9 s against the opponent's 15.3, which cannot both be right.
             *
             * A sample at a fixed cadence during a lull costs a line every HEARTBEAT ms
             * while a fight is otherwise static, and turns that into an actual measurement:
             * two consecutive heartbeats with no move between them bracket the decay to
             * the cadence rather than to the frame rate. Everything else in this file is
             * value-gated on purpose and stays that way.
             *
             * Not a substitute for the value gate - it is an addition. A change still emits
             * immediately; this only stops silence from being unbounded.
             *
             * 2026-09-04 re-measure on 76 schema-12 logs: the heartbeat removes
             * value-gate jitter but the set is thin (19 decays: 9 foe, 10 mine)
             * and still contradicts any single tau per side (foe max lower 46 s
             * above min upper 19 s; mine max lower 19 s above min upper 1.9 s).
             * The decay term stays out of Sim until a controlled still lull
             * (30 s or more, standing, no third party) is logged on schema 12. */
            long ts = now();
            boolean beat = (ts - lastBeat) >= HEARTBEAT;
            if(key.equals(lastSample) && !beat)
                return;
            lastBeat = ts;
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
    public static void sampleFoes(long[] packed, int[] gst) {
        if(!active() || (packed == null) || (packed.length == 0))
            return;
        try {
            StringBuilder k = new StringBuilder();
            for(long v : packed)
                k.append(v).append(':');
            /* The aggression state is part of the key. One animal in a pack extending its
             * olive branch changes nothing about anyone's openings, so without this the
             * moment it happens is suppressed as an unchanged state - and that moment is
             * the whole point of recording it. */
            for(int i = 0; (gst != null) && (i < gst.length); i++)
                k.append(gst[i]).append(';');
            String key = k.toString();
            if(key.equals(lastFoes))
                return;
            lastFoes = key;
            log(CombatEvent.foes(now(), packed, gst));
        } catch(Exception e) {
            /* never propagate into tick() */
        }
    }

    /**
     * An overlay appearing on anybody in the fight - the move they just used.
     *
     * A combat move is announced by a brief icon over whoever used it, and nothing else in a
     * log says what somebody other than us did: their moves are not in our fightview. The
     * corpus has now named the resources - gfx/fx/fight/barrage, fullcircle, cleave,
     * oppknock, dash, zigzag, sting, jump, flex, slide, artevade - one per card.
     *
     * WHICH IS WHY THE CALLER NOW FILTERS ON THE OVERLAY AND NOT ON THE GOB. Restricted to
     * player bodies, this recorded 2729 announcements and every one of them was a player's;
     * an ant's was discarded, and an ant's is the one that matters. Twenty-one species have
     * no combat skill recovered at all, for exactly one reason - every fight against them
     * has other creatures in it and no gain can be attributed. This is the signal that
     * attributes them.
     *
     * Not value-gated: an overlay is an event, and two identical ones in a row are two moves.
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
            String id = who + gobId;
            String key = names.toString();
            if(key.equals(lastBuffs.get(id)))
                return;
            lastBuffs.put(id, key);
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
        Path path = curPath;
        try {
            w.offer(CombatEvent.end(now(), outcome, w.dropped(), !w.alive()));
        } catch(Exception e) {
            /* never propagate */
        }
        writer = null;
        curPath = null;
        try {
            w.close();
        } catch(Exception e) {
            /* never propagate */
        }
        try {
            if(path != null)
                CombatLogSync.enqueue(path);
        } catch(Exception e) {
            /* never propagate — upload is best-effort */
        }
    }
}
