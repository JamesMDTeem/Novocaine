package haven.automated.cookbook;

import haven.BAttrWnd;
import haven.Defer;
import haven.GItem;
import haven.ItemInfo;
import haven.OptWnd;
import haven.Resource;
import haven.UI;
import haven.automated.eat.EatObserver;
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
        // Taken here, not inside the Defer below: this method runs on the UI thread (GItem.info()),
        // and BAttrWnd.Constipations.els is a plain ArrayList the UI thread appends to and removes
        // from. Reading it off a worker would be a straight data race, and the index FoodInfo.types
        // carries is only meaningful against the list as it stands right now anyway.
        List<String> satiationKeys = snapshotSatiationKeys(infoList);
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
                    // The observed quality, sent alongside the q10-normalized numbers rather than
                    // instead of them. The server keeps a running max per variant and the Eating
                    // Helper's "% of highest quality seen" mode is built entirely on it; without
                    // this field that mode has never had a single value to work with. Null when
                    // the item carries no QBuff, because the 10.0 default above is a normalization
                    // fallback, not an observation, and uploading it would poison the max.
                    parsedFoodInfo.quality = (qBuff != null) ? round2Dig(quality) : null;
                    readSatiationKeys(foodInfo, satiationKeys, parsedFoodInfo);
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
                        } else if (info.getClass().getName().contains("FoodTypes")) {
                            readFoodTypes(info, parsedFoodInfo);
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

    /**
     * The character's live satiation entries, in list order, keyed exactly as
     * {@code EatObserver.resolveSatiationKey} keys them - so entry {@code i} here is what
     * {@code FoodInfo.types[i']} means when it holds the value {@code i}.
     *
     * <h2>Why this exists at all</h2>
     *
     * The tooltip's "Food types:" line and the character's satiation list are two different
     * things, which is not obvious and cost a wrong turn to find out. The type resources are
     * thirteen stable categories ({@code gfx/invobjs/food/veg}, {@code .../food/shrooms}); the
     * satiation entries are keyed by a representative dish icon instead
     * ({@code gfx/invobjs/steaktuber}, {@code .../applepie}), and the two namespaces do not
     * overlap at all. Joining a catalog dish to a live satiation penalty by type resource
     * therefore never matches anything, and the planner silently prices every dish as unsatiated.
     *
     * {@code FoodInfo.types} is the game's own pointer from a food to the satiation entries it
     * drains, and it is what {@code FoodInfo.tipimg} uses for the Food Efficiency percentage the
     * player already reads. The index is safe to trust despite the list being mutable: the server
     * computes it against its own view, and {@code Constipations.update} mirrors that view's
     * semantics exactly - append on first sight, remove at full decay - so both sides stay in the
     * same order.
     *
     * @return keys by index, or an empty list when the character sheet isn't available yet.
     */
    private static List<String> snapshotSatiationKeys(List<ItemInfo> infoList) {
        try {
            UI ui = null;
            for (ItemInfo info : infoList) {
                if (info.owner instanceof GItem) {
                    ui = ((GItem) info.owner).ui;
                    break;
                }
            }
            if (ui == null || ui.gui == null || ui.gui.chrwdg == null
                    || ui.gui.chrwdg.battr == null || ui.gui.chrwdg.battr.cons == null) {
                return java.util.Collections.emptyList();
            }
            List<BAttrWnd.Constipations.El> els = ui.gui.chrwdg.battr.cons.els;
            List<String> keys = new ArrayList<>(els.size());
            for (BAttrWnd.Constipations.El el : els) {
                keys.add(EatObserver.resolveSatiationKey(el.t));
            }
            return keys;
        } catch (Exception e) {
            // A food is still worth uploading without its satiation keys; the next hover, once the
            // sheet is up, fills them in (see improvesOnCached).
            return java.util.Collections.emptyList();
        }
    }

    /**
     * The satiation entries this food drains, resolved through {@code FoodInfo.types} against the
     * snapshot taken on the UI thread. Out-of-range indices are dropped rather than guessed at -
     * that only happens if the list moved between the snapshot and here, in which case the index
     * no longer means anything.
     */
    private static void readSatiationKeys(FoodInfo foodInfo, List<String> snapshot,
                                           ParsedFoodInfo out) {
        if (foodInfo.types == null || snapshot.isEmpty()) {
            return;
        }
        for (int type : foodInfo.types) {
            if (type < 0 || type >= snapshot.size()) {
                continue;
            }
            String key = snapshot.get(type);
            if (key == null || key.startsWith("?") || out.satiationKeys.contains(key)) {
                continue;
            }
            out.satiationKeys.add(key);
        }
    }

    /**
     * The food's satiation types - the "Food types:" line on the hover tooltip - pulled off the
     * resource-side {@code FoodTypes} item info.
     *
     * This is the exact join the Eating Helper needs and could not previously get. The character's
     * live satiation list ({@code BAttrWnd.Constipations}) is keyed by these same resources, so a
     * food's own types say precisely which entries eating it drains. The server was instead trying
     * to <i>infer</i> that join from eat history, voting a satiation resource onto a wiki category;
     * that cannot work, because the single most common resource - {@code gfx/invobjs/meat}, 524 of
     * 1042 readings in the local logs - is shared by dishes the wiki files under Fish, Game and
     * Meat separately. Reading the answer off the item beats inferring it from behaviour.
     *
     * Reflective because {@code FoodTypes} lives in the game's resource tree, not in this source
     * tree, exactly like the {@code Ingredient} and {@code Smoke} infos handled beside it. Its
     * {@code types} field is a {@code Resource[]} - not the {@code int[]} on {@link FoodInfo},
     * which is a positional index into a client-side list that reorders and drops entries as
     * satiation decays, and so is not a stable identity for anything.
     *
     * Any failure here is swallowed: a missing class, a renamed field or a resource still loading
     * costs this one food its types, and must not cost the upload its FEP and hunger numbers.
     */
    private static void readFoodTypes(ItemInfo info, ParsedFoodInfo out) {
        try {
            java.lang.reflect.Field typesField = info.getClass().getDeclaredField("types");
            typesField.setAccessible(true);
            Object raw = typesField.get(info);
            if (!(raw instanceof Object[])) {
                return;
            }
            for (Object entry : (Object[]) raw) {
                if (!(entry instanceof Resource)) {
                    continue;
                }
                Resource res = (Resource) entry;
                String display = null;
                try {
                    Resource.Tooltip tt = res.layer(Resource.tooltip);
                    if (tt != null) {
                        display = tt.t;
                    }
                } catch (Exception e) {
                    // A type with no readable tooltip is still worth recording by resource -
                    // the resource is the identity, the name is only for display.
                }
                out.foodTypes.add(new FoodType(res.name, display));
            }
        } catch (Exception e) {
            if (cookbookDebug) {
                System.out.println("Cannot read food types: " + e);
            }
        }
    }

    private static void checkAndSend(ParsedFoodInfo info) {
        String hash = generateHash(info);
        if (hash == null) return;
        if (!improvesOnCached(hash, info)) {
            return;
        }
        sendQueue.add(new HashedFoodInfo(hash, info));
    }

    /**
     * Whether this sighting is worth uploading: the dish is new this session, or it has been seen
     * at a strictly higher quality than the copy already sent.
     *
     * The quality clause is what makes the server's running max mean anything. The hash covers
     * name, resource and ingredients but deliberately not quality - putting quality in the key
     * would upload a fresh row for every quality point of every dish - so a plain
     * already-seen check silently threw away every sighting after the first. Inspect a q10 pie in
     * the morning and a q80 one in the afternoon and the server would only ever hear about the
     * q10, leaving "% of highest quality seen" planning against a number that never grows.
     */
    private static boolean improvesOnCached(String hash, ParsedFoodInfo info) {
        ParsedFoodInfo seen = cachedItems.get(hash);
        if (seen == null) {
            return true;
        }
        // Satiation types can arrive late: the first sighting of a dish may land while its
        // FoodTypes resource is still loading, and without this the session cache would pin that
        // typeless copy in place for as long as the client runs.
        if (!info.foodTypes.isEmpty() && seen.foodTypes.isEmpty()) {
            return true;
        }
        // Same for the satiation keys, which additionally need the character sheet to be up - the
        // first hover of a session routinely lands before it is.
        if (!info.satiationKeys.isEmpty() && seen.satiationKeys.isEmpty()) {
            return true;
        }
        if (info.quality == null) {
            return false;
        }
        return seen.quality == null || info.quality > seen.quality;
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

        List<HashedFoodInfo> batch = new ArrayList<>();
        List<ParsedFoodInfo> toSend = new ArrayList<>();
        while (!sendQueue.isEmpty()) {
            HashedFoodInfo info = sendQueue.poll();
            if (!improvesOnCached(info.hash, info.foodInfo)) {
                continue;
            }
            batch.add(info);
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

                // Only remember a dish once the server has actually acknowledged it. Caching on
                // the way *out* meant a batch the server rejected - or, worse, one it accepted
                // while silently ignoring a field it was too old to understand - was recorded as
                // delivered, and that dish could then never be re-sent for the rest of the
                // session. Restarting the client was the only way to retry, which is a confusing
                // thing to have to discover. On a non-200 nothing is cached, so the next sighting
                // of the same dish queues again.
                if (code == HTTP_OK) {
                    for (HashedFoodInfo sent : batch) {
                        cachedItems.put(sent.hash, sent.foodInfo);
                    }
                }

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

    /**
     * One satiation type a food drains, as the tooltip's "Food types:" line lists it. The resource
     * is the identity - it is what {@code BAttrWnd.Constipations} keys its live entries by - and
     * the name is for display only, so a type whose tooltip has not loaded still joins correctly.
     */
    public static class FoodType {
        public String resource;
        public String name;

        public FoodType(String resource, String name) {
            this.resource = resource;
            this.name = name;
        }

        public String getResource() {
            return resource;
        }

        public String getName() {
            return name;
        }
    }

    /** Holds parsed food information for cookbook submission. Fields are initialized with default values in constructor. */
    public static class ParsedFoodInfo {
        public String itemName;
        public String resourceName;
        public String genus;
        public Integer energy;
        public double hunger;
        /** Observed item quality, or null when the item had no quality buff to read. */
        public Double quality;
        public ArrayList<FoodIngredient> ingredients;
        public ArrayList<FoodFEP> feps;
        /** Satiation types read off the tooltip - see {@link FoodService#readFoodTypes}. */
        public ArrayList<FoodType> foodTypes;
        /** Live satiation entry keys this food drains - see {@link FoodService#snapshotSatiationKeys}. */
        public ArrayList<String> satiationKeys;

        public ParsedFoodInfo() {
            this.itemName = "";
            this.resourceName = "";
            this.ingredients = new ArrayList<>();
            this.feps = new ArrayList<>();
            this.foodTypes = new ArrayList<>();
            this.satiationKeys = new ArrayList<>();
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

        public Double getQuality() {
            return quality;
        }

        public ArrayList<FoodType> getFoodTypes() {
            return foodTypes;
        }

        public ArrayList<String> getSatiationKeys() {
            return satiationKeys;
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
