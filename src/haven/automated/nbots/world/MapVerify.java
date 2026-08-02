package haven.automated.nbots.world;

import haven.Coord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Diagnostic tool that checks the persistent tile record (botmap.json) for gaps in barrier
 * recordings and reports statistics.
 *
 * The user's base has continuous palisade walls with no gaps except through gates. If the
 * recorded map shows gaps — OPEN or UNSEEN tiles sandwiched between WALL tiles — something is
 * wrong with the recording or update logic. This tool finds those gaps and reports where they
 * are.
 *
 * A gap is defined as an OPEN tile with WALL (or SOLID) on opposite sides (N/S or E/W).
 * Isolated walls — single WALL tiles with no WALL/GATE neighbour — are also reported.
 */
public class MapVerify {

    /** All 8 directions for neighbour checking. */
    private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DY = {-1, -1, -1, 0, 0, 1, 1, 1};

    /** Opposite direction pairs: N/S, E/W, NW/SE, NE/SW. */
    private static final int[][] OPPOSITES = {{0, -1, 0, 1}, {-1, 0, 1, 0}, {-1, -1, 1, 1}, {-1, 1, 1, -1}};

    /** A finding from the verification scan. */
    public static class Finding {
        public final String level;  // "GAP", "ISOLATED", "SUSPECT"
        public final long seg;
        public final Coord tile;
        public final String message;

        public Finding(String level, long seg, Coord tile, String message) {
            this.level = level;
            this.seg = seg;
            this.tile = tile;
            this.message = message;
        }

        @Override
        public String toString() {
            return String.format("[%s] seg=%d tile=(%d,%d): %s", level, seg, tile.x, tile.y, message);
        }
    }

    /** Per-segment statistics. */
    public static class SegStats {
        public final long seg;
        public int wall, gate, solid, open, unseen;

        SegStats(long seg) {
            this.seg = seg;
        }
    }

    /**
     * Run the full verification scan and return a human-readable report.
     *
     * Uses only public Observed API — iterates known tiles via barriersIn/gatesIn and checks
     * tile states with Observed.at().
     */
    public static String verify() {
        List<Finding> findings = new ArrayList<>();
        List<SegStats> stats = new ArrayList<>();
        StringBuilder report = new StringBuilder();

        report.append("=== MapVerify Diagnostic ===\n\n");

        // Discover segments by checking what Observed knows about
        Set<Long> segments = Observed.allSegments();

        if (segments.isEmpty()) {
            report.append("No map data loaded (botmap.json is empty or missing).\n");
            report.append("Nothing to verify — explore the world first to build a map.\n");
            return report.toString();
        }

        report.append("Segments found: ").append(segments.size()).append("\n\n");

        for (long seg : segments) {
            SegStats ss = new SegStats(seg);

            // Count tiles by state - iterate a reasonable range around recorded tiles
            // Gates and barriers give us anchor points
            Set<Coord> checkedTiles = new HashSet<>();

            // Check tiles around gates
            for (Coord gateTile : Observed.gatesIn(seg)) {
                checkSurroundingTiles(findings, seg, gateTile, checkedTiles, ss);
            }

            // Check tiles around barriers (walls)
            for (Coord wallTile : Observed.barriersIn(seg)) {
                checkSurroundingTiles(findings, seg, wallTile, checkedTiles, ss);
            }

            // Also check the gate tiles themselves for classification consistency
            for (Coord gateTile : Observed.gatesIn(seg)) {
                byte state = Observed.at(seg, gateTile);
                if (state != Observed.GATE) {
                    findings.add(new Finding("SUSPECT", seg, gateTile,
                        "gate tile in gatesIn() but Observed state is " + stateName(state)));
                }
                ss.gate++;
            }

            stats.add(ss);
        }

        // Print per-segment stats
        for (SegStats ss : stats) {
            report.append(String.format(
                "Segment %d: walls=%d gates=%d solids=%d open=%d unseen=%d\n",
                ss.seg, ss.wall, ss.gate, ss.solid, ss.open, ss.unseen));
        }
        report.append('\n');

        // Print findings
        if (findings.isEmpty()) {
            report.append("No suspicious patterns found.\n");
        } else {
            int gaps = 0, isolated = 0, suspect = 0;
            for (Finding f : findings) {
                switch (f.level) {
                    case "GAP":      gaps++;      break;
                    case "ISOLATED": isolated++;  break;
                    case "SUSPECT":  suspect++;   break;
                }
            }
            report.append(String.format(
                "Findings: %d gap(s), %d isolated wall(s), %d suspect(s)\n\n",
                gaps, isolated, suspect));

            long lastSeg = -1;
            for (Finding f : findings) {
                if (f.seg != lastSeg) {
                    report.append(String.format("--- Segment %d ---\n", f.seg));
                    lastSeg = f.seg;
                }
                report.append("  ").append(f).append('\n');
            }
        }

        report.append("\n=== End MapVerify ===\n");
        return report.toString();
    }

    /**
     * Check tiles surrounding an anchor tile for gap/isolation patterns.
     */
    private static void checkSurroundingTiles(List<Finding> findings, long seg, Coord anchor,
                                               Set<Coord> checked, SegStats ss) {
        // Check the anchor and its neighbours
        Set<Coord> toCheck = new HashSet<>();
        toCheck.add(anchor);
        for (int d = 0; d < 8; d++) {
            toCheck.add(new Coord(anchor.x + DX[d], anchor.y + DY[d]));
        }

        for (Coord tile : toCheck) {
            if (checked.contains(tile))
                continue;
            checked.add(tile);

            byte state = Observed.at(seg, tile);
            if (state == Observed.UNSEEN) {
                ss.unseen++;
                continue;
            }

            // Count by type
            switch (state) {
                case Observed.OPEN:   ss.open++;   break;
                case Observed.SOLID:  ss.solid++;  break;
                case Observed.GATE:   ss.gate++;   break;
                case Observed.WALL:   ss.wall++;   break;
            }

            // Only check WALL and OPEN tiles for patterns
            if (state == Observed.WALL) {
                checkWallTile(findings, seg, tile, checked);
            } else if (state == Observed.OPEN) {
                checkOpenTile(findings, seg, tile, checked);
            }
        }
    }

    /**
     * Check a WALL tile for isolation (no wall/gate neighbours).
     */
    private static void checkWallTile(List<Finding> findings, long seg, Coord tile,
                                       Set<Coord> checked) {
        int wallNeighbours = 0;

        for (int d = 0; d < 8; d++) {
            Coord nb = new Coord(tile.x + DX[d], tile.y + DY[d]);
            byte state = Observed.at(seg, nb);

            if (state == Observed.WALL || state == Observed.GATE) {
                wallNeighbours++;
            }
        }

        if (wallNeighbours == 0) {
            findings.add(new Finding("ISOLATED", seg, tile,
                "WALL tile with no adjacent WALL/GATE neighbours — may be mis-recorded"));
        }
    }

    /**
     * Check an OPEN tile for being a gap (WALL on opposite sides).
     */
    private static void checkOpenTile(List<Finding> findings, long seg, Coord tile,
                                       Set<Coord> checked) {
        // Check each opposite pair for WALL on both sides
        for (int o = 0; o < OPPOSITES.length; o++) {
            int dx1 = OPPOSITES[o][0], dy1 = OPPOSITES[o][1];
            int dx2 = OPPOSITES[o][2], dy2 = OPPOSITES[o][3];

            Coord north = new Coord(tile.x + dx1, tile.y + dy1);
            Coord south = new Coord(tile.x + dx2, tile.y + dy2);

            byte northState = Observed.at(seg, north);
            byte southState = Observed.at(seg, south);

            boolean northWall = (northState == Observed.WALL || northState == Observed.SOLID);
            boolean southWall = (southState == Observed.WALL || southState == Observed.SOLID);

            if (northWall && southWall) {
                findings.add(new Finding("GAP", seg, tile,
                    "OPEN tile between WALL/SOLID tiles — possible gap in barrier"));
                return; // Only report once per tile
            }
        }
    }

    private static String stateName(byte state) {
        switch (state) {
            case Observed.UNSEEN: return "UNSEEN";
            case Observed.OPEN:   return "OPEN";
            case Observed.SOLID:  return "SOLID";
            case Observed.GATE:   return "GATE";
            case Observed.WALL:   return "WALL";
            default:              return "UNKNOWN(" + state + ")";
        }
    }
}
