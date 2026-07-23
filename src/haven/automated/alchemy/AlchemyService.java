package haven.automated.alchemy;

import haven.OptWnd;
import haven.UI;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Mirrors the Alchemy Book into a dump file.
 *
 * Supersedes the Phase-1 item-pipeline dumper, which hooked {@link haven.GItem#info()}
 * on the assumption that book rows were ordinary items. They are not -- see
 * {@link AlchemyBook} -- so that hook only ever saw physical inventory items and is gone.
 *
 * Polling is passive: the book model is populated from server uimsg at login and
 * survives the window being closed ({@code BookWindow.reqclose} hides rather than
 * destroys), so nothing needs to be opened or hovered.
 *
 * Driven from {@link haven.GameUI#tick} to stay on the UI thread; see {@link AlchemyBook}
 * for why a background timer would be unsafe.
 */
public class AlchemyService {
    public static volatile boolean ENABLED = true;

    private static final String OUT_FILE = "alchemy-book-dump.json";
    private static final double POLL_INTERVAL = 5.0;

    private static double since = 0.0;
    private static int lastHash = 0;
    private static boolean warned = false;

    /** Called every frame from GameUI.tick; self-throttles to POLL_INTERVAL. */
    public static void poll(UI ui, double dt) {
        if (!ENABLED)
            return;
        since += dt;
        if (since < POLL_INTERVAL)
            return;
        since = 0.0;

        try {
            JSONObject snap = AlchemyBook.snapshot(ui);
            if (snap == null)
                return;

            int ingredients = snap.getJSONArray("ingredients").length();
            int elixirs = snap.getJSONArray("elixirs").length();
            if (ingredients == 0 && elixirs == 0)
                return;

            String json = snap.toString(2);
            int hash = json.hashCode();
            if (hash == lastHash)
                return;
            lastHash = hash;

            Path out = Paths.get(OUT_FILE);
            Files.write(out, json.getBytes(StandardCharsets.UTF_8));
            System.out.println("[Alchemy] " + ingredients + " ingredients, " + elixirs
                    + " elixirs -> " + out.toAbsolutePath());

            upload(snap);
        } catch (ReflectiveOperationException e) {
            // the server changed ui/alchbook out from under us; say so once rather
            // than silently reporting an empty book every 5s
            if (!warned) {
                warned = true;
                System.err.println("[Alchemy] book model no longer matches expectations, "
                        + "hook disabled -- rerun check-alchbook-contract.sh: " + e);
            }
            ENABLED = false;
        } catch (Exception e) {
            if (!warned) {
                warned = true;
                System.err.println("[Alchemy] dump failed: " + e);
            }
        }
    }

    /**
     * Alchemy upload URL, derived from the already-configured Cookbook endpoint.
     *
     * Both are the same mapper token URL with a different suffix
     * ({@code {server}/client/{token}/food} vs {@code .../alchemyUpload}), so reusing
     * it means existing users need no new setting and no third token to paste.
     * Returns null when the cookbook endpoint is unset or not the expected shape,
     * in which case we simply do not upload.
     */
    private static String endpoint() {
        if (OptWnd.cookBookEndpointTextEntry == null)
            return null;
        String raw = OptWnd.cookBookEndpointTextEntry.buf.line();
        if (raw == null)
            return null;
        raw = raw.trim();
        if (!raw.endsWith("/food"))
            return null;
        return raw.substring(0, raw.length() - "/food".length()) + "/alchemyUpload";
    }

    /** Reshapes a book snapshot into the mapper's AlchemyUploadDto. */
    private static JSONObject payload(JSONObject snap) {
        JSONArray discoveries = new JSONArray();
        JSONArray ingredients = snap.getJSONArray("ingredients");
        for (int i = 0; i < ingredients.length(); i++) {
            JSONObject ik = ingredients.getJSONObject(i);
            String name = ik.getJSONObject("input").getString("name");
            JSONArray effs = ik.getJSONArray("effects");
            for (int j = 0; j < effs.length(); j++) {
                // slot is left unset: the book lists effects but does not reveal
                // which of the four hidden slots each one occupies
                discoveries.put(new JSONObject()
                        .put("ingredient", name)
                        .put("effect", effs.getString(j)));
            }
        }

        JSONArray experiments = new JSONArray();
        JSONArray elixirs = snap.getJSONArray("elixirs");
        for (int i = 0; i < elixirs.length(); i++) {
            JSONObject rcp = elixirs.getJSONObject(i);
            String type = elixirType(rcp.getJSONObject("elixir").getString("res"));
            if (type == null)
                continue;
            // Send the tree, not just leaf names. A slot may hold a processed
            // ingredient (Lye Ablution, Measured Distillate, ...) which contributes
            // only part of its base ingredient's four properties, so flattening would
            // assert effects that were never in play.
            // "negatives" is deliberately dropped: it rolls at random whenever the
            // inputs carried unmatched effects, so it says nothing about which
            // effects an ingredient holds.
            experiments.put(new JSONObject()
                    .put("type", type)
                    .put("inputs", rcp.getJSONArray("inputs"))
                    .put("effects", rcp.getJSONArray("effects")));
        }

        return new JSONObject()
                .put("discoveries", discoveries)
                .put("experiments", experiments);
    }

    /** Maps an elixir's craft resource to the mapper's elixir type tag. */
    private static String elixirType(String res) {
        String r = res.toLowerCase();
        if (r.contains("decoction"))
            return "decoction";
        if (r.contains("mercurial"))
            return "mercurial";
        if (r.contains("swill"))
            return "swill";
        return null;
    }

    /** POSTs off the UI thread; snap is already detached so it is safe to hand over. */
    private static void upload(JSONObject snap) {
        String url = endpoint();
        if (url == null)
            return;
        String body = payload(snap).toString();
        new Thread(() -> post(url, body), "alchemy-upload").start();
    }

    private static void post(String url, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "H&H Client");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                System.out.println("[Alchemy] upload failed: HTTP " + code + " -> " + url);
            } else {
                System.out.println("[Alchemy] uploaded " + body.length() + " bytes");
            }
        } catch (Exception e) {
            System.out.println("[Alchemy] upload error: " + e);
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
}
