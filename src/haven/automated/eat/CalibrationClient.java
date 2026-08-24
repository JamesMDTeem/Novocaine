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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fetches the server-measured Eating Helper calibration - {@code GET /client/{token}/
 * eatcalibration} - the same way {@link CookbookClient} fetches the food catalog: off
 * {@link FoodService#scheduler}, cached for the session, same endpoint/token. This is what
 * {@link EatLogService}-side aggregation (see the plan and {@code EatObserver}'s upload queue)
 * exists to produce: a variety-coefficient table with real sample counts instead of the wiki's
 * unverified one, and a satiation resource-to-category map, pooled across every character and
 * every tenant member who has EatObserver enabled.
 */
public class CalibrationClient {
    /** One variety-coefficient measurement for a hunger-level bucket. */
    public static final class VarietySample {
        public final double gmod;
        public final double coefficient;
        public final int samples;

        public VarietySample(double gmod, double coefficient, int samples) {
            this.gmod = gmod;
            this.coefficient = coefficient;
            this.samples = samples;
        }
    }

    public static final class Calibration {
        public final java.util.List<VarietySample> variety;
        public final Map<String, String> satiationCategoryMap;
        public final int eatRecordsAnalyzed;

        Calibration(java.util.List<VarietySample> variety, Map<String, String> satiationCategoryMap,
                    int eatRecordsAnalyzed) {
            this.variety = variety;
            this.satiationCategoryMap = satiationCategoryMap;
            this.eatRecordsAnalyzed = eatRecordsAnalyzed;
        }
    }

    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private static volatile Calibration cached = null;
    private static volatile long cachedAt = 0;
    /** Guards against two concurrent fetches. An AtomicBoolean rather than a volatile flag
     *  because the check-then-set on a volatile is not atomic: two callers arriving together
     *  both read false and both fetch. */
    private static final java.util.concurrent.atomic.AtomicBoolean fetching =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile String lastError = null;

    /** World the cached calibration was fetched for - see {@link #refreshIfStale()}. */
    private static volatile String cachedWorld = null;

    public static Calibration cached() {
        return cached;
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * Calibration is pooled per world - the satiation-category half of it is derived from that
     * world's foods - so a calibration fetched for another world is stale however fresh it is.
     */
    public static void refreshIfStale() {
        if (fetching.get())
            return;
        boolean sameWorld = java.util.Objects.equals(cachedWorld, FoodService.worldTag());
        if (cached != null && sameWorld && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS)
            return;
        fetch(null);
    }

    public static void fetch(Consumer<Calibration> onDone) {
        if (!fetching.compareAndSet(false, true))
            return;
        FoodService.scheduler.execute(() -> {
            try {
                String world = FoodService.worldTag();
                Calibration result = fetchNow();
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

    private static Calibration fetchNow() throws Exception {
        String endpoint = FoodService.cachedEndpoint();
        if (endpoint == null)
            throw new IllegalStateException("Cookbook endpoint not configured");
        String url = FoodService.endpointFor("eatcalibration");
        if (url == null)
            throw new IllegalStateException("Configured endpoint doesn't look like a food-upload URL: " + endpoint);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "H&H Client");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            String token = FoodService.cachedToken();
            if (token != null && !token.isEmpty())
                connection.setRequestProperty("Authorization", "Bearer " + token);

            int code = connection.getResponseCode();
            if (code != 200)
                throw new IllegalStateException("Calibration fetch failed: HTTP " + code);

            try (InputStream in = connection.getInputStream();
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) >= 0)
                    sb.append(buf, 0, n);
                return parse(new JSONObject(sb.toString()));
            }
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    private static Calibration parse(JSONObject o) {
        java.util.List<VarietySample> variety = new java.util.ArrayList<>();
        JSONArray varietyArr = o.optJSONArray("varietyCoefficients");
        if (varietyArr != null) {
            for (int i = 0; i < varietyArr.length(); i++) {
                try {
                    JSONObject v = varietyArr.getJSONObject(i);
                    variety.add(new VarietySample(v.getDouble("gmod"), v.getDouble("coefficient"), v.getInt("samples")));
                } catch (JSONException e) {
                    // One malformed row shouldn't lose the rest.
                }
            }
        }

        Map<String, String> satiationMap = new LinkedHashMap<>();
        JSONObject satObj = o.optJSONObject("satiationCategoryMap");
        if (satObj != null) {
            for (String key : satObj.keySet()) {
                String val = satObj.optString(key, null);
                if (val != null)
                    satiationMap.put(key, val);
            }
        }

        return new Calibration(variety, satiationMap, o.optInt("eatRecordsAnalyzed", 0));
    }
}
