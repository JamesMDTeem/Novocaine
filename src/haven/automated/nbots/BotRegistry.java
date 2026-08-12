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
        DEFS.add(BotDef.window("NCellarDiggerBot", "Cellar Digger (crew)",
            "Digs a cellar with several characters at once.",
            NCellarDiggerBot::new));
        DEFS.add(BotDef.window("NCleanupBot", "Cleanup (crew)",
            "Clears trees, bushes, boulders, stumps and soil piles, swapping tools by itself.",
            NCleanupBot::new));
        DEFS.add(BotDef.window("NWaterScoutBot", "Water Scout (crew)",
            "Follows a coastline or a river bank by boat.",
            NWaterScoutBot::new));
        DEFS.add(BotDef.window("OceanScoutBot", "Ocean Scout",
            "Sails the ocean looking for pearls and avoiding danger.",
            "wndc-oceanScoutBotWindow", haven.automated.OceanScoutBot::new));
        DEFS.add(BotDef.window("TarKilnEmptierBot", "Tar Kiln Emptier",
            "Empties full tar kilns and drops the coal.",
            "wndc-tarKilnCleanerBotWindow", haven.automated.TarKilnCleanerBot::new));
        DEFS.add(BotDef.window("FishingBot", "Fishing",
            "Fishes with a pole and collects the catch.",
            "wndc-fishingBotWindow", haven.automated.FishingBot::new));
        DEFS.add(BotDef.window("CleanupBot", "Cleanup",
            "Clears bushes, trees, boulders, stumps and soil piles.",
            "wndc-cleanupBotWindow", haven.automated.CleanupBot::new));
        DEFS.add(BotDef.window("GrubGrubBot", "Grub-Grub",
            "Crafts grub-grub from ticks and moves the results to the belt.",
            "wndc-grubGrubBotWindow", haven.automated.GrubGrubBot::new));
        DEFS.add(BotDef.window("CellarDiggingBot", "Cellar Digging",
            "Digs a cellar alone: boulders, soil, and the cellar itself.",
            "wndc-cellarDiggingBotWindow", haven.automated.CellarDiggingBot::new));
        DEFS.add(BotDef.window("RoastingSpitBot", "Roasting Spit",
            "Roasts and carves food on a spit.",
            "wndc-roastingSpitBotWindow", haven.automated.RoastingSpitBot::new));
        /* The Other Scripts And Tools category: one-shots and window tools that used to be
         * hand-wired branches in MenuGrid.useCustom, migrated here so that branch is generic. */
        DEFS.add(BotDef.script("Add9CoalScript", "Add 9 Coal to Smelter",
            "Adds 9 coal to a smelter.",
            (gui) -> new haven.automated.AddCoalToSmelter(gui, 9)));
        DEFS.add(BotDef.script("Add12CoalScript", "Add 12 Coal to Smelter",
            "Adds 12 coal to a smelter.",
            (gui) -> new haven.automated.AddCoalToSmelter(gui, 12)));
        DEFS.add(BotDef.script("Add4BranchesScript", "Add 4 Branches to Furnace",
            "Adds 4 branches to a furnace.",
            (gui) -> new haven.automated.AddBranchesToFurnace(gui, 4)));
        DEFS.add(BotDef.script("Add5WoodBlocksScript", "Add 5 Wood Blocks to Smoke Shed",
            "Adds 5 wood blocks to a smoke shed.",
            (gui) -> new haven.automated.AddWoodBlocksToSmokeShed(gui, 5)));
        DEFS.add(BotDef.script("RefillCheeseTrays", "Fill Cheese Trays",
            "Fills cheese trays.",
            (gui) -> new haven.automated.FillCheeseTray(gui)));
        DEFS.add(BotDef.script("GridHeightCalculator", "Grid Height Calculator",
            "Shows the average grid height under the camera.",
            (gui) -> () -> haven.automated.AUtils.getGridHeightAvg(gui)));
        DEFS.add(BotDef.script("CloverScript", "Clover",
            "Feeds a clover to a nearby animal.",
            (gui) -> new haven.automated.CloverScript(gui)));
        DEFS.add(BotDef.script("CoracleScript", "Coracle",
            "Crafts a coracle.",
            (gui) -> new haven.automated.CoracleScript(gui)));
        DEFS.add(BotDef.script("SkisScript", "Skis",
            "Crafts skis.",
            (gui) -> new haven.automated.SkisScript(gui)));
        DEFS.add(BotDef.script("RefillWaterContainers", "Refill Water Containers",
            "Refills water containers.",
            (gui) -> new haven.automated.RefillWaterContainers(gui)));
        DEFS.add(BotDef.script("HarvestNearestDreamcatcher", "Harvest Nearest Dreamcatcher",
            "Harvests the nearest dreamcatcher.",
            (gui) -> new haven.automated.HarvestNearestDreamcatcher(gui)));
        DEFS.add(BotDef.script("DestroyNearestTrellisPlantScript", "Destroy Nearest Trellis Plant",
            "Destroys the nearest trellis plant.",
            (gui) -> new haven.automated.DestroyNearestTrellisPlantScript(gui)));
        DEFS.add(BotDef.window("CombatDistanceTool", "Combat Distance Tool",
            "Shows the distance to your current target.",
            "wndc-combatDistanceToolWindow", haven.automated.CombatDistanceTool::new));
        DEFS.add(BotDef.window("MiningSafetyAssistant", "Mining Safety Assistant",
            "Watches supports and loose rock while mining.",
            "wndc-miningSafetyAssistantWindow", haven.automated.MiningSafetyAssistant::new));
        DEFS.add(BotDef.window("QuestgiverTriangulation", "Questgiver Triangulation",
            "Draws lines to triangulate a questgiver's location.",
            "wndc-pointerTriangulationWindow", haven.automated.PointerTriangulation::new));
        DEFS.add(BotDef.window("OreAndStoneCounter", "Ore & Stone Counter",
            "Counts ore and stone tiles around you.",
            "wndc-oreAndStoneCounterWindow", haven.automated.OreAndStoneCounter::new));
    }

    /** Bot id -> its open window, if any. Keyed by id so a bot needs no field anywhere. */
    private final Map<String, Window> open = new LinkedHashMap<>();

    /** Bot id -> its running script thread, if any. Scripts are toggled on and off, no window. */
    private final Map<String, Thread> scripts = new LinkedHashMap<>();

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
     * Toggles the named entry on or off: opens a bot's window (or closes it if it is already open)
     * for windowed bots, and starts (or stops) the thread for script entries - the toggle
     * behaviour every menu-grid button in this client has.
     */
    public void toggle(GameUI gui, String id) {
        BotDef def = def(id);
        if (def == null) {
            gui.error("Unknown bot: " + id);
            return;
        }
        if (def.isScript()) {
            toggleScript(gui, def);
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

    /** Starts a script thread, or stops it if one is still running - the toggle every menu-grid button has. */
    private void toggleScript(GameUI gui, BotDef def) {
        Thread t = scripts.get(def.id);
        if (t != null && t.isAlive()) {
            t.interrupt();
            scripts.remove(def.id);
            return;
        }
        scripts.remove(def.id);
        t = new Thread(def.scriptFactory.create(gui), def.id);
        scripts.put(def.id, t);
        t.start();
    }

    /**
     * Forgets and tears down an entry.
     *
     * Called both from {@link #toggle} and from the window itself when the player closes it, so it
     * has to be safe to call twice - which is why it removes from the map before touching the
     * window rather than after. Script entries are stopped (interrupted) the same way.
     */
    public void close(String id) {
        Thread st = scripts.remove(id);
        if (st != null) {
            st.interrupt();
            return;
        }
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
        return open.containsKey(id) || scripts.containsKey(id);
    }

    /** Whether a script entry's thread is actually still running. */
    public boolean isRunning(String id) {
        Thread t = scripts.get(id);
        return t != null && t.isAlive();
    }

    /** The open window for a windowed entry, or null if it is closed. */
    public Window open(String id) {
        return open.get(id);
    }
}
