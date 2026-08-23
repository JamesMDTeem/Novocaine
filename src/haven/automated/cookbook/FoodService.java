package haven.automated.cookbook;

import haven.Defer;
import haven.ItemInfo;
import haven.OptWnd;
import haven.Resource;
import haven.res.ui.tt.q.qbuff.QBuff;
import haven.resutil.FoodInfo;
import org.json.JSONArray;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FoodService {
    private static final Map<String, ParsedFoodInfo> cachedItems = new ConcurrentHashMap<>();
    private static final Queue<HashedFoodInfo> sendQueue = new ConcurrentLinkedQueue<>();

    private static final boolean cookbookDebug = false;

    /** Number of threads in the scheduler pool. */
    private static final int SCHEDULER_THREADS = 2;

    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(SCHEDULER_THREADS);

    /** Haven item quality is on a 0-10 scale; 10.0 is the maximum, used both as the
     * default when no quality buff is present and as the divisor that normalizes quality. */
    private static final double QUALITY_SCALE = 10.0;

    /** Food fractions (energy, ingredient amounts) are reported as percentages on a 0-100 scale. */
    private static final int PERCENT_SCALE = 100;

    /** Multiplier/divisor that rounds a value to two decimal places. */
    private static final double ROUND_2DP_SCALE = 100.0;

    /** A cookbook endpoint shorter than this is not treated as a usable URL. */
    private static final int MIN_ENDPOINT_LENGTH = 5;

    /** How often the queued food items are flushed to the cookbook endpoint. */
    private static final long SEND_INTERVAL_SECONDS = 10L;

    /** Hunger scale factor applied to glutton values. */
    private static final int HUNGER_SCALE = 1000;

    /** HTTP status code indicating success. */
    private static final int HTTP_OK = 200;

    /** Radix for hexadecimal string conversion. */
    private static final int HEX_RADIX = 16;

    /**
     * Cached endpoint URL for scheduler-thread use. Updated from the UI thread via
     * {@link #refreshEndpointCache()} to avoid accessing {@code OptWnd} widget text
     * fields from a background thread.
     */
    private static volatile String cachedEndpoint = null;
    private static volatile String cachedToken = "";

    static {
        scheduler.scheduleAtFixedRate(FoodService::sendItems, SEND_INTERVAL_SECONDS, SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Snapshot the current endpoint and token from OptWnd into volatile cache fields.
     * Must be called from the UI thread (it reads widget text buffers).
     */
    public static void refreshEndpointCache() {
        if ((OptWnd.cookBookEndpointTextEntry == null) || (OptWnd.cookBookTokenTextEntry == null)) {
            cachedEndpoint = null;
            cachedToken = "";
            return;
        }
        String raw = OptWnd.cookBookEndpointTextEntry.buf.line();
        cachedEndpoint = (raw != null && raw.trim().length() >= MIN_ENDPOINT_LENGTH) ? raw.trim() : null;
        String tk = OptWnd.cookBookTokenTextEntry.buf.line();
        cachedToken = (tk != null) ? tk.trim() : "";
    }

    /** The endpoint {@link #refreshEndpointCache()} last snapshotted, or null if unconfigured. */
    public static String cachedEndpoint() {
        return cachedEndpoint;
    }

    /** The token {@link #refreshEndpointCache()} last snapshotted (may be empty). */
    public static String cachedToken() {
        return cachedToken;
    }

    /** The path segment a configured cookbook endpoint ends in. */
    private static final String FOOD_SEGMENT = "/food";

    /**
     * Rewrites the configured food-upload endpoint into one of its siblings under the same
     * {@code /client/{token}/} path, carrying any query string across: {@code .../food?world=W16}
     * plus {@code "cookbook"} gives {@code .../cookbook?world=W16}.
     *
     * The query string is the part that matters. The server namespaces the cookbook by world
     * and takes the world as {@code ?world=}, so a player with clients in two live worlds
     * points each install's endpoint at a different one - and every sibling call has to land
     * on the same world the uploads did, or the Eating Helper plans against the wrong catalog.
     *
     * @return the sibling URL, or null when the configured value is not a food-upload URL.
     */
    public static String siblingEndpoint(String endpoint, String segment) {
        if (endpoint == null) {
            return null;
        }
        int q = endpoint.indexOf('?');
        String path = (q < 0) ? endpoint : endpoint.substring(0, q);
        String query = (q < 0) ? "" : endpoint.substring(q);
        if (!path.endsWith(FOOD_SEGMENT)) {
            return null;
        }
        return path.substring(0, path.length() - FOOD_SEGMENT.length()) + "/" + segment + query;
    }

    /**
     * The URL for one client endpoint segment ("cookbook", "eatcalibration", ...), tagged with
     * the world of the character being played. Null when no usable endpoint is configured.
     */
    public static String endpointFor(String segment) {
        return withWorld(siblingEndpoint(cachedEndpoint, segment));
    }

    /** The food-upload URL, tagged with the world of the character being played. */
    public static String uploadUrl() {
        return withWorld(cachedEndpoint);
    }

    /**
     * The world of the character being played, or null when it isn't known (see
     * {@link WorldTag}). Callers cache per-world data against this.
     */
    public static String worldTag() {
        return WorldTag.current();
    }

    /**
     * Appends the session's world as a query tag, so the server files this upload under the
     * world it was actually observed in rather than whichever one the tenant is configured for.
     *
     * A world already present in the configured endpoint is left alone: it was typed there
     * deliberately, and an explicit override should beat auto-detection - not least so a
     * misdetected world can be corrected without a client change.
     */
    private static String withWorld(String url) {
        if (url == null) {
            return null;
        }
        String world = WorldTag.current();
        if (world == null || hasWorldParam(url)) {
            return url;
        }
        String sep = (url.indexOf('?') < 0) ? "?" : "&";
        return url + sep + "world=" + URLEncoder.encode(world, StandardCharsets.UTF_8);
    }

    /** Whether the URL's query string already carries a world parameter. */
    private static boolean hasWorldParam(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return false;
        }
        for (String param : url.substring(q + 1).split("&")) {
            if (param.equals("world") || param.startsWith("world=")) {
                return true;
            }
        }
        return false;
    }

    public static void checkFood(List<ItemInfo> ii, Resource res, String genus) {
        List<ItemInfo> infoList = new ArrayList<>(ii);
        Defer.later(() -> {
            try {
                String resName = res.name;
                FoodInfo foodInfo = ItemInfo.find(FoodInfo.class, infoList);
                if (foodInfo != null) {
                    QBuff qBuff = ItemInfo.find(QBuff.class, infoList);
                    double quality = qBuff != null ? qBuff.q : QUALITY_SCALE;
                    double multiplier = Math.sqrt(quality / QUALITY_SCALE);
                    double multiplier2 = Math.sqrt(multiplier);

                    ParsedFoodInfo parsedFoodInfo = new ParsedFoodInfo();
                    parsedFoodInfo.resourceName = resName;
                    parsedFoodInfo.genus = genus;
                    parsedFoodInfo.energy = (int) (Math.round(foodInfo.end * PERCENT_SCALE));
                    parsedFoodInfo.hunger = round2Dig(foodInfo.glut * HUNGER_SCALE / multiplier2);

                    for (int i = 0; i < foodInfo.evs.length; i++) {
                        parsedFoodInfo.feps.add(new FoodFEP(foodInfo.evs[i].ev.nm, round2Dig(foodInfo.evs[i].a / multiplier)));
                    }

                    for (ItemInfo info : infoList) {
                        if (info instanceof ItemInfo.AdHoc) {
                            String text = ((ItemInfo.AdHoc) info).str.text;
                            if (text.equals("White-truffled") || text.equals("Black-truffled") || text.equals("Peppered")) {
                                return (null);
                            }
                        }
                        if (info instanceof ItemInfo.Name) {
                            parsedFoodInfo.itemName = ((ItemInfo.Name) info).str.text;
                        }

                        if (info.getClass().getName().contains("Ingredient")) {
                            String name = (String) info.getClass().getField("name").get(info);
                            Double value = (Double) info.getClass().getField("val").get(info);
                            parsedFoodInfo.ingredients.add(new FoodIngredient(name, (int) (value * PERCENT_SCALE)));
                        } else if (info.getClass().getName().contains("Smoke")) {
                            String name = (String) info.getClass().getField("name").get(info);
                            Double value = (Double) info.getClass().getField("val").get(info);
                            parsedFoodInfo.ingredients.add(new FoodIngredient(name, (int) (value * PERCENT_SCALE)));
                        }
                    }
                    checkAndSend(parsedFoodInfo);
                }
            } catch (Exception exception) {
                if (cookbookDebug) {
                    System.out.println("Cannot create food info: " + exception.getMessage());
                }
            }
            return (null);
        });
    }

    private static double round2Dig(double value) {
        return Math.round(value * ROUND_2DP_SCALE) / ROUND_2DP_SCALE;
    }

    private static void checkAndSend(ParsedFoodInfo info) {
        String hash = generateHash(info);
        if (hash == null) return;
        if (cachedItems.containsKey(hash)) {
            return;
        }
        sendQueue.add(new HashedFoodInfo(hash, info));
    }

    public static boolean isValidEndpoint() {
        String raw = cachedEndpoint;
        return raw != null && raw.length() >= MIN_ENDPOINT_LENGTH;
    }

    private static void sendItems() {
        if (sendQueue.isEmpty()) {
            return;
        }

        if (cachedEndpoint == null || !isValidEndpoint()) return;
        final String endpoint = uploadUrl();
        if (endpoint == null) return;
        final java.net.URI apiBase = java.net.URI.create(endpoint.trim());

        List<ParsedFoodInfo> toSend = new ArrayList<>();
        while (!sendQueue.isEmpty()) {
            HashedFoodInfo info = sendQueue.poll();
            if (cachedItems.containsKey(info.hash)) {
                continue;
            }
            cachedItems.put(info.hash, info.foodInfo);
            toSend.add(info.foodInfo);
        }

        if (!toSend.isEmpty()) {
            HttpURLConnection connection = null;
            try {
                String jsonPayload = new JSONArray(toSend.toArray()).toString();

                connection = (HttpURLConnection) apiBase.toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "H&H Client");
                connection.setDoOutput(true);

                String token = cachedToken;
                if (token != null && !token.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                }

                try (OutputStream out = connection.getOutputStream()) {
                    out.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();

                if (code != HTTP_OK) {
                    if (cookbookDebug) {
                        String responseMessage = connection.getResponseMessage();

                        System.out.println("[Cookbook] Failed to send food items");
                        System.out.println("  URL: " + apiBase);
                        System.out.println("  HTTP " + code + " " + responseMessage);
                        System.out.println("  Items: " + toSend.size());
                        System.out.println("  Payload size: " + jsonPayload.length() + " bytes");
                    }
                }
            } catch (Exception ex) {
                if (cookbookDebug) {
                    System.out.println("[Cookbook] Exception while sending " + toSend.size() + " food items to " + apiBase + ": " + ex);
                    ex.printStackTrace(System.out);
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    /**
     * Content hash used to skip re-sending a dish already uploaded this session.
     *
     * The world is part of the key. Without it, inspecting a dish on a character in one world
     * would suppress the upload of the identical dish seen on a character in another - the
     * second world would silently never learn a recipe, and the gap would look like the server
     * dropping data rather than the client never sending it.
     */
    private static String generateHash(ParsedFoodInfo foodInfo) {
        try {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(WorldTag.current()).append(";")
                    .append(foodInfo.itemName).append(";")
                    .append(foodInfo.resourceName).append(";");
            foodInfo.ingredients.forEach(it -> stringBuilder.append(it.name).append(";").append(it.percentage).append(";"));

            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
            return getHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            if (cookbookDebug) {
                System.out.println("Cannot generate food hash");
            }
        }
        return null;
    }

    private static String getHex(byte[] bytes) {
        BigInteger bigInteger = new BigInteger(1, bytes);
        return bigInteger.toString(HEX_RADIX);
    }

    /** Internal holder pairing a content hash with its food payload for deduplication. */
    private static class HashedFoodInfo {
        public String hash;
        public ParsedFoodInfo foodInfo;

        public HashedFoodInfo(String hash, ParsedFoodInfo foodInfo) {
            this.hash = hash;
            this.foodInfo = foodInfo;
        }
    }

    /** Single ingredient entry serialized for cookbook submission (name + percentage scaled 0-100). */
    public static class FoodIngredient {
        public String name;
        public Integer percentage;

        public FoodIngredient(String name, Integer percentage) {
            this.name = name;
            this.percentage = percentage;
        }

        public String getName() {
            return name;
        }

        public Integer getPercentage() {
            return percentage;
        }
    }

    /** Nutrient component (name + value) from food effect description. */
    public static class FoodFEP {
        public String name;
        public Double value;

        public FoodFEP(String name, Double value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public Double getValue() {
            return value;
        }
    }

    /** Holds parsed food information for cookbook submission. Fields are initialized with default values in constructor. */
    public static class ParsedFoodInfo {
        public String itemName;
        public String resourceName;
        public String genus;
        public Integer energy;
        public double hunger;
        public ArrayList<FoodIngredient> ingredients;
        public ArrayList<FoodFEP> feps;

        public ParsedFoodInfo() {
            this.itemName = "";
            this.resourceName = "";
            this.ingredients = new ArrayList<>();
            this.feps = new ArrayList<>();
        }

        public String getItemName() {
            return itemName;
        }

        public String getGenus() {
            return genus;
        }

        public String getResourceName() {
            return resourceName;
        }

        public Integer getEnergy() {
            return energy;
        }

        public double getHunger() {
            return hunger;
        }

        public ArrayList<FoodIngredient> getIngredients() {
            return ingredients;
        }

        public ArrayList<FoodFEP> getFeps() {
            return feps;
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemName, resourceName, ingredients);
        }
    }
}
