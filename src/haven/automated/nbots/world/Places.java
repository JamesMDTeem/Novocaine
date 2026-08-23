package haven.automated.nbots.world;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.SharedFile;
import org.json.JSONArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every {@link Place} the player has defined, and the queries bots ask of them.
 *
 * This is the piece that lets a bot say "go and get water" without knowing where water is, and
 * therefore the piece that makes new bots cheap: a bot needing somewhere to put its output asks
 * for a place that accepts it, rather than growing its own setting and its own manager UI.
 *
 * <h2>Several clients share this file, and none of them owns it</h2>
 *
 * One character is one client is one JVM, so a crew is several processes writing one file in the
 * working directory. The shape this replaces - load once into a static cache, write the cache out
 * whole - is wrong in both directions at once, and both were being hit daily:
 *
 * <ul>
 *   <li><b>Outwards.</b> A whole-file write from a stale cache silently deletes every place the
 *       other clients have defined since this one started. It is not a shutdown-ordering problem:
 *       the write happens on any edit at all, including a checkbox, a keystroke in a rule field,
 *       and {@link Place#observe} firing from a running bot with no user action whatsoever. The
 *       last client to touch anything won, and the rest lost their areas.</li>
 *   <li><b>Inwards.</b> {@link #reload} existed and had no callers, and {@code load()} was guarded
 *       by {@code cache == null}, so a client never re-read the file after startup. A place drawn
 *       in one client was invisible to every other one - and to their bots, which is why two of
 *       them would happily work the same area.</li>
 * </ul>
 *
 * So the cache is now a cache rather than the truth. Reads re-read the file when its timestamp has
 * moved ({@link #all}), and writes take a cross-process lock, re-read, apply only what THIS client
 * changed, and write the merged result ({@link #save}). The authority rule is deliberately narrow:
 * a client asserts only the places it has actually created, edited or deleted since its last save,
 * and takes the file's word for everything else. Same shape as {@link Observed}, and as XmlPrefs
 * on the client side.
 */
public class Places {
    private static final String FILE = "botplaces.json";
    private static final Object LOCK = new Object();

    private static List<Place> cache = null;
    /** Names this client has created or changed and not yet written out. Lower-cased. */
    private static final Set<String> dirty = new LinkedHashSet<>();
    /** Names this client has deleted and not yet written out. Lower-cased. */
    private static final Set<String> removed = new LinkedHashSet<>();
    /**
     * The file's last-modified time as this client last saw it.
     *
     * The guard that makes re-reading cheap: an untouched file is a timestamp comparison rather
     * than a parse, so {@link #all} can afford to be called from the middle of a bot loop.
     */
    private static long fileAt = -1;

    private Places() {}

    private static Path file() {
        return Paths.get(System.getProperty("novocaine.placesfile", FILE));
    }

    private static String key(String name) {
        return (name == null) ? "" : name.toLowerCase(Locale.ROOT);
    }

    public static List<Place> all() {
        synchronized (LOCK) {
            long now = stamp();
            if ((cache == null) || (now != fileAt)) {
                cache = merge(load(), cache);
                fileAt = now;
            }
            return new ArrayList<>(cache);
        }
    }

    // ------------------------------------------------------------------ persistence

    /** The file's modification time, or -1 when it cannot be read at all. */
    private static long stamp() {
        try {
            Path p = file();
            return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static List<Place> load() {
        List<Place> out = new ArrayList<>();
        Path p = file();
        if (!Files.exists(p))
            return out;
        try {
            String body = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                Place place = Place.fromJson(arr.getJSONObject(i));
                // A place whose anchor won't parse is dropped rather than failing the whole load:
                // one corrupt entry should not cost the player every other place they defined.
                if (place != null)
                    out.add(place);
            }
        } catch (IOException | RuntimeException e) {
            NLog.crash("loading " + FILE, e);
        }
        return out;
    }

    /**
     * What is on disk, with this client's own unflushed changes laid over the top.
     *
     * Only names in {@link #dirty} and {@link #removed} are taken from us; everything else comes
     * from the file. That is the whole of the authority rule - a client that has not touched a
     * place has no opinion about it, and in particular no opinion that it should stop existing.
     */
    private static List<Place> merge(List<Place> disk, List<Place> mine) {
        List<Place> out = new ArrayList<>();
        for (Place p : disk) {
            String k = key(p.name);
            if (removed.contains(k) || dirty.contains(k))
                continue;
            out.add(p);
        }
        if (mine != null) {
            for (Place p : mine) {
                if (dirty.contains(key(p.name)))
                    out.add(p);
            }
        }
        return out;
    }

    /**
     * Writes this client's changes into the shared file without losing anyone else's.
     *
     * Runs under a sidecar lock file so two clients cannot interleave a read-modify-write, and
     * re-reads inside that lock so what gets merged is what is actually on disk right now rather
     * than what was there when this client started.
     *
     * A save that cannot get the lock is abandoned rather than forced. The deltas stay pending, so
     * the next edit - or the next {@link #all} that notices the file moved - carries them; forcing
     * it is how the whole-file overwrite got here in the first place.
     *
     * Private: a caller that mutates a place and then asks for a save has not said which place it
     * changed, and this cannot merge safely without that. {@link #add}, {@link #remove} and
     * {@link #touch} are the ways in, and each records the name before getting here.
     */
    private static void save() {
        synchronized (LOCK) {
            if (cache == null)
                return;
            if (dirty.isEmpty() && removed.isEmpty())
                return;
            /* RuntimeException is caught alongside IOException on purpose: this runs on the UI
             * thread from a button press, and an exception that escapes it kills that thread and
             * takes the whole client with it - a bad place to learn a value would not serialise. */
            try (SharedFile.Held held = SharedFile.lock(file())) {
                if (held == null) {
                    NLog.log("nbot-places.log", "couldn't lock " + FILE + " to save; will retry on the next change");
                    return;
                }
                List<Place> merged = merge(load(), cache);
                write(merged);
                cache = merged;
                dirty.clear();
                removed.clear();
                fileAt = stamp();
            } catch (IOException | RuntimeException e) {
                NLog.crash("saving " + FILE, e);
            }
        }
    }

    private static void write(List<Place> list) throws IOException {
        JSONArray arr = new JSONArray();
        for (Place p : list)
            arr.put(p.toJson());
        SharedFile.writeAtomic(file(), arr.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ editing

    public static void add(Place p) {
        synchronized (LOCK) {
            if (cache == null)
                cache = load();
            cache.removeIf(e -> e.name.equalsIgnoreCase(p.name));
            cache.add(p);
            dirty.add(key(p.name));
            removed.remove(key(p.name));
        }
        save();
    }

    public static void remove(String name) {
        synchronized (LOCK) {
            if (cache == null)
                cache = load();
            cache.removeIf(e -> e.name.equalsIgnoreCase(name));
            removed.add(key(name));
            dirty.remove(key(name));
        }
        save();
    }

    /**
     * Records that a place already in the list has been changed in place, and writes it out.
     *
     * Needed because not every edit goes through {@link #add}: {@link Place#observe} mutates a
     * place's memory directly from a bot thread, and a save that did not know which place had
     * changed could not tell "mine, keep it" from "someone else's, leave it alone".
     */
    public static void touch(Place p) {
        if (p == null)
            return;
        synchronized (LOCK) {
            dirty.add(key(p.name));
            removed.remove(key(p.name));
        }
        save();
    }

    // ------------------------------------------------------------------ working one at a time

    /**
     * Takes exclusive use of a place, when either the place or the bot says it should be exclusive.
     *
     * Two independent reasons to hold an area, and they are deliberately OR-ed rather than folded
     * into one flag on either side:
     *
     * <ul>
     *   <li>{@code needsAlone} is the bot's own answer. Surveying is the case it exists for - two
     *       bots surveying one area do not merely duplicate the work, they corrupt it - and no
     *       amount of configuring the PLACE can know which bot is about to be pointed at it.</li>
     *   <li>{@link Place#exclusiveByPolicy} is the player's answer, for "I want one bot in here"
     *       regardless of who is asking.</li>
     * </ul>
     *
     * True when nothing needs claiming at all, so a caller can use it unconditionally. Also true
     * when claiming is switched off or the registry is unusable - {@link WorkClaims} fails open on
     * purpose, and a crew that cannot coordinate is better than a crew that will not work.
     */
    public static boolean claim(Place p, boolean needsAlone) {
        if ((p == null) || !(needsAlone || p.exclusiveByPolicy()))
            return true;
        return WorkClaims.claim(WorkClaims.placeKey(p.name));
    }

    /** Keeps a claim from {@link #claim} alive. Free when the place was never claimed. */
    public static void renewClaim(Place p, boolean needsAlone) {
        if ((p != null) && (needsAlone || p.exclusiveByPolicy()))
            WorkClaims.renew(WorkClaims.placeKey(p.name));
    }

    /** Hands a claimed place back. Free when the place was never claimed. */
    public static void releaseClaim(Place p, boolean needsAlone) {
        if ((p != null) && (needsAlone || p.exclusiveByPolicy()))
            WorkClaims.release(WorkClaims.placeKey(p.name));
    }

    public static Place byName(String name) {
        for (Place p : all()) {
            if (p.name.equalsIgnoreCase(name))
                return p;
        }
        return null;
    }

    /** Every role any defined place carries, plus the well-known ones. For the manager UI. */
    public static Set<String> knownRoles() {
        Set<String> out = new LinkedHashSet<>(PlaceRoles.KNOWN);
        for (Place p : all())
            out.addAll(p.roles);
        return out;
    }

    // ------------------------------------------------------------------ what bots ask

    /**
     * The nearest place with this role that can actually be walked to from where we are.
     *
     * "Reachable" here means the anchor resolves - i.e. it is in the same map segment. A place on
     * another continent is silently skipped rather than returned and then failed on, so a crew
     * working two sites can define a water place at each and each bot picks its own.
     */
    public static Place nearest(GameUI gui, String role) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if (me == null)
            return null;
        Place best = null;
        double bestd = Double.MAX_VALUE;
        for (Place p : all()) {
            if (!p.hasRole(role))
                continue;
            Coord2d c = p.centre(gui);
            if (c == null)
                continue;
            double d = me.rc.dist(c);
            if (d < bestd) {
                bestd = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * The nearest place that will take this item - one whose `accepts` rule matches, preferred over
     * a general dump with no rule at all.
     *
     * The preference matters: with a "Stone" dump and a catch-all dump both defined, stone should
     * go to the stone one. Sorting by (specific first, then distance) expresses that without
     * needing a priority field on the place.
     */
    public static Place accepting(GameUI gui, String itemName) {
        return bestMatch(gui, itemName, true);
    }

    /** The nearest place that will yield this item - one whose `provides` rule matches. */
    public static Place providing(GameUI gui, String itemName) {
        return bestMatch(gui, itemName, false);
    }

    private static Place bestMatch(GameUI gui, String itemName, boolean in) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if (me == null || itemName == null)
            return null;
        Place best = null;
        double bestd = Double.MAX_VALUE;
        boolean bestSpecific = false;
        for (Place p : all()) {
            Alias rule = in ? p.accepts : p.provides;
            boolean specific = !rule.isEmpty() && rule.matchesEither(itemName);
            boolean general = rule.isEmpty()
                && p.hasRole(in ? PlaceRoles.DUMP : PlaceRoles.STORE);
            if (!specific && !general)
                continue;
            Coord2d c = p.centre(gui);
            if (c == null)
                continue;
            double d = me.rc.dist(c);
            if ((specific && !bestSpecific) || (specific == bestSpecific && d < bestd)) {
                best = p;
                bestd = d;
                bestSpecific = specific;
            }
        }
        return best;
    }

    /** The place the player is currently standing in with this role, if any. */
    public static Place containing(GameUI gui, String role) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if (me == null)
            return null;
        for (Place p : all()) {
            if (p.hasRole(role) && p.contains(gui, me.rc))
                return p;
        }
        return null;
    }

    /**
     * Drops the cache so the next read comes from the file.
     *
     * Rarely needed now that {@link #all} re-reads on its own when the file moves; kept for the
     * case where the file is replaced wholesale underneath a running client without its timestamp
     * being trustworthy. Pending changes are NOT discarded - they are still owed to the file.
     */
    public static void reload() {
        synchronized (LOCK) {
            cache = null;
            fileAt = -1;
        }
    }
}
