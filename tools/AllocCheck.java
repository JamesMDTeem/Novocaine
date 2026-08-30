/*
 * Allocation measurement for the two sources cut in 66dd4d8ab (ported from Kami
 * b0915eea5, "Cut two per-frame allocation sources").
 *
 * That commit claims 6.9 GB -> 240 MB over 30s for the height lookup and 7.3 GB over 30s
 * for icon categorisation. Those figures are Kami's, measured in-world with JFR. This
 * harness answers a narrower question that does not need a live session: for the code
 * this fork actually ships, how many bytes does one call allocate now, and how many did
 * it allocate before?
 *
 * Method: com.sun.management.ThreadMXBean.getThreadAllocatedBytes, which reports exact
 * per-thread allocation, around a warmed loop. The "after" case calls the real shipped
 * code - MCache.ZSurface is an interface whose getz(Coord2d) is a default method, so
 * implementing the two primitives here exercises the genuine implementation. The
 * "before" case is a faithful transcription of the pre-66dd4d8ab body, kept next to it
 * so the two can be compared on the same inputs.
 *
 * This measures allocation per call, not frame time, and it is deliberately silent about
 * how often the game calls these. Multiplying up to a GB-per-30s figure needs a real
 * call rate from a live session; see the note this prints at the end.
 *
 * NOT part of the client build - build.xml compiles src/ only, and this lives in tools/
 * so it can never reach a release jar. Run on demand (from the repo root, PowerShell):
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\alloccheck tools\AllocCheck.java
 *   java -cp "$env:TEMP\alloccheck;$CP" haven.AllocCheck
 */
package haven;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Arrays;

public class AllocCheck {
    static final com.sun.management.ThreadMXBean TMX =
	(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();

    static long allocated() {
	return(TMX.getThreadAllocatedBytes(Thread.currentThread().getId()));
    }

    /* Sink, so nothing under measurement is dead-code-eliminated. */
    static double dsink = 0;
    static boolean bsink = false;

    /* ---- height lookup ---------------------------------------------------- */

    /* The shipped surface: only the two primitives, so getz(Coord2d) below is the real
     * default method out of MCache.ZSurface. */
    static class Shipped implements MCache.ZSurface {
	public double getz(Coord tc) {return(tc.x + tc.y);}
	public double getz(int tx, int ty) {return(tx + ty);}
    }

    /* The pre-66dd4d8ab body, transcribed. Builds one Coord for the corner and three
     * more via add() for the other samples. */
    static class Before {
	double getz(Coord tc) {return(tc.x + tc.y);}
	double getz(Coord2d pc) {
	    double tw = MCache.tilesz.x, th = MCache.tilesz.y;
	    Coord ul = Coord.of(Utils.floordiv(pc.x, tw), Utils.floordiv(pc.y, th));
	    double sx = (pc.x - (ul.x * tw)) / tw, ix = 1.0 - sx;
	    double sy = (pc.y - (ul.y * th)) / th, iy = 1.0 - sy;
	    return((iy * ((ix * getz(ul          )) + (sx * getz(ul.add(1, 0))))) +
		   (sy * ((ix * getz(ul.add(0, 1))) + (sx * getz(ul.add(1, 1))))));
	}
    }

    /* ---- icon categorisation ---------------------------------------------- */

    static boolean matchany(String[] paths, String name) {
	for(String path : paths) {
	    if(name.contains(path))
		return(true);
	}
	return(false);
    }

    static boolean streamany(String[] paths, String name) {
	return(Arrays.stream(paths).anyMatch(name::contains));
    }

    /* ---- harness ----------------------------------------------------------- */

    static long perCall(Runnable body, int warm, int iters) {
	for(int i = 0; i < warm; i++) body.run();
	long a = allocated();
	for(int i = 0; i < iters; i++) body.run();
	long b = allocated();
	return((b - a) / iters);
    }

    static void row(String label, long before, long after) {
	String verdict = (after < before) ? String.format("%.0f%% less", 100.0 * (before - after) / before)
	                                  : (after == before ? "no change" : "WORSE");
	System.out.printf("  %-34s before %6d B/call   after %6d B/call   %s%n",
			  label, before, after, verdict);
    }

    public static void main(String[] args) throws Exception {
	int warm = 200_000, iters = 2_000_000;
	System.out.println("Allocation per call, measured with getThreadAllocatedBytes");
	System.out.printf("(%d warmup, %d measured iterations each)%n%n", warm, iters);

	System.out.println("height lookup - MCache.ZSurface.getz(Coord2d)");
	Shipped shipped = new Shipped();
	Before before = new Before();
	Coord2d[] pts = new Coord2d[64];
	for(int i = 0; i < pts.length; i++)
	    pts[i] = Coord2d.of((i * 37) % 1000 - 500, (i * 53) % 1000 - 500);
	final int[] k = {0};
	long bAlloc = perCall(() -> {dsink += before.getz(pts[(k[0]++) & 63]);}, warm, iters);
	k[0] = 0;
	long aAlloc = perCall(() -> {dsink += shipped.getz(pts[(k[0]++) & 63]);}, warm, iters);
	row("getz(Coord2d)", bAlloc, aAlloc);

	System.out.println();
	System.out.println("icon categorisation - substring match over the real path tables");
	String[] paths = animalPaths();
	if(paths == null) {
	    System.out.println("  (could not reach GobCategory.ANIMAL_PATHS by reflection - skipped)");
	} else {
	    System.out.printf("  using ANIMAL_PATHS, %d entries%n", paths.length);
	    String miss = "gfx/terobjs/nothing-matches-this";
	    long sAlloc = perCall(() -> {bsink ^= streamany(paths, miss);}, warm, iters);
	    long mAlloc = perCall(() -> {bsink ^= matchany(paths, miss);}, warm, iters);
	    row("one table, worst case (no match)", sAlloc, mAlloc);
	    System.out.printf("  categorize walks up to 8 such tables per gob, so multiply by up to 8%n");
	}

	System.out.println();
	System.out.println("Per-call figures only. Kami's 6.9 GB and 7.3 GB per 30s come from a");
	System.out.println("live session, where every gob asks its height once a frame and every");
	System.out.println("icon is categorised on every minimap tick. Reproducing those totals");
	System.out.println("needs a real call rate, which the login screen does not exercise.");
	if(dsink == Double.MIN_VALUE || bsink) System.out.print("");
    }

    /* The path tables are private statics inside the GobCategory enum. */
    static String[] animalPaths() {
	try {
	    for(Class<?> c : GobIconCategoryList.class.getDeclaredClasses()) {
		if(!c.getSimpleName().equals("GobCategory")) continue;
		Field f = c.getDeclaredField("ANIMAL_PATHS");
		f.setAccessible(true);
		return((String[])f.get(null));
	    }
	} catch(Throwable t) {
	}
	return(null);
    }
}
