package haven.render;

import java.util.*;
import haven.*;

/**
 * Frustum culling for a view's main draw list: what the camera cannot see is not drawn.
 *
 * It stands between the view's InstanceList and its DrawList and hands on only the slots whose
 * mesh reaches the camera's frustum. The renderer tests nothing itself - every slot in the tree is
 * a draw call every frame - and nothing here is taken out of the tree: the shadow list is fed by the
 * same instancer and keeps every caster, so an object behind the camera still throws its shadow into
 * view. Hurricane's "Only Render Camera-Visible Objects" option is a different thing: it tests a
 * gob's centre point on screen and takes the gob out of the tree, shadow and all.
 *
 * After brodgar-io-client bbe170665 and 42ff0cb58, changed where their review measured waste:
 *
 * - A slot is tested as its mesh's bounding SPHERE against the frustum's planes, not as eight box
 *   corners taken through the clip matrix: 0.09 ms against 0.66 ms per 20,000 slots (measured
 *   2026-09-26). The sphere holds the box, so it never leaves out what the box test would keep.
 * - A slot whose location and camera have not moved since it was last tested keeps its answer
 *   rather than being tested again, so a still scene costs one identity comparison per slot.
 *
 * Kept from theirs: a slot is left out only when its sphere is wholly behind one side plane or the
 * near plane (never merely "no corner inside", the way a box wider than the screen vanishes); the
 * side planes are widened by a margin, ENTER to come in and the wider LEAVE to go out, so what turns
 * into view is already drawn when it reaches the edge and one on the edge does not flicker; the far
 * plane is not tested. A slot with no location or camera, and a non-mesh object, are always drawn.
 * Taking a slot out is capped per frame, putting one back never is. Every call is under the tree's
 * lock, as the instancer's are.
 *
 * An instanced batch holds one map grid's members (InstanceList.CELL, after brodgar-io-client
 * 0401da437) and is tested as the box round them all, exactly rather than as a sphere: until then a
 * batch spanned the whole scene and was always drawn, every instance of it, every frame.
 */
public class FrustumList implements RenderList<Rendered> {
    /* Fractions of the view's half-width at a slot's distance: 0.1 widens a 90-degree frustum by
     * about 3 degrees a side, 0.2 by about 5. */
    public static final float ENTER = 0.1f, LEAVE = 0.2f;
    /* Taking a slot out saves a draw call and costs its disposal; putting one back costs its
     * compilation, and is never deferred - a slot turning into view has to be there. */
    private static final int REMOVES = 256;

    private final RenderList<Rendered> back;
    private final Map<Slot<? extends Rendered>, Entry> slots = new HashMap<>();
    private final ArrayList<Entry> order = new ArrayList<>();
    private boolean enabled = false;
    private int nculled = 0, ncullable = 0, ntested = 0;

    /* The frustum of each camera and projection seen this frame and the frame before. A camera
     * whose matrix is unchanged keeps its Planes object, which is how a slot knows it need not be
     * tested again. */
    private Map<Pair<Camera, Projection>, Planes> cur = new HashMap<>(), prev = new HashMap<>();

    /* Package-visible for tools/FrustumCullCheck, which holds it against the box test. */
    static class Planes {
	final float[] clip;
	/* Four side planes widened by ENTER, four by LEAVE, then the near plane; a b c d each,
	 * normalised so that a*x + b*y + c*z + d is a distance in world units. */
	final float[] enter = new float[20], leave = new float[20];

	Planes(float[] clip) {
	    this.clip = clip;
	    make(enter, 1 + ENTER);
	    make(leave, 1 + LEAVE);
	}

	/* Rows of the column-major clip matrix: x <= k*w is the plane k*row3 - row0, and so on. */
	private void make(float[] pl, float k) {
	    float[] m = clip;
	    int o = 0;
	    for(int s = 0; s < 4; s++) {
		int row = (s < 2) ? 0 : 1;
		float sg = ((s & 1) == 0) ? 1 : -1;
		for(int c = 0; c < 4; c++)
		    pl[o + c] = (k * m[(c * 4) + 3]) + (sg * m[(c * 4) + row]);
		o += 4;
	    }
	    for(int c = 0; c < 4; c++)
		pl[o + c] = m[(c * 4) + 3] + m[(c * 4) + 2];
	    for(int p = 0; p < 20; p += 4) {
		float l = (float)Math.sqrt((pl[p] * pl[p]) + (pl[p + 1] * pl[p + 1]) + (pl[p + 2] * pl[p + 2]));
		if(l > 0) {
		    pl[p] /= l; pl[p + 1] /= l; pl[p + 2] /= l; pl[p + 3] /= l;
		}
	    }
	}

	boolean sphere(float[] pl, float x, float y, float z, float r) {
	    for(int p = 0; p < 20; p += 4) {
		if(((pl[p] * x) + (pl[p + 1] * y) + (pl[p + 2] * z) + pl[p + 3]) < -r)
		    return(false);
	    }
	    return(true);
	}

	/* An axis-aligned box, nx ny nz px py pz, against the same planes: out when its corner
	 * furthest along a plane's normal is behind it, which is every corner behind it - exactly
	 * what the eight-corner test decides, at the sphere's cost. For an instanced batch, whose box
	 * is a map grid wide, the sphere round it would be some 1.4 times as loose. */
	boolean box(float[] pl, float[] b) {
	    float cx = (b[0] + b[3]) * 0.5f, cy = (b[1] + b[4]) * 0.5f, cz = (b[2] + b[5]) * 0.5f;
	    float hx = (b[3] - b[0]) * 0.5f, hy = (b[4] - b[1]) * 0.5f, hz = (b[5] - b[2]) * 0.5f;
	    for(int p = 0; p < 20; p += 4) {
		float r = (Math.abs(pl[p]) * hx) + (Math.abs(pl[p + 1]) * hy) + (Math.abs(pl[p + 2]) * hz);
		if(((pl[p] * cx) + (pl[p + 1] * cy) + (pl[p + 2] * cz) + pl[p + 3]) < -r)
		    return(false);
	    }
	    return(true);
	}
    }

    private static class Entry {
	final Slot<? extends Rendered> slot;
	int oidx;
	boolean drawn;
	/* Never testable: an object that is not a mesh. */
	boolean untestable;
	/* The world sphere, for the location it was taken under. */
	Location.Chain wloc;
	float x, y, z, r;
	/* What it was last tested against - the frustum, and the location, or an instanced batch's
	 * box - and the answer. */
	Planes tested;
	Object tloc;
	boolean twant;

	Entry(Slot<? extends Rendered> slot) {
	    this.slot = slot;
	}
    }

    public FrustumList(RenderList<Rendered> back) {
	this.back = back;
    }

    /** How many slots are left out of the draw. */
    public int culled() {return(nculled);}
    /** How many slots could be tested at all; the rest are always drawn. */
    public int cullable() {return(ncullable);}
    /** How many were actually tested in the last frame, the rest keeping their answer. */
    public int tested() {return(ntested);}

    public void add(Slot<? extends Rendered> slot) {
	Entry e = new Entry(slot);
	/* Into `back` whether or not it is in view, and out again at once if it is not: the add is
	 * what prepares the slot, and throws Loading until it can, before anything here is recorded.
	 * Whoever adds counts on that - RUtils.readd puts a sprite's old parts back when the new ones
	 * throw, and a part never prepared because it was out of view threw again there (42ff0cb58). */
	back.add(slot);
	boolean want = !enabled || (visible(e, false) != Boolean.FALSE);
	if(!want)
	    back.remove(slot);
	e.drawn = want;
	if(slots.put(slot, e) != null)
	    throw(new AssertionError());
	e.oidx = order.size();
	order.add(e);
	if(!want)
	    nculled++;
    }

    public void remove(Slot<? extends Rendered> slot) {
	Entry e = slots.remove(slot);
	if(e == null)
	    return;
	if(e.drawn)
	    back.remove(slot);
	else
	    nculled--;
	Entry last = order.remove(order.size() - 1);
	if(last != e) {
	    order.set(e.oidx, last);
	    last.oidx = e.oidx;
	}
    }

    public void update(Slot<? extends Rendered> slot) {
	Entry e = slots.get(slot);
	if((e != null) && e.drawn)
	    back.update(slot);
    }

    public void update(Pipe group, int[] statemask) {
	back.update(group, statemask);
    }

    /**
     * The frame's pass, from the view's draw under the tree's lock, after the instancer has
     * settled the frame's slots. Off, it puts everything back once and then does nothing.
     */
    public void cull(boolean on) {
	if(!on && !enabled && (nculled == 0))
	    return;
	enabled = on;
	Map<Pair<Camera, Projection>, Planes> t = prev;
	prev = cur;
	cur = t;
	cur.clear();
	/* The camera may have moved in place since last frame: ask again. */
	mcam = null;
	mprj = null;
	mpl = null;
	int removes = 0, testable = 0;
	ntested = 0;
	for(int i = 0; i < order.size(); i++) {
	    Entry e = order.get(i);
	    Boolean vis = on ? visible(e, true) : null;
	    if(vis != null)
		testable++;
	    boolean want = (vis != Boolean.FALSE);
	    if(want == e.drawn)
		continue;
	    if(want) {
		try {
		    back.add(e.slot);
		} catch(RuntimeException exc) {
		    /* Not preparable yet - a texture still loading. Asked again next frame. */
		    continue;
		}
		e.drawn = true;
		nculled--;
	    } else {
		if(removes >= REMOVES)
		    continue;
		removes++;
		back.remove(e.slot);
		e.drawn = false;
		nculled++;
	    }
	}
	ncullable = testable;
    }

    /* Nearly every slot shares one camera and one projection, so the last pair asked is kept
     * here and a slot costs two identity comparisons, not a map lookup and an allocation. */
    private Camera mcam = null;
    private Projection mprj = null;
    private Planes mpl = null;

    private Planes planes(Camera cam, Projection prj) {
	if((cam == mcam) && (prj == mprj) && (mpl != null))
	    return(mpl);
	mpl = planes0(cam, prj);
	mcam = cam;
	mprj = prj;
	return(mpl);
    }

    private Planes planes0(Camera cam, Projection prj) {
	Pair<Camera, Projection> key = new Pair<>(cam, prj);
	Planes ret = cur.get(key);
	if(ret != null)
	    return(ret);
	float[] clip = prj.fin(Matrix4f.id).mul(cam.fin(Matrix4f.id)).m;
	Planes was = prev.get(key);
	ret = ((was != null) && Arrays.equals(was.clip, clip)) ? was : new Planes(clip.clone());
	cur.put(key, ret);
	return(ret);
    }

    /* TRUE in view, FALSE out of it, null when this slot cannot be tested and is always drawn. */
    private Boolean visible(Entry e, boolean reuse) {
	if(e.untestable)
	    return(null);
	try {
	    Slot<? extends Rendered> slot = e.slot;
	    if(slot instanceof InstanceBatch)
		return(visbatch(e, reuse));
	    GroupPipe st = slot.state();
	    Location.Chain loc = st.get(Homo3D.loc);
	    Camera cam = st.get(Homo3D.cam);
	    Projection prj = st.get(Homo3D.prj);
	    if((loc == null) || (cam == null) || (prj == null))
		return(null);
	    Planes pl = planes(cam, prj);
	    if(reuse && (e.tested == pl) && (e.tloc == loc))
		return(e.twant);
	    if(e.wloc != loc) {
		Rendered obj = slot.obj();
		if(!(obj instanceof FastMesh)) {
		    e.untestable = true;
		    return(null);
		}
		sphere(e, ((FastMesh)obj).bounds(), loc.fin(Matrix4f.id));
		e.wloc = loc;
	    }
	    ntested++;
	    boolean want = pl.sphere(e.drawn ? pl.leave : pl.enter, e.x, e.y, e.z, e.r);
	    e.tested = pl;
	    e.tloc = loc;
	    e.twant = want;
	    return(want);
	} catch(RuntimeException exc) {
	    return(null);
	}
    }

    /* An instanced batch: one map grid's members (InstanceList.CELL), tested as the box round them
     * all. The box is a new array whenever it changes, so an unchanged one under an unchanged
     * frustum keeps its answer. A batch with a member that has no box is drawn, and asked again
     * next frame - its members come and go. */
    private Boolean visbatch(Entry e, boolean reuse) {
	float[] b = InstanceList.batchbox(e.slot);
	if(b == null)
	    return(null);
	GroupPipe st = e.slot.state();
	Camera cam = st.get(Homo3D.cam);
	Projection prj = st.get(Homo3D.prj);
	if((cam == null) || (prj == null))
	    return(null);
	Planes pl = planes(cam, prj);
	if(reuse && (e.tested == pl) && (e.tloc == b))
	    return(e.twant);
	ntested++;
	boolean want = pl.box(e.drawn ? pl.leave : pl.enter, b);
	e.tested = pl;
	e.tloc = b;
	e.twant = want;
	return(want);
    }

    /* The mesh's box taken to the world, as a sphere round it: the box's centre through the
     * location, and its half-diagonal scaled by the largest of the location's axis scales. */
    private static void sphere(Entry e, Volume3f b, Matrix4f xf) {
	float[] m = xf.m;
	float bx = (b.n.x + b.p.x) * 0.5f, by = (b.n.y + b.p.y) * 0.5f, bz = (b.n.z + b.p.z) * 0.5f;
	e.x = (m[0] * bx) + (m[4] * by) + (m[8]  * bz) + m[12];
	e.y = (m[1] * bx) + (m[5] * by) + (m[9]  * bz) + m[13];
	e.z = (m[2] * bx) + (m[6] * by) + (m[10] * bz) + m[14];
	float hx = (b.p.x - b.n.x) * 0.5f, hy = (b.p.y - b.n.y) * 0.5f, hz = (b.p.z - b.n.z) * 0.5f;
	float sx = (m[0] * m[0]) + (m[1] * m[1]) + (m[2]  * m[2]);
	float sy = (m[4] * m[4]) + (m[5] * m[5]) + (m[6]  * m[6]);
	float sz = (m[8] * m[8]) + (m[9] * m[9]) + (m[10] * m[10]);
	float s = (float)Math.sqrt(Math.max(sx, Math.max(sy, sz)));
	e.r = (float)Math.sqrt((hx * hx) + (hy * hy) + (hz * hz)) * s;
    }
}
