package haven.automated.nbots;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.world.Barriers;
import haven.automated.nbots.world.PlaceOverlay;

import java.awt.Color;
import java.util.Objects;

/**
 * The behaviour toggles every crew bot shares.
 *
 * Only global behaviour lives here. Destinations - water, food, storage - are in
 * {@link PlacesWindow}, and per-bot options are in each bot's own window via
 * {@link haven.automated.nbots.core.BotSettings}. The split is worth keeping: this window answers
 * "how should bots behave", the places window answers "where is everything", and a bot's own
 * window answers "what should THIS one do" - three questions that were previously mixed.
 *
 * Grouped into headed sections rather than one column, because the list has outgrown being read as
 * a list. The groups are by the QUESTION each answers - who else is around, how do they look after
 * themselves, how do they get about, what can you see - so a setting has one obvious place to be
 * looked for rather than a position in an order nobody remembers.
 */
public class NBotsSettingsWindow extends Window {
    private final GameUI gui;

    public NBotsSettingsWindow(GameUI gui) {
        super(UI.scale(400, 420), "Custom Settings");
        this.gui = gui;

        /* Everything below counts in SCALED pixels. It used to count in unscaled ones and hand
         * them to UI.scale, which is fine for the checkbox rows - a flat twenty covers those -
         * but not for the buttons: a Button's height comes from its own images, is already
         * scaled, and a wide one is the taller of the two variants. The twenty-six added after
         * "Forget remembered walls" was several pixels short of it, so the next section heading
         * was drawn through the button. Rows that contain a button now measure it. */
        int y = UI.scale(4);
        y = section(y, "Working together");
        y = toggle(y, "Reserve work spots between my clients", NBotConfig.Key.shareClaims);
        y = toggle(y, "Keep out of other characters' way", NBotConfig.Key.avoidOthers);

        y = section(y, "Looking after themselves");
        y = toggle(y, "Swap tools automatically (axe / pick / shovel)", NBotConfig.Key.autoEquipTools);
        y = toggle(y, "Refill water when it runs out", NBotConfig.Key.autoRefillWater);
        y = toggle(y, "Eat when energy runs low", NBotConfig.Key.autoEat);

        y = section(y, "Getting around");
        y = toggle(y, "Walk around water instead of swimming", NBotConfig.Key.avoidWater);
        y = toggle(y, "Open gates and walk through them", NBotConfig.Key.useGates);
        y = toggle(y, "Shut a gate again behind us", NBotConfig.Key.closeGates);
        y = below(add(new Button(UI.scale(180), "Forget remembered walls") {
            @Override
            public void click() {
                /* Walls are learned and never expire, so a base that has been torn down leaves a
                 * palisade in the record that nothing will ever contradict - routes would go on
                 * detouring around a wall that is not there. There is no way to detect that from
                 * the client, so it is a button. */
                Barriers.reset();
                gui.msg("Forgot every remembered wall and gate.", Color.WHITE);
            }
        }, new Coord(UI.scale(16), y)), UI.scale(10));

        y = section(y, "Seeing what they're doing");
        y = toggle(y, "Draw ticked areas on the ground", NBotConfig.Key.showAreas);
        y = below(add(new Button(UI.scale(180), "Open Bot Places") {
            @Override
            public void click() {
                openPlaces();
            }
        }, new Coord(UI.scale(16), y)), UI.scale(10));

        add(new Label("Water, food and work areas are defined in Bot Places."),
            new Coord(UI.scale(10), y));
        pack();
    }

    /** The y a following row should start at, from where a widget actually ended. */
    private static int below(Widget w, int gap) {
        return w.c.y + w.sz.y + gap;
    }

    private int section(int y, String title) {
        // A little air above a heading, except the first: a heading butted against the row above
        // reads as belonging to it.
        int top = (y <= UI.scale(4)) ? y : (y + UI.scale(6));
        Label l = add(new Label(title), new Coord(UI.scale(10), top));
        l.setcolor(Color.YELLOW);
        return below(l, UI.scale(4));
    }

    private int toggle(int y, String label, NBotConfig.Key key) {
        return below(add(new CheckBox(label) {
            {
                a = NBotConfig.on(key);
            }

            public void set(boolean val) {
                NBotConfig.set(key, val);
                a = val;
                // The overlay sweep only removes rectangles it can see are unwanted on its own
                // schedule; clearing here makes turning it off look immediate.
                if (key == NBotConfig.Key.showAreas && !val)
                    PlaceOverlay.clear(NBotsSettingsWindow.this.gui);
            }
        }, new Coord(UI.scale(16), y)), UI.scale(4));
    }

    /**
     * Opens the places window, or brings it forward if it is already up.
     *
     * Deliberately not a toggle, unlike the menu-grid button: this one is offered as "here is where
     * that lives", and a button under that heading that CLOSES the window you were looking for
     * would be a trap.
     */
    private void openPlaces() {
        if (gui.nbotPlaces != null) {
            gui.nbotPlaces.raise();
            return;
        }
        gui.nbotPlaces = new PlacesWindow(gui);
        gui.add(gui.nbotPlaces, Utils.getprefc("wndc-nbotPlacesWindow",
            new Coord(gui.sz.x / 2 - gui.nbotPlaces.sz.x / 2,
                gui.sz.y / 2 - gui.nbotPlaces.sz.y / 2 - 200)));
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && Objects.equals(msg, "close")) {
            if (gui.nBotsSettings == this)
                gui.nBotsSettings = null;
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }

    @Override
    public void reqdestroy() {
        Utils.setprefc("wndc-nBotsSettingsWindow", this.c);
        super.reqdestroy();
    }
}
