package haven.automated;

import haven.Composite;
import haven.Console;
import haven.Coord;
import haven.Coord2d;
import haven.Drawable;
import haven.GAttrib;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Moving;
import haven.ResDrawable;
import haven.Resource;
import haven.Utils;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * What the client knows about a resource or an object, for development: the two questions
 * brodgar-io-client answers with its ResourceStack and Inspector Gadget addons.
 * <pre>
 *   :resinfo gfx/terobjs/trees/oak     every layer of a resource: kind, id, image sizes, tooltip
 *   :gobinfo [id]                      one object (default: nearest to you): resource, position,
 *                                      tile, distance, facing, speed, attributes, overlays, poses
 *                                      and the raw state bytes the server drew it with
 * </pre>
 * Both print to the console and write the same lines to logs/, where they can be diffed.
 */
public class DevInspect {
    static {
        Console.setscmd("resinfo", (cons, args) -> {
            if (args.length < 2) {
                cons.out.println("usage: resinfo <resource name>");
                return;
            }
            out(cons, "resinfo", resinfo(args[1]));
        });
    }

    /** Registers :resinfo; :gobinfo is registered by GameUI, which is what knows the world. */
    public static void init() {}

    public static List<String> resinfo(String name) {
        List<String> o = new ArrayList<>();
        Resource r;
        try {
            r = Resource.remote().loadwait(name);
        } catch (RuntimeException e) {
            o.add(name + ": could not load: " + e);
            return o;
        }
        o.add(r.name + " v" + r.ver);
        for (Resource.Layer l : r.layers(Resource.Layer.class)) {
            StringBuilder b = new StringBuilder("  ").append(l.getClass().getSimpleName());
            try {
                if (l instanceof Resource.Image) {
                    Resource.Image img = (Resource.Image) l;
                    b.append(" id=").append(img.id).append(" z=").append(img.z)
                        .append(" sz=").append(img.sz).append(" o=").append(img.o);
                } else if (l instanceof Resource.Tooltip) {
                    b.append(" \"").append(((Resource.Tooltip) l).t).append('"');
                } else {
                    String s = String.valueOf(l);
                    if (!s.contains("@"))
                        b.append(' ').append(s);
                }
            } catch (RuntimeException e) {
                b.append(" <").append(e.getClass().getSimpleName()).append('>');
            }
            o.add(b.toString());
        }
        return o;
    }

    /** The :gobinfo command. With no id, the object nearest the player that is not the player. */
    public static void gobinfo(GameUI gui, Console cons, String[] args) {
        Gob pl = gui.map.player();
        Gob g = null;
        if (args.length > 1) {
            try {
                g = gui.map.glob.oc.getgob(Long.parseLong(args[1]));
            } catch (NumberFormatException e) {
                cons.out.println("gobinfo: not an id: " + args[1]);
                return;
            }
        } else if (pl != null) {
            double best = Double.MAX_VALUE;
            synchronized (gui.map.glob.oc) {
                for (Gob o : gui.map.glob.oc) {
                    if (o == pl || o.virtual)
                        continue;
                    double d = o.rc.dist(pl.rc);
                    if (d < best) {
                        best = d;
                        g = o;
                    }
                }
            }
        }
        if (g == null) {
            cons.out.println("gobinfo: no such object");
            return;
        }
        out(cons, "gobinfo", describe(g, pl));
    }

    public static List<String> describe(Gob g, Gob pl) {
        List<String> o = new ArrayList<>();
        o.add("id " + g.id + "  res " + safe(() -> {
            Resource r = g.getres();
            return (r == null) ? "-" : (r.name + " v" + r.ver);
        }));
        o.add("at " + g.rc + "  tile " + g.rc.floor(MCache.tilesz) + "  facing " + String.format("%.1f deg", Math.toDegrees(g.a))
            + ((pl != null && pl != g) ? String.format("  distance %.1f (%.1f tiles)", g.rc.dist(pl.rc), g.rc.dist(pl.rc) / MCache.tilesz.x) : ""));
        o.add("height " + safe(() -> String.format("%.2f", g.getc().z)));
        Moving mv = g.getattr(Moving.class);
        if (mv != null)
            o.add("moving: " + mv.getClass().getSimpleName() + safe(() -> String.format("  v=%.2f", mv.getv())));
        Drawable d = g.getattr(Drawable.class);
        if (d instanceof ResDrawable) {
            ResDrawable rd = (ResDrawable) d;
            o.add("state bytes: " + ((rd.sdt == null) ? "-" : hex(rd.sdt.fin())));
        } else if (d instanceof Composite) {
            Composite c = (Composite) d;
            o.add("poses: " + safe(() -> String.valueOf(c.poses)));
        }
        StringBuilder at = new StringBuilder("attributes:");
        for (GAttrib a : g.attr.values())
            at.append(' ').append(a.getClass().getSimpleName());
        o.add(at.toString());
        List<String> ols = new ArrayList<>();
        for (Gob.Overlay ol : g.ols)
            ols.add(safe(() -> (ol.spr == null || ol.spr.res == null) ? ("id " + ol.id) : ol.spr.res.name));
        o.add("overlays: " + (ols.isEmpty() ? "-" : String.join(", ", ols)));
        return o;
    }

    private interface Get {
        String get();
    }

    private static String safe(Get f) {
        try {
            return f.get();
        } catch (RuntimeException e) {
            return "<" + e.getClass().getSimpleName() + ">";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b)
            s.append(String.format("%02x ", x & 0xff));
        return s.toString().trim() + "  (" + b.length + " bytes)";
    }

    private static void out(Console cons, String what, List<String> lines) {
        for (String l : lines)
            cons.out.println(l);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        Path f = Paths.get("logs", what + "-" + stamp + ".txt");
        try {
            Files.createDirectories(f.getParent());
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(f, StandardCharsets.UTF_8))) {
                for (String l : lines)
                    w.println(l);
            }
            cons.out.println("-> " + f.toAbsolutePath());
        } catch (IOException e) {
            cons.out.println("could not write " + f + ": " + e);
        }
    }
}
