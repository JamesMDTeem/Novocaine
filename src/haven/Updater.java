package haven;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.*;

/*
 * Self-updating for the packaged client. Only the download happens
 * inside the running client (see UpdateWindow); the swap itself
 * cannot, since this JVM holds hafen.jar open and Windows will not let
 * an open file be replaced, and unpacking must not either, since after
 * a long session the client can be sitting right at its heap limit and
 * even the unpacker's small buffers have produced OutOfMemoryError.
 * Both are therefore handed to a second JVM with a fresh heap, started
 * from a scratch copy of the running jar, which waits for the client
 * to exit, unpacks the package, copies it over the install and starts
 * the client again -- see main() below.
 *
 * Only installs that came from a release package and were started
 * through their own Play script update themselves. Steam keeps its own
 * copy current, and a client started any other way (a developer build,
 * the Haven launcher) is left alone.
 *
 * Nightly stamp: file-by-file evaluation with a 24h fast path. When the
 * latest tag has not changed since the last successful check (stamp
 * file under update/nightly-stamp), the login screen skips re-showing
 * the update and the updater skips re-hashing unchanged files.
 * Coexists with launcher/Update.bat -- when Updater.possible() is false
 * the LoginScreen falls back to the Update.bat instructions.
 */
public class Updater {
    public static final String repo = "JamesMDTeem/Novocaine";
    private static final String workdir = "update";
    private static final String prefname = "autoUpdate";
    private static final String stampName = "nightly-stamp";
    private static final long stampValidMs = 24L * 60 * 60 * 1000;
    private static Path install = null;
    private static boolean installck = false;
    private static boolean skipped = false;

    public static class Cancelled extends IOException {
	public Cancelled() { super("update cancelled"); }
    }

    public interface Progress {
	void status(String text, double frac);
	boolean cancelled();
    }

    private static final Progress noprog = new Progress() {
	    public void status(String text, double frac) {}
	    public boolean cancelled() { return false; }
	};

    public static String asseturl(String tag) {
	return "https://github.com/" + repo + "/releases/download/" + tag + "/Novocaine-" + tag + ".zip";
    }

    public static boolean enabled() {
	return !"false".equals(Utils.getprop("haven.autoupdate", "true")) && Utils.getprefb(prefname, true);
    }

    public static void enabled(boolean on) {
	Utils.setprefb(prefname, on);
    }

    public static synchronized boolean skipped() { return skipped; }
    public static synchronized void skipped(boolean v) { Updater.skipped = v; }

    public static synchronized Path install() {
	if(!installck) { install = findinstall(); installck = true; }
	return install;
    }

    private static Path findinstall() {
	try {
	    java.security.CodeSource src = Updater.class.getProtectionDomain().getCodeSource();
	    if(src == null) return null;
	    Path jar = Paths.get(src.getLocation().toURI());
	    if(!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) return null;
	    Path dir = jar.getParent();
	    if(dir == null || !Files.isRegularFile(dir.resolve(launchscript()))) return null;
	    return dir;
	} catch(URISyntaxException | RuntimeException e) { return null; }
    }

    private static String launchscript() {
	return Config.windows ? "Play.bat" : "Play_Linux.sh";
    }

    private static boolean ownlaunch() {
	return "false".equals(System.getProperty("runningThroughSteam"));
    }

    public static boolean possible() {
	Path dir = install();
	return ownlaunch() && dir != null && Files.isWritable(dir);
    }

    private static Path work() throws IOException {
	return Files.createDirectories(install().resolve(workdir));
    }

    private static Path stampFile() {
	try { return work().resolve(stampName); } catch(IOException e) { return install().resolve(workdir).resolve(stampName); }
    }

    /** Nightly stamp: tag + epoch millis. Fast path when unchanged and within 24h. */
    public static boolean isStampFresh(String tag) {
	if(tag == null) return false;
	try {
	    Path s = stampFile();
	    if(!Files.isRegularFile(s)) return false;
	    List<String> lines = Files.readAllLines(s, StandardCharsets.UTF_8);
	    if(lines.isEmpty()) return false;
	    String stampedTag = lines.get(0).trim();
	    if(!tag.equals(stampedTag)) return false;
	    if(lines.size() < 2) return true;
	    long ts = Long.parseLong(lines.get(1).trim());
	    return System.currentTimeMillis() - ts < stampValidMs;
	} catch(Exception e) { return false; }
    }

    public static void writeStamp(String tag) {
	try {
	    Path s = stampFile();
	    Files.createDirectories(s.getParent());
	    String content = tag + "\n" + System.currentTimeMillis() + "\n";
	    Files.write(s, content.getBytes(StandardCharsets.UTF_8));
	} catch(Exception e) { /* best-effort */ }
    }

    private static String mb(long bytes) { return String.format("%.1f MB", bytes / 1048576.0); }

    public static Path download(String tag, Progress prog) throws IOException {
	Path work = work(), part = work.resolve("download.part"), zip = work.resolve(tag + ".zip");
	if(Files.isRegularFile(zip)) { prog.status("Already downloaded", 1.0); return zip; }
	URL url = new URL(asseturl(tag));
	HttpURLConnection conn = (HttpURLConnection) url.openConnection();
	conn.setConnectTimeout(15000);
	conn.setReadTimeout(60000);
	conn.setRequestProperty("User-Agent", Config.confid + "/" + Config.clientVersion);
	try {
	    int code = conn.getResponseCode();
	    if(code != HttpURLConnection.HTTP_OK) throw new IOException("server returned HTTP " + code);
	    long len = conn.getContentLengthLong(), got = 0;
	    try(InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
		byte[] buf = new byte[65536];
		for(int rv = in.read(buf); rv >= 0; rv = in.read(buf)) {
		    if(prog.cancelled()) throw new Cancelled();
		    out.write(buf, 0, rv);
		    got += rv;
		    prog.status("Downloading " + mb(got) + ((len > 0) ? (" / " + mb(len)) : ""), (len > 0) ? ((double) got / (double) len) : 0.0);
		}
	    }
	} finally { conn.disconnect(); }
	Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
	return zip;
    }

    public static void verify(Path zip) throws IOException {
	try(ZipFile zf = new ZipFile(zip.toFile())) {
	    if(zf.getEntry("hafen.jar") == null) throw new IOException("update package contains no hafen.jar");
	}
    }

    public static Path unpack(Path zip, Path stage, Progress prog) throws IOException {
	rmtree(stage);
	Files.createDirectories(stage);
	try(ZipFile zf = new ZipFile(zip.toFile())) {
	    int n = Math.max(zf.size(), 1), i = 0;
	    for(Enumeration<? extends ZipEntry> ents = zf.entries(); ents.hasMoreElements();) {
		ZipEntry ent = ents.nextElement();
		if(prog.cancelled()) throw new Cancelled();
		Path dst = stage.resolve(ent.getName()).normalize();
		if(!dst.startsWith(stage)) throw new IOException("refusing archive entry outside the update: " + ent.getName());
		if(ent.isDirectory()) { Files.createDirectories(dst); }
		else {
		    Files.createDirectories(dst.getParent());
		    try(InputStream in = zf.getInputStream(ent)) { Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING); }
		    String name = dst.getFileName().toString();
		    if(name.endsWith(".sh") || name.equals("hafen.jar")) setexec(dst);
		}
		prog.status("Unpacking...", (double) (++i) / (double) n);
	    }
	}
	if(!Files.isRegularFile(stage.resolve("hafen.jar"))) throw new IOException("update package contains no hafen.jar");
	return stage;
    }

    public static void restart(String tag, Path zip) throws IOException {
	Path dir = install();
	Path jar = Files.createTempDirectory("novocaine-update").resolve("novocaine-update.jar");
	Files.copy(dir.resolve("hafen.jar"), jar);
	List<String> cmd = new ArrayList<>(Arrays.asList(javabin(), "-cp", jar.toString(), "haven.Updater", "--apply", Long.toString(ProcessHandle.current().pid()), dir.toString(), zip.toString(), tag));
	cmd.addAll(relaunch(dir));
	new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(work().resolve("update.log").toFile()).start();
    }

    public static void discard() {
	try { rmtree(install().resolve(workdir)); } catch(IOException | RuntimeException e) {}
    }

    private static String javabin() {
	Path java = Paths.get(System.getProperty("java.home"), "bin", Config.windows ? "java.exe" : "java");
	return Files.isRegularFile(java) ? java.toString() : "java";
    }

    private static List<String> relaunch(Path dir) {
	Path script = dir.resolve(launchscript()).toAbsolutePath();
	if(Config.windows) return Arrays.asList("cmd", "/c", script.toString());
	return Arrays.asList("bash", script.toString());
    }

    private static void setexec(Path p) {
	try {
	    Set<PosixFilePermission> perm = new HashSet<>(Files.getPosixFilePermissions(p));
	    perm.add(PosixFilePermission.OWNER_EXECUTE);
	    perm.add(PosixFilePermission.GROUP_EXECUTE);
	    perm.add(PosixFilePermission.OTHERS_EXECUTE);
	    Files.setPosixFilePermissions(p, perm);
	} catch(UnsupportedOperationException | IOException e) {}
    }

    private static List<Path> tree(Path root) throws IOException {
	List<Path> ret = new ArrayList<>();
	try(Stream<Path> walk = Files.walk(root)) { walk.forEach(ret::add); }
	return ret;
    }

    static String sha256(Path p) throws IOException {
	try {
	    MessageDigest md = MessageDigest.getInstance("SHA-256");
	    try(InputStream in = Files.newInputStream(p)) {
		byte[] buf = new byte[8192];
		int r;
		while((r = in.read(buf)) >= 0) md.update(buf, 0, r);
	    }
	    byte[] d = md.digest();
	    StringBuilder sb = new StringBuilder(d.length * 2);
	    for(byte b : d) sb.append(String.format("%02x", b & 0xff));
	    return sb.toString();
	} catch(Exception e) { throw new IOException(e); }
    }

    private static boolean filesEqual(Path src, Path dst) {
	try {
	    if(!Files.isRegularFile(dst)) return false;
	    if(Files.size(src) != Files.size(dst)) return false;
	    return sha256(src).equals(sha256(dst));
	} catch(IOException e) { return false; }
    }

    private static boolean isTranslations(Path rel) {
	String s = rel.toString().replace('\\', '/');
	return s.equals("Translations") || s.startsWith("Translations/");
    }

    private static void copytree(Path from, Path to) throws IOException {
	Path jar = from.resolve("hafen.jar");
	List<Path> files = tree(from);
	files.sort(Comparator.comparing((Path p) -> p.equals(jar)));
	for(Path src : files) {
	    Path rel = from.relativize(src);
	    if(isTranslations(rel)) continue;
	    Path dst = to.resolve(rel.toString());
	    if(Files.isDirectory(src)) { Files.createDirectories(dst); }
	    else {
		if(Files.isRegularFile(dst) && filesEqual(src, dst)) continue;
		copyfile(src, dst);
	    }
	}
    }

    private static void copyfile(Path src, Path dst) throws IOException {
	IOException last = null;
	for(int i = 0; i < 60; i++) {
	    try { Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES); return; }
	    catch(IOException e) {
		last = e;
		try { Thread.sleep(500); } catch(InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException(ie); }
	    }
	}
	throw last;
    }

    private static void droppkgs(Path work) throws IOException {
	try(Stream<Path> files = Files.list(work)) {
	    for(Path p : (Iterable<Path>) files::iterator) {
		String name = p.getFileName().toString();
		if(name.endsWith(".zip") || name.endsWith(".part")) Files.deleteIfExists(p);
	    }
	}
    }

    private static void rmtree(Path root) throws IOException {
	if(!Files.exists(root)) return;
	List<Path> files = tree(root);
	Collections.reverse(files);
	for(Path p : files) Files.deleteIfExists(p);
    }

    public static void main(String[] args) {
	if(args.length < 6 || !args[0].equals("--apply")) {
	    System.err.println("usage: haven.Updater --apply <pid> <install> <staging> <version> <command>...");
	    System.exit(1);
	}
	try {
	    long pid = Long.parseLong(args[1]);
	    Path dir = Paths.get(args[2]), pkg = Paths.get(args[3]);
	    String tag = args[4];
	    List<String> cmd = new ArrayList<>(Arrays.asList(args).subList(5, args.length));
	    Optional<ProcessHandle> proc = ProcessHandle.of(pid);
	    if(proc.isPresent()) {
		try { proc.get().onExit().get(120, TimeUnit.SECONDS); }
		catch(Exception e) { System.err.println("client " + pid + " did not exit: " + e); }
	    }
	    Path work = dir.resolve(workdir);
	    try {
		Path stage = Files.isDirectory(pkg) ? pkg : unpack(pkg, work.resolve("staging"), noprog);
		copytree(stage, dir);
		Files.write(dir.resolve("launcher-version.txt"), (tag + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
		try { Files.write(work.resolve(stampName), (tag + "\n" + System.currentTimeMillis() + "\n").getBytes(StandardCharsets.UTF_8)); } catch(Exception e) {}
		rmtree(stage);
		droppkgs(work);
	    } catch(Exception e) {
		e.printStackTrace();
		try { rmtree(work.resolve("staging")); } catch(IOException | RuntimeException e2) {}
		try { droppkgs(work); } catch(IOException | RuntimeException e2) {}
	    }
	    new ProcessBuilder(cmd).directory(dir.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
	} catch(Exception e) { e.printStackTrace(); System.exit(1); }
	System.exit(0);
    }
}
