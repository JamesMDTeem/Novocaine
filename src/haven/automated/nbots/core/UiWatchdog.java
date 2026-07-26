package haven.automated.nbots.core;

/**
 * Detects the UI thread freezing and captures a thread dump when it does.
 *
 * Why this exists: in the original nurgling2 client the bot-cancel path froze the client rather
 * than throwing, which a normal crash log can't catch because a deadlock produces no exception.
 * The watchdog works by heartbeat - the UI thread stamps {@link #beat()} every frame while a bot
 * is running, and this daemon notices when those stamps stop arriving. If the UI thread goes
 * quiet for longer than a frame could ever legitimately take, it dumps every thread's stack (via
 * NLog) so the two threads stuck on each other are recorded, then stays quiet until the UI
 * recovers so it dumps once per freeze rather than continuously.
 *
 * It only judges the UI stalled while it's actually supposed to be beating (a bot is running and
 * ticking); an idle client that simply isn't calling beat() is not a freeze.
 */
public class UiWatchdog {
    private static final long STALL_MS = 8000;      // far longer than any real frame or lag spike
    private static final long POLL_MS = 1000;

    private static volatile long lastBeat = 0;
    private static volatile boolean beating = false;
    private static Thread thread;

    /** Called from the UI thread every frame it's alive. Cheap: two volatile writes. */
    public static void beat() {
        lastBeat = System.currentTimeMillis();
        beating = true;
    }

    /** Called when the last bot stops - stops the watchdog judging an intentionally idle UI. */
    public static void idle() {
        beating = false;
    }

    public static synchronized void ensureStarted() {
        if (thread != null)
            return;
        thread = new Thread(UiWatchdog::loop, "ui-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        boolean dumped = false;
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!beating) {
                dumped = false;
                continue;
            }
            long since = System.currentTimeMillis() - lastBeat;
            if (since > STALL_MS) {
                if (!dumped) {
                    NLog.dumpAllThreads("UI thread appears frozen (" + since + "ms since last frame)");
                    dumped = true;
                }
            } else {
                dumped = false;  // UI recovered; arm for the next distinct freeze
            }
        }
    }
}
