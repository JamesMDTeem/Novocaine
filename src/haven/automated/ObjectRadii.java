package haven.automated;

import haven.Console;
import haven.Utils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Circles of the player's own choosing: any object whose resource name ends with a given text
 * gets a circle of a given radius and colour (after brodgar-io-client's Object Radius Indicator).
 *
 * The built-in circles - dangerous beasts, troughs, bee skeps, mound beds - keep their own rules
 * in Gob (a knocked-out animal loses its circle, a bat or troll you are immune to loses its); this
 * list is for everything else, and draws on an overlay of its own so the two never fight.
 * <pre>
 *   :radius add /fox 60 #c0000080      suffix, radius in world units (a tile is 11), RGBA colour
 *   :radius rm /fox
 *   :radius list
 * </pre>
 * Kept in the settings file; objects already in view pick a change up when they next come into view.
 */
public class ObjectRadii {
    public static final class Entry {
        public final String suffix;
        public final float radius;
        public final Color col;

        Entry(String suffix, float radius, Color col) {
            this.suffix = suffix;
            this.radius = radius;
            this.col = col;
        }
    }

    private static final String PREF = "customObjectRadii";
    private static volatile List<Entry> entries = load();

    static {
        Console.setscmd("radius", (cons, args) -> command(cons, args));
    }

    public static void init() {}

    /** The entry for a resource name, or null. */
    public static Entry find(String resname) {
        if (resname == null)
            return null;
        for (Entry e : entries) {
            if (resname.endsWith(e.suffix))
                return e;
        }
        return null;
    }

    private static List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        for (String line : Utils.getpref(PREF, "").split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 3)
                continue;
            try {
                out.add(new Entry(f[0], Float.parseFloat(f[1]), parse(f[2])));
            } catch (RuntimeException e) {
                /* A line that no longer parses is dropped, not fatal. */
            }
        }
        return out;
    }

    private static void save(List<Entry> list) {
        StringBuilder b = new StringBuilder();
        for (Entry e : list)
            b.append(e.suffix).append(' ').append(e.radius).append(' ').append(fmt(e.col)).append('\n');
        Utils.setpref(PREF, b.toString());
        entries = list;
    }

    private static Color parse(String s) {
        String h = s.startsWith("#") ? s.substring(1) : s;
        if (h.length() == 6)
            h = h + "80";
        if (h.length() != 8)
            throw new IllegalArgumentException("colour must be #RRGGBB or #RRGGBBAA");
        long v = Long.parseLong(h, 16);
        return new Color((int) ((v >> 24) & 0xff), (int) ((v >> 16) & 0xff), (int) ((v >> 8) & 0xff), (int) (v & 0xff));
    }

    private static String fmt(Color c) {
        return String.format("#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    private static void command(Console cons, String[] args) {
        String verb = (args.length > 1) ? args[1] : "list";
        List<Entry> list = new ArrayList<>(entries);
        switch (verb) {
            case "add":
                if (args.length < 4) {
                    cons.out.println("usage: radius add <name suffix> <radius> [#RRGGBBAA]");
                    return;
                }
                Color col;
                float r;
                try {
                    r = Float.parseFloat(args[3]);
                    col = parse((args.length > 4) ? args[4] : "#ffd70080");
                } catch (RuntimeException e) {
                    cons.out.println("radius: " + e.getMessage());
                    return;
                }
                list.removeIf(e -> e.suffix.equals(args[2]));
                list.add(new Entry(args[2], r, col));
                save(list);
                cons.out.println("radius: " + args[2] + " -> " + r + " " + fmt(col) + " (applies as objects come into view)");
                break;
            case "rm":
                if (args.length < 3) {
                    cons.out.println("usage: radius rm <name suffix>");
                    return;
                }
                boolean gone = list.removeIf(e -> e.suffix.equals(args[2]));
                save(list);
                cons.out.println(gone ? ("radius: removed " + args[2]) : ("radius: no entry for " + args[2]));
                break;
            default:
                if (list.isEmpty())
                    cons.out.println("radius: no custom circles (radius add <suffix> <radius> [#RRGGBBAA])");
                for (Entry e : list)
                    cons.out.println(String.format("  %-28s %6.1f  %s", e.suffix, e.radius, fmt(e.col)));
        }
    }
}
