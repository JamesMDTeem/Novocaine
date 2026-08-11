package haven.automated;

import haven.Composited;
import haven.Coord;
import haven.Coord2d;
import haven.Drawable;
import haven.GAttrib;
import haven.GameUI;
import haven.Gob;
import haven.IMeter;
import haven.Inventory;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.WItem;
import haven.Widget;
import haven.Composite;
import haven.Window;
import haven.automated.nbots.core.Carried;

import java.awt.*;
import java.util.*;
import java.util.List;

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;
import static haven.OCache.posres;

public class AUtils {

    public final static HashSet<String> potentialAggroTargets = new HashSet<String>() {{ // ND: Probably still missing dungeon ants, dungeon bees, dungeon beavers, dungeon bats?
        add("gfx/borka/body");
        add("gfx/kritter/adder/adder");
        add("gfx/kritter/ants/ants");
//        add("gfx/kritter/cattle/cattle"); // ND: Aurochs are handled differently in the method below!
        add("gfx/kritter/badger/badger");
        add("gfx/kritter/bat/bat");
        add("gfx/kritter/bear/bear");
        add("gfx/kritter/bear/polarbear");
        add("gfx/kritter/beaver/beaver");
        add("gfx/kritter/boar/boar");
        add("gfx/kritter/boreworm/boreworm");
        add("gfx/kritter/caveangler/caveangler");
        add("gfx/kritter/cavelouse/cavelouse");
        add("gfx/kritter/chasmconch/chasmconch"); // ND: I even added this one
        add("gfx/kritter/eagleowl/eagleowl");
        add("gfx/kritter/fox/fox");
        add("gfx/kritter/goat/wildgoat");
        add("gfx/kritter/goldeneagle/goldeneagle");
        add("gfx/kritter/greyseal/greyseal");
        add("gfx/kritter/horse/horse");
        add("gfx/kritter/lynx/lynx");
        add("gfx/kritter/mammoth/mammoth");
        add("gfx/kritter/moose/moose");
//        add("gfx/kritter/sheep/sheep"); // ND: Mouflons are handled differently in the method below!
        add("gfx/kritter/nidbane/nidbane");
        add("gfx/kritter/ooze/greenooze");
        add("gfx/kritter/orca/orca");
        add("gfx/kritter/otter/otter");
        add("gfx/kritter/pelican/pelican");
        add("gfx/kritter/rat/caverat");
        add("gfx/kritter/reddeer/reddeer");
        add("gfx/kritter/reindeer/reindeer");
        add("gfx/kritter/roedeer/roedeer");
        add("gfx/kritter/spermwhale/spermwhale");
        add("gfx/kritter/stoat/stoat");
        add("gfx/kritter/swan/swan");
        add("gfx/kritter/troll/troll");
        add("gfx/kritter/walrus/walrus");
        add("gfx/kritter/wolf/wolf");
        add("gfx/kritter/wolverine/wolverine");
        add("gfx/kritter/woodgrouse/woodgrouse-m");
        add("gfx/kritter/garefowl/garefowl");
        add("gfx/kritter/goshawk/goshawk");
        add("gfx/kritter/narwhal/narwhal");
        add("gfx/kritter/crane/crane");
        add("gfx/kritter/woodscorpion/woodscorpion");

        add("gfx/kritter/ants/queenant");
        add("gfx/kritter/ants/royalguardant");
        add("gfx/kritter/ants/warriorant");
        add("gfx/kritter/ants/redants");

        add("gfx/kritter/beaver/beaverking");
        add("gfx/kritter/beaver/oldbeaver");
        add("gfx/kritter/beaver/grizzlybeaver");

        add("gfx/kritter/bees/warriordrone");
        add("gfx/kritter/bees/queenbee");
        add("gfx/kritter/bees/sentinelbee");
        add("gfx/kritter/bees/vulturebee");
        add("gfx/kritter/bees/honeybee");
        add("gfx/kritter/bees/beelarva");
        add("gfx/kritter/wildbees/beeswarm");

        add("gfx/kritter/bat/nightqueen");
        add("gfx/kritter/bat/vampire");
        add("gfx/kritter/bat/bloodstalker");
        add("gfx/kritter/bat/denmother");
        add("gfx/kritter/bat/fatbat");

        add("gfx/kritter/rat/ratking");
        add("gfx/kritter/rat/fatrat");
        add("gfx/kritter/rat/blackrat");

    }
    };

    public static HashMap<Long, Gob> getAllAttackableMap(GameUI gui) {
        HashMap<Long, Gob> gobs = new HashMap<>();
        if (gui.map.plgob == -1) {
            return gobs;
        }
        synchronized (gui.map.glob.oc) {
            for (Gob gob : gui.map.glob.oc) {
                if (gob.getres() != null && gob.getres().name != null){
                    if (gob.id != gui.map.plgob) {
                        if (potentialAggroTargets.contains(gob.getres().name)){
                            gobs.put(gob.id, gob);
                        } else if (gob.getres().name.equals("gfx/kritter/cattle/cattle")) { // ND: Special case for Aurochs
                            for (GAttrib g : gob.attr.values()) {
                                if (g instanceof Drawable) {
                                    if (g instanceof Composite) {
                                        Composite c = (Composite) g;
                                        if (c.comp.cmod.size() > 0) {
                                            for (Composited.MD item : c.comp.cmod) {
                                                if (item.mod.get().basename().equals("aurochs")){
                                                    gobs.put(gob.id, gob);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (gob.getres().name.equals("gfx/kritter/sheep/sheep")) { // ND: Special case for Mouflon
                            for (GAttrib g : gob.attr.values()) {
                                if (g instanceof Drawable) {
                                    if (g instanceof Composite) {
                                        Composite c = (Composite) g;
                                        if (c.comp.cmod.size() > 0) {
                                            for (Composited.MD item : c.comp.cmod) {
                                                if (item.mod.get().basename().equals("mouflon")){
                                                    gobs.put(gob.id, gob);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }
        return gobs;
    }

    public static void attackGob(GameUI gui, Gob gob) {
        if (gob != null && gui != null && gui.map != null) {
            gui.act("aggro");
            gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 1, 0, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
            rightClick(gui);
        }
    }

    public static void rightClick(GameUI gui) {
        gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.floor(posres), 3, 0);
    }

    public static HashMap<Long, Gob> getAllAttackablePlayersMap(GameUI gui) {
        HashMap<Long, Gob> gobs = new HashMap<>();
        if (gui.map.plgob == -1) {
            return gobs;
        }
        synchronized (gui.map.glob.oc) {
            for (Gob gob : gui.map.glob.oc) {
                if (gob.getres() != null && gob.getres().name != null){
                    if (gob.id != gui.map.plgob) {
                        if (gob.getres().name.equals("gfx/borka/body")){
                            gobs.put(gob.id, gob);
                        }
                    }
                }
            }
        }
        return gobs;
    }

    public static WItem findItemByPrefixInAllInventories(GameUI gui, final String resNamePrefix) {
        for(Inventory inventory : gui.getAllInventories()){
            for (Widget wdg = inventory.child; wdg != null; wdg = wdg.next) {
                if (wdg instanceof WItem) {
                    final WItem witm = (WItem)wdg;
                    try {
                        if (witm.item.getres().name.startsWith(resNamePrefix)) {
                            return witm;
                        }
                    }
                    catch (Loading ignored) {}
                }
            }
        }
        return null;
    }

    public static WItem findItemInInv(final Inventory inv, final String resName) {
        for (Widget wdg = inv.child; wdg != null; wdg = wdg.next) {
            if (wdg instanceof WItem) {
                final WItem witm = (WItem)wdg;
                try {
                    if (witm.item.getres().name.equals(resName)) {
                        return witm;
                    }
                }
                catch (Loading ignored) {}
            }
        }
        return null;
    }

    /** Interval in milliseconds for checking hand state during waitForEmptyHand. */
    private static final long HAND_CHECK_INTERVAL_MS = 5L;

    /** Timeout in milliseconds at which waitForEmptyHand fails and reports an error. */
    private static final long HAND_WAIT_TIMEOUT_MS = 2000L;

    /** Sleep interval in milliseconds used to avoid busy waiting in waitForEmptyHand and waitForOccupiedHand. */
    private static final long HAND_SLEEP_INTERVAL_MS = 5L;

    public static boolean waitForEmptyHand(final GameUI gui, final int timeout, final String error) throws InterruptedException {
        int t = 0;
        while (gui.vhand != null) {
            t += HAND_CHECK_INTERVAL_MS;
            if (t >= timeout) {
                gui.error(error);
                return false;
            }
            try {
                Thread.sleep(HAND_SLEEP_INTERVAL_MS);
            }
            catch (InterruptedException ie) {
                throw ie;
            }
        }
        return true;
    }

    public static boolean waitForOccupiedHand(final GameUI gui, final int timeout, final String error) throws InterruptedException {
        int t = 0;
        while (gui.vhand == null) {
            t += HAND_CHECK_INTERVAL_MS;
            if (t >= timeout) {
                gui.error(error);
                return false;
            }
            try {
                Thread.sleep(HAND_SLEEP_INTERVAL_MS);
            }
            catch (InterruptedException ie) {
                throw ie;
            }
        }
        return true;
    }

    /** Initial sleep in milliseconds before polling pathfinder thread. */
    private static final long PATHFINDER_INITIAL_SLEEP_MS = 300L;

    /** Poll interval in milliseconds for pathfinder thread and player velocity. */
    private static final long PATHFINDER_POLL_INTERVAL_MS = 70L;

    /** Time in milliseconds with no progress before attempting unstuck. */
    private static final long PATHFINDER_STUCK_THRESHOLD_MS = 2000L;

    /** Maximum time in milliseconds to wait for pathfinder before giving up. */
    private static final long PATHFINDER_MAX_WAIT_MS = 20000L;

    public static boolean waitPf(GameUI gui) throws InterruptedException {
        if(gui.map.pfthread == null){
            return false;
        }
        int time = 0;
        boolean moved = false;
        Thread.sleep(PATHFINDER_INITIAL_SLEEP_MS);
        while (gui.map.pfthread.isAlive() || gui.map.player().getv() > 0) {
            time += PATHFINDER_POLL_INTERVAL_MS;
            Thread.sleep(PATHFINDER_POLL_INTERVAL_MS);
            if (gui.map.player().getv() > 0) {
                time = 0;
                moved = true;
            }
            if (time > PATHFINDER_STUCK_THRESHOLD_MS && moved == false) {
                System.out.println("TRYING UNSTUCK");
                return false;
            } else if (time > PATHFINDER_MAX_WAIT_MS) {
                return false;
            }
        }
        return true;
    }

    /** Sleep interval in milliseconds while waiting for progress bar to complete. */
    private static final long PROGRESS_BAR_SLEEP_MS = 40L;

    public static void waitProgBar(GameUI gui) throws InterruptedException {
        while (gui.prog != null && gui.prog.prog >= 0) {
            Thread.sleep(PROGRESS_BAR_SLEEP_MS);
        }
    }

    public static void rightClickShiftCtrl(GameUI gui, Gob gob) {
        gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 3, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
    }

    public static ArrayList<Gob> getGobs(String name, GameUI gui) {
        ArrayList<Gob> gobs = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob gob : gui.map.glob.oc) {
                try {
                    Resource res = gob.getres();
                    if (res != null && res.name.equals(name)) {
                        gobs.add(gob);
                    }
                } catch (Loading l) {
                }
            }
        }
        return gobs;
    }

    /** Maximum sips before a drink-to-full gives up. Drinking is a timed action and each sip is a
     * fresh iact on a vessel; bounded because the alternative is a bot standing drinking forever
     * over a vessel that will not deliver. */
    private static final int DRINK_SIPS_MAX = 30;

    /** Polls (of 25ms) to see a sip register on the stamina meter before calling it a miss. */
    private static final int DRINK_SIP_WAIT_TICKS = 40;

    /** Poll interval between sip-effect checks. */
    private static final long DRINK_SIP_WAIT_MS = 25L;

    /**
     * Drinks until the stamina meter reads at least {@code stoplevel}.
     *
     * Used to go through {@code GameUI.drink}, the client's own drink, which looks for a flask in
     * the open windows but checks only equipment slots 6 and 7 and only for a {@code bucket-water} -
     * so a Waterflask WORN anywhere else is invisible to it, it returns false, and the caller read
     * that as "no water" and quit over a full flask on their belt. It now sips through
     * {@link Carried#drink(GameUI)}, which reads every equipment slot.
     *
     * One mechanism only: drinking is a timed action and a fresh iact on a vessel cancels the drink
     * in progress, so {@code Carried.drink} and {@code GameUI.drink} must never both run.
     */
    public static void drinkTillFull(GameUI gui, double stoplevel) throws InterruptedException {
        for (int sip = 0; sip < DRINK_SIPS_MAX; sip++) {
            IMeter.Meter stam = gui.getmeter("stam", 0);
            if ((stam == null) || (stam.a >= stoplevel))
                return;
            double was = stam.a;
            if (!Carried.drink(gui))
                return;
            boolean moved = false;
            for (int i = 0; i < DRINK_SIP_WAIT_TICKS; i++) {
                Thread.sleep(DRINK_SIP_WAIT_MS);
                if (gui.getmeter("stam", 0).a > was) {
                    moved = true;
                    break;
                }
            }
            if (!moved)
                return;
        }
    }

    public static void clickWItemAndSelectOption(GameUI gui, WItem wItem, int index) {
        wItem.item.wdgmsg("iact", Coord.z, gui.ui.modflags());
        gui.ui.rcvr.rcvmsg(gui.ui.lastWidgetID+1, "cl", index, gui.ui.modflags());
    }

    /** Number of random click attempts in unstuck routine. */
    private static final int UNSTUCK_ATTEMPTS = 5;

    /** Maximum coordinate offset in pixels for random unstuck clicks. */
    private static final int UNSTUCK_MAX_OFFSET = 250;

    /** Sleep interval in milliseconds between unstuck click attempts. */
    private static final long UNSTUCK_SLEEP_MS = 100L;

    public static void unstuck(GameUI gui) throws InterruptedException {
        Coord2d pc = gui.map.player().rc;
        Random r = new Random();
        for (int i = 0; i < UNSTUCK_ATTEMPTS; i++) {
            int xAdd = r.nextInt(UNSTUCK_MAX_OFFSET * 2) - UNSTUCK_MAX_OFFSET;
            int yAdd = r.nextInt(UNSTUCK_MAX_OFFSET * 2) - UNSTUCK_MAX_OFFSET;
            gui.map.wdgmsg("click", Coord.z, pc.floor(posres).add(xAdd, yAdd), 1, 0);
            Thread.sleep(UNSTUCK_SLEEP_MS);
        }
    }

    public static void rightClickGobAndSelectOption(GameUI gui, Gob gob, int index) {
        gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
        gui.ui.rcvr.rcvmsg(gui.ui.lastWidgetID+1, "cl", index, gui.ui.modflags());
    }

    public static ArrayList<Gob> getAllSupports(GameUI gui) {
        ArrayList<Gob> supports = new ArrayList<>();
        Set<String> types = new HashSet<>(Arrays.asList("gfx/terobjs/ladder", "gfx/terobjs/minesupport", "gfx/terobjs/column", "gfx/terobjs/minebeam"));
        synchronized (gui.map.glob.oc) {
            for (Gob gob : gui.map.glob.oc) {
                try {
                    Resource res = gob.getres();
                    if (res != null && types.contains(res.name)) {
                        supports.add(gob);
                    }
                } catch (Loading ignored) {}
            }
        }
        return supports;
    }

    /** Grid size in tiles for getGridHeightAvg calculation. */
    private static final int GRID_SIZE_TILES = 100;

    public static void getGridHeightAvg(GameUI gui){
        try {
            Coord playerCoord = gui.map.player().rc.floor(tilesz);
            MCache.Grid grid = gui.ui.sess.glob.map.getgrid(playerCoord.div(cmaps));
            float wholeGridHeight = 0;
            float[] quarterHeights = new float[4];
            int gridSize = GRID_SIZE_TILES;
            int halfGridSize = gridSize / 2;
            for (int i = 0; i < gridSize; i++) {
                for (int j = 0; j < gridSize; j++) {
                    wholeGridHeight += grid.z[i * gridSize + j];
                    int quarterIndex;
                    if(i < halfGridSize) {
                        quarterIndex = (j < halfGridSize) ? 0 : 1;
                    } else {
                        quarterIndex = (j < halfGridSize) ? 2 : 3;
                    }
                    quarterHeights[quarterIndex] += grid.z[i * gridSize + j];
                }
            }
            String[] quarterNames = {"N-W", "N-E", "S-W", "S-E"};
            StringBuilder message = new StringBuilder("Whole grid average height is: " + wholeGridHeight / 10000 + ", ");
            for (int i = 0; i < 4; i++) {
                message.append("\n").append(quarterNames[i]).append(" quarter average height is: ").append(quarterHeights[i] / 2500).append(", ");
            }
            gui.msg(message.toString(), Color.WHITE);
        } catch (Loading ignored) {}
    }

    public static List<WItem> getAllItemsFromAllInventoriesAndStacksExcludeBeltAndKeyring(GameUI gui){
        List<WItem> items = new ArrayList<>();
        List<Inventory> allInventories = gui.getAllInventories();

        for (Inventory inventory : allInventories) {
            if (!isBeltOrKeyring(inventory)) {
                for (WItem item : inventory.getAllItems()) {
                    if (!item.item.getname().contains("stack of")) {
                        items.add(item);
                    }
                }
            }
        }

        items.addAll(gui.getAllContentsWindows());
        return items;
    }

    public static boolean isBeltOrKeyring(Inventory inventory) {
        if (inventory.parent instanceof Window) {
            String cap = ((Window) inventory.parent).cap;
            return cap.contains("Belt") || cap.contains("Keyring");
        }
        return false;
    }

    public static boolean rightClickGobOverlayWithItem(GameUI gui, Gob gob, String overlayResName) {
        if (gob != null && !gob.ols.isEmpty()) {
            Optional<Gob.Overlay> foundOverlay = gob.ols.stream()
                    .filter(ol -> ol != null && ol.spr != null && ol.spr.res != null && overlayResName.equals(ol.spr.res.name))
                    .map(ol -> (Gob.Overlay) ol)
                    .findFirst();

            if (foundOverlay.isPresent()) {
                Gob.Overlay gobOverlay = foundOverlay.get();
                gui.map.wdgmsg("itemact", Coord.z, gob.rc.floor(posres), 0, 1, (int) gob.id, gob.rc.floor(posres), gobOverlay.id, -1);
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    public static boolean rightClickGobOverlayAndSelectOption(GameUI gui, Gob gob, int index, String overlayResName) {
        if (gob != null && !gob.ols.isEmpty()) {
            Optional<Gob.Overlay> foundOverlay = gob.ols.stream()
                    .filter(ol -> ol != null && ol.spr != null && ol.spr.res != null && overlayResName.equals(ol.spr.res.name))
                    .map(ol -> (Gob.Overlay) ol)
                    .findFirst();

            if (foundOverlay.isPresent()) {
                Gob.Overlay gobOverlay = foundOverlay.get();
                gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 1, (int) gob.id, gob.rc.floor(posres), gobOverlay.id, -1);
                gui.ui.rcvr.rcvmsg(gui.ui.lastWidgetID+1, "cl", index, gui.ui.modflags());
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    public static boolean gobHasOverlay (Gob gob, String overlayResName){
        if (gob != null && !gob.ols.isEmpty()) {
            Optional<Gob.Overlay> foundOverlay = gob.ols.stream()
                    .filter(ol -> ol != null && ol.spr != null && ol.spr.res != null && overlayResName.equals(ol.spr.res.name))
                    .map(ol -> (Gob.Overlay) ol)
                    .findFirst();
            if (foundOverlay.isPresent()) {
                return true;
            }
        }
        return false;
    }

}
