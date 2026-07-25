package haven.automated.lp;

import haven.Utils;

/**
 * The LP-assistant's settings, stood in for nurgling2's NConfig keys. Backed by the client's
 * normal preference store (Utils.getpref/setpref, so they persist per install the same way every
 * other Hurricane option does), with the defaults the nurgling2 feature shipped with - notably
 * felling and eat-for-seed are OFF: felling is destructive and self-perpetuating, and eating is
 * only wanted for the few fruits whose LP lives in a seed you can't get any other way.
 */
public class LpConfig {
    public enum Key {
        /** Master switch for LP-discovery tracking, markers and the bot. */
        lpassistent(true),
        /** Always-on harvest overlay per gob type. */
        treeHarvestOverlay(false),
        bushHarvestOverlay(false),
        logHarvestOverlay(false),
        stoneHarvestOverlay(false),
        oldtrunkHarvestOverlay(false),
        /** Per-category sub-toggles of the tree overlay. */
        treeHarvestSeeds(true),
        treeHarvestLeaves(true),
        treeHarvestBoughs(true),
        treeHarvestBark(true),
        /** Show the chop hint (axe + Board/Block) on standing trees with undiscovered wood. */
        treeHarvestWood(true),
        /** Auto-LP bot may fell trees (gated on ownProductsAllDiscovered even when on). */
        autolpCutTrees(false),
        /** Auto-LP bot may eat a collected consumable to register its seed's discovery. */
        autolpEatConsumables(false);

        public final boolean def;

        Key(boolean def) {
            this.def = def;
        }
    }

    /** Auto-LP bot search radius in map units (nurgling round-6 default: 400). */
    public static final String RADIUS_PREF = "lpAutolpRadius";
    public static final int RADIUS_DEFAULT = 400;

    // These are read on genuinely hot paths - the world overlay asks lpassistent for every
    // LP-capable gob on every tick, and the marker/overlay resolvers ask several more per gob -
    // while Utils.getprefb builds a "lp_"+name string for each call and bottoms out in a
    // synchronized java.util.prefs lookup. Since every write goes through set() below, the values
    // can simply be cached. AtomicIntegerArray rather than a plain array because the readers are
    // the render and bot threads while the writer is the UI thread.
    private static final int UNKNOWN = 0, NO = 1, YES = 2;
    private static final java.util.concurrent.atomic.AtomicIntegerArray cache =
        new java.util.concurrent.atomic.AtomicIntegerArray(Key.values().length);

    public static boolean on(Key key) {
        int i = key.ordinal();
        int v = cache.get(i);
        if (v == UNKNOWN) {
            v = Utils.getprefb("lp_" + key.name(), key.def) ? YES : NO;
            cache.set(i, v);
        }
        return v == YES;
    }

    public static void set(Key key, boolean value) {
        Utils.setprefb("lp_" + key.name(), value);
        cache.set(key.ordinal(), value ? YES : NO);
    }

    public static int radius() {
        return Utils.getprefi(RADIUS_PREF, RADIUS_DEFAULT);
    }

    public static void radius(int units) {
        Utils.setprefi(RADIUS_PREF, units);
    }
}
