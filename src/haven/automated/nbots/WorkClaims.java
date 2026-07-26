package haven.automated.nbots;

import haven.GameUI;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A short-lived, cross-process reservation on one {@link WorkSlots} position.
 *
 * {@link Crowd} covers what can be seen - a character mid-swing is obviously busy - but it cannot
 * see INTENT. Two bots that decide, in the same second, to walk to the same side of the same
 * boulder are both idle and both far away at the moment they decide, so nothing observable
 * separates them; they only discover the conflict on arrival, having each wasted the walk. That
 * gap is what this closes.
 *
 * The channel is the filesystem, because the thing that has to be coordinated is several JVMs on
 * one machine (one Haven client is one character, so "several bots" means several client
 * processes) and the filesystem is the only thing they already share. One file per claim, rather
 * than one registry file everyone rewrites: creating a file that must not already exist is
 * genuinely atomic on both Windows and POSIX, so winning a claim is a single syscall with no
 * locking protocol to get wrong, and a crashed client leaves at most a handful of small files
 * that expire on their own.
 *
 * Claims are always short-lived and always renewed. Nothing here is authoritative and nothing
 * blocks: if the registry is unwritable, unreadable, or simply switched off, every call degrades
 * to "yes, go ahead" and the bots fall back on observation alone. Coordination is an optimisation,
 * not a correctness requirement - the worst outcome of losing it is a wasted walk.
 */
public class WorkClaims {
    /**
     * How long an unrenewed claim stands. Long enough to cover a slow walk across a work site,
     * short enough that a client killed mid-job frees its slot before anyone notices.
     */
    public static final long TTL_MS = 25_000;
    /** How often a holder should call {@link #renew} while it still wants the slot. */
    public static final long RENEW_MS = 8_000;

    private static final String DIR_PROP = "novocaine.claimdir";
    private static final String SUFFIX = ".claim";

    /**
     * Identity of this client process. The character name alone isn't enough - the same character
     * can't be logged in twice, but a client that crashed and restarted would inherit its own
     * previous claims and never notice they were stale. The per-JVM suffix makes each run distinct.
     */
    private static final String JVM = Long.toHexString(
        (System.nanoTime() ^ System.identityHashCode(WorkClaims.class)) & 0xffffffffL);
    private static volatile String identity = "anon/" + JVM;

    /** Slots this process currently holds, so they can all be dropped when a bot stops. */
    private static final Set<String> held = new HashSet<>();

    private static volatile boolean broken = false;
    private static long lastSweep = 0;

    static {
        // A client closed with a bot running would otherwise leave its slots reserved for the full
        // TTL, which is exactly when another instance is most likely to want them.
        Runtime.getRuntime().addShutdownHook(new Thread(WorkClaims::releaseAll, "claim-cleanup"));
    }

    private WorkClaims() {}

    /** Records which character this process is, for legibility of the claim files. */
    public static void identify(GameUI gui) {
        if (gui != null && gui.chrid != null && !gui.chrid.isEmpty())
            identity = gui.chrid + "/" + JVM;
    }

    private static Path dir() {
        return Paths.get(System.getProperty(DIR_PROP, "botclaims"));
    }

    private static String key(long gobid, int slot) {
        return Long.toHexString(gobid) + "-" + slot;
    }

    private static Path file(String key) {
        return dir().resolve(key + SUFFIX);
    }

    /**
     * Tries to reserve a slot.
     *
     * @return true if it's ours to use - including when the registry is disabled or unavailable,
     *         since in that case there is nothing to conflict with and refusing would simply stop
     *         the bot working.
     */
    public static boolean claim(long gobid, int slot) {
        if (!NBotConfig.on(NBotConfig.Key.shareClaims) || broken)
            return true;
        String k = key(gobid, slot);
        synchronized (held) {
            if (held.contains(k))
                return true;
        }
        try {
            sweepOccasionally();
            Files.createDirectories(dir());
            try {
                write(file(k), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException e) {
                Holder cur = read(file(k));
                // A claim we can take: nobody's (unreadable), expired, or already ours from an
                // earlier bot run in this same process.
                if (cur != null && !cur.owner.equals(identity) && cur.expires > System.currentTimeMillis())
                    return false;
                write(file(k), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                // Two processes can both find the same claim expired and both overwrite it. Reading
                // back settles it: the last writer wins and the other one backs off, so the window
                // where both believe they hold it closes within a syscall rather than a whole TTL.
                Holder after = read(file(k));
                if (after == null || !after.owner.equals(identity))
                    return false;
            }
        } catch (IOException | RuntimeException e) {
            // An unusable claim directory must not stop the bots. Give up on the registry for the
            // rest of the session rather than retrying (and failing) on every single target.
            broken = true;
            return true;
        }
        synchronized (held) {
            held.add(k);
        }
        return true;
    }

    /** Extends a claim we hold. Silently does nothing if we don't hold it. */
    public static void renew(long gobid, int slot) {
        if (!NBotConfig.on(NBotConfig.Key.shareClaims) || broken)
            return;
        String k = key(gobid, slot);
        synchronized (held) {
            if (!held.contains(k))
                return;
        }
        try {
            write(file(k), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public static void release(long gobid, int slot) {
        String k = key(gobid, slot);
        boolean was;
        synchronized (held) {
            was = held.remove(k);
        }
        if (!was)
            return;
        try {
            Files.deleteIfExists(file(k));
        } catch (IOException ignored) {
        }
    }

    /** Drops every slot this process holds. Called when a bot stops, and at JVM shutdown. */
    public static void releaseAll() {
        List<String> keys;
        synchronized (held) {
            keys = new java.util.ArrayList<>(held);
            held.clear();
        }
        for (String k : keys) {
            try {
                Files.deleteIfExists(file(k));
            } catch (IOException ignored) {
            }
        }
    }

    private static void write(Path p, StandardOpenOption... opts) throws IOException {
        String body = identity + "\n" + (System.currentTimeMillis() + TTL_MS) + "\n";
        Files.write(p, body.getBytes(StandardCharsets.UTF_8), opts);
    }

    private static Holder read(Path p) {
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            if (lines.size() < 2)
                return null;
            return new Holder(lines.get(0), Long.parseLong(lines.get(1).trim()));
        } catch (IOException | NumberFormatException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /**
     * Deletes expired claim files. Without this the directory would accumulate one file per object
     * any bot ever touched; with it, an abandoned claim is cleaned up by whoever next wants a slot.
     */
    private static void sweepOccasionally() {
        long now = System.currentTimeMillis();
        synchronized (WorkClaims.class) {
            if (now - lastSweep < 60_000)
                return;
            lastSweep = now;
        }
        try (Stream<Path> files = Files.list(dir())) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).forEach(p -> {
                Holder h = read(p);
                if (h != null && h.expires < now) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static final class Holder {
        final String owner;
        final long expires;

        Holder(String owner, long expires) {
            this.owner = owner;
            this.expires = expires;
        }
    }
}
