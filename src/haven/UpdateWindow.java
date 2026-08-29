package haven;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;

public class UpdateWindow extends Window {
    private static final int width = 340;
    private static final long delay = 5000;
    private final String tag;
    private Path ready = null;
    private String errmsg = null;
    private String stext = "Starting download...";
    private double frac = -1, wfrac = -1, lfrac = -1;
    private long got = -1;
    private boolean cancelled = false, restarting = false, shown = false;
    private long t0 = System.nanoTime();

    private final Button upd, no;
    private final CheckBox auto;
    private final Label status;
    private final IBox prog;
    private final Thread worker;

    private final Updater.Progress progcb = new Updater.Progress() {
	public void status(String text, double f) { synchronized(UpdateWindow.this) { stext = text; frac = f; } }
	public boolean cancelled() { synchronized(UpdateWindow.this) { return cancelled; } }
    };

    public UpdateWindow(String tag) {
	super(Coord.z, "Update Available!", true);
	this.tag = tag;

	Widget prev;
	prev = add(new Label("A new Novocaine version is available: " + tag), Coord.z);
	prev = add(status = new Label(""), prev.pos("bl").adds(0, 4).x(0));
	status.setcolor(new Color(200, 200, 200));
	prev = add(prog = new IBox(UI.scale(width), UI.scale(14)), prev.pos("bl").adds(0, 6).x(0));
	prog.hide();

	upd = add(new Button(UI.scale(130), "Download & Restart"), prev.pos("bl").adds(0, 8).x(0));
	upd.action(this::restart);
	no = add(new Button(UI.scale(80), "Skip"), upd.pos("ur").adds(6, 0));
	no.action(this::skip);

	auto = add(new CheckBox("Keep Novocaine up to date automatically"), no.pos("ur").adds(8, 4));
	auto.a = Updater.enabled();
	auto.changed = v -> Updater.enabled(v);

	pack();
	worker = new HackThread("Updater");
	worker.start();
    }

    private void skip() {
	synchronized(this) { cancelled = true; ready = null; }
	Updater.skipped(true);
	reqdestroy();
    }

    private class HackThread extends Thread {
	HackThread(String name) { super(name); }
	public void run() {
	    try {
		Path zip = Updater.download(tag, progcb);
		try {
		    Updater.verify(zip);
		} catch(IOException e) {
		    synchronized(UpdateWindow.this) { ready = null; errmsg = "Downloaded package is damaged: " + e.getMessage(); }
		    try { Updater.discard(); } catch(Exception e2) {}
		    return;
		}
		synchronized(UpdateWindow.this) { ready = zip; }
	    } catch(Updater.Cancelled e) {
		try { Updater.discard(); } catch(Exception e2) {}
		synchronized(UpdateWindow.this) { ready = null; }
	    } catch(Throwable e) {
		Warning w = new Warning(e, "updater download failed");
		w.issue();
		synchronized(UpdateWindow.this) { errmsg = e.getMessage(); if(errmsg == null) errmsg = e.toString(); ready = null; }
		try { Updater.discard(); } catch(Exception e2) {}
	    }
	}
    }

    public void destroy() {
	synchronized(this) { cancelled = true; }
	try { worker.interrupt(); } catch(Exception e) {}
	super.destroy();
    }

    public void tick(double dt) {
	super.tick(dt);
	String cur;
	double cf;
	boolean isReady, isErr, isRestarting;
	synchronized(this) { cur = stext; cf = frac; isReady = ready != null; isErr = errmsg != null; isRestarting = restarting; }
	if(isErr) { status.settext(errmsg); prog.hide(); }
	else if(isRestarting) { status.settext("Restarting..."); prog.hide(); }
	else if(isReady) {
	    long left = delay - (System.nanoTime() - t0) / 1000000;
	    if(left <= 0 && Updater.enabled() && Updater.possible()) { restart(); return; }
	    if(Updater.enabled())
		status.settext("Ready -- restarting in " + Math.max(1, (left + 999) / 1000) + "s");
	    else
		status.settext("Ready to update");
	    prog.hide();
	} else {
	    status.settext(cur);
	    if(cf >= 0) {
		prog.show();
		wfrac = cf;
	    } else prog.hide();
	}
	if(!shown) { shown = true; t0 = System.nanoTime(); }
    }

    public void draw(GOut g) {
	super.draw(g);
	if(wfrac >= 0 && prog.visible) {
	    Coord c = prog.c.add(1, 1);
	    Coord sz = prog.sz.sub(2, 2);
	    g.chcolor(90, 180, 90, 255);
	    g.frect(c, new Coord((int) (sz.x * Math.max(0, Math.min(1, wfrac))), sz.y));
	    g.chcolor();
	}
    }

    private void restart() {
	Path zip;
	synchronized(this) { zip = ready; if(zip == null) return; restarting = true; }
	try {
	    Updater.restart(tag, zip);
	    Updater.writeStamp(tag);
	    System.exit(0);
	} catch(IOException e) {
	    Warning w = new Warning(e, "updater restart failed");
	    w.issue();
	    synchronized(this) { errmsg = e.getMessage(); restarting = false; ready = null; }
	}
    }

    public void wdgmsg(Widget sender, String msg, Object... args) {
	if(msg.equals("close")) skip();
	else super.wdgmsg(sender, msg, args);
    }

    private static class IBox extends Widget {
	IBox(int w, int h) { super(new Coord(w, h)); }
	public void draw(GOut g) {
	    g.chcolor(60, 60, 60, 255);
	    g.frect(Coord.z, sz);
	    g.chcolor(120, 120, 120, 255);
	    g.rect(Coord.z, sz);
	    g.chcolor();
	}
    }
}
