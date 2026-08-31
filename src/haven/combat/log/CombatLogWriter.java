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

/**
 * Bounded, non-blocking JSONL sink.
 *
 * offer() is called from the UI and render threads and must never block, never throw, and never
 * allocate beyond the string it is handed. On a full queue it drops the line and counts it: losing
 * telemetry is acceptable, stalling the client is not.
 *
 * Imports nothing from haven - see tools/CombatLogCheck.java.
 */
public final class CombatLogWriter implements Closeable {
    private final BlockingQueue<String> q;
    private final Thread thread;
    private final Path path;
    private volatile int dropped = 0;
    private volatile boolean closed = false;

    public CombatLogWriter(Path path, int capacity) throws IOException {
        this.path = path;
        Path parent = path.getParent();
        if(parent != null)
            Files.createDirectories(parent);
        this.q = new ArrayBlockingQueue<String>(capacity);
        this.thread = new Thread(this::drain, "combat-log-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void offer(String line) {
        if(closed || line == null)
            return;
        if(!q.offer(line))
            dropped++;
    }

    public int dropped() {
        return(dropped);
    }

    private void drain() {
        try(BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                                                       StandardOpenOption.CREATE,
                                                       StandardOpenOption.APPEND)) {
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
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch(IOException e) {
            /* A telemetry logger must never take the client down. */
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
