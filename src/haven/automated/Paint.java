package haven.automated;

import haven.Button;
import haven.Clickable;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.GameUI;
import haven.HSlider;
import haven.Label;
import haven.Loading;
import haven.MCache;
import haven.MapView;
import haven.UI;
import haven.Utils;
import haven.VertexBuf;
import haven.Widget;
import haven.Window;
import haven.render.BaseColor;
import haven.render.Location;
import haven.render.Model;
import haven.render.Pipe;
import haven.render.RenderTree;
import haven.render.States;

import java.awt.Color;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Drawing on the ground with the mouse (after brodgar-io-client's Paint addon).
 *
 * For pointing at things with a crew watching the same patch of ground on a stream or in a
 * screenshot - "dig here", "the wall goes along this line" - where a map marker says where but not
 * what shape. A stroke is a flat ribbon laid on the terrain: it follows every slope and ridge, and
 * whatever stands on the ground hides it, your own character included. Nothing is sent to the
 * server; nobody else sees it, and it is gone at logout.
 *
 * Open the tool from the action menu (Novocaine > Paint) or {@code :paint}. With a pencil or the
 * eraser picked, a left drag on the map draws or rubs out instead of walking, and a right click puts
 * the tool down; with nothing picked the map behaves as usual.
 *
 * How it is drawn: each stroke is cut into short pieces, each its own small triangle strip whose
 * vertices sit a hair above the terrain height at that spot. Short pieces are what let the eraser
 * take out part of a stroke rather than all of it, and let a stroke appear while it is being drawn.
 * The ground under the pointer comes from the map's own hit test, which renders a click map; only
 * one is in flight at a time, so dragging fast costs a few tests a second rather than one per
 * mouse event.
 */
public class Paint {
    static final Color[] COLOURS = {
        new Color(230, 40, 40), new Color(255, 150, 20), new Color(250, 230, 40),
        new Color(60, 200, 60), new Color(50, 120, 255), new Color(245, 245, 245),
    };
    static final int ERASER = COLOURS.length;

    /** The picked tool: -1 for none, 0-5 a colour, {@link #ERASER}. */
    static volatile int tool = -1;
    /** Stroke width and eraser reach, in world units (a tile is 11). */
    static volatile double width = 2.0;

    private static MapView map = null;
    private static final List<Piece> pieces = new ArrayList<>();
    private static final List<Piece> pending = new ArrayList<>();
    private static final ConcurrentLinkedQueue<Coord2d> hits = new ConcurrentLinkedQueue<>();
    private static volatile boolean testing = false;
    private static boolean drawing = false;
    private static Coord lastc = null;
    /** The points of the piece being drawn, and the colour and width it started with. */
    private static List<Coord2d> cur = null;
    private static int curcol;
    private static double curw;
    private static Wnd wnd = null;

    /** Points per piece: short enough to erase finely and to show a stroke as it is drawn. */
    private static final int PIECE = 8;
    /** Longest step between ribbon vertices, in world units, so the strip follows the ground. */
    private static final double STEP = 2.0;

    public static boolean active() {
        return tool >= 0;
    }

    /** Opens or closes the tool window. */
    public static void toggle(GameUI gui) {
        if (wnd != null && wnd.parent != null) {
            wnd.reqdestroy();
            return;
        }
        wnd = gui.add(new Wnd(), Utils.getprefc("wndc-paint", UI.scale(new Coord(300, 200))));
    }

    /* ------------------------------------------------------------------ input, from MapView */

    public static boolean mousedown(MapView mv, Widget.MouseDownEvent ev) {
        if (!active())
            return false;
        if (ev.b == 3) {
            tool = -1;
            return true;
        }
        if (ev.b != 1)
            return false;
        attach(mv);
        drawing = true;
        cur = new ArrayList<>();
        curcol = tool;
        curw = width;
        lastc = null;
        test(mv, ev.c, true);
        return true;
    }

    public static void mousemove(MapView mv, Coord c) {
        if (!drawing)
            return;
        test(mv, c, false);
    }

    public static boolean mouseup(MapView mv, Widget.MouseUpEvent ev) {
        if (!drawing || ev.b != 1)
            return false;
        test(mv, ev.c, true);
        drawing = false;
        return true;
    }

    private static void test(MapView mv, Coord c, boolean force) {
        if (lastc != null && !force && lastc.dist(c) < UI.scale(3))
            return;
        if (testing && !force)
            return;
        lastc = c;
        testing = true;
        try {
            mv.new Maptest(c) {
                protected void hit(Coord pc, Coord2d mc) {
                    hits.add(mc);
                    testing = false;
                }

                protected void nohit(Coord pc) {
                    testing = false;
                }
            }.run();
        } catch (Loading e) {
            testing = false;
        }
    }

    /* ------------------------------------------------------------------ the drawing */

    private static void attach(MapView mv) {
        if (map != mv) {
            clear();
            map = mv;
        }
    }

    /** Called from GameUI.tick: turns the points the hit tests found into pieces. */
    public static void tick(GameUI gui) {
        if (map != null && gui.map != map) {
            /* A new session's map: the old drawing belonged to the old one. */
            synchronized (pieces) {
                pieces.clear();
                pending.clear();
            }
            map = null;
        }
        if (map == null)
            return;
        Coord2d mc;
        while ((mc = hits.poll()) != null) {
            if (cur == null)
                continue;
            if (curcol == ERASER) {
                erase(mc, curw);
                continue;
            }
            cur.add(mc);
            if (cur.size() >= PIECE) {
                emit();
                /* The next piece starts where this one ended, so the stroke has no gaps. */
                cur.add(mc);
            }
        }
        if (!drawing && cur != null) {
            if (cur.size() >= 1 && curcol != ERASER)
                emit();
            cur = null;
        }
        /* Pieces whose ground was still loading when they were made. */
        synchronized (pieces) {
            for (Iterator<Piece> i = pending.iterator(); i.hasNext(); ) {
                Piece p = i.next();
                if (p.place(map))
                    i.remove();
            }
        }
    }

    private static void emit() {
        Piece p = new Piece(new ArrayList<>(cur), COLOURS[curcol], curw);
        cur.clear();
        synchronized (pieces) {
            pieces.add(p);
            if (!p.place(map))
                pending.add(p);
        }
    }

    private static void erase(Coord2d at, double reach) {
        synchronized (pieces) {
            for (Iterator<Piece> i = pieces.iterator(); i.hasNext(); ) {
                Piece p = i.next();
                if (p.near(at, reach)) {
                    p.remove();
                    pending.remove(p);
                    i.remove();
                }
            }
        }
    }

    public static void clear() {
        synchronized (pieces) {
            for (Piece p : pieces)
                p.remove();
            pieces.clear();
            pending.clear();
        }
    }

    /** One short run of a stroke: a triangle strip laid on the ground. */
    private static final class Piece implements RenderTree.Node {
        final List<Coord2d> pts;
        final Color col;
        final double w;
        Model model;
        Coord3f origin;
        RenderTree.Slot slot;

        Piece(List<Coord2d> pts, Color col, double w) {
            this.pts = pts;
            this.col = col;
            this.w = w;
        }

        boolean near(Coord2d at, double reach) {
            double r = reach + (w / 2);
            for (Coord2d p : pts)
                if (p.dist(at) <= r)
                    return true;
            return false;
        }

        /** Builds the strip and adds it to the map's scene; false while the ground is loading. */
        boolean place(MapView mv) {
            if (slot != null)
                return true;
            List<Coord2d> path = subdivide(pts);
            MCache mc = mv.glob.map;
            int n = path.size();
            FloatBuffer pos = Utils.wfbuf(n * 2 * 3), nrm = Utils.wfbuf(n * 2 * 3);
            try {
                Coord2d o = path.get(0);
                float oz = (float) mc.getcz(o.x, o.y);
                origin = new Coord3f((float) o.x, -(float) o.y, oz);
                for (int i = 0; i < n; i++) {
                    Coord2d a = path.get(Math.max(i - 1, 0)), b = path.get(Math.min(i + 1, n - 1));
                    Coord2d t = b.sub(a);
                    double len = Math.hypot(t.x, t.y);
                    Coord2d nv = (len < 1e-6) ? Coord2d.of(0, 1) : Coord2d.of(-t.y / len, t.x / len);
                    for (int s = 0; s < 2; s++) {
                        Coord2d v = path.get(i).add(nv.mul((s == 0) ? (w / 2) : (-w / 2)));
                        /* A hair above the ground, so the strip wins the depth test against the
                         * terrain under it and loses it to anything standing on it. */
                        float z = (float) mc.getcz(v.x, v.y) + 0.35f - oz;
                        int k = (i * 2 + s) * 3;
                        pos.put(k, (float) (v.x - o.x)).put(k + 1, -(float) (v.y - o.y)).put(k + 2, z);
                        nrm.put(k, 0).put(k + 1, 0).put(k + 2, 1);
                    }
                }
            } catch (Loading e) {
                return false;
            }
            VertexBuf vbuf = new VertexBuf(new VertexBuf.VertexData(pos), new VertexBuf.NormalData(nrm));
            model = new Model(Model.Mode.TRIANGLE_STRIP, vbuf.data(), null);
            slot = mv.drawadd(this);
            return true;
        }

        private static List<Coord2d> subdivide(List<Coord2d> pts) {
            List<Coord2d> out = new ArrayList<>();
            out.add(pts.get(0));
            for (int i = 1; i < pts.size(); i++) {
                Coord2d a = pts.get(i - 1), b = pts.get(i);
                int steps = (int) Math.ceil(a.dist(b) / STEP);
                for (int s = 1; s <= steps; s++)
                    out.add(a.add(b.sub(a).mul((double) s / steps)));
            }
            if (out.size() == 1)
                /* A dot: a single click still leaves a mark. */
                out.add(pts.get(0).add(0.01, 0));
            return out;
        }

        public void added(RenderTree.Slot slot) {
            slot.ostate(Pipe.Op.compose(new BaseColor(col), new States.Facecull(States.Facecull.Mode.NONE),
                                        Clickable.notClickable, Location.xlate(origin)));
            slot.add(model);
        }

        void remove() {
            if (slot != null) {
                try {
                    slot.remove();
                } catch (RuntimeException e) {
                    /* the map went away first */
                }
                slot = null;
            }
        }
    }

    /* ------------------------------------------------------------------ the tool window */

    private static final class Wnd extends Window {
        Wnd() {
            super(Coord.z, "Paint", true);
            int cs = UI.scale(22), gap = UI.scale(4);
            for (int i = 0; i <= ERASER; i++)
                add(new Cell(i, cs), Coord.of(i * (cs + gap), 0));
            Label wl = add(new Label("Width"), Coord.of(0, cs + UI.scale(10)));
            HSlider sl = add(new HSlider(UI.scale(110), 1, 20, (int) Math.round(width * 2)) {
                public void changed() {
                    width = val / 2.0;
                }
            }, wl.pos("ur").adds(UI.scale(6), 0));
            add(new Button(UI.scale(60), "Clear", false).action(Paint::clear), sl.pos("ur").adds(UI.scale(8), -UI.scale(4)));
            add(new Label("Left-drag on the ground draws; right-click puts the tool down."), wl.pos("bl").adds(0, UI.scale(8)));
            pack();
        }

        public void wdgmsg(Widget sender, String msg, Object... args) {
            if ((sender == this) && msg.equals("close")) {
                reqdestroy();
                return;
            }
            super.wdgmsg(sender, msg, args);
        }

        public void destroy() {
            /* Closing the window puts the tool down; the drawing stays. */
            tool = -1;
            Utils.setprefc("wndc-paint", c);
            super.destroy();
        }
    }

    private static final class Cell extends Widget {
        final int idx;

        Cell(int idx, int sz) {
            super(Coord.of(sz, sz));
            this.idx = idx;
        }

        public void draw(GOut g) {
            boolean on = (tool == idx);
            g.chcolor(on ? Color.WHITE : new Color(40, 40, 40));
            g.frect(Coord.z, sz);
            Coord in = UI.scale(Coord.of(2, 2));
            if (idx == ERASER) {
                g.chcolor(new Color(140, 140, 140));
                g.frect(in, sz.sub(in.mul(2)));
                g.chcolor(new Color(60, 60, 60));
                g.line(in, sz.sub(in), UI.scale(2));
            } else {
                g.chcolor(COLOURS[idx]);
                g.frect(in, sz.sub(in.mul(2)));
            }
            g.chcolor();
        }

        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) {
                tool = (tool == idx) ? -1 : idx;
                return true;
            }
            return super.mousedown(ev);
        }

        public Object tooltip(Coord c, Widget prev) {
            return (idx == ERASER) ? "Eraser: drag over a stroke to rub it out" : "Pencil";
        }
    }
}
