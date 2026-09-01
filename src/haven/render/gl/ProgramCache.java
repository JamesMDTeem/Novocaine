/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
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

package haven.render.gl;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import haven.Utils;

/**
 * On-disk cache of linked GL programs, so that a shader combination is linked
 * once on this machine rather than once per session.
 *
 * <p>Linking is the expensive half of getting a program onto the GPU. Measured
 * on one session: 310 programs, median 12ms each, worst 53ms, four seconds in
 * total. Most of that lands in the first few seconds and reads as loading, but
 * the rest arrives whenever the player walks into terrain whose materials have
 * not been drawn yet - forty-odd programs needed by a single frame, half a
 * second of stall, in the middle of otherwise smooth play. The work is real and
 * cannot be avoided the first time. It can be avoided every time after that.
 *
 * <p>ARB_get_program_binary hands back a driver-specific blob that can be
 * loaded again later, skipping compilation and linking entirely. The catch is
 * that the blob is valid only for the exact driver, GPU and program that
 * produced it, and drivers are entitled to reject one for reasons they do not
 * explain - a driver update is the common case, but not the only one. So every
 * step here is best-effort:
 *
 * <ul>
 *   <li>The cache directory is keyed on GL vendor, renderer and version. A
 *       driver update lands on a different key and the old directory is simply
 *       never read again (and swept, see {@link #sweep}).
 *   <li>Each entry is keyed on a digest of the actual shader source, so a
 *       change to the shaders - ours or upstream's - cannot be served a stale
 *       binary.
 *   <li>A loaded binary is only trusted after GL_LINK_STATUS confirms it. On
 *       any failure the program is linked from source exactly as before, and
 *       the bad entry is dropped.
 *   <li>Any IO problem anywhere disables the cache for the session rather than
 *       failing a render.
 * </ul>
 *
 * <p>The worst case is therefore the behaviour we had before this existed, plus
 * one wasted attempt. Set {@code -Dhaven.progcache=0} to turn it off.
 */
public class ProgramCache {
    /* Entries are small (tens to hundreds of KB) and the working set is a few
     * hundred, so a few tens of MB. Swept back to this on startup, oldest
     * first, so an abandoned world or a long-past session cannot grow it
     * without bound. */
    private static final long MAX_BYTES = 96 * 1024 * 1024;
    /* How long a cache for some other GL implementation has to have gone
     * untouched before it is assumed dead rather than merely idle. */
    private static final long STALE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final boolean enabled = !Utils.getprop("haven.progcache", "1").equals("0");

    private final Path dir;
    private boolean broken = false;

    private ProgramCache(Path dir) {
	this.dir = dir;
    }

    /**
     * Opens the cache for the GL implementation now current. Returns null when
     * caching is off, unsupported, or the directory cannot be made - callers
     * treat null as "just link from source".
     */
    public static ProgramCache open(String vendor, String renderer, String version, boolean supported) {
	Path base = Utils.path(System.getProperty("java.io.tmpdir"));
	try {
	    base = haven.Config.localdir();
	} catch(Exception e) {
	    /* Fall through to the temp directory. */
	}
	return(open(base, vendor, renderer, version, supported));
    }

    /** Takes the base directory explicitly, so a test can point it somewhere disposable. */
    public static ProgramCache open(Path base, String vendor, String renderer, String version, boolean supported) {
	if(!enabled || !supported)
	    return(null);
	try {
	    Path dir = base.resolve("progcache").resolve(key(vendor + " " + renderer + " " + version));
	    Files.createDirectories(dir);
	    ProgramCache ret = new ProgramCache(dir);
	    ret.sweep();
	    return(ret);
	} catch(Exception e) {
	    return(null);
	}
    }

    private static String key(String s) {
	try {
	    MessageDigest md = MessageDigest.getInstance("SHA-256");
	    byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	    StringBuilder sb = new StringBuilder();
	    for(int i = 0; i < 16; i++)
		sb.append(String.format("%02x", d[i]));
	    return(sb.toString());
	} catch(Exception e) {
	    throw(new RuntimeException(e));
	}
    }

    /** Digest of the source a program is built from; the per-entry key. */
    public static String progkey(String vsrc, String fsrc) {
	return(key(vsrc + " " + fsrc));
    }

    /**
     * A cached binary, or null. The format is the driver's own and is stored
     * alongside the blob; handing a binary back under the wrong format is one
     * of the ways a driver is entitled to reject it.
     */
    public Entry get(String progkey) {
	if(broken)
	    return(null);
	try {
	    Path p = dir.resolve(progkey);
	    if(!Files.exists(p))
		return(null);
	    byte[] all = Files.readAllBytes(p);
	    if(all.length < 4)
		return(null);
	    int fmt = ((all[0] & 0xff) << 24) | ((all[1] & 0xff) << 16) | ((all[2] & 0xff) << 8) | (all[3] & 0xff);
	    return(new Entry(fmt, Arrays.copyOfRange(all, 4, all.length)));
	} catch(Exception e) {
	    broken = true;
	    return(null);
	}
    }

    public void put(String progkey, int format, byte[] binary, int length) {
	if(broken)
	    return;
	try {
	    /* Written aside and moved into place: two clients starting together
	     * would otherwise be able to read a half-written entry, and a
	     * truncated binary is exactly the kind of thing a driver crashes
	     * on rather than rejects cleanly. */
	    Files.createDirectories(dir);
	    Path tmp = Files.createTempFile(dir, progkey, ".tmp");
	    try(OutputStream out = Files.newOutputStream(tmp)) {
		out.write(new byte[] {(byte)(format >>> 24), (byte)(format >>> 16), (byte)(format >>> 8), (byte)format});
		out.write(binary, 0, length);
	    }
	    Files.move(tmp, dir.resolve(progkey), StandardCopyOption.REPLACE_EXISTING);
	} catch(Exception e) {
	    broken = true;
	}
    }

    /** Drops one entry the driver would not take. */
    public void drop(String progkey) {
	try {
	    Files.deleteIfExists(dir.resolve(progkey));
	} catch(Exception e) {
	    broken = true;
	}
    }

    private void sweep() {
	try {
	    /* Sibling directories are the caches of GL implementations this
	     * machine no longer has - normally the driver version before the
	     * last update. They are removed on age alone, never merely for
	     * being a sibling: several clients can be running at once (see the
	     * Count crew option), and two of them can legitimately report
	     * different GL strings, in which case each is another's sibling.
	     * Deleting on sight let one wipe a live cache out from under the
	     * other mid-session. */
	    Path parent = dir.getParent();
	    long stale = System.currentTimeMillis() - STALE_MS;
	    try(DirectoryStream<Path> ds = Files.newDirectoryStream(parent)) {
		for(Path p : ds) {
		    if(!Files.isDirectory(p) || p.equals(dir))
			continue;
		    if(newest(p) < stale)
			rmtree(p);
		}
	    }
	    List<Path> files = new ArrayList<>();
	    long total = 0;
	    try(DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
		for(Path p : ds) {
		    files.add(p);
		    total += Files.size(p);
		}
	    }
	    if(total <= MAX_BYTES)
		return;
	    files.sort((a, b) -> {
		    try {
			return(Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)));
		    } catch(IOException e) {
			return(0);
		    }
		});
	    for(Path p : files) {
		if(total <= MAX_BYTES)
		    break;
		try {
		    long sz = Files.size(p);
		    Files.deleteIfExists(p);
		    total -= sz;
		} catch(IOException e) {
		    break;
		}
	    }
	} catch(Exception e) {
	    /* A cache that cannot be swept is still a usable cache. */
	}
    }

    /* Most recent mtime anywhere in the directory; 0 if it cannot be read,
     * which keeps an unreadable directory (never younger than the cutoff) from
     * being deleted on the strength of a failed stat. */
    private static long newest(Path d) {
	long ret = 0;
	try(DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
	    for(Path p : ds)
		ret = Math.max(ret, Files.getLastModifiedTime(p).toMillis());
	} catch(Exception e) {
	    return(Long.MAX_VALUE);
	}
	try {
	    ret = Math.max(ret, Files.getLastModifiedTime(d).toMillis());
	} catch(Exception e) {
	}
	return(ret);
    }

    private static void rmtree(Path p) {
	try(DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
	    for(Path c : ds) {
		if(Files.isDirectory(c))
		    rmtree(c);
		else
		    Files.deleteIfExists(c);
	    }
	} catch(Exception e) {
	    return;
	}
	try {
	    Files.deleteIfExists(p);
	} catch(Exception e) {
	}
    }

    public static class Entry {
	public final int format;
	public final byte[] binary;

	Entry(int format, byte[] binary) {
	    this.format = format;
	    this.binary = binary;
	}
    }
}
