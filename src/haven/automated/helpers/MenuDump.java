package haven.automated.helpers;

import haven.GameUI;
import haven.MenuGrid;
import haven.Resource;
import haven.automated.nbots.core.NLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes out every action the character's menu currently offers, once per session.
 *
 * There to answer a question that cannot be answered from source: what a menu button is actually
 * CALLED. Actions are invoked either by name ({@code gui.wdgmsg("act", "carry")}) or by matching a
 * menu resource ({@code "paginae/act/fish"}), and both need the exact string - which lives on the
 * server, arrives as a resource reference, and appears nowhere in this tree.
 *
 * Written for the hearth-fire travel button under Adventure, so the pather can offer teleporting
 * to the hearth when that beats walking. Guessing the string was the alternative, and a wrong guess
 * fires the wrong action on a live character; a dump costs one run and nothing else.
 *
 * Deliberately not wired to anything but a log. Nothing routes off this, and it is not a feature -
 * it exists to be read once and then to have its caller removed.
 */
public class MenuDump {
    /** So a bot restarted three times in a session writes this once, not three times. */
    private static boolean done = false;

    private MenuDump() {}

    public static synchronized void once(GameUI gui) {
        if (done)
            return;
        if ((gui == null) || (gui.menu == null))
            return;
        List<String> names = new ArrayList<>();
        int unresolved = 0;
        try {
            synchronized (gui.menu.paginae) {
                for (MenuGrid.Pagina pag : gui.menu.paginae) {
                    try {
                        Resource res = pag.res();
                        names.add((res == null) ? ("<null> id=" + pag.id) : res.name);
                    } catch (RuntimeException e) {
                        /* Includes Loading: a menu entry whose resource has not arrived yet. Counted
                         * rather than skipped silently, so a short list is not mistaken for a small
                         * menu - the whole point of this is completeness. */
                        unresolved++;
                    }
                }
            }
        } catch (RuntimeException e) {
            NLog.log("menu.log", "could not read the menu: " + e);
            return;
        }
        Collections.sort(names);
        done = true;
        NLog.log("menu.log", "=== every menu action this character offers ("
            + names.size() + " resolved, " + unresolved + " still loading) ===");
        for (String n : names)
            NLog.log("menu.log", "  " + n);
        NLog.log("menu.log", "=== end - look for the Adventure travel-to-hearth entry above ===");
        attrs(gui);
    }

    /**
     * Every character attribute the server has sent, with its values.
     *
     * The companion question to the menu one, and unanswerable the same way: travel weariness caps
     * how often the hearth-fire teleport may be used, so the pather has to read it before choosing
     * to travel - and {@code Glob.getcattr} only answers for a name you already know, CREATING a
     * zeroed entry for a name you guessed wrong. Listing what actually arrived is the only way to
     * learn the name.
     *
     * Base and comp both printed: an attribute that is a budget against a cap usually shows the
     * spent value in one and the ceiling in the other, and which way round is worth seeing rather
     * than assuming.
     */
    private static void attrs(GameUI gui) {
        if ((gui.ui == null) || (gui.ui.sess == null) || (gui.ui.sess.glob == null))
            return;
        List<String> out = new ArrayList<>();
        try {
            for (java.util.Map.Entry<String, haven.Glob.CAttr> e
                     : gui.ui.sess.glob.cattrs().entrySet()) {
                haven.Glob.CAttr a = e.getValue();
                out.add(String.format("  %-16s base=%-8d comp=%d", e.getKey(), a.base, a.comp));
            }
        } catch (RuntimeException e) {
            NLog.log("menu.log", "could not read the attributes: " + e);
            return;
        }
        Collections.sort(out);
        NLog.log("menu.log", "=== every character attribute the server has sent ("
            + out.size() + ") ===");
        for (String s : out)
            NLog.log("menu.log", s);
        NLog.log("menu.log", "=== end - look for a travel/weariness budget above ===");
    }
}
