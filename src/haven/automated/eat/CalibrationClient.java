package haven.automated.eat;

import haven.automated.cookbook.FoodService;
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
 * Fetches the server-side Eating Helper calibration - {@code GET /client/{token}/eatcalibration} -
 * the same way {@link CookbookClient} fetches the food catalog: off {@link FoodService#scheduler},
 * cached for the session, same endpoint/token.
 *
 * What this fetches changed when the variety reduction stopped being a thing to measure. It used
 * to be a coefficient table the client chose between the wiki's guesses and the server's pooled
 * measurements with. The reduction is now a closed form ({@link EatPlanner#varietyStep}), so there
 * is no table to serve and nothing to choose - the server instead replays the same pooled log
 * <i>against</i> that form and reports how well it holds. The client cares about exactly one thing
 * from that: whether it still holds. A residual that starts drifting means a game patch moved the
 * constant, and the status line should say so rather than every plan quietly being wrong.
 *
 * The satiation resource-to-name map is unchanged and is the other half of the response.
 */
public class CalibrationClient {
    /**
     * How the server's pooled eat log scores against {@link EatPlanner#varietyStep}. This is a
     * check, not an input: nothing here feeds a plan.
     */
    public static final class VarietyResidual {
        /** Cap-decrease events replayed. */
        public final int samples;
        /** How many of those matched the formula for some integer m. */
        public final int matched;
        /** Mean absolute error, in cap points, over the matched events. */
        public final double meanAbsError;
        /** Median of {@code dcap^2 / (gmod * topStat)} over fresh-bar events; expected 0.4. */
        public final double constant;

        public VarietyResidual(int samples, int matched, double meanAbsError, double constant) {
            this.samples = samples;
            this.matched = matched;
            this.meanAbsError = meanAbsError;
            this.constant = constant;
        }

        /** True when the pooled log still agrees with the formula the planner is using. */
        public boolean holds() {
            return samples <= 0
                    || (matched >= samples * 0.98 && Math.abs(constant - EatPlanner.VARIETY_CONST) < 0.01);
        }
    }

    public static final class Calibration {
        /** Null when the server has no eat records to check against yet. */
        public final VarietyResidual varietyResidual;
        public final Map<String, String> satiationCategoryMap;
        public final int eatRecordsAnalyzed;

        Calibration(VarietyResidual varietyResidual, Map<String, String> satiationCategoryMap,
                    int eatRecordsAnalyzed) {
            this.varietyResidual = varietyResidual;
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
        VarietyResidual residual = null;
        JSONObject r = o.optJSONObject("varietyResidual");
        if (r != null) {
            try {
                residual = new VarietyResidual(r.getInt("samples"), r.getInt("matched"),
                        r.getDouble("meanAbsError"), r.getDouble("constant"));
            } catch (JSONException e) {
                // A malformed residual costs the check, not the satiation map underneath it.
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

        return new Calibration(residual, satiationMap, o.optInt("eatRecordsAnalyzed", 0));
    }
}
