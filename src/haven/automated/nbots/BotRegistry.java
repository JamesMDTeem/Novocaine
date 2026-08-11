package haven.automated.nbots;

import haven.Coord;
import haven.GameUI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.Stoppable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every bot on the Nurgling Imports tab, and the one piece of code that opens them.
 *
 * Adding a bot is a line in {@link #DEFS} and an icon. Nothing in GameUI or MenuGrid changes, which
 * is the whole point: those are vendored upstream files this fork is maintained as a patch
 * against, and the previous arrangement grew them by three edits per bot.
 *
 * Open windows live here too rather than in a GameUI field apiece. GameUI keeps one reference to
 * this registry's map, so the per-bot field pairs are gone; the trade is that this class owns a bit
 * of window lifecycle, which is a fair price for the upstream file not knowing bots exist.
 *
 * The stock Bots-tab bots (the legacy windowed bots in haven.automated) were moved onto this
 * registry in 2026-08 so the Bots-tab branch in MenuGrid is one generic toggle, exactly like the
 * Nurgling Imports branch. They keep their own window-position keys so their remembered positions
 * survive the move.
 */
public class BotRegistry {
    private static final List<BotDef> DEFS = new ArrayList<>();

    static {
        DEFS.add(new BotDef("NCellarDiggerBot", "Cellar Digger (crew)",
            "Digs a cellar with several characters at once.",
            NCellarDiggerBot::new));
        DEFS.add(new BotDef("NCleanupBot", "Cleanup (crew)",
            "Clears trees, bushes, boulders, stumps and soil piles, swapping tools by itself.",
            NCleanupBot::new));
        DEFS.add(new BotDef("NWaterScoutBot", "Water Scout (crew)",
            "Follows a coastline or a river bank by boat.",
            NWaterScoutBot::new));
        DEFS.add(new BotDef("OceanScoutBot", "Ocean Scout",
            "Sails the ocean looking for pearls and avoiding danger.",
            "wndc-oceanScoutBotWindow", haven.automated.OceanScoutBot::new));
        DEFS.add(new BotDef("TarKilnEmptierBot", "Tar Kiln Emptier",
            "Empties full tar kilns and drops the coal.",
            "wndc-tarKilnCleanerBotWindow", haven.automated.TarKilnCleanerBot::new));
        DEFS.add(new BotDef("FishingBot", "Fishing",
            "Fishes with a pole and collects the catch.",
            "wndc-fishingBotWindow", haven.automated.FishingBot::new));
        DEFS.add(new BotDef("CleanupBot", "Cleanup",
            "Clears bushes, trees, boulders, stumps and soil piles.",
            "wndc-cleanupBotWindow", haven.automated.CleanupBot::new));
        DEFS.add(new BotDef("GrubGrubBot", "Grub-Grub",
            "Crafts grub-grub from ticks and moves the results to the belt.",
            "wndc-grubGrubBotWindow", haven.automated.GrubGrubBot::new));
        DEFS.add(new BotDef("CellarDiggingBot", "Cellar Digging",
            "Digs a cellar alone: boulders, soil, and the cellar itself.",
            "wndc-cellarDiggingBotWindow", haven.automated.CellarDiggingBot::new));
        DEFS.add(new BotDef("RoastingSpitBot", "Roasting Spit",
            "Roasts and carves food on a spit.",
            "wndc-roastingSpitBotWindow", haven.automated.RoastingSpitBot::new));
    }

    /** Bot id -> its open window, if any. Keyed by id so a bot needs no field anywhere. */
    private final Map<String, Window> open = new LinkedHashMap<>();

    public static List<BotDef> defs() {
        return new ArrayList<>(DEFS);
    }

    public static BotDef def(String id) {
        for (BotDef d : DEFS) {
            if (d.id.equals(id))
                return d;
        }
        return null;
    }

    /**
     * Opens the named bot's window, or closes it if it is already open - the toggle behaviour every
     * menu-grid button in this client has.
     */
    public void toggle(GameUI gui, String id) {
        BotDef def = def(id);
        if (def == null) {
            gui.error("Unknown bot: " + id);
            return;
        }
        Window w = open.get(id);
        if (w != null) {
            close(id);
            return;
        }
        w = def.factory.create(gui);
        Coord centred = new Coord(gui.sz.x / 2 - w.sz.x / 2, gui.sz.y / 2 - w.sz.y / 2 - 200);
        gui.add(w, Utils.getprefc(def.windowKey(), centred));
        open.put(id, w);
        if (w instanceof Runnable) {
            Thread t = new Thread((Runnable) w, id);
            t.start();
        }
    }

    /**
     * Forgets and tears down a bot's window.
     *
     * Called both from {@link #toggle} and from the window itself when the player closes it, so it
     * has to be safe to call twice - which is why it removes from the map before touching the
     * window rather than after.
     */
    public void close(String id) {
        Window w = open.remove(id);
        if (w == null)
            return;
        if (w instanceof Stoppable)
            ((Stoppable) w).stop();
        w.reqdestroy();
    }

    /** Called by a window that is closing itself, so the registry doesn't hold a destroyed widget. */
    public void forget(Widget w) {
        open.values().remove(w);
    }

    public boolean isOpen(String id) {
        return open.containsKey(id);
    }
}
