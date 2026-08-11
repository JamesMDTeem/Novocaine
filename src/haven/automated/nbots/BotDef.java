package haven.automated.nbots;

import haven.GameUI;
import haven.Window;

/**
 * One entry in the {@link BotRegistry}: what a bot is called, and how to open it.
 *
 * The point of describing bots as data is what it does to the wiring. Before this, adding a bot
 * cost two fields in GameUI, a makeLocal line and a hand-written else-if branch in
 * MenuGrid.useCustom - three edits to vendored upstream files per bot, which is both O(n) friction
 * and directly against this fork's "smallest possible edit to the upstream file" rule. With a
 * registry those files each get one generic piece of code that never changes again, and a new bot
 * is one line here plus its icon.
 *
 * {@code id} is the string the menu-grid resource passes through, so it has to match the .res
 * generated for it by tools/gen-menugrid-res.py.
 */
public class BotDef {
    public interface Factory {
        Window create(GameUI gui);
    }

    public final String id;
    public final String title;
    public final String description;
    public final Factory factory;
    private final String windowKey;

    public BotDef(String id, String title, String description, Factory factory) {
        this(id, title, description, null, factory);
    }

    /** A windowed bot that predates the registry may already remember its position under a custom key. */
    public BotDef(String id, String title, String description, String windowKey, Factory factory) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.windowKey = windowKey;
        this.factory = factory;
    }

    /** The preference key its window position is remembered under. */
    public String windowKey() {
        return windowKey != null ? windowKey : "wndc-nbot-" + id;
    }

    public String toString() {
        return "bot(" + id + ")";
    }
}
