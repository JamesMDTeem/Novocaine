/*
 * The light states a view compiles every frame are reused while the lights stand still.
 *
 * PView.lights() asks for the light state on every draw. In SIMPLE lighting mode, and in every view
 * but the map (character portraits, previews), that built a new SimpleLights each time, and a new
 * state makes PView.basic apply the view's whole render state again - every frame. ZONED lighting
 * already kept its grid (Lighting.LightGrid). This checks that the same lights now hand back the same
 * state, that a real change hands back a new one, and that a light changing its own colour array in
 * place still counts as a change (the cache keeps a copy, not the light's array).
 *
 * NOT part of the client build. Run from the repo root (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\lightcheck tools\LightCacheCheck.java
 *   java -cp "$env:TEMP\lightcheck;$CP" haven.LightCacheCheck
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven;

import haven.render.*;

public class LightCacheCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    /* One point light's parameters as PosLight.params hands them out: colours, position, attenuation. */
    static Object[] light(float[] col, float x) {
        return(new Object[] {new float[] {0, 0, 0, 1}, col, col, new float[] {x, 2, 3, 1}, 0.5f, 1f, 0.1f, 0f});
    }

    public static void main(String[] args) {
        Projection proj = new Projection(Matrix4f.id);
        /* SIMPLE mode: a compiler with no settings has no zone grid. */
        MapView.LightCompiler lc = new MapView.LightCompiler(null);
        float[] col = {1, 0.5f, 0.25f, 1};
        Pipe.Op a = lc.compile(new Object[][] {light(col, 10)}, proj);
        Pipe.Op b = lc.compile(new Object[][] {light(new float[] {1, 0.5f, 0.25f, 1}, 10)}, proj);
        check(a == b, "the same lights, handed over afresh, give the same state");
        Pipe.Op c = lc.compile(new Object[][] {light(new float[] {1, 0.5f, 0.25f, 1}, 10.0004f)}, proj);
        check(a == c, "a position that moved less than the tolerance gives the same state");
        Pipe.Op d = lc.compile(new Object[][] {light(new float[] {1, 0.5f, 0.25f, 1}, 25)}, proj);
        check(d != a, "a light that moved gives a new state");
        Pipe.Op e = lc.compile(new Object[][] {light(col, 25)}, proj);
        check(e == d, "and that state is kept while it stands");
        col[1] = 0.9f;
        Pipe.Op f = lc.compile(new Object[][] {light(col, 25)}, proj);
        check(f != e, "a light that changed its own colour array in place gives a new state");
        Pipe.Op g = lc.compile(new Object[][] {light(col, 25), light(new float[] {0, 1, 0, 1}, 40)}, proj);
        check(g != f, "a light added gives a new state");

        /* Every other view: Light.LightList.compile. */
        Light.LightList ll = new Light.LightList();
        State s1 = ll.compile(), s2 = ll.compile();
        check(s1 == s2, "a view's light list compiled twice with nothing changed gives the same state");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
