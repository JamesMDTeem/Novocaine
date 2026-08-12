package haven.automated;

import haven.Button;
import haven.Label;
import haven.Window;
import haven.CheckBox;
import haven.Coord;
import haven.Coord2d;
import haven.GOut;
import haven.GameUI;
import haven.Gob;
import haven.GobHealth;
import haven.OldDropBox;
import haven.OptWnd;
import haven.RichText;
import haven.Text;
import haven.UI;
import haven.Utils;
import haven.Widget;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static haven.OCache.posres;

public class MiningSafetyAssistant extends Window implements Runnable, Stoppable {
    // Distances are in pixels, where one tile = 11px (MCache.tilesz).
    private static final int TILE = 11;
    private static final double HALF_TILE = TILE / 2.0;          // centre of a tile
    private static final double MINED_TILE_OFFSET = 13.75;       // how far ahead of the player the pick lands
    private static final int WOODEN_SUPPORT_RANGE = 100;         // ladder/minesupport support radius, px
    private static final int COLUMN_SUPPORT_RANGE = 125;         // stone column support radius, px
    private static final int MINEBEAM_SUPPORT_RANGE = 150;       // minebeam support radius, px
    private static final int LOOSE_ROCK_RANGE = 125;             // loose rock closer than this stops mining, px
    private static final double SUPPORT_HP_FIFTY = 0.5;          // hp fraction that trips the "<50" stop
    private static final double SUPPORT_HP_TWENTY_FIVE = 0.25;   // hp fraction that trips the "<25" stop
    private static final int ESC_KEY = 27;                       // ESC cancels the current mining pose

    private final GameUI gui;
    private boolean stop;
    public static CheckBox preventUnsafeMiningCheckBox;
    public static CheckBox stopUnsafeMiningCheckBox;
    public static CheckBox stopMiningFiftyCheckBox;
    public static CheckBox stopMiningTwentyFiveCheckBox;
    public static CheckBox stopMiningLooseRockCHeckBox;

    public static CheckBox enableMineSweeperCheckBox;
    public OldDropBox<Integer> sweeperDurationDropbox;

    ArrayList<Gob> supports = new ArrayList<>();
    ArrayList<Gob> looseRocks = new ArrayList<>();
    private int counter = 0;
    private Thread self;

    public MiningSafetyAssistant(GameUI gui) {
        super(UI.scale(new Coord(220, 180)), "Mining Safety Assistant");
        this.gui = gui;
        this.stop = false;
        Widget prev;

        preventUnsafeMiningCheckBox = new CheckBox("Prevent unsafe mining.") {
            {a = Utils.getprefb("preventMiningOutsideSupport", false);}
            public void set(boolean val) {
                Utils.setprefb("preventMiningOutsideSupport", val);
                a = val;
            }
        };
        prev = add(preventUnsafeMiningCheckBox, UI.scale(new Coord(10, 12)));
        preventUnsafeMiningCheckBox.tooltip = RichText.render("This option will prevent selecting mining area even \npartially outside (visible) mining supports range. \n(Cannot select area outside view range)", UI.scale(300));

        stopUnsafeMiningCheckBox = new CheckBox("Stop unsafe mining.") {
            {a = Utils.getprefb("stopMiningWhenOutsideSupport", false);}
            public void set(boolean val) {
                Utils.setprefb("stopMiningWhenOutsideSupport", val);
                a = val;
            }
        };
        prev = add(stopUnsafeMiningCheckBox, prev.pos("bl").adds(0, 6));
        stopUnsafeMiningCheckBox.tooltip = RichText.render("If currently mined tile is outside support range \nmining will stop. (Drinking animation overrides mining \nand delay bot reaction - not 100% safe)", UI.scale(300));

        stopMiningFiftyCheckBox = new CheckBox("Stop mining <50.") {
            {a = Utils.getprefb("stopMiningFifty", false);}
            public void set(boolean val) {
                Utils.setprefb("stopMiningFifty", val);
                a = val;
            }
        };
        prev = add(stopMiningFiftyCheckBox, prev.pos("bl").adds(0, 6));
        stopMiningFiftyCheckBox.tooltip = RichText.render("If currently mined tile is withing support range \nbelow 50% hp mining will stop.", UI.scale(300));


        stopMiningTwentyFiveCheckBox = new CheckBox("Stop mining <25.") {
            {a = Utils.getprefb("stopMiningTwentyFive", false);}
            public void set(boolean val) {
                Utils.setprefb("stopMiningTwentyFive", val);
                a = val;
            }
        };
        prev = add(stopMiningTwentyFiveCheckBox, prev.pos("bl").adds(0, 6));
        stopMiningTwentyFiveCheckBox.tooltip = RichText.render("If currently mined tile is withing support range \nbelow 25% hp mining will stop.", UI.scale(300));

        stopMiningLooseRockCHeckBox = new CheckBox("Stop mining near loose rock.") {
            {a = Utils.getprefb("stopMiningLooseRock", false);}
            public void set(boolean val) {
                Utils.setprefb("stopMiningLooseRock", val);
                a = val;
            }
        };
        prev = add(stopMiningLooseRockCHeckBox, prev.pos("bl").adds(0, 6));
        stopMiningLooseRockCHeckBox.tooltip = RichText.render("If currently mined tile is withing ~9 tiles from any \nloose rock mining will stop.", UI.scale(300));

        prev = add(enableMineSweeperCheckBox = new CheckBox("Show Mine Sweeper Numbers"){
            {a = (Utils.getprefb("enableMineSweeper", true));}
            public void set(boolean val) {
                OptWnd.enableMineSweeperCheckBox.set(val);
                a = val;
            }
        }, prev.pos("bl").adds(0, 10));
        enableMineSweeperCheckBox.tooltip = RichText.render("Enabling this will cause cave dust tiles to show the number of potential cave-ins surrounding them, just like in Minesweeper." +
                "\n$col[218,163,0]{Note:} $col[185,185,185]{If a cave-in has been mined out, the tiles surrounding it will still drop cave dust, and they will still show a number on the ground. The cave dust tiles are pre-generated with the world. That's just how Loftar coded it.}" +
                "\n$col[218,163,0]{Note:} $col[185,185,185]{You can still pick up the cave dust item off the ground. The numbers are affected only by the duration of the falling dust particles effect (aka dust rain), which can be set below}" +
                "\n\n$col[200,0,0]{NOTE:} $col[185,185,185]{There's a bug with the falling dust particles, that we can't really \"fix\". If you mine them out on a level, the same particles can also show up on different levels or the overworld. If you want them to vanish, you can just relog, but they will despawn from their original location too.}", UI.scale(300));

        prev = add(new Label("Sweeper Display Duration (Min):"), prev.pos("bl").adds(0, 2));
        prev.tooltip = RichText.render("Use this to set how long you want the numbers to be displayed on the ground, in minutes. The numbers will be visible as long as the dust particle effect stays on the tile." +
                "\n$col[218,163,0]{Note:} $col[185,185,185]{Changing this option will only affect the duration of newly spawned cave dust tiles. The duration is set once the wall tile is mined and the cave dust spawns in.}", UI.scale(300));

        add(sweeperDurationDropbox = new OldDropBox<Integer>(UI.scale(40), OptWnd.sweeperDurations.size(), UI.scale(17)) {
            {
                super.change(OptWnd.sweeperDurations.get(OptWnd.sweeperSetDuration));
            }
            @Override
            protected Integer listitem(int i) {
                return OptWnd.sweeperDurations.get(i);
            }
            @Override
            protected int listitems() {
                return OptWnd.sweeperDurations.size();
            }
            @Override
            protected void drawitem(GOut g, Integer item, int i) {
                g.aimage(Text.renderstroked(item.toString()).tex(), Coord.of(UI.scale(3), g.sz().y / 2), 0.0, 0.5);
            }
            @Override
            public void change(Integer item) {
                super.change(item);
                OptWnd.sweeperDurationDropbox.change(item);
            }
        }, prev.pos("ul").adds(160, 2));


        add(new Label("Movement"), UI.scale(154, 10));
        add(new Button(UI.scale(20), "↖") {
            @Override
            public void click() {
                if (gui.map.player().getv() == 0) {
                    gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.add(-TILE, -TILE).floor(posres), 1, 0);
                }
            }
        }, UI.scale(150, 27));
        add(new Button(UI.scale(20), "↑") {
            @Override
            public void click() {
                if (gui.map.player().getv() == 0) {
                    gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.add(0, -TILE).floor(posres), 1, 0);
                }
            }
        }, UI.scale(170, 27));
        add(new Button(UI.scale(20), "↗") {
            @Override
            public void click() {
                if (gui.map.player().getv() == 0) {
                    gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.add(TILE, -TILE).floor(posres), 1, 0);
                }
            }
        }, UI.scale(190, 27));

        add(new Button(UI.scale(20), "←") {
            @Override
            public void click() {
                if (gui.map.player().getv() == 0) {
                    gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.add(-TILE, 0).floor(posres), 1, 0);
                }
            }
        }, UI.scale(150, 50));
        add(new Button(UI.scale(20), "○") {
            @Override
            public void click() {
                if (gui.map.player().getv() == 0) {
                    Coord2d center = ui.gui.map.player().rc.div(TILE).floord().mul(TILE).add(HALF_TILE, HALF_TILE);
                    gui.map.wdgmsg("click", Coord.z, center.floor(posres), 1, 0);
                }
            }
        }, UI.scale(170, 50));
        add(new Button(UI.scale(20), "→") {
            @Override
            public void click() {
                if (gui.map.player().getv() == 0) {
                    gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.add(TILE, 0).floor(posres), 1, 0);
                }
            }
        }, UI.scale(190, 50));


        add(new Button(UI.scale(20), "↙") {
            @Override
            public void click() {
                if (gui.map.player().getv() == 0) {
                    gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.add(-TILE, TILE).floor(posres), 1, 0);
                }
            }
        }, UI.scale(150, 73));
        add(new Button(UI.scale(20), "↓") {
            @Override
            public void click() {
                if (gui.map.player().getv() == 0) {
                    gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.add(0, TILE).floor(posres), 1, 0);
                }
            }
        }, UI.scale(170, 73));
        add(new Button(UI.scale(20), "↘") {
            @Override
            public void click() {
                if (gui.map.player().getv() == 0) {
                    gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.add(TILE, TILE).floor(posres), 1, 0);
                }
            }
        }, UI.scale(190, 73));
    pack();

    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && (Objects.equals(msg, "close"))) {
            stop();
            if (gui.nbots != null)
                gui.nbots.forget(this);
            reqdestroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    @Override
    public void stop() {
        stop = true;
        if (self != null)
            self.interrupt();
        if (gui.map != null && gui.map.pfthread != null)
            gui.map.pfthread.interrupt();
        this.destroy();
    }

    @Override
    public void run() {
        self = Thread.currentThread();
        while (!stop) {
            if (counter == 0 && (stopUnsafeMiningCheckBox.a || stopMiningFiftyCheckBox.a || stopMiningTwentyFiveCheckBox.a)) {
                supports = AUtils.getAllSupports(gui);
            }
            if(gui.map.player() != null){
                if (stopUnsafeMiningCheckBox.a && (gui.map.player().getPoses().contains("pickan") || gui.map.player().getPoses().contains("gfx/borka/choppan"))) {
                    Gob player = gui.map.player();
                    Coord2d minedTile = new Coord2d(player.rc.x + (Math.cos(player.a) * MINED_TILE_OFFSET), player.rc.y + Math.sin(player.a) * MINED_TILE_OFFSET);
                    Set<Gob> gobsInRange = new HashSet<>();
                    for (Gob support : supports) {
                        String res = support.getres().name;
                        if (res.equals("gfx/terobjs/ladder") || res.equals("gfx/terobjs/minesupport")) {
                            if (support.rc.dist(minedTile) <= WOODEN_SUPPORT_RANGE) {
                                gobsInRange.add(support);
                            }
                        } else if (res.equals("gfx/terobjs/column")) {
                            if (support.rc.dist(minedTile) <= COLUMN_SUPPORT_RANGE) {
                                gobsInRange.add(support);
                            }
                        } else if (res.equals("gfx/terobjs/minebeam")) {
                            if (support.rc.dist(minedTile) <= MINEBEAM_SUPPORT_RANGE) {
                                gobsInRange.add(support);
                            }
                        }
                    }
                    if (gobsInRange.size() < 1) {
                        ui.root.wdgmsg("gk", ESC_KEY);
                        gui.error("Trying to mine outside supports.");
                    }
                }

                if (stopMiningLooseRockCHeckBox.a && (gui.map.player().getPoses().contains("pickan") || gui.map.player().getPoses().contains("gfx/borka/choppan"))) {
                    Gob player = gui.map.player();
                    Coord2d minedTile = new Coord2d(player.rc.x + (Math.cos(player.a) * MINED_TILE_OFFSET), player.rc.y + Math.sin(player.a) * MINED_TILE_OFFSET);
                    if (counter == 0) {
                        looseRocks = AUtils.getGobs("gfx/terobjs/looserock", gui);
                    }
                    for (Gob looseRock : looseRocks) {
                        if (looseRock.rc.dist(minedTile) <= LOOSE_ROCK_RANGE) {
                            looseRock.highlight(Color.red);
                            ui.root.wdgmsg("gk", ESC_KEY);
                            gui.error("Loose rock is too close to mine safely.");
                        }
                    }
                }

                if ((stopMiningFiftyCheckBox.a || stopMiningTwentyFiveCheckBox.a) && (gui.map.player().getPoses().contains("pickan") || gui.map.player().getPoses().contains("gfx/borka/choppan"))) {
                    Gob player = gui.map.player();
                    Coord2d minedTile = new Coord2d(player.rc.x + (Math.cos(player.a) * MINED_TILE_OFFSET), player.rc.y + Math.sin(player.a) * MINED_TILE_OFFSET);
                    for (Gob support : supports) {
                        String res = support.getres().name;
                        if (res.equals("gfx/terobjs/ladder") || res.equals("gfx/terobjs/minesupport")) {
                            if (support.rc.dist(minedTile) <= WOODEN_SUPPORT_RANGE) {
                                if (support.getattr(GobHealth.class) != null) {
                                    if (support.getattr(GobHealth.class).hp <= SUPPORT_HP_FIFTY && stopMiningFiftyCheckBox.a) {
                                        ui.root.wdgmsg("gk", ESC_KEY);
                                        gui.error("Support nearby below 50%..");
                                        support.highlight(Color.red);
                                    } else if (support.getattr(GobHealth.class).hp <= SUPPORT_HP_TWENTY_FIVE && stopMiningTwentyFiveCheckBox.a) {
                                        ui.root.wdgmsg("gk", ESC_KEY);
                                        gui.error("Support nearby below 25%..");
                                        support.highlight(Color.red);
                                    }
                                }
                            }
                        } else if (res.equals("gfx/terobjs/column")) {
                            if (support.rc.dist(minedTile) <= COLUMN_SUPPORT_RANGE) {
                                if (support.getattr(GobHealth.class) != null) {
                                    if (support.getattr(GobHealth.class).hp <= SUPPORT_HP_FIFTY && stopMiningFiftyCheckBox.a) {
                                        ui.root.wdgmsg("gk", ESC_KEY);
                                        gui.error("Support nearby below 50%..");
                                        support.highlight(Color.red);
                                    } else if (support.getattr(GobHealth.class).hp <= SUPPORT_HP_TWENTY_FIVE && stopMiningTwentyFiveCheckBox.a) {
                                        ui.root.wdgmsg("gk", ESC_KEY);
                                        gui.error("Support nearby below 25%..");
                                        support.highlight(Color.red);
                                    }
                                }
                            }
                        } else if (res.equals("gfx/terobjs/minebeam")) {
                            if (support.rc.dist(minedTile) <= MINEBEAM_SUPPORT_RANGE) {
                                if (support.getattr(GobHealth.class) != null) {
                                    if (support.getattr(GobHealth.class).hp <= SUPPORT_HP_FIFTY && stopMiningFiftyCheckBox.a) {
                                        ui.root.wdgmsg("gk", ESC_KEY);
                                        gui.error("Support nearby below 50%..");
                                        support.highlight(Color.red);
                                    } else if (support.getattr(GobHealth.class).hp <= SUPPORT_HP_TWENTY_FIVE && stopMiningTwentyFiveCheckBox.a) {
                                        ui.root.wdgmsg("gk", ESC_KEY);
                                        gui.error("Support nearby below 25%..");
                                        support.highlight(Color.red);
                                    }
                                }
                            }
                        }

                    }
                }
            }

            counter++;
            if (counter > 10) {
                counter = 0;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }
    }

    public static boolean isAreaInSupportRange(Coord one, Coord two, GameUI gui) {
        Coord northWestCoord = new Coord(Math.min(one.x, two.x) * TILE, Math.min(one.y, two.y) * TILE);
        Set<Coord2d> tiles = new HashSet<>();
        int tilesCountX = Math.abs(one.x - two.x) + 1;
        int tilesCountY = Math.abs(one.y - two.y) + 1;
        for (int x = 0; x < tilesCountX; x++) {
            for (int y = 0; y < tilesCountY; y++) {
                tiles.add(new Coord2d(northWestCoord.x + (x * TILE) + HALF_TILE, northWestCoord.y + (y * TILE) + HALF_TILE));
            }
        }
        ArrayList<Gob> supportsStatic = AUtils.getAllSupports(gui);
        for (Coord2d tile : tiles) {
            int inRange = 0;
            for (Gob support : supportsStatic) {
                String res = support.getres().name;
                if (res.equals("gfx/terobjs/ladder") || res.equals("gfx/terobjs/minesupport")) {
                    if (support.rc.dist(tile) <= WOODEN_SUPPORT_RANGE) {
                        inRange++;
                    }
                } else if (res.equals("gfx/terobjs/column")) {
                    if (support.rc.dist(tile) <= COLUMN_SUPPORT_RANGE) {
                        inRange++;
                    }
                } else if (res.equals("gfx/terobjs/minebeam")) {
                    if (support.rc.dist(tile) <= MINEBEAM_SUPPORT_RANGE) {
                        inRange++;
                    }
                }
            }
            if (inRange == 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void reqdestroy() {
        Utils.setprefc("wndc-miningSafetyAssistantWindow", this.c);
        super.reqdestroy();
    }
}
