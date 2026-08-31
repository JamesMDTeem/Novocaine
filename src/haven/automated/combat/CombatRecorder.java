package haven.automated.combat;

import haven.Client;
import haven.OptWnd;
import haven.combat.log.CombatEvent;
import haven.combat.log.CombatLogWriter;
import haven.combat.log.Openings;

import java.nio.file.Path;
import java.nio.file.Paths;
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

    public static synchronized void start(String charName, long foeGob, String foeRes) {
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
            String safe = (charName == null ? "unknown" : charName.replaceAll("[^A-Za-z0-9_-]", "_"));
            Path p = Paths.get(Client.gameDir, "CombatLogs",
                               t0 + "-" + safe + "-" + seq.incrementAndGet() + ".jsonl");
            writer = new CombatLogWriter(p, 4096);
        } catch(Exception e) {
            writer = null;
        }
    }

    public static void log(String line) {
        CombatLogWriter w = writer;
        if(w != null)
            w.offer(line);
    }

    public static void onMove(String actor, String moveRes, double cooldownTicks, long gobId) {
        if(!active())
            return;
        try {
            log(CombatEvent.move(now(), actor, moveRes, cooldownTicks, gobId));
        } catch(Exception e) {
            /* never propagate into the message loop */
        }
    }

    /* Colour codes from GobDamageInfo. 35071 is Initiative, which the client does not render but
     * which is logged here: it is a free signal and cannot be recovered retroactively. */
    private static String channel(int c) {
        switch(c) {
        case 61455: return("SHP");
        case 64527: return("HHP");
        case 36751: return("ARM");
        case 35071: return("IP");
        default:    return("C" + c);
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
        writer = null;
        try {
            w.close();
        } catch(Exception e) {
            /* never propagate */
        }
    }
}
