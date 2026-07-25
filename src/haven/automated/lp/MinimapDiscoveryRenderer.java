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
import java.util.Collections;
import java.util.List;

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
        for (Marker m : markers(map)) {
            TexI icon = LpExplorer.getMarkerIcon(m.gob, m.product);
            Coord screenPos = map.p2c(m.gob.rc);
            if (screenPos == null)
                continue;
            Coord half = icon != null ? icon.sz().div(2) : fallbackHalf;
            if (screenPos.x < -half.x || screenPos.x > map.sz.x + half.x ||
                screenPos.y < -half.y || screenPos.y > map.sz.y + half.y)
                continue;

            if (icon != null) {
                g.usestate(MARKER_TINT);
                g.image(icon, screenPos.sub(half));
                g.defstate();
            } else {
                g.chcolor(FALLBACK_TINT);
                g.fellipse(screenPos, half);
                g.chcolor();
            }
        }
    }

    /** Finds the discoverable gob (if any) whose marker is under the given minimap screen coordinate. */
    public static Gob gobAt(MiniMap map, Coord screenCoord) {
        for (Marker m : markers(map)) {
            TexI icon = LpExplorer.getMarkerIcon(m.gob, m.product);
            int threshold = icon != null
                ? Math.max(icon.sz().x, icon.sz().y) / 2 + UI.scale(3)
                : UI.scale(FALLBACK_RADIUS_PX + 3);
            Coord sc = map.p2c(m.gob.rc);
            if (sc != null && sc.dist(screenCoord) < threshold)
                return m.gob;
        }
        return null;
    }

    /** One gob with an undiscovered product, and which product it is. */
    private static final class Marker {
        final Gob gob;
        final String product;

        Marker(Gob gob, String product) {
            this.gob = gob;
            this.product = product;
        }
    }

    // The scan below walks every loaded gob and, for trees and bushes, decodes each one's live
    // sprite bitmask - so running it per frame meant tens of thousands of discovery lookups and
    // sdt clones per second once a few hundred objects were loaded, and it now runs once per map
    // WINDOW per frame since the standalone big map draws its own minimap. Discovery state changes
    // only when something is picked (which bumps the generation and invalidates this immediately),
    // so rescanning a few times a second is plenty. Gob POSITIONS are still read live at draw time,
    // so markers track moving objects exactly as smoothly as before.
    private static final long RESCAN_NANOS = 250_000_000L;
    private static List<Marker> markers = Collections.emptyList();
    private static long lastScan = 0;
    private static int lastGen = -1;

    private static List<Marker> markers(MiniMap map) {
        if (!LpExplorer.isEnabled() || map.ui == null || map.ui.sess == null)
            return Collections.emptyList();
        long now = System.nanoTime();
        int gen = LpExplorer.generation();
        if ((gen == lastGen) && ((now - lastScan) < RESCAN_NANOS) && (lastScan != 0))
            return markers;

        // Snapshot the gob list under the OCache lock, then run the discovery scan and icon
        // resolution outside it. Those steps can block (HarvestState.loadIcon bottoms out in
        // Resource.loadwait), and holding the monitor the network thread needs for gob updates
        // across all of that stalls both sides.
        List<Gob> snapshot = new ArrayList<>();
        OCache oc = map.ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc)
                snapshot.add(gob);
        }

        List<Marker> found = new ArrayList<>();
        for (Gob gob : snapshot) {
            try {
                String product = LpExplorer.firstUndiscoveredProduct(gob);
                if (product != null)
                    found.add(new Marker(gob, product));
            } catch (Loading l) {
                // Position or sprite not ready yet; picked up by the next rescan.
            }
        }
        markers = found;
        lastGen = gen;
        lastScan = now;
        return found;
    }
}
