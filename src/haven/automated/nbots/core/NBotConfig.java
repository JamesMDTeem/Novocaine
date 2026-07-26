package haven.automated.nbots.core;

import haven.Utils;

/**
 * The behaviour toggles every bot shares - how they treat each other, and how much they are
 * allowed to do on their own initiative.
 *
 * Deliberately only global BEHAVIOUR lives here. Two things that used to and no longer do:
 *
 * - The water source. It was a single stored anchor, which worked for one bot needing one kind of
 *   destination and generalises to nothing: food, tools, and somewhere to dump output each want
 *   the same treatment, and four global anchors is not a design. Destinations are now
 *   {@link haven.automated.nbots.world.Places}.
 * - The work radius. It reads as global but is a per-JOB judgement (a cleanup crew clearing one
 *   grove and a digger working one cellar want very different numbers), so it is now a per-bot
 *   {@link BotSettings} entry.
 *
 * Backed by the client's normal preference store, which is per-INSTALL - which is what you want
 * for the multiboxing case these bots are built around: several clients launched from one install
 * agree about all of this, and it is also why the claim registry can simply live in the working
 * directory beside them.
 */
public class NBotConfig {
    public enum Key {
        /**
         * Reserve work slots through WorkClaims so two of our own clients can't pick the same
         * standing position. Costs a couple of small file operations per target. Off makes the
         * bots fall back on watching each other, which still works, just less precisely.
         */
        shareClaims(true),
        /** Walk to a place tagged for water and refill carried containers when they run dry. */
        autoRefillWater(true),
        /**
         * Walk to a place tagged for food and eat when energy runs low, instead of stopping.
         * Separate from the water toggle because eating has consequences drinking doesn't - it
         * consumes food you may be saving, and it moves the character's food meter.
         */
        autoEat(true),
        /**
         * Let a bot swap tools by itself (axe out for a shovel to clear a stump, then back). Off
         * makes a task needing a tool that isn't in hand get skipped with a message.
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

    // These are read inside per-target loops, and Utils.getprefb bottoms out in a synchronized
    // java.util.prefs lookup after building a key string. Since every write goes through set(),
    // the values can simply be cached. AtomicIntegerArray rather than a plain array because the
    // readers are bot threads while the writer is the UI thread.
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
}
