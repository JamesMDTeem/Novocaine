package haven.automated.mapper;

import haven.BuddyWnd;
import haven.Coord;
import haven.Coord2d;
import haven.GItem;
import haven.GameUI;
import haven.Glob;
import haven.Gob;
import haven.Indir;
import haven.Inventory;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapView;
import haven.OptWnd;
import haven.WItem;
import haven.MCache.LoadingMap;
import haven.res.ui.obj.buddy.Buddy;
import haven.res.ui.tt.q.qbuff.QBuff;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;


/** @author Vendan **/
public class MappingClient {
    /** Size of one map grid in world units (100 tiles of 11 units each). */
    private static final int GRID_SIZE = 1100;
    /** Number of tiles along one edge of a map grid (100x100). */
    private static final int TILES_PER_GRID = 100;
    /** Size of one tile in world units. */
    private static final int TILE_SIZE = 11;
    /** Interval in seconds at which live player positions are reported. */
    private static final long POSITION_UPDATE_SECONDS = 2L;

    /**
     * Timeouts for every call to the map server, in milliseconds.
     *
     * There were none, and {@code HttpURLConnection}'s default is to wait forever. Each of these
     * uploads runs on a single-threaded executor, so one unreachable or wedged server does not
     * fail a request - it parks the thread that would have made the next one, and the queue behind
     * it stops moving for the rest of the session with nothing in the log to say why. A dropped
     * update is worth nothing; a stalled uploader costs every update after it.
     *
     * Read is the longer of the two because a grid upload carries image data and the server has to
     * write it; connect only has to reach the host, and a host that has not answered in five
     * seconds is not going to.
     */
    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int READ_TIMEOUT_MS = 20000;

    /** Submission infrastructure for live player positions and grid updates. */
    private ExecutorService gridsUploader = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    
    private static volatile MappingClient INSTANCE = null;
    
    private int spamPreventionVal = 3;
    private int spamCount = 0;
	// --- P5: per-cell "seen" masks (what the character actually rendered) ---
	/** Half-width of the gob-delivery region in tiles (~45-tile square around the player). */
	private static final int SEEN_HALF_TILES = 22;
	private static final int SEEN_CELL_PX = 11;          // px per tile (1100 / 100)
	private static final int SEEN_GRID_CELLS = 100;      // cells per grid dimension
	private static final int SEEN_MASK_BYTES = 1250;     // 100*100/8
	/** gridId -> seen mask (bit per cell, LSB-first). Monotonic: only gains bits. */
	private final Map<Long, byte[]> seenMasks = new HashMap<>();
	/** Grids whose mask gained bits since the last flush. */
	private final Set<Long> dirtySeenGrids = new LinkedHashSet<>();

	/**
	 * [LEAKDBG] Size probe for the heap-leak hunt. One 1250-byte mask per grid ever rendered,
	 * never dropped, so this grows with exploration whenever tile upload is enabled - which is
	 * exactly the shape being hunted, and reason enough to log the number rather than assume.
	 *
	 * Read without a lock on purpose. seenMasks is confined to the scheduler thread (see
	 * markSeenRegion), which does not synchronise on it, so locking here would only look like a
	 * discipline that does not exist. size() is one field read; a stale count in a diagnostic
	 * line costs nothing.
	 */
	public int seenmasksz() {
	    return (seenMasks.size());
	}

    private Glob glob;
    
    public static void init(Glob glob) {
	synchronized (MappingClient.class) {
	    if(INSTANCE == null) {
		INSTANCE = new MappingClient(glob);
	    } else {
		throw new IllegalStateException("MappingClient can only be initialized once!");
	    }
	}
    }
    
    public static void destroy() {
	synchronized (MappingClient.class) {
	    if(INSTANCE != null) {
		/* Kami b9587b9ce: mark dead BEFORE shutting pools down. The marker and grid
		 * pipelines reschedule themselves, so a task already in flight would otherwise
		 * keep calling execute() on a shut-down scheduler — which throws
		 * RejectedExecutionException and silently drops the upload. Tasks check
		 * dead() and bail out instead. */
		INSTANCE.dead = true;
		// Discard the queues, don't drain them: at logout the session is over, so anything
		// still queued is stale private data from a session that is being torn down. A
		// shutdownNow drops queued tasks instead of running them to completion.
	        INSTANCE.gridsUploader.shutdownNow();
	        INSTANCE.scheduler.shutdownNow();
		INSTANCE = null;
	    }
	}
    }

    private volatile boolean dead = false;
    public boolean dead() {return dead;}

    /* Kami b9587b9ce: automap failures went to stdout only, so a dropped upload looked
     * identical to a success. Surface it in chat — the player is the only one who can act. */
    private void warn(String msg) {
	System.out.println(msg);
	try {
	    GameUI gui = glob.sess.ui.gui;
	    if(gui != null)
		gui.error(msg);
	} catch(Exception ignored) {}
    }

    /* Kami b9587b9ce: every pool submission goes through these so a shutdown race is a
     * no-op rather than a RejectedExecutionException. Returns whether the task was accepted. */
    private boolean submit(Runnable task) {
	if(dead)
	    return false;
	try {
	    scheduler.execute(task);
	    return true;
	} catch(RejectedExecutionException ex) {
	    return false;
	}
    }

    private boolean submit(Runnable task, long delay, TimeUnit unit) {
	if(dead)
	    return false;
	try {
	    scheduler.schedule(task, delay, unit);
	    return true;
	} catch(RejectedExecutionException ex) {
	    return false;
	}
    }

    private boolean submitGrid(Runnable task) {
	if(dead)
	    return false;
	try {
	    gridsUploader.execute(task);
	    return true;
	} catch(RejectedExecutionException ex) {
	    return false;
	}
    }
    
    public static boolean initialized() {return INSTANCE != null;}
    
    public static MappingClient getInstance() {
	synchronized (MappingClient.class) {
	    if(INSTANCE == null) {
		return null;
	    }
	    return INSTANCE;
	}
    }

    private PositionUpdates pu = new PositionUpdates();

    private MappingClient(Glob glob) {
	this.glob = glob;
	scheduler.scheduleAtFixedRate(pu, POSITION_UPDATE_SECONDS, POSITION_UPDATE_SECONDS, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------------------------
    // Terrain backfill
    //
    // Terrain otherwise only rides along with a grid image upload, and those only happen
    // for grids the server asks for - which means coverage tracks where the character
    // walks today, and anywhere explored before the feature existed stays blank forever.
    //
    // The client already has the answer on disk. MapFile keeps every grid this character
    // has ever seen, each with its own tileset list and per-cell indices into it, plus the
    // mtime of when it was last looked at. That is exactly a terrain sidecar, for the whole
    // explored history, and it needs no game session to read.
    //
    // Two things make this safe to run against a shared server:
    //   - We ask which grids the server is missing first, and send only those.
    //   - Every upload carries the grid's mtime, and the server keeps the later sighting.
    //     A character returning after months away cannot stamp its stale view over someone
    //     else's fresher one.
    // ---------------------------------------------------------------------------------

    /** How many grids to offer the server per gap query. Server caps at 2000. */
    private static final int BACKFILL_QUERY_BATCH = 500;
    /** Grids per multipart batch upload (P4): one request per batch instead of per grid. */
    private static final int BACKFILL_BATCH_SIZE = 20;
    /** Pause between batch uploads; the server's backfill rate lane owns pacing now. */
    private static final long BACKFILL_BATCH_GAP_MS = 500L;
    /** How many times to retry a batch the server throttled (429) or refused. */
    private static final int BACKFILL_MAX_ATTEMPTS = 3;
    /** Backoff before retrying a throttled batch. */
    private static final long BACKFILL_THROTTLE_BACKOFF_MS = 5000L;
    /** Consecutive Loading failures before a grid image upload gives up entirely. */
    private static final int GRID_LOADING_MAX_RETRIES = 6;
    /** Base backoff between Loading retries (doubles each attempt). */
    private static final long GRID_LOADING_RETRY_BACKOFF_MS = 1000L;

    private volatile boolean backfillRunning = false;
    private volatile String backfillStatus = "idle";

    public String backfillStatus() {return(backfillStatus);}
    public boolean backfillRunning() {return(backfillRunning);}

    /**
     * Walks the local map file and uploads terrain for every grid the server does not have.
     * Safe to call repeatedly - a second call while one is running is ignored. Runs on the
     * scheduler, never on the UI thread: it does disk I/O per grid.
     */
    public void startTerrainBackfill(MapFile mapfile) {
	if(mapfile == null)
	    return;
	synchronized(this) {
	    if(backfillRunning)
		return;
	    backfillRunning = true;
	}
	submit(new TerrainBackfillTask(mapfile));
    }

    private class TerrainBackfillTask implements Runnable {
	private final MapFile file;

	TerrainBackfillTask(MapFile file) {
	    this.file = file;
	}

	@Override
	public void run() {
	    int sent = 0, skipped = 0;
	    try {
		// Snapshot the segment list under the read lock rather than holding it for the
		// whole walk - loading a grid can hit disk, and the map file is live.
		List<Long> segments;
		file.lock.readLock().lock();
		try {
		    segments = new ArrayList<>(file.knownsegs);
		} finally {
		    file.lock.readLock().unlock();
		}

		for(Long sid : segments) {
		    if(!OptWnd.uploadMapTilesCheckBox.a)
			break;
		    List<Long> gridIds = new ArrayList<>();
		    file.lock.readLock().lock();
		    try {
			MapFile.Segment seg = file.segments.get(sid);
			if(seg != null)
			    gridIds.addAll(seg.map.values());
		    } finally {
			file.lock.readLock().unlock();
		    }

		    for(int i = 0; i < gridIds.size(); i += BACKFILL_QUERY_BATCH) {
			List<Long> batch = gridIds.subList(i, Math.min(i + BACKFILL_QUERY_BATCH, gridIds.size()));
			Set<String> missing = askWhichAreMissing(batch);
			if(missing == null)
			    return;          // server unreachable or refused; stop quietly
			List<String> missingList = new ArrayList<>(missing);
			for(int j = 0; j < missingList.size(); j += BACKFILL_BATCH_SIZE) {
			    if(!OptWnd.uploadMapTilesCheckBox.a)
				return;
			    List<String> chunk = missingList.subList(j, Math.min(j + BACKFILL_BATCH_SIZE, missingList.size()));
			    // A throttled/failed batch is retried with backoff, not silently skipped.
			    int handled = -1;
			    for(int attempt = 0; attempt < BACKFILL_MAX_ATTEMPTS && handled < 0; attempt++) {
				handled = uploadBatch(chunk);
				if(handled < 0) {
				    backfillStatus = "throttled - backing off";
				    try {
					Thread.sleep(BACKFILL_THROTTLE_BACKOFF_MS);
				    } catch(InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				    }
				}
			    }
			    if(handled < 0) {
				skipped += chunk.size();   // gave up after retries
			    } else {
				sent += handled;
				skipped += chunk.size() - handled;
			    }
			    backfillStatus = String.format("sent %d, skipped %d", sent, skipped);
			    try {
				Thread.sleep(BACKFILL_BATCH_GAP_MS);
			    } catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			    }
			}
		    }
		}
		backfillStatus = String.format("done: sent %d, skipped %d", sent, skipped);
	    } catch(Exception e) {
		backfillStatus = "failed: " + e.getMessage();
		System.out.println("Terrain backfill failed: " + e);
	    } finally {
		backfillRunning = false;
	    }
	}

	/** Grid ids the server has no terrain for, or null when it could not be asked. */
	private Set<String> askWhichAreMissing(List<Long> gridIds) {
	    HttpURLConnection conn = null;
	    try {
		JSONArray ids = new JSONArray();
		for(Long id : gridIds)
		    ids.put(String.valueOf(id));
		JSONObject body = new JSONObject();
		body.put("grids", ids);

		conn = (HttpURLConnection) new URL(
		    OptWnd.webmapEndpointTextEntry.buf.line() + "/terrainGaps").openConnection();
		conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
		conn.setReadTimeout(READ_TIMEOUT_MS);
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
		conn.setDoOutput(true);
		try(OutputStream out = conn.getOutputStream()) {
		    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
		}
		if(conn.getResponseCode() != 200)
		    return(null);
		StringBuilder sb = new StringBuilder();
		try(BufferedReader in = new BufferedReader(
			new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
		    String line;
		    while((line = in.readLine()) != null)
			sb.append(line);
		}
		JSONArray missing = new JSONObject(sb.toString()).getJSONArray("missing");
		Set<String> out = new LinkedHashSet<>();
		for(int i = 0; i < missing.length(); i++)
		    out.add(missing.getString(i));
		return(out);
	    } catch(Exception e) {
		System.out.println("Terrain backfill: gap query failed: " + e.getMessage());
		return(null);
	    } finally {
		if(conn != null)
		    conn.disconnect();
	    }
	}

	/** Sends one batch of grids' stored terrain via /gridUploadBatch (terrainOnly mode).
	 *  Returns how many grids the server accepted (accepted + silent), or -1 when the
	 *  whole batch was refused/throttled and should be retried with backoff. */
	private int uploadBatch(List<String> gridIds) {
	    try {
		MultipartUtility multipart = new MultipartUtility(
		    OptWnd.webmapEndpointTextEntry.buf.line() + "/gridUploadBatch", "utf-8");
		JSONObject metadata = new JSONObject();
		metadata.put("backfill", true);
		metadata.put("terrainOnly", true);
		JSONArray ids = new JSONArray();
		for(String gid : gridIds)
		    ids.put(gid);
		metadata.put("ids", ids);
		multipart.addFormField("metadata", metadata.toString());

		int i = 0;
		for(String gid : gridIds) {
		    MapFile.Grid grid;
		    file.lock.readLock().lock();
		    try {
			grid = MapFile.Grid.load(file, Long.parseLong(gid));
		    } catch(Exception e) {
			grid = null;
		    } finally {
			file.lock.readLock().unlock();
		    }
		    // Indexed but absent happens (crashes, reboots); export() tolerates it, so do we.
		    if(grid == null || grid.tilesets == null || grid.tiles == null)
			continue;

		    ByteArrayOutputStream cells = new ByteArrayOutputStream(20000);
		    for(int t : grid.tiles) {
			cells.write(t & 0xFF);
			cells.write((t >> 8) & 0xFF);
		    }
		    JSONArray names = new JSONArray();
		    for(MapFile.TileInfo ti : grid.tilesets)
			names.put((ti == null || ti.res == null) ? "" : ti.res.name);

		    multipart.addFilePart("terrain_" + i, new ByteArrayInputStream(cells.toByteArray()), "terrain.bin");
		    multipart.addFormField("tilesets_" + i, names.toString());
		    multipart.addFormField("observedAt_" + i, String.valueOf(grid.mtime));
		    i++;
		}
		if(i == 0)
		    return 0;

		MultipartUtility.Response response = multipart.finish();
		JSONObject body = new JSONObject(response.response);
		return body.getInt("accepted") + body.getInt("silent");
	    } catch(Exception e) {
		System.out.println("Terrain backfill: batch upload failed: " + e.getMessage());
		return(-1);   // 429 or network error -> caller backs off and retries
	    }
	}
    }

    private String playerName;

    public void SetPlayerName(String name) {
	playerName = name;
    }

    public void Track(long id, Coord2d coordinates) {
	try {
	    MCache.Grid g = glob.map.getgrid(toGC(coordinates));
	    pu.Track(id, coordinates, g.id);
	} catch (Exception ex) {
	    // Grid lookup failed - character likely in unmapped area
	}
    }
    
    private Coord lastGC = null;

    public void EnterGrid(Coord gc) {
	lastGC = gc;
	submit(new GenerateGridUpdateTask(gc));
    }

    public void CheckGridCoord(Coord2d c) {
	Coord gc = toGC(c);
	if(lastGC == null || !gc.equals(lastGC)) {
	    EnterGrid(gc);
	}
    }

    /**
     * Schedules marker extraction and upload from a map file.
     *
     * Currently disabled - the extraction workflow is commented out. Markers are now handled
     * through the direct upload path in {@link #UploadMarker(MapFile.SMarker)} instead.
     */
    public void ProcessMap(MapFile mapfile, Predicate<MapFile.Marker> uploadCheck) {
	// Extraction workflow disabled - markers uploaded directly via UploadMarker
	// scheduler.schedule(new ExtractMapper(mapfile, uploadCheck), 5, TimeUnit.SECONDS);
    }

    /** No longer used - marker extraction workflow disabled. Kept for reference. */
    private class ExtractMapper implements Runnable {
	MapFile mapfile;
	Predicate<MapFile.Marker> uploadCheck;
	int retries = 5;

	ExtractMapper(MapFile mapfile, Predicate<MapFile.Marker> uploadCheck) {
	    this.mapfile = mapfile;
	    this.uploadCheck = uploadCheck;
	}

	@Override
	public void run() {
//		if (mapfile.lock.readLock().tryLock()) {
//			try {
//				List<MarkerData> markers = mapfile.markers.stream()
//						.filter(uploadCheck)
//						.map(m -> {
//							Coord mgc = new Coord(Math.floorDiv(m.tc.x, TILES_PER_GRID), Math.floorDiv(m.tc.y, TILES_PER_GRID));
//							Indir<MapFile.Grid> indirGrid = mapfile.segments.get(m.seg).grid(mgc);
//							return new MarkerData(m, indirGrid);
//						})
//						.collect(Collectors.toList());
//
//				scheduler.execute(new ProcessMapper(mapfile, markers));
//			} finally {
//				mapfile.lock.readLock().unlock();
//			}
//		} else {
//			if (retries-- > 0) {
//				scheduler.schedule(this, 5, TimeUnit.SECONDS);
//			}
//		}
	}

    }

    /** No longer used - marker extraction workflow disabled. Kept for reference. */
    private class MarkerData {
	MapFile.Marker m;
	Indir<MapFile.Grid> indirGrid;

	MarkerData(MapFile.Marker m, Indir<MapFile.Grid> indirGrid) {
	    this.m = m;
	    this.indirGrid = indirGrid;
	}
    }

    /** No longer used - marker extraction workflow disabled. Kept for reference. */
    private class ProcessMapper implements Runnable {
	MapFile mapfile;
	List<MarkerData> markers;

	ProcessMapper(MapFile mapfile, List<MarkerData> markers) {
	    this.mapfile = mapfile;
	    this.markers = markers;
	}

	@Override
	public void run() {
	    ArrayList<JSONObject> loadedMarkers = new ArrayList<>();
	    while (!markers.isEmpty()) {
		Iterator<MarkerData> iterator = markers.iterator();
		while (iterator.hasNext()) {
		    MarkerData md = iterator.next();
		    try {
			Coord mgc = new Coord(Math.floorDiv(md.m.tc.x, TILES_PER_GRID), Math.floorDiv(md.m.tc.y, TILES_PER_GRID));
			long gridId;
			try {
				gridId = md.indirGrid.get().id;
			} catch (Exception e) {
				iterator.remove();
				continue;
			}
			JSONObject o = new JSONObject();
			o.put("name", md.m.nm);
			o.put("gridID", String.valueOf(gridId));
			Coord gridOffset = md.m.tc.sub(mgc.mul(TILES_PER_GRID));
			o.put("x", gridOffset.x);
			o.put("y", gridOffset.y);

			if(md.m instanceof MapFile.SMarker) {
			    o.put("type", "shared");
			    /* String.valueOf, because SMarker.oid is a UID and UID extends Number: org.json
			     * writes any Number verbatim and unquoted, and UID.toString() is unsigned
			     * base-16. That put a bare 531a9f2e44c81b07 in the payload, which is not JSON
			     * at all - so the whole batch was rejected, not just this field, and every
			     * shared marker silently failed to upload to any strict parser. */
			    o.put("id", String.valueOf(((MapFile.SMarker) md.m).oid));
			    o.put("image", ((MapFile.SMarker) md.m).res.name);
			} else if(md.m instanceof MapFile.PMarker) {
			    o.put("type", "player");
			    o.put("color", ((MapFile.PMarker) md.m).color);
			}
			loadedMarkers.add(o);
			iterator.remove();
		    } catch (Loading ex) {
		    }
		}
		try {
		    Thread.sleep(50);
		} catch (InterruptedException ex) { }
	    }
	    if(dead())
		return;
	    if(!submit(new MarkerUpdate(new JSONArray(loadedMarkers.toArray()))))
		warn(String.format("Marker upload failed: could not queue %d markers.", loadedMarkers.size()));
	}
    }

    /** Sends marker updates to the webmap endpoint. */
    private class MarkerUpdate implements Runnable {
	JSONArray data;

	MarkerUpdate(JSONArray data) {
	    this.data = data;
	}

	@Override
	public void run() {
	    HttpURLConnection connection = null;
	    try {
		connection =
		    (HttpURLConnection) new URL(OptWnd.webmapEndpointTextEntry.buf.line() + "/markerUpdate").openConnection();
		connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
		connection.setReadTimeout(READ_TIMEOUT_MS);
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
		connection.setDoOutput(true);
		try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
		    final String json = data.toString();
		    out.write(json.getBytes(StandardCharsets.UTF_8));
		}
		int code = connection.getResponseCode();
		if(code < 200 || code >= 300)
		    warn(String.format("Marker upload rejected by the server (HTTP %d).", code));
	    } catch (Exception ex) {
		warn("Marker upload failed: " + ex.getMessage());
	    } finally {
		if (connection != null) {
		    connection.disconnect();
		}
	    }
	}
    }
    
    /** Tracks active character positions for periodic position updates. */
    private class PositionUpdates implements Runnable {
	/** Live position tracking entry for a single character. */
        private class Tracking {
	    public String name;
	    public String type;
	    public long gridId;
	    public Coord2d coords;
	    
	    public JSONObject getJSON() {
		JSONObject j = new JSONObject();
		j.put("name", name);
		j.put("type", type);
		j.put("gridID", String.valueOf(gridId));
		JSONObject c = new JSONObject();
		c.put("x", (int) (coords.x / TILE_SIZE));
		c.put("y", (int) (coords.y / TILE_SIZE));
		j.put("coords", c);
		return j;
	    }
	}
	
	private Map<Long, Tracking> tracking = new ConcurrentHashMap<Long, Tracking>();

	
	private PositionUpdates() {
	}
	
	private void Track(long id, Coord2d coordinates, long gridId) {
	    Tracking t = tracking.get(id);
	    boolean fresh = (t == null);
	    if(fresh) {
		/* Filled in FIRST and published at the end of this method, not here.
		 *
		 * It used to go into the map the moment it was constructed, with coords still null,
		 * and be finished several statements later. PositionUpdater iterates this map from
		 * the scheduler thread every two seconds, so landing in that window read a Tracking
		 * whose getJSON dereferences coords - an NPE, thrown out of a scheduleAtFixedRate
		 * task, which Java answers by cancelling the task for good. The symptom is one
		 * character quietly missing from the live map for the rest of the session while
		 * everyone else keeps updating, and nothing in the log to say why. */
		t = new Tracking();

		if(id == glob.sess.ui.gui.map.plgob) {
		    t.name = playerName;
		    t.type = "player";
		} else {
		    Glob g = glob;
		    Gob gob = g.oc.getgob(id);
		    t.name = "???";
		    t.type = "white";
		    if(gob != null) {
			Buddy bud = gob.getattr(Buddy.class);
			if(bud != null && bud.rgrp != -1) {
			    t.name = bud.rnm;
			    t.type = Integer.toHexString(BuddyWnd.gc[bud.rgrp].getRGB());
			}
		    }
		}
	    }
	    t.gridId = gridId;
	    t.coords = gridOffset(coordinates);
	    if(fresh)
		tracking.put(id, t);
	}
	
	/* Re-reads the player straight from the session, rather than waiting to be told.
	 *
	 * Track() is only ever called from Gob.move(). That covers walking, and nothing else:
	 * a character who arrives somewhere without taking a step - return to dock, waystation,
	 * carriage, boat - never reports the arrival, so the entry keeps the grid it left from
	 * and the icon on the web map sits at the old location. It looks like the page is stale
	 * because reloading appears to fix it; in fact the next footstep is what fixes it, and a
	 * reload just happens to come after one.
	 *
	 * Reading the position here makes the feed self-healing: whatever did or did not fire a
	 * movement event, the next send carries where the character actually is.
	 *
	 * Right after a teleport the destination grid is usually not paged in yet and getgrid
	 * throws. That is a reason to try again on the next tick, not to publish a position we
	 * know is wrong, so the entry is left untouched and the retry carries it. */
	private void refreshPlayer() {
	    Glob g = glob;
	    if((g.sess == null) || (g.sess.ui == null) || (g.sess.ui.gui == null))
		return;
	    MapView mv = g.sess.ui.gui.map;
	    if(mv == null)
		return;
	    Gob pl = mv.player();
	    if(pl == null)
		return;
	    Coord2d rc = pl.rc;
	    if(rc == null)
		return;
	    try {
		MCache.Grid grid = g.map.getgrid(toGC(rc));
		Track(pl.id, rc, grid.id);
	    } catch(Loading l) {
		// Destination not paged in yet - next tick.
	    } catch(Exception e) {
		// Same: an unmapped area is a wait, not a failure.
	    }
	}

	@Override
	public void run() {
	    /* NOTHING may escape this method.
	     *
	     * scheduleAtFixedRate answers an uncaught throwable by cancelling the task - silently,
	     * permanently, and with no way back short of a restart. For a two-second position feed
	     * that trades a dropped update for the whole feed, which is a terrible bargain: any
	     * transient fault in here (a half-built gob, a window torn down mid-login, a Loading
	     * from item info) costs this character its place on the live map for the session.
	     *
	     * The HTTP work below has its own catch and keeps it. This is the outer guarantee, and
	     * it is deliberately Throwable rather than Exception - an Error escaping is even less of
	     * a reason to stop reporting positions. */
	    try {
	    /* P5: sample what is on screen every tick, not only on the send tick.
	     *
	     * This used to sit inside the spam gate below, so the seen region was only sampled
	     * once every third tick - about six seconds. A character on foot covers more ground
	     * in six seconds than the ~45-tile region is wide, so consecutive samples did not
	     * overlap and the overlay came out as stripes with unseen gaps between them.
	     *
	     * Sampling is local bit-setting against the in-memory masks and costs nothing on the
	     * wire: the upload still happens on the send tick, and still only for masks whose
	     * bits actually changed. */
	    markSeenRegion();
	    if(spamCount == spamPreventionVal) {
		spamCount = 0;
		if(OptWnd.sendLiveLocationCheckBox.a) {
		    // Ask the session where the player is before serialising, so an arrival that
		    // fired no movement event still goes out with this send.
		    refreshPlayer();
		    Glob g = glob;
		    Iterator<Map.Entry<Long, Tracking>> i = tracking.entrySet().iterator();
		    JSONObject upload = new JSONObject();
		    while (i.hasNext()) {
			Map.Entry<Long, Tracking> e = i.next();
			if(g.oc.getgob(e.getKey()) == null) {
			    i.remove();
			} else {
			    upload.put(String.valueOf(e.getKey()), e.getValue().getJSON());
			}
		    }
		    
		    HttpURLConnection posConn = null;
		    try {
			posConn =
			    (HttpURLConnection) new URL(OptWnd.webmapEndpointTextEntry.buf.line() + "/positionUpdate").openConnection();
			posConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			posConn.setReadTimeout(READ_TIMEOUT_MS);
			posConn.setRequestMethod("POST");
			posConn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
			posConn.setDoOutput(true);
			try (DataOutputStream out = new DataOutputStream(posConn.getOutputStream())) {
			    final String json = upload.toString();
			    out.write(json.getBytes(StandardCharsets.UTF_8));
			}
			int code = posConn.getResponseCode();
			if(code < 200 || code >= 300)
			    warn(String.format("Position update rejected by the server (HTTP %d).", code));
		    } catch (final Exception ex) {
			warn("Position update failed: " + ex.getMessage());
		    } finally {
			if(posConn != null) posConn.disconnect();
		    }
		}
	    } else {
		spamCount++;
	    }
	    try {
		flushSeenMasks();
	    } catch(final Throwable t) {
		// Seen uploads are best-effort; a failure here must not kill the position feed.
	    }
	    } catch(final Throwable t) {
		// Deliberately silent, like the HTTP catch below it: this runs every two seconds, so
		// a fault that recurs would fill the log with the same line hundreds of times a shift.
	    }
	}
    }

    /* Marks every cell inside the gob-delivery region around the player as seen, in the
     * per-grid bitmasks. Monotonic: bits are only ever set, so a grid whose mask did not
     * change is not re-uploaded. Runs on the scheduler thread, never the UI thread. */
    private void markSeenRegion() {
	Glob g = glob;
	if((g.sess == null) || (g.sess.ui == null) || (g.sess.ui.gui == null))
	    return;
	MapView mv = g.sess.ui.gui.map;
	if(mv == null)
	    return;
	Gob pl = mv.player();
	if((pl == null) || (pl.rc == null))
	    return;
	int px = (int) pl.rc.x;
	int py = (int) pl.rc.y;
	int span = SEEN_HALF_TILES * SEEN_CELL_PX;
	int cellMinX = Math.floorDiv(px - span, SEEN_CELL_PX);
	int cellMaxX = Math.floorDiv(px + span, SEEN_CELL_PX);
	int cellMinY = Math.floorDiv(py - span, SEEN_CELL_PX);
	int cellMaxY = Math.floorDiv(py + span, SEEN_CELL_PX);

	for(int cy = cellMinY; cy <= cellMaxY; cy++) {
	    int gridY = Math.floorDiv(cy, SEEN_GRID_CELLS);
	    int cellY = Math.floorMod(cy, SEEN_GRID_CELLS);
	    for(int cx = cellMinX; cx <= cellMaxX; cx++) {
		int gridX = Math.floorDiv(cx, SEEN_GRID_CELLS);
		int cellX = Math.floorMod(cx, SEEN_GRID_CELLS);
		long gridId;
		try {
		    gridId = g.map.getgrid(new Coord(gridX, gridY)).id;
		} catch(Exception e) {
		    continue; // grid not loaded; can't attribute cells to it yet
		}
		byte[] mask = seenMasks.get(gridId);
		if(mask == null) {
		    mask = new byte[SEEN_MASK_BYTES];
		    seenMasks.put(gridId, mask);
		}
		int cell = cellY * SEEN_GRID_CELLS + cellX;
		int bit = 1 << (cell & 7);
		if((mask[cell >> 3] & bit) == 0) {
		    mask[cell >> 3] |= bit;
		    dirtySeenGrids.add(gridId);
		}
	    }
	}
    }

    /* Uploads the seen masks that gained bits since the last flush. Monotonic OR-merge on
     * the server, so re-sending is harmless and never un-sees. Best-effort: failures are
     * dropped here and the dirty set is retained for the next tick. */
    private void flushSeenMasks() {
	if(dirtySeenGrids.isEmpty() || !OptWnd.sendLiveLocationCheckBox.a)
	    return;
	try {
	    JSONArray items = new JSONArray();
	    for(Long gridId : dirtySeenGrids) {
		byte[] mask = seenMasks.get(gridId);
		if(mask == null)
		    continue;
		JSONObject item = new JSONObject();
		item.put("id", String.valueOf(gridId));
		item.put("seen", java.util.Base64.getEncoder().encodeToString(mask));
		items.put(item);
	    }
	    dirtySeenGrids.clear();
	    if(items.length() == 0)
		return;
	    final HttpURLConnection connection =
		(HttpURLConnection) new URL(OptWnd.webmapEndpointTextEntry.buf.line() + "/seenUpload").openConnection();
	    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
	    connection.setReadTimeout(READ_TIMEOUT_MS);
	    connection.setRequestMethod("POST");
	    connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
	    connection.setDoOutput(true);
	    try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
		out.write(items.toString().getBytes(StandardCharsets.UTF_8));
	    }
	    int code = connection.getResponseCode();
	    if(code < 200 || code >= 300)
		warn(String.format("Seen upload rejected by the server (HTTP %d).", code));
	    connection.disconnect();
	} catch(Exception e) {
	    warn("Seen upload failed: " + e.getMessage());
	}
    }

    /** P6: uploads a collectable timer/quality observation to /markerReady, which
     *  collapses the ready window to the exact countdown, resets it on collection
     *  (Collected: true -> now + the type's max) and records the gathered quality. */
    public void uploadCollectable(Gob gob, String objectType, long readyAtMs, Integer quality, boolean collected) {
	try {
	    MCache.Grid grid = glob.map.getgrid(toGridCoordinate(gob.rc));
	    Coord offset = gridOffset2(gob.rc);

	    JSONObject obj = new JSONObject();
	    obj.put("gridID", String.valueOf(grid.id));
	    obj.put("x", offset.x);
	    obj.put("y", offset.y);
	    obj.put("objectType", objectType);
	    obj.put("quality", (quality == null) ? JSONObject.NULL : quality.intValue());
	    obj.put("collected", collected);
	    if(readyAtMs > 0) {
		obj.put("maxReady", readyAtMs);
		obj.put("minReady", readyAtMs);
	    }
	    submit(new CollectableUpdate(new JSONArray().put(obj)));
	} catch (Loading ignored) {
	}
    }

    private class CollectableUpdate implements Runnable {
	JSONArray data;

	CollectableUpdate(JSONArray data) {
	    this.data = data;
	}

	@Override
	public void run() {
	    HttpURLConnection connection = null;
	    try {
		connection =
		    (HttpURLConnection) new URL(OptWnd.webmapEndpointTextEntry.buf.line() + "/markerReady").openConnection();
		connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
		connection.setReadTimeout(READ_TIMEOUT_MS);
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
		connection.setDoOutput(true);
		try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
		    final String json = data.toString();
		    out.write(json.getBytes(StandardCharsets.UTF_8));
		}
		int code = connection.getResponseCode();
		if(code < 200 || code >= 300)
		    warn(String.format("Collectable upload rejected by the server (HTTP %d).", code));
	    } catch(Exception e) {
		warn("Collectable upload failed: " + e.getMessage());
	    } finally {
		if(connection != null)
		    connection.disconnect();
	    }
	}
    }

    // --- P6: gather detection from the player's main inventory ----------------------
    //
    // The game's only reliable signal that a collectable was actually gathered is the
    // item appearing in the inventory. The earlier hook watched chat for "Quality: N"
    // on the study cursor, which fires on *inspection*, never on a real gather - so no
    // gather was ever reported and no quality ever reached the mapper. Here we watch
    // maininv for a collectable item arriving and read the quality from the item
    // itself, exactly as the game shows it to the player.

    /** Items whose resource basename differs from the gob they were gathered from. */
    private static final Map<String, String> COLLECTABLE_ITEM_GOB_ALIASES = new HashMap<>();
    static {
	// Bat Guano is gathered from a Guano Pile.
	COLLECTABLE_ITEM_GOB_ALIASES.put("batguano", "guanopile");
    }

    private long lastCollectablePoll = 0;
    private static final long COLLECTABLE_POLL_INTERVAL_MS = 1000;
    /** Number of tiles around the player we will look for the gathered gob. */
    private static final double COLLECTABLE_GOB_RADIUS = 4 * TILE_SIZE;
    /** How long we wait for the item's quality info to load before giving up on it. */
    private static final long COLLECTABLE_QUALITY_TIMEOUT_MS = 30000;

    /** Last observed per-key item counts in maininv; null until the first snapshot. */
    private Map<String, Integer> collectableCounts = null;
    /** Keys whose count increased but whose quality info is still loading. */
    private final Map<String, PendingGather> pendingGathers = new HashMap<>();

    private static class PendingGather {
	final String key;
	final Gob gob;
	final long seenAt;
	PendingGather(String key, Gob gob) {
	    this.key = key;
	    this.gob = gob;
	    this.seenAt = System.currentTimeMillis();
	}
    }

    private static String itemRes(GItem item) {
	try {
	    return item.getres().name;
	} catch(Loading l) {
	    return null;
	}
    }

    private static String gobRes(Gob gob) {
	try {
	    return gob.getres().name;
	} catch(Loading l) {
	    return null;
	}
    }

    /** Normalizes a resource name to the collectable key used on the mapper: basename,
     *  lowercase, spaces/underscores/dashes stripped - mirroring the server's
     *  CollectableTimerTable.Normalize. Bat Guano is aliased to its Guano Pile gob. */
    private static String collectableKey(String resname) {
	if(resname == null)
	    return null;
	int slash = resname.lastIndexOf('/');
	String base = (slash >= 0) ? resname.substring(slash + 1) : resname;
	base = base.toLowerCase(Locale.ROOT).replaceAll("[ _-]", "");
	if(base.isEmpty())
	    return null;
	String alias = COLLECTABLE_ITEM_GOB_ALIASES.get(base);
	return (alias != null) ? alias : base;
    }

    /** Called from GameUI.tick. Detects a collectable item arriving in maininv and
     *  uploads it as the gather signal (timer reset + quality from the item). */
    public void pollCollectableInventory(GameUI gui) {
	try {
	    if(gui == null || gui.maininv == null)
		return;
	    long now = System.currentTimeMillis();
	    if(now - lastCollectablePoll < COLLECTABLE_POLL_INTERVAL_MS) {
		resolvePendingGathers(gui, now);
		return;
	    }
	    lastCollectablePoll = now;

	    Map<String, Integer> counts = new HashMap<>();
	    synchronized(gui.maininv.wmap) {
		for(WItem w : gui.maininv.getAllItems()) {
		    String key = collectableKey(itemRes(w.item));
		    if(key == null)
			continue;
		    int n = (w.item.num > 1) ? w.item.num : 1;
		    counts.merge(key, n, Integer::sum);
		}
	    }
	    if(collectableCounts == null) {
		// First snapshot: just establish the baseline, never fire on login items.
		collectableCounts = counts;
		return;
	    }
	    for(Map.Entry<String, Integer> e : counts.entrySet()) {
		int prev = collectableCounts.getOrDefault(e.getKey(), 0);
		if(e.getValue() > prev && !pendingGathers.containsKey(e.getKey())) {
		    Gob gob = findCollectableGob(gui, e.getKey());
		    if(gob != null)
			pendingGathers.put(e.getKey(), new PendingGather(e.getKey(), gob));
		}
	    }
	    collectableCounts = counts;
	    resolvePendingGathers(gui, now);
	} catch(Exception ignored) {
	}
    }

    /** The gob that was just gathered: the one the player most recently inspected if it
     *  is this type, otherwise the nearest gob of this type within gather range. */
    private Gob findCollectableGob(GameUI gui, String key) {
	Gob inspected = gui.lastInspectedGob;
	if(inspected != null && key.equals(collectableKey(gobRes(inspected))))
	    return inspected;
	Gob pl = (gui.map != null) ? gui.map.player() : null;
	Coord2d pc = (pl != null) ? pl.rc : null;
	double bestD = Double.MAX_VALUE;
	Gob best = null;
	String fallbackKey = null;
	synchronized(glob.oc) {
	    for(Gob g : glob.oc) {
		if(g == pl)
		    continue;
		String gkey = collectableKey(gobRes(g));
		if(gkey == null)
		    continue;
		// Exact key match (normalized + aliased). Items whose gob resname differs
		// unpredictably still report through the contains/prefix fallback below.
		if(key.equals(gkey)) {
		    double d = (pc != null) ? g.rc.dist(pc) : 0;
		    if(d < bestD) {
			bestD = d;
			best = g;
		    }
		} else if(fallbackKey == null && gobRes(g) != null &&
		    (gkey.contains(key) || key.contains(gkey)))
		    fallbackKey = gkey;
	    }
	}
	if(best != null && (pc == null || bestD <= COLLECTABLE_GOB_RADIUS))
	    return best;
	// No exact-key gob found: fall back to the first gob whose key overlaps the item
	// key (e.g. item "fairy" -> gob "fairystone"), so the gather still gets reported.
	if(fallbackKey != null) {
	    synchronized(glob.oc) {
		for(Gob g : glob.oc) {
		    if(g == pl)
			continue;
		    String gkey = collectableKey(gobRes(g));
		    if(fallbackKey.equals(gkey)) {
			double d = (pc != null) ? g.rc.dist(pc) : 0;
			if(d <= COLLECTABLE_GOB_RADIUS)
			    return g;
		    }
		}
	    }
	}
	return null;
    }

    /** Reads the item's quality (as shown in the inventory) for a pending gather key. */
    private Integer findItemQuality(GameUI gui, String key) {
	if(gui.maininv == null)
	    return null;
	synchronized(gui.maininv.wmap) {
	    for(WItem w : gui.maininv.getAllItems()) {
		String res = itemRes(w.item);
		if(res == null || !key.equals(collectableKey(res)))
		    continue;
		QBuff qb = w.item.getQBuff();
		if(qb != null)
		    return (int) Math.round(qb.q);
	    }
	}
	return null;
    }

    /** Flushes pending gathers once the item's quality has loaded (or after a timeout,
     *  in which case the timer is still reset but quality is left null). */
    private void resolvePendingGathers(GameUI gui, long now) {
	Iterator<Map.Entry<String, PendingGather>> it = pendingGathers.entrySet().iterator();
	while(it.hasNext()) {
	    PendingGather pg = it.next().getValue();
	    Integer quality = findItemQuality(gui, pg.key);
	    if(quality == null && (now - pg.seenAt) < COLLECTABLE_QUALITY_TIMEOUT_MS)
		continue;
	    uploadCollectable(pg.gob, gobRes(pg.gob), 0L, quality, true);
	    it.remove();
	}
    }

    /** Container for a 3x3 grid region plus weak refs to backing MCache.Grid objects. */
    private static class GridUpdate {
	String[][] grids;
	Map<String, WeakReference<MCache.Grid>> gridRefs;
	
	GridUpdate(final String[][] grids, Map<String, WeakReference<MCache.Grid>> gridRefs) {
	    this.grids = grids;
	    this.gridRefs = gridRefs;
	}
	
	@Override
	public String toString() {
	    return String.format("GridUpdate (%s)", grids[1][1]);
	}
    }
    
    /** Generates a grid update for a 3x3 region around the given coordinate. */
    private class GenerateGridUpdateTask implements Runnable {
	Coord coord;
	int retries = 3;
	
	GenerateGridUpdateTask(Coord c) {
	    this.coord = c;
	}
	
	@Override
	public void run() {
	    if(OptWnd.uploadMapTilesCheckBox.a) {
		final String[][] gridMap = new String[3][3];
		Map<String, WeakReference<MCache.Grid>> gridRefs = new HashMap<String, WeakReference<MCache.Grid>>();
		try {
		    for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
			    final MCache.Grid subg = glob.map.getgrid(coord.add(x, y));
			    gridMap[x + 1][y + 1] = String.valueOf(subg.id);
			    gridRefs.put(String.valueOf(subg.id), new WeakReference<MCache.Grid>(subg));
			}
		    }
		    submit(new UploadGridUpdateTask(new GridUpdate(gridMap, gridRefs)));
		} catch (LoadingMap lm) {
		    retries--;
		    if(retries >= 0) {
			submit(this, 1L, TimeUnit.SECONDS);
		    }
		} catch (Exception e) {
		    System.out.println(e);
		}
		;
	    }
	}
    }
    
    /** Uploads a generated grid update and dispatches per-grid image uploads. */
    private class UploadGridUpdateTask implements Runnable {
	private final GridUpdate gridUpdate;
	
	UploadGridUpdateTask(final GridUpdate gridUpdate) {
	    this.gridUpdate = gridUpdate;
	}
	
	@Override
	public void run() {
	    if(OptWnd.uploadMapTilesCheckBox.a) {
		HashMap<String, Object> dataToSend = new HashMap<>();
		
		dataToSend.put("grids", this.gridUpdate.grids);
		HttpURLConnection connection = null;
		try {
		    connection =
			(HttpURLConnection) new URL(OptWnd.webmapEndpointTextEntry.buf.line() + "/gridUpdate").openConnection();
		    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
		    connection.setReadTimeout(READ_TIMEOUT_MS);
		    connection.setRequestMethod("POST");
		    connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
		    connection.setDoOutput(true);
		    try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
			String json = new JSONObject(dataToSend).toString();
			out.write(json.getBytes(StandardCharsets.UTF_8));
		    }
		    int code = connection.getResponseCode();
		    if(code == 200) {
			DataInputStream dio = new DataInputStream(connection.getInputStream());
			int nRead;
			byte[] data = new byte[1024];
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			while ((nRead = dio.read(data, 0, data.length)) != -1) {
			    buffer.write(data, 0, nRead);
			}
			buffer.flush();
			String response = buffer.toString(StandardCharsets.UTF_8);
			JSONObject jo = new JSONObject(response);
			/* Only 'gridRequests' is read. The response also carries 'map' and
			 * 'coords', but nothing consumes them, so they are left alone. */
			JSONArray reqs = jo.optJSONArray("gridRequests");
			for (int i = 0; reqs != null && i < reqs.length(); i++) {
			    if(dead()) break;
			    if(!submitGrid(new GridUploadTask(reqs.getString(i), gridUpdate.gridRefs.get(reqs.getString(i)))))
				warn(String.format("Grid upload failed: could not queue %s.", reqs.getString(i)));
			}
		    } else {
			warn(String.format("Grid update rejected by the server (HTTP %d).", code));
		    }
		} catch (Exception ex) {
		    warn("Grid update failed: " + ex.getMessage());
		} finally {
		    if (connection != null) {
			connection.disconnect();
		    }
		}
	    }
	}
    }
    
    /** Uploads a single grid's minimap image via multipart POST. */
    private class GridUploadTask implements Runnable {
	private final String gridID;
	private final WeakReference<MCache.Grid> grid;
	/** Consecutive Loading failures already retried for this grid. */
	private int loadingRetries = 0;
	
	GridUploadTask(String gridID, WeakReference<MCache.Grid> grid) {
	    this.gridID = gridID;
	    this.grid = grid;
	}
	
	@Override
	public void run() {
	    // The opt-in is re-checked here, not just where the task was submitted: a grid queued
	    // while uploads were on must not go out once the player switched them off. A task that
	    // is repeatedly resubmitted (below) would otherwise keep uploading past a toggle-off.
	    if(!OptWnd.uploadMapTilesCheckBox.a)
		return;
	    try {
		MCache.Grid g = grid.get();
		if(g != null && glob != null) {
			BufferedImage image = MinimapImageGenerator.drawmap(glob.map, g);
		    if(image == null) {
			throw new Loading();
		    }
		    try {
			JSONObject extraData = new JSONObject();
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			ImageIO.write(image, "png", outputStream);
			ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
			MultipartUtility multipart = new MultipartUtility(OptWnd.webmapEndpointTextEntry.buf.line() + "/gridUpload", "utf-8");
			multipart.addFormField("id", this.gridID);
			multipart.addFilePart("file", inputStream, "minimap.png");
			extraData.put("season", glob.ast.is);
			multipart.addFormField("extraData", extraData.toString());

			// Terrain: per-cell tile ids (100x100, uint16 LE) so the server can show
			// biome info on the map. The id -> resource name table rides along so the
			// server never needs a separate handshake; it is tiny (a few dozen entries).
			ByteArrayOutputStream terrainOut = new ByteArrayOutputStream(20000);
			Coord tc = new Coord();
			for(tc.y = 0; tc.y < MCache.cmaps.y; tc.y++) {
			    for(tc.x = 0; tc.x < MCache.cmaps.x; tc.x++) {
				int tid = g.gettile(tc);
				terrainOut.write(tid & 0xFF);
				terrainOut.write((tid >> 8) & 0xFF);
			    }
			}
			multipart.addFilePart("terrain", new ByteArrayInputStream(terrainOut.toByteArray()), "terrain.bin");
			// When we actually looked at this ground. A grid only refreshes while the
			// character is standing in it, so upload order says nothing about whose view
			// is newer; the server keeps whichever was observed last, not whichever
			// arrived last. This grid is in view right now, so that is now.
			multipart.addFormField("terrainObservedAt", String.valueOf(System.currentTimeMillis()));
			JSONObject terrainTypes = new JSONObject();
			for(java.util.Map.Entry<Integer, String> e : glob.map.tileTypeNames().entrySet())
			    terrainTypes.put(String.valueOf(e.getKey()), e.getValue());
			multipart.addFormField("terrainTypes", terrainTypes.toString());
			MultipartUtility.Response response = multipart.finish();
			if(response.statusCode < 200 || response.statusCode >= 300)
			    warn(String.format("Grid image upload rejected by the server (HTTP %d) for grid %s.", response.statusCode, gridID));
		    } catch (IOException ex) {
			warn("Grid image upload failed: " + ex.getMessage());
		    }
		}
	    } catch (Loading ex) {
		// The grid is not renderable yet. The old behaviour was to resubmit this task to the
		// single-threaded uploader immediately, with no cap and no pause: a grid that never
		// becomes renderable (weak ref collected, grid dropped from the cache while walking
		// on) re-enqueued itself in a tight loop forever, spinning the one thread that every
		// other grid upload shares. Retry on the scheduler instead, with backoff, and give up
		// after a handful of tries.
		if(loadingRetries++ >= GRID_LOADING_MAX_RETRIES)
		    return;
		long delay = GRID_LOADING_RETRY_BACKOFF_MS * (1L << loadingRetries);
		submit(this, delay, TimeUnit.MILLISECONDS);
	    }
	    
	}
    }
    
    private static Coord toGC(Coord2d c) {
	return new Coord(Math.floorDiv((int) c.x, GRID_SIZE), Math.floorDiv((int) c.y, GRID_SIZE));
    }
    
    private static Coord toGridUnit(Coord2d c) {
	return new Coord(Math.floorDiv((int) c.x, GRID_SIZE) * GRID_SIZE, Math.floorDiv((int) c.y, GRID_SIZE) * GRID_SIZE);
    }
    
    private static Coord2d gridOffset(Coord2d c) {
	Coord gridUnit = toGridUnit(c);
	return new Coord2d(c.x - gridUnit.x, c.y - gridUnit.y);
    }

	public void uploadSMarker(Gob gob, MapFile.SMarker marker) {
		try {
			MCache.Grid grid = glob.map.getgrid(toGridCoordinate(gob.rc));
			Coord offset = gridOffset2(gob.rc);

			JSONObject obj = new JSONObject();
			obj.put("name", marker.nm);
			obj.put("gridID", String.valueOf(grid.id));
			obj.put("x", offset.x);
			obj.put("y", offset.y);
			obj.put("type", "shared");
			// Quoted for the same reason as in ProcessMap above: a bare UID is not JSON.
			obj.put("id", String.valueOf(marker.oid));
			obj.put("image", marker.res.name);

			// .put(obj), not new JSONArray(List.of(obj)): the bundled org.json only has a
			// JSONArray(Collection<Object>) ctor, which List<JSONObject> does not match (invariant
			// generics), so that form binds to JSONArray(Object) and throws at runtime - the same
			// trap that kept LpLog from ever writing. See LpLog.writeTo.
			submit(new MarkerUpdate(new JSONArray().put(obj)));
		} catch (Loading ignored) {
		}
	}

	/** the grid coordinate of a map grid, used for retrieving map grids in mcache
	 * example: "glob.map.getgrid(toGridCoordinate(gob.rc)).id" will get the grid id for the coordinate the gob is on **/
	public static Coord toGridCoordinate(Coord2d c) {
		return new Coord(Math.floorDiv((int) c.x, GRID_SIZE), Math.floorDiv((int) c.y, GRID_SIZE));
	}

	/** a coordinate (0-TILES_PER_GRID,0-TILES_PER_GRID) within a TILES_PER_GRIDxTILES_PER_GRID map grid **/
	public static Coord gridOffset2(Coord2d c) {
		Coord gridUnit = toGridUnit(c);
		return new Coord((int) ((c.x - gridUnit.x)/TILE_SIZE), (int) ((c.y - gridUnit.y)/TILE_SIZE));
	}

}