package haven.automated;

import haven.Console;
import haven.UI;
import haven.Widget;
import haven.Window;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

/**
 * A recorder for the UI protocol, for development: every message a widget sends to the server,
 * every update the server sends a widget, and every widget created, attached and destroyed.
 *
 * Modelled on brodgar-io-client's EventStack and WidgetStack addons, built natively because this
 * client has no addon layer. The question it answers is the one that comes up before every new
 * bot or helper: what does the game actually say when I do this by hand? Reading it off a live
 * session beats reading it out of decompiled resource code.
 *
 * Off by default, and off it costs the callers in {@code UI} one volatile read. On, each event is
 * formatted once into a fixed ring, so a long session keeps the most recent {@link #CAP} events and
 * nothing grows. Console:
 * <pre>
 *   :uitap on | off | clear       start, stop, or empty the ring
 *   :uitap dump [text]            write the ring (lines containing text) to logs/uitap-*.txt
 *   :wdgtree                      write the live widget tree to logs/wdgtree-*.txt (in game)
 * </pre>
 */
public class UiTap {
    /** Read by the hooks in UI on every message; the only cost when recording is off. */
    public static volatile boolean on = false;

    private static final int CAP = 20000;
    /** One argument list is cut here: a map grid or an inventory can carry kilobytes. */
    private static final int ARGMAX = 400;

    private static final String[] ring = new String[CAP];
    private static int head = 0, count = 0;
    private static final long t0 = System.nanoTime();

    static {
        Console.setscmd("uitap", (cons, args) -> command(cons, args));
    }

    /** Makes sure the console commands exist before anything has been recorded. */
    public static void init() {}

    /* ------------------------------------------------------------------ the hooks */

    /** A widget sent a message to the server. */
    public static void out(int id, Widget w, String msg, Object[] args) {
        record('>', id, w, msg, args);
    }

    /** The server sent a message to a widget, about to be dispatched. */
    public static void in(int id, Widget w, String msg, Object[] args) {
        record('<', id, w, msg, args);
    }

    /** A widget was created. {@code type} is the name the server asked for. */
    public static void created(int id, Widget w, Object type, Object[] cargs) {
        record('+', id, w, String.valueOf(type), cargs);
    }

    /** A widget was attached to its parent. */
    public static void added(int id, Widget w, int parent, Object[] pargs) {
        record('^', id, w, "@" + parent, pargs);
    }

    /** A widget is being destroyed. */
    public static void destroyed(int id, Widget w) {
        record('-', id, w, "", null);
    }

    private static void record(char kind, int id, Widget w, String msg, Object[] args) {
        String a = "";
        if (args != null && args.length > 0) {
            try {
                a = Arrays.deepToString(args);
            } catch (RuntimeException e) {
                a = "<" + e + ">";
            }
            if (a.length() > ARGMAX)
                a = a.substring(0, ARGMAX) + "...(" + a.length() + ")";
        }
        String line = String.format("%9.3f %c %5d %-22s %s %s",
            (System.nanoTime() - t0) / 1e9, kind, id, name(w), msg, a);
        synchronized (ring) {
            ring[head] = line;
            head = (head + 1) % CAP;
            if (count < CAP)
                count++;
        }
    }

    private static String name(Widget w) {
        if (w == null)
            return "-";
        String n = w.getClass().getName();
        return n.startsWith("haven.") ? n.substring(6) : n;
    }

    /* ------------------------------------------------------------------ the console */

    private static void command(Console cons, String[] args) {
        String verb = (args.length > 1) ? args[1] : "";
        switch (verb) {
            case "on":
                on = true;
                cons.out.println("uitap: recording (" + CAP + " most recent events kept)");
                break;
            case "off":
                on = false;
                cons.out.println("uitap: stopped, " + count + " events held");
                break;
            case "clear":
                synchronized (ring) {
                    Arrays.fill(ring, null);
                    head = count = 0;
                }
                cons.out.println("uitap: cleared");
                break;
            case "dump":
                String filter = (args.length > 2) ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
                dump(cons, filter);
                break;
            default:
                cons.out.println("usage: uitap on|off|clear|dump [text]   (" + (on ? "recording" : "off")
                    + ", " + count + " events held)");
        }
    }

    private static void dump(Console cons, String filter) {
        String[] lines;
        synchronized (ring) {
            lines = new String[count];
            int start = (head - count + CAP) % CAP;
            for (int i = 0; i < count; i++)
                lines[i] = ring[(start + i) % CAP];
        }
        Path f = file("uitap");
        int n = 0;
        try (PrintWriter w = writer(f)) {
            w.println("# time(s) kind id widget msg args   kinds: > to server, < from server, + created, ^ attached, - destroyed");
            for (String l : lines) {
                if (l != null && (filter == null || l.contains(filter))) {
                    w.println(l);
                    n++;
                }
            }
        } catch (IOException e) {
            cons.out.println("uitap: could not write " + f + ": " + e);
            return;
        }
        cons.out.println("uitap: " + n + " events -> " + f.toAbsolutePath());
    }

    /** The {@code :wdgtree} command. Registered by GameUI, which is what knows its UI. */
    public static void tree(UI ui, Console cons) {
        if (ui == null || ui.root == null) {
            cons.out.println("wdgtree: no UI to walk");
            return;
        }
        Path f = file("wdgtree");
        int[] n = {0};
        try (PrintWriter w = writer(f)) {
            w.println("# id class pos size [hidden] [caption]");
            synchronized (ui) {
                walk(ui, ui.root, 0, w, n);
            }
        } catch (IOException e) {
            cons.out.println("wdgtree: could not write " + f + ": " + e);
            return;
        }
        cons.out.println("wdgtree: " + n[0] + " widgets -> " + f.toAbsolutePath());
    }

    private static void walk(UI ui, Widget w, int depth, PrintWriter out, int[] n) {
        n[0]++;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++)
            b.append("  ");
        int id = ui.widgetid(w);
        b.append((id < 0) ? "local" : String.valueOf(id)).append(' ').append(name(w))
            .append(' ').append(w.c).append(' ').append(w.sz);
        if (!w.visible())
            b.append(" hidden");
        if (w instanceof Window && ((Window) w).cap != null)
            b.append(" \"").append(((Window) w).cap).append('"');
        out.println(b);
        for (Widget ch = w.child; ch != null; ch = ch.next)
            walk(ui, ch, depth + 1, out, n);
    }

    private static Path file(String what) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        return Paths.get("logs", what + "-" + stamp + ".txt");
    }

    private static PrintWriter writer(Path f) throws IOException {
        Files.createDirectories(f.getParent());
        return new PrintWriter(Files.newBufferedWriter(f, StandardCharsets.UTF_8));
    }
}
