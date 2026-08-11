package haven.automated;


import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Label;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.pathfinder.Pathfinder;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Random;

import static haven.OCache.posres;

public class OceanScoutBot extends Window implements Runnable, Stoppable {
    // Distances in this bot are expressed in pixel units where one tile = 11px (MCache.tilesz).
    private static final int TILE = (int) MCache.tilesz.x;
    private static final int CLICK_STEP = 44;               // px to nudge the player per click
    private static final int FLEE_STEP = 2 * TILE;          // px to step away from a nearby mob
    private static final int GROUND_SEARCH_RADIUS = 40 * TILE; // px range for random ground tile
    private static final int WATER_SEARCH_RADIUS = 30 * TILE;  // px range for random water tile
    private static final int GOB_SCAN_RADIUS = 25 * TILE;      // px range for nearby-gob scans
    private static final int DANGER_RADIUS = 14 * TILE;        // px inside which a walrus/orca is dangerous
    private static final int VERY_DANGER_RADIUS = 11 * TILE;   // px inside which a walrus/orca is fled
    private static final int SAMPLE_STEPS = 20;                // radial/angular samples per sweep in getNextLoc

    private int checkClock;
    private GameUI gui;
    private Thread self;
    public volatile boolean stop;
    private MCache mcache;
    private int clockwiseDirection = 1;
    private double ang = 0;
    private double searchRadius = 5;
    private ArrayList<Gob> nearbyGobs = new ArrayList<>();
    private Random random = new Random();
    private int successLocs;
    private boolean active = false;

    public OceanScoutBot(GameUI gui) {
        super(UI.scale(UI.scale(274, 96)), "Ocean Scouting Bot");
        this.gui = gui;
        checkClock = 0;
        stop = false;
        mcache = gui.map.glob.map;
        add(new Label(""), UI.scale(263, 0)); // ND: Label to fix horizontal size
        add(new Label("Remember: The direction of the Shoreline is always"), UI.scale(10, 4));
        add(new Label("the opposite of the Deeper Water Edge."), UI.scale(10, 18));
        CheckBox dirBox = new CheckBox("Clockwise (Deeper Water Edge)") {
            {
                a = true;
            }

            public void set(boolean val) {
                clockwiseDirection = val ? 1 : -1;
                a = val;
            }
        };
        add(dirBox, UI.scale(16, 42));

        add(new Button(UI.scale(170), "Start"){
            @Override
            public void click() {
                active = !active;
                if (active){
                    this.change("Stop");
                } else {
                    ui.gui.map.wdgmsg("click", Coord.z, ui.gui.map.player().rc.floor(posres), 1, 0);
                    this.change("Start");
                }
            }
        }, UI.scale(52, 66));
        pack();
    }

    @Override
    public void run() {
        self = Thread.currentThread();
        try {
            while (!stop) {
                if (!active) {
                    Thread.sleep(200);
                    continue;
                }
                if (successLocs > 20) {
                    Coord2d groundTile = findRandomGroundTile();
                    Coord2d groundVector = groundTile.sub(ui.gui.map.player().rc);
                    groundVector = groundVector.div(groundVector.abs()).mul(CLICK_STEP);
                    ui.gui.map.wdgmsg("click", Coord.z, ui.gui.map.player().rc.add(groundVector).floor(posres), 1, 0);
                    Thread.sleep(300);
                }

                nearbyGobs = getNearbyGobs();
                Coord loc = getNextLoc();
                if (loc != null) {
                    ang -= clockwiseDirection * Math.PI / 2;
                    ui.gui.map.wdgmsg("click", Coord.z, new Coord2d(loc.x, loc.y).floor(posres), 1, 0);
                } else {
                    Coord2d pcCoord = ui.gui.map.player().rc;
                    Coord2d dangerMob = isVeryDangerZone(ui.gui.map.player().rc.floor());
                    if (dangerMob != null) {
                        Coord2d addCoord = pcCoord.sub(dangerMob);
                        Coord2d clickCoord = pcCoord.add(addCoord.div(addCoord.abs()).mul(FLEE_STEP));
                        ui.gui.map.wdgmsg("click", Coord.z, clickCoord.floor(posres), 1, 0);
                    } else {
                        Coord2d gocoord = findRandomWaterTile();
                        Coord2d groundVector = gocoord.sub(ui.gui.map.player().rc);
                        groundVector = groundVector.div(groundVector.abs()).mul(CLICK_STEP);
                        ui.gui.map.wdgmsg("click", Coord.z, ui.gui.map.player().rc.add(groundVector).floor(posres), 1, 0);
                    }
                    Thread.sleep(300);
                }
                Thread.sleep(200);
                checkClock++;
            }
        } catch (InterruptedException e) {
//            System.out.println("interrupted.. after checkclock: " + checkClock);
        }

    }

    private Coord2d findRandomGroundTile() {
        Coord2d basecoord = gui.map.player().rc;
        int radius = GROUND_SEARCH_RADIUS;
        for (int i = 0; i < 1000; i++) {
            Coord2d rancoord = new Coord2d(random.nextInt(radius * 2) - radius, random.nextInt(radius * 2) - radius);
            if (!isWater(basecoord.add(rancoord).floor())) {
                return basecoord.add(rancoord);
            }
        }
        return basecoord;
    }

    private Coord2d findRandomWaterTile() {
        Coord2d basecoord = gui.map.player().rc;
        int radius = WATER_SEARCH_RADIUS;
        for (int i = 0; i < 1000; i++) {
            Coord2d rancoord = new Coord2d(random.nextInt(radius * 2) - radius, random.nextInt(radius * 2) - radius);
            if (isWater(basecoord.add(rancoord).floor())) {
                return basecoord.add(rancoord);
            }
        }
        return basecoord;
    }

    private ArrayList<Gob> getNearbyGobs() {
        ArrayList<Gob> gobs = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob gob : gui.map.glob.oc) {
                if (gui.map.player().rc.dist(gob.rc) < 3) {
                    continue;
                }
                if (gui.map.player().rc.dist(gob.rc) < GOB_SCAN_RADIUS && gob.collisionBox != null && gob.collisionBox.fx != null) {
                    gobs.add(gob);
                }
            }
        }
        return gobs;
    }

    private Coord getNextLoc() {
//        Coord pltc = new Coord(gui.map.player().rc.floor().x / 11, gui.map.player().rc.floor().y / 11);
        Coord pc = gui.map.player().rc.floor();
        double curAng = ang;
        int angles = SAMPLE_STEPS;
        while (clockwiseDirection == 1 ? ang <= curAng + 2 * Math.PI : ang >= curAng - 2 * Math.PI) {
            boolean foundground = false;
            for (int i = 0; i < SAMPLE_STEPS; i++) {
                Coord2d addcoord = new Coord2d(-Math.cos(-ang) * i * searchRadius, Math.sin(-ang) * i * searchRadius);
                Coord t = pc.add(addcoord.floor());


                if (checkTiles(t)) {
                    foundground = true;
                }
            }
            if (!foundground) {
                Coord2d addcoord = new Coord2d(-Math.cos(-ang) * SAMPLE_STEPS * searchRadius, Math.sin(-ang) * SAMPLE_STEPS * searchRadius);
                successLocs++;
                return (pc.add(addcoord.floor()));
            } else {
                successLocs = 0;
            }
            ang += clockwiseDirection * 2 * Math.PI / angles;
        }
        return null;
    }

    private boolean checkTiles(Coord t) {
        int rad = 2;
        for (int i = -rad; i <= rad; i++) {
            for (int j = -rad; j <= rad; j++) {
                if (!isWater(t.add(i * TILE, j * TILE))) {
                    return true;
                } else if (isGobCollision(t.add(i * TILE, j * TILE))) {
                    return true;
                } else if (isDangerZone(t.add(i * TILE, j * TILE))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isGobCollision(Coord t) {
        for (Gob gob : nearbyGobs) {
            if (gob != null && gob.getres() != null) {
                if (Pathfinder.isInsideBoundBox(gob.rc.floor(), gob.a, gob.getres().name, t)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDangerZone(Coord t) {
        for (Gob gob : nearbyGobs) {
            if(gob.getres() != null){
                if ((gob.getres().name.endsWith("/walrus") || (gob.getres().name.endsWith("/orca")) && t.dist(gob.rc.floor()) < DANGER_RADIUS)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Coord2d isVeryDangerZone(Coord t) {
        for (Gob gob : nearbyGobs) {
            if (gob.getres() != null) {
                if ((gob.getres().name.endsWith("/walrus") || (gob.getres().name.endsWith("/orca")) && t.dist(gob.rc.floor()) < VERY_DANGER_RADIUS)) {
                    return gob.rc;
                }
            }
        }
        return null;
    }


    private boolean isWater(Coord t) {
        Coord pltc = new Coord(t.x / 11, t.y / 11);
        try {
            int dt = mcache.gettile(pltc);
            Resource res = mcache.tilesetr(dt);
            if (res == null)
                return false;

            String name = res.name;
            if (name.equals("gfx/tiles/odeep")) {
                return true;
            } else {
                return false;
            }
        } catch (Loading e) {
            return false;
        }
    }


    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if((sender == this) && (Objects.equals(msg, "close"))) {
            stop();
            if (gui.nbots != null)
                gui.nbots.forget(this);
            reqdestroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    public void stop() {
        stop = true;
        ui.gui.map.wdgmsg("click", Coord.z, ui.gui.map.player().rc.floor(posres), 1, 0);
        if (gui.map.pfthread != null) {
            gui.map.pfthread.interrupt();
        }
        if (self != null) {
            self.interrupt();
        }
        this.destroy();
    }

    @Override
    public void reqdestroy() {
        Utils.setprefc("wndc-oceanScoutBotWindow", this.c);
        super.reqdestroy();
    }

}
