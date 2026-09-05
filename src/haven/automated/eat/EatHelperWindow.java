package haven.automated.eat;

import haven.BAttrWnd;
import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.Glob;
import haven.Label;
import haven.OptWnd;
import haven.Text;
import haven.TextEntry;
import haven.Tex;
import haven.TexI;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enter target attribute points, hit Plan, and see which foods from the cookbook catalog get you
 * there — the eating equivalent of the LP Helper ({@code StudyHelperWindow}), advise-only.
 *
 * Like the LP Helper, this window owns no arithmetic beyond reading live state into plain numbers.
 * The plan itself is {@link EatPlanner#plan}; a wrong answer is a planner bug, a wrong-looking one
 * is a bug in here, and the two stay separable.
 *
 * <h2>Two answers, not one</h2>
 *
 * The plan says what is optimal. Underneath it, {@link EatPlanner.Plan#candidates} says what would
 * <i>work</i> — every catalog dish that advances the goal at all, ranked by goal points per unit
 * hunger, with the bar overflow each one wastes shown next to it. That second list exists because
 * the first answer is frequently unusable: the best dish in the catalog is often not the one in
 * your cellar right now, and a planner that only ever names a single winner makes the player guess
 * at the substitution. It is also a guard against the failure this window used to have, where a
 * static score meant the plan could physically only ever contain one dish.
 *
 * <h2>Staying current</h2>
 *
 * Once a goal has been planned, the window keeps that answer live: it re-plans by itself whenever
 * satiation, the FEP cap or the hunger multiplier move enough to change it. That work happens on
 * {@code FoodService.scheduler}, not here — {@link EatPlanner} imports nothing from {@code haven}
 * exactly so it can, and it must, since a nine-stat goal against the full catalog measures 249 ms.
 * The previous result stays on screen until the new one lands, so the table never blanks. Auto-
 * update never invents a goal; it only keeps one the player already asked for from going stale.
 *
 * <h2>Calibration, and where it is still thin</h2>
 *
 * The variety reduction is no longer calibrated at all, because it no longer needs to be: it is a
 * closed form of the hunger multiplier and the top stat, settled against the server's pooled log
 * to float precision. See {@link EatPlanner} for the formula and the residual. What the server
 * still does with the uploaded logs is check that form rather than supply it — a game patch that
 * changed the constant would show up as a residual there instead of silently biasing every plan.
 *
 * Satiation never went through that pipeline. It is joined directly, live
 * {@code Constipations} entry to catalog dish, on the entry key both sides derive the same way —
 * see {@link #readLiveSatiation}. What remains uncertain is coverage, not correctness: a dish
 * nobody has hovered since the client started uploading its keys has none recorded, and the plan
 * reports how many of those it met rather than quietly treating them as unsatiated. Everything
 * else the plan stands on that it cannot verify comes back as {@link EatPlanner.Plan#warnings}
 * and is printed under the tables.
 */
public class EatHelperWindow extends Window {
    private static final String[] STATS = {"STR", "AGI", "INT", "CON", "PER", "CHA", "DEX", "WILL", "PSY"};
    private static final Map<String, String> STAT_TO_GLOB = new LinkedHashMap<>();
    static {
        STAT_TO_GLOB.put("STR", "str");
        STAT_TO_GLOB.put("AGI", "agi");
        STAT_TO_GLOB.put("INT", "int");
        STAT_TO_GLOB.put("CON", "con");
        STAT_TO_GLOB.put("PER", "prc");
        STAT_TO_GLOB.put("CHA", "csm");
        STAT_TO_GLOB.put("DEX", "dex");
        STAT_TO_GLOB.put("WILL", "wil");
        STAT_TO_GLOB.put("PSY", "psy");
    }

    /**
     * The character's highest base attribute — the FEP bar's base capacity, and the {@code topStat}
     * term of the variety formula. Read from {@code CAttr.base} exactly as {@code EatObserver}
     * stamps it, so the number the planner uses is the number the measurement was made against.
     */
    private int topStat() {
        int best = 0;
        Glob glob = ui.sess.glob;
        for (String g : STAT_TO_GLOB.values()) {
            Glob.CAttr a = glob.getcattr(g);
            if (a != null)
                best = Math.max(best, a.base);
        }
        return best;
    }

    /**
     * The variety reduction the next distinct food of this bar will buy, given the live hunger
     * multiplier and top stat. Delegates to {@link EatPlanner#varietyStep} rather than keeping a
     * second copy of the constant: this is the status line's number and the planner's number, and
     * they must be the same number.
     */
    private static double nextVarietyStep(double gmod, double topStat, int spentSoFar) {
        return EatPlanner.varietyStep(gmod, topStat, spentSoFar + 1);
    }

    private final GameUI gui;
    private final Map<String, TextEntry> goalFields = new LinkedHashMap<>();
    private final Body body;
    private final StatusBox status;
    private CheckBox tableOverride;
    private CheckBox autoUpdate;

    private EatPlanner.QualityMode qualityMode = EatPlanner.QualityMode.Q10;
    private double qualityPct = 1.0;
    private Button qualityButton;

    private boolean shown = false;
    private Coord savedPos = null;

    public EatHelperWindow(GameUI gui) {
        super(Coord.z, "Eating Helper");
        this.gui = gui;
        this.savedPos = Utils.getprefc("wndc-eatHelper", null);

        int col = UI.scale(56);
        int row = UI.scale(22);
        int x0 = 0, y = 0;
        for (int i = 0; i < STATS.length; i++) {
            int cx = x0 + (i % 3) * (col + UI.scale(6));
            int cy = y + (i / 3) * row;
            add(new Label(STATS[i]), cx, cy + UI.scale(4));
            TextEntry te = new TextEntry(UI.scale(30), "0");
            add(te, cx + UI.scale(26), cy);
            goalFields.put(STATS[i], te);
        }
        y += row * 3 + UI.scale(8);

        qualityButton = new Button(UI.scale(110), qualityLabel()) {
            @Override
            public void click() {
                cycleQuality();
                change(qualityLabel());
            }
        };
        add(qualityButton, x0, y);

        tableOverride = add(new CheckBox("Assume at a table"), x0 + UI.scale(120), y + UI.scale(2));
        tableOverride.tooltip = Text.render("Plan against the last feasting table you opened."
                + " Open one once so its real bonus can be read.").tex();

        y += row + UI.scale(4);
        add(new Button(UI.scale(300), "Plan") {
            @Override
            public void click() {
                runPlan();
            }
        }, x0, y);
        autoUpdate = add(new CheckBox("Auto-update") {
            @Override
            public void set(boolean val) {
                super.set(val);
                Utils.setprefb("eatHelperAutoUpdate", val);
            }
        }, x0 + UI.scale(312), y + UI.scale(4));
        autoUpdate.a = Utils.getprefb("eatHelperAutoUpdate", true);
        autoUpdate.tooltip = Text.render("Re-plan by itself as your satiation, hunger and FEP cap"
                + " change. Turn off to hold the plan you are reading still.").tex();
        y += row + UI.scale(6);

        status = add(new StatusBox(), Coord.of(x0, y));
        y += status.sz.y + UI.scale(6);

        body = add(new Body(), Coord.of(x0, y));
        pack();
        refreshStatus();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        boolean wanted = OptWnd.eatHelperCheckBox != null && OptWnd.eatHelperCheckBox.a;
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
            CookbookClient.refreshIfStale();
            CalibrationClient.refreshIfStale();
        }
        refreshStatus();
        pumpPlanning();
    }

    private void savePos() {
        if (c == null || c.equals(savedPos))
            return;
        savedPos = c;
        Utils.setprefc("wndc-eatHelper", c);
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
            if (OptWnd.eatHelperCheckBox != null)
                OptWnd.eatHelperCheckBox.set(false);
            shown = false;
            hide();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }

    private String qualityLabel() {
        if (qualityMode == EatPlanner.QualityMode.Q10)
            return "Quality: q10";
        return String.format("Quality: %d%% of max", Math.round(qualityPct * 100));
    }

    private void cycleQuality() {
        if (qualityMode == EatPlanner.QualityMode.Q10) {
            qualityMode = EatPlanner.QualityMode.PERCENT_OF_MAX;
            qualityPct = 0.25;
        } else if (qualityPct < 0.5) {
            qualityPct = 0.5;
        } else if (qualityPct < 0.75) {
            qualityPct = 0.75;
        } else if (qualityPct < 1.0) {
            qualityPct = 1.0;
        } else {
            qualityMode = EatPlanner.QualityMode.Q10;
        }
    }

    private void refreshStatus() {
        List<String> lines = new ArrayList<>();
        try {
            double cap = gui.chrwdg.battr.feps.cap;
            double gmod = gui.chrwdg.battr.glut.gmod;
            int topStat = topStat();
            // How much cap the next new food costs is the number a player is actually deciding
            // against, so show that rather than the coefficient it is derived from.
            double spent = Math.max(0, topStat - cap);
            double step = nextVarietyStep(gmod, topStat, spent > 0 ? 1 : 0);
            lines.add(String.format("Cap %.1f/%d  ·  FEP mult %.2fx  ·  next new food -%.2f cap",
                    cap, topStat, gmod, step));
        } catch (Exception e) {
            lines.add("Character sheet not loaded yet.");
        }

        List<EatPlanner.Dish> catalog = CookbookClient.cached();
        String err = CookbookClient.lastError();
        StringBuilder second = new StringBuilder();
        if (catalog != null)
            second.append(catalog.size()).append(" dishes");
        else if (err != null)
            second.append("cookbook: ").append(err);
        else
            second.append("cookbook: loading…");
        CalibrationClient.Calibration cal = CalibrationClient.cached();
        if (cal != null)
            second.append(String.format("  ·  %d satiation categories known", cal.satiationCategoryMap.size()));
        lines.add(second.toString());

        // Silent while the formula holds, loud when it stops. A drifting residual means the game
        // changed under us and every number above this line is wrong by an unknown amount - that
        // is worth a line of screen space on the rare occasions it happens.
        CalibrationClient.VarietyResidual res = (cal != null) ? cal.varietyResidual : null;
        if (res != null && !res.holds()) {
            lines.add(String.format(
                    "Variety formula no longer matches the server log: %d/%d events, constant %.4f (expected %.2f).",
                    res.matched, res.samples, res.constant, EatPlanner.VARIETY_CONST));
        }

        // The table line only appears when it has something to say, so the normal case stays two
        // lines. A ticked override with nothing ever observed is the one case worth shouting about:
        // it is the state in which this checkbox genuinely cannot do anything.
        ModifierContext.TableValues seen = ModifierContext.lastSeenTable();
        ModifierContext mods = ModifierContext.resolve(ui);
        if (mods != null && mods.atTable) {
            lines.add(String.format("At a table: FEP ×%.2f, hunger ×%.2f (live)",
                    mods.tableFoodEventBonus, mods.tableHungerMod));
        } else if (tableOverride.a && seen != null) {
            lines.add(String.format("Assuming your last table: FEP ×%.2f, hunger ×%.2f",
                    seen.foodEventBonus, seen.hungerMod));
        } else if (tableOverride.a) {
            lines.add("No table seen yet — open a feasting table once to read its real bonus.");
        }

        status.set(lines);
    }

    private void runPlan() {
        List<EatPlanner.Dish> catalog = CookbookClient.cached();
        if (catalog == null) {
            gui.error("Cookbook not loaded yet - try again in a moment.");
            CookbookClient.refreshIfStale();
            return;
        }

        Map<String, Double> targets = new LinkedHashMap<>();
        for (Map.Entry<String, TextEntry> e : goalFields.entrySet()) {
            String raw = e.getValue().text().trim();
            if (raw.isEmpty())
                continue;
            Double v = parseGoal(raw);
            if (v == null) {
                // Silently treating "1O" as zero drops a goal stat the player thinks they set,
                // and the plan that comes back looks merely wrong rather than misread.
                gui.error(String.format("%s: \"%s\" isn't a number.", e.getKey(), raw));
                return;
            }
            if (v > 0)
                targets.put(e.getKey(), v);
        }
        if (targets.isEmpty()) {
            gui.error("Enter at least one target attribute point.");
            return;
        }

        EatPlanner.CharState state = readCharState();
        if (state == null) {
            gui.error("Character sheet not loaded yet.");
            return;
        }

        activeTargets = targets;
        dispatch(catalog, state, targets);
    }

    // -- planning off the UI thread -------------------------------------------------------------

    /**
     * The goal the displayed plan was built for, kept so an auto-refresh can rebuild it without
     * re-reading (and re-validating) the entry fields. Null until the player presses Plan once -
     * auto-refresh deliberately never invents a goal, it only keeps an existing answer current.
     */
    private Map<String, Double> activeTargets = null;

    /** Result waiting to be drawn, handed over from the scheduler thread. */
    private volatile EatPlanner.Plan pendingPlan = null;
    private volatile Map<String, Double> pendingTargets = null;

    /** One plan in flight at a time; a second would only race the first to the same widget. */
    private final java.util.concurrent.atomic.AtomicBoolean planning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Inputs the displayed plan was built from, for deciding whether anything has moved. */
    private String plannedFingerprint = null;
    private double plannedGmod = Double.NaN;
    private long lastAutoPlan = 0;

    /**
     * How often an auto-refresh may fire. The server pushes hunger roughly once a second, so
     * without a floor here a drifting gmod could queue a replan on almost every push.
     */
    private static final long AUTO_PLAN_INTERVAL_MS = 2000;

    /**
     * How far gmod must move before it alone justifies a replan. It drifts continuously as hunger
     * decays, and a plan is not meaningfully different for a 0.001 change; satiation and cap, which
     * move in discrete jumps, trigger on any change at all.
     */
    private static final double GMOD_REPLAN_EPSILON = 0.02;

    /**
     * Runs the plan on {@link haven.automated.cookbook.FoodService#scheduler} rather than here.
     * {@link EatPlanner} imports nothing from {@code haven} precisely so it can run off the UI
     * thread, and it needs to: a nine-stat goal against the full catalog measured 249 ms, which is
     * a visible freeze if it happens on every satiation change. The snapshot it works from is taken
     * on the UI thread by the caller, so the background side never touches a live widget.
     */
    private void dispatch(List<EatPlanner.Dish> catalog, EatPlanner.CharState state,
                          Map<String, Double> targets) {
        if (!planning.compareAndSet(false, true))
            return;
        plannedFingerprint = fingerprint(state, catalog);
        plannedGmod = state.hungerMod;
        lastAutoPlan = System.currentTimeMillis();
        final Map<String, Double> goal = new LinkedHashMap<>(targets);
        // Snapshot the quality settings too: they are mutated by a UI-thread button click, and the
        // background run must plan against one consistent pair, not whatever they are when it gets
        // around to reading them.
        final EatPlanner.QualityMode mode = qualityMode;
        final double pct = qualityPct;
        haven.automated.cookbook.FoodService.scheduler.execute(() -> {
            try {
                EatPlanner.Plan plan = EatPlanner.plan(catalog, state, new EatPlanner.Goal(goal),
                        mode, pct, 500);
                pendingTargets = goal;
                pendingPlan = plan;
            } catch (Exception e) {
                // A planner fault must not wedge the window - the previous result stays up and the
                // next input change will try again.
            } finally {
                planning.set(false);
            }
        });
    }

    /**
     * Everything except gmod that would change the answer, as one comparable string. gmod is left
     * out and compared with an epsilon instead - see {@link #GMOD_REPLAN_EPSILON}.
     */
    private String fingerprint(EatPlanner.CharState state, List<EatPlanner.Dish> catalog) {
        StringBuilder sb = new StringBuilder();
        sb.append(state.startCap).append('|')
          .append(state.accountMult).append('|')
          .append(state.tableFoodEventBonus).append('|')
          .append(state.tableHungerMod).append('|')
          // No variety term: the reduction is a function of gmod and the top stat, and both are
          // already watched - gmod by the epsilon below, the top stat by the attrs loop.
          .append(qualityMode).append('|')
          .append(qualityPct).append('|')
          .append(catalog.size()).append('|');
        for (Map.Entry<String, Integer> e : state.attrs.entrySet())
            sb.append(e.getKey()).append('=').append(e.getValue()).append(',');
        sb.append('|');
        for (Map.Entry<String, Double> e : state.satiationPenalty.entrySet())
            sb.append(e.getKey()).append('=').append(String.format("%.4f", e.getValue())).append(',');
        return sb.toString();
    }

    /** Draws a finished plan, and re-runs one when the world has moved under it. */
    private void pumpPlanning() {
        EatPlanner.Plan ready = pendingPlan;
        if (ready != null) {
            Map<String, Double> t = pendingTargets;
            pendingPlan = null;
            pendingTargets = null;
            if (t != null)
                body.update(ready, t);
        }

        if (!autoUpdate.a || activeTargets == null || planning.get())
            return;
        if (System.currentTimeMillis() - lastAutoPlan < AUTO_PLAN_INTERVAL_MS)
            return;
        List<EatPlanner.Dish> catalog = CookbookClient.cached();
        if (catalog == null)
            return;
        EatPlanner.CharState state = readCharState();
        if (state == null)
            return;
        boolean moved = !fingerprint(state, catalog).equals(plannedFingerprint)
                || Double.isNaN(plannedGmod)
                || Math.abs(state.hungerMod - plannedGmod) > GMOD_REPLAN_EPSILON;
        if (moved)
            dispatch(catalog, state, activeTargets);
    }

    /** Parsed goal, or null when the text is present but not a number. */
    private static Double parseGoal(String s) {
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Reads live state into the plain-data snapshot {@link EatPlanner} takes. Null if unavailable. */
    private EatPlanner.CharState readCharState() {
        try {
            Glob glob = ui.sess.glob;
            Map<String, Integer> attrs = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : STAT_TO_GLOB.entrySet())
                attrs.put(e.getKey(), glob.getcattr(e.getValue()).base);

            double cap = gui.chrwdg.battr.feps.cap;
            double gmod = gui.chrwdg.battr.glut.gmod;
            double glut = gui.chrwdg.battr.glut.glut;

            Map<String, Double> satiation = readLiveSatiation();

            ModifierContext mods = ModifierContext.resolve(ui);
            double accountMult = mods != null ? mods.accountMult : 1.0;

            // Live table values win outright. The override falls back to the last table actually
            // observed this session, because those numbers depend on the table's quality and the
            // number of feasters and cannot be defaulted - and because the alternative, which this
            // window shipped with, was a checkbox that multiplied by 1.0 and changed nothing.
            double tableFoodEventBonus = 1.0;
            double tableHungerMod = 1.0;
            if (mods != null && mods.atTable) {
                tableFoodEventBonus = mods.tableFoodEventBonus;
                tableHungerMod = mods.tableHungerMod;
            } else if (tableOverride.a) {
                ModifierContext.TableValues seen = ModifierContext.lastSeenTable();
                if (seen != null) {
                    tableFoodEventBonus = seen.foodEventBonus;
                    tableHungerMod = seen.hungerMod;
                }
            }

            return new EatPlanner.CharState(attrs, gmod, satiation, accountMult,
                    tableFoodEventBonus, tableHungerMod, cap, glut);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Live {@code Constipations} entries keyed by their food-type resource, which is exactly how
     * the catalog now records what each dish drains - so the join is a direct lookup with nothing
     * inferred in between.
     *
     * This used to route through the server's voted resource-to-wiki-category map, which could not
     * be right: {@code gfx/invobjs/meat} alone accounts for 524 of the local logs' satiation
     * readings and is shared by dishes the wiki files under Fish, Game and Meat, so any single
     * category the votes settled on was wrong for most of them. Satiation is per food type, the
     * item's own tooltip states its types, and reading that beats inferring it from eat history.
     *
     * Entries are keyed twice on purpose. {@code EatObserver.resolveSatiationKey} appends the
     * entry's {@code sdt} to disambiguate one resource carrying two live penalties at once (the
     * observed {@code gfx/invobjs/meat} case); the catalog only has the bare resource, since the
     * tooltip's type list carries no {@code sdt}. Publishing both means a dish matches on the bare
     * resource, and the qualified key stays available for when the catalog can carry it too. Where
     * one resource does hold several penalties, the bare key keeps the harshest - planning against
     * the worst of an ambiguous pair understates the plan rather than overselling it.
     */
    private Map<String, Double> readLiveSatiation() {
        Map<String, Double> satiation = new LinkedHashMap<>();
        if (gui.chrwdg == null || gui.chrwdg.battr == null || gui.chrwdg.battr.cons == null)
            return satiation;
        for (BAttrWnd.Constipations.El el : gui.chrwdg.battr.cons.els) {
            try {
                String qualified = EatObserver.resolveSatiationKey(el.t);
                satiation.merge(qualified, el.a, Math::max);
                String bare = el.t.res.get().name;
                satiation.merge(bare, el.a, Math::max);
            } catch (Exception e) {
                // A resource still loading must not cost the rest of the satiation picture.
            }
        }
        return satiation;
    }

    // -- shared drawing -------------------------------------------------------------------------

    private static final int TABLE_W = 430;
    private static final Color HEADER_COLOR = new Color(218, 163, 0);
    private static final Color ROW_COLOR = new Color(210, 210, 210);
    private static final Color DIM_COLOR = new Color(140, 140, 140);
    private static final Color GOAL_COLOR = new Color(192, 255, 192);
    private static final Color WARN_COLOR = new Color(255, 192, 128);

    private static int rowh() {
        return UI.scale(15);
    }

    private static void drawText(Graphics2D g2, String s, int x, int y, Color c) {
        g2.drawImage(Text.render(s, c).img, x, y, null);
    }

    private static void drawCell(Graphics2D g2, String s, int x, int w, int y, Color c, boolean rightAlign) {
        BufferedImage t = Text.render(clip(s, w), c).img;
        int px = x;
        if (rightAlign)
            px += w - t.getWidth();
        g2.drawImage(t, px, y, null);
    }

    private static String clip(String s, int maxWidth) {
        if (Text.render(s, Color.WHITE).img.getWidth() <= maxWidth)
            return s;
        String cut = s;
        while (cut.length() > 1
                && Text.render(cut + "…", Color.WHITE).img.getWidth() > maxWidth)
            cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    /**
     * Greedy word wrap against rendered pixel width. {@code Label}'s own wrapping is only applied
     * by the constructor - its {@code settext} re-renders unwrapped - so anything that changes over
     * time has to wrap itself.
     */
    private static List<String> wrap(String s, int maxWidth) {
        List<String> out = new ArrayList<>();
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String probe = line.length() == 0 ? w : line + " " + w;
            if (line.length() > 0 && Text.render(probe, Color.WHITE).img.getWidth() > maxWidth) {
                out.add(line.toString());
                line = new StringBuilder(w);
            } else {
                line = new StringBuilder(probe);
            }
        }
        if (line.length() > 0)
            out.add(line.toString());
        if (out.isEmpty())
            out.add("");
        return out;
    }

    // -- status ---------------------------------------------------------------------------------

    /**
     * The live readout above the tables: wrapped to the window width, and re-rendered only when
     * the text actually changes.
     *
     * Both halves of that matter. {@code Label.settext} has its no-op-on-equal guard deliberately
     * commented out upstream, so a plain Label fed from {@code tick} re-renders, re-strokes and
     * re-uploads a texture every single frame; and a plain Label does not wrap, so a status string
     * this long would stretch the window to one enormous line the moment {@code pack()} ran.
     */
    private class StatusBox extends Widget {
        private Tex tex;
        private List<String> shown = null;

        StatusBox() {
            super(UI.scale(TABLE_W, 30));
        }

        void set(List<String> lines) {
            if (lines.equals(shown))
                return;
            shown = new ArrayList<>(lines);
            render();
        }

        private void render() {
            int maxW = UI.scale(TABLE_W);
            List<String> wrapped = new ArrayList<>();
            for (String l : shown)
                wrapped.addAll(wrap(l, maxW));
            int h = Math.max(rowh(), rowh() * wrapped.size());
            BufferedImage img = new BufferedImage(maxW, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            int y = 0;
            for (String l : wrapped) {
                drawText(g2, l, 0, y, DIM_COLOR);
                y += rowh();
            }
            g2.dispose();

            Tex old = tex;
            tex = new TexI(img);
            if (old != null) {
                try {
                    old.dispose();
                } catch (RuntimeException e) {
                }
            }
            if (sz.y != h) {
                resize(maxW, h);
                if (parent != null)
                    parent.pack();
            }
        }

        @Override
        public void draw(GOut g) {
            super.draw(g);
            if (tex != null)
                g.image(tex, Coord.z);
        }
    }

    // -- results --------------------------------------------------------------------------------

    /** Past this many alternatives it stops being advice and starts being a catalog dump. */
    private static final int SHOWN_CANDIDATES = 8;

    private static final int[] PLAN_X = {0, 190, 240, 310};
    private static final int[] PLAN_W = {186, 46, 66, 120};
    private static final String[] PLAN_TITLE = {"Dish", "Bites", "Hunger", "Expected"};

    private static final int[] ALT_X = {0, 170, 240, 300, 355};
    private static final int[] ALT_W = {166, 66, 56, 51, 75};
    private static final String[] ALT_TITLE = {"Alternative", "pts/bite", "pts/‰", "waste", "alone"};

    private class Body extends Widget {
        private Tex table;

        Body() {
            super(UI.scale(TABLE_W, 20));
        }

        void update(EatPlanner.Plan plan, Map<String, Double> targets) {
            render(plan, targets);
        }

        private void render(EatPlanner.Plan plan, Map<String, Double> targets) {
            int rowh = rowh();
            int maxW = UI.scale(TABLE_W);

            List<EatPlanner.Candidate> alts = plan.candidates.size() > SHOWN_CANDIDATES
                    ? plan.candidates.subList(0, SHOWN_CANDIDATES) : plan.candidates;

            List<String> warnLines = new ArrayList<>();
            for (String w : plan.warnings)
                warnLines.addAll(wrap("! " + w, maxW));

            int lines = 1 + Math.max(1, plan.rows.size())          // plan header + rows
                    + 1 + targets.size()                            // totals + one per goal
                    + (alts.isEmpty() ? 0 : 2 + alts.size())        // blank + header + alternatives
                    + (warnLines.isEmpty() ? 0 : 1 + warnLines.size());
            int h = rowh * lines + UI.scale(10);

            BufferedImage img = new BufferedImage(maxW, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            int y = 0;

            for (int i = 0; i < PLAN_TITLE.length; i++)
                drawCell(g2, PLAN_TITLE[i], UI.scale(PLAN_X[i]), UI.scale(PLAN_W[i]), y, HEADER_COLOR, i > 0);
            y += rowh;

            if (plan.rows.isEmpty()) {
                drawText(g2, "No plan yet - enter a goal and hit Plan.", 0, y, DIM_COLOR);
                y += rowh;
            } else {
                for (EatPlanner.PlanRow r : plan.rows) {
                    Color c = r.qualityFallback ? WARN_COLOR : ROW_COLOR;
                    String name = r.qualityFallback ? r.dish + " (q10 - no quality data)" : r.dish;
                    drawCell(g2, name, UI.scale(PLAN_X[0]), UI.scale(PLAN_W[0]), y, c, false);
                    drawCell(g2, Integer.toString(r.bites), UI.scale(PLAN_X[1]), UI.scale(PLAN_W[1]), y, c, true);
                    drawCell(g2, String.format("%.1f", r.totalHunger), UI.scale(PLAN_X[2]), UI.scale(PLAN_W[2]), y, c, true);
                    drawCell(g2, points(r.expectedPoints), UI.scale(PLAN_X[3]), UI.scale(PLAN_W[3]), y, c, true);
                    y += rowh;
                }
            }

            drawText(g2, String.format("Total %.1f‰ (%.2f hunger bars)  ·  %d bar%s%s",
                    plan.totalHunger, plan.totalHunger / EatPlanner.HUNGER_PER_FULL_BAR,
                    plan.barsSimulated, plan.barsSimulated == 1 ? "" : "s",
                    plan.stalled ? "  ·  stalled" : ""), 0, y, DIM_COLOR);
            y += rowh;

            for (Map.Entry<String, Double> e : targets.entrySet()) {
                double got = plan.expectedPoints.getOrDefault(e.getKey(), 0.0);
                boolean met = got >= e.getValue();
                drawText(g2, String.format("%s: %.2f / %.0f expected%s",
                        e.getKey(), got, e.getValue(), met ? " ✓" : ""),
                        0, y, met ? GOAL_COLOR : DIM_COLOR);
                y += rowh;
            }

            if (!alts.isEmpty()) {
                y += rowh;
                for (int i = 0; i < ALT_TITLE.length; i++)
                    drawCell(g2, ALT_TITLE[i], UI.scale(ALT_X[i]), UI.scale(ALT_W[i]), y, HEADER_COLOR, i > 0);
                y += rowh;
                for (EatPlanner.Candidate c : alts) {
                    Color col = c.qualityFallback ? WARN_COLOR : ROW_COLOR;
                    double perBite = 0;
                    for (double v : c.goalPointsPerBite.values())
                        perBite += v;
                    drawCell(g2, c.dish, UI.scale(ALT_X[0]), UI.scale(ALT_W[0]), y, col, false);
                    drawCell(g2, String.format("%.3f", perBite), UI.scale(ALT_X[1]), UI.scale(ALT_W[1]), y, col, true);
                    drawCell(g2, String.format("%.3f", c.goalPointsPerHunger), UI.scale(ALT_X[2]), UI.scale(ALT_W[2]), y, col, true);
                    // Overflow waste is the number that stops "biggest FEP wins" being the answer:
                    // a dish carrying twice the cap throws away half of every bite.
                    drawCell(g2, c.overflowWaste > 0.005 ? String.format("%.0f%%", c.overflowWaste * 100) : "-",
                            UI.scale(ALT_X[3]), UI.scale(ALT_W[3]), y,
                            c.overflowWaste > 0.25 ? WARN_COLOR : col, true);
                    drawCell(g2, c.bitesAlone < 0 ? "partial" : Integer.toString(c.bitesAlone),
                            UI.scale(ALT_X[4]), UI.scale(ALT_W[4]), y, col, true);
                    y += rowh;
                }
            }

            if (!warnLines.isEmpty()) {
                y += rowh;
                for (String w : warnLines) {
                    drawText(g2, w, 0, y, WARN_COLOR);
                    y += rowh;
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

            resize(maxW, h);
            if (parent != null)
                parent.pack();
        }

        private String points(Map<String, Double> m) {
            if (m == null || m.isEmpty())
                return "-";
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Double> e : m.entrySet()) {
                if (e.getValue() == null || e.getValue() < 0.005)
                    continue;
                if (sb.length() > 0)
                    sb.append(" ");
                sb.append(String.format("%s+%.2f", e.getKey(), e.getValue()));
            }
            return sb.length() == 0 ? "-" : sb.toString();
        }

        @Override
        public void draw(GOut g) {
            super.draw(g);
            if (table != null)
                g.image(table, Coord.z);
        }
    }
}
