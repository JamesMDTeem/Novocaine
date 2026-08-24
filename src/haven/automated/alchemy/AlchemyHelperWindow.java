package haven.automated.alchemy;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.Label;
import haven.OptWnd;
import haven.Tex;
import haven.TexI;
import haven.Text;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.invpool.ContainerPool;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows which elixirs are worth brewing with the ingredients the player can actually reach.
 *
 * The ranking is the mapper's, not this window's. Deciding what a craft would teach means reasoning
 * over the whole world's pooled discoveries and craft log, which lives on the server; this sends up
 * what is in the player's inventory, belt and opened containers and draws the answer that comes
 * back. So a wrong ranking is a server bug and a wrong-looking table is a bug in here, and the two
 * never have to be untangled from each other.
 *
 * Containers are remembered while the helper is enabled -- see {@link ContainerPool} -- so chests
 * can be opened one at a time and still counted together, and everything is forgotten the moment
 * the toggle goes off.
 *
 * Unlike the LP Helper this stays on screen with no container open, because the player's own
 * inventory is always a source and is usually where the ingredients are.
 */
public class AlchemyHelperWindow extends Window {
    /** How often the containers are rescanned. The server request behind it throttles itself. */
    private static final double REFRESH_INTERVAL = 0.5;

    private static final Color HEADER_COLOR = new Color(218, 163, 0);
    private static final Color ROW_COLOR = new Color(235, 235, 235);
    private static final Color DIMMED_COLOR = new Color(120, 120, 120);
    private static final Color SCORE_COLOR = new Color(192, 255, 192);
    private static final Color SOURCE_COLOR = new Color(192, 192, 255);
    private static final Color ERROR_COLOR = new Color(220, 110, 110);

    private static final int[] COL_X = {0, 22, 96, 330};
    private static final int[] COL_W = {20, 70, 230, 46};
    private static final int TABLE_W = COL_X[3] + COL_W[3];
    private static final String[] COL_TITLE = {"#", "Elixir", "Craft", "Score"};

    private static final int ROW_H = 15;
    private static final int MAX_ROWS = 12;

    /**
     * The elixir shapes, as {server name, label}. Swill takes two alchemical inputs and the other
     * two take three, which is why one can be brewable when the others are not.
     */
    private static final String[][] ELIXIRS = {
        {"swill", "Swill"},
        {"mercurial", "Mercurial"},
        {"decoction", "Decoction"},
    };

    /**
     * What a slot may hold. Herbal Grind and Mineral Calcination are absent on purpose: they reroll
     * their inputs, so a craft built on one cannot say anything about the ingredients that went in,
     * and the server refuses them by name rather than silently ignoring them.
     */
    private static final String[][] INPUTS = {
        {"raw", "Raw"},
        {"lye", "Lye"},
        {"fiery", "Fiery"},
        {"distillate", "Distillate"},
    };

    private final GameUI gui;
    private final Body body;
    private final Button modeButton;
    private final Map<String, Boolean> elixirs = new LinkedHashMap<String, Boolean>();
    private final Map<String, Boolean> inputs = new LinkedHashMap<String, Boolean>();
    private final ContainerPool<String> pool = new ContainerPool<String>();
    private final AlchemySuggestClient client = new AlchemySuggestClient();

    private double sinceRefresh = REFRESH_INTERVAL;
    /**
     * What this window has asked to be, which is not the same as {@link #visible} -- Window.hide()
     * starts an animation and leaves visible true until it finishes. Driving the calls off our own
     * intent keeps a toggle flipped twice in quick succession from settling the wrong way.
     */
    private boolean shown = false;
    private Coord savedPos = null;
    private boolean loggedFailure = false;
    /** Set when the scope has been narrowed to nothing, which is not a question worth asking. */
    private String scopeWarning = null;

    public AlchemyHelperWindow(GameUI gui) {
        super(Coord.z, "Alchemy Helper");
        this.gui = gui;
        this.savedPos = Utils.getprefc("wndc-alchemyHelper", null);

        int y = 0;
        modeButton = add(new Button(UI.scale(104), modeLabel()) {
            @Override
            public void click() {
                cycleMode();
            }
        }, 0, y);
        modeButton.settip("What to optimise for: most facts learned, a lean towards effects "
                + "nobody has found yet, or chasing them outright.");

        Button refresh = add(new Button(UI.scale(84), "Refresh") {
            @Override
            public void click() {
                reask();
            }
        }, UI.scale(110), y);
        refresh.settip("Ask the mapper again now, without waiting for the ingredients to change.");

        Button forget = add(new Button(UI.scale(120), "Forget closed") {
            @Override
            public void click() {
                pool.forget();
                reask();
            }
        }, UI.scale(200), y);
        forget.settip("Stop counting containers that are no longer open. Use this if you have "
                + "reopened a chest you took from and the counts look too high.");

        y += UI.scale(26);
        y = addScopeRow(y, "Elixirs:", ELIXIRS, elixirs, "alchemyHelperElixir.");
        y = addScopeRow(y, "Inputs:", INPUTS, inputs, "alchemyHelperInput.");

        this.body = add(new Body(), Coord.of(0, y));
        pack();
    }

    /**
     * One row of scope toggles, laid out left to right by measured width rather than fixed
     * columns -- the labels differ in length and a column grid would either clip "Measured
     * Distillate" or waste half the row on "Raw".
     */
    private int addScopeRow(int y, String caption, String[][] entries,
                            final Map<String, Boolean> flags, final String prefPrefix) {
        Label label = add(new Label(caption), 0, y + UI.scale(3));
        int x = label.sz.x + UI.scale(6);
        for (String[] entry : entries) {
            final String key = entry[0];
            boolean on = Utils.getprefb(prefPrefix + key, true);
            flags.put(key, on);
            /* Named "flags" rather than "state" because ACheckBox has a field by that name, and
             * the inherited one wins inside the subclass body. */
            CheckBox cb = new CheckBox(entry[1]) {
                @Override
                public void changed(boolean val) {
                    Utils.setprefb(prefPrefix + key, val);
                    flags.put(key, val);
                    reask();
                }
            };
            cb.a = on;
            add(cb, x, y + UI.scale(3));
            x += cb.sz.x + UI.scale(10);
        }
        return y + UI.scale(20);
    }

    /** The ticked keys as the comma-separated allow-list the endpoint takes. */
    private static String selected(Map<String, Boolean> state) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : state.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue()))
                continue;
            if (sb.length() > 0)
                sb.append(',');
            sb.append(e.getKey());
        }
        return sb.toString();
    }

    /** Throws away the answer on screen and asks again on the next tick. */
    private void reask() {
        client.invalidate();
        sinceRefresh = REFRESH_INTERVAL;
    }

    /** Cycles the objective, mirroring the website's Suggestions modes. */
    private void cycleMode() {
        String next;
        switch (mode()) {
            case "maxinfo":
                next = "balanced";
                break;
            case "balanced":
                next = "hunt";
                break;
            default:
                next = "maxinfo";
                break;
        }
        Utils.setpref("alchemyHelperMode", next);
        modeButton.change(modeLabel());
        reask();
    }

    private static String modeLabel() {
        switch (mode()) {
            case "balanced":
                return "Balanced";
            case "hunt":
                return "Hunt unfound";
            default:
                return "Max-info";
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        boolean wanted = OptWnd.alchemyHelperCheckBox != null && OptWnd.alchemyHelperCheckBox.a;
        if (!wanted) {
            if (shown) {
                shown = false;
                savePos();
                hide();
                /* Everything the pool remembers was true only while the helper was on. Keeping it
                 * across a disable would mean re-enabling in another region silently plans against
                 * chests half a map away. */
                pool.clear();
                client.invalidate();
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
         * raises a plain RuntimeException. The window simply keeps last tick's answer and tries
         * again in REFRESH_INTERVAL. Logged once, because a guard that hides a repeatable bug
         * silently is how the bug stays. */
        try {
            pool.refresh(gui, (item, into) -> {
                String name = item.item.getname();
                if (name != null && !name.isEmpty())
                    into.add(name);
            });
            /* An empty allow-list means "no restriction" to the server, not "nothing allowed", so
             * unticking every box would silently widen the search instead of narrowing it. Refuse
             * to ask rather than show an answer to a question nobody asked. */
            String types = selected(elixirs);
            String processes = selected(inputs);
            if (types.isEmpty() || processes.isEmpty()) {
                scopeWarning = types.isEmpty()
                        ? "Tick at least one elixir."
                        : "Tick at least one input.";
                body.update(pool, client);
                return;
            }
            scopeWarning = null;
            client.refresh(pool.items(), mode(), types, processes, MAX_ROWS);
            body.update(pool, client);
        } catch (RuntimeException e) {
            if (!loggedFailure) {
                loggedFailure = true;
                haven.automated.nbots.core.NLog.crash("Alchemy Helper refresh", e);
                if (gui != null)
                    gui.error("Alchemy Helper hit an error and will keep the last plan "
                            + "(details in logs/crash.log).");
            }
        }
    }

    /** The objective, mirroring the website's Suggestions modes. */
    private static String mode() {
        String m = Utils.getpref("alchemyHelperMode", "maxinfo");
        return (m == null || m.isEmpty()) ? "maxinfo" : m;
    }

    /**
     * Position is written when the window goes away rather than while it is being dragged: the pref
     * store is a file, and a drag would otherwise write to it every frame.
     */
    private void savePos() {
        if (c == null || c.equals(savedPos))
            return;
        savedPos = c;
        Utils.setprefc("wndc-alchemyHelper", c);
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
            if (OptWnd.alchemyHelperCheckBox != null)
                OptWnd.alchemyHelperCheckBox.set(false);
            shown = false;
            hide();
            pool.clear();
            client.invalidate();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }

    /**
     * Clicking the window body forgets every container that is no longer open, keeping the live
     * ones. Reopening a chest you have taken from cannot be told from opening a second identical
     * one (see {@link ContainerPool}), so the drift that causes needs a way out that is not
     * "turn the helper off and start again".
     */
    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (ev.b == 3 && body != null && ev.c.isect(body.c, body.sz)) {
            pool.forget();
            client.invalidate();
            return true;
        }
        return super.mousedown(ev);
    }

    /**
     * The table is composed into one texture per change rather than drawn cell by cell every frame.
     */
    private class Body extends Widget {
        private Tex table;
        private String signature = null;

        Body() {
            super(UI.scale(TABLE_W, 40));
        }

        void update(ContainerPool<String> pool, AlchemySuggestClient client) {
            List<AlchemySuggestClient.Craft> crafts = client.cached();
            String sig = signature(pool, client, crafts);
            if (sig.equals(signature))
                return;
            signature = sig;
            render(pool, client, crafts);
        }

        private String signature(ContainerPool<String> pool, AlchemySuggestClient client,
                                 List<AlchemySuggestClient.Craft> crafts) {
            StringBuilder sb = new StringBuilder();
            sb.append(pool.sourceCount()).append('/').append(pool.items().size())
                    .append('/').append(client.busy()).append('/').append(client.lastError())
                    .append('/').append(scopeWarning).append(';');
            /* Every column that is drawn has to appear here, or the table keeps a stale texture. */
            if (crafts != null) {
                for (AlchemySuggestClient.Craft c : crafts) {
                    sb.append(c.elixirType).append(':').append(String.join("+", c.slots))
                            .append(':').append(String.format("%.2f", c.score)).append(';');
                }
            }
            return sb.toString();
        }

        private void render(ContainerPool<String> pool, AlchemySuggestClient client,
                            List<AlchemySuggestClient.Craft> crafts) {
            int rowh = UI.scale(ROW_H);
            int rows = (crafts == null) ? 0 : Math.min(crafts.size(), MAX_ROWS);
            int lines = 2 + 1 + Math.max(1, rows);
            BufferedImage img = new BufferedImage(UI.scale(TABLE_W), rowh * lines + UI.scale(6),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();

            int y = 0;
            drawText(g2, sourceLine(pool), 0, y, SOURCE_COLOR);
            y += rowh;

            if (scopeWarning != null) {
                drawText(g2, scopeWarning, 0, y, ERROR_COLOR);
            } else if (client.lastError() != null) {
                drawText(g2, "Mapper: " + client.lastError(), 0, y, ERROR_COLOR);
            } else if (client.busy()) {
                drawText(g2, "Asking the mapper…", 0, y, DIMMED_COLOR);
            } else if (crafts != null && !crafts.isEmpty()) {
                drawText(g2, "Take one of each slot; the elixir needs them together.",
                        0, y, DIMMED_COLOR);
            } else {
                drawText(g2, "Open a container or carry ingredients, then Refresh.",
                        0, y, DIMMED_COLOR);
            }
            y += rowh;

            for (int i = 0; i < COL_TITLE.length; i++)
                drawCell(g2, COL_TITLE[i], i, y, HEADER_COLOR, i == 3);
            y += rowh;

            if (crafts == null) {
                drawText(g2, "No answer from the mapper yet.", 0, y, DIMMED_COLOR);
            } else if (crafts.isEmpty()) {
                drawText(g2, pool.isEmpty()
                        ? "Open a container, or carry some ingredients."
                        : "Nothing left to learn from these ingredients.", 0, y, DIMMED_COLOR);
            } else {
                for (int i = 0; i < rows; i++) {
                    AlchemySuggestClient.Craft c = crafts.get(i);
                    drawCell(g2, Integer.toString(i + 1), 0, y, DIMMED_COLOR, false);
                    drawCell(g2, c.elixirType, 1, y, HEADER_COLOR, false);
                    drawCell(g2, String.join(" + ", c.slots.isEmpty() ? c.ingredients : c.slots),
                            2, y, ROW_COLOR, false);
                    drawCell(g2, String.format("%.2f", c.score), 3, y, SCORE_COLOR, true);
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
            resize(UI.scale(TABLE_W), img.getHeight());
            if (parent != null)
                parent.pack();
        }

        /** Which containers are being counted, and which of them are only remembered. */
        private String sourceLine(ContainerPool<String> pool) {
            List<ContainerPool.Source> sources = pool.sources();
            if (sources.isEmpty())
                return "No containers open.";
            int items = 0;
            int remembered = 0;
            List<String> names = new ArrayList<String>();
            for (ContainerPool.Source s : sources) {
                items += s.size();
                if (!s.open)
                    remembered++;
                if (names.size() < 4)
                    names.add(s.open ? s.name : s.name + "*");
            }
            String head = String.join(", ", names);
            if (sources.size() > names.size())
                head += " +" + (sources.size() - names.size());
            return String.format("%s  ·  %d item%s%s", head, items, items == 1 ? "" : "s",
                    remembered > 0 ? "  ·  * remembered" : "");
        }

        private void drawText(Graphics2D g2, String s, int x, int y, Color c) {
            g2.drawImage(Text.render(s, c).img, x, y, null);
        }

        /** Only the score column is a number and hugs its right edge; the rest is prose. */
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
