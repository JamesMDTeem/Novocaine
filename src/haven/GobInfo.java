package haven;

import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;

public abstract class GobInfo extends GAttrib implements RenderTree.Node, PView.Render2D {
    protected Tex tex;
    public Coord3f pos = new Coord3f(0, 0, 1);
    protected final Object texLock = new Object();
    protected Pair<Double, Double> center = new Pair<>(0.5, 1.0);
    protected boolean dirty = true;

    /**
     * Minimum seconds between {@link #ctick} passes, from the "Gob info tick interval" slider.
     * Zero means every frame. Cached in a field the way GLEnvironment.cachedDisposeCap and
     * Composited.cachedAnimSkip are, because this is read once per gob per frame and a
     * preferences lookup is a synchronized call, not a field read.
     */
    public static volatile double cachedTickInterval =
	Utils.getprefd("perf.gob_info_tick_interval", 0.25);

    /**
     * Where in the interval this instance sits, as a fraction. Fixed per gob.
     *
     * Every gob in view ticks from the same loop, so leaving them in phase would have the whole
     * screen come due on the same frame - two thousand at once in a built-up base - turning a
     * steady per-frame cost into a periodic spike. That would trade a small constant cost for a
     * visible hitch, which is worse than what the throttle replaced.
     *
     * A fraction rather than a fixed number of seconds because the offset only spreads anything
     * if it is scaled to the interval actually in force: seeded against the widest interval the
     * slider offers, three quarters of the gobs come out already overdue at the narrowest one and
     * fire together on the first frame anyway. Measured at 1496 of 2000 on frame one before this
     * was scaled - the exact spike the stagger exists to prevent.
     */
    private final double phase = Math.random();

    /** Seconds since this one last ticked. Seeded on the first tick against the live interval. */
    private double since = 0;

    /**
     * The interval this instance is currently spread against.
     *
     * Phases settled under one interval are meaningless under another: after running at 1/sec,
     * every gob's offset is spread across a whole second, and narrowing the slider to 4/sec leaves
     * three quarters of them instantly overdue - 1503 of 2000 refreshing on the frame the setting
     * changed. Noticing the change and re-spreading costs one comparison per gob per frame and
     * turns that into nothing.
     */
    private double lastInterval = -1;

    public GobInfo(Gob owner) {
        super(owner);
    }

    protected abstract boolean enabled();

    protected void up(float up) {
        pos = new Coord3f(0, 0, up);
    }

    @Override
    public void ctick(double dt) {
        /* Gated before the monitor, which is the point of the setting. In the steady state this
         * method has nothing to do - the texture is built and not dirty - but it still took
         * texLock and asked enabled() for every gob on screen on every frame, which at two
         * thousand gobs and a hundred frames a second is two hundred thousand monitor
         * acquisitions a second to answer "no". */
        double iv = cachedTickInterval;
        if(iv > 0) {
            if(iv != lastInterval) {
                /* First tick, or the slider moved. Either way the offset has to be re-scaled to
                 * the interval now in force before it means anything. */
                lastInterval = iv;
                since = phase * iv;
            }
            since += dt;
            if(since < iv)
                return;
            /* Subtract rather than zero, so the offset this instance started with survives and
             * the gobs stay spread across frames instead of bunching up after their first tick. */
            since -= iv;
            if(since > iv)
                /* More than a whole interval behind: a long stall, an alt-tab, or the slider
                 * moved to a shorter interval. Re-spread rather than zeroing - zeroing here is
                 * what would collect every gob into the same phase the first time the client
                 * hitched, and quietly undo the stagger for the rest of the session. */
                since = phase * iv;
        }
        synchronized (texLock) {
            if(enabled() && dirty && tex == null) {
                tex = render();
                dirty = false;
            }
        }
    }

    @Override
    public void draw(GOut g, Pipe state) {
        if (!GameUI.showUI)
            return;
        synchronized (texLock) {
            if(enabled() && tex != null) {
                Coord sc = Homo3D.obj2sc(pos, state, Area.sized(g.sz()));
                if(sc == null) {return;}
                sc.y = sc.y + UI.scale(17);
                if(sc.isect(Coord.z, g.sz())) {
                    g.aimage(tex, sc, center.a, center.b);
                }
            }
        }
    }

    protected abstract Tex render();

    public void clear() {
        synchronized(texLock) {
            if(tex != null) {
                tex.dispose();
                tex = null;
            }
        }
        dirty = true;
    }

    public void dispose() {
        clear();
    }
}
