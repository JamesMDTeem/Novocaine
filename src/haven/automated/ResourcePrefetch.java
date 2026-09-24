package haven.automated;

import haven.ResCache;
import haven.Resource;
import haven.Utils;
import haven.automated.cookbook.FoodService;
import haven.automated.nbots.core.NLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resource pre-download, shared through the crew's server (after brodgar-io-client's).
 *
 * A client stalls the first time it meets something whose resource it has never fetched. Every
 * client here tells the server which resources it loads from the game (names and versions only,
 * once each per session); a client with "Pre-download resources the crew has seen" on pulls that
 * list once per session and fetches whatever it does not hold yet, in the background, through the
 * game's own loader - so the file lands in the ordinary resource cache exactly as if it had been
 * met in play. Brodgar ships a pack of the files from its own site; here the server keeps names
 * only, and every file still comes from the game.
 *
 * Reporting happens whenever the cookbook endpoint is configured, since that endpoint is the
 * crew's server and names are harmless. Pre-downloading is on by default and can be switched off
 * in Server Integration; on a fresh install it can mean a few hundred megabytes, paced at
 * {@link #PACE_MS} between fetches so it never competes with a terrain crossing for the loader.
 */
public class ResourcePrefetch {
    public static final String PREF = "preDownloadResources";

    /** On unless the player has turned it off. */
    public static boolean enabled() {
        return Utils.getprefb(PREF, true);
    }

    private static final int PACE_MS = 40;
    private static final int BATCH = 2000;

    private static final Set<String> seen = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Object[]> pending = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "resource-prefetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    static {
        exec.scheduleWithFixedDelay(ResourcePrefetch::flush, 60, 60, TimeUnit.SECONDS);
    }

    /** Called by Resource.Pool for every resource it loaded from the cache or the network. */
    public static void loaded(String name, int ver) {
        if ((name == null) || !seen.add(name))
            return;
        pending.add(new Object[] {name, ver});
    }

    /** Called from GameUI.tick: starts this session's pre-download once, if it is wanted. */
    public static void maybeStart() {
        if (started.get() || !enabled() || endpoint() == null)
            return;
        if (started.compareAndSet(false, true))
            exec.submit(ResourcePrefetch::prefetch);
    }

    private static String endpoint() {
        return FoodService.siblingEndpoint(FoodService.cachedEndpoint(), "resnames");
    }

    private static void flush() {
        try {
            String url = endpoint();
            if (url == null || pending.isEmpty())
                return;
            while (!pending.isEmpty()) {
                JSONArray names = new JSONArray();
                Object[] e;
                while (names.length() < BATCH && (e = pending.poll()) != null)
                    names.put(new JSONObject().put("n", e[0]).put("v", e[1]));
                HttpURLConnection c = open(url);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(new JSONObject().put("names", names).toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                c.disconnect();
                if (code / 100 != 2)
                    return;   /* this batch is lost; the next session reports it again */
            }
        } catch (Exception e) {
            /* Best effort: a server that is down costs nothing but a later report. */
        }
    }

    private static void prefetch() {
        String url = endpoint();
        if (url == null)
            return;
        List<Object[]> want = new ArrayList<>();
        try {
            HttpURLConnection c = open(url);
            if (c.getResponseCode() / 100 != 2)
                return;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                in.transferTo(buf);
            }
            JSONArray list = new JSONArray(buf.toString(StandardCharsets.UTF_8));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                want.add(new Object[] {o.getString("n"), o.optInt("v", -1)});
            }
        } catch (Exception e) {
            NLog.log("prefetch.log", "could not read the crew's resource list: " + e);
            return;
        }
        int have = 0, got = 0, failed = 0;
        long t0 = System.currentTimeMillis();
        for (Object[] w : want) {
            if (!enabled())
                break;   /* switched off mid-run */
            String name = (String) w[0];
            if (cached(name)) {
                have++;
                continue;
            }
            try {
                Resource.remote().loadwait(name, (Integer) w[1]);
                got++;
            } catch (RuntimeException e) {
                failed++;
            }
            try {
                Thread.sleep(PACE_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        NLog.log("prefetch.log", String.format("pre-download: %d listed, %d already held, %d fetched, %d failed, %.0fs",
            want.size(), have, got, failed, (System.currentTimeMillis() - t0) / 1000.0));
    }

    /** Whether the local resource cache already holds a name, without loading it. */
    private static boolean cached(String name) {
        ResCache cache = Resource.cache();
        if (cache == null)
            return false;
        try (InputStream in = cache.fetch("res/" + name)) {
            return in.read() >= 0;
        } catch (FileNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "H&H Client");
        String token = FoodService.cachedToken();
        if (token != null && !token.isEmpty())
            c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }
}
