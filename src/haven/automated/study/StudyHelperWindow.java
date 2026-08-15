package haven.automated.study;

import haven.Coord;
import haven.GItem;
import haven.GOut;
import haven.GameUI;
import haven.Inventory;
import haven.ItemInfo;
import haven.Loading;
import haven.OptWnd;
import haven.Tex;
import haven.TexI;
import haven.Text;
import haven.UI;
import haven.Utils;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.resutil.Curiosity;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the Study Plan for whatever containers are open: which curiosities to take, in what order,
 * and how they pack into the Study Grid.
 *
 * The window owns no arithmetic. It scans, hands plain numbers to {@link StudyPlanner}, and draws
 * what comes back — so a wrong plan is a planner bug and a wrong-looking plan is a bug in here, and
 * the two never have to be untangled from each other.
 *
 * It also owns its own visibility. GameUI creates it once and never thinks about it again: every
 * tick this asks whether the toggle is on and whether a container is open, and shows or hides
 * itself accordingly. Closing it by the X turns the toggle off, because a window that reappears one
 * tick after you close it is a window that ignores you.
 */
public class StudyHelperWindow extends Window {
    /** How often the containers are rescanned. Fast enough to feel live, slow enough not to matter. */
    private static final double REFRESH_INTERVAL = 0.4;

    private static final Color HEADER_COLOR = new Color(218, 163, 0);
    private static final Color SELECTED_COLOR = new Color(235, 235, 235);
    private static final Color DIMMED_COLOR = new Color(120, 120, 120);
    private static final Color LP_COLOR = new Color(192, 192, 255);
    private static final Color RATE_COLOR = new Color(192, 255, 255);
    private static final Color WEIGHT_COLOR = new Color(255, 192, 255);
    private static final Color CUT_COLOR = new Color(180, 60, 60);

    /* Column left edges and widths, in unscaled units. Numeric columns are right-aligned inside
     * their width; the eye compares digits by their last one, not their first. */
    private static final int[] COL_X = {0, 164, 198, 254, 316, 372, 410};
    private static final int[] COL_W = {160, 30, 52, 58, 52, 34, 44};
    private static final int TABLE_W = COL_X[6] + COL_W[6];
    private static final String[] COL_TITLE = {"Curiosity", "Own", "LP", "Time", "LP/h", "MW", "ΣMW"};

    private static final int ROW_H = 15;
    private static final int GRID_CELL = 18;
    /** Ceiling on dimmed rows, so a wall of open chests can't grow the window past the screen. */
    private static final int MAX_ROWS = 24;

    private final GameUI gui;
    private final Body body;
    private double sinceRefresh = REFRESH_INTERVAL;
    /**
     * What this window has asked to be, which is not the same as {@link #visible} — Window.hide()
     * starts an animation and leaves visible true until it finishes. Driving the calls off our own
     * intent keeps a toggle flipped twice in quick succession from settling the wrong way.
     */
    private boolean shown = false;
    private Coord savedPos = null;

    public StudyHelperWindow(GameUI gui) {
        super(UI.scale(TABLE_W, 120), "LP Helper");
        this.gui = gui;
        this.body = add(new Body(), Coord.z);
        this.savedPos = Utils.getprefc("wndc-studyHelper", null);
        pack();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        boolean wanted = OptWnd.studyHelperCheckBox != null && OptWnd.studyHelperCheckBox.a
                && anyContainerOpen();
        if (!wanted) {
            if (shown) {
                shown = false;
                savePos();
                hide();
            }
            return;
        }
        if (!shown) {
            shown = true;
            show();
        }
        sinceRefresh += dt;
        if (sinceRefresh < REFRESH_INTERVAL)
            return;
        sinceRefresh = 0;
        /* Nothing this window does is worth taking the client down for. Reading item info touches
         * resource loading, which throws more than Loading - a broken or half-arrived resource
         * raises a plain RuntimeException, and before this guard existed that escaped straight
         * through tick() and killed the UI. The plan simply keeps last tick's numbers and tries
         * again in REFRESH_INTERVAL. Logged once, because a guard that hides a repeatable bug
         * silently is how the bug stays. */
        try {
            Coord grid = studyGrid();
            body.update(StudyPlanner.plan(scan(), attentionBudget(), grid.x, grid.y));
        } catch (RuntimeException e) {
            if (!loggedFailure) {
                loggedFailure = true;
                haven.automated.nbots.core.NLog.crash("LP Helper refresh", e);
                if (gui != null)
                    gui.error("LP Helper hit an error and will keep the last plan "
                            + "(details in logs/crash.log).");
            }
        }
    }

    /** One report per session — a refresh that fails once usually fails every 0.4s after that. */
    private boolean loggedFailure = false;

    /**
     * Position is written when the window goes away rather than while it is being dragged: the pref
     * store is a file, and a drag would otherwise write to it every frame.
     */
    private void savePos() {
        if (c == null || c.equals(savedPos))
            return;
        savedPos = c;
        Utils.setprefc("wndc-studyHelper", c);
    }

    @Override
    public void destroy() {
        savePos();
        super.destroy();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (sender == this && msg.equals("close")) {
            savePos();
            /* Closing the window is the player saying they don't want it, not a request to have it
             * back on the next container. The toggle is the state; the window only reflects it. */
            if (OptWnd.studyHelperCheckBox != null)
                OptWnd.studyHelperCheckBox.set(false);
            shown = false;
            hide();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }

    // -- reading the world ---------------------------------------------------------------------

    /** Any window that isn't one of the player's own inventories and does hold an inventory. */
    private boolean anyContainerOpen() {
        for (Window w : gui.getAllWindows()) {
            if (w == this || w.cap == null || !w.visible)
                continue;
            if (Inventory.PLAYER_INVENTORY_NAMES.contains(w.cap))
                continue;
            for (Widget child : w.children()) {
                if (Inventory.fromWidget(child) != null)
                    return true;
            }
        }
        return false;
    }

    /**
     * Every curiosity in every open container, one entry per physical copy. Items still loading are
     * skipped for this pass rather than guessed at — the next refresh is 0.4s away.
     */
    private List<StudyPlanner.Curio> scan() {
        List<StudyPlanner.Curio> found = new ArrayList<>();
        for (Window w : gui.getAllWindows()) {
            if (w == this || w.cap == null || !w.visible)
                continue;
            if (Inventory.PLAYER_INVENTORY_NAMES.contains(w.cap))
                continue;
            for (Widget child : w.children()) {
                Inventory inv = Inventory.fromWidget(child);
                if (inv == null)
                    continue;
                for (WItem wi : inv.getAllItems())
                    collect(wi, found);
            }
        }
        return found;
    }

    private void collect(WItem wi, List<StudyPlanner.Curio> into) {
        try {
            GItem item = wi.item;
            Curiosity ci = ItemInfo.find(Curiosity.class, item.info());
            if (ci == null)
                return;
            String name = item.getname();
            if (name == null || name.isEmpty())
                return;
            /* WItem.sz is already snapped to whole slots by WItem.tick, and both it and sqsz are in
             * scaled pixels, so this is the Footprint the game itself uses. A WItem whose sprite
             * hasn't loaded is still one slot wide, which is the documented fallback. */
            int slotsWide = Math.max(1, wi.sz.x / Inventory.sqsz.x);
            int slotsHigh = Math.max(1, wi.sz.y / Inventory.sqsz.y);
            /* Curiosity.time is server time; lph already carries the speed multiplier. Convert the
             * one that doesn't so every number in the window is in the player's hours. */
            int realTime = (int) (ci.time / GameUI.gameTimeSpeedMultiplier);
            into.add(new StudyPlanner.Curio(name, ci.exp, ci.mw, realTime, ci.lph, slotsWide, slotsHigh));
        } catch (Loading l) {
            /* Still arriving from the server. It will be here next refresh. */
        } catch (RuntimeException e) {
            /* One bad item must not cost us the other thirty in the container. Resource loading
             * raises LoadException and friends, none of which are Loading. */
        }
    }

    /** The Attention pool: full Intelligence, ignoring whatever is already being studied. */
    private int attentionBudget() {
        try {
            return ui.sess.glob.getcattr("int").comp;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Cached because finding it means walking the whole widget tree, and it moves exactly once per
     * session — when the character sheet first arrives. Dropped if it is ever detached.
     */
    private Inventory studyCache = null;

    private Coord studyGrid() {
        if (studyCache == null || studyCache.parent == null)
            studyCache = findStudy(gui);
        Inventory study = studyCache;
        if (study != null && study.isz != null && study.isz.x > 0 && study.isz.y > 0)
            return study.isz;
        return new Coord(StudyPlanner.FALLBACK_GRID_SIZE, StudyPlanner.FALLBACK_GRID_SIZE);
    }

    /**
     * The Study Grid lives inside the character sheet, which may never have been opened this
     * session — hence the fallback rather than a hard requirement.
     */
    private static Inventory findStudy(Widget from) {
        for (Widget w = from.child; w != null; w = w.next) {
            if (w instanceof haven.StudyInventory)
                return (Inventory) w;
            Inventory found = findStudy(w);
            if (found != null)
                return found;
        }
        return null;
    }

    // -- drawing -------------------------------------------------------------------------------

    /**
     * The table is composed into one texture per plan change rather than drawn cell by cell every
     * frame; the mini-grid is drawn live because it is a handful of rectangles.
     */
    private class Body extends Widget {
        private StudyPlanner.Plan plan;
        private Tex table;
        private String signature = null;

        Body() {
            super(UI.scale(TABLE_W, 40));
        }

        /**
         * Rebuilds the table only once the contents have stopped moving.
         *
         * Sorting a container takes every item to the cursor and puts it back, one at a time. Each
         * of those two steps changes the item set, so a redraw-on-every-change rebuilds the whole
         * table texture twice per item moved — sixty rebuilds to sort a thirty-slot cupboard, each
         * one a fresh BufferedImage and a fresh GL texture, while the sorter is already saturating
         * things. Requiring the same plan twice in a row rides out the churn: during a sort nothing
         * is redrawn, and the finished layout is drawn once when it settles.
         */
        void update(StudyPlanner.Plan plan) {
            this.plan = plan;
            String sig = signature(plan);
            if (sig.equals(signature))
                return;
            if (!sig.equals(candidate)) {
                candidate = sig;
                return;
            }
            signature = sig;
            render(plan);
        }

        /** The signature seen last refresh, still waiting to be confirmed by a second one. */
        private String candidate = null;

        /**
         * Several open containers can turn up more kinds of curiosity than fit on a screen. Every
         * selected row is always shown — that is the part you act on — and the dimmed tail is cut
         * to whatever is left of the budget, with a line saying how many were dropped. A list that
         * silently stops reads as a complete list.
         */
        private int rowsToShow(StudyPlanner.Plan p) {
            if (p.groups.size() <= MAX_ROWS)
                return p.groups.size();
            return Math.max(p.selectedRows(), MAX_ROWS);
        }

        private String signature(StudyPlanner.Plan p) {
            StringBuilder sb = new StringBuilder();
            sb.append(p.budget).append('/').append(p.usedWeight).append('/')
                    .append(p.gridWidth).append('x').append(p.gridHeight).append(';');
            /* Every column that is drawn has to appear here, or the table keeps a stale texture.
             * Row order matters too and is captured by iterating in order — the partition that
             * puts taken rows on top changes this string, which is what forces the redraw. */
            for (StudyPlanner.Group g : p.groups) {
                sb.append(g.curio.name).append(':').append(g.available).append(':')
                        .append(g.selected).append(':').append(g.curio.lp).append(':')
                        .append(g.curio.studyTime).append(':').append(g.curio.lpPerHour).append(':')
                        .append(g.curio.mentalWeight).append(':').append(g.cumulativeWeight)
                        .append(';');
            }
            return sb.toString();
        }

        private void render(StudyPlanner.Plan p) {
            int rowh = UI.scale(ROW_H);
            int headerRows = 2;
            int shownRows = rowsToShow(p);
            boolean truncated = shownRows < p.groups.size();
            int tableH = rowh * (headerRows + 1 + Math.max(1, shownRows) + (truncated ? 1 : 0))
                    + UI.scale(6);
            BufferedImage img = new BufferedImage(UI.scale(TABLE_W), tableH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();

            int y = 0;
            /* Used, left, and the pool it came out of. The planner never admits a copy that would
             * exceed the budget, so "used" is a number that cannot go over - no warning state. */
            drawText(g2, String.format("Attention: %s used  ·  %s free  ·  %s Int",
                    Utils.thformat(p.usedWeight), Utils.thformat(p.budget - p.usedWeight),
                    Utils.thformat(p.budget)), 0, y, WEIGHT_COLOR);
            drawText(g2, String.format("Grid %dx%d", p.gridWidth, p.gridHeight),
                    UI.scale(TABLE_W) - UI.scale(64), y, HEADER_COLOR);
            y += rowh;
            drawText(g2, String.format("Plan: %s LP  ·  %s LP/h  ·  %d item%s",
                    Utils.thformat(p.totalLp), Utils.thformat(p.totalLpPerHour),
                    p.placements.size(), p.placements.size() == 1 ? "" : "s"), 0, y, LP_COLOR);
            y += rowh;

            for (int i = 0; i < COL_TITLE.length; i++)
                drawCell(g2, COL_TITLE[i], i, y, HEADER_COLOR, i > 0);
            y += rowh;

            if (p.groups.isEmpty()) {
                drawText(g2, "No curiosities in the open containers.", 0, y, DIMMED_COLOR);
            } else {
                boolean cutDrawn = false;
                for (int i = 0; i < shownRows; i++) {
                    StudyPlanner.Group g = p.groups.get(i);
                    boolean picked = g.selected;
                    if (!picked && !cutDrawn && p.selectedRows() > 0) {
                        g2.setColor(CUT_COLOR);
                        g2.drawLine(0, y + UI.scale(1), UI.scale(TABLE_W), y + UI.scale(1));
                        cutDrawn = true;
                    }
                    Color base = picked ? SELECTED_COLOR : DIMMED_COLOR;
                    drawCell(g2, g.curio.name, 0, y, base, false);
                    /* How many you own, never "1/7" - only one of a kind can be studied at a time,
                     * so a ratio here read as a fraction of a curiosity. Whether to take one is
                     * said by the highlight and the cut line, not by this number. */
                    drawCell(g2, Integer.toString(g.available), 1, y, base, true);
                    drawCell(g2, Utils.thformat(g.curio.lp), 2, y, picked ? LP_COLOR : DIMMED_COLOR, true);
                    drawCell(g2, timefmt(g.curio.studyTime), 3, y, base, true);
                    drawCell(g2, Utils.thformat(g.curio.lpPerHour), 4, y, picked ? RATE_COLOR : DIMMED_COLOR, true);
                    drawCell(g2, Integer.toString(g.curio.mentalWeight), 5, y,
                            picked ? WEIGHT_COLOR : DIMMED_COLOR, true);
                    drawCell(g2, g.cumulativeWeight < 0 ? "-" : Utils.thformat(g.cumulativeWeight),
                            6, y, base, true);
                    y += rowh;
                }
                if (truncated) {
                    drawText(g2, String.format("… and %d more, all below the cut line",
                            p.groups.size() - shownRows), 0, y, DIMMED_COLOR);
                }
            }
            g2.dispose();

            Tex old = table;
            table = new TexI(img);
            if (old != null) {
                try {
                    old.dispose();
                } catch (RuntimeException e) {
                }
            }

            int gridH = p.gridHeight * UI.scale(GRID_CELL) + UI.scale(8);
            resize(UI.scale(TABLE_W), tableH + gridH);
            if (parent != null)
                parent.pack();
        }

        private void drawText(Graphics2D g2, String s, int x, int y, Color c) {
            g2.drawImage(Text.render(s, c).img, x, y, null);
        }

        /** Column 0 is left-aligned prose; every other column is a number and hugs its right edge. */
        private void drawCell(Graphics2D g2, String s, int col, int y, Color c, boolean rightAlign) {
            BufferedImage t = Text.render(clip(s, UI.scale(COL_W[col])), c).img;
            int x = UI.scale(COL_X[col]);
            if (rightAlign)
                x += UI.scale(COL_W[col]) - t.getWidth();
            g2.drawImage(t, x, y, null);
        }

        private String clip(String s, int maxWidth) {
            if (Text.render(s, Color.WHITE).img.getWidth() <= maxWidth)
                return s;
            String cut = s;
            while (cut.length() > 1
                    && Text.render(cut + "…", Color.WHITE).img.getWidth() > maxWidth)
                cut = cut.substring(0, cut.length() - 1);
            return cut + "…";
        }

        @Override
        public void draw(GOut g) {
            super.draw(g);
            if (table == null || plan == null)
                return;
            g.image(table, Coord.z);
            drawGrid(g, plan, new Coord(0, table.sz().y + UI.scale(4)));
        }

        /** The packed Study Grid, each cell tinted with the colour of whatever occupies it. */
        private void drawGrid(GOut g, StudyPlanner.Plan p, Coord at) {
            int cell = UI.scale(GRID_CELL);
            for (int gy = 0; gy < p.gridHeight; gy++) {
                for (int gx = 0; gx < p.gridWidth; gx++) {
                    Coord c = at.add(gx * cell, gy * cell);
                    g.chcolor(60, 60, 60, 255);
                    g.frect(c, new Coord(cell - 1, cell - 1));
                }
            }
            for (StudyPlanner.Placement pl : p.placements) {
                Coord c = at.add(pl.x * cell, pl.y * cell);
                Coord sz = new Coord(pl.w * cell - 1, pl.h * cell - 1);
                g.chcolor(groupColor(pl.group));
                g.frect(c, sz);
                g.chcolor(20, 20, 20, 255);
                g.rect(c, sz);
            }
            g.chcolor();
        }
    }

    /**
     * Distinct colours without a palette to maintain: stepping the hue by the golden ratio keeps
     * consecutive groups far apart however many there turn out to be.
     */
    private static Color groupColor(int index) {
        float hue = (float) ((index * 0.618033988749895) % 1.0);
        Color c = Color.getHSBColor(hue, 0.55f, 0.85f);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 200);
    }

    /** Real-time seconds as the player reads them, coarsest unit first. */
    static String timefmt(int seconds) {
        if (seconds <= 0)
            return "-";
        int days = seconds / 86400;
        int hours = (seconds % 86400) / 3600;
        int minutes = (seconds % 3600) / 60;
        if (days > 0)
            return hours > 0 ? String.format("%dd %dh", days, hours) : String.format("%dd", days);
        if (hours > 0)
            return minutes > 0 ? String.format("%dh %dm", hours, minutes) : String.format("%dh", hours);
        if (minutes > 0)
            return String.format("%dm", minutes);
        return String.format("%ds", seconds);
    }
}
