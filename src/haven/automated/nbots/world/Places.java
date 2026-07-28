package haven.automated.nbots.world;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.NLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every {@link Place} the player has defined, and the queries bots ask of them.
 *
 * This is the piece that lets a bot say "go and get water" without knowing where water is, and
 * therefore the piece that makes new bots cheap: a bot needing somewhere to put its output asks
 * for a place that accepts it, rather than growing its own setting and its own manager UI.
 *
 * Shared across every client launched from one install (the file sits in the working directory
 * beside logs/ and the claim registry), which is what you want for a crew: define the water place
 * once and all of them know it.
 *
 * Loaded lazily and cached; writes go through a temp file and an atomic move, so a client killed
 * mid-save leaves the previous list intact rather than a half-written one.
 */
public class Places {
    private static final String FILE = "botplaces.json";
    private static final Object LOCK = new Object();

    private static List<Place> cache = null;

    private Places() {}

    private static Path file() {
        return Paths.get(System.getProperty("novocaine.placesfile", FILE));
    }

    public static List<Place> all() {
        synchronized (LOCK) {
            if (cache == null)
                cache = load();
            return new ArrayList<>(cache);
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

    public static void save() {
        synchronized (LOCK) {
            if (cache == null)
                return;
            /* Serialising is inside the try, and RuntimeException is caught alongside IOException,
             * to match load() above. Both matter more here than they look: this runs on the UI
             * thread from a button press, and an exception that escapes it kills that thread and
             * takes the whole client with it - a bad place to learn a value would not serialise. */
            try {
                JSONArray arr = new JSONArray();
                for (Place p : cache)
                    arr.put(p.toJson());
                Path dst = file();
                Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
                Files.write(tmp, arr.toString(2).getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                NLog.crash("saving " + FILE, e);
            }
        }
    }

    public static void add(Place p) {
        synchronized (LOCK) {
            if (cache == null)
                cache = load();
            cache.removeIf(e -> e.name.equalsIgnoreCase(p.name));
            cache.add(p);
        }
        save();
    }

    public static void remove(String name) {
        synchronized (LOCK) {
            if (cache == null)
                cache = load();
            cache.removeIf(e -> e.name.equalsIgnoreCase(name));
        }
        save();
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

    /** Drops the cache so an edit made in another client is picked up. */
    public static void reload() {
        synchronized (LOCK) {
            cache = null;
        }
    }
}
