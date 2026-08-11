package haven.automated.lp;

import haven.Drawable;
import haven.GameUI;
import haven.GItem;
import haven.Gob;
import haven.ResDrawable;
import haven.Resource;
import haven.Sprite;
import haven.TexI;
import haven.Widget;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

// Tracks which LP-discoverable products (LpSpec.object) the player has found for each gob type.
// Both the normal and "Yesteryear's " variant of seasonal fruit are literal, independently-tracked
// entries in LpSpec.object (see HarvestState's season-aware icon resolution for the counterpart
// that decides which of the two is currently displayed) - so recording a discovery is a direct
// name match, no normalization needed. Ported from nurgling2's LpExplorer; the per-character
// store moved from NCharacterInfo to LpLog (via LpContext).
public class LpExplorer {
    public static boolean isEnabled() {
        return LpConfig.on(LpConfig.Key.lpassistent);
    }

    /** The gob's resolved resource name, or null while still loading - the ngob.name stand-in. */
    public static String resname(Gob gob) {
        if (gob == null)
            return null;
        Resource res = gob.getres();
        return res != null ? res.name : null;
    }

    // True if this product is the one that could actually be picked up right now - i.e. it either
    // has no seasonal counterpart at all, or it's the variant matching the current season. Whether
    // a seasonal pair exists at all is derived from LpSpec.object itself (does the sibling name
    // appear in this same resource's product list) rather than a separately hand-maintained
    // species list, so there's nothing to keep in sync when a new species is added.
    private static boolean isCurrentSeasonProduct(String gobResName, String product) {
        boolean isYesteryearVariant = product.startsWith(HarvestState.YESTERYEAR_PREFIX);
        String base = isYesteryearVariant ? product.substring(HarvestState.YESTERYEAR_PREFIX.length()) : product;
        String sibling = isYesteryearVariant ? base : (HarvestState.YESTERYEAR_PREFIX + base);
        List<String> products = LpSpec.getObject(gobResName);
        if (products == null || !products.contains(sibling))
            return true;
        return isYesteryearVariant == HarvestState.isYesteryearSeason();
    }

    // Resources confirmed to have every product (including bark, for trees) already discovered -
    // checked before any of the per-tick scanning below (LpSpec.object iteration, and for
    // trees/bushes the live bitmask decode too), so a fully-explored species stops paying that
    // cost every tick for every one of its gobs, forever. Deliberately independent of the live
    // per-instance bitmask (whether a product is CURRENTLY visible on a given gob) - discovery
    // status is a per-RESOURCE fact, the same regardless of which instance or moment you look at,
    // so it's what's safe to cache and share across every gob of that resource.
    //
    // Invalidated whole-cache on every season change rather than per-entry, since that's simpler
    // and seasons change only a few times a year: a Yesteryear's-capable resource can be "fully
    // discovered" while its off-season variant is out of season, then stop being once that
    // variant's season arrives and it's still unfound, so the cache can't be permanent for those.
    private static final Set<String> fullyDiscoveredResources = ConcurrentHashMap.newKeySet();
    private static volatile int fullyDiscoveredCacheSeason = -1;

    // Composed, ready-to-draw marker icons, keyed by everything they actually depend on (resource,
    // product, season). The minimap discovery renderer asks for one per discoverable gob per
    // FRAME, and every TexI handed out becomes its own GPU texture upload the first time it's
    // drawn - without this, that's a fresh upload per gob per frame, all of it discarded again
    // immediately.
    private static final Map<String, TexI> markerIconCache = new ConcurrentHashMap<>();

    // Composed multi-product marker images, keyed by (resource, product set, season) - see
    // getMarkerIcon(Gob, List). Shared across every marker of the same species/state so a
    // re-render (bulk load, overlay toggle) is a cheap cache hit, not a fresh GPU upload.
    private static final Map<String, TexI> composedMarkerCache = new ConcurrentHashMap<>();

    // Bumped whenever the set of undiscovered products could have changed (a discovery recorded,
    // a reset, a character switch). The per-gob marker overlay watches this so it can re-render
    // the instant something is found instead of on a fixed timer - which is what made picked
    // items' icons linger. Cheap to read (one volatile int), so it can be polled every ctick.
    private static volatile int generation = 0;

    public static int generation() {
        return generation;
    }

    /** Nudge the overlays to re-render even without a discovery (e.g. a config toggle). */
    public static void bumpGeneration() {
        generation++;
    }

    /**
     * Drops every cached discovery-derived value. Discovery state is per-CHARACTER (it lives in
     * LpLog, which is rebuilt per character) while these caches are static, so switching
     * characters would otherwise leave the previous character's "already discovered" conclusions
     * in place - showing an alt no markers and no tint for products it hasn't actually found yet.
     */
    public static void reset() {
        fullyDiscoveredResources.clear();
        fullyDiscoveredCacheSeason = -1;
        markerIconCache.clear();
        composedMarkerCache.clear();
        generation++;
        LpIcons.clearLabelCache();
    }

    // Package-visible so ProductListHarvestSpec (the log/stone/old-trunk always-on overlay) can
    // skip its own per-product undiscovered checks the same way once nothing's left to find.
    static boolean isFullyDiscovered(String gobResName) {
        if (gobResName == null)
            return false;
        int season = HarvestState.isYesteryearSeason() ? 1 : 0;
        if (season != fullyDiscoveredCacheSeason) {
            fullyDiscoveredResources.clear();
            fullyDiscoveredCacheSeason = season;
        }
        if (fullyDiscoveredResources.contains(gobResName))
            return true;

        LpLog info = charLog();
        if (info == null)
            return false;
        List<String> products = LpSpec.getObject(gobResName);
        if (products != null) {
            for (String product : products) {
                if (isCurrentSeasonProduct(gobResName, product) && !discovered(info, gobResName, product))
                    return false;
            }
        }
        if (HarvestSpecs.TREE.matches(gobResName) && hasUndiscoveredBarkProduct(gobResName))
            return false;

        // The per-product checks above read the season independently as they go, so a season
        // boundary crossed mid-scan could otherwise file an old-season conclusion under the new
        // season - where, unlike every other entry here, it would be wrong and would survive until
        // the NEXT boundary months later. Re-checking narrows that to the point of irrelevance
        // without having to thread the season through every call below.
        if (season == (HarvestState.isYesteryearSeason() ? 1 : 0) && season == fullyDiscoveredCacheSeason)
            fullyDiscoveredResources.add(gobResName);
        return true;
    }

    /**
     * The resource this gob turns into when worked on, whose products therefore count as
     * reachable from it: a standing tree becomes a log when chopped, and it's the LOG - not the
     * tree - that carries the Board/Block entries in LpSpec.object. Lets a standing tree advertise
     * that its wood products are still undiscovered, which it otherwise has no way to express
     * since it tracks none of them itself.
     *
     * Derived by name rather than a hand-maintained table, which LpSpec.object supports: of its
     * standing-tree entries nearly all have a matching "<name>log" entry (only appletreegreen
     * lacks one), and of its log entries all but charredtreelog have a matching standing tree.
     * The containsKey check below means either gap simply yields no hint rather than a bad lookup.
     *
     * Bushes are excluded - they don't fell into logs.
     */
    public static String derivedResource(String gobResName) {
        if (gobResName == null || !gobResName.startsWith("gfx/terobjs/trees/"))
            return null;
        if (!HarvestState.isTreeOrBushRes(gobResName))
            return null;  // already a log/oldtrunk/stump - nothing further to fell it into
        String log = gobResName + "log";
        return LpSpec.hasObject(log) ? log : null;
    }

    /**
     * Whether felling this gob would expose a product that's still undiscovered - i.e. whether
     * the "you'd have to chop this" hint is worth showing. Note this is a statement about the
     * DERIVED resource's discovery state, so it stays true no matter how thoroughly the standing
     * tree's own seed/leaf/bough/bark products have been found.
     */
    public static boolean hasUndiscoveredDerivedProduct(String gobResName) {
        String derived = derivedResource(gobResName);
        if (derived == null || charLog() == null)
            return false;
        return !isFullyDiscovered(derived);
    }

    /**
     * Whether every product this standing tree/bush can yield WITHOUT being destroyed - its
     * seed/fruit, leaf, bough and bark - has already been discovered. Used to gate felling: a tree
     * whose own berries (etc.) aren't found yet must never be chopped down, or the bot would
     * destroy the very thing it should be harvesting (as it did to a juniper's berries). Checked
     * season-independently via undiscoveredCategories rather than the live availability bits, so a
     * tree that simply isn't fruiting right now still counts as "not done" and is spared.
     */
    public static boolean ownProductsAllDiscovered(String gobResName) {
        UndiscoveredCategories c = undiscoveredCategories(gobResName);
        return !c.seed && !c.leaf && !c.bough && !hasUndiscoveredBarkProduct(gobResName);
    }

    // Throws haven.Loading if the gob's sprite hasn't loaded yet - propagated to the caller,
    // same as HarvestState.hasHarvestableSeed() itself does.
    public static boolean hasUndiscoveredProduct(Gob gob) {
        return firstUndiscoveredProduct(gob) != null;
    }

    // Same check as hasUndiscoveredProduct(), but also returns which product it is - lets callers
    // that need both (e.g. the minimap discovery renderer, which gates on this and then resolves
    // an icon for the same product) avoid running the discovery scan twice per gob.
    public static String firstUndiscoveredProduct(Gob gob) {
        List<String> products = allUndiscoveredProducts(gob);
        return products.isEmpty() ? null : products.get(0);
    }

    /**
     * The products the character's LOG still lacks for this gob's resource, ignoring the gob's
     * live availability bits.
     *
     * <p>This is the discovery half of {@link #allUndiscoveredProducts(Gob)} and deliberately
     * drops the gob-side gates (maturity, seed/leaf bits): those change the moment a pick lands,
     * while the item-parented {@code LpLog} entry lands a beat later. A before/after comparison
     * keyed off the bit view can therefore report "discovered something" for a discovery that has
     * not actually been recorded yet (one redundant walk; only harmful if the item never registers
     * at all). Keyed off the log view, the same comparison waits until the record has actually
     * landed - which is exactly what the LP executor's progress check needs.
     */
    public static List<String> logUndiscoveredProducts(Gob gob) {
        String name = resname(gob);
        if (name == null)
            return Collections.emptyList();
        String gobResName = HarvestState.normalizeBumlingRes(name);
        if (isFullyDiscovered(gobResName))
            return Collections.emptyList();

        List<String> products = undiscoveredProductsMatching(gobResName, product -> true);
        if (HarvestSpecs.TREE.matches(gobResName) && hasUndiscoveredBarkProduct(gobResName)) {
            products = new ArrayList<>(products);
            products.add(HarvestState.getBarkProductName(gobResName));
        }
        return products;
    }

    // Every currently-undiscovered product this gob tracks, not just the first - lets markers
    // show every still-undiscovered icon at once instead of just one at a time, matching how the
    // always-on overlay stacks leaf/seed/bough/bark simultaneously.
    public static List<String> allUndiscoveredProducts(Gob gob) {
        String name = resname(gob);
        if (name == null)
            return Collections.emptyList();
        // Mineable "bumlings" carry a variant-digit suffix (granite3) that LpSpec.object isn't
        // keyed by - without this, every stone's product lookup misses and stones are silently
        // treated as having nothing undiscoverable, so the marker and the auto-LP bot both ignore
        // them. A no-op for every other resource. (The always-on stone overlay already normalized
        // via ProductListHarvestSpec; this is the discovery side catching up.)
        String gobResName = HarvestState.normalizeBumlingRes(name);
        if (isFullyDiscovered(gobResName))
            return Collections.emptyList();

        if (!HarvestState.isTreeOrBushRes(gobResName))
            return undiscoveredProductsMatching(gobResName, product -> true);

        Drawable dr = gob.getattr(Drawable.class);
        if (!(dr instanceof ResDrawable))
            return Collections.emptyList();
        ResDrawable d = (ResDrawable) dr;
        if (!HarvestState.isMatureTreeOrBush(gob, d))
            return Collections.emptyList();

        // Seed/leaf are gated by their own live bit; bough (a fixed per-species trait, already
        // implied by the product simply existing in LpSpec.object) and bark (assumed always
        // available on a mature tree/bush) aren't bit-gated at all - matching TreeHarvestSpec/
        // BushHarvestSpec's own per-category availability model.
        int sdt = Sprite.decnum(d.sdt.clone());
        boolean seedPresent = HarvestState.hasSeedBit(sdt);
        boolean leafPresent = HarvestState.hasLeafBit(sdt);

        List<String> products = undiscoveredProductsMatching(gobResName, product -> {
            if (isLeafProduct(product)) return leafPresent;
            if (isBoughProduct(product)) return true;
            return seedPresent;
        });

        if (HarvestSpecs.TREE.matches(gobResName) && hasUndiscoveredBarkProduct(gobResName)) {
            products = new ArrayList<>(products);
            products.add(HarvestState.getBarkProductName(gobResName));
        }
        return products;
    }

    /**
     * The products a floating LP MARKER should show for a gob: everything allUndiscoveredProducts
     * returns, plus - for a standing tree with the wood hint enabled - the Board/Block its felled
     * log would yield, while those are still undiscovered. Deliberately marker-only: the auto-LP
     * planner uses allUndiscoveredProducts, which must NOT list wood on a standing tree (you can't
     * take a board off it without felling it first), but the marker should still advertise "chop
     * this for a Board you don't have" - which otherwise only appears with the harvest overlay
     * (treeHarvestOverlay) turned on, a separate toggle most LP users never enable.
     */
    public static List<String> markerProducts(Gob gob) {
        List<String> base = allUndiscoveredProducts(gob);
        String name = resname(gob);
        if (name == null || !LpConfig.on(LpConfig.Key.treeHarvestWood))
            return base;
        String derived = derivedResource(name);
        List<String> products = (derived != null) ? LpSpec.getObject(derived) : null;
        if (products == null)
            return base;
        List<String> result = null;
        for (String p : products) {
            if (isProductUndiscovered(derived, p)) {
                if (result == null)
                    result = new ArrayList<>(base);
                result.add(p);
            }
        }
        return result == null ? base : result;
    }

    /** Which harvest categories (seed/leaf/bough) still have an undiscovered product for a resource. */
    public static class UndiscoveredCategories {
        public final boolean seed, leaf, bough;
        private UndiscoveredCategories(boolean seed, boolean leaf, boolean bough) {
            this.seed = seed;
            this.leaf = leaf;
            this.bough = bough;
        }
    }

    private static final UndiscoveredCategories NONE_UNDISCOVERED = new UndiscoveredCategories(false, false, false);

    // LpSpec.object lists every trackable product for a resource with no category metadata at all
    // (e.g. figtree -> ["Fig Leaf", "Fig"]), so a blanket "is anything undiscovered" check can't
    // tell which of several simultaneously-shown icons (seed/leaf/bough) it actually applies to -
    // tinting the wrong one, or leaving the right one untinted, whenever a species tracks more
    // than one product. The data does follow a reliable naming convention though (confirmed by
    // grepping every multi-product entry in the source data): leaf products always contain "Leaf"
    // or "Leaves" (e.g. "Fig Leaf", "Laurel Leaves"), bough products always contain "Bough" (e.g.
    // "Alder Bough"), and everything else is the seed/fruit/catkin product the "seed" icon
    // represents. Classified in one pass over the product list (used by TreeHarvestSpec/
    // BushHarvestSpec) rather than one independent rescan per category.
    public static UndiscoveredCategories undiscoveredCategories(String gobResName) {
        if (gobResName == null || !LpSpec.hasObject(gobResName))
            return NONE_UNDISCOVERED;
        if (isFullyDiscovered(gobResName))
            return NONE_UNDISCOVERED;
        LpLog info = charLog();
        if (info == null)
            return NONE_UNDISCOVERED;

        boolean seed = false, leaf = false, bough = false;
        for (String product : LpSpec.getObject(gobResName)) {
            if (!isCurrentSeasonProduct(gobResName, product) || discovered(info, gobResName, product))
                continue;
            if (isLeafProduct(product)) leaf = true;
            else if (isBoughProduct(product)) bough = true;
            else seed = true;
        }
        return new UndiscoveredCategories(seed, leaf, bough);
    }

    // Bark isn't listed in LpSpec.object at all (unlike seed/leaf/bough, which are literal product
    // entries there) - its item name is assumed from the species instead (see
    // HarvestState.getBarkProductName()), so this checks discovery directly rather than filtering
    // LpSpec.object's product list like the other three hasUndiscovered*Product methods do.
    public static boolean hasUndiscoveredBarkProduct(String gobResName) {
        String barkProduct = HarvestState.getBarkProductName(gobResName);
        LpLog info = charLog();
        if (barkProduct == null || info == null)
            return false;
        // Unlike seed/leaf/bough (uniquely named per species), several species share the exact
        // same bark item name ("Treebark", "Tough Bark") - confirmed in-game that picking it from
        // one species' tree also satisfies it for every other species sharing that name, so check
        // discovery globally rather than against just this one resource.
        return !info.IsLpExplorerContainsAnywhere(barkProduct);
    }

    /**
     * Whether this product counts as already discovered for this resource.
     *
     * Normally that's a per-resource fact: a species' seed/fruit/bough name is unique to it, so
     * finding it says nothing about any other resource. But some products are reachable from more
     * than one resource - a Mallard Feather comes off both the drake and the hen, Cleaned Chicken
     * off any of the chicken gobs - and for those the discovery has to be checked GLOBALLY, or
     * every sibling resource would keep its marker forever after the first pickup (and the LP is
     * only ever awarded once anyway, so a per-resource check would also be claiming there's
     * something left to gain when there isn't). Bark already needed exactly this; LpTargets
     * generalises it by deriving the shared set from the data instead of hand-listing it.
     */
    private static boolean discovered(LpLog info, String gobResName, String product) {
        if (LpTargets.isSharedProduct(product))
            return info.IsLpExplorerContainsAnywhere(product);
        return info.IsLpExplorerContains(gobResName, product);
    }

    // Package-visible so LpPlanner can classify ONE gob's undiscovered products back into menu
    // categories - see LpPlanner.harvestOptions(res, isTree, undiscovered).
    static boolean isLeafProduct(String product) {
        return product.contains("Leaf") || product.contains("Leaves");
    }

    // Package-visible for the same reason as isLeafProduct.
    static boolean isBoughProduct(String product) {
        // Most species name this product "<Species> Bough", but a few (olive) call it
        // "<Species> Branch" and offer it via a "Take branch" menu option. Both are the same
        // bough category. Without the Branch case, "Olive Branch" fell through to the seed
        // branch and got drawn with the tree's seed (olive) icon - so an olive tree showed the
        // olive icon twice (once for "Olive", once for the mis-typed "Olive Branch").
        return product.contains("Bough") || product.contains("Branch");
    }

    private static List<String> undiscoveredProductsMatching(String gobResName, Predicate<String> category) {
        if (gobResName == null || !LpSpec.hasObject(gobResName))
            return Collections.emptyList();
        LpLog info = charLog();
        if (info == null)
            return Collections.emptyList();

        List<String> result = new ArrayList<>();
        for (String product : LpSpec.getObject(gobResName)) {
            if (!category.test(product) || !isCurrentSeasonProduct(gobResName, product))
                continue;
            if (!discovered(info, gobResName, product))
                result.add(product);
        }
        return result;
    }

    // The icon representing one of this gob's undiscovered LP products, if resolvable: for
    // trees/bushes, the harvest-category icon (seed/leaf/bough) matching this specific product,
    // matching what the always-on overlay itself would show; otherwise a name-based lookup via
    // LpSpec's icon data (e.g. mineable "bumlings" ore/stone gobs, or a log's Board/Block, whose
    // names already have icons recorded there). Returns null if nothing can be resolved (falls
    // back to a generic marker at the call site).
    public static TexI getMarkerIcon(Gob gob, String knownUndiscoveredProduct) {
        String name = resname(gob);
        if (name == null)
            return null;
        String product = knownUndiscoveredProduct != null ? knownUndiscoveredProduct : firstUndiscoveredProduct(gob);
        if (product == null)
            return null;

        // Season matters because resolveProductIcon() picks the "Yesteryear's " seed icon during
        // it; the resource matters because tree/bush species resolve their own per-category icon.
        String key = name + '|' + product + '|' + (HarvestState.isYesteryearSeason() ? 'y' : 'n');
        TexI cached = markerIconCache.get(key);
        if (cached != null)
            return cached;

        BufferedImage img = resolveProductIcon(gob, product);
        if (img == null)
            // Left uncached deliberately: the miss path bottoms out in HarvestState's own icon
            // cache (which does record failures), so repeating it costs a couple of map lookups
            // and no allocation.
            return null;
        TexI tex = new TexI(img);
        markerIconCache.put(key, tex);
        return tex;
    }

    // A single combined, tinted icon showing every currently-undiscovered product for this gob at
    // once, stacked the same way the always-on overlay stacks leaf/seed/bough/bark - used by the
    // 3D-world marker so a log (Board + Block) or a multi-product tree/bush reads the same way
    // whether the overlay or the fallback marker is showing it. Delegates the actual tint+layout
    // to LpIcons.compose(), the same step the overlay uses, so the two displays never drift apart.
    public static TexI getMarkerIcon(Gob gob, List<String> knownUndiscoveredProducts) {
        String name = resname(gob);
        if (name == null)
            return null;
        List<String> products = knownUndiscoveredProducts != null ? knownUndiscoveredProducts : allUndiscoveredProducts(gob);
        if (products.isEmpty())
            return null;

        // The composed+tinted image depends only on (resource, exact product set, season) - not
        // on the specific gob instance - so every marker of the same species and discovery state
        // shares one TexI. Without this, EVERY marker rebuilt and re-uploaded its own texture to
        // the GPU on every re-render, which is what spiked the frame when many objects loaded at
        // once or the overlay was toggled (a toggle re-renders every marker on one frame). The
        // key changes as products are discovered, so a stale composition is never reused.
        String key = name + '|' + String.join(",", products) + '|' + (HarvestState.isYesteryearSeason() ? 'y' : 'n');
        TexI cached = composedMarkerCache.get(key);
        if (cached != null)
            return cached;

        List<HarvestSpec.Part> parts = new ArrayList<>(products.size());
        for (String product : products) {
            BufferedImage img = resolveProductIcon(gob, product);
            if (img != null)
                parts.add(new HarvestSpec.Part(product, img, true));
        }

        // Lay out the same direction the gob's own always-on harvest overlay would (e.g. a log's
        // Board+Block side by side), so the fallback marker and the overlay read consistently.
        HarvestSpec spec = HarvestSpecs.forResource(name);
        boolean horizontal = spec != null && spec.horizontal();
        TexI composed = LpIcons.compose(horizontal, parts);
        if (composed != null)
            composedMarkerCache.put(key, composed);
        return composed;
    }

    // Whether this exact product is still undiscovered for this gob - package-visible so
    // ProductListHarvestSpec (the log/stone always-on overlay) can tint individual icons the same
    // way the tree/bush categories already do.
    static boolean isProductUndiscovered(String gobResName, String product) {
        LpLog info = charLog();
        if (info == null || gobResName == null || product == null)
            return false;
        return isCurrentSeasonProduct(gobResName, product) && !discovered(info, gobResName, product);
    }

    // Resolves one product's own icon: the matching harvest-category icon (seed/leaf/bough) for
    // tree/bush species, so a leaf product shows its leaf icon rather than always falling back to
    // the seed icon; the generic LpSpec name-based lookup otherwise. Package-visible so
    // ProductListHarvestSpec can resolve log/stone icons the same way.
    static BufferedImage resolveProductIcon(Gob gob, String product) {
        Drawable dr = gob.getattr(Drawable.class);
        if (dr instanceof ResDrawable) {
            Resource res = ((ResDrawable) dr).getres();
            if (HarvestState.isTreeOrBush(res)) {
                // Only a product the tree/bush ACTUALLY tracks gets a category (seed/leaf/bough/
                // bark) icon. A derived wood product - a felled log's Board/Block shown as a hint on
                // the standing tree - isn't in the tree's own list, so it must fall through to the
                // by-name lookup below rather than be mis-drawn as the tree's seed icon.
                List<String> own = LpSpec.getObject(res.name);
                boolean tracksThis = (own != null && own.contains(product))
                    || product.equals(HarvestState.getBarkProductName(res.name));
                if (tracksThis) {
                    String type = isLeafProduct(product) ? "leaf"
                        : isBoughProduct(product) ? "bough"
                        : product.equals(HarvestState.getBarkProductName(res.name)) ? "bark"
                        : "seed";
                    BufferedImage img = HarvestState.getIcon(res, type);
                    if (img != null)
                        return img;
                }
            }
        }
        return HarvestState.loadIcon(LpSpec.getIconPath(product));
    }

    // Reverse index: product name -> A resource that tracks it in LpSpec.object. Built lazily
    // (LpSpec's own static data is populated by class-init order this class shouldn't assume has
    // already run) and cached.
    //
    // For plants and stones this is one-to-one - no two species share a seed/leaf/bough/board/
    // block/ore name - but it isn't in general: bark item names are shared across species, and so
    // are some animal products (a Mallard Feather from either sex, Cleaned Chicken from any chicken
    // gob). Which of the siblings a discovery gets FILED under doesn't matter, because lookups for
    // a shared name go through discovered(), which checks globally - the same argument bark's own
    // synthetic storage key already rests on. So putIfAbsent picking an arbitrary one is fine.
    private static Map<String, String> productToResource;

    private static synchronized Map<String, String> productToResource() {
        if (productToResource == null) {
            Map<String, String> index = new HashMap<>();
            for (Map.Entry<String, ArrayList<String>> e : LpSpec.objectEntries()) {
                for (String product : e.getValue()) {
                    index.putIfAbsent(product, e.getKey());
                }
            }
            productToResource = index;
        }
        return productToResource;
    }

    /** Any accepted spelling of a product -> the SPEC's own spelling of it. */
    private static Map<String, String> productAlias;

    /**
     * The spec's name for a product the game has just called {@code name}, or null if the spec has
     * no such product at all.
     *
     * Everything downstream of a discovery - the record itself, the marker, "is this species
     * finished" - is keyed by the name the SPEC uses, because that is the only name the product
     * lists are written in. So a discovery must be translated INTO that name at the moment it is
     * recorded, and this is where.
     *
     * The case that made this necessary: a few names carry a trailing bracket the game does not
     * use, "Board of Briarwood (Heath)" and "Board of Charred (Valhalla)", inherited verbatim from
     * nurgling2's VSpec (which took them from the wiki) by gen-lpspec.py. Stripping the bracket was
     * already enough to find which RESOURCE the board came from - and that was the trap. Knowing
     * the resource, the discovery was filed under the game's plain "Board of Briarwood", while
     * every later check went on asking for the bracketed spelling, so it was recorded and never
     * found: the character's own log holds that entry today and the boards still show as
     * undiscovered. Resolving the name and resolving the resource have to be the same step.
     */
    static synchronized String canonical(String name) {
        if (productAlias == null) {
            Map<String, String> alias = new HashMap<>();
            for (ArrayList<String> products : LpSpec.objectValues()) {
                for (String product : products)
                    alias.put(product, product);
            }
            // Second pass, so a real product spelled like some other product's stripped form is
            // never displaced by the alias.
            for (String product : new ArrayList<>(alias.keySet())) {
                String plain = stripBracket(product);
                if (plain != null)
                    alias.putIfAbsent(plain, product);
            }
            productAlias = alias;
        }
        return productAlias.get(name);
    }

    /** Item icon resource -> the SPEC's name for that product. Only unambiguous icons are in it. */
    private static Map<String, String> iconToProduct;

    /**
     * Which gob an item came from, decided by its ICON rather than by its name.
     *
     * The names in the spec are not reliably the names the game uses. They come from nurgling2's
     * VSpec, which took them from the wiki, and the wiki spells several woods differently: the
     * spec says "Board of Osier" and "Board of Briarwood (Heath)" where the game says Osierwood and
     * Briarwood. A name that is not recognised is discarded in silence, so those discoveries never
     * counted no matter how many logs a bot chopped, and correcting them one at a time only lasts
     * until somebody finds the next one.
     *
     * The ICON does not have that problem. It is a resource path, it is in the spec already
     * ({@code LpSpec.productIcon}), and an item's own resource is the same path - so matching on it
     * sidesteps the display name entirely.
     *
     * It returns the SPEC's name for the product and not the game's, which is the whole point:
     * discovery is recorded and then looked up again by that name, so recording what the game
     * called it would leave the two halves disagreeing exactly as they do now - the record would
     * say "Board of Osierwood" and every later check would go on asking about "Board of Osier".
     *
     * AMBIGUOUS ICONS ARE LEFT OUT. Several products share one, most often a plant and its seed
     * (both seaberries and seaberry seed are drawn as {@code seed-sandthorn}), and crediting the
     * wrong one of a pair would be worse than crediting neither - it would mark something
     * discovered that the character has never seen, and nothing would ever correct it.
     */
    private static synchronized String productByIcon(Widget itemWidget) {
        if (!(itemWidget instanceof GItem))
            return null;
        if (iconToProduct == null) {
            Map<String, String> byIcon = new HashMap<>();
            Set<String> ambiguous = new HashSet<>();
            Map<String, String> toRes = productToResource();
            for (Map.Entry<String, String> e : LpSpec.iconEntries()) {
                String product = e.getKey();
                // Only products that are actually discoverable from some gob; the icon table is
                // much broader than the harvest data and the rest would only add collisions.
                if (!toRes.containsKey(product))
                    continue;
                String prev = byIcon.put(e.getValue(), product);
                if (prev != null && !prev.equals(product))
                    ambiguous.add(e.getValue());
            }
            byIcon.keySet().removeAll(ambiguous);
            iconToProduct = byIcon;
        }
        try {
            Resource res = ((GItem) itemWidget).getres();
            return (res == null) ? null : iconToProduct.get(res.name);
        } catch (RuntimeException e) {
            // Includes Loading: an item whose resource hasn't arrived is looked at again next tick.
            return null;
        }
    }

    /**
     * A product name with its trailing "(...)" removed, or null if it hasn't got one.
     *
     * Only the bracket at the very END, and only when something is left in front of it: that is
     * the shape a disambiguator takes, and it keeps names where the bracket is part of the thing
     * out of it.
     */
    private static String stripBracket(String product) {
        if (product == null || !product.endsWith(")"))
            return null;
        int at = product.lastIndexOf(" (");
        if (at <= 0)
            return null;
        String plain = product.substring(0, at).trim();
        return plain.isEmpty() ? null : plain;
    }

    // Called for every newly-resolved item name. Discovery is tracked per RESOURCE (any gob of a
    // species satisfies the same discovery, not just the specific instance that produced it), so
    // this looks up which resource the name belongs to directly from LpSpec.object rather than
    // needing to know exactly which gob produced it. All the "should we even consider this
    // pickup" decisions (a tool rather than a product, an unrecognized name, not actually a
    // harvest) live here too.
    public static void checkLpExplorer(String name, Widget itemWidget) {
        // Exclude tools from LP tracking - the player's own axe/saw resolving its name isn't a
        // harvested product (LpSpec.object wouldn't have an entry for it anyway, but this avoids
        // relying on that alone).
        if (name.contains(" Axe") || name.contains(" Saw"))
            return;
        GameUI gui = LpContext.gui();
        LpLog info = LpContext.log();
        if (gui == null || info == null)
            return;

        // Only count an item the player actually HAS - i.e. one resolving its name inside their
        // own pack. Items shown in someone else's container widget (the cupboard case this gate
        // exists for) are parented to that container's Inventory instead, so they no longer
        // register merely for being looked at.
        //
        // This replaces an earlier gate that required the last right-clicked gob to be a
        // harvestable resource. That was a proxy for "a harvest just happened", and it had the
        // side effect of discarding every discovery that doesn't come from clicking a gob at all
        // - butchering a carcass, cleaning a critter, eating a berry that leaves a seed - since
        // for those the last-clicked gob is stale or absent. Possession is both the more direct
        // signal and the one that covers those cases. Inventory.addchild() parents a GItem to the
        // Inventory itself, so this is a direct identity check.
        if (itemWidget == null || itemWidget.parent != gui.maininv)
            return;

        /* Two ways of getting from what the game called it to what the spec calls it, tried in
         * that order, and BOTH of them settle the recorded name rather than only the resource:
         * whatever comes out of here is what gets written down and what every later check asks
         * for, so the two halves can only agree if they are decided together. See canonical.
         *
         * By name covers everything but a handful of woods. By icon covers those: the spec says
         * "Board of Osier" where the game says Osierwood, and no amount of spelling rules will
         * find the next one of those, whereas the icon resource is the same path on both sides. */
        String product = name;
        String spec = canonical(name);
        if (spec != null)
            product = spec;
        String gobName = productToResource().get(product);
        if (gobName == null) {
            String byIcon = productByIcon(itemWidget);
            if (byIcon != null) {
                product = byIcon;
                gobName = productToResource().get(byIcon);
            }
        }
        if (gobName == null && HarvestState.isBarkProductName(name))
            // Bark isn't an LpSpec.object entry and several species share the exact same bark item
            // name - the resource key here is just a stable, synthetic storage key; discovery for
            // bark is always checked globally (IsLpExplorerContainsAnywhere), never against a
            // specific resource, so which key it's filed under doesn't matter.
            gobName = "bark:" + name;

        if (gobName == null)
            return;

        if (!info.IsLpExplorerContains(gobName, product)) {
            info.LpExplorerAdd(gobName, product);
            info.newLpExplorer = true;
            // A product just became discovered - drop the fully-discovered cache entry for its
            // resource (it may now be complete) and bump the generation so markers re-render now.
            fullyDiscoveredResources.remove(gobName);
            generation++;
        }
    }

    private static LpLog charLog() {
        return LpContext.log();
    }
}
