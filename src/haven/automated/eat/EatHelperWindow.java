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
 * Both the variety coefficient and satiation now prefer {@link CalibrationClient}'s
 * server-measured values (pooled from every character's uploaded {@code EatObserver} log - see
 * that class and {@code IEatLogService}) over the wiki. Both still have a real coverage
 * dependency, called out in the status readout rather than hidden: the variety coefficient falls
 * back to the wiki's table for any hunger-level bucket with fewer than
 * {@link #MIN_CALIBRATION_SAMPLES} measurements, and satiation can only price a live
 * {@code Constipations} entry whose resource key the server has resolved to a category from at
 * least two agreeing samples - an entry with no match yet plans as unsatiated for that one
 * category, not for the whole dish. Both gaps close themselves the more this window (and
 * EatObserver) gets used across the tenant; there is nothing to configure.
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
     * Wiki Hunger-table coefficients (see the plan's evidence table), used only until a real
     * per-hunger-level measurement is wired in from EatObserver's log. Keyed by the Food Efficiency
     * multiplier the wiki names each row after - gmod reads directly as that multiplier.
     */
    private static final double[] WIKI_GMOD_TIERS = {3.0, 2.0, 1.5, 1.0, 0.9, 0.75, 0.5};
    private static final double[] WIKI_COEFS = {1.097, 0.894, 0.632, 0.602, 0.447, 0.315, 0.315};

    private static double wikiVarietyCoef(double gmod) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < WIKI_GMOD_TIERS.length; i++) {
            double dist = Math.abs(WIKI_GMOD_TIERS[i] - gmod);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return WIKI_COEFS[best];
    }

    /** Below this many pooled samples in its bucket, a measured coefficient isn't meaningfully
     *  better than the wiki guess it would replace - fall back rather than trust a thin sample. */
    private static final int MIN_CALIBRATION_SAMPLES = 3;

    /** Nearest measured bucket within this much gmod is close enough to use; further than that,
     *  the bucket is answering a different hunger level and the wiki's nearest-tier guess is the
     *  more honest fallback. Half the wiki table's own tightest gap (Full 0.9 to Stuffed 0.75). */
    private static final double MAX_CALIBRATION_GMOD_DIST = 0.1;

    /** True if {@link #resolveVarietyCoef} is currently backed by a measured sample. Drives the
     *  status line's "(measured, N samples)" vs "(wiki estimate)" wording. */
    private boolean varietyIsMeasured = false;
    private int varietySamplesUsed = 0;

    private double resolveVarietyCoef(double gmod) {
        varietyIsMeasured = false;
        varietySamplesUsed = 0;
        CalibrationClient.Calibration cal = CalibrationClient.cached();
        if (cal != null) {
            CalibrationClient.VarietySample best = null;
            double bestDist = Double.MAX_VALUE;
            for (CalibrationClient.VarietySample s : cal.variety) {
                double dist = Math.abs(s.gmod - gmod);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = s;
                }
            }
            if (best != null && best.samples >= MIN_CALIBRATION_SAMPLES && bestDist <= MAX_CALIBRATION_GMOD_DIST) {
                varietyIsMeasured = true;
                varietySamplesUsed = best.samples;
                return best.coefficient;
            }
        }
        return wikiVarietyCoef(gmod);
    }

    private final GameUI gui;
    private final Map<String, TextEntry> goalFields = new LinkedHashMap<>();
    private final Body body;
    private CheckBox tableOverride;
    private Label status;

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

        y += row + UI.scale(4);
        add(new Button(UI.scale(230), "Plan") {
            @Override
            public void click() {
                runPlan();
            }
        }, x0, y);
        y += row + UI.scale(6);

        status = add(new Label(""), x0, y);
        y += UI.scale(60); // room for the multi-line status text

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
        StringBuilder sb = new StringBuilder();
        try {
            double cap = gui.chrwdg.battr.feps.cap;
            double gmod = gui.chrwdg.battr.glut.gmod;
            double coef = resolveVarietyCoef(gmod);
            String coefSrc = varietyIsMeasured
                    ? String.format("measured, %d samples", varietySamplesUsed) : "wiki estimate";
            sb.append(String.format("Cap: %.1f  ·  FEP mult: %.2fx  ·  variety coef: %.3f (%s)",
                    cap, gmod, coef, coefSrc));
        } catch (Exception e) {
            sb.append("Character sheet not loaded yet.");
        }
        List<EatPlanner.Dish> catalog = CookbookClient.cached();
        String err = CookbookClient.lastError();
        if (catalog != null)
            sb.append(String.format("  ·  %d dishes loaded", catalog.size()));
        else if (err != null)
            sb.append("  ·  cookbook: ").append(err);
        else
            sb.append("  ·  cookbook: loading…");
        CalibrationClient.Calibration cal = CalibrationClient.cached();
        if (cal != null)
            sb.append(String.format("  ·  %d satiation categories known", cal.satiationCategoryMap.size()));
        status.settext(sb.toString());
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
            double v = parseGoal(e.getValue().text());
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

        EatPlanner.Plan plan = EatPlanner.plan(catalog, state, new EatPlanner.Goal(targets),
                qualityMode, qualityPct, 500);
        body.update(plan, targets);
    }

    private static double parseGoal(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
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
            double variety = resolveVarietyCoef(gmod);

            Map<String, Double> satiation = readLiveSatiation();

            ModifierContext mods = ModifierContext.resolve(ui);
            double accountMult = mods != null ? mods.accountMult : 1.0;
            boolean useTable = tableOverride.a || (mods != null && mods.atTable);
            double tableFoodEventBonus = useTable && mods != null ? mods.tableFoodEventBonus : 1.0;
            double tableHungerMod = useTable && mods != null ? mods.tableHungerMod : 1.0;

            return new EatPlanner.CharState(attrs, gmod, satiation, accountMult,
                    tableFoodEventBonus, tableHungerMod, cap, variety);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Live {@code Constipations} entries keyed the same way {@code EatObserver.onSatiation}
     * already logs them, resolved to a wiki category name via the server's calibration map so
     * {@code EatPlanner}'s {@code satMod} (keyed by category, since that's what the cookbook
     * catalog's dishes carry) can actually use them. An entry with no resolved category yet is
     * simply absent from the result - {@code satMod} treats a missing group as unsatiated, which
     * is the honest answer for "this game state exists but we don't know its name yet", not a bug
     * to work around.
     */
    private Map<String, Double> readLiveSatiation() {
        Map<String, Double> satiation = new LinkedHashMap<>();
        CalibrationClient.Calibration cal = CalibrationClient.cached();
        if (cal == null || cal.satiationCategoryMap.isEmpty())
            return satiation;
        if (gui.chrwdg == null || gui.chrwdg.battr == null || gui.chrwdg.battr.cons == null)
            return satiation;
        for (BAttrWnd.Constipations.El el : gui.chrwdg.battr.cons.els) {
            try {
                String key = EatObserver.resolveSatiationKey(el.t);
                String category = cal.satiationCategoryMap.get(key);
                if (category != null)
                    satiation.merge(category, el.a, Math::max);
            } catch (Exception e) {
                // One unresolved entry must not cost the rest of the satiation picture.
            }
        }
        return satiation;
    }

    // -- results table --------------------------------------------------------------------------

    private static final int TABLE_W = 280;
    private static final int[] COL_X = {0, 150, 190};
    private static final int[] COL_W = {146, 36, 90};
    private static final String[] COL_TITLE = {"Dish", "Bites", "Hunger"};
    private static final Color HEADER_COLOR = new Color(218, 163, 0);
    private static final Color ROW_COLOR = new Color(210, 210, 210);
    private static final Color DIM_COLOR = new Color(140, 140, 140);
    private static final Color GOAL_COLOR = new Color(192, 255, 192);
    private static final Color WARN_COLOR = new Color(255, 192, 128);

    private class Body extends Widget {
        private Tex table;

        Body() {
            super(UI.scale(TABLE_W, 20));
        }

        void update(EatPlanner.Plan plan, Map<String, Double> targets) {
            render(plan, targets);
        }

        private void render(EatPlanner.Plan plan, Map<String, Double> targets) {
            int rowh = UI.scale(15);
            int rows = Math.max(1, plan.rows.size());
            int extraLines = 2 + targets.size(); // header line, hunger total, one per goal stat
            int h = rowh * (1 + rows + extraLines) + UI.scale(10);
            BufferedImage img = new BufferedImage(UI.scale(TABLE_W), h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();

            int y = 0;
            for (int i = 0; i < COL_TITLE.length; i++)
                drawCell(g2, COL_TITLE[i], i, y, HEADER_COLOR, i > 0);
            y += rowh;

            if (plan.rows.isEmpty()) {
                drawText(g2, "No plan yet - enter a goal and hit Plan.", 0, y, DIM_COLOR);
                y += rowh;
            } else {
                for (EatPlanner.PlanRow r : plan.rows) {
                    Color c = r.qualityFallback ? WARN_COLOR : ROW_COLOR;
                    String name = r.qualityFallback ? r.dish + " (q10 - no quality data)" : r.dish;
                    drawCell(g2, name, 0, y, c, false);
                    drawCell(g2, Integer.toString(r.bites), 1, y, c, true);
                    drawCell(g2, String.format("%.1f", r.totalHunger), 2, y, c, true);
                    y += rowh;
                }
            }

            drawText(g2, String.format("Total hunger: %.1f  ·  %d bar%s simulated%s",
                    plan.totalHunger, plan.barsSimulated, plan.barsSimulated == 1 ? "" : "s",
                    plan.stalled ? " (ran out of catalog)" : ""), 0, y, DIM_COLOR);
            y += rowh;

            for (Map.Entry<String, Double> e : targets.entrySet()) {
                double got = plan.expectedPoints.getOrDefault(e.getKey(), 0.0);
                boolean met = got >= e.getValue();
                drawText(g2, String.format("%s: %.2f / %.0f expected%s",
                        e.getKey(), got, e.getValue(), met ? " ✓" : ""),
                        0, y, met ? GOAL_COLOR : DIM_COLOR);
                y += rowh;
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

            resize(UI.scale(TABLE_W), h);
            if (parent != null)
                parent.pack();
        }

        private void drawText(Graphics2D g2, String s, int x, int y, Color c) {
            g2.drawImage(Text.render(s, c).img, x, y, null);
        }

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
            if (table != null)
                g.image(table, Coord.z);
        }
    }
}
