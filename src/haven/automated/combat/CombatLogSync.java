package haven.automated.combat;

import haven.Client;
import haven.Config;
import haven.Utils;
import haven.automated.cookbook.WorldTag;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Background uploader for finished combat logs.
 *
 * Each finished fight is a .jsonl file under {@code <gameDir>/CombatLogs}. This class posts
 * it to the mapper server's {@code /combatlog} endpoint and backfills previously pooled logs
 * on launch. Every entry point is a no-op when the feature is disabled and never throws
 * into the caller.
 *
 * <p>The local file is staging, not storage: a log the server has confirmed it holds is
 * deleted from disk, either straight after its own 200 or - for logs uploaded by an older
 * client - when the launch backfill finds its fightId already in {@code /combatlog/ids}.
 * Confirmation is the only trigger. A log that fails to upload for any reason stays on
 * disk and is retried by the next launch, so a broken token or an offline server costs
 * nothing but disk.
 *
 * Threading: {@link CombatRecorder#stop()} calls {@link #enqueue(Path)} on the UI/message
 * thread. That method only enqueues a task (microseconds); all file I/O and network I/O
 * runs on a single daemon scheduler thread so gameplay is never stalled.
 */
public final class CombatLogSync {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int MAX_LINES = 40000;
    private static final long BACKFILL_GAP_MS = 500L;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "combat-log-sync");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean backfillStarted = false;

    private CombatLogSync() {}

    /**
     * Enqueue a finished combat log for upload. Must return in microseconds; all I/O
     * happens on the scheduler thread. Never propagates.
     */
    public static void enqueue(Path path) {
        if (path == null)
            return;
        if (shouldSkip())
            return;
        try {
            scheduler.execute(() -> doUpload(path));
        } catch (RejectedExecutionException e) {
            // scheduler shut down — drop silently
        }
    }

    /**
     * Enqueue a combat-deck dump for upload. Same contract as {@link #enqueue}: returns in
     * microseconds, all I/O on the scheduler thread, never propagates.
     *
     * A deck is what makes a fight readable. The log says which move was thrown; only the deck
     * says what LEVEL that move was, and mu - a factor in every attack weight - cannot be
     * recovered without it. Uploading fights without decks is what left 2418 of 3022 pooled
     * fights unusable for every level-keyed measurement.
     */
    public static void enqueueDeck(Path path) {
        if (path == null)
            return;
        if (shouldSkip())
            return;
        try {
            scheduler.execute(() -> doUploadDeck(path));
        } catch (RejectedExecutionException e) {
            // scheduler shut down - drop silently
        }
    }

    /**
     * Launch-time backfill: uploads every *.jsonl under CombatLogs that has a terminal
     * {@code end} line and whose fightId is not already on the server. Runs once per
     * launch; re-entry is guarded. Sequential with 500 ms gaps.
     *
     * Called from {@link haven.Config#initAutomapper} after {@link haven.automated.mapper.MappingClient}
     * init, because that is the established per-character launch hook (called from
     * Charlist, AltManager and GameUI after WorldTag is set) and has the UI context.
     * Guarded by a volatile boolean so multiple initAutomapper calls per session are idempotent.
     */
    public static void backfill() {
        if (backfillStarted)
            return;
        synchronized (CombatLogSync.class) {
            if (backfillStarted)
                return;
            backfillStarted = true;
        }
        if (shouldSkip())
            return;
        try {
            scheduler.execute(CombatLogSync::doBackfill);
        } catch (RejectedExecutionException e) {
            // drop silently
        }
    }

    private static boolean shouldSkip() {
        if (!Utils.getprefb("combatTelemetry", true))
            return true;
        String ep = Utils.getpref("webMapEndpoint", "");
        return ep == null || ep.trim().isEmpty();
    }

    /**
     * Delete a log the server has confirmed it holds.
     *
     * The local file is a staging area, not an archive: once the fight is on the server
     * it is the server's copy that the estimator reads, and leaving the local one behind
     * only grows {@code <gameDir>/CombatLogs} without bound. Deletion is therefore tied
     * strictly to confirmation - a 200 from {@code /combatlog}, or the fightId appearing
     * in {@code /combatlog/ids} - and never to merely having attempted an upload. A log
     * that fails to upload stays on disk and is retried by the next launch's backfill.
     */
    private static void deleteUploaded(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            // A log we cannot delete is harmless: the server already has it, so the next
            // backfill sees its id in the remote set and skips straight back to here.
            System.out.println("[CombatLogSync] could not delete uploaded log (debug): " + e.getMessage());
        }
    }

    private static void doUpload(Path path) {
        try {
            if (shouldSkip())
                return;
            if (path == null || !Files.exists(path))
                return;
            long sz = Files.size(path);
            if (sz > MAX_BYTES) {
                System.out.println("[CombatLogSync] skip >4MB: " + path);
                return;
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() > MAX_LINES) {
                System.out.println("[CombatLogSync] skip >20000 lines: " + path);
                return;
            }
            if (lines.isEmpty())
                return;
            String endpoint = combatLogEndpoint();
            if (endpoint == null)
                return;
            byte[] body = buildPayload(path, lines);
            if (body == null)
                return;
            if (body.length > MAX_BYTES) {
                System.out.println("[CombatLogSync] skip payload >4MB: " + path);
                return;
            }
            String token = bearerToken();
            if (postWithRetry(endpoint, body, token))
                deleteUploaded(path);
        } catch (Exception e) {
            System.out.println("[CombatLogSync] upload failed (debug): " + e.getMessage());
        }
    }

    /**
     * The deck-dump filename stem, minus the "deck-" prefix: "&lt;character&gt;-&lt;wall&gt;".
     * This is the server's DeckId, and it is also what sync_pool names the pulled file, so the
     * three sides agree without anyone parsing the document to find out what it is.
     */
    private static String deckIdFromPath(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".json"))
            name = name.substring(0, name.length() - ".json".length());
        if (name.startsWith("deck-"))
            name = name.substring("deck-".length());
        return name;
    }

    private static void doUploadDeck(Path path) {
        try {
            if (shouldSkip())
                return;
            if ((path == null) || !Files.exists(path))
                return;
            long sz = Files.size(path);
            // A 40-card sheet is tens of KB. Anything at 4MB is not a deck.
            if (sz > MAX_BYTES) {
                System.out.println("[CombatLogSync] skip deck >4MB: " + path);
                return;
            }
            String doc = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (doc.trim().isEmpty())
                return;
            String endpoint = combatDeckEndpoint();
            if (endpoint == null)
                return;
            String deckId = deckIdFromPath(path);
            JSONObject src = new JSONObject(doc);
            JSONObject body = src.optJSONObject("body");
            String characterId = (body == null) ? "" : body.optString("char", "");
            if (characterId.isEmpty())
                return;
            String world = WorldTag.current();
            JSONObject payload = new JSONObject();
            payload.put("characterId", characterId);
            payload.put("deckId", deckId);
            payload.put("wall", src.optLong("wall", 0L));
            if (world == null)
                payload.put("world", JSONObject.NULL);
            else
                payload.put("world", world);
            payload.put("payload", doc);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) {
                System.out.println("[CombatLogSync] skip deck payload >4MB: " + path);
                return;
            }
            // Deliberately NOT deleted on success, unlike a fight log. A dump is a few tens of
            // KB, there are only a few hundred of them, and they are the local record of what
            // this character's cards were worth at a given moment - the thing every level-keyed
            // measurement is read against. Cheap to keep, expensive to be wrong about.
            postWithRetry(endpoint, bytes, bearerToken());
        } catch (Exception e) {
            System.out.println("[CombatLogSync] deck upload failed (debug): " + e.getMessage());
        }
    }

    private static byte[] buildPayload(Path path, List<String> lines) {
        try {
            String first = lines.get(0);
            String characterId = parseCharacterId(first);
            String world = WorldTag.current();
            String fightId = fightIdFromPath(path);
            JSONObject payload = new JSONObject();
            payload.put("characterId", characterId);
            if (world == null)
                payload.put("world", JSONObject.NULL);
            else
                payload.put("world", world);
            payload.put("fightId", fightId);
            JSONArray arr = new JSONArray();
            for (String line : lines)
                arr.put((Object) line);
            payload.put("lines", arr);
            return payload.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[CombatLogSync] payload build failed: " + e.getMessage());
            return null;
        }
    }

    private static String parseCharacterId(String firstLine) {
        try {
            JSONObject o = new JSONObject(firstLine);
            if (o.has("char") && !o.isNull("char"))
                return o.getString("char");
        } catch (Exception e) {
            // fall through
        }
        return "";
    }

    private static String fightIdFromPath(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".jsonl"))
            return name.substring(0, name.length() - ".jsonl".length());
        return name;
    }

    /**
     * The endpoints this class can be pointed at, longest first.
     *
     * The stored pref is whatever the player pasted, which may already name any one of these
     * (or {@code /food}, the setting's original purpose). Stripping the longest match first
     * matters: {@code /combatlog} is a prefix of {@code /combatlog/ids}, so testing it first
     * would leave a stray {@code /ids} behind.
     */
    private static final String[] KNOWN_SUFFIXES = {
        "/combatlog/ids", "/combatdeck/ids", "/combatlog", "/combatdeck", "/food"
    };

    /**
     * The client base URL with {@code suffix} on it, or null when nothing is configured.
     *
     * One derivation for four endpoints. The two hand-written ones this replaced had already
     * drifted into differently-shaped special cases for the same inputs, and adding a third
     * and fourth copy for the deck endpoints would have made four places to get it wrong.
     */
    private static String clientEndpoint(String suffix) {
        String raw = Utils.getpref("webMapEndpoint", "");
        if (raw == null)
            return null;
        raw = raw.trim();
        if (raw.isEmpty())
            return null;
        int q = raw.indexOf('?');
        String path = (q < 0) ? raw : raw.substring(0, q);
        String query = (q < 0) ? "" : raw.substring(q);
        while (path.endsWith("/") && path.length() > 1)
            path = path.substring(0, path.length() - 1);
        for (String known : KNOWN_SUFFIXES) {
            if (path.endsWith(known)) {
                path = path.substring(0, path.length() - known.length());
                break;
            }
        }
        while (path.endsWith("/") && path.length() > 1)
            path = path.substring(0, path.length() - 1);
        return withWorld(path + suffix + query);
    }

    private static String combatLogEndpoint() {
        return clientEndpoint("/combatlog");
    }

    private static String combatLogIdsEndpoint() {
        return clientEndpoint("/combatlog/ids");
    }

    private static String combatDeckEndpoint() {
        return clientEndpoint("/combatdeck");
    }

    private static String combatDeckIdsEndpoint() {
        return clientEndpoint("/combatdeck/ids");
    }

    private static String withWorld(String url) {
        if (url == null)
            return null;
        String world = WorldTag.current();
        if (world == null || hasWorldParam(url))
            return url;
        String sep = (url.indexOf('?') < 0) ? "?" : "&";
        return url + sep + "world=" + URLEncoder.encode(world, StandardCharsets.UTF_8);
    }

    private static boolean hasWorldParam(String url) {
        int q = url.indexOf('?');
        if (q < 0)
            return false;
        for (String param : url.substring(q + 1).split("&")) {
            if (param.equals("world") || param.startsWith("world="))
                return true;
        }
        return false;
    }

    private static String bearerToken() {
        String raw = Utils.getpref("webMapEndpoint", "");
        if (raw == null)
            return "";
        int idx = raw.indexOf("/client/");
        if (idx >= 0) {
            int s = idx + "/client/".length();
            int e = raw.indexOf('/', s);
            int q = raw.indexOf('?', s);
            if (e < 0 || (q >= 0 && q < e))
                e = q;
            if (e < 0)
                e = raw.length();
            String tok = raw.substring(s, e).trim();
            if (!tok.isEmpty())
                return tok;
        }
        return "";
    }

    /**
     * @return true only if the server accepted the log with HTTP 200. Anything else -
     *         throttled twice, a non-200, a timeout, a transport failure - is false, and
     *         the caller must leave the local file alone so the next backfill retries it.
     */
    private static boolean postWithRetry(String url, byte[] body, String token) {
        try {
            int code = doPost(url, body, token);
            if (code == 429) {
                long retryAfter = lastRetryAfterSeconds;
                long delayMs = (retryAfter > 0) ? retryAfter * 1000L : 3000L;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                int retryCode = doPost(url, body, token);
                if (retryCode == 429) {
                    System.out.println("[CombatLogSync] throttled twice, dropping: " + url);
                }
                return retryCode == 200;
            }
            return code == 200;
        } catch (java.net.SocketTimeoutException e) {
            System.out.println("[CombatLogSync] timeout (debug): " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.out.println("[CombatLogSync] post failed (debug): " + e.getMessage());
            return false;
        }
    }

    private static volatile long lastRetryAfterSeconds = -1;

    private static int doPost(String url, byte[] body, String token) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setRequestProperty("User-Agent", Config.confid);
            if (token != null && !token.isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            // capture Retry-After for 429
            lastRetryAfterSeconds = conn.getHeaderFieldLong("Retry-After", -1L);
            if (code != 200) {
                // drain error stream to reuse connection
                try {
                    InputStream err = conn.getErrorStream();
                    if (err != null)
                        err.close();
                } catch (Exception e) {
                    // ignore
                }
                if (code != 429)
                    System.out.println("[CombatLogSync] non-200 (debug): HTTP " + code + " " + url);
                return code;
            }
            // drain input on success
            try (InputStream in = conn.getInputStream()) {
                // discard
                byte[] buf = new byte[1024];
                while (in.read(buf) != -1) {}
            }
            return code;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    private static void doBackfill() {
        try {
            if (shouldSkip())
                return;
            String idsUrl = combatLogIdsEndpoint();
            if (idsUrl == null)
                return;
            String token = bearerToken();
            Set<String> remoteIds = fetchIds(idsUrl, token);
            if (remoteIds == null)
                return;
            Path dir = combatLogDir();
            if (!Files.exists(dir) || !Files.isDirectory(dir))
                return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jsonl")) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    if (name.startsWith("Deck-"))
                        continue;
                    String fightId = fightIdFromPath(p);
                    if (remoteIds.contains(fightId)) {
                        // The server already holds this fight, which is precisely the
                        // condition deleteUploaded waits for. Collect it here rather than
                        // letting logs from before the delete-on-upload change pile up:
                        // without this, every log uploaded by an older client stays on disk
                        // for ever, because it is skipped by exactly this branch.
                        deleteUploaded(p);
                        continue;
                    }
                    // size and line caps are enforced in doUpload; check end-line here
                    // to avoid uploading incomplete fights
                    if (!hasTerminalEnd(p))
                        continue;
                    doUpload(p);
                    try {
                        Thread.sleep(BACKFILL_GAP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            doBackfillDecks(dir, token);
        } catch (Exception e) {
            System.out.println("[CombatLogSync] backfill failed (debug): " + e.getMessage());
        }
    }

    /**
     * The deck half of the launch backfill.
     *
     * Runs after the fights rather than beside them so a first sync sends the logs first: a
     * deck with no fights to explain is worth less than fights with no decks, and if the run is
     * interrupted that is the better half to have finished. Decks are small and few, so this
     * costs a fraction of what the fight backfill does.
     */
    private static void doBackfillDecks(Path dir, String token) {
        try {
            String idsUrl = combatDeckIdsEndpoint();
            if (idsUrl == null)
                return;
            Set<String> remoteIds = fetchIds(idsUrl, token);
            if (remoteIds == null)
                return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "deck-*.json")) {
                for (Path p : ds) {
                    if (remoteIds.contains(deckIdFromPath(p)))
                        continue;
                    doUploadDeck(p);
                    try {
                        Thread.sleep(BACKFILL_GAP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[CombatLogSync] deck backfill failed (debug): " + e.getMessage());
        }
    }

    private static Set<String> fetchIds(String idsUrl, String token) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(idsUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", Config.confid);
            if (token != null && !token.isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            if (code != 200) {
                System.out.println("[CombatLogSync] ids fetch non-200 (debug): HTTP " + code);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null)
                    sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            Set<String> out = new HashSet<>();
            for (int i = 0; i < arr.length(); i++)
                out.add(arr.getString(i));
            return out;
        } catch (Exception e) {
            System.out.println("[CombatLogSync] ids fetch failed (debug): " + e.getMessage());
            return null;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    private static Path combatLogDir() {
        // Mirror CombatRecorder.start exactly: Paths.get(Client.gameDir, "CombatLogs", ...).
        // The client launches with CWD=bin and gameDir="" non-Steam, so a relative
        // "CombatLogs" resolves to bin/CombatLogs. Do NOT prepend "bin" here.
        String gd = Client.gameDir;
        if (gd == null || gd.trim().isEmpty())
            return Paths.get("CombatLogs");
        return Paths.get(gd, "CombatLogs");
    }

    private static boolean hasTerminalEnd(Path path) {
        try {
            long sz = Files.size(path);
            if (sz > MAX_BYTES)
                return false;
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() > MAX_LINES)
                return false;
            if (lines.isEmpty())
                return false;
            // terminal end line: last non-empty line contains ev=end
            for (int i = lines.size() - 1; i >= 0; i--) {
                String l = lines.get(i).trim();
                if (l.isEmpty())
                    continue;
                if (l.contains("\"ev\"") && l.contains("\"end\""))
                    return true;
                // last non-empty line is not an end event
                return false;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
