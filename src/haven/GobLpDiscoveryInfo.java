package haven;

import haven.automated.lp.HarvestSpec;
import haven.automated.lp.HarvestSpecs;
import haven.automated.lp.HarvestState;
import haven.automated.lp.LpConfig;
import haven.automated.lp.LpExplorer;
import haven.automated.lp.LpIcons;

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
 * tex. A discovery re-renders immediately (LpExplorer.generation() changes the instant one is
 * recorded, so a picked item's icon clears at once); season and the live bitmask fall back to a
 * short timer, since neither warrants per-frame recomputation.
 */
public class GobLpDiscoveryInfo extends GobInfo {
    private static final double REFRESH_SECONDS = 1.0;
    private double sinceRefresh = 0;
    private int lastGen = -1;

    protected GobLpDiscoveryInfo(Gob owner) {
        super(owner);
        up(4);
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
        }
        super.ctick(dt);
    }

    @Override
    protected Tex render() {
        try {
            String name = LpExplorer.resname(gob);
            if (name == null)
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
