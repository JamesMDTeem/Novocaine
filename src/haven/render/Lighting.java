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

package haven.render;

import java.util.*;
import java.nio.*;
import haven.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Function.PDir.*;
import static haven.render.sl.Type.*;

public interface Lighting {
    public static final State.Slot<State> lights = new State.Slot<>(State.Slot.Type.SYS, State.class);
    public static final Struct s_light = Struct.make(new Symbol.Shared("light"),
						     VEC4, "amb",
						     VEC4, "dif",
						     VEC4, "spc",
						     VEC4, "pos",
						     FLOAT, "ac",
						     FLOAT, "al",
						     FLOAT, "aq",
						     FLOAT, "at");

    public static abstract class LightList implements ShaderMacro {
	public abstract void construct(Block blk, java.util.function.Function<Params, Statement> body);

	public static class Params {
	    public Expression idx, lpar;

	    public Params(Expression idx, Expression lpar) {this.idx = idx; this.lpar = lpar;}
	}

	public void modify(ProgramContext prog) {
	    prog.module(this);
	}
    }

    public static class SimpleLights extends State {
	public static final boolean unroll = true;
	public static final int defmax = 4;
	private final Object[][] list;
	public int maxlights = defmax;

	public SimpleLights(Object[][] lights) {
	    this.list = lights;
	}

	public static class Shader implements ShaderMacro {
	    public final int maxlights;

	    public Shader(int maxlights) {
		this.maxlights = maxlights;
	    }

	    public void modify(ProgramContext prog) {
		prog.module(new LightList() {
			/* Clamped to the size of the array it indexes. The list is
			 * every light in the scene - LightList never caps it, and
			 * SimpleLights stores it whole - while the uniform array
			 * below is maxlights long, and UniformApplier writes only
			 * the first maxlights of them. An unclamped count sends the
			 * loop past the last entry that was ever written, reading
			 * whatever is adjacent in uniform memory as a light: the
			 * observed result is the whole screen saturating to red once
			 * a base grows past the limit, which is also why it appears
			 * "after some time" rather than on arrival.
			 *
			 * Dropping the excess is the documented behaviour of this
			 * mode, not a workaround for the overrun - the option's own
			 * tooltip says the limit caps the total number of light
			 * sources globally. */
			final Uniform u_nlights = new Uniform(INT, "nlights", p -> Math.min(((SimpleLights)p.get(lights)).list.length, maxlights), lights);
			final Uniform u_lights = new Uniform(new Array(s_light, maxlights), "lights", p -> ((SimpleLights)p.get(lights)).list, lights);

			public void construct(Block blk, java.util.function.Function<Params, Statement> body) {
			    if(!unroll || (maxlights > 4)) {
				Variable i = blk.local(INT, "i", null);
				blk.add(new For(ass(i, l(0)), lt(i.ref(), u_nlights.ref()), linc(i.ref()),
						body.apply(new Params(i.ref(), idx(u_lights.ref(), i.ref())))));
			    } else {
				for(int i = 0; i < maxlights; i++) {
				    /* Some old drivers and/or hardware seem to be
				     * having trouble with the for loop. Might not be
				     * as much of a problem these days as it used to
				     * be, but keep this for now, especially if
				     * SimpleLights are to be more of a legacy
				     * concern. */
				    blk.add(new If(gt(u_nlights.ref(), l(i)),
						   body.apply(new Params(l(i), idx(u_lights.ref(), l(i))))));
				}
			    }
			}
		    });
	    }

	    public int hashCode() {
		return(maxlights);
	    }
	    public boolean equals(Shader o) {
		return(maxlights == o.maxlights);
	    }
	    public boolean equals(Object o) {
		return((o instanceof Shader) && equals((Shader)o));
	    }

	    private static WeakHashedSet<Shader> interned = new WeakHashedSet<>(Hash.eq);
	    public static ShaderMacro get(int maxlights) {
		synchronized(interned) {
		    return(interned.intern(new Shader(maxlights)));
		}
	    }
	}

	private ShaderMacro shader = null;
	public ShaderMacro shader() {
	    if(shader == null)
		shader = Shader.get(maxlights);
	    return(shader);
	}

	public void apply(Pipe p) {
	    p.put(lights, this);
	}
    }

    public static class LightGrid {
	public static final boolean visnlights = false;
	public static final boolean stats = false;
	public static final int defmax = 16;
	public final int w, h, d;
	public final int wb, hb, db;
	public int maxlights = defmax;
	private final int lswb;
	private GridLights last, prev;
	private Object[][] lastlights;

	public LightGrid(int w, int h, int d) {
	    if(w != Integer.highestOneBit(w)) throw(new IllegalArgumentException("not a power of two: " + w));
	    if(h != Integer.highestOneBit(h)) throw(new IllegalArgumentException("not a power of two: " + h));
	    if(d != Integer.highestOneBit(d)) throw(new IllegalArgumentException("not a power of two: " + d));
	    this.w = w;
	    this.h = h;
	    this.d = d;
	    this.wb = Integer.numberOfTrailingZeros(w);
	    this.hb = Integer.numberOfTrailingZeros(h);
	    this.db = Integer.numberOfTrailingZeros(d);
	    lswb = (wb + hb + db + 1) / 2;
	}

	/* Tolerances for deciding that nothing has changed since the last
	 * compile. The light parameters carry float positions and attenuations
	 * that jitter in the last few digits as the camera eases, so an exact
	 * comparison reports "changed" on essentially every frame of a scene
	 * that is visually static. LIGHT_EPS is what counts as the same light.
	 *
	 * BBOX_SNAP is coarser and does a different job: the grid is built over
	 * the view volume, so the volume is snapped outwards to a multiple of
	 * BBOX_SNAP before anything is derived from it. A camera drifting within
	 * one snap step then lands on a bit-identical volume, which is what
	 * makes the reuse below hit at all during normal movement. It is in
	 * world units and deliberately tile-scale: the grid is 64 cells across
	 * the view volume, so a snap much smaller than a cell would not stop the
	 * voxel assignment from shifting, and a snap much larger would inflate
	 * the volume enough to waste grid resolution. */
	private static final float LIGHT_EPS = 1e-3f;
	private static final float BBOX_SNAP = 4.0f;

	private static boolean feq(float a, float b) {
	    return((a == b) || (Math.abs(a - b) <= LIGHT_EPS));
	}

	/* Deep comparison of the light parameter arrays with the tolerance
	 * above. Mirrors the shape Arrays.deepEquals walks, but compares
	 * float[] and Float leaves with feq rather than bit equality. */
	private static boolean lightsEq(Object[][] a, Object[][] b) {
	    if(a == b)
		return(true);
	    if((a == null) || (b == null) || (a.length != b.length))
		return(false);
	    for(int i = 0; i < a.length; i++) {
		Object[] la = a[i], lb = b[i];
		if(la == lb)
		    continue;
		if((la == null) || (lb == null) || (la.length != lb.length))
		    return(false);
		for(int j = 0; j < la.length; j++) {
		    Object va = la[j], vb = lb[j];
		    if(va == vb)
			continue;
		    if((va == null) || (vb == null))
			return(false);
		    if((va instanceof float[]) && (vb instanceof float[])) {
			float[] fa = (float[])va, fb = (float[])vb;
			if(fa.length != fb.length)
			    return(false);
			for(int k = 0; k < fa.length; k++) {
			    if(!feq(fa[k], fb[k]))
				return(false);
			}
		    } else if((va instanceof Float) && (vb instanceof Float)) {
			if(!feq((Float)va, (Float)vb))
			    return(false);
		    } else if(!va.equals(vb)) {
			return(false);
		    }
		}
	    }
	    return(true);
	}

	private static Coord3f snapn(Coord3f c) {
	    return(Coord3f.of((float)Math.floor(c.x / BBOX_SNAP) * BBOX_SNAP,
			      (float)Math.floor(c.y / BBOX_SNAP) * BBOX_SNAP,
			      (float)Math.floor(c.z / BBOX_SNAP) * BBOX_SNAP));
	}

	private static Coord3f snapp(Coord3f c) {
	    return(Coord3f.of((float)Math.ceil(c.x / BBOX_SNAP) * BBOX_SNAP,
			      (float)Math.ceil(c.y / BBOX_SNAP) * BBOX_SNAP,
			      (float)Math.ceil(c.z / BBOX_SNAP) * BBOX_SNAP));
	}

	private static final Hash<short[]> sahash = new Hash<short[]>() {
		public int hash(short[] ob) {return(Arrays.hashCode(ob));}
		public boolean equal(short[] x, short[] y) {return(Arrays.equals(x, y));}
	    };

	private static final Coord3f[] clipcorn = {
	    Coord3f.of(-1, -1, -1), Coord3f.of( 1, -1, -1), Coord3f.of( 1,  1, -1), Coord3f.of(-1,  1, -1),
	    Coord3f.of(-1, -1,  1), Coord3f.of( 1, -1,  1), Coord3f.of( 1,  1,  1), Coord3f.of(-1,  1,  1),
	};

	private class Compiler {
	    final Volume3f bbox;
	    final Coord3f gsz, szf;
	    final Collection<Short> global = new ArrayList<>();
	    final short[] grid = new short[w * h * d];
	    short[] listbuf = new short[256];
	    int lboff = 0;
	    short[][] lists = new short[][] {new short[0]};
	    short[][] table = new short[32][];
	    int nlists = 1;
	    int maxlist = 0;

	    Compiler(Volume3f bbox) {
		this.bbox = bbox;
		gsz = bbox.sz().div(w, h, d);
		szf = Coord3f.of(1, 1, 1).div(gsz);
	    }

	    int us(short v) {
		return(v & 0xffff);
	    }

	    void rehash(int nlen) {
		short[][] ntab = new short[nlen][];
		short[] ntnum = new short[nlen];
		for(int b = 0; b < table.length; b++) {
		    if(table[b] == null)
			continue;
		    for(int bi = 0; (bi < table[b].length) && (table[b][bi] != -1); bi++) {
			int ln = us(table[b][bi]);
			short[] list = lists[ln];
			int hash = 0;
			for(int i = 0; i < list.length; i++)
			    hash = (hash * 31) + list[i];
			int nb = hash & (nlen - 1);
			if(ntab[nb] == null) {
			    ntab[nb] = new short[] {(short)ln, -1, -1, -1};
			    ntnum[nb] = 1;
			} else {
			    int tlen = us(ntnum[nb]);
			    if(tlen == ntab[nb].length) {
				ntab[nb] = Arrays.copyOf(ntab[nb], tlen * 2);
				Arrays.fill(ntab[nb], tlen, tlen * 2, (short)-1);
			    }
			    ntab[nb][tlen] = (short)ln;
			    ntnum[nb] = (short)(tlen + 1);
			}
		    }
		}
		table = ntab;
	    }

	    void ckrehash() {
		if(nlists >= table.length / 2)
		    rehash(table.length * 2);
	    }

	    short addlist(short[] plist, short add) {
		if(nlists == lists.length)
		    lists = Arrays.copyOf(lists, lists.length * 2);
		lists[nlists] = Arrays.copyOf(plist, plist.length + 1);
		lists[nlists][plist.length] = add;
		maxlist = Math.max(maxlist, lists[nlists].length);
		return((short)nlists++);
	    }

	    short getlist(short[] plist, short add) {
		int hash = 0;
		for(int i = 0; i < plist.length; i++)
		    hash = (hash * 31) + plist[i];
		hash = (hash * 31) + add;
		int b = hash & (table.length - 1);
		if(table[b] == null) {
		    short ret = addlist(plist, add);
		    table[b] = new short[] {ret, -1, -1, -1};
		    ckrehash();
		    return(ret);
		} else {
		    list: for(int bi = 0; bi < table[b].length; bi++) {
			if(table[b][bi] == -1) {
			    short ret = table[b][bi] = addlist(plist, add);
			    ckrehash();
			    return(ret);
			}
			int ln = us(table[b][bi]);
			if((lists[ln].length == (plist.length + 1))) {
			    for(int li = 0; li < plist.length; li++) {
				if(plist[li] != lists[ln][li])
				    continue list;
			    }
			    if(lists[ln][plist.length] == add)
				return((short)ln);
			}
		    }
		    int n = table[b].length;
		    table[b] = Arrays.copyOf(table[b], n * 2);
		    Arrays.fill(table[b], n, table[b].length, (short)-1);
		    short ret = table[b][n] = addlist(plist, add);
		    ckrehash();
		    return(ret);
		}
	    }

	    void addpoint(int idx, Object[] light, float[] pos) {
		float lx = pos[0], ly = pos[1], lz = pos[2];
		Coord3f lc = Coord3f.of(lx, ly, lz);
		float ac = (Float)light[4];
		float al = (Float)light[5];
		float aq = (Float)light[6];
		float at = (Float)light[7];
		float aqi = 1f / aq;
		float r = -(al * aqi * 0.5f) + (float)Math.sqrt((aqi / at) - (ac * aqi) + (al * al * aqi * aqi * 0.25f));
		if(bbox.closest(lc).dist(lc) > r) {
		    Debug.statprint(Utils.formatter("Light %d: Out-of-bounds", idx), stats);
		    return;
		}
		int nz = (int)Math.floor((lz - r - bbox.n.z) * szf.z), pz = (int)Math.ceil((lz + r - bbox.n.z) * szf.z);
		int lightlim = maxlights - global.size();
		for(int gz = Math.max(nz, 0); gz < Math.min(pz, d); gz++) {
		    float gnz = (gz * gsz.z) + bbox.n.z, gpz = gnz + gsz.z;
		    float cz = Utils.clip(lz, gnz, gpz), dz = lz - cz;
		    float ey = (float)Math.sqrt((r * r) - (dz * dz));
		    int ny = (int)Math.floor((ly - ey - bbox.n.y) * szf.y), py = (int)Math.ceil((ly + ey - bbox.n.y) * szf.y);
		    for(int gy = Math.max(ny, 0); gy < Math.min(py, h); gy++) {
			float gny = (gy * gsz.y) + bbox.n.y, gpy = gny + gsz.y;
			float cy = Utils.clip(ly, gny, gpy), dy = ly - cy;
			float ex = (float)Math.sqrt(((r * r) - (dy * dy) - (dz * dz)));
			int nx = (int)Math.floor((lx - ex - bbox.n.x) * szf.x), px = (int)Math.ceil((lx + ex - bbox.n.x) * szf.x);
			int ygi = (gy * w) + (gz * w * h);
			int lgi = Math.max(nx, 0) + ygi, hgi = Math.min(px, w) + ygi;
			for(int gri = lgi; gri < hgi; gri++) {
			    if(lists[us(grid[gri])].length >= lightlim) {
				Debug.statprint(Utils.formatter("Light %d: Disabled", idx), stats);
				return;
			    }
			}
		    }
		}
		int ng = 0;
		short cpval = -1, cnval = -1;
		for(int gz = Math.max(nz, 0); gz < Math.min(pz, d); gz++) {
		    float gnz = (gz * gsz.z) + bbox.n.z, gpz = gnz + gsz.z;
		    float cz = Utils.clip(lz, gnz, gpz), dz = lz - cz;
		    float ey = (float)Math.sqrt((r * r) - (dz * dz));
		    int ny = (int)Math.floor((ly - ey - bbox.n.y) * szf.y), py = (int)Math.ceil((ly + ey - bbox.n.y) * szf.y);
		    for(int gy = Math.max(ny, 0); gy < Math.min(py, h); gy++) {
			float gny = (gy * gsz.y) + bbox.n.y, gpy = gny + gsz.y;
			float cy = Utils.clip(ly, gny, gpy), dy = ly - cy;
			float ex = (float)Math.sqrt(((r * r) - (dy * dy) - (dz * dz)));
			int nx = (int)Math.floor((lx - ex - bbox.n.x) * szf.x), px = (int)Math.ceil((lx + ex - bbox.n.x) * szf.x);
			int ygi = (gy * w) + (gz * w * h);
			int lgi = Math.max(nx, 0) + ygi, hgi = Math.min(px, w) + ygi;
			ng += Math.max(hgi - lgi, 0);
			x: for(int gri = lgi; gri < hgi; gri++) {
			    short val;
			    while(true) {
				if(gri >= hgi)
				    break x;
				if((val = grid[gri]) != cpval)
				    break;
				grid[gri++] = cnval;
			    }
			    cpval = val;
			    cnval = grid[gri] = getlist(lists[grid[gri]], (short)idx);
			}
		    }
		}
		Debug.statprint(Utils.formatter("Light %d: %.2f %,d", idx, r, ng), stats);
	    }

	    void addglobal(int idx, Object[] light) {
		if(global.size() + maxlist >= maxlights)
		    return;
		global.add((short)idx);
	    }

	    void addlight(int idx, Object[] light) {
		float[] pos = (float[])light[3];
		if(pos[3] == 0) {
		    addglobal(idx, light);
		} else {
		    addpoint(idx, light, pos);
		}
	    }

	    void compact() {
		short[] conv = new short[nlists];
		Arrays.fill(conv, (short)-1);
		for(int i = 0; i < grid.length; i++) {
		    short cval = conv[grid[i]];
		    if(cval == -1) {
			cval = conv[grid[i]] = (short)lboff;
			short[] list = lists[grid[i]];
			while(listbuf.length < lboff + global.size() + list.length + 1)
			    listbuf = Arrays.copyOf(listbuf, listbuf.length * 2);
			for(short gidx : global)
			    listbuf[lboff++] = gidx;
			for(int o = 0; o < list.length; o++)
			    listbuf[lboff++] = list[o];
			listbuf[lboff++] = -1;
		    }
		    grid[i] = cval;
		}
	    }

	    void dump() {
		try(java.io.BufferedWriter fp = java.nio.file.Files.newBufferedWriter(Debug.somedir("clight-dump"))) {
		    for(int z = 0; z < d; z++) {
			fp.write(Integer.toString(z));
			fp.write('\n');
			for(int y = 0; y < h; y++) {
			    for(int x = 0; x < w; x++)
				fp.write('0' + grid[x + (y * w) + (z * w * h)]);
			    fp.write('\n');
			}
			fp.write('\n');
		    }
		    for(int i = 0; i < nlists; i++) {
			fp.write(String.format("%d:", i));
			for(short v : lists[i]) {
			    fp.write(' ');
			    fp.write(Integer.toString(v));
			}
			fp.write('\n');
		    }
		} catch(java.io.IOException e) {
		    e.printStackTrace();
		}
	    }
	}

	/* The view volume the grid is built over, snapped outwards to BBOX_SNAP.
	 * Eight corner transforms, which is cheap enough to run before deciding
	 * whether a rebuild is needed at all. */
	private Volume3f viewbox(Projection proj) {
	    Matrix4f iproj = proj.fin(Matrix4f.id).invert();
	    Volume3f bbox = Volume3f.point(Coord3f.of(HomoCoord4f.fromindc(iproj, clipcorn[0])));
	    for(int i = 1; i < clipcorn.length; i++)
		bbox = bbox.include(Coord3f.of(HomoCoord4f.fromindc(iproj, clipcorn[i])));
	    return(Volume3f.corn(snapn(bbox.n), snapp(bbox.p)));
	}

	public State compile(Object[][] lights, Projection proj) {
	    /* The light grid is rebuilt from scratch on every draw otherwise,
	     * churning two texture uploads per frame even in a fully static
	     * scene.
	     *
	     * The projection only reaches the grid through the view volume, so
	     * that is what gets compared rather than the projection matrix: an
	     * easing camera perturbs the matrix every frame but keeps landing in
	     * the same snapped volume, and the grid built from it would come out
	     * identical. Comparing the matrix instead means a rebuild on every
	     * frame the camera is in motion, which is most of them.
	     *
	     * Both halves of the state have to match to reuse it. The grid says
	     * which lights reach each voxel, not what those lights are, so the
	     * volume agreeing is not on its own enough - the light data texture
	     * is built from the parameters and goes stale if they have changed.
	     * The bbox is carried by the state too, as the shader's coordinate
	     * transform reads it. */
	    Volume3f bbox = viewbox(proj);
	    if((last != null) && (lastlights != null) && lightsEq(lastlights, lights) && last.bbox.equals(bbox))
		return(last);
	    Compiler c = new Compiler(bbox);
	    int n = Math.min(lights.length, 65535);
	    for(int i = 0; i < n; i++)
		c.addlight(i, lights[i]);
	    c.compact();
	    Debug.statprint(Utils.formatter("C-lights: %d lists, max %d, bounds %s, cell %s", c.nlists, c.maxlist, c.bbox, c.gsz), stats);
	    /* KamiClient: this used to dispose the previous grid right here, but the light
	     * recompile runs outside the tree lock - the loader thread can be halfway
	     * through building drawlist settings off the old grid's textures, and it'd
	     * blow up on a use-after-free. Hold it one extra compile before letting go:
	     * by then the frame that could still have been reading it is long done.
	     * Can't just leak it to the finalizer either - with ZONED lighting this runs
	     * every frame, and that's two textures a frame for GC to chase. */
	    if(prev != null)
		prev.dispose();
	    prev = last;
	    lastlights = lights;
	    return(last = new GridLights(lights, c.bbox, c.grid, c.listbuf, c.lboff));
	}

	private static final Uniform u_bboxm = new Uniform(VEC3, "lboxm", p -> ((GridLights)p.get(lights)).bboxm(), lights);
	private static final Uniform u_bboxk = new Uniform(VEC3, "lboxk", p -> ((GridLights)p.get(lights)).bboxk(), lights);
	private static final Uniform u_lstex = new Uniform(USAMPLER2D, "lstex", p -> ((GridLights)p.get(lights)).lstex, lights);
	private static final Uniform u_ldtex = new Uniform(SAMPLER2D, "ldtex", p -> ((GridLights)p.get(lights)).ldtex, lights);
	private static class Shader implements ShaderMacro {
	    private final int w, h, d, wb, hb, db, lswb, maxlights;

	    Shader(LightGrid pars) {
		this.w = pars.w; this.h = pars.h; this.d = pars.d;
		this.wb = pars.wb; this.hb = pars.hb; this.db = pars.db;
		this.lswb = pars.lswb;
		this.maxlights = pars.maxlights;
	    }

	    public void modify(ProgramContext prog) {
		Function getlist = new Function.Def(UINT, "getlist") {{
		    Expression gc = code.local(IVEC3, clamp(ivec3(mul(add(Homo3D.frageyev.ref(), u_bboxm.ref()), u_bboxk.ref())),
							    ivec3(0, 0, 0), ivec3(w - 1, h - 1, d - 1))).ref();
		    Expression gidx = code.local(INT, add(pick(gc, "x"), lshift(pick(gc, "y"), l(wb)), lshift(pick(gc, "z"), l(wb + hb)))).ref();
		    Expression lsidx = pick(texelFetch(u_lstex.ref(), ivec2(bitand(gidx, l((1 << lswb) - 1)), rshift(gidx, l(lswb))), l(0)), "r");
		    code.add(new Return(lsidx));
		}};

		Function getlidx = new Function.Def(UINT, "getlidx") {{
		    Expression lsidx = param(IN, UINT).ref();
		    Expression lidx = pick(texelFetch(u_lstex.ref(), ivec2(bitand(lsidx, ul((1 << lswb) - 1)), add(rshift(lsidx, l(lswb)), ul(1 << (wb + hb + db - lswb)))), l(0)), "r");
		    code.add(new Return(lidx));
		}};

		Function getlight = new Function.Def(s_light, "getlight") {{
		    Expression lidx = param(IN, UINT).ref();
		    Expression base = code.local(IVEC2, ivec2(mul(bitand(lidx, ul((1 << 5) - 1)), ul(5)), rshift(lidx, l(5)))).ref();
		    Expression amb = code.local(VEC4, texelFetch(u_ldtex.ref(), base, l(0))).ref();
		    Expression dif = code.local(VEC4, texelFetch(u_ldtex.ref(), add(base, ivec2(l(1), l(0))), l(0))).ref();
		    Expression spc = code.local(VEC4, texelFetch(u_ldtex.ref(), add(base, ivec2(l(2), l(0))), l(0))).ref();
		    Expression pos = code.local(VEC4, texelFetch(u_ldtex.ref(), add(base, ivec2(l(3), l(0))), l(0))).ref();
		    Expression att = code.local(VEC4, texelFetch(u_ldtex.ref(), add(base, ivec2(l(4), l(0))), l(0))).ref();
		    code.add(new Return(s_light.construct(amb, dif, spc, pos, pick(att, "r"), pick(att, "g"), pick(att, "b"), pick(att, "a"))));
		}};

		prog.module(new LightList() {
			public void construct(Block blk, java.util.function.Function<Params, Statement> body) {
			    Expression lsidx = blk.local(UINT, getlist.call()).ref();
			    Variable i = blk.local(UINT, "i", null);
			    Variable lidx = blk.local(UINT, null);
			    blk.add(new For(ass(i, ul(0)), and(lt(i.ref(), ul(maxlights)), ne(ass(lidx, getlidx.call(add(lsidx, i.ref()))), ul(0xffff))), linc(i.ref()),
					    body.apply(new Params(intcons(lidx.ref()), getlight.call(lidx.ref())))));
			}
		    });

		Function vgapal = new Function.Def(VEC3, "vgapal") {{
		    Expression n = param(IN, INT).ref();
		    code.add(new Return(mul(add(vec3(floatcons(rshift(bitand(n, l(4)), l(1))),
						     floatcons(       bitand(n, l(2))),
						     floatcons(lshift(bitand(n, l(1)), l(1)))),
						vec3(floatcons(rshift(bitand(n, l(8)), l(3))))),
					    l(1.0 / 3.0))));
		}};
		Function nlights = new Function.Def(INT, "nlights") {{
		    Expression lsidx = code.local(UINT, getlist.call()).ref();
		    Variable i = code.local(UINT, "i", null);
		    Variable lidx = code.local(UINT, null);
		    code.add(new For(ass(i, ul(0)), and(lt(i.ref(), ul(maxlights)), ne(ass(lidx, getlidx.call(add(lsidx, i.ref()))), ul(0xffff))), linc(i.ref()),
				     new Block()));
		    code.add(new Return(intcons(i.ref())));
		}};

		if(visnlights) {
		    /* XXX? Not *entirely* sure why this is necessary.
		     *  What rendering with lighting is not already
		     *  using the Homo3D shader? */
		    Homo3D.get(prog);
		    FragColor.fragcol(prog.fctx).mod(in -> mix(in, vec4(vgapal.call(nlights.call()), l(1.0)), l(0.5)), 50000);
		}
	    }

	    public int hashCode() {
		return(Objects.hash(w, h, d, lswb, maxlights));
	    }
	    public boolean equals(Shader o) {
		return((w == o.w) && (h == o.h) && (d == o.d) && (lswb == o.lswb) && (maxlights == o.maxlights));
	    }
	    public boolean equals(Object o) {
		return((o instanceof Shader) && equals((Shader)o));
	    }

	    private static WeakHashedSet<Shader> interned = new WeakHashedSet<>(Hash.eq);
	    private static ShaderMacro get(LightGrid pars) {
		synchronized(interned) {
		    return(interned.intern(new Shader(pars)));
		}
	    }
	}

	private ShaderMacro shader = null;
	public ShaderMacro shader() {
	    if(shader == null)
		shader = Shader.get(LightGrid.this);
	    return(shader);
	}

	public class GridLights extends State implements Disposable {
	    public final Texture2D.Sampler2D ldtex, lstex;
	    public final Volume3f bbox;

	    public GridLights(Object[][] lights, Volume3f bbox, short[] grid, short[] lists, int listlen) {
		this.bbox = bbox;
		this.ldtex = new Texture2D.Sampler2D(lighttex(lights));
		this.lstex = new Texture2D.Sampler2D(listtex(grid, lists, listlen));
	    }

	    private Texture2D listtex(short[] grid, short[] lists, int listlen) {
		int tw = 1 << lswb;
		int th = (1 << (wb + hb + db - lswb)) + (Math.max(listlen - 1, 0) / tw) + 1;
		DataBuffer.Filler<Texture2D.Image> init = (img, env) -> {
		    if(img.level != 0)
			return(null);
		    FillBuffer ret = env.fillbuf(img);
		    ShortBuffer buf = ret.push().asShortBuffer();
		    buf.position(0);
		    buf.put(grid);
		    buf.put(lists, 0, listlen);
		    return(ret);
		};
		Texture2D tex = new Texture2D(tw, th, DataBuffer.Usage.STATIC, new VectorFormat(1, NumberFormat.UINT16), init);
		tex.desc = "lightgrid-list";
		return(tex);
	    }

	    private Texture2D lighttex(Object[][] lights) {
		int st = 5, lw = 32, tw = lw * st, th = (Math.max(lights.length - 1, 0) / lw) + 1;
		DataBuffer.Filler<Texture2D.Image> init = (img, env) -> {
		    if(img.level != 0)
			return(null);
		    FillBuffer ret = env.fillbuf(img);
		    FloatBuffer buf = ret.push().asFloatBuffer();
		    for(int i = 0; i < lights.length; i++) {
			int o = i * st * 4;
			float[] amb = (float[])lights[i][0];
			float[] dif = (float[])lights[i][1];
			float[] spc = (float[])lights[i][2];
			float[] pos = (float[])lights[i][3];
			float ac = (Float)lights[i][4];
			float al = (Float)lights[i][5];
			float aq = (Float)lights[i][6];
			float at = (Float)lights[i][7];
			buf.put(o +  0, amb[0]).put(o +  1, amb[1]).put(o +  2, amb[2]).put(o +  3, amb[3]);
			buf.put(o +  4, dif[0]).put(o +  5, dif[1]).put(o +  6, dif[2]).put(o +  7, dif[3]);
			buf.put(o +  8, spc[0]).put(o +  9, spc[1]).put(o + 10, spc[2]).put(o + 11, spc[3]);
			buf.put(o + 12, pos[0]).put(o + 13, pos[1]).put(o + 14, pos[2]).put(o + 15, pos[3]);
			buf.put(o + 16, ac).put(o + 17, al).put(o + 18, aq).put(o + 19, at);
		    }
		    return(ret);
		};
		Texture2D tex = new Texture2D(tw, th, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.FLOAT32), init);
		tex.desc = "lightgrid-light";
		return(tex);
	    }

	    public ShaderMacro shader() {return(LightGrid.this.shader());}

	    public void apply(Pipe p) {
		p.put(lights, this);
	    }

	    public Coord3f bboxm() {
		return(bbox.n.inv());
	    }

	    public Coord3f bboxk() {
		return(Coord3f.of(w, h, d).div(bbox.sz()));
	    }

	    public void dispose() {
		ldtex.dispose();
		lstex.dispose();
	    }
	}
    }
}
