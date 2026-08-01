package haven.automated.nbots.core;

import haven.FlowerMenu;
import haven.Widget;

import java.util.function.Supplier;

/**
 * Finding a widget in the live tree.
 *
 * Bots need this for exactly one reason: the flower menu. It is not a field on anything - the
 * server creates it as a child of the root when a right-click lands - so the only way to know
 * whether the click opened one is to look. Every place that right-clicks something therefore needs
 * the same recursive walk, and there were about to be three of them.
 */
public class Widgets {
    private Widgets() {}

    /** The first widget of this type anywhere under {@code root}, or null. */
    public static <T extends Widget> T find(Widget root, Class<T> cls) {
        if (root == null)
            return null;
        for (Widget w = root.child; w != null; w = w.next) {
            if (cls.isInstance(w))
                return cls.cast(w);
            T deep = find(w, cls);
            if (deep != null)
                return deep;
        }
        return null;
    }

    /**
     * Polls for a {@link FlowerMenu} to appear under {@code root}.
     *
     * Waits up to {@code maxPolls * pollMs} milliseconds, checking {@code running.get()} each
     * iteration so the caller can abort cleanly. Returns the first menu found, or {@code null}
     * if the timeout expires or the running flag becomes false.
     *
     * @param root the UI root to search under (typically {@code gui.ui.root})
     * @param running a supplier that returns true while the caller should keep waiting
     * @param maxPolls maximum poll iterations (each sleeps {@code pollMs})
     * @param pollMs milliseconds to sleep between polls
     * @return the FlowerMenu if found, otherwise null
     */
    public static FlowerMenu awaitFlowerMenu(Widget root, Supplier<Boolean> running,
                                             int maxPolls, int pollMs) throws InterruptedException {
        for (int i = 0; i < maxPolls; i++) {
            if (running == null || !running.get())
                throw new InterruptedException();
            FlowerMenu fm = find(root, FlowerMenu.class);
            if (fm != null)
                return fm;
            Thread.sleep(pollMs);
        }
        return null;
    }

    /** Convenience overload with the standard polling parameters (40 polls, 25ms = ~1 second). */
    public static FlowerMenu awaitFlowerMenu(Widget root, Supplier<Boolean> running)
            throws InterruptedException {
        return awaitFlowerMenu(root, running, 40, 25);
    }
}
