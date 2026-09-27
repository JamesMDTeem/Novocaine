/*
 * Instanced batches by map grid, and FrustumList leaving out the ones off screen.
 *
 * InstanceList keys a batch by the map grid its members stand in and keeps the box round them
 * (after brodgar-io-client 0401da437, 2026-09-27); FrustumList tests that box. This holds:
 *
 * - the geometry: FrustumList.Planes.box decides exactly what brodgar's eight-corner test decides
 *   (copied verbatim in FrustumCullCheck) over random cameras and boxes, both ways;
 * - the bookkeeping, through a real InstanceList with real slots: members of one grid batch, of two
 *   grids do not; the box is the members' boxes, grows on an add as a new array and shrinks after a
 *   removal; a slot moved into a grid by a full update joins that grid's batch;
 * - the culling: with InstanceList -> FrustumList -> a recording draw list, the batch in view is
 *   drawn, the one behind the camera leaves, an unchanged frame tests nothing again, and turning
 *   culling off puts it back.
 *
 * The mesh is a FastMesh whose instancify answers a bare renderer: what is checked is the lists'
 * bookkeeping, not GL. NOT part of the client build. Run from the repo root (PowerShell), after
 * `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\batchcheck tools\InstanceBatchCheck.java tools\FrustumCullCheck.java
 *   java -cp "$env:TEMP\batchcheck;$CP" haven.render.InstanceBatchCheck
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven.render;

import java.util.*;
import java.nio.FloatBuffer;
import haven.*;

public class InstanceBatchCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    static void geometry() {
        Random r = new Random(11);
        int n = 0, kept = 0, differ = 0;
        for(int c = 0; c < 400; c++) {
            Camera cam = Camera.pointed(Coord3f.of(r.nextFloat() * 400 - 200, r.nextFloat() * 400 - 200, 0),
                                        100 + r.nextFloat() * 700, 0.2f + r.nextFloat() * 1.2f, r.nextFloat() * 6.28f);
            Projection prj = FrustumCullCheck.proj(0.5f + r.nextFloat() * 0.8f, 0.5f + r.nextFloat());
            float[] clip = prj.fin(Matrix4f.id).mul(cam.fin(Matrix4f.id)).m;
            FrustumList.Planes pl = new FrustumList.Planes(clip.clone());
            for(int b = 0; b < 200; b++) {
                /* Grid-sized and mesh-sized boxes both. */
                float big = (b % 2 == 0) ? 600 : 60;
                float cx = r.nextFloat() * 4000 - 2000, cy = r.nextFloat() * 4000 - 2000, cz = r.nextFloat() * 60 - 10;
                float hx = 0.5f + r.nextFloat() * big, hy = 0.5f + r.nextFloat() * big, hz = 0.5f + r.nextFloat() * 40;
                float[] wb = new float[24];
                for(int i = 0; i < 8; i++) {
                    wb[i * 3]     = cx + (((i & 1) == 0) ? -hx : hx);
                    wb[i * 3 + 1] = cy + (((i & 2) == 0) ? -hy : hy);
                    wb[i * 3 + 2] = cz + (((i & 4) == 0) ? -hz : hz);
                }
                boolean bk = FrustumCullCheck.boxinside(clip, wb, 1 + FrustumList.ENTER);
                boolean pk = pl.box(pl.enter, new float[] {cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz});
                n++;
                if(pk) kept++;
                if(bk != pk) differ++;
            }
        }
        System.out.printf("  %d boxes, %d kept%n", n, kept);
        /* The two tests are the same inequality taken in clip space and in normalised world planes:
         * only a box within float rounding of a plane could come out differently. */
        check(differ <= n / 10000, "the plane box test decides what the eight-corner test decides (" + differ + " differ of " + n + ")");
        check((kept > 0) && (kept < n), "and it keeps some and leaves some out");
    }

    /* A mesh the instancer batches without GL. */
    static class Mesh extends FastMesh {
        Mesh(float h) {super(FrustumCullCheck.cube(h).vert, FrustumCullCheck.cube(h).indb);}
        public Rendered.Instanced instancify(InstanceBatch bat) {
            return(new Rendered.Instanced() {
                    public void draw(Pipe context, Render out) {}
                    public void iupdate(int idx) {}
                    public void itrim(int max) {}
                    public void commit(Render g) {}
                    public void dispose() {}
                });
        }
    }

    /* A slot as the render tree hands one over: its state in groups, one shared by the view (camera,
     * projection) and one of its own holding its location - the group a move is announced by. */
    static class TSlot implements RenderList.Slot<Rendered> {
        final FastMesh mesh;
        final BufPipe view, own = new BufPipe();

        TSlot(FastMesh mesh, Coord3f at, BufPipe view) {
            this.mesh = mesh;
            this.view = view;
            put(at);
        }

        TSlot(FastMesh mesh, Coord3f at, Camera cam, Projection prj) {
            this(mesh, at, view(cam, prj));
        }

        static BufPipe view(Camera cam, Projection prj) {
            BufPipe v = new BufPipe();
            v.put(Homo3D.cam, cam);
            v.put(Homo3D.prj, prj);
            return(v);
        }

        void put(Coord3f at) {
            BufPipe p = new BufPipe();
            new Location(Transform.makexlate(new Matrix4f(), at)).apply(p);
            own.put(Homo3D.loc, p.get(Homo3D.loc));
        }

        /* A new state for the whole slot, as a full update brings. */
        void move(Coord3f at) {put(at);}

        public GroupPipe state() {
            return(new GroupPipe() {
                    public Pipe group(int g) {return((g == 0) ? view : own);}
                    public int gstate(int id) {
                        if((id == Homo3D.cam.id) || (id == Homo3D.prj.id)) return(0);
                        if(id == Homo3D.loc.id) return(1);
                        return(-1);
                    }
                    public int nstates() {return(Math.max(Homo3D.loc.id, Math.max(Homo3D.cam.id, Homo3D.prj.id)) + 1);}
                });
        }

        public Rendered obj() {return(mesh);}
    }

    static InstanceBatch batch(Collection<? extends RenderList.Slot<?>> held) {
        InstanceBatch ret = null;
        for(RenderList.Slot<?> s : held) {
            if(s instanceof InstanceBatch)
                ret = (InstanceBatch)s;
        }
        return(ret);
    }

    static boolean near(float[] b, float... want) {
        for(int i = 0; i < 6; i++) {
            if(Math.abs(b[i] - want[i]) > 1e-3)
                return(false);
        }
        return(true);
    }

    static void bookkeeping() {
        Camera cam = Camera.pointed(Coord3f.o, 300, 0.5f, 0f);
        Projection prj = FrustumCullCheck.proj(1.0f, 1.0f);
        Mesh mesh = new Mesh(5);
        BufPipe view = TSlot.view(cam, prj);
        FrustumCullCheck.Recording rec = new FrustumCullCheck.Recording();
        InstanceList il = new InstanceList(null);
        il.add(rec, Rendered.class);
        TSlot a1 = new TSlot(mesh, Coord3f.of(100, 100, 0), view);
        TSlot a2 = new TSlot(mesh, Coord3f.of(200, 100, 0), view);
        TSlot b1 = new TSlot(mesh, Coord3f.of(5000, 100, 0), view);
        il.add(a1);
        il.add(a2);
        il.add(b1);
        InstanceBatch bat = batch(rec.held);
        check((bat != null) && (rec.held.size() == 2) && rec.held.contains(b1),
              "two of one grid batch, the one in another grid does not (" + rec.held.size() + " held, " + il.stats() + ")");
        if(bat == null)
            return;
        RenderList.Slot<?> bs = (RenderList.Slot<?>)bat;
        float[] box = InstanceList.batchbox(bs);
        check((box != null) && near(box, 95, 95, -5, 205, 105, 5), "the batch's box is its members' boxes " + Arrays.toString(box));
        check(InstanceList.batchbox(bs) == box, "asked again with nothing changed, the same array");
        check(InstanceList.batchbox(b1) == null, "a slot that is not a batch has no batch box");
        TSlot a3 = new TSlot(mesh, Coord3f.of(150, 400, 0), view);
        il.add(a3);
        float[] grown = InstanceList.batchbox(bs);
        check((grown != box) && near(grown, 95, 95, -5, 205, 405, 5), "an add grows it, as a new array " + Arrays.toString(grown));
        il.remove(a3);
        float[] shrunk = InstanceList.batchbox(bs);
        check(near(shrunk, 95, 95, -5, 205, 105, 5), "a removal shrinks it back " + Arrays.toString(shrunk));
        b1.move(Coord3f.of(300, 100, 0));
        il.update(b1);
        check((rec.held.size() == 1) && (batch(rec.held) == bat) && near(InstanceList.batchbox(bs), 95, 95, -5, 305, 105, 5),
              "moved into the grid by a full update, it joins that grid's batch (" + rec.held.size() + " held, " + il.stats() + ")");
        /* Moves announced as the tree announces them: the slot's own group, with the location's id. */
        int[] locmask = {Homo3D.loc.id};
        float[] before = InstanceList.batchbox(bs);
        a2.put(Coord3f.of(250, 700, 0));
        il.update(a2.own, locmask);
        float[] after = InstanceList.batchbox(bs);
        check((batch(rec.held) == bat) && (after != before) && near(after, 95, 95, -5, 305, 705, 5),
              "a move within the grid stays in the batch and the box follows it " + Arrays.toString(after));
        a2.put(Coord3f.of(5000, 700, 0));
        il.update(a2.own, locmask);
        check((rec.held.size() == 2) && rec.held.contains(a2) && near(InstanceList.batchbox(bs), 95, 95, -5, 305, 105, 5),
              "a move into another grid takes it out of the batch, and the box shrinks (" + rec.held.size() + " held, " + il.stats() + ")");
        TSlot b2 = new TSlot(mesh, Coord3f.of(5200, 100, 0), view);
        il.add(b2);
        check((rec.held.size() == 2) && !rec.held.contains(a2) && !rec.held.contains(b2),
              "and there it batches with what else stands in that grid (" + rec.held.size() + " held, " + il.stats() + ")");
    }

    static void culling() {
        /* Two batches, one in front of the camera and one behind, found as FrustumCullCheck finds
         * its directions; three thousand units out, so they are grids apart. */
        Camera cam = Camera.pointed(Coord3f.o, 300, 0.5f, 0f);
        Projection prj = FrustumCullCheck.proj(1.0f, 1.0f);
        float[] clip = prj.fin(Matrix4f.id).mul(cam.fin(Matrix4f.id)).m;
        Coord3f front = null, back = null;
        for(float a = 0; a < 6.3f; a += 0.1f) {
            Coord3f c = Coord3f.of((float)Math.cos(a) * 3000, (float)Math.sin(a) * 3000, 0);
            boolean in = FrustumCullCheck.boxinside(clip, FrustumCullCheck.box(c, 5), 1f);
            if(in && (front == null)) front = c;
            if(!in && (back == null)) back = c;
        }
        check((front != null) && (back != null), "the test camera sees some directions and not others");
        if((front == null) || (back == null))
            return;
        Mesh mesh = new Mesh(5);
        FrustumCullCheck.Recording rec = new FrustumCullCheck.Recording();
        FrustumList fl = new FrustumList(rec);
        InstanceList il = new InstanceList(null);
        il.add(fl, Rendered.class);
        BufPipe view = TSlot.view(cam, prj);
        il.add(new TSlot(mesh, front, view));
        il.add(new TSlot(mesh, front.add(20, 0, 0), view));
        il.add(new TSlot(mesh, back, view));
        il.add(new TSlot(mesh, back.add(20, 0, 0), view));
        check(rec.held.size() == 2, "two batches drawn while culling is off (" + rec.held.size() + ")");
        fl.cull(true);
        InstanceBatch kept = batch(rec.held);
        float[] kb = (kept == null) ? null : InstanceList.batchbox((RenderList.Slot<?>)kept);
        check((rec.held.size() == 1) && (kb != null) && (Math.abs(((kb[0] + kb[3]) / 2) - (front.x + 10)) < 1),
              "culling on: the batch in view stays, the one behind leaves (" + rec.held.size() + " held)");
        check((fl.culled() == 1) && (fl.cullable() == 2), "counted: 1 culled of 2 testable (" + fl.culled() + "/" + fl.cullable() + ")");
        fl.cull(true);
        check(fl.tested() == 0, "a frame in which nothing changed tests nothing again (" + fl.tested() + ")");
        fl.cull(false);
        check(rec.held.size() == 2, "culling off puts it back");
    }

    public static void main(String[] args) {
        geometry();
        bookkeeping();
        culling();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
