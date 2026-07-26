package haven.automated.nbots;

import haven.Button;
import haven.CheckBox;
import haven.GameUI;
import haven.Label;
import haven.TextEntry;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;

import java.awt.Color;
import java.util.Objects;

/**
 * The one window for everything the Nurgling-tab bots share: how they coordinate with each other,
 * and where they go for water.
 *
 * These settings live together rather than being repeated in each bot's own window because they
 * are genuinely shared - three bots run by one player against one work site should not each have
 * their own idea of where the water barrel is - and because the water source in particular is
 * something you set once when you set up a site, not something you fiddle with per run.
 */
public class NBotsSettingsWindow extends Window {
    private final GameUI gui;
    private final Label sourceLabel;

    public NBotsSettingsWindow(GameUI gui) {
        super(UI.scale(320, 260), "Nurgling Bots");
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
        y += 20;
        add(toggle("Swap tools automatically (axe / pick / shovel)", NBotConfig.Key.autoEquipTools),
            UI.scale(16, y));
        y += 28;

        add(new Label("Water"), UI.scale(10, y));
        y += 18;
        add(toggle("Go and refill when I run out", NBotConfig.Key.autoRefillWater),
            UI.scale(16, y));
        y += 22;

        sourceLabel = add(new Label(""), UI.scale(16, y));
        y += 18;
        add(new Button(UI.scale(140), "Set source here") {
            @Override
            public void click() {
                setSourceHere();
            }
        }, UI.scale(16, y));
        add(new Button(UI.scale(120), "Forget source") {
            @Override
            public void click() {
                NBotConfig.waterSource(null);
                refresh();
                gui.msg("Water source forgotten.", Color.WHITE);
            }
        }, UI.scale(164, y));
        y += 30;

        add(new Label("...or use a map marker named:"), UI.scale(16, y));
        y += 18;
        add(new TextEntry(UI.scale(180), NBotConfig.waterMarker()) {
            @Override
            public void changed() {
                NBotConfig.waterMarker(text());
                refresh();
            }
        }, UI.scale(16, y));
        y += 30;

        add(new Label("Work radius (map units):"), UI.scale(10, y));
        add(new TextEntry(UI.scale(60), Integer.toString(NBotConfig.radius())) {
            @Override
            public void changed() {
                try {
                    int v = Integer.parseInt(text().trim());
                    if (v > 0)
                        NBotConfig.radius(v);
                } catch (NumberFormatException ignored) {
                    // Half-typed numbers are normal while editing; keep the last good value.
                }
            }
        }, UI.scale(190, y - 3));

        refresh();
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

    /**
     * Records where the player is standing as the water source.
     *
     * Refuses unless there is actually water here, rather than storing the spot and letting a bot
     * discover the problem later, several hundred tiles from its work site.
     */
    private void setSourceHere() {
        WaterService svc = new WaterService(gui, new BotNav(gui, () -> true, "nbot-settings.log"),
            "nbot-settings.log");
        if (svc.learnHere()) {
            refresh();
            gui.msg("Water source set to where you're standing.", Color.GREEN);
        } else {
            gui.error("Stand next to a water barrel, or in fresh water, then press this again.");
        }
    }

    private void refresh() {
        String marker = NBotConfig.waterMarker();
        if (marker != null && !marker.isEmpty()) {
            boolean found = WorldAnchor.ofMarker(gui, marker) != null;
            sourceLabel.settext(found ? "Using map marker \"" + marker + "\"."
                : "No marker named \"" + marker + "\" - falling back.");
            return;
        }
        WorldAnchor a = NBotConfig.waterSource();
        if (a == null)
            sourceLabel.settext("No water source set.");
        else
            sourceLabel.settext(a.reachable(gui) ? "Source set, and reachable from here."
                : "Source set, but not on this part of the map.");
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        // The marker may be created or renamed while this window is open, and whether the stored
        // source is reachable changes as the player walks. Re-checked on a slow cadence rather than
        // every frame: resolving an anchor takes the map file's read lock.
        if ((ticks++ % 60) == 0)
            refresh();
    }

    private int ticks = 0;

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
