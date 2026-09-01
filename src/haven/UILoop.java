/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import haven.render.*;
import haven.iosys.audio.*;
import haven.iosys.tk.*;
import java.awt.image.BufferedImage;
import haven.GSettings.SyncMode;
import haven.render.gl.GLEnvironment;
import haven.render.gl.GLRender;

public abstract class UILoop implements Console.Directory {
    public static final Config.Variable<Boolean> dbtext = Config.Variable.propb("haven.dbtext", false);
    public static final Config.Variable<Boolean> profile = Config.Variable.propb("haven.profile", false);
    public final Windeye wnd;
    public final Thread th;
    public final CPUProfile uprof = new CPUProfile(300), rprof = new CPUProfile(300);
    public final GPUProfile gprof = new GPUProfile(300);
    public Environment env;
    public UI ui;
    private final Cursor.Caps curscaps;
    private final Object uilock = new Object();
    private UI lockedui;
    private long frameno = 0;
    public static boolean showFramerate = Utils.getprefb("showFramerate", true);

    public UILoop(Windeye wnd) {
	this.wnd = wnd;
	wnd.drophandler(new Dropper());
	setenv(wnd.env());
	this.curscaps = wnd.toolkit().cursorcaps();
	newui(null);
	this.th = new HackThread(this::run, "Haven UI thread");
    }

    /* Input is otherwise drained exactly once per rendered frame, so the
     * queue does not move at all while a frame is stalled - a second of
     * stalled presentation is a second in which nothing the player does
     * reaches a widget, and the batch that finally lands does so against a
     * world that has since moved on. This drains it on its own cadence
     * instead, so input keeps flowing at a steady rate regardless of what
     * the frame time is doing.
     *
     * It drains through the same dispatch() the frame loop uses, not a
     * clicks-only variant: mouse motion and button presses have to stay in
     * the order they were made, or a press can be delivered before the
     * motion that says where the pointer was when it happened. Both this and
     * the frame loop drain from inside synchronized(ui), which is what keeps
     * the two from interleaving a batch - the queue is only ever read by a
     * thread holding that monitor, so batches stay whole and ordered.
     *
     * Running UI work off the render thread under the ui monitor is what the
     * click readback in MapView.checkmapclick already does from a GL callback
     * thread; the invariant being kept is the monitor, not the thread. */
    private static final int INPUT_PUMP_MS = 8;
    private volatile Thread inputThread = null;

    private void startInputPump() {
	if(inputThread != null)
	    return;
	Thread it = new HackThread(() -> {
		try {
		    while(!Thread.interrupted()) {
			UI cui;
			synchronized(uilock) {
			    cui = this.ui;
			}
			if(cui != null) {
			    synchronized(cui) {
				dispatch(cui);
			    }
			}
			Thread.sleep(INPUT_PUMP_MS);
		    }
		} catch(InterruptedException e) {
		    /* Normal shutdown. */
		}
	    }, "Haven input pump");
	it.setDaemon(true);
	inputThread = it;
	it.start();
    }

    private void stopInputPump() {
	Thread it = inputThread;
	if(it != null) {
	    inputThread = null;
	    it.interrupt();
	    try {
		it.join(1000);
	    } catch(InterruptedException e) {
		Thread.currentThread().interrupt();
	    }
	}
    }

    public void start() {
	this.th.start();
	startInputPump();
    }

    private void setenv(Environment env) {
	this.env = env;
	if(ui != null)
	    ui.env = env;
	haven.error.ErrorHandler errh = haven.error.ErrorHandler.find();
	if(errh != null) {
	    Environment.Caps caps = env.caps();
	    errh.lsetprop("tk.desc", wnd.toolkit().description());
	    errh.lsetprop("gl.vendor", caps.vendor());
	    errh.lsetprop("gl.version", caps.driver());
	    errh.lsetprop("gl.renderer", caps.device());
	    errh.lsetprop("render.caps", caps);
	}
    }

    private Audio.Root audio = null;
    public UI newui(UI.Runner fun) {
	if(audio == null)
	    audio = new Audio.Root(audiosink());
	UI prevui, newui = new UI(wnd, audio, new Coord(wnd.size()), fun);
	newui.env = this.env;
	newui.cons.add(this);
	synchronized(uilock) {
	    prevui = this.ui;
	    this.ui = newui;
	    ui.root.guprof = uprof;
	    ui.root.grprof = rprof;
	    ui.root.ggprof = gprof;
	    while((this.lockedui != null) && (this.lockedui == prevui)) {
		try {
		    uilock.wait();
		} catch(InterruptedException e) {
		    Thread.currentThread().interrupt();
		    break;
		}
	    }
	}
	if(prevui != null) {
	    prevui.destroy();
	}
	return(newui);
    }

    /* XXX: Move to UI? */
    private Object prevtooltip = null;
    private Indir<Tex> prevtooltex = null;
    private Disposable freetooltex = null;
    private void drawtooltip(UI ui, GOut g) {
	Object tooltip;
	synchronized(ui) {
	    tooltip = ui.tooltip(ui.mc);
	}
	Indir<Tex> tt = null;
	if(Utils.eq(tooltip, prevtooltip)) {
	    tt = prevtooltex;
	} else {
	    if(freetooltex != null) {
		freetooltex.dispose();
		freetooltex = null;
	    }
	    prevtooltip = null;
	    prevtooltex = null;
	    Disposable free = null;
	    if(tooltip != null) {
		if(tooltip instanceof Text) {
		    Tex t = ((Text)tooltip).tex();
		    tt = () -> t;
		} else if(tooltip instanceof Tex) {
		    Tex t = (Tex)tooltip;
		    tt = () -> t;
		} else if(tooltip instanceof Indir<?>) {
		    @SuppressWarnings("unchecked")
			Indir<Tex> c = (Indir<Tex>)tooltip;
		    tt = c;
		} else if(tooltip instanceof String) {
		    if(((String)tooltip).length() > 0) {
			Tex r = new TexI(Text.render((String)tooltip).img, false);
			tt = () -> r;
			free = r;
		    }
		}
	    }
	    prevtooltip = tooltip;
	    prevtooltex = tt;
	    freetooltex = free;
	}
	Tex tex = (tt == null) ? null : tt.get();
	if(tex != null) {
	    Coord sz = tex.sz();
	    Coord pos = ui.mc.sub(sz).sub(curshotspot);
	    /* Right and bottom edges before the left and top ones below: a tooltip wider than
	     * the cursor position allows was drawn off the side of the window entirely. */
	    Coord lim = (ui.root == null) ? null : ui.root.sz;
	    if(lim != null) {
		if(pos.x + sz.x > lim.x)
		    pos.x = lim.x - sz.x;
		if(pos.y + sz.y > lim.y)
		    pos.y = lim.y - sz.y;
	    }
	    if(pos.x < 0)
		pos.x = 0;
	    if(pos.y < 0)
		pos.y = 0;
	    Coord br = pos.add(sz);
	    Coord m = UI.scale(3, 3);
	    g.chcolor(255, 195, 0, 210); // ND: This is the tooltip border color
	    g.rect2(pos.sub(m).sub(1, 1), br.add(m));
	    g.chcolor(5, 5, 5, 230);
	    g.frect2(pos.sub(m), br.add(m));
	    g.chcolor();
	    g.image(tex, pos);
	}
	ui.lasttip = tooltip;
    }

    private final Map<Resource, Cursor> cursors = new WeakHashMap<>();
    private final Map<Cursor, Coord> curshotspots = new WeakHashMap<>();
    private Object lastcursor = null;
    private Coord curshotspot = Coord.z;
    protected void drawcursor(UI ui, GOut g) {
	Object curs;
	synchronized(ui) {
	    curs = ui.getcurs(ui.mc);
	}
	if(curs instanceof Resource) {
	    Resource res = (Resource)curs;
	    if(curscaps == null) {
		if(!(lastcursor instanceof Resource))
		    wnd.cursor(Cursor.Std.NONE);
		curshotspot = UI.scale(res.flayer(Resource.negc).cc);
		Coord dc = ui.mc.sub(curshotspot);
		g.image(res.flayer(Resource.imgc), dc);
	    } else {
		if(curs != lastcursor) {
		    Cursor tkc = cursors.get(res);
		    if(tkc == null) {
			Coord hotspot = res.flayer(Resource.negc).cc;
			BufferedImage img = res.flayer(Resource.imgc).img;
			Coord sz = PUtils.imgsz(img);
			Coord tsz;
			if(curscaps.pref != 0) {
			    tsz = sz.mul(curscaps.pref).div(sz.max());
			} else {
			    tsz = UI.scale(sz);
			    if((tsz.x > curscaps.max) || (tsz.y > curscaps.max))
				tsz = tsz.mul(curscaps.max).div(tsz.max());
			}
			if(!Utils.eq(tsz, sz)) {
			    img = PUtils.uiscale(img, tsz);
			    hotspot = hotspot.mul(tsz).div(sz);
			}
			cursors.put(res, tkc = wnd.toolkit().makecursor(img, hotspot));
			curshotspots.put(tkc, hotspot);
		    }
		    curshotspot = curshotspots.get(tkc);
		    wnd.cursor(tkc);
		}
	    }
	} else if(curs instanceof Cursor.Std) {
	    if(curs != lastcursor)
		wnd.cursor((Cursor.Std)curs);
	} else {
	    if(curs != lastcursor)
		Warning.warn("unexpected cursor specification: %s", curs);
	}
	lastcursor = curs;
    }

    private long prevfree = 0, framealloc = 0;
    protected void statlines(Collection<String> buf, UI ui) {
	buf.add(String.format("FPS: %d (%d%% idle, latency %.2f ms)", fps, (int)(uidle * 100.0), framelag * 1000));
	Runtime rt = Runtime.getRuntime();
	long free = rt.freeMemory(), total = rt.totalMemory();
	if(free < prevfree)
	    framealloc = ((prevfree - free) + (framealloc * 19)) / 20;
	prevfree = free;
	buf.add(String.format("Mem: %,011d/%,011d/%,011d/%,011d (%,d)", free, total - free, total, rt.maxMemory(), framealloc));
	buf.add(String.format("State slots: %d", State.Slot.numslots()));
	Environment env = ui.getenv();
	if(env instanceof GLEnvironment) {
	    GLEnvironment gl = (GLEnvironment)env;
	    buf.add(String.format("GL progs: %d", gl.numprogs()));
	    buf.add(String.format("V-Mem: %s", gl.memstats()));
	}
	@SuppressWarnings("deprecation") MapView map = ui.root.findchild(MapView.class);
	if((map != null) && (map.back != null)) {
	    buf.add(String.format("Camera: %s", map.camstats()));
	    buf.add(String.format("Mapview: %s", map.stats()));
	    // buf.add(String.format("Click: Map: %s, Obj: %s", map.clmaplist.stats(), map.clobjlist.stats()));
	}
	if((ui.sess != null) && (ui.sess.conn instanceof Connection))
	    buf.add(String.format("Connection: %s", ((Connection)ui.sess.conn).stats));
	buf.add(String.format("Async: L %s, D %s", ui.loader.stats(), Defer.gstats()));
	int rqd = Resource.local().qdepth() + Resource.remote().qdepth();
	if(rqd > 0)
	    buf.add(String.format("RQ depth: %d (%d)", rqd, Resource.local().numloaded() + Resource.remote().numloaded()));
	wnd.stats(buf);
    }

    private void drawstats(UI ui, GOut g, Render buf) {
	Collection<String> lines = new ArrayList<>();
	statlines(lines, ui);
	synchronized(Debug.framestats) {
	    Debug.framestats.forEach(s -> lines.add(String.valueOf(s)));
	}
	int y = g.sz().y - UI.scale(190), dy = FastText.h;
	for(String ln : lines)
	    FastText.aprint(g, new Coord(10, y -= dy), 0, 1, ln);
    }

    protected Pipe basestate() {
	Pipe base = new BufPipe();
	base.prep(wnd.fbstate());
	return(base);
    }

    private void display(UI ui, Render buf) {
	Pipe base = basestate();
	base.prep(FragColor.blend(new BlendMode()));
	Area wnd = Area.sized(ui.root.sz);
	base.prep(new States.Viewport(wnd)).prep(new Ortho2D(wnd));
	base.prep(new FrameInfo());
	buf.clear(base, FragColor.fragcol, FColor.BLACK);
	GOut g = new GOut(buf, base, wnd.sz());
	synchronized(ui) {
	    ui.draw(g);
	}
    if (showFramerate) {
        FastText.aprintfstroked(g, new Coord(g.sz().x - UI.scale(50), UI.scale(15)), 0, 1, "FPS: " + fps);
    }
	if(dbtext.get())
	    drawstats(ui, g, buf);
	drawtooltip(ui, g);
	drawcursor(ui, g);
    }

    public static class Fence implements Runnable, Abortable {
	private int state = 0;

	public void run() {
	    synchronized(this) {
		state = 1;
		notifyAll();
	    }
	}

	public void abort() {
	    synchronized(this) {
		state = 2;
		notifyAll();
	    }
	}

	public boolean waitfor() throws InterruptedException {
	    synchronized(this) {
		while(state == 0)
		    wait();
		return(state == 1);
	    }
	}
    }

    public static class RenderProfile implements Runnable {
	private final CPUProfile prof;
	private RenderProfile prev;
	private CPUProfile.Frame frame;

	public RenderProfile(CPUProfile prof, RenderProfile prev, Render out) {
	    this.prof = prof;
	    this.prev = prev;
	    out.fence(this);
	}

	public void run() {
	    if(prev != null) {
		if(prev.frame != null) {
		    /* The reason frame would be null is if the
		     * environment has become invalid and the previous
		     * cycle never ran. */
		    prev.frame.fin();
		}
		prev = null;
	    }
	    frame = prof.new Frame();
	}

	public class Part implements Runnable {
	    private final String label;

	    public Part(String label, Render out) {
		this.label = label;
		out.fence(this);
	    }

	    public void run() {
		if(frame != null)
		    frame.part(label);
	    }
	}
    }

    protected class Dropper implements DropHandler {
	public Action drophover(DropHoverEvent ev) {
	    if(DropTarget.drophover(ui.root, ev.wndc(), SystemDrop.of(ev)))
		return(DropHandler.Action.COPY);
	    return(null);
	}
	public boolean dropped(DroppedEvent ev) {
	    return(DropTarget.dropthing(ui.root, ev.wndc(), SystemDrop.of(ev)));
	}
    }

    protected abstract void dispatch(UI ui);

    protected AudioSystem.SinkLine audiosink() {
	return(DummyAudio.DummySink.instance);
	// return(AudioSystem.instance().sinkline(Audio.defspec()));
    }

    protected boolean bgmode() {
	return(false);
    }

    protected double framedur() {
	GSettings gp = this.ui.gprefs;
	double hz = gp.hz.val, bghz = gp.bghz.val;
	if(bgmode()) {
	    if(bghz != Double.POSITIVE_INFINITY)
		return(1.0 / bghz);
	}
	if(hz == Double.POSITIVE_INFINITY)
	    return(0.0);
	return(1.0 / hz);
    }

    private final double[] frames = new double[128], waited = new double[frames.length];
    public static int fps;
    private double framelag, uidle;
    protected void updstats(Frame f) {
	int fi = (int)(f.frameno % frames.length);
	frames[fi] = f.ftime;
	waited[fi] = f.waited;
	double twait = 0;
	int i = 0, ckf = fi;
	for(; i < frames.length - 1; i++) {
	    twait += waited[ckf];
	    if(f.ftime - frames[ckf] > 1)
		break;
	    ckf = (ckf - 1 + frames.length) % frames.length;
	}
	if(f.ftime > frames[ckf]) {
	    fps = (int)Math.round(i / (f.ftime - frames[ckf]));
	    uidle = twait / (f.ftime - frames[ckf]);
	}
    }

    protected void framedone(Frame f) {
	updstats(f);
    }

    public static class Frame {
	public final UILoop loop;
	public final long frameno;
	public final UI ui;
	public final Render out;
	public final Fence sync = new Fence();
	public Frame prev;
	public CPUProfile.Current prof = null;
	public GPUProfile.Frame gprof = null;
	public RenderProfile rprofc = null;
	public double ttime, ftime, waited;

	public Frame(UILoop loop, UI ui, Render out, Frame prev) {
	    this.loop = loop;
	    this.frameno = loop.frameno++;
	    this.ui = ui;
	    this.out = out;
	    this.prev = prev;
	}

	protected void tick() {
	    synchronized(ui) {
		CPUProfile.phase(prof, "dwait");
		if(rprofc != null) rprofc.new Part("tick", out);
		if(gprof  != null) gprof.part(out, "tick");
		/* Kept here as well as on the input pump: whatever arrived
		 * between the pump's last pass and this frame is dispatched
		 * before the frame ticks on it, so a frame never draws from
		 * input it has not yet seen. */
		loop.dispatch(ui);
		CPUProfile.phase(prof, "stick");
		if(ui.sess != null) {
		    ui.sess.glob.ctick();
		    ui.sess.glob.gtick(out);
		}
		CPUProfile.phase(prof, "utick");
		ui.tick();
		ui.gtick(out);
		ui.mousehover(ui.mc);
		Coord sz = loop.wnd.size();
		if(!ui.root.sz.equals(sz))
		    ui.root.resize(sz);
	    }
	}

	protected void display() {
	    CPUProfile.phase(prof, "draw");
	    if(rprofc != null) rprofc.new Part("draw", out);
	    if(gprof  != null) gprof.part(out, "draw");
	    loop.display(ui, out);
	}

	protected void swapbuffers() {
	    if(rprofc != null) rprofc.new Part("swap", out);
	    if(gprof  != null) gprof.part(out, "swap");
	    loop.wnd.swapbuffers(out, ui.gprefs.vsync.val);
	    out.fence(() -> loop.framelag = Utils.rtime() - ttime);
	    if(gprof  != null) gprof.fin(out);
	}

	protected void fin() throws InterruptedException {
	    CPUProfile.phase(prof, "wait");
	    double now = Utils.rtime();
	    double fd = loop.framedur();
	    if((prev != null) && (prev.ftime + fd > now)) {
		this.ftime = prev.ftime + fd;
		long nanos = (long)((this.ftime - now) * 1e9);
		Thread.sleep(nanos / 1000000, (int)(nanos % 1000000));
		waited += this.ftime - now;
	    } else {
		this.ftime = now;
	    }
	    CPUProfile.end(prof);
	}

	protected void syncwait() throws InterruptedException {
	    CPUProfile.phase(prof, "dwait");
	    if(prev != null) {
		double then = Utils.rtime();
		prev.sync.waitfor();
		waited += Utils.rtime() - then;
	    }
	}

	public void run() throws InterruptedException {
	    this.prof   = profile.get() ? CPUProfile.set(loop.uprof.new Frame()) : null;
	    this.gprof  = profile.get() ? loop.gprof.new Frame(out) : null;
	    this.rprofc = profile.get() ? new RenderProfile(loop.rprof, (prev == null) ? null : prev.rprofc, out) : null;
	    SyncMode syncmode = ui.gprefs.syncmode.val;
	    boolean swapsync = (syncmode != SyncMode.FRAME);
	    boolean tickwait = (syncmode == SyncMode.FRAME) || (syncmode == SyncMode.TICK);

	    if(!swapsync) out.fence(sync);
	    if(!tickwait) syncwait();
	    ttime = Utils.rtime();
	    tick();
	    if(tickwait) syncwait();
	    display();
	    CPUProfile.phase(prof, "aux");
	    swapbuffers();
	    if(swapsync) out.fence(sync);
	}
    }

    public static class GLFrame extends Frame {
	public final GLRender gl;
	private final haven.render.gl.BufferBGL.Profile frameprof = false ? new haven.render.gl.BufferBGL.Profile() : null;

	public GLFrame(UILoop loop, UI ui, GLRender out, Frame prev) {
	    super(loop, ui, out, prev);
	    this.gl = out;
	    if(frameprof != null) gl.submit(frameprof.start);
	}

	protected void swapbuffers() {
	    super.swapbuffers();
	    if(ui.gprefs.syncmode.val == SyncMode.FINISH) {
		if(rprofc != null) rprofc.new Part("finish", out);
		gl.finish();
	    }
	    if(frameprof != null) {
		gl.submit(frameprof.stop);
		gl.submit(frameprof.dump(Utils.path("frameprof")));
	    }
	}
    }

    protected Frame frame(UI ui, Render out, Frame prev) {
	if(out instanceof GLRender)
	    return(new GLFrame(this, ui, (GLRender)out, prev));
	return(new Frame(this, ui, out, prev));
    }

    private void run() {
	Render buf = null;
	try {
	    Frame prevframe = null;
	    double then = Utils.rtime();
	    while(true) {
		Environment env = wnd.env();
		if(env != this.env)
		    setenv(env);
		buf = env.render();
		try {
		    UI ui;
		    synchronized(uilock) {
			this.lockedui = ui = this.ui;
			uilock.notifyAll();
		    }
		    Debug.cycle(ui.modflags());
		    haven.automated.LeakDbg.tick(ui);

		    Frame curframe = frame(ui, buf, prevframe);
		    prevframe = null;
		    curframe.run();
		    env.submit(buf); buf = null;
		    curframe.fin();

		    framedone(curframe);
		    (prevframe = curframe).prev = null;
		} finally {
		    if(buf != null)
			buf.dispose();
		}
	    }
	} catch(InterruptedException e) {
	} finally {
	    synchronized(uilock) {
		lockedui = null;
		uilock.notifyAll();
	    }
	}
    }

    public void dispose() {
	stopInputPump();
	th.interrupt();
	try {
	    th.join(5000);
	} catch(InterruptedException e) {
	    Thread.currentThread().interrupt();
	}
	if(th.isAlive())
	    Warning.warn("ui thread failed to terminate");
    }

    private Map<String, Console.Command> cmdmap = new TreeMap<String, Console.Command>();
    {
	cmdmap.put("stats", (cons, args) -> {
	    dbtext.set(Utils.parsebool(args[1]));
	});
	cmdmap.put("profile", (cons, args) -> {
	    profile.set(Utils.parsebool(args[1]));
	});
	cmdmap.put("renderer", (cons, args) -> {
	    cons.out.printf("Toolkit: %s\n", UILoop.this.wnd.toolkit().description());
	    if(env != null) {
		Environment.Caps caps = env.caps();
		cons.out.printf("Rendering device: %s, %s\n", caps.vendor(), caps.device());
		cons.out.printf("Driver version: %s\n", caps.driver());
	    }
	});
    }
    public Map<String, Console.Command> findcmds() {
	return(cmdmap);
    }
}
