/*
 * TerrainTile.Blend from the noise block cache with the shrinking blur, held to the old blend.
 *
 * The blend now reads its noise from blocks shared by neighbouring cuts and blurs only the
 * vertices the cut's own vertices read (after brodgar-io-client 07cf23e5e, 2026-10-03). It claims
 * the same floats bit for bit. This builds a real TerrainTile over a real SNoise3 and checks, for
 * cuts at random places (negative coordinates too, so block division rounds down), neighbouring
 * cuts sharing blocks, and all four settings of Disable Tile Blending / Disable Tile Smoothing:
 *
 * - every weight a cut's tiles read (vertices 0..sz) equals the old blend's, compared by raw bits;
 * - every tile's enabled layers (en) equal the old blend's.
 *
 * The old blend below is the constructor and setbase as they stood before the change, copied
 * verbatim but for its inputs. It also times both (2026-10-03: 582 cuts, 0 differ; 144 cuts
 * with a cold block cache 62 ms where the old blend took 158). NOT part of the client build. Run from the repo
 * root (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\blendcheck tools\TerrainBlendCheck.java
 *   java -cp "$env:TEMP\blendcheck;$CP" haven.resutil.TerrainBlendCheck
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven.resutil;

import java.util.*;
import java.lang.reflect.*;
import haven.*;
import haven.MapMesh.Scan;

public class TerrainBlendCheck {
    static int fails = 0;
    static final int sr = 12;

    static void check(boolean ok, String what) {
        if(!ok) {
            System.out.println("FAIL " + what);
            fails++;
        }
    }

    /* The blend as it stood before the change: bv after post-processing, and en. */
    static final class Old {
        final Scan vs, es;
        final float[][] bv;
        final boolean[][] en;

        Old(SNoise3 noise, TerrainTile.Var[] var, Coord ul, Coord sz, boolean blendOff, boolean smoothOff) {
            vs = new Scan(Coord.z.sub(sr, sr), sz.add(sr * 2 + 1, sr * 2 + 1));
            float[][] buf1 = new float[var.length + 1][vs.l];
            float[][] lwc = new float[var.length + 1][vs.l];
            if(!blendOff) {
                for(int i = 0; i < var.length + 1; i++) {
                    for(int y = vs.ul.y; y < vs.br.y; y++) {
                        for(int x = vs.ul.x; x < vs.br.x; x++) {
                            lwc[i][vs.o(x, y)] = (float)noise.getr(0.5, 1.5, 32, x + ul.x, y + ul.y, i * 23);
                        }
                    }
                }
            }
            setbase(noise, var, ul, buf1, smoothOff);
            int passes = blendOff ? 0 : sr;
            for(int i = 0; i < passes; i++) {
                float[][] buf2 = new float[var.length + 1][vs.l];
                for(int y = vs.ul.y; y < vs.br.y; y++) {
                    for(int x = vs.ul.x; x < vs.br.x; x++) {
                        for(int o = 0; o < var.length + 1; o++) {
                            float s = buf1[o][vs.o(x, y)] * 4;
                            float w = 4;
                            float lw = lwc[o][vs.o(x, y)];
                            if(lw < 0)
                                lw = lw * lw * lw;
                            else
                                lw = lw * lw;
                            if(x > vs.ul.x) {
                                s += buf1[o][vs.o(x - 1, y)] * lw;
                                w += lw;
                            }
                            if(y > vs.ul.y) {
                                s += buf1[o][vs.o(x, y - 1)] * lw;
                                w += lw;
                            }
                            if(x < vs.br.x - 1) {
                                s += buf1[o][vs.o(x + 1, y)] * lw;
                                w += lw;
                            }
                            if(y < vs.br.y - 1) {
                                s += buf1[o][vs.o(x, y + 1)] * lw;
                                w += lw;
                            }
                            buf2[o][vs.o(x, y)] = s / w;
                        }
                    }
                }
                buf1 = buf2;
            }
            bv = buf1;
            for(int y = vs.ul.y; y < vs.br.y; y++) {
                for(int x = vs.ul.x; x < vs.br.x; x++) {
                    for(int i = 0; i < var.length + 1; i++) {
                        float v = bv[i][vs.o(x, y)];
                        v = v * 1.2f - 0.1f;
                        if(v < 0)
                            v = 0;
                        else if(v > 1)
                            v = 1;
                        else
                            v = 0.25f + (0.75f * v);
                        bv[i][vs.o(x, y)] = v;
                    }
                }
            }
            es = new Scan(Coord.z, sz);
            en = new boolean[var.length + 1][es.l];
            for(int y = es.ul.y; y < es.br.y; y++) {
                for(int x = es.ul.x; x < es.br.x; x++) {
                    boolean fall = false;
                    for(int i = var.length; i >= 0; i--) {
                        if(fall) {
                            en[i][es.o(x, y)] = false;
                        } else if((bv[i][vs.o(x    , y    )] < 0.001f) && (bv[i][vs.o(x + 1, y    )] < 0.001f) &&
                                  (bv[i][vs.o(x    , y + 1)] < 0.001f) && (bv[i][vs.o(x + 1, y + 1)] < 0.001f)) {
                            en[i][es.o(x, y)] = false;
                        } else {
                            en[i][es.o(x, y)] = true;
                            if((bv[i][vs.o(x    , y    )] > 0.99f) && (bv[i][vs.o(x + 1, y    )] > 0.99f) &&
                               (bv[i][vs.o(x    , y + 1)] > 0.99f) && (bv[i][vs.o(x + 1, y + 1)] > 0.99f)) {
                                fall = true;
                            }
                        }
                    }
                }
            }
        }

        private void setbase(SNoise3 noise, TerrainTile.Var[] var, Coord ul, float[][] bv, boolean smoothOff) {
            if(smoothOff) {
                for(int y = vs.ul.y; y < vs.br.y - 1; y++) {
                    for(int x = vs.ul.x; x < vs.br.x - 1; x++) {
                        bv[0][vs.o(x, y)] = 1;
                        bv[0][vs.o(x + 1, y)] = 1;
                        bv[0][vs.o(x, y + 1)] = 1;
                        bv[0][vs.o(x + 1, y + 1)] = 1;
                        for(int i = var.length - 1; i >= 0; i--) {
                            bv[i + 1][vs.o(x, y)] = 1;
                            bv[i + 1][vs.o(x + 1, y)] = 1;
                            bv[i + 1][vs.o(x, y + 1)] = 1;
                            bv[i + 1][vs.o(x + 1, y + 1)] = 1;
                        }
                    }
                }
            } else {
                for(int y = vs.ul.y; y < vs.br.y - 1; y++) {
                    for(int x = vs.ul.x; x < vs.br.x - 1; x++) {
                        fall: {
                            for(int i = var.length - 1; i >= 0; i--) {
                                TerrainTile.Var v = var[i];
                                double n = 0;
                                for(double s = 64; s >= 8; s /= 2)
                                    n += noise.get(s, x + ul.x, y + ul.y, v.nz);
                                if(((n / 2) >= v.thrl) && ((n / 2) <= v.thrh)) {
                                    bv[i + 1][vs.o(x, y)] = 1;
                                    bv[i + 1][vs.o(x + 1, y)] = 1;
                                    bv[i + 1][vs.o(x, y + 1)] = 1;
                                    bv[i + 1][vs.o(x + 1, y + 1)] = 1;
                                    break fall;
                                }
                            }
                            bv[0][vs.o(x, y)] = 1;
                            bv[0][vs.o(x + 1, y)] = 1;
                            bv[0][vs.o(x, y + 1)] = 1;
                            bv[0][vs.o(x + 1, y + 1)] = 1;
                        }
                    }
                }
            }
        }
    }

    static Constructor<MapMesh> mmc;

    static MapMesh mesh(Coord ul, Coord sz) throws Exception {
        return(mmc.newInstance(null, ul, sz, new Random(1)));
    }

    static CheckBox box(boolean a) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        CheckBox cb = (CheckBox)((sun.misc.Unsafe)uf.get(null)).allocateInstance(CheckBox.class);
        cb.a = a;
        return(cb);
    }

    static void compare(TerrainTile tile, Coord ul, Coord sz, boolean blendOff, boolean smoothOff, String what) throws Exception {
        OptWnd.disableTileBlendingCheckBox = box(blendOff);
        OptWnd.disableTileSmoothingCheckBox = box(smoothOff);
        TerrainTile.Blend nw = tile.blend.make(mesh(ul, sz));
        Old old = new Old(tile.noise, tile.var, ul, sz, blendOff, smoothOff);
        int bad = 0;
        for(int l = 0; l < tile.var.length + 1; l++) {
            for(int y = 0; y <= sz.y; y++) {
                for(int x = 0; x <= sz.x; x++) {
                    if(Float.floatToRawIntBits(nw.bv[l][nw.vs.o(x, y)]) != Float.floatToRawIntBits(old.bv[l][old.vs.o(x, y)]))
                        bad++;
                }
            }
            if(!Arrays.equals(nw.en[l], old.en[l]))
                bad++;
        }
        check(bad == 0, what + ": " + bad + " values differ");
    }

    public static void main(String[] args) throws Exception {
        mmc = MapMesh.class.getDeclaredConstructor(MCache.class, Coord.class, Coord.class, Random.class);
        mmc.setAccessible(true);
        Random rnd = new Random(20261003);
        Coord sz = MCache.cutsz;
        int cases = 0;
        for(int t = 0; t < 6; t++) {
            int nv = 1 + rnd.nextInt(4);
            TerrainTile.Var[] var = new TerrainTile.Var[nv];
            for(int i = 0; i < nv; i++) {
                double a = (rnd.nextDouble() * 1.6) - 0.8, b = a + (rnd.nextDouble() * 0.8);
                var[i] = new TerrainTile.Var(null, a, b, rnd.nextDouble() * 1000);
            }
            TerrainTile tile = new TerrainTile(t, new SNoise3(rnd.nextLong()), null, var, null);
            for(int c = 0; c < 12; c++) {
                /* A cut where the map puts one: a whole number of cuts from the origin, either side. */
                Coord ul = new Coord((rnd.nextInt(400) - 200) * sz.x, (rnd.nextInt(400) - 200) * sz.y);
                for(int f = 0; f < 4; f++) {
                    boolean bo = (f & 1) != 0, so = (f & 2) != 0;
                    compare(tile, ul, sz, bo, so, String.format("tileset %d vars %d cut %s blendOff %b smoothOff %b", t, nv, ul, bo, so));
                    /* and its neighbour, which reads blocks this one made */
                    compare(tile, ul.add(sz.x, 0), sz, bo, so, String.format("tileset %d neighbour of %s blendOff %b smoothOff %b", t, ul, bo, so));
                    cases += 2;
                }
            }
            /* An odd-placed, odd-sized region too, so block edges fall inside the cut. */
            compare(tile, new Coord(-37, 13), new Coord(17, 31), false, false, "tileset " + t + " odd region");
            cases++;
        }
        System.out.println(cases + " cuts compared, " + fails + " failed");

        /* Timing, blending and smoothing on: the old blend against the new one over a 3x3 grid
         * of cuts, the new one with a cold block cache each round (fresh noise). */
        TerrainTile.Var[] var = {new TerrainTile.Var(null, -0.2, 0.4, 11), new TerrainTile.Var(null, 0.3, 1.0, 523)};
        OptWnd.disableTileBlendingCheckBox = box(false);
        OptWnd.disableTileSmoothingCheckBox = box(false);
        for(int round = 0; round < 6; round++) {
            TerrainTile tile = new TerrainTile(100 + round, new SNoise3(round), null, var, null);
            long t0 = System.nanoTime();
            for(int y = 0; y < 12; y++)
                for(int x = 0; x < 12; x++)
                    new Old(tile.noise, tile.var, new Coord(x * sz.x, y * sz.y), sz, false, false);
            long t1 = System.nanoTime();
            for(int y = 0; y < 12; y++)
                for(int x = 0; x < 12; x++)
                    tile.blend.make(mesh(new Coord(x * sz.x, y * sz.y), sz));
            long t2 = System.nanoTime();
            System.out.printf("round %d: 144 cuts old %.1f ms, new %.1f ms%n", round, (t1 - t0) / 1e6, (t2 - t1) / 1e6);
        }
        System.exit((fails == 0) ? 0 : 1);
    }
}
