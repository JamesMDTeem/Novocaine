package haven.automated.pathfinder;

import haven.Coord;

import java.util.HashMap;

/**
 * Represents a rectangular obstacle that can be traversed around.
 * Holds corner waypoints, clearance vertices, and a raster scan of occupied cells
 * for visibility testing during pathfinding.
 */
public class TraversableObstacle {
    public Coord wa, wb, wc, wd;
    public Coord clra, clrb, clrc, clrd;
    public HashMap<Integer, Utils.MinMax> raster;

    public TraversableObstacle(Coord wa, Coord wb, Coord wc, Coord wd,
                               Coord clra, Coord clrb, Coord clrc, Coord clrd, HashMap<Integer, Utils.MinMax> raster) {
        this.wa = wa;
        this.wb = wb;
        this.wc = wc;
        this.wd = wd;
        this.clra = clra;
        this.clrb = clrb;
        this.clrc = clrc;
        this.clrd = clrd;
        this.raster = raster;
    }
}