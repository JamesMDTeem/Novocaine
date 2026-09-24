package haven;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The resource cache and the recorded map, each in one SQLite file instead of one file per entry
 * (after brodgar-io-client's SqliteCache, simplified, with the old store carried over).
 *
 * <p>Why: {@link HashDirCache} keeps every entry as a file of its own under {@code data/} - about
 * 120,000 of them on a well-travelled install, most of them 1-2 KB map grids. Every read opens a
 * file, takes an exclusive lock on it and parses a header, which costs hundreds of microseconds
 * (and a virus-scanner visit on Windows); a query on an open SQLite file costs tens. The map window
 * reads dozens of grids at a time while panning and zooming, so that is where it shows. And the map
 * becomes one file, which can be backed up or moved as one.
 *
 * <p><b>Nothing is lost by switching.</b> Each file is an overlay on the store it replaces: a name
 * SQLite does not hold is looked up in the old store, and what is found there is copied in. Every
 * write goes to SQLite only, so the old folder is never touched and switching back finds it as it
 * was left - without whatever was recorded in the meantime. For the map a background pass also
 * copies every entry across once, so areas that are never viewed again still arrive; resources
 * move over only as they are used, which leaves the old versions nothing asks for behind.
 *
 * <p><b>The contract {@code haven} expects of a store</b> is kept: a miss is
 * {@link FileNotFoundException} and nothing else is; a store stream writes on {@code close()} and
 * not at all if never closed (an aborted download leaves no entry); and a zero-length entry - the
 * tombstone {@code MapFile} writes for a deleted segment or an invalidated zoom level - reads back
 * as zero bytes, never as a miss, so the old store is not consulted for it either.
 *
 * <p>One writer connection under the instance monitor; reads borrow one of a few reader connections,
 * which in WAL mode block neither each other nor the writer. Several clients on one folder share the
 * file through the log and a busy timeout. A file that cannot be opened is a warning and the old
 * store for the session.
 */
public final class SqliteCache implements ResCache {
    public static final String PREF = "sqliteStore";
    static final int SCHEMA = 1;
    private static final int BUSY_MS = 5000;
    private static final int READERS = 4;

    /** On unless the player has turned it off. Read once, at startup. */
    public static boolean enabled() {
        return Utils.getprefb(PREF, true);
    }

    private static SqliteCache res, map;
    private static String resWhy, mapWhy;

    /** What {@code Resource.setcache} gets: the SQLite resource cache over {@code legacy}, or {@code legacy}. */
    public static synchronized ResCache resources(ResCache legacy) {
        if(!enabled())
            return legacy;
        if(res == null && resWhy == null) {
            try {
                res = new SqliteCache(file("rescache.sqlite"), legacy);
            } catch(Exception | LinkageError e) {
                resWhy = why(e);
                new Warning(e, "the SQLite resource cache could not be opened (" + resWhy + "); using the file cache").issue();
            }
        }
        return (res != null) ? res : legacy;
    }

    /** What {@code GameUI} records the map in: the SQLite map over {@code legacy}, or {@code legacy}. */
    public static synchronized ResCache map(ResCache legacy) {
        if(!enabled())
            return legacy;
        if(map == null && mapWhy == null) {
            try {
                map = new SqliteCache(file("map.sqlite"), legacy);
                if(legacy instanceof HashDirCache)
                    map.migrate((HashDirCache)legacy);
            } catch(Exception | LinkageError e) {
                mapWhy = why(e);
                new Warning(e, "the SQLite map could not be opened (" + mapWhy + "); using the file store").issue();
            }
        }
        return (map != null) ? map : legacy;
    }

    private static Path file(String name) throws IOException {
        Path home = Config.localdir();
        if(home == null)
            throw new IOException("no local folder to keep it in");
        return home.resolve(name);
    }

    private static String why(Throwable e) {
        String msg = e.getMessage();
        return ((msg == null) || msg.isEmpty()) ? e.toString() : msg;
    }

    static {
        Console.setscmd("store", (cons, args) -> report(cons.out));
    }

    /** Makes sure {@code :store} exists before either file has been opened. */
    public static void init() {}

    /* ------------------------------------------------------------------ the instance */

    public final Path file;
    /** The store this one replaces: read on a miss, never written. May be null. */
    public final ResCache legacy;
    private Connection conn;
    private PreparedStatement put, putnew;
    private final ArrayDeque<Reader> free = new ArrayDeque<>();

    /** A reader connection with its two statements, prepared once. One thread at a time. */
    private static final class Reader {
        final Connection conn;
        final PreparedStatement get, has;

        Reader(Connection conn) throws SQLException {
            this.conn = conn;
            this.get = conn.prepareStatement("SELECT data FROM entries WHERE name = ?");
            this.has = conn.prepareStatement("SELECT 1 FROM entries WHERE name = ?");
        }

        void close() {
            try {conn.close();} catch(SQLException e) {}
        }
    }
    private int opened = 0;
    private boolean closing = false;
    private volatile long copied = 0;
    private volatile String migration = "not needed";

    private SqliteCache(Path file, ResCache legacy) throws SQLException, IOException {
        this.file = file;
        this.legacy = legacy;
        Files.createDirectories(file.getParent());
        Connection c = connect(file);
        try(Statement st = c.createStatement()) {
            int have;
            try(ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                have = rs.next() ? rs.getInt(1) : 0;
            }
            if(have > SCHEMA)
                throw new SQLException("written by a newer client (schema " + have + ", this one writes " + SCHEMA + ")");
            st.execute("CREATE TABLE IF NOT EXISTS entries (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE," +
                       " data BLOB NOT NULL, mtime INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");
            if(have == 0)
                st.execute("PRAGMA user_version = " + SCHEMA);
            put = c.prepareStatement("INSERT INTO entries (name, data, mtime) VALUES (?, ?, ?)" +
                                     " ON CONFLICT (name) DO UPDATE SET data = excluded.data, mtime = excluded.mtime");
            /* What the old store supplies never overwrites what this client has written since. */
            putnew = c.prepareStatement("INSERT OR IGNORE INTO entries (name, data, mtime) VALUES (?, ?, ?)");
        } catch(SQLException | RuntimeException e) {
            try {c.close();} catch(SQLException x) {}
            throw e;
        }
        this.conn = c;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, file.getFileName().toString()));
        } catch(IllegalStateException e) {
            /* already exiting */
        }
    }

    private static Connection connect(Path file) throws SQLException {
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        cfg.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);
        cfg.setBusyTimeout(BUSY_MS);
        return cfg.createConnection("jdbc:sqlite:" + file);
    }

    private void close() {
        synchronized(free) {
            closing = true;
            for(Reader r : free)
                r.close();
            opened -= free.size();
            free.clear();
            free.notifyAll();
        }
        synchronized(this) {
            if(conn != null) {
                try {conn.close();} catch(SQLException e) {}
                conn = null;
            }
        }
    }

    private Reader borrow() throws IOException {
        synchronized(free) {
            while(true) {
                if(closing)
                    throw new IOException(file + " is closed");
                Reader r = free.poll();
                if(r != null)
                    return r;
                if(opened < READERS) {
                    opened++;
                    break;
                }
                try {
                    free.wait();
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException(file + ": interrupted waiting for a reader");
                }
            }
        }
        Connection c = null;
        try {
            c = connect(file);
            return new Reader(c);
        } catch(SQLException e) {
            if(c != null)
                try {c.close();} catch(SQLException x) {}
            synchronized(free) {
                opened--;
                free.notify();
            }
            throw new IOException(file + ": " + why(e), e);
        }
    }

    private void release(Reader r, boolean ok) {
        synchronized(free) {
            if(ok && !closing) {
                free.push(r);
                free.notify();
                return;
            }
            opened--;
        }
        r.close();
    }

    /** The entry's bytes, or null for a name this file does not hold. */
    private byte[] get(String name) throws IOException {
        Reader r = borrow();
        boolean ok = false;
        try {
            PreparedStatement st = r.get;
            st.setString(1, name);
            byte[] data = null;
            try(ResultSet rs = st.executeQuery()) {
                if(rs.next()) {
                    data = rs.getBytes(1);
                    if(data == null)
                        data = new byte[0];   /* the driver's spelling of a zero-length blob */
                }
            }
            ok = true;
            return data;
        } catch(SQLException e) {
            throw new IOException(file + ": " + name + ": " + why(e), e);
        } finally {
            release(r, ok);
        }
    }

    /** Whether this file, or the store under it, holds the name; without reading the entry. */
    public boolean has(String name) {
        try {
            Reader r = borrow();
            boolean ok = false;
            try {
                PreparedStatement st = r.has;
                st.setString(1, name);
                try(ResultSet rs = st.executeQuery()) {
                    ok = true;
                    if(rs.next())
                        return true;
                }
            } finally {
                release(r, ok);
            }
        } catch(Exception e) {
            return false;
        }
        if(legacy == null)
            return false;
        try(InputStream in = legacy.fetch(name)) {
            return in.read() >= 0;
        } catch(Exception e) {
            return false;
        }
    }

    public InputStream fetch(String name) throws IOException {
        byte[] data = get(name);
        if(data == null) {
            if(legacy == null)
                throw new FileNotFoundException(name);
            try(InputStream in = legacy.fetch(name)) {
                data = in.readAllBytes();
            }
            /* FileNotFoundException from the old store is this one's miss too. */
            try {
                putnew(name, data);
                copied++;
            } catch(IOException e) {
                /* The bytes are good; they are copied next time. */
            }
        }
        return new ByteArrayInputStream(data);
    }

    public OutputStream store(String name) throws IOException {
        synchronized(this) {
            if(conn == null)
                throw new IOException(file + " is closed");
        }
        return new ByteArrayOutputStream() {
            private boolean closed = false;

            public void close() throws IOException {
                if(closed)
                    return;
                closed = true;
                put(name, toByteArray());
            }
        };
    }

    private synchronized void put(String name, byte[] data) throws IOException {
        if(conn == null)
            return;   /* exiting */
        try {
            put.setString(1, name);
            put.setBytes(2, data);
            put.setLong(3, System.currentTimeMillis());
            put.executeUpdate();
        } catch(SQLException e) {
            throw new IOException(file + ": " + name + ": " + why(e), e);
        }
    }

    private synchronized void putnew(String name, byte[] data) throws IOException {
        if(conn == null)
            return;
        try {
            putnew.setString(1, name);
            putnew.setBytes(2, data);
            putnew.setLong(3, System.currentTimeMillis());
            putnew.executeUpdate();
        } catch(SQLException e) {
            throw new IOException(file + ": " + name + ": " + why(e), e);
        }
    }

    private synchronized String meta(String k) {
        if(conn == null)
            return null;
        try(PreparedStatement st = conn.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
            st.setString(1, k);
            try(ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch(SQLException e) {
            return null;
        }
    }

    private synchronized void meta(String k, String v) {
        if(conn == null)
            return;
        try(PreparedStatement st = conn.prepareStatement("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)")) {
            st.setString(1, k);
            st.setString(2, v);
            st.executeUpdate();
        } catch(SQLException e) {
            /* it runs again next session, and finds everything already copied */
        }
    }

    /**
     * Copies every entry of the old store across, once, on a background thread. Names this file
     * already holds are skipped - they are either copied already or newer - so an interrupted pass
     * simply resumes next session. A few thousand entries a second, paced so it never competes
     * with play for the disk.
     */
    private void migrate(HashDirCache old) {
        String key = "migrated:" + old.id;
        if(meta(key) != null) {
            migration = "done " + meta(key);
            return;
        }
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            int seen = 0, moved = 0;
            try {
                migration = "running";
                for(Iterator<String> i = old.list(); i.hasNext();) {
                    String name = i.next();
                    seen++;
                    /* Without haven.mapbase the map shares its identity with the resource
                     * cache, and only the map belongs in this file. */
                    if(!name.startsWith("map/"))
                        continue;
                    if(get(name) == null) {
                        byte[] data;
                        try(InputStream in = old.fetch(name)) {
                            data = in.readAllBytes();
                        } catch(FileNotFoundException e) {
                            continue;
                        }
                        putnew(name, data);
                        moved++;
                    }
                    if((seen % 500) == 0) {
                        migration = String.format("running: %d examined, %d copied", seen, moved);
                        Thread.sleep(20);
                    }
                }
                String done = String.format("%tF (%d entries examined, %d copied, %.0fs)", new java.util.Date(), seen, moved,
                                            (System.currentTimeMillis() - t0) / 1000.0);
                meta(key, done);
                migration = "done " + done;
            } catch(InterruptedException e) {
                migration = "interrupted after " + seen;
            } catch(Exception e) {
                migration = "failed after " + seen + ": " + why(e);
                new Warning(e, file + ": copying the old map across failed; it resumes next start").issue();
            }
        }, "map-sqlite-migrate");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private synchronized String stats() {
        if(conn == null)
            return "closed";
        try(Statement st = conn.createStatement()) {
            long n, sz;
            try(ResultSet rs = st.executeQuery("SELECT count(*) FROM entries")) {
                rs.next();
                n = rs.getLong(1);
            }
            try(ResultSet rs = st.executeQuery("SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size()")) {
                rs.next();
                sz = rs.getLong(1);
            }
            return String.format("%d entries, %.1f MB", n, sz / 1048576.0);
        } catch(SQLException e) {
            return why(e);
        }
    }

    public String toString() {
        return "SqliteCache(" + file + ")";
    }

    private static synchronized void report(PrintWriter out) {
        if(!enabled()) {
            out.print("store: files (SQLite is off in Novocaine Settings)\n");
        } else {
            out.print("store: sqlite\n");
            out.print("res: " + ((res != null) ? res.file + " - " + res.stats() + ", " + res.copied + " copied from the old cache this session"
                                               : "not open: " + ((resWhy != null) ? resWhy : "not yet")) + "\n");
            out.print("map: " + ((map != null) ? map.file + " - " + map.stats() + "; carry-over " + map.migration
                                               : "not open: " + ((mapWhy != null) ? mapWhy : "not until a character is in the world")) + "\n");
        }
        out.flush();
    }
}
