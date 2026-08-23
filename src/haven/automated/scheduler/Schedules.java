package haven.automated.scheduler;

import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.SharedFile;
import org.json.JSONArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Every {@link Schedule} the player has defined, persisted as one JSON array beside
 * botplaces.json.
 *
 * Loaded lazily and cached, with the same write discipline as Places: serialize to a temp
 * file and move it into place, so a client killed mid-save keeps the previous list rather
 * than a half-written one. Saves happen on the UI thread - keep the file small.
 *
 * Shared across every client launched from one install, like Places and the claim registry.
 */
public class Schedules {
    private static final String FILE = "schedules.json";
    private static final Object LOCK = new Object();

    private static List<Schedule> cache = null;

    private Schedules() {}

    private static Path file() {
        return Paths.get(System.getProperty("novocaine.schedulesfile", FILE));
    }

    private static void load() {
        List<Schedule> list = new ArrayList<>();
        Path f = file();
        if (Files.exists(f)) {
            try {
                String body = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    Object raw = arr.opt(i);
                    if (raw instanceof org.json.JSONObject) {
                        try {
                            list.add(Schedule.fromJson((org.json.JSONObject) raw));
                        } catch (RuntimeException e) {
                            NLog.crash("schedules.json entry " + i + " unreadable - skipped", e);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                NLog.crash("Couldn't read " + f, e);
            }
        }
        cache = list;
    }

    private static void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Schedule s : cache)
                arr.put(s.toJson());
        } catch (RuntimeException e) {
            NLog.crash("Couldn't serialize schedules", e);
            return;
        }
        Path f = file();
        /* Same cross-process discipline as Places and Observed: this file is shared by every
         * client launched from one install, so an unlocked rename here either loses the other
         * client's edit or fails outright, because Windows will not rename over a file another
         * process has open. Unlike those two this is a whole-file overwrite of an in-memory list
         * rather than a merge, so the lock is what keeps last-writer-wins from meaning
         * last-writer-wins-a-race. */
        try (SharedFile.Held held = SharedFile.lock(f)) {
            if (held == null) {
                NLog.crash("Couldn't lock " + f + " to save",
                    new IOException("cross-process lock held by another client"));
                return;
            }
            SharedFile.writeAtomic(f, arr.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            NLog.crash("Couldn't save " + f, e);
        }
    }

    private static List<Schedule> allUnsafe() {
        if (cache == null)
            load();
        return cache;
    }

    /** All schedules, newest last, for display. Never null. */
    public static List<Schedule> all() {
        synchronized (LOCK) {
            return new ArrayList<>(allUnsafe());
        }
    }

    public static Schedule byName(String name) {
        synchronized (LOCK) {
            for (Schedule s : allUnsafe())
                if (s.name.equals(name))
                    return s;
        }
        return null;
    }

    /** Adds or replaces the schedule with this name, then saves. */
    public static void add(Schedule schedule) {
        synchronized (LOCK) {
            List<Schedule> list = allUnsafe();
            list.removeIf(s -> s.name.equals(schedule.name));
            list.add(schedule);
            save();
        }
    }

    public static void remove(String name) {
        synchronized (LOCK) {
            List<Schedule> list = allUnsafe();
            if (list.removeIf(s -> s.name.equals(name)))
                save();
        }
    }
}
