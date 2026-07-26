package haven.automated.nbots;

import haven.Utils;

/**
 * Settings shared by the Nurgling-tab bots, in the same shape as {@link haven.automated.lp.LpConfig}
 * so there is one obvious pattern in this fork rather than two.
 *
 * Backed by the client's normal preference store, which is per-INSTALL. That matters for the
 * multiboxing case these bots are built around: several clients launched from one install share
 * these settings, which is what you want (they should agree about the water source and about
 * whether to coordinate) and is also why the claim registry can simply live in the working
 * directory next to them.
 */
public class NBotConfig {
    public enum Key {
        /**
         * Reserve work slots through {@link WorkClaims} so two of our own clients can't pick the
         * same standing position. Costs a couple of small file operations per target. Off makes
         * the bots fall back on watching each other, which still works, just less precisely.
         */
        shareClaims(true),
        /**
         * Walk back to a known water source and refill carried containers when they run dry,
         * instead of stopping. See {@link WaterService}.
         */
        autoRefillWater(true),
        /**
         * Let a bot swap tools by itself (axe out for a shovel to clear a stump, then back). Off
         * makes a task needing a tool that isn't in hand get skipped with a message, which is the
         * behaviour the LP bot already has.
         */
        autoEquipTools(true),
        /** Route around water rather than swimming through it while a bot is running. */
        avoidWater(true),
        /** Treat other characters as small obstacles so bots don't pile onto the same spot. */
        avoidOthers(true);

        public final boolean def;

        Key(boolean def) {
            this.def = def;
        }
    }

    // Same caching argument as LpConfig: these are read inside per-target loops, and Utils.getprefb
    // bottoms out in a synchronized java.util.prefs lookup after building a key string.
    private static final int UNKNOWN = 0, NO = 1, YES = 2;
    private static final java.util.concurrent.atomic.AtomicIntegerArray cache =
        new java.util.concurrent.atomic.AtomicIntegerArray(Key.values().length);

    public static boolean on(Key key) {
        int i = key.ordinal();
        int v = cache.get(i);
        if (v == UNKNOWN) {
            v = Utils.getprefb("nbot_" + key.name(), key.def) ? YES : NO;
            cache.set(i, v);
        }
        return v == YES;
    }

    public static void set(Key key, boolean value) {
        Utils.setprefb("nbot_" + key.name(), value);
        cache.set(key.ordinal(), value ? YES : NO);
    }

    /**
     * The remembered water source, as a segment anchor string. Stored per install rather than per
     * character on purpose: a crew of alts working one site all refill at the same barrel, and
     * making each of them learn it separately is just friction.
     */
    private static final String WATER_PREF = "nbotWaterSource";

    public static WorldAnchor waterSource() {
        return WorldAnchor.parse(Utils.getpref(WATER_PREF, ""));
    }

    public static void waterSource(WorldAnchor anchor) {
        Utils.setpref(WATER_PREF, (anchor == null) ? "" : anchor.store());
    }

    /**
     * Name of a custom map marker to use as the water source instead of the remembered one. Empty
     * means "use whatever was remembered". Lets a player redirect the bots with the map UI they
     * already have rather than a bot-specific coordinate picker.
     */
    private static final String WATER_MARKER_PREF = "nbotWaterMarker";

    public static String waterMarker() {
        return Utils.getpref(WATER_MARKER_PREF, "");
    }

    public static void waterMarker(String name) {
        Utils.setpref(WATER_MARKER_PREF, (name == null) ? "" : name.trim());
    }

    /**
     * How far a bot may wander from where it started looking for work, in map units. Bounds the
     * damage a mis-identified target can do (a cleanup bot that keeps finding "one more tree" all
     * the way across the map), and keeps several bots working a shared site from drifting apart.
     */
    private static final String RADIUS_PREF = "nbotWorkRadius";
    public static final int RADIUS_DEFAULT = 300;

    public static int radius() {
        return Utils.getprefi(RADIUS_PREF, RADIUS_DEFAULT);
    }

    public static void radius(int units) {
        Utils.setprefi(RADIUS_PREF, units);
    }
}
