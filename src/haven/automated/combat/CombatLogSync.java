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
            postWithRetry(endpoint, body, token);
        } catch (Exception e) {
            System.out.println("[CombatLogSync] upload failed (debug): " + e.getMessage());
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

    private static String combatLogEndpoint() {
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
        if (path.endsWith("/food")) {
            path = path.substring(0, path.length() - "/food".length()) + "/combatlog";
        } else if (!path.endsWith("/combatlog")) {
            // handle already /combatlog/ids case
            if (path.endsWith("/combatlog/ids")) {
                path = path.substring(0, path.length() - "/ids".length());
            } else {
                path = path + "/combatlog";
            }
        }
        return withWorld(path + query);
    }

    private static String combatLogIdsEndpoint() {
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
        if (path.endsWith("/food")) {
            path = path.substring(0, path.length() - "/food".length()) + "/combatlog/ids";
        } else if (path.endsWith("/combatlog")) {
            path = path + "/ids";
        } else if (path.endsWith("/combatlog/ids")) {
            // already ids
        } else {
            path = path + "/combatlog/ids";
        }
        return withWorld(path + query);
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

    private static void postWithRetry(String url, byte[] body, String token) {
        try {
            int code = doPost(url, body, token);
            if (code == 429) {
                long retryAfter = lastRetryAfterSeconds;
                long delayMs = (retryAfter > 0) ? retryAfter * 1000L : 3000L;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                int retryCode = doPost(url, body, token);
                if (retryCode == 429) {
                    System.out.println("[CombatLogSync] throttled twice, dropping: " + url);
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            System.out.println("[CombatLogSync] timeout (debug): " + e.getMessage());
        } catch (Exception e) {
            System.out.println("[CombatLogSync] post failed (debug): " + e.getMessage());
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
                    if (remoteIds.contains(fightId))
                        continue;
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
        } catch (Exception e) {
            System.out.println("[CombatLogSync] backfill failed (debug): " + e.getMessage());
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
