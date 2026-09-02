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

    public static synchronized void start(String charName, long meGob, long foeGob, String foeRes,
                                          Glob glob, Equipory eq) {
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
            named.clear();
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
            log(CombatEvent.begin(0, t0, CombatEvent.SCHEMA, charName, meGob, foeGob, foeRes,
                                  readAttrs(glob, false), readAttrs(glob, true), arm[0], arm[1]));
            for(String g : gear)
                log(g);
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
                              int hp, double stam, double energy, double dist, long gobId) {
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
            String key = gobId + ":" + mine.toJson() + foe.toJson() + myIp + ":" + foeIp + ":" + hp + ":" + (long)dist;
            if(key.equals(lastSample))
                return;
            lastSample = key;
            log(CombatEvent.state(now(), mine, foe, myIp, foeIp, hp, stam, energy, dist, gobId));
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
