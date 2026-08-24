package haven.automated.alchemy;

import haven.automated.cookbook.FoodService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Asks the mapper which crafts are worth doing with the ingredients the player is holding:
 * {@code POST /client/{token}/alchemySuggest}.
 *
 * The ranking lives on the server and stays there. It reasons over the whole world's pooled
 * discoveries and craft log, which this client has no copy of and no business rebuilding -- so the
 * ingredient list goes up and a ranked answer comes back. A POST rather than a GET because that
 * list runs to a hundred names and does not belong in a query string.
 *
 * Endpoint and token are reused from {@link FoodService} exactly as {@link AlchemyService} reuses
 * them for uploads, so a player who has configured the mapper once has configured this too.
 *
 * <h2>Why the request is throttled rather than driven by the window</h2>
 * The window refreshes several times a second so the ingredient count feels live. The suggestion
 * behind it costs the server a real search over a million candidate crafts, so it is re-asked only
 * when the ingredient list actually changes, and never more often than {@link #MIN_INTERVAL_MS}.
 * Between answers the last one stays on screen.
 */
public class AlchemySuggestClient {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** The server search is seconds, not milliseconds; asking faster than this only queues work. */
    private static final long MIN_INTERVAL_MS = 4_000;

    /** One ranked craft, as the server's CraftSuggestion carries it. */
    public static class Craft {
        public final String elixirType;
        /** One entry per alchemical slot: "Chives", "Lye Ablution(Chives)". */
        public final List<String> slots;
        public final List<String> ingredients;
        public final double score;
        public final String rationale;

        Craft(String elixirType, List<String> slots, List<String> ingredients,
              double score, String rationale) {
            this.elixirType = elixirType;
            this.slots = slots;
            this.ingredients = ingredients;
            this.score = score;
            this.rationale = rationale;
        }
    }

    private volatile List<Craft> cached = null;
    private volatile String lastError = null;
    private volatile boolean fetching = false;
    private volatile long lastFetchAt = 0;
    /** The request the cached answer belongs to, so an unchanged pool is not re-asked. */
    private volatile String cachedRequest = null;

    public List<Craft> cached() {
        return cached;
    }

    public String lastError() {
        return lastError;
    }

    public boolean busy() {
        return fetching;
    }

    /** Throws away the answer and the memory of what was asked, so the next tick re-asks. */
    public void invalidate() {
        cached = null;
        cachedRequest = null;
        lastError = null;
    }

    /**
     * Re-asks if the ingredient pool has changed since the last answer and the throttle allows it.
     *
     * @param available every item name the player can reach. Unknown names cost nothing -- the
     *                  server matches them against the alchemy catalog and ignores the rest, which
     *                  is why this sends items rather than a client-side idea of what an ingredient
     *                  is: a catalog addition then needs no client change.
     */
    public void refresh(List<String> available, String mode, String types, String processes, int limit) {
        if (fetching)
            return;

        List<String> sorted = new ArrayList<String>(available);
        Collections.sort(sorted);
        String signature = mode + "|" + types + "|" + processes + "|" + limit + "|" + String.join(",", sorted);
        if (signature.equals(cachedRequest) && cached != null)
            return;
        if (System.currentTimeMillis() - lastFetchAt < MIN_INTERVAL_MS)
            return;

        fetching = true;
        lastFetchAt = System.currentTimeMillis();
        FoodService.scheduler.execute(() -> {
            try {
                List<Craft> result = fetchNow(sorted, mode, types, processes, limit);
                cached = result;
                cachedRequest = signature;
                lastError = null;
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                /* The signature is deliberately not stored on failure: a transient error should be
                 * retried on the next tick, not remembered as the answer for this pool. */
            } finally {
                lastFetchAt = System.currentTimeMillis();
                fetching = false;
            }
        });
    }

    private List<Craft> fetchNow(List<String> available, String mode, String types,
                                 String processes, int limit) throws Exception {
        String url = FoodService.endpointFor("alchemySuggest");
        if (url == null)
            throw new IllegalStateException("Mapper endpoint is not configured (Options > mapper)");

        /* Built by hand rather than with new JSONArray(available): the vendored org.json declares
         * that constructor as JSONArray(Collection<Object>), and generics being invariant, a
         * List<String> does not match it. Java then binds to JSONArray(Object array), which
         * compiles happily and throws "JSONArray initial value should be a string or collection or
         * array" at runtime because a List is not a Java array. */
        JSONArray names = new JSONArray();
        for (String name : available)
            names.put(name);

        JSONObject body = new JSONObject()
                .put("available", names)
                .put("mode", mode)
                .put("limit", limit);
        if (types != null && !types.isEmpty())
            body.put("types", types);
        if (processes != null && !processes.isEmpty())
            body.put("processes", processes);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "H&H Client");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200)
                throw new IllegalStateException("HTTP " + code);

            try (InputStream in = conn.getInputStream();
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) >= 0)
                    sb.append(buf, 0, n);
                return parse(new JSONArray(sb.toString()));
            }
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    private static List<Craft> parse(JSONArray arr) {
        List<Craft> out = new ArrayList<Craft>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Craft(
                        o.optString("elixirType", "?"),
                        strings(o.optJSONArray("slots")),
                        strings(o.optJSONArray("ingredients")),
                        o.optDouble("score", 0),
                        o.optString("rationale", "")));
            } catch (JSONException e) {
                /* One malformed row should not lose the rest of the ranking. */
            }
        }
        return out;
    }

    private static List<String> strings(JSONArray arr) {
        List<String> out = new ArrayList<String>();
        if (arr == null)
            return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (s != null)
                out.add(s);
        }
        return out;
    }
}
