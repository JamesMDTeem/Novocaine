package haven.automated.lp;

import haven.ColorMask;
import haven.Coord;
import haven.GOut;
import haven.Gob;
import haven.Loading;
import haven.MiniMap;
import haven.OCache;
import haven.TexI;
import haven.UI;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Draws the undiscovered product's own icon (green-tinted) on the minimap over any currently
 * loaded gob that still has an undiscovered LP product, falling back to a flat dot when no icon
 * is resolvable (e.g. ground herbs/mushrooms). Mirrors the 3D-world GobLpDiscoveryInfo overlay
 * and shares the same LpConfig.lpassistent toggle. Right-click hit-testing is via gobAt(), used
 * from MiniMap.mouseup() so a marker opens the same flower menu a real gob icon would.
 *
 * Ported from nurgling2's renderer onto Hurricane's MiniMap (p2c(Coord2d) for the world->widget
 * projection). Kept in haven.automated.lp so it depends on the LP data, not the reverse.
 */
public class MinimapDiscoveryRenderer {
    private static final java.awt.Color FALLBACK_TINT = new java.awt.Color(60, 255, 0, 255);
    private static final int FALLBACK_RADIUS_PX = 5;
    // Hoisted out of the per-gob draw loop below - the tint never varies, so building one per
    // marker per frame was pure allocation.
    private static final ColorMask MARKER_TINT = new ColorMask(LpIcons.LP_UNDISCOVERED_TINT);

    public static void renderDiscoveryMarkers(MiniMap map, GOut g) {
        Coord fallbackHalf = new Coord(UI.scale(FALLBACK_RADIUS_PX), UI.scale(FALLBACK_RADIUS_PX));
        forEachDiscoverableGob(map, (gob, product) -> {
            TexI icon = LpExplorer.getMarkerIcon(gob, product);
            Coord screenPos = map.p2c(gob.rc);
            if (screenPos == null)
                return false;
            Coord half = icon != null ? icon.sz().div(2) : fallbackHalf;
            if (screenPos.x < -half.x || screenPos.x > map.sz.x + half.x ||
                screenPos.y < -half.y || screenPos.y > map.sz.y + half.y)
                return false;

            if (icon != null) {
                g.usestate(MARKER_TINT);
                g.image(icon, screenPos.sub(half));
                g.defstate();
            } else {
                g.chcolor(FALLBACK_TINT);
                g.fellipse(screenPos, half);
                g.chcolor();
            }
            return false; // never stop early; draw every marker
        });
    }

    /** Finds the discoverable gob (if any) whose marker is under the given minimap screen coordinate. */
    public static Gob gobAt(MiniMap map, Coord screenCoord) {
        Gob[] hit = new Gob[1];
        forEachDiscoverableGob(map, (gob, product) -> {
            TexI icon = LpExplorer.getMarkerIcon(gob, product);
            int threshold = icon != null
                ? Math.max(icon.sz().x, icon.sz().y) / 2 + UI.scale(3)
                : UI.scale(FALLBACK_RADIUS_PX + 3);
            Coord sc = map.p2c(gob.rc);
            if (sc == null || sc.dist(screenCoord) >= threshold)
                return false;
            hit[0] = gob;
            return true; // stop at first match
        });
        return hit[0];
    }

    /**
     * Visits every loaded gob with an undiscovered LP product, along with which product it is
     * (resolved once here rather than separately by every caller). The visitor returns true to
     * stop early.
     */
    private static void forEachDiscoverableGob(MiniMap map, BiPredicate<Gob, String> visitor) {
        if (!LpExplorer.isEnabled())
            return;
        if (map.ui == null || map.ui.sess == null)
            return;

        // Snapshot the gob list under the OCache lock, then run the discovery scan, icon
        // resolution and drawing outside it. Those steps issue GL calls and can block
        // (HarvestState.loadIcon bottoms out in Resource.loadwait), and this runs every frame -
        // holding the monitor the network thread needs for gob updates across all of that stalls
        // both sides.
        List<Gob> snapshot = new ArrayList<>();
        OCache oc = map.ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc)
                snapshot.add(gob);
        }

        for (Gob gob : snapshot) {
            try {
                String product = LpExplorer.firstUndiscoveredProduct(gob);
                if (product != null && visitor.test(gob, product))
                    return;
            } catch (Loading l) {
                // Position or sprite not ready yet this frame, skip.
            }
        }
    }
}
