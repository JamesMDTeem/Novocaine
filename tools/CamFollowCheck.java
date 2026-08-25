/*
 * Camera-follow feedback loop.
 *
 * Symptom under test: "the entire camera was wrong, it would lock where I spawned",
 * "it locked instantly on the mine hole when I came down" - the player walks away and
 * the camera stays behind at the point the map instance was entered.
 *
 * The measurement is the symptom itself: project the player's world position through the
 * camera's view matrix and watch where they sit on screen. A following camera keeps them
 * put; a locked camera lets them slide off.
 *
 * NOT part of the client build - build.xml compiles src/ only, and this lives in tools/ so
 * it can never reach a release jar. Run on demand (from the repo root, PowerShell):
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\camcheck tools\CamFollowCheck.java
 *   java -cp "$env:TEMP\camcheck;$CP" haven.CamFollowCheck
 *
 * Exits 0 when every scenario passes, 1 otherwise.
 */
package haven;

import java.util.Collection;

public class CamFollowCheck {
    /* java.util.Collection, for the id snapshots. */
    static int failures = 0;
    static final long PLID = 4242;

    /* A Moving that reports whatever position we tell it, so the harness never needs loaded
     * map grids - the real placers go through MCache.getzp and throw Loading without them. */
    static class Ghost extends Moving {
	Coord3f c;
	Ghost(Gob gob, Coord3f c) {super(gob); this.c = c;}
	public Coord3f getc() {return(c);}
	public double getv() {return(0);}
    }

    /* OptWnd's widgets are assigned in its constructor, which needs a live UI. The client
     * reads them from static fields all over the gob and camera path, so stand in plain
     * defaults rather than standing up the whole options window. */
    static void stubOptWnd() throws Exception {
	for(java.lang.reflect.Field f : OptWnd.class.getDeclaredFields()) {
	    if(!java.lang.reflect.Modifier.isStatic(f.getModifiers()))
		continue;
	    f.setAccessible(true);
	    if(f.get(null) != null)
		continue;
	    if(f.getType() == CheckBox.class)
		f.set(null, new CheckBox("harness"));
	    else if(f.getType() == HSlider.class)
		f.set(null, new HSlider(200, 10, 300, 150));
	}
    }

    static Gob mkgob(Glob glob, long id, double x, double y) {
	Gob g = new Gob(glob, Coord2d.of(x, y), id);
	g.setattr(new Ghost(g, Coord3f.of((float)x, (float)y, 0)));
	return(g);
    }

    static void move(Gob g, double x, double y) {
	g.rc = Coord2d.of(x, y);
	((Ghost)g.getattr(Moving.class)).c = Coord3f.of((float)x, (float)y, 0);
    }

    /* The camera lerps toward its target, so tick it long enough to arrive. */
    static void settle(MapView mv) {
	for(int i = 0; i < 300; i++) {
	    try {
		mv.camera.tick(1.0 / 60.0);
	    } catch(Loading l) {
		/* mirrors the swallow in MapView.tick */
	    }
	}
    }

    /* Where the player sits on screen, in view space. */
    static Coord3f onscreen(MapView mv, Gob pl) {
	Coord3f w = pl.getc();
	return(mv.camera.view.fin(Matrix4f.id).mul4(new Coord3f(w.x, -w.y, w.z)));
    }

    static String state(MapView mv) {
	String cc;
	try {
	    cc = String.valueOf(mv.getcc());
	} catch(Loading l) {
	    cc = "<Loading: " + l.getMessage() + ">";
	}
	return(String.format("player=%s getcc=%s", (mv.player() == null) ? "null" : "ok", cc));
    }

    /* Walk the player 300 units and report how far they slid across the screen.
     * Camera following -> ~0. Camera locked -> the full walk distance. */
    static double drift(MapView mv, Gob... gobs) {
	settle(mv);
	Coord3f before = onscreen(mv, gobs[0]);
	for(Gob g : gobs)
	    move(g, 400, 400);
	settle(mv);
	Coord3f after = onscreen(mv, gobs[0]);
	return(Math.hypot(after.x - before.x, after.y - before.y));
    }

    static void check(String what, boolean ok, String detail) {
	System.out.printf("  %-46s %-6s %s%n", what, ok ? "ok" : "FAIL", detail);
	if(!ok)
	    failures++;
    }




    /* Waits for the player id to become unambiguous again. Returns null once it has, or a
     * one-shot description of the stuck state - taken under the cache lock, so the text
     * reports the same sample the verdict was made on. */
    static String settleId(Glob glob) throws Exception {
	for(int i = 0; i < 200; i++) {
	    synchronized(glob.oc) {
		Collection<Gob> on = glob.oc.getgobs(PLID);
		if((on.size() == 1) && (glob.oc.getgob(PLID) != null))
		    return(null);
	    }
	    Thread.sleep(10);
	}
	synchronized(glob.oc) {
	    return(String.format("stuck: %d object(s) hold id %d, getgob=%s",
				 glob.oc.getgobs(PLID).size(), PLID, glob.oc.getgob(PLID)));
	}
    }

    static int count(Glob glob, long id) {
	int n = 0;
	synchronized(glob.oc) {
	    for(Gob g : glob.oc) {
		if(g.id == id)
		    n++;
	    }
	}
	return(n);
    }

    static void settleLoader(Glob glob, String what) throws Exception {
	for(int i = 0; i < 200; i++) {
	    if(glob.oc.getgob(PLID) != null)
		return;
	    Thread.sleep(10);
	}
	System.out.println("     (loader never produced a gob " + what + ")");
    }


    /* Drives the probe the way MapView does: enter, tick, draw. With track=false the camera is
     * never ticked, which is what a frozen camera looks like from the probe's side. */
    static void walk(haven.automated.PlgobWatch pw, MapView mv, Gob pl, int steps, boolean track) {
	double x = pl.rc.x, y = pl.rc.y;
	for(int i = 0; i < steps; i++) {
	    x += 4;
	    move(pl, x, y);
	    pw.enter();
	    pw.tick(mv, 1.0 / 60.0);
	    if(track) {
		try {
		    mv.camera.tick(1.0 / 60.0);
		} catch(Loading l) {}
	    }
	    pw.drawn(mv);
	}
    }


    /* Walks the player back and forth rather than in a straight line. This is what a character
     * does indoors, and it is what made the first detector cry wolf: A-B-A banks path length in
     * both the player's travel and their drift across the view, so comparing those two reads as
     * a failure on a camera that is tracking perfectly. */
    static void pace(haven.automated.PlgobWatch pw, MapView mv, Gob pl, int steps, boolean track) {
	double x = pl.rc.x, y = pl.rc.y;
	int dir = 1;
	for(int i = 0; i < steps; i++) {
	    if((i % 20) == 0)
		dir = -dir;
	    x += 4 * dir;
	    move(pl, x, y);
	    pw.enter();
	    pw.tick(mv, 1.0 / 60.0);
	    if(track) {
		try {
		    mv.camera.tick(1.0 / 60.0);
		} catch(Loading l) {}
	    }
	    pw.drawn(mv);
	}
    }


    /* The player's view-space position out of the beat line - the field that says whether the
     * camera is pointed at them, as opposed to merely being updated. */
    static java.util.List<double[]> pvs(String log) {
	java.util.List<double[]> out = new java.util.ArrayList<>();
	java.util.regex.Matcher m = java.util.regex.Pattern
	    .compile("pv=\\((-?[\\d.]+), (-?[\\d.]+)\\)").matcher(log);
	while(m.find())
	    out.add(new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))});
	return(out);
    }

    static double spread(java.util.List<double[]> pts) {
	double lo0 = 1e9, hi0 = -1e9, lo1 = 1e9, hi1 = -1e9;
	for(double[] p : pts) {
	    lo0 = Math.min(lo0, p[0]); hi0 = Math.max(hi0, p[0]);
	    lo1 = Math.min(lo1, p[1]); hi1 = Math.max(hi1, p[1]);
	}
	return(Math.hypot(hi0 - lo0, hi1 - lo1));
    }

    static String tail(java.nio.file.Path log, long from) throws Exception {
	if(!java.nio.file.Files.exists(log))
	    return("");
	byte[] all = java.nio.file.Files.readAllBytes(log);
	int off = (int)Math.min(from, all.length);
	return(new String(all, off, all.length - off, java.nio.charset.StandardCharsets.UTF_8));
    }

    static String firstline(String hay, String needle) {
	for(String l : hay.split("\r?\n")) {
	    if(l.contains(needle))
		return(l.trim());
	}
	return("(not logged)");
    }

    static MapView mkview(Glob glob) {
	return(new MapView(new Coord(1280, 720), glob, Coord2d.of(100, 100), PLID));
    }

    public static void main(String[] args) throws Exception {
	stubOptWnd();
	/* NLog trims a log to its last few launches the first time it writes to it in a run,
	 * which can make the file shorter than an offset taken before that. Starting clean
	 * keeps the byte offsets these scenarios read back by meaningful. */
	java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("logs", "plgob.log"));

	System.out.println("1. baseline: one player gob, camera should track it");
	{
	    Glob glob = new Glob(null);
	    Gob pl = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl);
	    MapView mv = mkview(glob);
	    double d = drift(mv, pl);
	    check("player stays put on screen", d < 5.0,
		  String.format("drift=%.1fpx %s", d, state(mv)));
	}

	System.out.println("2. instance change: a replacement gob shares the player id");
	{
	    Glob glob = new Glob(null);
	    Gob pl = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl);
	    MapView mv = mkview(glob);
	    settle(mv);

	    /* Coming down a mine hole or through a door: the server re-sends the player
	     * object for the new instance. OCache's applier builds and add()s the
	     * replacement before the outgoing GobInfo's removal has run. */
	    Gob pl2 = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl2);
	    System.out.printf("     getgob(%d) with two gobs on the id -> %s%n", PLID, glob.oc.getgob(PLID));

	    double d = drift(mv, pl, pl2);
	    check("an ambiguous id reads as no player at all", mv.player() == null,
		  String.format("drift=%.1fpx %s", d, state(mv)));
	    check("and the camera stops tracking", d > 100.0,
		  String.format("drift=%.1fpx", d));
	}

	System.out.println("3. recovery: the outgoing gob is removed again");
	{
	    Glob glob = new Glob(null);
	    Gob pl = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl);
	    MapView mv = mkview(glob);
	    settle(mv);
	    Gob pl2 = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl2);
	    glob.oc.remove(pl);
	    System.out.printf("     getgob(%d) after the removal      -> %s%n", PLID, glob.oc.getgob(PLID));

	    double d = drift(mv, pl2);
	    check("player stays put on screen", d < 5.0,
		  String.format("drift=%.1fpx %s", d, state(mv)));
	}


	System.out.println("4. real net path: player object reissued for a new instance");
	{
	    Glob glob = new Glob(null);
	    MapView mv = mkview(glob);

	    /* Attribute-less deltas: enough to make OCache build and register a Gob,
	     * without dragging resource loading into the harness. */
	    OCache.ObjDelta d1 = new OCache.ObjDelta();
	    d1.id = PLID; d1.frame = 1; d1.initframe = 0;
	    glob.oc.receive(d1);
	    settleLoader(glob, "after first object");

	    /* The reissue the server sends when you cross into a new map instance. */
	    OCache.ObjDelta d2 = new OCache.ObjDelta();
	    d2.id = PLID; d2.frame = 5; d2.initframe = 5;
	    glob.oc.receive(d2);

	    long t0 = System.nanoTime();
	    String snap = settleId(glob);
	    check("duplicate resolves by itself", snap == null,
		  (snap == null) ? String.format("resolved after %dms", (System.nanoTime() - t0) / 1000000) : snap);
	}


	System.out.println("5. same reissue, but a registered ChangeCallback throws on removal");
	{
	    Glob glob = new Glob(null);
	    MapView mv = mkview(glob);

	    /* OCache.remove() runs every registered callback inline, inside the same
	     * GobInfo.apply() task that unregisters the outgoing gob. MiniMap's
	     * mine-support callback, MapView.Gobs and nbots' Sight watcher all sit here. */
	    OCache.ChangeCallback bad = new OCache.ChangeCallback() {
		    public void added(Gob ob) {}
		    public void removed(Gob ob) {throw(new NullPointerException("callback blew up"));}
		};
	    glob.oc.callback(bad);

	    OCache.ObjDelta d1 = new OCache.ObjDelta();
	    d1.id = PLID; d1.frame = 1; d1.initframe = 0;
	    glob.oc.receive(d1);
	    settleLoader(glob, "after first object");

	    OCache.ObjDelta d2 = new OCache.ObjDelta();
	    d2.id = PLID; d2.frame = 5; d2.initframe = 5;
	    glob.oc.receive(d2);

	    long t0 = System.nanoTime();
	    String snap = settleId(glob);
	    check("duplicate resolves by itself", snap == null,
		  (snap == null) ? String.format("resolved after %dms, player=%s",
						 (System.nanoTime() - t0) / 1000000,
						 (mv.player() == null) ? "null" : "ok")
				 : snap);
	    check("the loader survived the throwing listener", snap == null,
		  "a dead loader thread leaves the id stuck");
	}


	System.out.println("6. every registered camera type must track the player");
	{
	    Glob glob = new Glob(null);
	    Gob pl = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl);
	    MapView mv = mkview(glob);

	    /* Built directly rather than through setcam, which writes the real client's
	     * camera preference as a side effect. */
	    MapView.Camera[] cams = {
		mv.new FollowCam(), mv.new SimpleCam(), mv.new FreeCam(), mv.new SOrthoCam(new String[0]),
	    };
	    for(MapView.Camera cam : cams) {
		mv.camera = cam;
		move(pl, 100, 100);
		double d = drift(mv, pl);
		check(cam.getClass().getSimpleName() + " tracks the player", d < 5.0,
		      String.format("drift=%.1fpx", d));
	    }
	}


	System.out.println("7. the follow detector fires when the camera is pinned, and not before");
	{
	    java.nio.file.Path log = java.nio.file.Paths.get("logs", "plgob.log");
	    long was = java.nio.file.Files.exists(log) ? java.nio.file.Files.size(log) : 0;

	    Glob glob = new Glob(null);
	    Gob pl = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl);
	    MapView mv = mkview(glob);
	    haven.automated.PlgobWatch pw = new haven.automated.PlgobWatch();
	    settle(mv);

	    /* A healthy client: the camera is ticked every frame, so the player stays put on
	     * screen however far they walk. */
	    walk(pw, mv, pl, 300, true);
	    check("stays quiet while the camera tracks",
		  !tail(log, was).contains("camera is not following"), "no complaint logged");

	    /* The reported symptom: the player covers ground while the camera does not move. */
	    walk(pw, mv, pl, 300, false);
	    String out = tail(log, was);
	    check("reports a pinned camera", out.contains("camera is not following"),
		  firstline(out, "camera is not following"));
	    check("and names the camera and the id", out.contains("FreeCam") && out.contains("id " + PLID),
		  "detail present");
	}


	System.out.println("8. a player pacing indoors is not a stuck camera");
	{
	    java.nio.file.Path log = java.nio.file.Paths.get("logs", "plgob.log");
	    long was = java.nio.file.Files.exists(log) ? java.nio.file.Files.size(log) : 0;

	    Glob glob = new Glob(null);
	    Gob pl = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl);
	    MapView mv = mkview(glob);
	    haven.automated.PlgobWatch pw = new haven.automated.PlgobWatch();
	    settle(mv);

	    pace(pw, mv, pl, 400, true);
	    check("stays quiet while the camera tracks a pacing player",
		  !tail(log, was).contains("camera is not following"),
		  firstline(tail(log, was), "camera is not following"));

	    /* And still catches a genuinely pinned camera under the same movement. */
	    long was2 = java.nio.file.Files.exists(log) ? java.nio.file.Files.size(log) : 0;
	    pace(pw, mv, pl, 400, false);
	    check("still reports a pinned camera while pacing",
		  tail(log, was2).contains("camera is not following"),
		  firstline(tail(log, was2), "camera is not following"));
	}


	System.out.println("9. the client's own position freezes while the server keeps moving them");
	{
	    java.nio.file.Path log = java.nio.file.Paths.get("logs", "plgob.log");
	    long was = java.nio.file.Files.exists(log) ? java.nio.file.Files.size(log) : 0;

	    Glob glob = new Glob(null);
	    Gob pl = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl);
	    MapView mv = mkview(glob);
	    haven.automated.PlgobWatch pw = new haven.automated.PlgobWatch();
	    settle(mv);

	    /* rc advances - the server keeps saying where the character is - while getc() stays
	     * put. The camera then tracks a point that never moves, which is a static scene with
	     * the character still walking about in it. Nothing in the old measurement noticed:
	     * it only counted getc() travel, so a frozen position looked like standing still. */
	    double x = pl.rc.x, y = pl.rc.y;
	    for(int i = 0; i < 300; i++) {
		x += 4;
		pl.rc = Coord2d.of(x, y);
		pw.enter();
		pw.tick(mv, 1.0 / 60.0);
		try {
		    mv.camera.tick(1.0 / 60.0);
		} catch(Loading l) {}
		pw.drawn(mv);
	    }

	    String out = tail(log, was);
	    check("reports the frozen position", out.contains("POSITION FROZEN"),
		  firstline(out, "camera is not following"));
	}


	System.out.println("10. the beat's own fields say whether the camera is pointed at the player");
	{
	    java.nio.file.Path log = java.nio.file.Paths.get("logs", "plgob.log");

	    Glob glob = new Glob(null);
	    Gob pl = mkgob(glob, PLID, 100, 100);
	    glob.oc.add(pl);
	    MapView mv = mkview(glob);
	    haven.automated.PlgobWatch pw = new haven.automated.PlgobWatch();
	    settle(mv);

	    /* Beats are rate-limited to one a second, so drive real time by walking in bursts
	     * with a pause between them rather than faking the clock. */
	    long was = java.nio.file.Files.size(log);
	    for(int burst = 0; burst < 4; burst++) {
		walk(pw, mv, pl, 40, true);
		Thread.sleep(1050);
	    }
	    double tracking = spread(pvs(tail(log, was)));
	    check("pv holds still while the camera tracks", tracking < 40.0,
		  String.format("pv spread=%.1f over %d beats", tracking, pvs(tail(log, was)).size()));

	    was = java.nio.file.Files.size(log);
	    for(int burst = 0; burst < 4; burst++) {
		walk(pw, mv, pl, 40, false);
		Thread.sleep(1050);
	    }
	    double pinned = spread(pvs(tail(log, was)));
	    check("pv slides away while the camera is pinned", pinned > 200.0,
		  String.format("pv spread=%.1f over %d beats", pinned, pvs(tail(log, was)).size()));
	}

	System.out.println(failures == 0 ? "PASS" : failures + " FAILURE(S)");
	System.exit(failures == 0 ? 0 : 1);
    }
}
