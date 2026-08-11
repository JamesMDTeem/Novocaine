package haven.automated;

import haven.Coord;
import haven.Coord2d;
import haven.Drawable;
import haven.Equipory;
import haven.FlowerMenu;
import haven.GAttrib;
import haven.GItem;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.ResDrawable;
import haven.Resource;
import haven.Tiler;
import haven.WItem;
import haven.resutil.WaterTile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static haven.OCache.posres;

public class CoracleScript implements Runnable {

    private GameUI gui;
    private static final int TIMEOUT = 4000;

    private static final int HAND_DELAY = 8;
    public List<String> bogtype = new ArrayList<>(Arrays.asList("gfx/tiles/bog", "gfx/tiles/bogwater", "gfx/tiles/fen", "gfx/tiles/fenwater", "gfx/tiles/swamp", "gfx/tiles/swampwater", "", ""));
    private static final double[][] surroundingTilesPositions = {{-11,-11},{-11,0},{-11,11},{11,-11},{11,0},{11,11},{0,-11},{0,11}}; // {0,0} would be my own tile

    public CoracleScript(GameUI gui) {
        this.gui = gui;
    }

    public void run() {
        Gob player = gui.map.player();
        if (player == null)
            return; //player is null, possibly taking a road, don't bother trying to do all of the below.

        //Get Equipment
        Equipory eq = gui.getequipory();
        if (eq == null)
            return;

        if (player.imInCoracle) { // ND: If you're in a coracle already, try to pick it up
            //find closest coracle and pick it up
            Gob gobCoracle = null;
            synchronized (gui.map.glob.oc) {
                for (Gob gob : gui.map.glob.oc) {
                    try {
                        Resource res = gob.getres();
                        if (res != null && (res.name.startsWith("gfx/terobjs/vehicle/coracle"))) {
                            Coord2d plc = gui.map.player().rc;
                            if ((gobCoracle == null || gob.rc.dist(plc) < gobCoracle.rc.dist(plc)))
                                gobCoracle = gob;
                        }
                    } catch (Loading l) {
                    }
                }
            }

            if (gobCoracle == null)
                return;

            if (gobCoracle.rc.dist(gui.map.player().rc) < 11*5) {

                // ND: Check if we're too far from a shore/shallow water tile, to prevent picking up the coracle. Just check the 8 tiles directly adjacent to us, that's good enough.
                // I do it this way rather than just checking if we're on shallow water cause sometimes deep water tiles and land tiles have no shallow tiles between them.
                // (So basically it would be retarded to prevent dismounting just by being in deep water, even when we're literally touching land)
                boolean preventCoraclePickup = false;
                MCache mcache = gui.ui.sess.glob.map;
                int t = mcache.gettile(gui.map.player().rc.floor(MCache.tilesz));
                Tiler tl = mcache.tiler(t);
                if (tl instanceof WaterTile){
                    Resource res = mcache.tilesetr(t);
                    if (res != null) {
                        if (res.name.contains("deep")){ // ND: If the tile I'm currently on is deep water
                            for (double[] position : surroundingTilesPositions) { // ND: Check all 8 tiles encircling me
                                Coord2d tilePosition = new Coord2d(gui.map.player().rc.x + position[0], gui.map.player().rc.y + position[1]);
                                int nearTileInt = mcache.gettile(tilePosition.floor(MCache.tilesz));
                                Tiler nearTile = mcache.tiler(nearTileInt);
                                if (nearTile instanceof WaterTile){
                                    Resource nearTileRes = mcache.tilesetr(nearTileInt);
                                    if (nearTileRes != null) {
                                        if (nearTileRes.name.contains("deep")){
                                            preventCoraclePickup = true;
                                        } else { // ND: If the adjacent tile is a water tile, but not a deep tile, we can stop checking and allow dismounting
                                            preventCoraclePickup = false;
                                            break;
                                        }
                                    }
                                } else { // ND: If the adjacent tile is not a water tile, we can stop checking and allow dismounting
                                    preventCoraclePickup = false;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (preventCoraclePickup) {
                    gui.error("Coracle Script: You're surrounded by Deep Water! Get closer to Shallow Water or Land before Picking up the Coracle.");
                    return;
                } else {
                    FlowerMenu.setNextSelection("Pick up");
                    gui.map.wdgmsg("click", Coord.z, gobCoracle.rc.floor(posres), 3, 0, 0, (int) gobCoracle.id, gobCoracle.rc.floor(posres), 0, -1);
                }
            }

            if (eq.slots[21] == null) { // ND: Don't need to do anything here, cause "Pick up" already puts it on your cape slot
                return;
            } else {
                int timeout = 0;
                while (gui.hand.isEmpty() || gui.vhand == null) {
                    timeout += HAND_DELAY;
                    if (timeout >= TIMEOUT) {
                        gui.error("Coracle Script: Timed out trying to Pick up Coracle");
                        return;
                    }
                    try {
                        Thread.sleep(HAND_DELAY);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }

                //Check if there is a 4x3 space in inventory
                Coord freecoord = gui.maininv.isRoom(4,3);
                if (freecoord != null) {
                    gui.maininv.wdgmsg("drop", freecoord);
                } else {
                    gui.error("Coracle Script: No free space in Inventory for Coracle.");
                }
            }

        } else { // ND: If you're NOT in a coracle already, run this part instead
            // Find WItem coracle by going through equipment list
            WItem coracle = null;

            coracle = gui.maininv.getItemPartial("Coracle");

            for (WItem wi : eq.slots) {
                try {
                    if (wi.item.getname().equals("Coracle")) {
                        coracle = wi;
                    }
                } catch (NullPointerException ex) {
                    //System.out.println("nothing equipped in this slot");
                }
            }


            if (coracle != null) { // ND: If I have any coracle in my inventory at all, do the following
                try {
                    // ND: Wait until we're on a shallow-water or bog tile before dropping.
                    // Decided by the TILESET NAME, not the Tiler class: the tiler caches are
                    // never cleared when the world remaps tile ids, so a WaterTile tiler can be
                    // stale, and a name that has not resolved yet means "not water yet" either
                    // way. tileTypeName answers null for both "undefined" and "still loading",
                    // which is exactly the keep-waiting signal this loop needs - never drop on a
                    // tile whose water-ness we cannot confirm.
                    MCache mcache = gui.ui.sess.glob.map;
                    int timeout = 0;
                    boolean onWater = false;
                    while (!onWater) {
                        try {
                            String name = mcache.tileTypeName(
                                mcache.gettile(gui.map.player().rc.floor(MCache.tilesz)));
                            if (name != null) {
                                if (haven.automated.pathfinder.Map.isDeep(name)) {
                                    gui.error("Coracle Script: You can't drop a Coracle while swimming in Deep Water! You must be in Shallow Water!");
                                    return;
                                }
                                if (haven.automated.pathfinder.Map.isShallow(name) || bogtype.contains(name)) {
                                    onWater = true;
                                }
                            }
                        } catch (Loading l) {
                            // grid still loading; try again next poll
                        }
                        if (!onWater) {
                            timeout++;
                            if (timeout > 300) {
                                gui.error("Coracle Script: Timed out waiting for Water Tile to drop Coracle on.");
                                return;
                            }
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                    //Get gui item and drop coracle
                    GItem coracleItem = coracle.item;
                    coracleItem.wdgmsg("drop", new Coord(coracleItem.sz.x / 2, coracleItem.sz.y / 2));
                } catch(Exception e) {
                    gui.error("Coracle Script: Some error occured, stopping script.");
                    return;
                }
            }

            Gob gobCoracle = null;
            boolean moutableCoracle = false;
            // ND: try this 3 times, cause sometimes it fails to click the coracle. Idk why, or how, I tried multiple print statements. 3 seems to be enough.
            for (int i = 0; i < 3; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e1) {
//                    e1.printStackTrace();
                    return;
                }

                // find closest coracle
                synchronized (gui.map.glob.oc) {
                    for (Gob gob : gui.map.glob.oc) {
                        try {
                            Resource res = gob.getres();
                            if (res != null && (res.name.startsWith("gfx/terobjs/vehicle/coracle"))) {
                                Coord2d plc = gui.map.player().rc;
                                if ((gobCoracle == null || gob.rc.dist(plc) < gobCoracle.rc.dist(plc)))
                                    gobCoracle = gob;
                            }
                        } catch (Loading l) {
                        }
                    }
                }
                if (gobCoracle != null) {
                    Integer peekrbuf = null;
                    for (GAttrib g : gobCoracle.attr.values()) {
                        if (g instanceof Drawable) {
                            if (g instanceof ResDrawable) {
                                ResDrawable resDrawable = gobCoracle.getattr(ResDrawable.class);
                                peekrbuf = resDrawable.sdt.checkrbuf(0);
                            }
                        }
                    }
                    if (peekrbuf != null && peekrbuf == 22) { // ND: peekrbuf 22 means empty coracle, on water
                        if (gobCoracle.rc.dist(gui.map.player().rc) < 11 * 6) {
                            moutableCoracle = true;
                            FlowerMenu.setNextSelection("Into the blue yonder!");
                            gui.map.wdgmsg("click", Coord.z, gobCoracle.rc.floor(posres), 3, 0, 0, (int) gobCoracle.id, gobCoracle.rc.floor(posres), 0, -1);
                            //System.out.println("I CLICKED: " + i);
                        }
                    }
                }
            }
            if (coracle == null && (gobCoracle == null || !moutableCoracle)){
                gui.error("Coracle Script: No Coracle found in Inventory and no mountable Coracle found in close proximity.");
            }
        }
    }
}
