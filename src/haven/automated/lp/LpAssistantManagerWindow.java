package haven.automated.lp;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.TextEntry;
import haven.UI;
import haven.Window;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Settings window for the LP assistant, launched from the menu grid (Custom Client Extras ->
 * Nurgling Imports -> LP Assistant Manager). Follows Hurricane's manager-window pattern
 * (CustomAlarmManager / AutoDropManager): a plain Window of checkboxes wired straight to the
 * feature's prefs, plus the per-character reset. All settings live in LpConfig; this window is
 * just a view of them, so closing/reopening it always shows the truth.
 */
public class LpAssistantManagerWindow extends Window {
    private final GameUI gui;

    public LpAssistantManagerWindow(GameUI gui) {
        super(UI.scale(300, 372), "LP Assistant Manager");
        this.gui = gui;

        int x = UI.scale(10);
        int y = UI.scale(2);
        int row = UI.scale(24);

        add(prefBox("Enable LP Assistant (markers & tracking)", LpConfig.Key.lpassistent), x, y);
        y += row;
        add(prefBox("Show chop hint on trees with undiscovered wood", LpConfig.Key.treeHarvestWood), x, y);
        y += row;

        add(new Label("Always-on harvest overlays:") {{ setcolor(new Color(218, 163, 0)); }}, x, y + UI.scale(4));
        y += row;
        add(prefBox("Trees", LpConfig.Key.treeHarvestOverlay), x, y);
        y += row;
        int indent = x + UI.scale(20);
        add(prefBox("Seeds/Fruit", LpConfig.Key.treeHarvestSeeds), indent, y);
        y += row;
        add(prefBox("Leaves", LpConfig.Key.treeHarvestLeaves), indent, y);
        y += row;
        add(prefBox("Boughs", LpConfig.Key.treeHarvestBoughs), indent, y);
        y += row;
        add(prefBox("Bark", LpConfig.Key.treeHarvestBark), indent, y);
        y += row;
        add(prefBox("Bushes", LpConfig.Key.bushHarvestOverlay), x, y);
        y += row;
        add(prefBox("Felled logs", LpConfig.Key.logHarvestOverlay), x, y);
        y += row;
        add(prefBox("Stones (bumlings)", LpConfig.Key.stoneHarvestOverlay), x, y);
        y += row;
        add(prefBox("Old trunks", LpConfig.Key.oldtrunkHarvestOverlay), x, y);
        y += row;

        add(new Label("Auto-LP bot:") {{ setcolor(new Color(218, 163, 0)); }}, x, y + UI.scale(4));
        y += row;
        add(prefBox("Allow felling trees (only fully-harvested ones)", LpConfig.Key.autolpCutTrees), x, y);
        y += row;
        add(prefBox("Eat a consumable to register its seed", LpConfig.Key.autolpEatConsumables), x, y);
        y += row;

        add(new Label("Search radius (map units):"), x, y + UI.scale(4));
        TextEntry radiusEntry = new TextEntry(UI.scale(60), String.valueOf(LpConfig.radius())) {
            @Override
            public void activate(String text) {
                commitRadius(this);
            }
        };
        add(radiusEntry, x + UI.scale(170), y);
        y += row + UI.scale(6);

        add(new Button(UI.scale(280), "Reset LP log (this character)") {
            @Override
            public void click() {
                LpLog log = LpContext.log();
                if (log == null) {
                    gui.error("No character is logged in.");
                    return;
                }
                log.clearLpExplorer();
                gui.msg("LP-discovery log wiped for this character. Everything counts as undiscovered again.", Color.WHITE);
            }
        }, x, y);

        pack();
    }

    private void commitRadius(TextEntry entry) {
        try {
            int units = Integer.parseInt(entry.text().trim());
            if (units < 10 || units > 5000) {
                gui.error("LP search radius must be between 10 and 5000.");
                return;
            }
            LpConfig.radius(units);
            gui.msg("LP search radius set to " + units + ".", Color.WHITE);
        } catch (NumberFormatException e) {
            gui.error("LP search radius must be a number.");
        }
    }

    // Each pref checkbox, so tick() can keep its visual state in sync with LpConfig - a change
    // made elsewhere (the "Toggle LP Assistant" menu-grid button, or the quick toggle writing
    // the same lpassistent key) is reflected live instead of only when the window is reopened.
    private final Map<CheckBox, LpConfig.Key> boxes = new HashMap<>();

    private CheckBox prefBox(String label, LpConfig.Key key) {
        CheckBox box = new CheckBox(label) {
            @Override
            public void changed(boolean val) {
                LpConfig.set(key, val);
                // A discovery-affecting toggle should refresh markers immediately.
                LpExplorer.bumpGeneration();
            }
        };
        box.a = LpConfig.on(key);
        boxes.put(box, key);
        return box;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        for (Map.Entry<CheckBox, LpConfig.Key> e : boxes.entrySet()) {
            boolean live = LpConfig.on(e.getValue());
            if (e.getKey().a != live)
                e.getKey().a = live;
        }
    }

    @Override
    public void wdgmsg(haven.Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            hide();
            if (gui != null && gui.lpAssistantManager == this)
                gui.lpAssistantManager = null;
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }
}
