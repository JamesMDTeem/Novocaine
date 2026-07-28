package haven.automated.nbots.core;

import haven.Widget;

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
}
