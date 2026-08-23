package haven.automated.nbots.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writing a state file that several clients share.
 *
 * Every file this client keeps beside itself - botplaces.json, botmap.json, schedules.json - lives
 * in the install directory, and a crew runs several clients out of one install. That makes every
 * save a read-modify-write race between processes, and it fails in two ways that do not look
 * related from the outside:
 *
 * <ul>
 *   <li><b>Silently.</b> Two clients read, both merge, both write, and whichever renames last
 *       drops the other's work. Nothing is logged, because nothing threw.</li>
 *   <li><b>Loudly.</b> Windows refuses to rename over a file another process has open, so the
 *       move fails with {@link AccessDeniedException} and the save is lost. This is the one that
 *       showed up in a friend's crash.log for botmap.json while he was running two clients.</li>
 * </ul>
 *
 * Holding {@link #lock} across the whole read-merge-write fixes the first and makes the second
 * impossible between our own clients. {@link #writeAtomic} catches {@code AccessDeniedException}
 * anyway, because a virus scanner or an editor can hold the destination for reasons of its own.
 *
 * The logic is {@code Places}', which had solved this correctly and on its own; it lives here so
 * that the next file to need it borrows the fix instead of the bug.
 */
public class SharedFile {
    /** Attempts at the cross-process lock before giving up, at {@link #LOCK_WAIT_MS} apart. */
    private static final int LOCK_TRIES = 50;
    private static final long LOCK_WAIT_MS = 20;

    private SharedFile() {}

    /** The lock file that guards {@code target}. Its own file, so it is never the thing renamed. */
    public static Path lockFile(Path target) {
        return (target.resolveSibling(target.getFileName() + ".lock"));
    }

    /**
     * Takes the cross-process lock guarding {@code target}, or returns null if another client kept
     * it for the whole retry window.
     *
     * A caller that cannot get the lock must <b>abandon the save, not force it</b> - leave the
     * state dirty and let the next pass carry it. Forcing the write is precisely the behaviour
     * that loses a crewmate's work.
     *
     * Use with try-with-resources; a null result closes to nothing, so the shape stays:
     * <pre>
     *     try(SharedFile.Held held = SharedFile.lock(file())) {
     *         if(held == null)
     *             return;                // still dirty, next pass tries again
     *         ...read, merge, writeAtomic...
     *     }
     * </pre>
     */
    public static Held lock(Path target) throws IOException {
        FileChannel ch = FileChannel.open(lockFile(target),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        for (int i = 0; i < LOCK_TRIES; i++) {
            try {
                FileLock l = ch.tryLock();
                if (l != null)
                    return (new Held(ch, l));
            } catch (IOException | RuntimeException e) {
                // Another process holds it, or the filesystem refused; both mean "wait and retry".
            }
            try {
                Thread.sleep(LOCK_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        ch.close();
        return (null);
    }

    /** A held cross-process lock. Closing releases it and closes the channel behind it. */
    public static final class Held implements AutoCloseable {
        private final FileChannel ch;
        private final FileLock lock;

        private Held(FileChannel ch, FileLock lock) {
            this.ch = ch;
            this.lock = lock;
        }

        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                ch.close();
            }
        }
    }

    /**
     * Temp file, forced to disk, then renamed over the target.
     *
     * The force matters as much as the rename: without it the rename can be published while the
     * bytes behind it are still in the page cache, so a machine that loses power between the two
     * leaves a file that exists, is the right size, and is full of zeroes. Same reasoning as the
     * client's own preference writer.
     *
     * The temp name is shared rather than per-process on purpose - callers hold {@link #lock}
     * across this, so two clients cannot be here at once, and one predictable leftover file is
     * easier to recognise than a directory of {@code .12345.tmp} strays from crashed runs.
     */
    public static void writeAtomic(Path dst, byte[] data) throws IOException {
        Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE,
                 StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(data));
            ch.force(true);
        }
        try {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
