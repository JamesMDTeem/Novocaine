package haven.automated.lp;

import haven.Gob;
import haven.ResDrawable;
import haven.Resource;
import haven.Sprite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Living trees: seed/leaf/bough/bark, each independently toggleable, read from the tree's live
 * per-instance state bitmask (see HarvestState) rather than guessed from species/season. Bough
 * and bark aren't part of that bitmask - bough is a fixed per-species trait (HarvestState.hasBough),
 * bark is assumed always available on a mature tree (its own item disappears once fully harvested,
 * same as the tree itself would stop being "mature" - not modeled further here).
 */
public class TreeHarvestSpec implements HarvestSpec {
    @Override
    public boolean matches(String gobResName) {
        return HarvestState.isTreeOrBushRes(gobResName) && gobResName.startsWith("gfx/terobjs/trees");
    }

    @Override
    public boolean horizontal() {
        return false;
    }

    @Override
    public LpConfig.Key masterToggle() {
        return LpConfig.Key.treeHarvestOverlay;
    }

    @Override
    public List<Part> parts(Gob gob, ResDrawable d) {
        if (!HarvestState.isMatureTreeOrBush(gob, d))
            return Collections.emptyList();

        Resource res = d.getres();
        if (!LpSpec.hasObject(res.name))
            // A species we have no data for at all (e.g. one the game added after the data was
            // last regenerated) - flag it rather than silently showing nothing. Not every KNOWN
            // species tracks every category (most have no distinct leaf product, for instance),
            // so that's judged here at the species level, not per-category below.
            return Collections.singletonList(new Part("unknown", HarvestState.unknownIcon(), true));

        int sdt = Sprite.decnum(d.sdt.clone());
        String base = res.basename();

        boolean showSeeds = LpConfig.on(LpConfig.Key.treeHarvestSeeds);
        boolean showLeaves = LpConfig.on(LpConfig.Key.treeHarvestLeaves);
        boolean showBoughs = LpConfig.on(LpConfig.Key.treeHarvestBoughs);
        boolean showBark = LpConfig.on(LpConfig.Key.treeHarvestBark);
        boolean showWood = LpConfig.on(LpConfig.Key.treeHarvestWood);

        boolean seed = showSeeds && HarvestState.hasSeedBit(sdt);
        boolean leaf = showLeaves && HarvestState.hasLeafBit(sdt);
        boolean bough = showBoughs && HarvestState.hasBough(base);
        boolean bark = showBark;

        boolean lpassistentOn = LpExplorer.isEnabled();
        LpExplorer.UndiscoveredCategories undiscovered = lpassistentOn
            ? LpExplorer.undiscoveredCategories(res.name) : null;
        boolean seedUndiscovered = seed && undiscovered != null && undiscovered.seed;
        boolean leafUndiscovered = leaf && undiscovered != null && undiscovered.leaf;
        boolean boughUndiscovered = bough && undiscovered != null && undiscovered.bough;
        boolean barkUndiscovered = bark && lpassistentOn && LpExplorer.hasUndiscoveredBarkProduct(res.name);

        // Unlike the four above, this isn't something you can take off a standing tree at all -
        // it's an axe standing in for "fell this and you'd reach a Board/Block you don't have
        // yet" (see LpExplorer.derivedResource). So it's purely an LP hint: shown only while
        // undiscovered, and it goes away for good once those products are found, rather than
        // persisting as a harvest indicator the way bark or a bough does.
        boolean wood = showWood && lpassistentOn && LpExplorer.hasUndiscoveredDerivedProduct(res.name);

        List<Part> parts = new ArrayList<>(5);
        HarvestSpec.addPart(parts, "leaf", leaf, HarvestState.getIcon(res, "leaf"), leafUndiscovered);
        HarvestSpec.addPart(parts, "seed", seed, HarvestState.getIcon(res, "seed"), seedUndiscovered);
        HarvestSpec.addPart(parts, "bough", bough, HarvestState.getIcon(res, "bough"), boughUndiscovered);
        HarvestSpec.addPart(parts, "bark", bark, HarvestState.getIcon(res, "bark"), barkUndiscovered);
        HarvestSpec.addPart(parts, "wood", wood, HarvestState.chopIcon(), true);
        // In addition to the axe, show the actual Board/Block icons the felled log would yield, so
        // the standing tree advertises WHAT chopping it would get you, not just that it's worth it.
        // The tree tracks none of these itself - they belong to the derived LOG resource (see
        // LpExplorer.derivedResource) - so resolve each undiscovered one by product name.
        if (wood) {
            String derived = LpExplorer.derivedResource(res.name);
            List<String> derivedProducts = (derived != null) ? LpSpec.getObject(derived) : null;
            if (derivedProducts != null) {
                for (String product : derivedProducts) {
                    if (LpExplorer.isProductUndiscovered(derived, product)) {
                        java.awt.image.BufferedImage icon = HarvestState.loadIcon(LpSpec.getIconPath(product));
                        HarvestSpec.addPart(parts, "wood:" + product, icon != null, icon, true);
                    }
                }
            }
        }
        return parts;
    }
}
