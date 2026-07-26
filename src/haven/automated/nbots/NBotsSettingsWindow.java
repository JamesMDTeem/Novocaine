package haven.automated.nbots;

import haven.CheckBox;
import haven.GameUI;
import haven.Label;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.nbots.core.NBotConfig;

import java.util.Objects;

/**
 * The behaviour toggles every crew bot shares.
 *
 * Only global behaviour lives here now. Destinations - water, food, storage - moved to
 * {@link PlacesWindow}, and per-bot options moved into each bot's own window via
 * {@link haven.automated.nbots.core.BotSettings}. The split is worth keeping: this window answers
 * "how should bots behave", the places window answers "where is everything", and a bot's own
 * window answers "what should THIS one do" - three questions that were previously mixed.
 */
public class NBotsSettingsWindow extends Window {
    private final GameUI gui;

    public NBotsSettingsWindow(GameUI gui) {
        super(UI.scale(330, 190), "Nurgling Bot Settings");
        this.gui = gui;

        int y = 4;
        add(new Label("Working together"), UI.scale(10, y));
        y += 18;
        add(toggle("Reserve work spots between my clients", NBotConfig.Key.shareClaims),
            UI.scale(16, y));
        y += 20;
        add(toggle("Keep out of other characters' way", NBotConfig.Key.avoidOthers),
            UI.scale(16, y));
        y += 20;
        add(toggle("Walk around water instead of swimming", NBotConfig.Key.avoidWater),
            UI.scale(16, y));
        y += 28;

        add(new Label("Looking after themselves"), UI.scale(10, y));
        y += 18;
        add(toggle("Swap tools automatically (axe / pick / shovel)", NBotConfig.Key.autoEquipTools),
            UI.scale(16, y));
        y += 20;
        add(toggle("Refill water when it runs out", NBotConfig.Key.autoRefillWater),
            UI.scale(16, y));
        y += 20;
        add(toggle("Eat when energy runs low", NBotConfig.Key.autoEat), UI.scale(16, y));
        y += 26;

        add(new Label("Water and food come from places you tag in Bot Places."), UI.scale(10, y));
        pack();
    }

    private CheckBox toggle(String label, NBotConfig.Key key) {
        return new CheckBox(label) {
            {
                a = NBotConfig.on(key);
            }

            public void set(boolean val) {
                NBotConfig.set(key, val);
                a = val;
            }
        };
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
