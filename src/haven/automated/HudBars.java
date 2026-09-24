package haven.automated;

import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.IMeter;
import haven.Tex;
import haven.Text;
import haven.UI;
import haven.Utils;
import haven.Widget;

import java.awt.Color;
import java.util.List;

/**
 * Health, stamina and energy as three flat bars you place yourself (after brodgar-io-client's HUD
 * addon).
 *
 * The stock meters sit in a fixed row beside the portrait, three small plates with the number
 * only in a tooltip; Hurricane's own "Always Show Combat UI" bars are bigger but fixed to the top
 * of the screen and have no energy. These are drawn from the same {@link IMeter} data the stock
 * meters draw from - every band the server sends, in its own colour - and each one is moved on its
 * own with Alt and a drag. A click without Alt goes straight through to whatever is underneath, so
 * a bar over the world never eats a walk click. While they are on the stock meters are hidden, and
 * turning them off gives them back.
 *
 * Settings live in Novocaine Settings > Interface: on/off, and one width and height for all three.
 * Where each bar was dropped is remembered.
 */
public class HudBars extends Widget {
    public static final String PREF = "hudBars";
    private static final String[] NAMES = {"hp", "stam", "nrj"};
    private static final String[] LABELS = {"Health", "Stamina", "Energy"};

    public static boolean enabled() {
        return Utils.getprefb(PREF, false);
    }

    public static int barw() {
        return UI.scale(Utils.getprefi("hudBarW", 220));
    }

    public static int barh() {
        return UI.scale(Utils.getprefi("hudBarH", 18));
    }

    /** Adds the three bars to a GameUI. */
    public static void attach(GameUI gui) {
        for (int i = 0; i < NAMES.length; i++) {
            Coord def = UI.scale(new Coord(10, 130 + i * 26));
            gui.add(new HudBars(NAMES[i], LABELS[i]), Utils.getprefc("hudbar-" + NAMES[i], def));
        }
    }

    private final String name, label;
    private UI.Grab drag = null;
    private Coord dc;
    private Tex text = null;
    private String shown = null;
    /** Whether this bar last hid the stock meters; only the "hp" bar manages them. */
    private boolean hid = false;

    private HudBars(String name, String label) {
        super(new Coord(barw(), barh()));
        this.name = name;
        this.label = label;
    }

    private GameUI gui() {
        return getparent(GameUI.class);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        boolean on = enabled();
        Coord want = new Coord(barw(), barh());
        if (!sz.equals(want))
            resize(want);
        if (name.equals("hp") && (on != hid)) {
            /* Only on a change, so nothing else that shows or hides the meters is fought every frame. */
            GameUI gui = gui();
            if (gui != null) {
                for (Widget m : gui.meterWidgets())
                    if (m instanceof IMeter)
                        m.show(!on);
            }
            hid = on;
        }
    }

    private String reading(List<IMeter.Meter> ms) {
        if (name.equals("hp")) {
            String hp = IMeter.characterCurrentHealth;
            if (hp != null && !hp.isEmpty())
                return hp;
        }
        return Math.round(ms.get(0).a * 100) + "%";
    }

    @Override
    public void draw(GOut g) {
        if (!enabled() || !GameUI.showUI)
            return;
        GameUI gui = gui();
        List<IMeter.Meter> ms = (gui == null) ? null : gui.getmeters(name);
        if (ms == null || ms.isEmpty())
            return;
        g.chcolor(0, 0, 0, 170);
        g.frect(Coord.z, sz);
        for (IMeter.Meter m : ms) {
            g.chcolor(m.c);
            g.frect(Coord.z, new Coord((int) Math.ceil(sz.x * Math.max(0, Math.min(1, m.a))), sz.y));
        }
        g.chcolor(ui.modmeta ? new Color(255, 220, 0) : Color.BLACK);
        g.rect(Coord.z, sz);
        g.chcolor();
        String now = label + " " + reading(ms);
        if (!now.equals(shown)) {
            if (text != null)
                text.dispose();
            text = Text.renderstroked(now, Color.WHITE, Color.BLACK, Text.num12boldFnd).tex();
            shown = now;
        }
        /* Left off when the bar is too short to hold it, rather than clipped. */
        if (text.sz().y <= sz.y + UI.scale(2))
            g.aimage(text, sz.div(2), 0.5, 0.5);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (!enabled() || !GameUI.showUI || !ui.modmeta || ev.b != 1)
            return false;
        if (drag != null)
            drag.remove();
        drag = ui.grabmouse(this);
        dc = ev.c;
        return true;
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        if (drag != null) {
            c = c.add(ev.c).sub(dc);
            return;
        }
        super.mousemove(ev);
    }

    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if (drag != null) {
            drag.remove();
            drag = null;
            if (parent != null)
                c = new Coord(Utils.clip(c.x, 0, Math.max(0, parent.sz.x - sz.x)), Utils.clip(c.y, 0, Math.max(0, parent.sz.y - sz.y)));
            Utils.setprefc("hudbar-" + name, c);
            return true;
        }
        return super.mouseup(ev);
    }

    @Override
    public boolean checkhit(Coord c) {
        return enabled() && GameUI.showUI && super.checkhit(c);
    }

    @Override
    public void dispose() {
        if (text != null)
            text.dispose();
        super.dispose();
    }
}
