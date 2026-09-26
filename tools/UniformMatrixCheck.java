/*
 * BGL.glUniformMatrix4fv(float[][]): matrices flattened when the command runs, not when it is recorded.
 *
 * The skinned-mesh bone uniform used to build a fresh n*16 array per animated model per frame to hand
 * the recorded command (4.5% of the client's allocation, 2026-09-26). The command now keeps the caller's
 * matrices and lays them end to end into a per-thread buffer when it runs. This records it into a real
 * BufferBGL, runs it against a GL that records what it is handed, and checks the upload: the count, the
 * length exactly count * 16 (LWJGLWrap asserts that, and throws otherwise), the matrices in order, and
 * that alternating counts each get their own exact buffer.
 *
 * NOT part of the client build. Run from the repo root (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\matcheck tools\UniformMatrixCheck.java
 *   java -cp "$env:TEMP\matcheck;$CP" haven.render.gl.UniformMatrixCheck
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven.render.gl;

import java.lang.reflect.*;
import java.util.*;

public class UniformMatrixCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    static float[][] mats(int n, float base) {
        float[][] m = new float[n][16];
        for(int i = 0; i < n; i++)
            for(int o = 0; o < 16; o++)
                m[i][o] = base + (i * 16) + o;
        return(m);
    }

    public static void main(String[] args) {
        List<Object[]> calls = new ArrayList<>();
        GL gl = (GL)Proxy.newProxyInstance(GL.class.getClassLoader(), new Class<?>[] {GL.class}, (p, m, a) -> {
                if(m.getName().equals("glUniformMatrix4fv")) {
                    float[] v = (float[])a[3];
                    calls.add(new Object[] {a[0], a[1], a[2], v.clone(), v.length});
                }
                return(null);
            });
        BGL.ID loc = new BGL.ID() {
                public int glid() {return(7);}
            };
        BufferBGL buf = new BufferBGL();
        float[][] a = mats(3, 0), b = mats(5, 1000), c = mats(3, 2000);
        buf.glUniformMatrix4fv(loc, 3, false, a);
        buf.glUniformMatrix4fv(loc, 5, false, b);
        buf.glUniformMatrix4fv(loc, 3, false, c);
        buf.run(gl);
        check(calls.size() == 3, "three uploads");
        float[][][] want = {a, b, c};
        int[] counts = {3, 5, 3};
        for(int k = 0; k < calls.size() && k < 3; k++) {
            Object[] call = calls.get(k);
            float[] got = (float[])call[3];
            float[] flat = new float[counts[k] * 16];
            for(int i = 0; i < counts[k]; i++)
                System.arraycopy(want[k][i], 0, flat, i * 16, 16);
            check(((Integer)call[0] == 7) && ((Integer)call[1] == counts[k]) && !((Boolean)call[2]),
                  "upload " + k + ": location 7, count " + counts[k] + ", not transposed");
            check((Integer)call[4] == counts[k] * 16, "upload " + k + ": the array is exactly count * 16 long (" + call[4] + ")");
            check(Arrays.equals(got, flat), "upload " + k + ": the matrices, in order");
        }
        /* only the first `count` matrices go up, as the old copy took Math.min(length, size) */
        calls.clear();
        BufferBGL buf2 = new BufferBGL();
        buf2.glUniformMatrix4fv(loc, 2, false, mats(4, 0));
        buf2.run(gl);
        check((calls.size() == 1) && ((Integer)calls.get(0)[4] == 32), "a longer list than count uploads count matrices");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
