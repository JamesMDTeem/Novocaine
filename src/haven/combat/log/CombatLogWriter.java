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
    private final BlockingQueue<String> q;
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
        this.q = new ArrayBlockingQueue<String>(capacity);
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

    public int dropped() {
        return(dropped.get());
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
                String line = q.poll(200, TimeUnit.MILLISECONDS);
                if(line != null) {
                    w.write(line);
                    w.write('\n');
                }
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
            String line;
            while((line = q.poll()) != null) {
                w.write(line);
                w.write('\n');
            }
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

    public void close() {
        if(closed)
            return;
        closed = true;
        try {
            thread.join(3000);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
