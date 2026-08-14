package haven.automated.helpers;

import haven.Coord2d;

/**
 * Exact convex-polygon geometry for the pathfinder's collision model.
 *
 * The pathfinder raster historically collapsed every gob collision box to an
 * axis-aligned bounding box and painted that with a Bresenham frame + row fill
 * ({@code Map.addGobToList}/{@code Utils.plotRect}), which over-covers and
 * self-intersects for rotated boxes — closing tight gaps the character's disc
 * can actually pass. This service carries the real polygon (already stored,
 * unrotated, in the gob's local frame by {@link HitBoxes#collisionBoxMap}) and
 * answers point/disc/segment queries against it after an exact rotation around
 * the gob origin, so the raster can sample the disc at pixel resolution instead
 * of inflating a lossy AABB.
 *
 * All queries are pure and allocation-light on the hot path; the polygon is
 * rotated once per gob by the caller and reused across the sampled cells.
 */
public final class CollisionGeom {
    private CollisionGeom() {}

    /**
     * Rotate a polygon given in the gob's local frame (the same frame as
     * {@code HitBoxes.CollisionBoxSecondary.coords}) into world coordinates.
     * Rotation is exact double math around the gob origin, matching the
     * direction {@code Map.addGobToList} used ({@code +gob.a}, not the
     * un-rotation that {@code Pathfinder.isInsideBoundBox} applies to its query
     * point).
     */
    public static Coord2d[] worldPolygon(Coord2d[] local, Coord2d origin, double a) {
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        Coord2d[] out = new Coord2d[local.length];
        for (int i = 0; i < local.length; i++) {
            double x = local[i].x;
            double y = local[i].y;
            out[i] = new Coord2d(x * cos - y * sin + origin.x, x * sin + y * cos + origin.y);
        }
        return out;
    }

    /**
     * A rectangle (two opposite corners in the gob's local frame) as a four-
     * corner polygon, for the gate/pow special cases that used to pass a bare
     * top-left/bottom-right pair.
     */
    public static Coord2d[] rect(double minX, double minY, double maxX, double maxY) {
        return new Coord2d[]{
                new Coord2d(minX, minY), new Coord2d(maxX, minY),
                new Coord2d(maxX, maxY), new Coord2d(minX, maxY)
        };
    }

    /** Rotate a single point by {@code a} radians around the origin. */
    public static Coord2d rotate(Coord2d p, double a) {
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        return new Coord2d(p.x * cos - p.y * sin, p.x * sin + p.y * cos);
    }

    /** True if the point is inside a convex polygon (any winding). */
    public static boolean pointInConvex(Coord2d[] poly, double px, double py) {
        boolean neg = false;
        boolean pos = false;
        for (int i = 0; i < poly.length; i++) {
            Coord2d a = poly[i];
            Coord2d b = poly[(i + 1) % poly.length];
            double cross = (b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x);
            if (cross < 0) neg = true;
            else if (cross > 0) pos = true;
            if (neg && pos)
                return false;
        }
        return true;
    }

    /** True if the disc centred at (px,py) with radius r intersects the polygon. */
    public static boolean discHits(Coord2d[] poly, double px, double py, double r) {
        if (pointInConvex(poly, px, py))
            return true;
        for (int i = 0; i < poly.length; i++) {
            Coord2d a = poly[i];
            Coord2d b = poly[(i + 1) % poly.length];
            if (distPointSegment(px, py, a.x, a.y, b.x, b.y) <= r)
                return true;
        }
        return false;
    }

    /**
     * The corner points of {@code poly} offset outward by {@code dist} — the
     * Minkowski-sum corners a disc of radius {@code dist} would trace around the
     * polygon. Replaces the old "axis-aligned bounding box plus a per-axis inset"
     * waypoint/clearance corner: for a rectangle this lands each corner on its
     * angular bisector at exactly the same spot the old {@code tl +/- way} math
     * produced, but for a ROTATED polygon it hugs the true corner instead of a
     * bloated AABB, so routing vertices sit against the real obstacle and a tight
     * gap between two rotated logs keeps a waypoint on either side of it.
     *
     * Works for any winding; collinear (straight-through) vertices collapse onto
     * the offset of their neighbours' combined normal rather than a singular corner.
     */
    public static Coord2d[] offsetVertices(Coord2d[] poly, double dist) {
        int n = poly.length;
        double area = 0;
        for (int i = 0; i < n; i++) {
            Coord2d a = poly[i];
            Coord2d b = poly[(i + 1) % n];
            area += a.x * b.y - b.x * a.y;
        }
        double s = (area >= 0) ? 1.0 : -1.0;
        Coord2d[] out = new Coord2d[n];
        for (int i = 0; i < n; i++) {
            Coord2d prev = poly[(i + n - 1) % n];
            Coord2d cur = poly[i];
            Coord2d next = poly[(i + 1) % n];
            double n1x = 0, n1y = 0, n2x = 0, n2y = 0;
            {
                double ex = cur.x - prev.x, ey = cur.y - prev.y;
                double len = Math.hypot(ex, ey);
                if (len > 1e-9) { n1x = s * ey / len; n1y = -s * ex / len; }
            }
            {
                double ex = next.x - cur.x, ey = next.y - cur.y;
                double len = Math.hypot(ex, ey);
                if (len > 1e-9) { n2x = s * ey / len; n2y = -s * ex / len; }
            }
            double dot = n1x * n2x + n1y * n2y;
            double denom = 1.0 + dot;
            if (denom < 1e-9)
                denom = 1e-9;
            double k = dist / denom;
            out[i] = new Coord2d(cur.x + (n1x + n2x) * k, cur.y + (n1y + n2y) * k);
        }
        return out;
    }

    /** Distance from a point to a line segment. */
    public static double distPointSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = (len2 == 0) ? 0.0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        double ex = ax + t * dx - px;
        double ey = ay + t * dy - py;
        return Math.sqrt(ex * ex + ey * ey);
    }

    /** The point on the polygon's boundary closest to p (null for an empty polygon). */
    public static Coord2d nearestBoundaryPoint(Coord2d[] poly, Coord2d p) {
        Coord2d best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < poly.length; i++) {
            Coord2d a = poly[i];
            Coord2d b = poly[(i + 1) % poly.length];
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double len2 = dx * dx + dy * dy;
            double t = (len2 == 0) ? 0.0 : ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2;
            if (t < 0) t = 0;
            else if (t > 1) t = 1;
            Coord2d proj = new Coord2d(a.x + t * dx, a.y + t * dy);
            double d = proj.dist(p);
            if (d < bestD) {
                bestD = d;
                best = proj;
            }
        }
        return best;
    }

    /** True if the segment ab intersects the polygon boundary or interior. */
    public static boolean segmentHits(Coord2d[] poly, double ax, double ay, double bx, double by) {
        if (pointInConvex(poly, ax, ay) || pointInConvex(poly, bx, by))
            return true;
        for (int i = 0; i < poly.length; i++) {
            Coord2d c = poly[i];
            Coord2d d = poly[(i + 1) % poly.length];
            if (segmentsIntersect(ax, ay, bx, by, c.x, c.y, d.x, d.y))
                return true;
        }
        return false;
    }

    /** True if the segment ab comes within {@code r} of the polygon (a capsule swept along the
     *  line). This is the continuous-layer version of disc inflation: a character disc of radius
     *  {@code r} travelling from a to b would touch the object. The closest pair of points between
     *  two non-intersecting convex segments is always a vertex, so endpoint-to-segment distances
     *  plus a crossing test is exact. */
    public static boolean segmentHitsRadius(Coord2d[] poly, Coord2d a, Coord2d b, double r) {
        for (int i = 0; i < poly.length; i++) {
            Coord2d c = poly[i];
            Coord2d d = poly[(i + 1) % poly.length];
            if (segmentsIntersect(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y))
                return true;
            if (distPointSegment(c.x, c.y, a.x, a.y, b.x, b.y) <= r
                    || distPointSegment(d.x, d.y, a.x, a.y, b.x, b.y) <= r
                    || distPointSegment(a.x, a.y, c.x, c.y, d.x, d.y) <= r
                    || distPointSegment(b.x, b.y, c.x, c.y, d.x, d.y) <= r)
                return true;
        }
        return false;
    }

    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                             double cx, double cy, double dx, double dy) {
        double d1 = cross(bx - ax, by - ay, cx - ax, cy - ay);
        double d2 = cross(bx - ax, by - ay, dx - ax, dy - ay);
        double d3 = cross(dx - cx, dy - cy, ax - cx, ay - cy);
        double d4 = cross(dx - cx, dy - cy, bx - cx, by - cy);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }
}
