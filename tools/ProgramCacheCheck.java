/*
 * Exercises haven.render.gl.ProgramCache without a GL context.
 *
 * The cache is the part of the shader-binary work that can be got wrong quietly: a
 * truncated or mis-keyed entry is handed to a driver as an opaque blob, and a driver
 * given a bad blob is entitled to do rather worse than reject it. The GL half needs a
 * GPU to test; the disk half does not, and that is where the format, the keying and the
 * eviction live.
 *
 * Checks:
 *   1. put/get round-trips the format word and the bytes exactly.
 *   2. A miss returns null rather than throwing.
 *   3. Entry keys follow the shader source - changed source must not hit.
 *   4. Cache directories differ per GL identity, so a driver update cannot serve
 *      binaries written by the previous one.
 *   5. drop() removes an entry.
 *   6. A partial/corrupt file is refused rather than returned as a short binary.
 *   7. Writes are atomic enough that a reader never sees a partial entry, checked by
 *      hammering put and get from several threads at once.
 *
 * NOT part of the client build - build.xml compiles src/ only. Run from the repo root:
 *
 *   $CP="build\classes;build\classes-lib;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\pcc tools\ProgramCacheCheck.java
 *   java -cp "$env:TEMP\pcc;$CP" haven.ProgramCacheCheck
 */
package haven;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ProgramCacheCheck {
    static int fails = 0;

    static void check(String what, boolean ok) {
	System.out.printf("  %-58s %s%n", what, ok ? "ok" : "FAIL");
	if(!ok)
	    fails++;
    }

    /* The base-directory overload of open() exists so this cannot touch a
     * real client's cache. */
    static Path base;

    static Object open(String vendor, String renderer, String version) throws Exception {
	Class<?> cl = Class.forName("haven.render.gl.ProgramCache");
	Method m = cl.getDeclaredMethod("open", Path.class, String.class, String.class, String.class, boolean.class);
	m.setAccessible(true);
	return(m.invoke(null, base, vendor, renderer, version, true));
    }

    static void put(Object c, String key, int fmt, byte[] bin) throws Exception {
	Method m = c.getClass().getDeclaredMethod("put", String.class, int.class, byte[].class, int.class);
	m.setAccessible(true);
	m.invoke(c, key, fmt, bin, bin.length);
    }

    static Object get(Object c, String key) throws Exception {
	Method m = c.getClass().getDeclaredMethod("get", String.class);
	m.setAccessible(true);
	return(m.invoke(c, key));
    }

    static void drop(Object c, String key) throws Exception {
	Method m = c.getClass().getDeclaredMethod("drop", String.class);
	m.setAccessible(true);
	m.invoke(c, key);
    }

    static int fmtof(Object ent) throws Exception {
	return(ent.getClass().getField("format").getInt(ent));
    }

    static byte[] binof(Object ent) throws Exception {
	return((byte[])ent.getClass().getField("binary").get(ent));
    }

    static String progkey(String v, String f) throws Exception {
	Class<?> cl = Class.forName("haven.render.gl.ProgramCache");
	Method m = cl.getDeclaredMethod("progkey", String.class, String.class);
	m.setAccessible(true);
	return((String)m.invoke(null, v, f));
    }

    static Path dirof(Object c) throws Exception {
	Field f = c.getClass().getDeclaredField("dir");
	f.setAccessible(true);
	return((Path)f.get(c));
    }

    public static void main(String[] args) throws Exception {
	base = Files.createTempDirectory("pcccheck");
	System.out.println("ProgramCache, no GL required\n");

	Object c = open("TestVendor", "TestRenderer", "4.6.0");
	check("cache opens", c != null);
	if(c == null) {
	    System.out.println("\ncannot continue without a cache");
	    System.exit(1);
	}

	// 1. round-trip
	String k1 = progkey("vertex source A", "fragment source A");
	byte[] blob = new byte[5000];
	new Random(1234).nextBytes(blob);
	put(c, k1, 0x9999, blob);
	Object got = get(c, k1);
	check("round-trip returns an entry", got != null);
	check("round-trip preserves the format word", got != null && fmtof(got) == 0x9999);
	check("round-trip preserves the bytes", got != null && Arrays.equals(binof(got), blob));

	// 2. miss
	check("a miss returns null", get(c, progkey("nothing", "here")) == null);

	// 3. source keying
	String k2 = progkey("vertex source A", "fragment source B");
	check("changed shader source is a different key", !k1.equals(k2));
	check("changed shader source does not hit", get(c, k2) == null);

	// 4. GL identity keying
	Object c2 = open("TestVendor", "TestRenderer", "4.6.1");
	check("a driver version change uses a different directory", !dirof(c).equals(dirof(c2)));

	// 4b. the regression this test was written on: opening a second cache
	// must not delete a live one belonging to another GL identity.
	check("opening another cache leaves the first's directory alone", Files.isDirectory(dirof(c)));
	put(c, k1, 0x9999, blob);
	check("the first cache still writes after the second opened", get(c, k1) != null);

	// 5. drop
	drop(c, k1);
	check("drop removes the entry", get(c, k1) == null);

	// 6. corruption
	put(c, k1, 0x1234, blob);
	Path f = dirof(c).resolve(k1);
	Files.write(f, new byte[] {1, 2});		// shorter than the format word
	check("a truncated entry is refused, not returned", get(c, k1) == null);
	Files.deleteIfExists(f);

	// 7. concurrent readers never see a partial entry
	final Object cc = c;
	final String k3 = progkey("concurrent", "entry");
	final byte[] big = new byte[256 * 1024];
	new Random(99).nextBytes(big);
	ExecutorService ex = Executors.newFixedThreadPool(6);
	final List<String> bad = Collections.synchronizedList(new ArrayList<>());
	List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
	for(int i = 0; i < 3; i++) {
	    fs.add(ex.submit(() -> {
		    try {
			for(int n = 0; n < 40; n++)
			    put(cc, k3, 0x4321, big);
		    } catch(Exception e) {
			bad.add("writer: " + e);
		    }
		}));
	}
	for(int i = 0; i < 3; i++) {
	    fs.add(ex.submit(() -> {
		    try {
			for(int n = 0; n < 200; n++) {
			    Object e = get(cc, k3);
			    if(e != null) {
				byte[] b = binof(e);
				if(b.length != big.length || !Arrays.equals(b, big))
				    bad.add("reader saw a partial entry of " + b.length + " bytes");
			    }
			}
		    } catch(Exception e) {
			bad.add("reader: " + e);
		    }
		}));
	}
	for(java.util.concurrent.Future<?> fu : fs)
	    fu.get();
	ex.shutdown();
	check("concurrent put/get never yields a partial entry", bad.isEmpty());
	for(String b : new LinkedHashSet<>(bad))
	    System.out.println("      " + b);

	System.out.println();
	if(fails == 0) {
	    System.out.println("all checks passed");
	} else {
	    System.out.println(fails + " check(s) FAILED");
	    System.exit(1);
	}
    }
}
