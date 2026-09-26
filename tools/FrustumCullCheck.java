/*
 * FrustumList: what it leaves out, and what it keeps.
 *
 * Two halves. The geometry: over random cameras and boxes, the bounding-sphere test the list uses must
 * never leave out a box that brodgar-io-client's eight-corner test (copied here verbatim) keeps - that
 * is the "nothing partly on screen disappears" guarantee - and the check reports how much more the
 * sphere keeps. The bookkeeping, through the real class with real slots and a draw list that records
 * what it holds: out of view leaves the draw list and in view stays, a still frame tests nothing
 * again, a camera that moves tests again, turning culling off puts everything back, and removal keeps
 * the counts straight.
 *
 * NOT part of the client build. Run from the repo root (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\frustcheck tools\FrustumCullCheck.java
 *   java -cp "$env:TEMP\frustcheck;$CP" haven.render.FrustumCullCheck
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven.render;

import java.util.*;
import java.nio.FloatBuffer;
import haven.*;

public class FrustumCullCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    /* brodgar-io-client's FrustumList.inside, verbatim: all eight corners outside one side plane
     * (widened by k) or all behind the near plane. */
    static boolean boxinside(float[] m, float[] wb, float k) {
        boolean left = true, right = true, down = true, up = true, near = true;
        for(int i = 0; i < 24; i += 3) {
            float x = wb[i], y = wb[i + 1], z = wb[i + 2];
            float cx = (m[0] * x) + (m[4] * y) + (m[ 8] * z) + m[12];
            float cy = (m[1] * x) + (m[5] * y) + (m[ 9] * z) + m[13];
            float cz = (m[2] * x) + (m[6] * y) + (m[10] * z) + m[14];
            float cw = (m[3] * x) + (m[7] * y) + (m[11] * z) + m[15];
            float kw = k * cw;
            if(!(cx < -kw)) left = false;
            if(!(cx >  kw)) right = false;
            if(!(cy < -kw)) down = false;
            if(!(cy >  kw)) up = false;
            if(!(cz < -cw)) near = false;
        }
        return(!(left || right || down || up || near));
    }

    static Projection proj(float fov, float aspect) {
        float f = (float)Math.tan(fov / 2);
        return(Projection.frustum(-f, f, -aspect * f, aspect * f, 1, 5000));
    }

    static void geometry() {
        Random r = new Random(5);
        int n = 0, boxkept = 0, spherekept = 0, lost = 0;
        for(int c = 0; c < 400; c++) {
            Camera cam = Camera.pointed(Coord3f.of(r.nextFloat() * 400 - 200, r.nextFloat() * 400 - 200, 0),
                                        100 + r.nextFloat() * 700, 0.2f + r.nextFloat() * 1.2f, r.nextFloat() * 6.28f);
            Projection prj = proj(0.5f + r.nextFloat() * 0.8f, 0.5f + r.nextFloat());
            float[] clip = prj.fin(Matrix4f.id).mul(cam.fin(Matrix4f.id)).m;
            FrustumList.Planes pl = new FrustumList.Planes(clip.clone());
            for(int b = 0; b < 200; b++) {
                float cx = r.nextFloat() * 3000 - 1500, cy = r.nextFloat() * 3000 - 1500, cz = r.nextFloat() * 60 - 10;
                float hx = 0.5f + r.nextFloat() * 60, hy = 0.5f + r.nextFloat() * 60, hz = 0.5f + r.nextFloat() * 40;
                float[] wb = new float[24];
                for(int i = 0; i < 8; i++) {
                    wb[i * 3]     = cx + (((i & 1) == 0) ? -hx : hx);
                    wb[i * 3 + 1] = cy + (((i & 2) == 0) ? -hy : hy);
                    wb[i * 3 + 2] = cz + (((i & 4) == 0) ? -hz : hz);
                }
                float rad = (float)Math.sqrt((hx * hx) + (hy * hy) + (hz * hz));
                boolean bk = boxinside(clip, wb, 1 + FrustumList.ENTER);
                boolean sk = pl.sphere(pl.enter, cx, cy, cz, rad);
                n++;
                if(bk) boxkept++;
                if(sk) spherekept++;
                if(bk && !sk) lost++;
            }
        }
        System.out.printf("  %d boxes: the box test keeps %d, the sphere test %d%n", n, boxkept, spherekept);
        check(lost == 0, "the sphere test never leaves out a box the box test keeps (" + lost + " lost)");
        check(spherekept < n, "and it does leave things out (" + (n - spherekept) + " culled)");
    }

    /* A slot as the instancer hands one over: a mesh, and a state with a location, camera, projection. */
    static class TSlot implements RenderList.Slot<Rendered> {
        final FastMesh mesh;
        Location.Chain loc;
        Camera cam;
        Projection prj;

        TSlot(FastMesh mesh, Coord3f at, Camera cam, Projection prj) {
            this.mesh = mesh;
            this.cam = cam;
            this.prj = prj;
            move(at);
        }

        void move(Coord3f at) {
            BufPipe p = new BufPipe();
            new Location(Transform.makexlate(new Matrix4f(), at)).apply(p);
            loc = p.get(Homo3D.loc);
        }

        public GroupPipe state() {
            return(new GroupPipe() {
                    @SuppressWarnings("unchecked")
                    public <T extends State> T get(State.Slot<T> slot) {
                        if(slot == Homo3D.loc) return((T)loc);
                        if(slot == Homo3D.cam) return((T)cam);
                        if(slot == Homo3D.prj) return((T)prj);
                        return(null);
                    }
                    public Pipe group(int g) {return(Pipe.nil);}
                    public int gstate(int id) {return(-1);}
                    public int nstates() {return(0);}
                    public Pipe copy() {return(this);}
                    public State[] states() {return(new State[0]);}
                });
        }

        public Rendered obj() {return(mesh);}
    }

    /* The draw list, reduced to what it holds. */
    static class Recording implements RenderList<Rendered> {
        final Set<RenderList.Slot<? extends Rendered>> held = new HashSet<>();
        public void add(RenderList.Slot<? extends Rendered> s) {if(!held.add(s)) throw(new AssertionError("added twice"));}
        public void remove(RenderList.Slot<? extends Rendered> s) {if(!held.remove(s)) throw(new AssertionError("removed absent"));}
        public void update(RenderList.Slot<? extends Rendered> s) {}
        public void update(Pipe group, int[] statemask) {}
    }

    static FastMesh cube(float h) {
        FloatBuffer v = Utils.wfbuf(8 * 3);
        for(int i = 0; i < 8; i++) {
            v.put(((i & 1) == 0) ? -h : h);
            v.put(((i & 2) == 0) ? -h : h);
            v.put(((i & 4) == 0) ? -h : h);
        }
        v.rewind();
        short[] ind = {0, 1, 2, 1, 3, 2, 4, 6, 5, 5, 6, 7, 0, 4, 1, 1, 4, 5};
        return(new FastMesh(new VertexBuf(new VertexBuf.VertexData(v)), ind));
    }

    static void bookkeeping() {
        /* A camera at the origin looking along one axis; one slot in front, one behind, one far to
         * the side. Which way "in front" is comes from the camera itself: the three are placed along
         * the axis the clip matrix says is visible. */
        Camera cam = Camera.pointed(Coord3f.o, 300, 0.5f, 0f);
        Projection prj = proj(1.0f, 1.0f);
        float[] clip = prj.fin(Matrix4f.id).mul(cam.fin(Matrix4f.id)).m;
        Coord3f front = null, back = null;
        for(float a = 0; a < 6.3f; a += 0.1f) {
            Coord3f c = Coord3f.of((float)Math.cos(a) * 200, (float)Math.sin(a) * 200, 0);
            boolean in = boxinside(clip, box(c, 5), 1f);
            if(in && (front == null)) front = c;
            if(!in && (back == null)) back = c;
        }
        check((front != null) && (back != null), "the test camera sees some directions and not others");
        if((front == null) || (back == null))
            return;
        FastMesh mesh = cube(5);
        TSlot sin = new TSlot(mesh, front, cam, prj), sout = new TSlot(mesh, back, cam, prj);
        Recording rec = new Recording();
        FrustumList fl = new FrustumList(rec);
        fl.add(sin);
        fl.add(sout);
        check(rec.held.size() == 2, "added while culling is off, both are drawn");
        fl.cull(true);
        check(rec.held.contains(sin) && !rec.held.contains(sout), "culling on: the one in view stays, the one out of view leaves");
        check((fl.culled() == 1) && (fl.cullable() == 2), "counted: 1 culled of 2 testable (" + fl.culled() + "/" + fl.cullable() + ")");
        fl.cull(true);
        check(fl.tested() == 0, "a frame in which nothing moved tests nothing again (" + fl.tested() + ")");
        sout.move(front.add(3, 0, 0));
        fl.cull(true);
        check(rec.held.contains(sout) && (fl.tested() == 1), "the one that moved into view is tested, and drawn (" + fl.tested() + " tested)");
        sout.move(back);
        sout.cam = Camera.pointed(Coord3f.o, 300, 0.5f, 0f);
        fl.cull(true);
        check(!rec.held.contains(sout), "moved back out of view, it leaves again");
        TSlot sadd = new TSlot(mesh, back, cam, prj);
        fl.add(sadd);
        check(!rec.held.contains(sadd) && (fl.culled() == 2), "a slot added out of view while culling is on is not drawn");
        fl.remove(sadd);
        check(fl.culled() == 1, "removing a culled slot takes it off the count");
        fl.cull(false);
        check(rec.held.size() == 2 && (fl.culled() == 0), "culling off puts everything back");
    }

    static float[] box(Coord3f c, float h) {
        float[] wb = new float[24];
        for(int i = 0; i < 8; i++) {
            wb[i * 3]     = c.x + (((i & 1) == 0) ? -h : h);
            wb[i * 3 + 1] = c.y + (((i & 2) == 0) ? -h : h);
            wb[i * 3 + 2] = c.z + (((i & 4) == 0) ? -h : h);
        }
        return(wb);
    }

    public static void main(String[] args) {
        geometry();
        bookkeeping();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
