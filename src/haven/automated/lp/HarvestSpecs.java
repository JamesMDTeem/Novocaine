package haven.automated.lp;

import java.util.Arrays;
import java.util.List;

/** The gob types the always-visible harvest overlay currently covers. */
public class HarvestSpecs {
    public static final HarvestSpec TREE = new TreeHarvestSpec();
    public static final HarvestSpec BUSH = new BushHarvestSpec();
    public static final HarvestSpec LOG = new ProductListHarvestSpec(
        name -> name.startsWith("gfx/terobjs/trees") && name.endsWith("log"),
        LpConfig.Key.logHarvestOverlay, true);
    public static final HarvestSpec STONE = new ProductListHarvestSpec(
        name -> name.startsWith("gfx/terobjs/bumlings"),
        LpConfig.Key.stoneHarvestOverlay, false);
    public static final HarvestSpec OLDTRUNK = new ProductListHarvestSpec(
        name -> name.equals("gfx/terobjs/trees/oldtrunk"),
        LpConfig.Key.oldtrunkHarvestOverlay, false);

    private static final List<HarvestSpec> ALL = Arrays.asList(TREE, BUSH, LOG, STONE, OLDTRUNK);

    private HarvestSpecs() {}

    /** The spec that applies to this gob resource, or null if none of the five cover it. */
    public static HarvestSpec forResource(String gobResName) {
        for (HarvestSpec spec : ALL) {
            if (spec.matches(gobResName))
                return spec;
        }
        return null;
    }
}
