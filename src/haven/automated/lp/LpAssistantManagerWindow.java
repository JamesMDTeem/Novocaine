package haven.automated.lp;

import haven.Button;
import haven.CheckBox;
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
        super(UI.scale(300, 396), "LP Assistant Manager");
        this.gui = gui;

        int x = UI.scale(10);
        int y = UI.scale(2);
        int row = UI.scale(24);

        add(prefBox("Enable LP Assistant (markers & tracking)", LpConfig.Key.lpassistent), x, y);
        y += row;
        add(prefBox("Show chop hint on trees with undiscovered wood", LpConfig.Key.treeHarvestWood), x, y);
        y += row;
        add(prefBox("Ring animals that still owe a hide/bone/feather", LpConfig.Key.lpHuntTargets), x, y);
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
        radiusEntry = new TextEntry(UI.scale(60), String.valueOf(LpConfig.radius())) {
            @Override
            public void activate(String text) {
                commitRadius(this, true);
            }
        };
        add(radiusEntry, x + UI.scale(170), y);
        y += row + UI.scale(6);

        // Side by side, because they are each other's undo and reading them together is the point:
        // the reset is only safe to press BECAUSE the restore next to it exists.
        int half = UI.scale(136);
        add(new Button(half, "Reset LP log...") {
            @Override
            public void click() {
                askReset();
            }
        }, x, y);
        add(new Button(half, "Restore last reset") {
            @Override
            public void click() {
                askRestore();
            }
        }, x + half + UI.scale(8), y);

        pack();
    }

    /**
     * The reset is destructive in a way nothing else in this window is - it throws away discovery
     * history that can only be rebuilt by re-foraging every species - and it sits directly under a
     * column of harmless checkboxes, so a mis-aimed click used to wipe it outright. Confirm it, and
     * say how much is at stake rather than asking an abstract "are you sure".
     */
    private void askReset() {
        LpLog log = LpContext.log();
        if (log == null) {
            gui.error("No character is logged in.");
            return;
        }
        int n = log.size();
        if (n == 0) {
            gui.msg("This character's LP-discovery log is already empty.", Color.WHITE);
            return;
        }
        // Re-fetched inside the callback rather than closing over `log`: the dialog is not modal,
        // so a character switch between asking and confirming would otherwise wipe the log of a
        // character the question was never about.
        LpConfirm.ask(gui, "Reset LP log?", "Reset", () -> {
            LpLog live = LpContext.log();
            if (live == null) {
                gui.error("No character is logged in.");
                return;
            }
            boolean saved = live.clearLpExplorer();
            gui.msg("LP-discovery log wiped for this character. Everything counts as undiscovered"
                + " again." + (saved ? " Use \"Restore last reset\" to undo it." : ""), Color.WHITE);
        },
            "Forget all " + n + " recorded discoveries for this character?",
            "Everything counts as undiscovered again.",
            "A copy is kept, so this can be undone.");
    }

    /** Restoring is destructive in its own right - it drops anything found since the reset. */
    private void askRestore() {
        LpLog log = LpContext.log();
        if (log == null) {
            gui.error("No character is logged in.");
            return;
        }
        if (!log.hasBackup()) {
            gui.error("Nothing to restore - this character's LP log has not been reset.");
            return;
        }
        LpConfirm.ask(gui, "Restore LP log?", "Restore", () -> {
            LpLog live = LpContext.log();
            if (live == null) {
                gui.error("No character is logged in.");
                return;
            }
            int n = live.restoreBackup();
            if (n < 0)
                gui.error("Couldn't read the saved copy (details in logs/crash.log).");
            else
                gui.msg("Restored " + n + " discoveries from before the last reset.", Color.WHITE);
        },
            "Replace this character's LP log with the copy",
            "saved by the last reset?",
            "Anything discovered since that reset is dropped.");
    }

    private final TextEntry radiusEntry;

    // `loud` distinguishes the player pressing Enter (confirm it, and say so if it's rejected) from
    // the implicit commit when the window closes, where an unparseable leftover just means they
    // typed something and changed their mind - not worth an error message.
    private void commitRadius(TextEntry entry, boolean loud) {
        try {
            int units = Integer.parseInt(entry.text().trim());
            if (units < 10 || units > 5000) {
                if (loud)
                    gui.error("LP search radius must be between 10 and 5000.");
                return;
            }
            if (units == LpConfig.radius())
                return;
            LpConfig.radius(units);
            gui.msg("LP search radius set to " + units + ".", Color.WHITE);
        } catch (NumberFormatException e) {
            if (loud)
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
            // Every checkbox applies the moment it's clicked, so the radius field being the one
            // setting that needed Enter made it easy to type a number, close the window, and have
            // it silently discarded.
            commitRadius(radiusEntry, false);
            hide();
            if (gui != null && gui.lpAssistantManager == this)
                gui.lpAssistantManager = null;
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }
}
