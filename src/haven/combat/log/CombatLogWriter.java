package haven.combat.log;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, non-blocking JSONL sink.
 *
 * offer() is called from the UI and render threads and must never block, never throw, and never
 * allocate beyond the string it is handed. On a full queue it drops the line and counts it: losing
 * telemetry is acceptable, stalling the client is not.
 *
 * The output file is opened in the constructor, not on the background thread. A bad path, missing
 * permissions, or a file locked by another process (all plausible under the game directory on
 * Windows) then fails loudly to the caller via a thrown IOException, instead of leaving behind a
 * writer that looks healthy but whose drain thread already died. If an IOException happens later,
 * mid-run, alive() flips to false so a caller can tell "logging fine" from "logging silently dead" -
 * offer() and close() still never throw.
 *
 * Imports nothing from haven - see tools/CombatLogCheck.java.
 */
public final class CombatLogWriter implements Closeable {
    /* How long the drain waits for a line offered with offerLater() before counting it dropped.
     * Far above what one is meant to take - the advice search is tenths of a second - so it only
     * ever fires on a computation that hung. */
    private static final long LATER_WAIT_MS = 10000;

    /* Strings, or Futures of one for offerLater(). */
    private final BlockingQueue<Object> q;
    private final Thread thread;
    private final BufferedWriter w;
    private final AtomicInteger dropped = new AtomicInteger(0);
    private volatile boolean closed = false;
    private volatile boolean failed = false;

    public CombatLogWriter(Path path, int capacity) throws IOException {
        Path parent = path.getParent();
        if(parent != null)
            Files.createDirectories(parent);
        this.w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                                         StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.q = new ArrayBlockingQueue<Object>(capacity);
        this.thread = new Thread(this::drain, "combat-log-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void offer(String line) {
        if(closed || line == null)
            return;
        if(!q.offer(line))
            dropped.incrementAndGet();
    }

    /**
     * A line that is still being computed, written in the place it was offered.
     *
     * For a line whose content takes too long to work out on the thread that knows where it
     * belongs: the caller holds its place in the file now, and the drain thread waits for it
     * there, so every line keeps the position and order it would have had written inline. A
     * future that yields null writes nothing, as a caller with nothing to say would have; one
     * that fails or outlasts LATER_WAIT_MS writes nothing and counts as dropped.
     */
    public void offerLater(Future<String> line) {
        if(closed || line == null)
            return;
        if(!q.offer(line))
            dropped.incrementAndGet();
    }

    public int dropped() {
        return(dropped.get());
    }

    private String resolve(Object item) {
        if(!(item instanceof Future))
            return((String)item);
        Future<?> f = (Future<?>)item;
        try {
            return((String)f.get(LATER_WAIT_MS, TimeUnit.MILLISECONDS));
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return(null);
        } catch(Exception e) {
            f.cancel(true);
            dropped.incrementAndGet();
            return(null);
        }
    }

    private void write(Object item) throws IOException {
        String line = resolve(item);
        if(line != null) {
            w.write(line);
            w.write('\n');
        }
    }

    /* False once an IOException has killed the drain thread - offer() keeps accepting into the
     * queue regardless, so this is the only signal a caller has that lines are no longer reaching
     * disk. */
    public boolean alive() {
        return(!failed);
    }

    private void drain() {
        try {
            while(true) {
                Object line = q.poll(200, TimeUnit.MILLISECONDS);
                if(line != null)
                    write(line);
                if(q.isEmpty()) {
                    w.flush();
                    if(closed)
                        break;
                }
            }
            /* close() flips `closed` independently of offer()'s check of it - a line can land in
             * the queue in the gap between offer() reading `closed` as false and this thread
             * observing it as true and breaking out above. Drain whatever is left before we
             * shut down so that gap doesn't silently eat a line. */
            Object line;
            while((line = q.poll()) != null)
                write(line);
            w.flush();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch(IOException e) {
            /* A telemetry logger must never take the client down. */
            failed = true;
        } finally {
            try {
                w.close();
            } catch(IOException e) {
                failed = true;
            }
        }
    }

    /**
     * Stops taking lines and waits for everything offered to reach the file. Waits for any line
     * still being computed as well, so call it off the UI thread.
     */
    public void close() {
        if(closed)
            return;
        closed = true;
        try {
            thread.join(3000 + LATER_WAIT_MS);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
