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
import java.util.function.Consumer;
import java.lang.annotation.*;
import java.lang.reflect.*;
import haven.render.*;

public class OCache implements Iterable<Gob> {
    public static final int OD_REM = 0;
    public static final int OD_MOVE = 1;
    public static final int OD_RES = 2;
    public static final int OD_LINBEG = 3;
    public static final int OD_LINSTEP = 4;
    public static final int OD_SPEECH = 5;
    public static final int OD_COMPOSE = 6;
    public static final int OD_ZOFF = 7;
    public static final int OD_LUMIN = 8;
    public static final int OD_AVATAR = 9;
    public static final int OD_FOLLOW = 10;
    public static final int OD_HOMING = 11;
    public static final int OD_OVERLAY = 12;
    /* public static final int OD_AUTH = 13; -- Removed */
    public static final int OD_HEALTH = 14;
    /* public static final int OD_BUDDY = 15; -- Removed */
    public static final int OD_CMPPOSE = 16;
    public static final int OD_CMPMOD = 17;
    public static final int OD_CMPEQU = 18;
    public static final int OD_ICON = 19;
    public static final int OD_RESATTR = 20;
    public static final int OD_END = 255;
    public static final int[] compodmap = {OD_REM, OD_RESATTR, OD_FOLLOW, OD_MOVE, OD_RES, OD_LINBEG, OD_LINSTEP, OD_HOMING};
    public static final Coord2d posres = Coord2d.of(0x1.0p-10, 0x1.0p-10).mul(11, 11);
    /* XXX: Use weak refs */
    private Collection<Collection<Gob>> local = new LinkedList<Collection<Gob>>();
    private MultiMap<Long, Gob> objs = new HashMultiMap<Long, Gob>();
    private Glob glob;
    private final Collection<ChangeCallback> cbs = new WeakList<ChangeCallback>();

    public interface ChangeCallback {
	public void added(Gob ob);
	public void removed(Gob ob);
    }

    public OCache(Glob glob) {
	this.glob = glob;
	if (OptWnd.toggleGobHidingCheckBox.a) gobAction(Gob::updateHidingBoxes);
	if (OptWnd.showObjectCollisionBoxesCheckBox.a) gobAction(Gob::updateCollisionBoxes);
	if (OptWnd.showContainerFullnessCheckBox.a) gobAction(Gob::updateContainerFullnessHighlight);
	gobAction(Gob::updateCustomSizeAndRotation);
	if (OptWnd.showWorkstationProgressCheckBox.a) gobAction(Gob::updateWorkstationProgressHighlight);
    }

    public synchronized void callback(ChangeCallback cb) {
	cbs.add(cb);
    }

    public synchronized void uncallback(ChangeCallback cb) {
	cbs.remove(cb);
    }

    /* A listener that throws must not take the object cache's bookkeeping down with it.
     *
     * These callbacks run inside GobInfo.apply on the loader thread, and apply is deferred
     * with capex=false, so an escaping exception both kills the loader thread and leaves the
     * GobInfo holding a non-null applier that nothing ever clears - which means the outgoing
     * gob is never unregistered. Objects are keyed by id in a MultiMap whose get() returns
     * null for an ambiguous key, so once that happens to the player's id, MapView.player()
     * reads null for the rest of the session and the camera, the click-to-move origin and
     * every range check fall back to wherever the map instance was entered. One misbehaving
     * listener is not worth that, so report it and carry on down the list.
     *
     * Loading is control flow here rather than a fault: let it out so the loader parks the
     * task and retries it, the way every other loading path in this class relies on. */
    private void fire(Collection<ChangeCallback> cbs, Gob ob, boolean added) {
	for(ChangeCallback cb : cbs) {
	    try {
		if(added)
		    cb.added(ob);
		else
		    cb.removed(ob);
	    } catch(Loading l) {
		throw(l);
	    } catch(RuntimeException e) {
		new Warning(e, String.format("gob %s callback failed for object %d",
					     added ? "added" : "removed", ob.id)).issue();
	    }
	}
    }

    public void add(Gob ob) {
	synchronized(ob) {
	    Collection<ChangeCallback> cbs;
	    synchronized(this) {
		cbs = new ArrayList<>(this.cbs);
		objs.put(ob.id, ob);
	    }
	    fire(cbs, ob, true);
	}
    }

    public void remove(Gob ob) {
	Gob old;
	Collection<ChangeCallback> cbs;
	synchronized(this) {
	    old = objs.remove(ob.id, ob);
	    if((old != null) && (old != ob))
		throw(new RuntimeException(String.format("object %d removed wrong object", ob.id)));
	    cbs = new ArrayList<>(this.cbs);
	}
	if(old != null) {
	    synchronized(old) {
		/* Same reasoning as fire(): the object is already out of the map by this
		 * point, so failing here would only strand the loader task. */
		try {
		    old.removed();
		} catch(Loading l) {
		    throw(l);
		} catch(RuntimeException e) {
		    new Warning(e, String.format("object %d failed its own removal", old.id)).issue();
		}
		fire(cbs, old, false);
	    }
	}
    }

    /** Every object currently registered under an id. More than one means the id is
     *  ambiguous and {@link #getgob} will read null for it. */
    public Collection<Gob> getgobs(long id) {
	synchronized(this) {
	    return(new ArrayList<>(objs.getall(id)));
	}
    }

    public void ctick(double dt) {
	ArrayList<Gob> copy = new ArrayList<Gob>();
	synchronized(this) {
	    for(Gob g : this)
		copy.add(g);
	}
	Consumer<Gob> task = g -> {
	    synchronized(g) {
		try {
		    g.ctick(dt);
		} catch(Loading l) {
		    /* One object still waiting on a resource is not a reason to abandon the
		     * tick for every other object - and when this runs on the parallel stream
		     * an escaping exception does not just skip the rest, it comes back out of
		     * ctick and ends the UI loop. Whatever this gob wanted will be there on a
		     * later tick, so let it sit this one out.
		     *
		     * Deliberately only Loading: anything else is a real fault and should
		     * still be loud rather than silently swallowed frame after frame. */
		}
	    }
	};
	if(!Config.par.get())
	    copy.forEach(task);
	else
	    copy.parallelStream().forEach(task);
    }

    public void gtick(Render g) {
	ArrayList<Gob> copy = new ArrayList<Gob>();
	synchronized(this) {
	    for(Gob ob : this)
		copy.add(ob);
	}

	MapView mapView;
	Coord viewportSize;
	if(glob.sess != null && glob.sess.ui != null && glob.sess.ui.gui != null) {
	    mapView = glob.sess.ui.gui.map;
	    viewportSize = (mapView != null) ? mapView.sz : null;
	} else {
        viewportSize = null;
        mapView = null;
    }

	java.util.concurrent.atomic.AtomicInteger culledCount = new java.util.concurrent.atomic.AtomicInteger(0);
	java.util.concurrent.atomic.AtomicInteger renderedCount = new java.util.concurrent.atomic.AtomicInteger(0);
	java.util.concurrent.atomic.AtomicInteger virtualCount = new java.util.concurrent.atomic.AtomicInteger(0);

	if(!Config.par.get()) {
	    copy.forEach(ob -> {
		    if(ob.virtual) {
			virtualCount.incrementAndGet();
		    }

		    if(mapView != null && viewportSize != null && !ob.virtual) {
			if(shouldCullGob(ob, mapView, viewportSize)) {
			    if(!ob.culled) {
				mapView.cullGob(ob);
				ob.culled = true;
			    }
			    culledCount.incrementAndGet();
			    return;
			} else {
			    if(ob.culled) {
				mapView.uncullGob(ob);
				ob.culled = false;
			    }
			}
		    }

		    synchronized(ob) {
			ob.gtick(g);
		    }
		    renderedCount.incrementAndGet();
		});
	} else {
	    Collection<Render> subs = new ArrayList<>();
	    ThreadLocal<Render> subv = new ThreadLocal<>();
	    copy.parallelStream().forEach(ob -> {
		    if(ob.virtual) {
			virtualCount.incrementAndGet();
		    }

		    if(mapView != null && viewportSize != null && !ob.virtual) {
			if(shouldCullGob(ob, mapView, viewportSize)) {
			    if(!ob.culled) {
				mapView.cullGob(ob);
				ob.culled = true;
			    }
			    culledCount.incrementAndGet();
			    return;
			} else {
			    if(ob.culled) {
				mapView.uncullGob(ob);
				ob.culled = false;
			    }
			}
		    }

		    Render sub = subv.get();
		    if(sub == null) {
			sub = g.env().render();
			synchronized(subs) {
			    subs.add(sub);
			}
			subv.set(sub);
		    }
		    synchronized(ob) {
			ob.gtick(sub);
		    }
		    renderedCount.incrementAndGet();
		});
	    for(Render sub : subs)
		g.submit(sub);
	}
    }

    private boolean shouldCullGob(Gob ob, MapView mapView, Coord viewportSize) {
        if (OptWnd.onlyRenderCameraVisibleObjectsCheckBox.a) {
            try {
                /* Never the player. MapView.Gobs.shouldCullGobOnAdd - the same predicate, applied
                 * when a gob joins the render tree - has always exempted them, and this copy did
                 * not, so with the option on the character could be dropped from rendering the
                 * moment it drifted off screen and put back when it returned.
                 *
                 * Compared against plgob rather than the isMe flag on purpose: isMe latches once
                 * and permanently the first time the gob ticks, so a gob that ticks while plgob
                 * still holds the previous character's id - which happens on a character switch,
                 * and while plgob briefly reads -1 during a handover - latches false for the rest
                 * of the session and never recovers. The id comparison cannot go stale. */
                if (ob.id == mapView.plgob)
                    return false;
                Coord3f gc = ob.getc();
                if (gc == null) {
                    return false;
                }
                Coord3f screenPos3f = mapView.screenxf(gc);
                if (screenPos3f == null) {
                    return false;
                }
                Coord screenPos = screenPos3f.round2();
                if (screenPos == null) {
                    return false;
                }
                int margin = 50;
                if (screenPos.x < -margin || screenPos.x > viewportSize.x + margin ||
                        screenPos.y < -margin || screenPos.y > viewportSize.y + margin) {
                    return true;
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }


    @SuppressWarnings("unchecked")
    public Iterator<Gob> iterator() {
	Collection<Iterator<Gob>> is = new LinkedList<Iterator<Gob>>();
	for(Collection<Gob> gc : local)
	    is.add(gc.iterator());
	return(new I2<Gob>(objs.values().iterator(), new I2<Gob>(is)));
    }

    public void ladd(Collection<Gob> gob) {
	Collection<ChangeCallback> cbs;
	synchronized(this) {
	    cbs = new ArrayList<>(this.cbs);
	    local.add(gob);
	}
	for(Gob g : gob) {
	    synchronized(g) {
		for(ChangeCallback cb : cbs)
		    cb.added(g);
	    }
	}
    }

    public void lrem(Collection<Gob> gob) {
	Collection<ChangeCallback> cbs;
	synchronized(this) {
	    cbs = new ArrayList<>(this.cbs);
	    local.remove(gob);
	}
	for(Gob g : gob) {
	    synchronized(g) {
		for(ChangeCallback cb : cbs)
		    cb.removed(g);
	    }
	}
    }

    public synchronized Gob getgob(long id) {
	return(objs.get(id));
    }

    private java.util.concurrent.atomic.AtomicLong nextvirt = new java.util.concurrent.atomic.AtomicLong(-1);
    public class Virtual extends Gob {
	public Virtual(Coord2d c, double a) {
	    super(OCache.this.glob, c, nextvirt.getAndDecrement());
	    this.a = a;
	    virtual = true;
	}
    }

    public class FixedPlace extends Virtual {
	public final Coord3f fc;

	public FixedPlace(Coord3f fc, double a) {
	    super(Coord2d.of(fc), a);
	    this.fc = fc;
	}

	public FixedPlace() {
	    this(Coord3f.o, 0);
	}

	public Coord3f getc() {
	    return(fc);
	}

	protected Pipe.Op getmapstate(Coord3f pc) {
	    return(null);
	}
    }

    public interface Delta {
	public void apply(Gob gob, AttrDelta msg);

	public static Indir<Resource> getres(Gob gob, int id) {
	    return(gob.glob.sess.getres(id));
	}
    }

    @dolda.jglob.Discoverable
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface DeltaType {
	public int value();
    }
    private static final Map<Integer, Delta> deltas = new HashMap<>();
    static {
	deltas: for(Class<?> cl : dolda.jglob.Loader.get(DeltaType.class).classes()) {
	    int id = cl.getAnnotation(DeltaType.class).value();
	    if(Delta.class.isAssignableFrom(cl)) {
		try {
		    Constructor<? extends Delta> cons = cl.asSubclass(Delta.class).getConstructor();
		    deltas.put(id, Utils.construct(cons));
		    continue deltas;
		} catch(NoSuchMethodException e) {}
	    }
	    throw(new Error("Illegal objdelta class: " + cl));
	}
    }

    @DeltaType(OD_MOVE)
    public static class $move implements Delta {
	public void apply(Gob g, AttrDelta msg) {
	    Coord2d c = msg.coord().mul(posres);
	    double a = (msg.uint16() / 65536.0) * Math.PI * 2;
	    g.move(c, a);
	}
    }

    public static class OlSprite implements Sprite.Mill<Sprite> {
	public final Indir<Resource> res;
	public byte[] sdt;

	public OlSprite(Indir<Resource> res, byte[] sdt) {
	    this.res = res;
	    this.sdt = sdt;
	}

	public Sprite create(Sprite.Owner owner) {
	    return(Sprite.create(owner, res.get(), new MessageBuf(sdt)));
	}

	public String toString() {
	    return(String.format("#<ol-mill %s %s>", res, Utils.hex.enc(sdt)));
	}
    }

    @DeltaType(OD_OVERLAY)
    public static class $overlay implements Delta {
	public void apply(Gob g, AttrDelta msg) {
	    int olidf = msg.int32();
	    boolean prs = (olidf & 1) != 0;
	    int olid = olidf >>> 1;
	    int resid = msg.uint16();
	    Indir<Resource> res;
	    byte[] sdt;
	    if(resid == 65535) {
		res = null;
		sdt = new byte[0];
	    } else {
		if((resid & 0x8000) != 0) {
		    resid &= ~0x8000;
		    sdt = msg.bytes(msg.uint8());
		} else {
		    sdt = new byte[0];
		}
		res = Delta.getres(g, resid);
	    }
	    Gob.Overlay ol = g.findol(olid);
	    if(res != null) {
		Gob.Overlay nol = null;
		if(ol == null) {
		    if(prs || (g.lastolid == 0) || (Gob.olidcmp(olid, g.lastolid) > 0)) {
			nol = new Gob.Overlay(g, olid, new OlSprite(res, sdt));
			nol.old = msg.old;
			g.addol(nol, false);
			if(!prs)
			    g.lastolid = olid;
		    }
		} else {
		    OlSprite os = (ol.sm instanceof OlSprite) ? (OlSprite)ol.sm : null;
		    if((os != null) && Arrays.equals(os.sdt, sdt)) {
		    } else if((os != null) && (ol.spr instanceof Sprite.CUpd)) {
			((Sprite.CUpd)ol.spr).update(new MessageBuf(sdt));
			os.sdt = sdt;
		    } else {
			nol = new Gob.Overlay(g, olid, new OlSprite(res, sdt));
			nol.old = msg.old;
			g.addol(nol, false);
			ol.remove(false);
		    }
		}
		if(nol != null)
		    nol.delign = prs;
	    } else {
		if(ol != null) {
		    if(ol.spr instanceof Sprite.CDel)
			((Sprite.CDel)ol.spr).delete();
		    else
			ol.remove(false);
		}
	    }
		g.updateDrawableStuff();
	}
    }

    @DeltaType(OD_RESATTR)
    public static class $resattr implements Delta {
	public void apply(Gob g, AttrDelta msg) {
	    Indir<Resource> resid = Delta.getres(g, msg.uint16());
	    int len = msg.uint8();
	    Message dat = (len > 0) ? new MessageBuf(msg.bytes(len)) : null;
	    resid.get().getcode(GAttrib.Parser.class, true).apply(g, dat);
		g.updateDrawableStuff();
	}
    }

    public class GobInfo {
	public final long id;
	public final LinkedList<AttrDelta> pending = new LinkedList<>();
	public int frame;
	public boolean nremoved, added, gremoved, virtual;
	public Gob gob;
	public Loader.Future<?> applier;

	public GobInfo(long id, int frame) {
	    this.id = id;
	    this.frame = frame;
	}

	private void apply() {
	    main: {
		synchronized(this) {
		    if(nremoved && (!added || gremoved))
			break main;
		    if(nremoved && added && !gremoved) {
			remove(gob);
			gob.updated();
			gremoved = true;
			gob = null;
			break main;
		    }
		    if(gob == null) {
			gob = new Gob(glob, Coord2d.z, id);
			gob.virtual = virtual;
		    }
		}
		while(true) {
		    AttrDelta d;
		    synchronized(this) {
			if((d = pending.peek()) == null)
			    break;
		    }
		    synchronized(gob) {
			deltas.get(d.type).apply(gob, d.clone());
		    }
		    synchronized(this) {
			if((pending.poll()) != d)
			    throw(new RuntimeException());
		    }
		}
		if(!added) {
		    add(gob);
		    added = true;
			try {
				synchronized (gob) {
					gob.init(false);
				}
			} catch (Exception e) {
			}
		}
		gob.updated();
	    }
	    synchronized(this) {
		applier = null;
		checkdirty(false);
	    }
	}

	public void checkdirty(boolean interrupt) {
	    synchronized(this) {
		if(applier == null) {
		    if(nremoved ? (added && !gremoved) : (!added || !pending.isEmpty())) {
			applier = glob.loader.defer(this::apply, null);
		    }
		} else if(interrupt) {
		    applier.restart();
		}
	    }
	}
    }

    private final Map<Long, GobInfo> netinfo = new HashMap<>();

    /* [LEAKDBG] Size probes for the heap-leak hunt, read once a second by the sampler.
     *
     * netremove below only sets nremoved - the XXX on the next line is upstream's own note - so an
     * entry survives until the SAME gob id comes back. If netinfo climbs while objs stays flat,
     * this map is retaining every object the character has ever walked past, and that is the leak.
     * Two numbers settle it; without them the class histogram is the only way to ask. */
    public int netinfosz() {
	synchronized(netinfo) {
	    return(netinfo.size());
	}
    }

    public int objsz() {
	synchronized(this) {
	    return(objs.size());
	}
    }

    private GobInfo netremove(long id, int frame) {
	synchronized(netinfo) {
	    GobInfo ng = netinfo.get(id);
	    if((ng == null) || (ng.frame > frame))
		return(null);
	    synchronized(ng) {
		/* XXX: Clean up removed objects */
		ng.nremoved = true;
		ng.checkdirty(true);
	    }
	    return(ng);
	}
    }

    private GobInfo netget(long id, int frame) {
	synchronized(netinfo) {
	    GobInfo ng = netinfo.get(id);
	    if((ng != null) && ng.nremoved) {
		if(ng.frame >= frame)
		    return(null);
		netinfo.remove(id);
		ng = null;
	    }
	    if(ng == null) {
		ng = new GobInfo(id, frame);
		netinfo.put(id, ng);
	    } else {
		if(ng.frame >= frame)
		    return(null);
	    }
	    return(ng);
	}
    }

    public static class ObjDelta {
	public int fl, frame;
	public int initframe;
	public long id;
	public final List<AttrDelta> attrs = new LinkedList<>();
	public boolean rem = false;

	public ObjDelta(int fl, long id, int frame) {
	    this.fl = fl;
	    this.id = id;
	    this.frame = frame;
	}

	public ObjDelta(ObjDelta from) {
	    this.fl = from.fl;
	    this.id = from.id;
	    this.frame = from.frame;
	    this.initframe = from.initframe;
	    this.rem = from.rem;
	    for(AttrDelta attr : from.attrs)
		attrs.add(attr.clone());
	}

	public ObjDelta() {}

	public ObjDelta clone() {
	    return(new ObjDelta(this));
	}
    }

    public static class AttrDelta extends PMessage {
	public boolean old;

	public AttrDelta(ObjDelta od, int type, byte[] blob) {
	    super(type, blob);
	    this.old = ((od.fl & 4) != 0);
	}

	public AttrDelta(ObjDelta od, int type, Message blob, int len) {
	    this(od, type, blob.bytes(len));
	}

	public AttrDelta(AttrDelta from) {
	    super(from);
	    this.old = from.old;
	}

	public AttrDelta clone() {
	    return(new AttrDelta(this));
	}
    }

    public GobInfo receive(ObjDelta delta) {
	if(delta.rem)
	    return(netremove(delta.id, delta.frame - 1));
	synchronized(netinfo) {
	    if(delta.initframe > 0)
		netremove(delta.id, delta.initframe - 1);
	    GobInfo ng = netget(delta.id, delta.frame);
	    if(ng != null) {
		synchronized(ng) {
		    ng.frame = delta.frame;
		    ng.virtual = ((delta.fl & 2) != 0);
		    ng.pending.addAll(delta.attrs);
		    ng.checkdirty(false);
		}
	    }
	    return(ng);
	}
    }

	public void gobAction(Consumer<Gob> action) {
		synchronized (this) {
			for (Gob g : this) {
				action.accept(g);
			}
			local.forEach(gobs -> gobs.forEach(action));
		}
	}
}
