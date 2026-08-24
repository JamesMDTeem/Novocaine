package haven.automated.eat;

import haven.automated.cookbook.FoodService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fetches the cookbook catalog for the Eating Helper's planner: {@code GET /client/{token}/cookbook}
 * on {@link FoodService#scheduler}, cached for the session. Reuses the exact endpoint/token
 * {@link FoodService} already sends food uploads to, snapshotted the same way
 * {@link FoodService#refreshEndpointCache()} does - never read straight off the OptWnd widgets
 * here, only the already-UI-thread-snapshotted cache.
 *
 * The endpoint field configured in Options holds the full food-upload POST URL
 * (".../client/{token}/food"); the GET this class needs is the sibling path with that trailing
 * segment swapped for "/cookbook", which {@link FoodService#siblingEndpoint} derives - including
 * carrying over a "?world=" tag, so the planner reads the same world the uploads went to. If the
 * configured value doesn't end in "/food", it isn't recognized as a cookbook endpoint at all -
 * reported through {@link #lastError()} rather than guessed at.
 */
public class CookbookClient {
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // catalog changes slowly - minutes, not seconds
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private static volatile List<EatPlanner.Dish> cached = null;
    private static volatile long cachedAt = 0;
    /** Guards against two concurrent fetches. An AtomicBoolean rather than a volatile flag
     *  because the check-then-set on a volatile is not atomic: two callers arriving together
     *  both read false and both fetch. */
    private static final java.util.concurrent.atomic.AtomicBoolean fetching =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile String lastError = null;

    /** World the cached catalog was fetched for - see {@link #refreshIfStale()}. */
    private static volatile String cachedWorld = null;

    /** Cached catalog from the last successful fetch, or null if none has landed yet. */
    public static List<EatPlanner.Dish> cached() {
        return cached;
    }

    /** Human-readable reason the last fetch failed, or null if the last fetch (if any) succeeded. */
    public static String lastError() {
        return lastError;
    }

    /**
     * Kicks off a background fetch if the cache is stale/empty and nothing is already in flight.
     *
     * A catalog fetched for another world is stale no matter how fresh it is: relogging onto a
     * character in a different world must not leave the planner recommending dishes from the
     * one before it.
     */
    public static void refreshIfStale() {
        if (fetching.get())
            return;
        boolean sameWorld = java.util.Objects.equals(cachedWorld, FoodService.worldTag());
        if (cached != null && sameWorld && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS)
            return;
        fetch(null);
    }

    /** Forces a fetch regardless of cache age. {@code onDone} (may be null) runs on the scheduler thread. */
    public static void fetch(Consumer<List<EatPlanner.Dish>> onDone) {
        if (!fetching.compareAndSet(false, true))
            return;
        FoodService.scheduler.execute(() -> {
            try {
                String world = FoodService.worldTag();
                List<EatPlanner.Dish> result = fetchNow();
                cached = result;
                cachedWorld = world;
                cachedAt = System.currentTimeMillis();
                lastError = null;
                if (onDone != null)
                    onDone.accept(result);
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (onDone != null)
                    onDone.accept(null);
            } finally {
                fetching.set(false);
            }
        });
    }

    private static List<EatPlanner.Dish> fetchNow() throws Exception {
        String endpoint = FoodService.cachedEndpoint();
        if (endpoint == null)
            throw new IllegalStateException("Cookbook endpoint not configured");
        String cookbookUrl = FoodService.endpointFor("cookbook");
        if (cookbookUrl == null)
            throw new IllegalStateException("Configured endpoint doesn't look like a food-upload URL: " + endpoint);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(cookbookUrl).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "H&H Client");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            String token = FoodService.cachedToken();
            if (token != null && !token.isEmpty())
                connection.setRequestProperty("Authorization", "Bearer " + token);

            int code = connection.getResponseCode();
            if (code != 200)
                throw new IllegalStateException("Cookbook fetch failed: HTTP " + code);

            try (InputStream in = connection.getInputStream();
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) >= 0)
                    sb.append(buf, 0, n);
                return parseCatalog(new JSONArray(sb.toString()));
            }
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    private static List<EatPlanner.Dish> parseCatalog(JSONArray arr) {
        List<EatPlanner.Dish> dishes = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            try {
                dishes.add(parseDish(arr.getJSONObject(i)));
            } catch (JSONException e) {
                // One malformed catalog row shouldn't lose the rest of the catalog.
            }
        }
        return dishes;
    }

    private static EatPlanner.Dish parseDish(JSONObject o) {
        String name = o.getString("name");
        double hunger = o.getDouble("hunger");

        List<EatPlanner.Fep> feps = new ArrayList<>();
        JSONArray fepArr = o.optJSONArray("feps");
        if (fepArr != null) {
            for (int i = 0; i < fepArr.length(); i++) {
                JSONObject f = fepArr.getJSONObject(i);
                feps.add(new EatPlanner.Fep(f.getString("attribute"), f.getInt("tier"), f.getDouble("value")));
            }
        }

        // satiationKeys, not satiationTypes: the types are the tooltip's thirteen display
        // categories (gfx/invobjs/food/*) and cannot be matched against live satiation state,
        // which is keyed by a representative dish icon instead. Only the keys join.
        List<String> satiationKeys = new ArrayList<>();
        JSONArray keyArr = o.optJSONArray("satiationKeys");
        if (keyArr != null) {
            for (int i = 0; i < keyArr.length(); i++) {
                String key = keyArr.optString(i, null);
                if (key != null && !key.isEmpty())
                    satiationKeys.add(key);
            }
        }

        Double maxQualitySeen = o.isNull("maxQualitySeen") || !o.has("maxQualitySeen")
                ? null : o.getDouble("maxQualitySeen");

        return new EatPlanner.Dish(name, feps, hunger, satiationKeys, maxQualitySeen);
    }
}
