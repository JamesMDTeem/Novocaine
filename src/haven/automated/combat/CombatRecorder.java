package haven.automated.combat;

import haven.Client;
import haven.OptWnd;
import haven.combat.log.CombatEvent;
import haven.combat.log.CombatLogWriter;

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
