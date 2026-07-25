package haven;

import haven.automated.lp.HarvestSpec;
import haven.automated.lp.HarvestSpecs;
import haven.automated.lp.HarvestState;
import haven.automated.lp.LpConfig;
import haven.automated.lp.LpExplorer;
import haven.automated.lp.LpIcons;
import haven.automated.lp.LpTargets;

import java.util.List;

/**
 * Floating icon(s) over any gob that still has LP-undiscovered products - the port of
 * nurgling2's NObjHarvestOl + NLPassistant pair, folded into one class on Hurricane's own
 * GobInfo pattern (same mechanism as GobBeeskepHarvestInfo / GobReadyForHarvestInfo, which is
 * fitting: nurgling's overlay credits GobReadyForHarvestInfo as its inspiration).
 *
 * Two display modes, resolved per gob type:
 *  - If the gob's type has its always-on harvest overlay enabled (see HarvestSpecs), the full
 *    harvest label shows (every current harvest icon, undiscovered ones tinted green).
 *  - Otherwise the LP marker fallback shows: only the still-undiscovered products' icons,
 *    all tinted - and nothing at all once everything is found.
 *
 * Discovery state, season, and the live seed/leaf bitmask all change out from under the cached
 * tex. Re-render is driven by LpExplorer.generation() - bumped the instant a discovery is
 * recorded, a config toggle happens, or the season flips - so those show up immediately. The
 * only thing generation doesn't cover is a single tree quietly growing/losing its fruit layer
 * (the live sdt bitmask), so a slow fallback timer catches that; it's deliberately long AND
 * staggered per gob (by id) so hundreds of markers never all recompute on the same frame, which
 * is what made loading many objects hitch. The composed image itself is cached in LpExplorer, so
 * even a generation bump that clears every marker at once is mostly cache hits, not GPU uploads.
 */
public class GobLpDiscoveryInfo extends GobInfo {
    private static final double REFRESH_SECONDS = 6.0;
    private double sinceRefresh;
    private int lastGen = -1;

    protected GobLpDiscoveryInfo(Gob owner) {
        super(owner);
        up(4);
        // Stagger the first fallback tick across [0, REFRESH_SECONDS) by gob id, so a screen full
        // of markers spreads its fallback recomputes over time instead of bunching on one frame.
        sinceRefresh = -(Math.floorMod(owner.id, 6000) / 1000.0);
    }

    @Override
    protected boolean enabled() {
        return LpConfig.on(LpConfig.Key.lpassistent) && !gob.isHidden;
    }

    @Override
    public void ctick(double dt) {
        sinceRefresh += dt;
        int gen = LpExplorer.generation();
        if (gen != lastGen || sinceRefresh >= REFRESH_SECONDS) {
            lastGen = gen;
            sinceRefresh = 0;
            clear();
            updateRing();
        }
        super.ctick(dt);
    }

    /**
     * Keeps the ground ring on huntable animals in sync. Driven off the same generation/refresh
     * beat as the icon rather than its own poll, so a discovery clears the ring on the next frame
     * and nothing new ticks per-gob.
     *
     * Only hunt-only animals get a ring. A floating icon over a moving animal at distance is easy
     * to miss - which is the whole reason to draw something on the ground - while a ring under every
     * herb and boulder would bury the map in circles.
     */
    private void updateRing() {
        if (!LpTargets.isHuntOnly(LpExplorer.resname(gob)))
            return;
        boolean want = enabled() && LpConfig.on(LpConfig.Key.lpHuntTargets);
        if (want) {
            try {
                want = !LpExplorer.markerProducts(gob).isEmpty();
            } catch (Loading l) {
                return;  // undecided this pass; the refresh timer comes back around
            }
        }
        gob.setLpAura(want);
    }

    @Override
    protected Tex render() {
        try {
            String name = LpExplorer.resname(gob);
            if (name == null)
                return null;
            if (LpTargets.isHuntOnly(name) && !LpConfig.on(LpConfig.Key.lpHuntTargets))
                return null;
            String norm = HarvestState.normalizeBumlingRes(name);
            HarvestSpec spec = HarvestSpecs.forResource(norm);
            if (spec != null && LpConfig.on(spec.masterToggle()))
                return LpIcons.computeLabel(gob, spec);
            List<String> products = LpExplorer.markerProducts(gob);
            if (products.isEmpty())
                return null;
            return LpExplorer.getMarkerIcon(gob, products);
        } catch (Loading l) {
            // Sprite not loaded yet; the refresh timer retries shortly.
            return null;
        }
    }
}
