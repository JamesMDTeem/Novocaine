package haven.automated.survey;

import haven.Area;
import haven.Coord;
import haven.automated.nbots.world.WorldAnchor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A worked-out set of surveys that levels a region to one flat plane, and what each will cost.
 *
 * Plain data: {@link SurveyPlanner} produces it, {@link SurveyPlanStore} persists it, and the
 * window renders it. Nothing here reaches into the game.
 *
 * <p>{@link #targetZ} is in raw client z and is the level EVERY survey is set to - not each
 * survey's own mean, which would terrace the region rather than flatten it. Converting it to the
 * survey window's quantised units needs the server-supplied {@code gran}, which is why
 * {@link #targetDz} takes it as an argument instead of the plan storing one.
 */
public class SurveyPlan {
    /**
     * The whole region the plan covers, in absolute tile coordinates.
     *
     * {@code br} is exclusive, matching every other {@link Area} in the client, so
     * {@code region.sz()} is the tile count rather than one short of it.
     */
    public final Area region;
    /** The one level every survey is set to, in raw client z. */
    public final double targetZ;
    public final List<SurveySpec> surveys;
    public final List<Transfer> transfers;
    /**
     * Something durable to hang the coordinates off, or null for a plan that has none.
     *
     * Every tile coordinate above is relative to the CURRENT session's floating map origin, which
     * moves when you log back in. A plan read off disk in a later session therefore describes real
     * rectangles at meaningless coordinates - the list still reads correctly, the ground is bare,
     * and it looks for all the world as though nothing was saved.
     *
     * <p>The anchor is the fix, and it is the one {@code Places} already uses: a
     * {@link WorldAnchor} is a server-assigned grid id plus an offset inside it, both of which mean
     * the same thing in every session and in every client. Captured at the PLAYER's position
     * rather than at the region's corner, because {@code WorldAnchor.capture} needs the grid it is
     * given to be loaded and recorded in the map file - true of where you are standing, routinely
     * not true of a corner several grids away.
     */
    public final WorldAnchor anchor;
    /** Tiles from the anchor's tile to {@link #region}'s corner. Meaningless without an anchor. */
    public final Coord anchorOff;
    /** Survey index -> its 1-based place in {@link #order()}; see {@link #step}. */
    private final Map<Integer, Integer> steps = new HashMap<>();

    public SurveyPlan(Area region, double targetZ, List<SurveySpec> surveys, List<Transfer> transfers) {
        this(region, targetZ, surveys, transfers, null, Coord.z);
    }

    public SurveyPlan(Area region, double targetZ, List<SurveySpec> surveys,
                      List<Transfer> transfers, WorldAnchor anchor, Coord anchorOff) {
        this.region = region;
        this.targetZ = targetZ;
        this.surveys = surveys;
        this.transfers = transfers;
        this.anchor = anchor;
        this.anchorOff = (anchorOff == null) ? Coord.z : anchorOff;
        List<SurveySpec> ord = order();
        for (int i = 0; i < ord.size(); i++)
            steps.put(ord.get(i).index, i + 1);
    }

    /** The same plan, with something durable to place it by. */
    public SurveyPlan anchored(WorldAnchor a, Coord off) {
        return new SurveyPlan(region, targetZ, surveys, transfers, a, off);
    }

    /**
     * The same plan, translated so its region starts at {@code ul}.
     *
     * This is what a plan read off disk goes through once its anchor resolves: the shape, the
     * balances and the work order are all unchanged - only the coordinates are restated in the
     * coordinates this session happens to be using. Stockpile hints move with their surveys, since
     * a hint that stayed put would point at ground in the old frame.
     */
    public SurveyPlan rebase(Coord ul) {
        Coord d = ul.sub(region.ul);
        if (d.equals(Coord.z))
            return this;
        List<SurveySpec> ns = new ArrayList<>(surveys.size());
        for (SurveySpec s : surveys)
            ns.add(new SurveySpec(s.index, Area.corn(s.tiles.ul.add(d), s.tiles.br.add(d)), s.net));
        List<Transfer> nt = new ArrayList<>(transfers.size());
        for (Transfer t : transfers)
            nt.add(new Transfer(t.from, t.to, t.amount, t.stockpile.add(d)));
        return new SurveyPlan(Area.corn(region.ul.add(d), region.br.add(d)), targetZ, ns, nt,
            anchor, anchorOff);
    }

    /** North-west corner of the region, in absolute tile coordinates. */
    public Coord ul() {
        return region.ul;
    }

    /** What one soil stockpile holds, for reporting a transfer in piles rather than units. */
    public static final double PILE_CAP = 250;

    /** How far outside its own boundary a survey can still reach a stockpile, in tiles. */
    public static final int REACH = 1;

    /**
     * Where a stockpile is reachable from BOTH of a transfer's surveys.
     *
     * A survey reaches anything inside it or within {@link #REACH} tiles of its boundary, and can
     * both fill and draw from a pile in that range. So the tiles usable by two surveys at once are
     * the overlap of their two expanded rectangles - for neighbours, a band two tiles wide running
     * along the edge they share. Soil dumped there by the survey with the surplus is picked up by
     * the one that needs it without anybody carrying it a step, which is the whole reason the
     * planner pairs surpluses with deficits by distance in the first place.
     *
     * <p>Which HALF of that band to use is decided by the work order rather than by geometry.
     * Surpluses are dug first, so by the time soil is moving the source's side is already at the
     * target level and a pile standing on it blocks nothing; the destination's side is still to be
     * levelled, and ground under a pile cannot be worked.
     *
     * <p>Null for two surveys that do not touch. The planner's flow is free to pair any surplus
     * with any deficit - it prices the walk rather than forbidding it - so a transfer across the
     * region is a real possibility, and it simply has no shared ground to offer.
     */
    public static Area reachZone(Area from, Area to) {
        Area a = Area.corn(from.ul.sub(REACH, REACH), from.br.add(REACH, REACH));
        Area b = Area.corn(to.ul.sub(REACH, REACH), to.br.add(REACH, REACH));
        return a.overlap(b);
    }

    /** The reach zone for one transfer, or null when its two surveys do not touch. */
    public Area reachZone(Transfer t) {
        return reachZone(surveys.get(t.from).tiles, surveys.get(t.to).tiles);
    }

    /**
     * Where a survey falls in the work order, 1-based; 0 for one this plan does not contain.
     *
     * This, not {@link SurveySpec#index}, is the number a player is shown - on the ground and in
     * the list, which have to agree or the highlight is worse than nothing. "Do 1, then 2" is the
     * instruction; the index is a row-major position that says nothing about what comes first. The
     * index stays the claim key underneath, because it is what identifies a survey in the shared
     * file and does not change when the ordering rule does.
     */
    public int step(int index) {
        Integer s = steps.get(index);
        return (s == null) ? 0 : s;
    }

    /**
     * The target in the units a survey window works in, for the {@code gran} that window reports.
     *
     * Matches what {@code LandSurvey.updmap} does to the ground it compares against -
     * {@code round(getfz(vc) * gran)} - so the plan's level and the window's reading are quantised
     * the same way and the predicted net is comparable to the displayed one.
     */
    public int targetDz(float gran) {
        return Math.round((float) (targetZ * gran));
    }

    /**
     * The work order: surveys with soil to spare first, then the ones that need it.
     *
     * A shortfall cannot be filled before the stockpiles feeding it exist, so the surpluses have to
     * be dug first. Stable within each group, so the plan's own index order survives and the list
     * reads the same way twice.
     */
    public List<SurveySpec> order() {
        List<SurveySpec> out = new ArrayList<>(surveys);
        out.sort((a, b) -> Boolean.compare(a.net > 0, b.net > 0));
        return out;
    }

    /** One survey to draw: where it goes, and how much soil it will have spare or short. */
    public static class SurveySpec {
        public final int index;
        /** The rectangle to draw, in absolute tile coordinates. */
        public final Area tiles;
        /** Positive needs soil brought in, negative has a surplus to give away. */
        public final double net;

        public SurveySpec(int index, Area tiles, double net) {
            this.index = index;
            this.tiles = tiles;
            this.net = net;
        }
    }

    /**
     * Soil moving from one survey to another.
     *
     * {@link #stockpile} is a tile inside the SOURCE survey, as near the destination as its own
     * boundary allows. That is where the surplus should be piled, so the next survey can be drawn
     * to take in that strip and let its own levelling consume the pile rather than anyone carrying
     * it further.
     */
    public static class Transfer {
        public final int from, to;
        public final double amount;
        public final Coord stockpile;

        public Transfer(int from, int to, double amount, Coord stockpile) {
            this.from = from;
            this.to = to;
            this.amount = amount;
            this.stockpile = stockpile;
        }
    }
}
