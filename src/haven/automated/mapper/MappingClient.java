package haven.automated.mapper;

import haven.BuddyWnd;
import haven.Coord;
import haven.Coord2d;
import haven.Glob;
import haven.Gob;
import haven.Indir;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.OptWnd;
import haven.MCache.LoadingMap;
import haven.res.ui.obj.buddy.Buddy;
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

    /** Submission infrastructure for live player positions and grid updates. */
    private ExecutorService gridsUploader = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    
    private static volatile MappingClient INSTANCE = null;
    
    private int spamPreventionVal = 3;
    private int spamCount = 0;
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
	        INSTANCE.gridsUploader.shutdown();
	        INSTANCE.scheduler.shutdown();
		INSTANCE = null;
	    }
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
	scheduler.execute(new GenerateGridUpdateTask(gc));
    }

    public void CheckGridCoord(Coord2d c) {
	Coord gc = toGC(c);
	if(lastGC == null || !gc.equals(lastGC)) {
	    EnterGrid(gc);
	}
    }
    
    private final Map<Long, MapRef> cache = new ConcurrentHashMap<Long, MapRef>();

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
			    o.put("id", ((MapFile.SMarker) md.m).oid);
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
	    try {
		scheduler.execute(new MarkerUpdate(new JSONArray(loadedMarkers.toArray())));
	    } catch (Exception ex) {
	    }
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
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
		connection.setDoOutput(true);
		try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
		    final String json = data.toString();
		    out.write(json.getBytes(StandardCharsets.UTF_8));
		}
		int code = connection.getResponseCode();
	    } catch (Exception ex) {
		System.out.println(ex);
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
	    if(t == null) {
		t = new Tracking();
		tracking.put(id, t);
		
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
	}
	
	@Override
	public void run() {
	    if(spamCount == spamPreventionVal) {
		spamCount = 0;
		if(OptWnd.sendLiveLocationCheckBox.a) {
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
		    
		    try {
			final HttpURLConnection connection =
			    (HttpURLConnection) new URL(OptWnd.webmapEndpointTextEntry.buf.line() + "/positionUpdate").openConnection();
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
			connection.setDoOutput(true);
			try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
			    final String json = upload.toString();
			    out.write(json.getBytes(StandardCharsets.UTF_8));
			} catch (Exception e) {
			}
			connection.getResponseCode();
			connection.disconnect();
		    } catch (final Exception ex) {
		    }
		}
	    } else {
		spamCount++;
	    }
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
		    scheduler.execute(new UploadGridUpdateTask(new GridUpdate(gridMap, gridRefs)));
		} catch (LoadingMap lm) {
		    retries--;
		    if(retries >= 0) {
			scheduler.schedule(this, 1L, TimeUnit.SECONDS);
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
		    connection.setRequestMethod("POST");
		    connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
		    connection.setDoOutput(true);
		    try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
			String json = new JSONObject(dataToSend).toString();
			out.write(json.getBytes(StandardCharsets.UTF_8));
		    }
		    if(connection.getResponseCode() == 200) {
			DataInputStream dio = new DataInputStream(connection.getInputStream());
			int nRead;
			byte[] data = new byte[1024];
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			while ((nRead = dio.read(data, 0, data.length)) != -1) {
			    buffer.write(data, 0, nRead);
			}
			buffer.flush();
			String response = buffer.toString(StandardCharsets.UTF_8.name());
			JSONObject jo = new JSONObject(response);
			JSONArray reqs = jo.optJSONArray("gridRequests");
			/* cache is a ConcurrentHashMap - the put is atomic, no external lock needed. */
			cache.put(Long.valueOf(gridUpdate.grids[1][1]), new MapRef(jo.getLong("map"), new Coord(jo.getJSONObject("coords").getInt("x"), jo.getJSONObject("coords").getInt("y"))));
			for (int i = 0; reqs != null && i < reqs.length(); i++) {
			    gridsUploader.execute(new GridUploadTask(reqs.getString(i), gridUpdate.gridRefs.get(reqs.getString(i))));
			}
		    }
		} catch (Exception ex) {
		    System.out.println("Grid upload failed: " + ex.getMessage());
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
	
	GridUploadTask(String gridID, WeakReference<MCache.Grid> grid) {
	    this.gridID = gridID;
	    this.grid = grid;
	}
	
	@Override
	public void run() {
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
			MultipartUtility.Response response = multipart.finish();
		    } catch (IOException ex) {
			System.out.println("Grid image upload failed: " + ex.getMessage());
		    }
		}
	    } catch (Loading ex) {
		gridsUploader.submit(this);
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
    
    /** Reference to a map space (mapID + grid coordinate) returned by the server. */
    public class MapRef {
	public Coord gc;
	public long mapID;
	
	private MapRef(long mapID, Coord gc) {
	    this.gc = gc;
	    this.mapID = mapID;
	}
	
	public String toString() {
	    return (gc.toString() + " in map space " + mapID);
	}
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
			obj.put("id", marker.oid);
			obj.put("image", marker.res.name);

			scheduler.execute(new MarkerUpdate(new JSONArray(List.of(obj))));
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