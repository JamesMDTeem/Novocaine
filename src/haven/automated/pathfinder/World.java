package haven.automated.pathfinder;

import haven.Coord2d;

/**
 * World — the one seam for "can a disc stand here, and what does it cost to route through".
 *
 * <p>The navigation stack grew several independent answers to the same physical question: whether a
 * character — a disc {@link #HALFWIDTH} across — may stand on or cross a point. {@code Router.World}
 * answers for the grid the planner searches, {@code Observed} for the record of what has been seen,
 * {@code BotNav} for the live gob boxes, {@code Terrain} for the map file, and {@link Map}'s own
 * {@code initGeography} for the window the character actually moves on. The models are not
 * interchangeable and they are not kept in step: cliff/rock/cave rules live only in the click path
 * ({@code initGeography}), so the router plans routes straight across cliffs the clicks refuse — the
 * certified-then-refused loop family.</p>
 *
 * <p>This interface is the shared question. The concrete models become adapters over it:</p>
 * <ul>
 *   <li>{@link Map} — continuous, the live window the character moves on. Answers from MCache
 *       tiles, gob boxes, cliff ridges and keep-out circles.</li>
 *   <li>{@code haven.automated.nbots.world.Router.World} — grid, the planner's view. Answers from
 *       the observed record, the terrain record and gate state.</li>
 * </ul>
 *
 * <p>The physical facts live here once: {@link #HALFWIDTH} (the disc radius) and {@link #TILE} (the
 * grid pitch). Executing the seam replaces the duplicate constants (Map.plbbox, Observed.HALFWIDTH,
 * Router.HALFWIDTH; the four TILE spellings) and moves the shared geography rules (deep/cave/nil/rock,
 * shallow-when-avoided, cliff, keep-out) into a single implementation both adapters consult, so the
 * planner certifies only what the mover accepts.</p>
 *
 * <p>Draft of 2026-08-06 (architecture review card 2): interface only — no callers yet. Adapters are
 * wired in the execution phase, per {@code plans/architecture-deepening-plan-2.md} phase 1.</p>
 */
public interface World {

    /** Radius of the character disc, in world units. Was Map.plbbox / Observed.HALFWIDTH / Router.HALFWIDTH. */
    double HALFWIDTH = 3.0;

    /** One map tile in world units. Matches {@code MCache.tilesz}. */
    double TILE = 11.0;

    /**
     * Whether a disc centred at {@code wc} may STOP here — a goal, a waypoint, a place to stand.
     * Crossing is not enough: a TIGHT channel is passable but not standable.
     */
    boolean standable(Coord2d wc);

    /** Whether a route may CROSS here. A refusal here is absolute — nothing routes through it. */
    boolean passable(Coord2d wc);

    /**
     * Routing cost through here. Higher than the plain tile cost makes the planner prefer a detour;
     * an impassable point is never costed.
     */
    int cost(Coord2d wc);

    /** Why the point was refused, for diagnostics; {@code null} when nothing objects. */
    String why(Coord2d wc);
}
