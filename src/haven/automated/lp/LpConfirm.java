package haven.automated.lp;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.UI;
import haven.Widget;
import haven.Window;

/**
 * A small yes/no confirmation window.
 *
 * It exists for the LP log reset, which is the one button in the LP assistant that destroys
 * something the player can't get back by playing: a character's whole discovery history, which can
 * be weeks of foraging. It sat one stray click below a column of harmless checkboxes.
 *
 * Modelled on the confirm window GobIcon's preset-overwrite already uses (parented to the GameUI,
 * centred, both buttons close it) rather than introducing a dialog framework the rest of the client
 * doesn't have. Not truly modal - the client has no modality primitive - but the action only runs
 * from the confirm button, so ignoring the window is the same as cancelling it.
 */
public class LpConfirm extends Window {
    private final Runnable onConfirm;

    private LpConfirm(String title, String[] lines, String confirmText, Runnable onConfirm) {
        super(UI.scale(320, 66 + 16 * lines.length), title);
        this.onConfirm = onConfirm;

        int y = UI.scale(4);
        for (String line : lines) {
            add(new Label(line), UI.scale(10), y);
            y += UI.scale(16);
        }
        y += UI.scale(10);

        add(new Button(UI.scale(140), confirmText) {
            @Override
            public void click() {
                // Destroy first: the action reports its own result through the chat log, and a
                // dialog still sitting there afterwards reads as if nothing happened.
                LpConfirm.this.reqdestroy();
                LpConfirm.this.onConfirm.run();
            }
        }, UI.scale(10), y);
        add(new Button(UI.scale(140), "Cancel") {
            @Override
            public void click() {
                LpConfirm.this.reqdestroy();
            }
        }, UI.scale(170), y);

        pack();
    }

    /**
     * Puts the question on screen, centred over the game UI. `lines` is the body text, one label
     * per line - the client has no wrapping label, so callers break their own lines.
     */
    public static void ask(GameUI gui, String title, String confirmText, Runnable onConfirm,
                           String... lines) {
        if (gui == null)
            return;
        LpConfirm w = new LpConfirm(title, lines, confirmText, onConfirm);
        gui.add(w, new Coord((gui.sz.x - w.sz.x) / 2, (gui.sz.y - w.sz.y) / 2));
        w.show();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }
}
