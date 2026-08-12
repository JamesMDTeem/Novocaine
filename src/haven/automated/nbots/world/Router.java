package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.automated.pathfinder.GridAStar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A route across a segment, planned tile by tile over ground the character has actually seen.
 *
 * Travel used to be greedy - aim at the destination, and on failing, swing blindly sideways and
 * widen the swing. Against anything longer than the swing, a lake shore or a palisade, that is a
 * bot pacing back and forth. No amount of tuning the local pathfinder helps, because it is only
 * ever shown an eighty-eight tile window with the target inside it: it answers its question
 * correctly, and the wrong question was being asked.
 *
 * WHY THIS IS AT TILE RESOLUTION. The first version searched over blocks of four tiles, on the
 * reasoning that a coarse grid is cheap and the point is only to choose the right side of the
 * lake. Every serious routing bug since came out of that choice, because a palisade is ONE TILE
 * THICK and a four-tile block cannot describe it. A block with a wall through it is neither open
 * nor closed: treat it as closed and every gateway in the base is sealed, treat it as open and the
 * route goes through the wall. The workarounds - sample the centre for ground but every tile for
 * walls, exempt the goal block, then exempt it only from sides worked out at tile resolution
 * anyway, then do the same for the start block - were each correct and each covering for the same
 * missing tile of resolution. At tile resolution none of them is needed and none of them exists: a
 * wall is a line of blocked tiles, a gateway is a gap in it, and A* walks through the gap.
 *
 * The cost is a bigger search, and it is not much of a cost. A three-hundred-tile journey with an
 * octile heuristic settles in a few milliseconds, which is nothing next to the walk it plans.
 *
 * WHAT IT PLANS ON. {@link Observed} for objects - dense, recorded from what the character has
 * seen, self-correcting - and {@link Terrain} for water, read from the map file so that ground
 * walked past hours ago is still answerable offline. Unseen ground stays passable but expensive,
 * because a route is a hypothesis the local pathfinder re-checks on arrival; the one caller whose
 * answer is FINAL asks for it to be refused instead. See {@link #reachable}.
 */
public class Router {
    /** Search ceiling, in tiles. A route needing more than this is a bad question. */
    public static final int MAX_TILES = 250000;

    /**
     * What a step across never-seen ground costs, as a multiple of a step across seen ground.
     *
     * Three, so a bot will go about three times as far to stay on ground it has looked at, and no
     * further. Higher rules out crossings that are perfectly ordinary - the corner of a field
     * nobody has walked over - and lower is not enough to turn a route aside from a straight line
     * that happens to run through the unknown.
     */
    private static final int UNKNOWN = 3;

    /**
     * What a tile the local pathfinder refused costs - see {@link Refused}.
     *
     * High enough that A* will go a very long way round rather than plan through one, low enough that
     * it still WILL when there is no other way. That second half is the point: a refusal is an
     * inference from one failed click, and an inference must not be able to make a destination
     * unreachable. Making it impassable let two refusals seal the only way out of where a bot stood,
     * the router returned null, and travel fell back on a single straight leg across fifteen solid
     * tiles - a far worse outcome than any detour.
     */
    private static final int REFUSED_COST = 200;

    /**
     * What routing a step through a SHUT gateway costs, as a multiple of a step over open ground.
     *
     * Only shut ones cost anything, because only shut ones cost anything: an open gateway is a
     * doorway you walk through without breaking stride, and charging for it made bots take long
     * detours around their own open doors. A shut one has to be stopped at, opened, and walked
     * through, which is worth a few tiles of walking to avoid - but only a few. This was briefly
     * twenty-five, which is a detour so large it distorted every route inside a base; six is enough
     * to prefer the open door next to the shut one and nothing like enough to send a bot round the
     * outside of a palisade.
     *
     * The gate a route genuinely needs still gets used, which is the half that must not break: a
     * cost can be paid, and only an impassable tile cannot.
     */
    private static final int GATE_COST = 6;

    /**
     * What a tile with no standable middle costs to cross. See {@link Observed#TIGHT}.
     *
     * Four, so a lane of three tight tiles loses to any open way up to about eight tiles longer and
     * wins against anything further. The number the logs argue for is "less than a hundred": the
     * behaviour being replaced is a hundred-and-five tile detour around a three tile gap.
     */
    private static final int TIGHT_COST = 4;

    /** Straight and diagonal step costs, scaled so the whole search stays in integers. */
    private static final int STRAIGHT = 10, DIAGONAL = 14;

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    private Router() {}

    /**
     * Whether the player could walk to a live world point at all.
     *
     * For callers that CHOOSE targets rather than travel to them - the LP assistant is the one that
     * needed it. It picks whatever is nearest and undiscovered and then chases it with the local
     * pathfinder, which sees eighty-eight tiles and re-aims every few seconds, so it cannot hold a
     * detour; a target it cannot reach in a roughly straight line is one it will walk at until its
     * attempt budget runs out. It did exactly that at an apple across a river, for several evenings.
     *
     * OVER SEEN GROUND ONLY, and that is the whole difference from every other caller. Everywhere
     * else unseen ground is passable, and rightly: the local pathfinder still gets its veto tile by
     * tile when the bot arrives, so optimism costs a detour. Here there is no second opinion
     * coming - the answer IS the decision - and optimism makes the test vacuous, because past the
     * edge of what has been seen there is nothing left to say no and a search that wanders far
     * enough always finds a way round through country nobody has looked at. There was no way round
     * that river; there was only no evidence.
     *
     * Bounded by AREA rather than by a node count. A budget sounds equivalent and is not: proving
     * something unreachable means exhausting everywhere that is, which in open country is more
     * than any budget worth spending - so the search always ran out, "ran out" answered yes, and
     * the check passed everything. Confined to a box around the two ends, running out of BOX is a
     * real answer.
     */
    public static boolean reachable(GameUI gui, Coord2d target, int margin) {
        return reachable(gui, target, margin, true);
    }

    /**
     * @param throughGateways whether the caller can OPEN a shut gateway on the way. Pass false from
     *                        anything that walks with the client's own pathfinder and nothing else -
     *                        it answers a materially different question, and answering the wrong one
     *                        is how the LP assistant came to pick four felled logs inside a palisade
     *                        it had no way through and walk at the wall for each of them.
     */
    public static boolean reachable(GameUI gui, Coord2d target, int margin, boolean throughGateways) {
        return reachable(gui, target, margin, throughGateways, null);
    }

    /**
     * The same, reporting WHY when the answer is no.
     *
     * A bare boolean was costing whole rounds of guesswork. "no way to it on foot" went from about
     * seven a run to fifty-three between two builds, and the honest position was two plausible
     * causes and nothing to separate them: routing had just been taught to refuse rock and cave
     * (so the rise could be correct), and the candidate set had just changed shape (so it could be
     * a fault). {@link World#why} has always been able to answer, and this method threw it away.
     *
     * @param reason if non-null, {@code reason[0]} is filled with a short account of the refusal.
     */
    public static boolean reachable(GameUI gui, Coord2d target, int margin, boolean throughGateways,
                                    String[] reason) {
        Gob me = ((gui == null) || (gui.map == null)) ? null : gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        WorldAnchor there = WorldAnchor.capture(gui, target);
        if ((me == null) || (here == null) || (there == null) || (here.seg != there.seg))
            return true;
        Coord from = here.sc.floor(MCache.tilesz), to = there.sc.floor(MCache.tilesz);
        // Search over a clamped world (UNSEEN impassable). No bounding box needed - the search has
        // its own ceiling (MAX_TILES).
        World w = new World(gui, here.seg, true, throughGateways);
        w.confine(from, to, DETOUR);
        /* NEXT TO it, not ON it - which is what the margin has always been for and what this method
         * spent its whole life ignoring, the parameter being accepted and then dropped on the floor.
         *
         * Everything worth walking to is a solid object, and an object's own tile carries its own
         * collision box, so it is impassable BY DEFINITION. Asking whether a path exists onto that
         * tile therefore answers no for every bush, tree and boulder in the world - including ones
         * the character is standing next to. That is the whole of "the LP bot will not path to
         * things literally right beside it": they were all being reported unreachable, correctly,
         * for a question nobody meant to ask.
         *
         * So the goal is the nearest tile within the margin that can actually be stood on, which is
         * where the bot would end up anyway.
         *
         * SEVERAL of them, tried in order, not just the nearest one. Taking the first and answering
         * on it alone makes the verdict turn on which side of the object happens to be a tile
         * closer: a tree on the far bank of a one-tile stream has its nearest standable tile on the
         * FAR side, no path leads there, and the tree is declared unreachable on foot - while the
         * near bank, a tile further out and perfectly walkable, was never asked about. That is the
         * shape of the surviving "no way to it on foot" rejections.
         *
         * All of them at once, by {@link #floodReaches} - not one search each. Reachability is a
         * property of the connected component, so the whole candidate set shares one traversal. */
        List<Coord> goals = standableAround(w, to, (int) Math.ceil(margin / MCache.tilesz.x), TRIES);
        if (goals.isEmpty()) {
            // Not a refusal - this returns TRUE. Recorded anyway: "nowhere to stand" and "cut off"
            // are different worlds and they were indistinguishable in the log.
            if (reason != null)
                reason[0] = "nowhere standable within " + margin + "u of it";
            return true;
        }
        if (floodReaches(w, from, new HashSet<>(goals)))
            return true;
        if (reason != null)
            reason[0] = refusalAccount(w, from, to, goals);
        return false;
    }

    /**
     * What stopped us, in the fewest words that still separate the causes.
     *
     * Two very different failures wear the same sentence in the LP log. Either the places to stand
     * are themselves refused - and then WHICH rule refused them is the whole answer, because "rock,
     * cave or void" means the terrain classification, "deep water" means the map file, and "wall"
     * or "solid" means the observed record - or every one of them is fine and simply not connected
     * to where we are, which is a genuine barrier and nothing to fix.
     *
     * Reports the reasons actually seen rather than the first, since a mixture is itself the
     * answer: all of them rock says a rule changed, a spread says the target is behind something.
     */
    private static String refusalAccount(World w, Coord from, Coord to, List<Coord> goals) {
        java.util.Map<String, Integer> tally = new java.util.LinkedHashMap<>();
        int open = 0;
        for (Coord g : goals) {
            String why = w.why(g);
            if (why == null) {
                open++;
                continue;
            }
            tally.merge(why, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(goals.size()).append(" place(s) to stand: ");
        if (open == goals.size()) {
            sb.append("all walkable but none connected to us - a real barrier in between");
        } else {
            if (open > 0)
                sb.append(open).append(" walkable, ");
            boolean first = true;
            for (java.util.Map.Entry<String, Integer> e : tally.entrySet()) {
                if (!first)
                    sb.append(", ");
                sb.append(e.getValue()).append("x ").append(e.getKey());
                first = false;
            }
        }
        return sb.toString();
    }

    /**
     * Whether a flood from {@code from} over passable ground touches ANY of {@code goals}.
     *
     * One traversal for the whole candidate set, and that is the point rather than a tidiness.
     * This was a loop running one A* per candidate, and a FAILED A* has already explored every
     * tile it can reach - so asking again for a second goal in the same enclosure re-walks exactly
     * the same ground, and asking twenty-four times walks it twenty-four times. Widening the
     * candidate list from four to the full first two rings therefore did not cost 6x a little, it
     * cost 6x the most expensive branch this class has, and the LP assistant went from planning in
     * milliseconds to visibly stalling on the first target it could not reach.
     *
     * A flood answers all of them at once because they share the question: reachability is a
     * property of the connected component, not of the individual goal. Cost is one component
     * traversal no matter how many candidates there are - strictly cheaper than the four-search
     * version this replaced, while asking about six times as many places to stand.
     *
     * No heuristic and no ordering: nothing here wants the shortest route, only whether one
     * exists. The caller that wants a distance is {@link #walkingDistance}, and it still searches.
     *
     * Bounded by {@link #MAX_TILES} exactly as the searches were, and by the world's own confine
     * box - a flood over a clamped world stops at the edge of what has been seen.
     */
    private static boolean floodReaches(World w, Coord from, Set<Coord> goals) {
        if (goals.contains(from))
            return true;
        Set<Coord> seen = new HashSet<>();
        Deque<Coord> queue = new ArrayDeque<>();
        seen.add(from);
        queue.add(from);
        int visited = 0;
        while (!queue.isEmpty() && (visited++ < MAX_TILES)) {
            Coord cur = queue.poll();
            for (int i = 0; i < 8; i++) {
                Coord nb = cur.add(DX[i], DY[i]);
                if (seen.contains(nb) || !w.passable(nb))
                    continue;
                // The same no-corner-cutting rule the search uses; a flood that squeezed through a
                // diagonal the router will not take would answer for a route nobody can walk.
                if ((DX[i] != 0) && (DY[i] != 0)
                    && (!w.passable(cur.add(DX[i], 0)) || !w.passable(cur.add(0, DY[i]))))
                    continue;
                if (goals.contains(nb))
                    return true;
                seen.add(nb);
                queue.add(nb);
            }
        }
        return false;
    }

    /**
     * How many standable spots around a target {@link #reachable} will try before answering no.
     *
     * Twenty-four, which is every tile of the first two rings - all eight neighbours, then all sixteen
     * at two tiles. The point is the COMPLETE ring, not the count: {@link #standableAround} returns
     * nearest-first, so a smaller budget is spent entirely on whichever side of the target happens to
     * touch it, and the far side is never asked about.
     *
     * That is not hypothetical. It was four, and four is what the eight-tile first ring hands back for
     * anything standing in the open - so the margin said "look five tiles out" and nothing beyond one
     * tile was ever tried. A tree at the water's edge has its nearest standable tiles across the
     * stream; all four candidates landed there, none was reachable, and the tree was written off. In
     * the 15:27-15:31 session that verdict fired nineteen times in three runs and drained one run's
     * whole ready list - LP RETIRES on a no, so each one wrote a good resource off for the session.
     *
     * The count is nearly free now and was not always. While this fed one A* per candidate, raising
     * it from four multiplied the most expensive branch in the class by six and stalled the LP
     * assistant on the first target it could not reach. {@link #floodReaches} answers the whole set
     * in one traversal, so the list can be as long as the geometry warrants.
     */
    private static final int TRIES = 24;

    /**
     * The smaller budget {@link #walkingDistance} keeps, and why the two differ.
     *
     * That one measures rather than decides: it has no early exit, it searches every candidate it
     * is handed to find the cheapest, and it runs for the leading few targets of every plan. It
     * cannot use the flood - a flood answers whether, not how far - so its cost really is one
     * search per candidate and it stays small.
     */
    private static final int NEAR = 4;

    /**
     * How far outside the two ends a NEARBY question may search, in tiles. See {@link World#confine}.
     *
     * Forty: room to go the long way round a pond, a building or a stretch of palisade, and no room
     * for a journey. Deliberately more than generous relative to what it bounds - LP's targets are
     * things it can see, at most {@link Observed#SEES} = 44 tiles off, and a forty-tile detour to
     * reach a tree eight tiles away is already far past anything worth walking.
     *
     * Erring large on purpose. Too small reports a reachable target unreachable, and the LP
     * assistant RETIRES on that answer - it would quietly write off good resources for the session,
     * which is the expensive mistake. Too large only costs search time, which is what this is for.
     */
    private static final int DETOUR = 40;

    /**
     * How far the player would actually WALK to a live world point, in world units, or -1 if that
     * cannot be worked out from what has been observed.
     *
     * The companion to {@link #reachable}, for the same caller and the same reason. A chooser that
     * ranks its candidates by how far away they LOOK will send the bot round a lake to a tree eleven
     * tiles off while an identical tree fourteen tiles off, on this bank, goes untouched - straight
     * line distance is a lower bound on walking distance and the gap between them is exactly the
     * obstacle. Measuring the walk instead ranks by the thing that costs time.
     *
     * -1 rather than infinity for "cannot tell", because it is not a claim that the target is far;
     * it is the absence of a claim, and a caller that treats it as far would quietly demote
     * everything just beyond the observed edge. Callers should fall back to the straight line, which
     * is what this would have returned had the ground been flat and empty.
     */
    public static double walkingDistance(GameUI gui, Coord2d target, int margin) {
        Gob me = ((gui == null) || (gui.map == null)) ? null : gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        WorldAnchor there = WorldAnchor.capture(gui, target);
        if ((me == null) || (here == null) || (there == null) || (here.seg != there.seg))
            return -1;
        Coord from = here.sc.floor(MCache.tilesz), to = there.sc.floor(MCache.tilesz);
        World w = new World(gui, here.seg, true);
        w.confine(from, to, DETOUR);
        /* {@link #NEAR}, not {@link #TRIES}, and the asymmetry with {@link #reachable} is deliberate.
         *
         * This one has no early exit - it wants the CHEAPEST walk, so it searches every candidate it
         * is given - and it runs for the leading handful of candidates on every plan. Handing it the
         * full ring would multiply a per-plan cost by six to sharpen a ranking, where reachable pays
         * the same price once, on a branch that is already the dear one, to stop a target being
         * written off for the session.
         *
         * The two can now disagree: a target reachable only from the far ring gets no distance here
         * and comes back -1. That is the documented "cannot tell", the caller falls back to the
         * straight line, and a target ranked by its straight line is a target ranked slightly
         * optimistically - not one that is lost. */
        List<Coord> goals = standableAround(w, to, (int) Math.ceil(margin / MCache.tilesz.x), NEAR);
        double best = -1;
        for (Coord goal : goals) {
            List<Coord> path = search(w, from, goal, false);
            if (path == null)
                continue;
            // Waypoints, not tiles - simplify has already collapsed the straight runs, and the
            // length of a polyline is the same either way.
            double len = 0;
            Coord at = from;
            for (Coord c : path) {
                len += at.dist(c) * MCache.tilesz.x;
                at = c;
            }
            if ((best < 0) || (len < best))
                best = len;
        }
        return best;
    }

    /**
     * The closest tiles to {@code at} that a character could stand on, within {@code radius} tiles,
     * nearest first and at most {@code max} of them.
     *
     * Rings outward from the target so the order is by distance to the thing itself, which is what
     * the caller cares about - and so that stopping early stops on the near ones.
     */
    private static List<Coord> standableAround(World w, Coord at, int radius, int max) {
        List<Coord> out = new ArrayList<>();
        // standable, not passable: this is picking somewhere to BE, and a tile a character can only
        // squeeze across is not somewhere to be. See World.standable.
        if (w.standable(at))
            out.add(at);
        for (int r = 1; (r <= Math.max(radius, 1)) && (out.size() < max); r++) {
            List<Coord> ring = new ArrayList<>();
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    // Only the ring just reached; the inside was covered by a previous, tighter pass.
                    if ((Math.abs(dx) != r) && (Math.abs(dy) != r))
                        continue;
                    if (w.standable(at.add(dx, dy)))
                        ring.add(new Coord(dx, dy));
                }
            }
            ring.sort((a, b) -> Double.compare(Math.hypot(a.x, a.y), Math.hypot(b.x, b.y)));
            for (Coord d : ring) {
                if (out.size() >= max)
                    break;
                out.add(at.add(d));
            }
        }
        return out;
    }

    /**
     * Whether {@link #reachable} is in a position to mean anything about this target.
     *
     * It answers yes to anything it cannot work out, which is the right default and makes a yes
     * ambiguous. Asked separately so a log can say which, because "there is a way" and "no idea"
     * point at completely different things.
     */
    public static boolean answerable(GameUI gui, Coord2d target) {
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        WorldAnchor there = WorldAnchor.capture(gui, target);
        return (here != null) && (there != null) && (here.seg == there.seg);
    }

    /**
     * Whether we know of anywhere to STAND near this target.
     *
     * The SECOND way {@link #reachable} answers yes without knowing anything, and the one nothing
     * was watching. {@link #answerable} covers only the first - the map file not being able to
     * place one end - and a run where that fired zero times was read as "every yes was a real
     * yes". It is not: when {@code standableAround} comes back empty, reachable returns true on
     * the deliberate principle that it should not rule a target out for its own ignorance, and
     * that yes is indistinguishable in a log from a route it actually found.
     *
     * This is the common case for anything seen at render distance but never walked near, because
     * standable ground has to have been OBSERVED to count. So the bot proves nothing, chases with
     * the local pathfinder, and walks at whatever is in the way until its attempt budget is gone -
     * which is the shape of the surviving "it tried to path through a solid wall" reports.
     *
     * Asked separately rather than folded into {@code answerable} so a log names which of the two
     * it was: "cannot place it" is a map-file problem and "no known ground beside it" is a
     * not-explored-yet one, and they want opposite responses.
     *
     * @param margin the same reach margin the matching {@link #reachable} call uses - a different
     *               one would answer about a different question.
     */
    public static boolean groundedAround(GameUI gui, Coord2d target, int margin) {
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        WorldAnchor there = WorldAnchor.capture(gui, target);
        if ((here == null) || (there == null) || (here.seg != there.seg))
            return false;
        /* Gateways open here, unlike the reachable call this shadows: whether a tile can be stood
         * on is a fact about the tile, and has nothing to do with what we can get through to
         * reach it. Passing false would make the answer depend on the wrong thing. Keep-out circles
         * are the same kind of transient, so they do not flip the answer either. */
        World w = new World(gui, here.seg, true, true, false);
        return !standableAround(w, there.sc.floor(MCache.tilesz),
            (int) Math.ceil(margin / MCache.tilesz.x), TRIES).isEmpty();
    }

    /**
     * Waypoints from one segment tile to another, or null if there is no route.
     *
     * A null return is not a failure to report - it is the caller's cue to fall back on walking
     * straight at the target, which is what it would have done anyway.
     */
    public static List<Coord> route(GameUI gui, long seg, Coord fromTile, Coord toTile) {
        return search(new World(gui, seg, false), fromTile, toTile, false);
    }

    /**
     * Route clamped to observed ground — UNSEEN tiles are treated as impassable.
     *
     * Unlike {@link #route}, which treats UNSEEN as passable (cost 3) and will plan through
     * ground we have never seen, this method refuses to cross UNSEEN tiles. If the destination
     * cannot be reached through observed ground, it returns the best path to the reachable
     * observed tile nearest the destination (the "vision edge" toward the goal). This prevents
     * the router from "guessing" through unseen ground where invisible walls may hide.
     *
     * The caller must handle the clamped to completion: walk the clamped route, then step toward
     * the destination to observe more, then re-plan.
     */
    public static List<Coord> routeClamped(GameUI gui, long seg, Coord fromTile, Coord toTile) {
        return search(new World(gui, seg, true), fromTile, toTile, true);
    }

    /**
     * Internal search: a full stateless A* over {@code w}, thinned to waypoints exactly once.
     *
     * {@link GridAStar#search} returns the RAW tile path and does no thinning of its own - that job
     * belongs to {@link #simplify} alone, because it is the pass that checks a line at quarter-tile
     * resolution against the character's half-width. Thinning twice, with a coarser test first, used
     * to drop the corner waypoints that check exists to keep.
     */
    private static List<Coord> search(World w, Coord from, Coord to, boolean clamped) {
        if (from.equals(to))
            return new ArrayList<>();

        List<Coord> path = GridAStar.search(w, from, to, clamped);
        if (path != null)
            return simplify(w, path);
        return null;
    }

    /**
     * What a planned route is made of, for the log.
     *
     * Re-walks the simplified route rather than the raw tile path, because the simplified one is
     * what travel will actually be told to walk: a leg is a straight line between waypoints, so a
     * tile the raw path avoided but the straight line crosses is a tile the character will meet.
     *
     * The number that matters is UNSEEN, and how far out it starts. Every report of walking through
     * a palisade or across a river has described it happening just beyond the edge of the screen,
     * and that is precisely where the record stops: unseen ground is passable here on purpose,
     * because the local pathfinder re-checks it on arrival, so a route is entitled to go through it
     * - but a route that is mostly unknown is a guess wearing a plan's clothes, and until now
     * nothing said which one had been produced.
     */
    public static String describe(GameUI gui, long seg, Coord from, Coord to, List<Coord> route) {
        World w = new World(gui, seg, false);
        List<Coord> line = new ArrayList<>();
        Coord at = from;
        for (Coord next : route) {
            trace(line, at, next);
            at = next;
        }
        trace(line, at, to);
        int unseen = 0, unmapped = 0, wet = 0, blocked = 0, tight = 0, firstUnseen = -1;
        for (int i = 0; i < line.size(); i++) {
            Coord t = line.get(i);
            if (w.state(t) == Observed.UNSEEN) {
                unseen++;
                if (firstUnseen < 0)
                    firstUnseen = (int) from.dist(t);
            }
            int c = w.wet(t);
            if (c < 0)
                unmapped++;
            else if ((c == Terrain.DEEP) || ((c == Terrain.SHALLOW)
                && haven.automated.pathfinder.Map.BLOCK_WATER))
                wet++;
            if (!w.passable(t))
                blocked++;
            else if (w.state(t) == Observed.TIGHT)
                tight++;
        }
        int learned = Refused.count(seg);
        return String.format("%d tiles, %d waypoint(s): %d never looked at%s, %d with no map file,"
            + " %d water, %d impassable%s%s%s", line.size(), route.size(), unseen,
            (firstUnseen < 0) ? "" : (" (first " + firstUnseen + "t out)"),
            unmapped, wet, blocked,
            // Named separately from "impassable" because it is the client's opinion rather than
            // anything observed, and a route that changed for this reason should say so.
            (learned == 0) ? "" : (", avoiding " + learned + " tile(s) the client refused"),
            // Said out loud because it is the route taking a squeeze rather than the long way round,
            // which is a decision worth being able to see when one of them goes wrong.
            (tight == 0) ? "" : (", threading " + tight + " tile(s) with no standable middle"),
            detour(w, from, to, line.size()));
    }

    /**
     * What stands on the straight line, when the route taken is meaningfully longer than it.
     *
     * The question every odd-looking route raises is "why not just go straight", and until now the
     * log could not answer it: a detour leaves no trace of the ground it avoided. Naming what is on
     * the direct line - and, when the answer is one of the router's GUESSES rather than something
     * observed, saying so - turns "it would not go through a gap it fits through" from an argument
     * about which rule might have fired into a fact.
     *
     * Only when the detour is real (a fifth longer than the direct line, and at least a few tiles
     * of difference), so an ordinary route round a corner does not carry a paragraph.
     */
    private static String detour(World w, Coord from, Coord to, int taken) {
        int straight = (int) from.dist(to);
        if ((straight <= 0) || (taken < (straight * 6 / 5)) || ((taken - straight) < 4))
            return "";
        java.util.Map<String, Integer> why = new java.util.TreeMap<>();
        List<Coord> line = new ArrayList<>();
        trace(line, from, to);
        for (Coord t : line) {
            String r = w.why(t);
            if (r != null)
                why.merge(r, 1, Integer::sum);
        }
        if (why.isEmpty())
            // Worth saying out loud: a clear direct line and a long route means the detour came from
            // COST rather than from passability - a shut gate, or tiles the client refused.
            return String.format(" [%dt round a %dt direct line that reads clear - cost, not walls]",
                taken, straight);
        StringBuilder sb = new StringBuilder(String.format(" [%dt round a %dt direct line:", taken,
            straight));
        for (java.util.Map.Entry<String, Integer> e : why.entrySet())
            sb.append(' ').append(e.getValue()).append('x').append(' ').append(e.getKey()).append(';');
        return sb.append(']').toString();
    }

    /** Every tile a straight line between two tiles crosses, appended in order. */
    private static void trace(List<Coord> into, Coord a, Coord b) {
        // The same walk the route was validated with, so the log describes the route that was
        // approved rather than a differently-rounded neighbour of it.
        for (Coord t : along(a, b)) {
            if (into.isEmpty() || !into.get(into.size() - 1).equals(t))
                into.add(t);
        }
    }

    /**
     * Drops the waypoints that add nothing.
     *
     * A raw tile path is one waypoint per step, nearly all of them in a straight line, and handing
     * those to travel would reintroduce the stutter this exists to remove - every waypoint costs a
     * pathfinder run. So a waypoint is kept only where the straight line from the last kept one
     * stops being clear, which is precisely where the route turns around something.
     *
     * This also gives travel the guarantee it needs: consecutive waypoints have a clear straight
     * line between them over ground we have seen. The server walks in straight lines, so a leg
     * that is straight and clear is one it can simply be told to walk.
     */
    private static List<Coord> simplify(World w, List<Coord> path) {
        List<Coord> out = new ArrayList<>();
        if (path.size() < 2)
            return out;
        int anchor = 0;
        for (int i = 2; i < path.size(); i++) {
            if (!clear(w, path.get(anchor), path.get(i))) {
                /* Never on a tile with no standable middle.
                 *
                 * A waypoint IS a tile centre - travel aims at one and the server throws away a
                 * click that lands inside an object - so putting one on a tight tile hands the
                 * client a target it must refuse, which is the failure the whole tight/solid split
                 * exists to stop. Crossing such a tile is fine; stopping on it is not. Walk back
                 * along the path to the last tile we could actually stand on, which is always at
                 * worst the anchor itself, since we stood there. */
                int at = i - 1;
                while ((at > anchor) && (w.state(path.get(at)) == Observed.TIGHT))
                    at--;
                /* THE ANCHOR MUST MOVE. Walking back can reach the anchor itself - when every tile
                 * between it and here is tight - and the previous code then re-emitted the anchor,
                 * left {@code anchor} where it was and reset {@code i} to the same place, which is
                 * not "one re-test and terminates" as the note below once claimed but an unbounded
                 * loop appending one coordinate.
                 *
                 * It survived only because tight runs were rare. The moment footprints started
                 * marking partly-covered tiles tight - which is most tiles beside most objects -
                 * it fired: a single logged route came out with 1,173,222 waypoints, all the same
                 * coordinate, an 18MB log line, and a client too busy to do anything else.
                 *
                 * So take i-1 regardless when the walk-back found nothing standable. A waypoint on
                 * a tight tile is a real cost - one click the server may refuse, which travel
                 * recovers from - and it is at least reachable from the anchor in a clear line,
                 * since i is the first index that is not. Guaranteeing progress is worth more than
                 * guaranteeing standable. */
                if (at == anchor)
                    at = Math.max(anchor + 1, i - 1);
                out.add(path.get(at));
                anchor = at;
                /* The scan must not step backwards, or a run of tight tiles re-tests the same pair
                 * for ever. Resuming at the new anchor costs one re-test, and with anchor now
                 * strictly increasing it terminates - out can never exceed path in length. */
                i = at + 1;
            }
        }
        Coord end = path.get(path.size() - 1);
        if (out.isEmpty() || !out.get(out.size() - 1).equals(end))
            out.add(end);
        return out;
    }

    /**
     * Whether a character could walk the straight line between two segment tiles.
     *
     * The same test {@link #simplify} uses to decide where a waypoint is needed, exposed so travel
     * can ask it about the line it is ACTUALLY on rather than the one that was planned. Those come
     * apart whenever a leg stops short of its waypoint, and what matters then is not how far short
     * it stopped but whether the rest of the route still works from there - which is a question
     * about the line, and has no sensible answer in tiles of tolerance.
     */
    public static boolean walkable(GameUI gui, long seg, Coord from, Coord to) {
        return clear(new World(gui, seg, false), from, to);
    }

    /** Whether every tile the straight line between two tiles crosses is passable. */
    private static boolean clear(World w, Coord a, Coord b) {
        for (Coord t : along(a, b)) {
            if (!w.passable(t))
                return false;
        }
        return true;
    }

    /** Samples per tile along a line. Quarter-tile, so no tile a line crosses is stepped over. */
    private static final int SAMPLES = 4;
    /**
     * The character's own half-width, in TILES: three units of eleven.
     *
     * The unit is the whole point and it is why this is not {@code World.HALFWIDTH}. That constant is
     * the same physical fact in WORLD UNITS (3.0); everything on this line is grid arithmetic, where
     * one step is a tile, so the radius has to be expressed as a fraction of one. Substituting the
     * world-unit constant here does not read as a unit error - it compiles, it is the "same" number
     * from the same seam, and it silently models the character as eleven times its real width, which
     * seals every corridor on the map.
     *
     * Derived from {@code pathfinder.World.HALFWIDTH} rather than spelled 3.0 again, so the physical
     * fact still has one home and the conversion is visible at the point of use. Fully qualified
     * because {@link World} inside this file is Router's own grid adapter, not the seam - which is
     * the second way this substitution goes wrong quietly.
     */
    private static final double HALFWIDTH = haven.automated.pathfinder.World.HALFWIDTH / MCache.tilesz.x;

    /**
     * Every tile a character walking between two tile CENTRES would touch.
     *
     * Three things this has to get right, and the version it replaces got none of them.
     *
     * It walked in tile indices with integer division, which TRUNCATES - so for a line that drops
     * one row over thirty-eight columns, every sample landed on the starting row and the row the
     * line actually finishes on was never looked at. On this base that is a leg running west along
     * the outside of the south palisade: the sampled row is the open ground one south of the wall,
     * the real line clips the wall itself, and the route was approved. Then the walk sets off,
     * meets the wall, makes no headway, and re-plans the same leg. That is "went down the wall and
     * tried to path through the palisade", and it is why the route log could honestly report zero
     * impassable tiles about a route that crossed thirty-seven of them.
     *
     * It measured between tile INDICES rather than centres. A waypoint becomes a tile centre when
     * travel converts it back to world coordinates, so the line the character actually walks is the
     * one between centres, offset half a tile from the one that was checked.
     *
     * And it treated the character as a point. A character is six units wide against tiles eleven
     * across, so a line threading the join between two blocked tiles fits on paper and not in the
     * game - which is the same corner-post the gate code keeps meeting, one layer up.
     */
    private static List<Coord> along(Coord a, Coord b) {
        // Insertion-ordered and de-duplicating: consecutive samples land on the same tile over and
        // over, and simplify() calls this once per waypoint candidate, so a linear scan per sample
        // would make the whole pass cubic in route length.
        Set<Coord> out = new LinkedHashSet<>();
        double ax = a.x + 0.5, ay = a.y + 0.5, bx = b.x + 0.5, by = b.y + 0.5;
        int steps = Math.max(1,
            (int) Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(by - ay)) * SAMPLES));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = ax + ((bx - ax) * t), y = ay + ((by - ay) * t);
            int lox = (int) Math.floor(x - HALFWIDTH), hix = (int) Math.floor(x + HALFWIDTH);
            int loy = (int) Math.floor(y - HALFWIDTH), hiy = (int) Math.floor(y + HALFWIDTH);
            for (int ty = loy; ty <= hiy; ty++) {
                for (int tx = lox; tx <= hix; tx++)
                    out.add(new Coord(tx, ty));
            }
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ the world, cached

    /**
     * One segment's walkability, cached per grid for the duration of a search.
     *
     * Worth the class. A tile search asks hundreds of thousands of questions, and both sources
     * behind them take a lock and divide down to a grid on every call; going through that per tile
     * turns a few milliseconds of arithmetic into a second of lock traffic. Here each grid is
     * fetched once.
     */
    public static final class World implements haven.automated.pathfinder.World {
        private final GameUI gui;
        private final long seg;
        /** Refuse ground nobody has seen, rather than charging extra for it. */
        private final boolean strict;
        /**
         * Whether the asker can OPEN a gateway, as opposed to merely walk through an open one.
         *
         * The router has always assumed yes, because the caller it was written for - {@link BotNav} -
         * has a whole gate layer behind it. The LP assistant does not: it chases with the client's
         * own pathfinder, which opens nothing. So "reachable" was answering a question LP was not
         * asking, and answering it yes: four targets in one logged run were felled logs INSIDE the
         * palisade while the bot stood outside it, each ruled reachable through a gateway it had no
         * way to operate, each then walked at the wall until its attempt budget ran out.
         */
        private final boolean opensGates;
        /**
         * The player's world position relative to the segment origin, captured once at
         * construction. The {@link haven.automated.pathfinder.World} seam asks in world
         * coordinates; the {@link Observed} record this grid reads is keyed by segment-relative
         * tiles, so the adapter form of every question converts through this. Null when the
         * position cannot be told (no gui/player/anchor) - the adapter answers refused then.
         */
        private final Coord2d off;
        private final Map<Coord, byte[]> obs = new HashMap<>();
        private final Map<Coord, Object> water = new HashMap<>();
        /** Snapshotted once per search - see {@link Refused#snapshot}. Usually empty. */
        private final java.util.Set<Coord> refused;
        /**
         * Tiles whose centre a loaded gate gob stands on, so the router can treat them as passable
         * even when the Observed record is stale (e.g. recorded before the gate was placed).
         */
        private final Set<Coord> gates;
        /** The subset standing SHUT - the only gateways a route pays anything to cross. */
        private final Set<Coord> shutGates;
        /**
         * Whether to refuse keep-out circles as impassable. Default on - it is the whole point of
         * {@link haven.automated.pathfinder.World#KEEPOUT_PLAYER_RADIUS} existing - and off only for
         * callers asking about a tile in the abstract, where a transient circle must not flip the
         * answer.
         */
        private final boolean avoidKeepouts;
        /**
         * The keep-out circles in force at construction, plus the player's world position. Sampled
         * once, the same discipline as {@link Refused#snapshot} and {@code Map.initGeography}: the
         * global setter can be called from another thread mid-scan, so a route either sees a circle
         * whole or not at all. Any circle covering the bot's OWN start position is dropped, so a ring
         * that wandered onto us can never seal our own routes out of where we are standing.
         */
        private final haven.automated.pathfinder.Map.Keepout[] keepout;
        private final Coord2d pl;
        private static final Object NONE = new Object();

        /** Search box, or null for the whole segment. See {@link #confine}. */
        private Coord blo = null, bhi = null;

        /**
         * Confine every search over this world to a box round the two ends.
         *
         * For a question about somewhere NEARBY, which is the only kind the LP assistant asks. Its
         * targets are things it can see - {@link Observed#SEES} is 44 tiles - so a search ceiling of
         * {@link Router#MAX_TILES}, a 500x500 square, is about thirty times the area the question
         * could possibly need.
         *
         * That costs nothing when the answer is yes: the heuristic walks more or less straight at a
         * reachable goal. It is the NO that is dear, because proving a target unreachable means
         * exhausting everywhere that is reachable first - and after a long session inside a base
         * that is every tile the bot has ever observed. Measured: sixteen to eighteen seconds of
         * frozen client per plan, with the bot standing still holding an undropped item.
         *
         * A box makes running out of room a real answer rather than a timeout, which is exactly why
         * this existed before, was removed as redundant against MAX_TILES, and is back: MAX_TILES
         * bounds a cross-map route sensibly and bounds a question about a tree eight tiles away not
         * at all.
         *
         * NOT for the long-range route methods. {@link Router#route} and {@link Router#clampedRoute}
         * plan journeys across a segment and must keep the whole of it.
         *
         * @param margin how far outside the two ends the search may wander, in TILES - the detour
         *               allowance. Generous on purpose: the cost of being wrong is a reachable
         *               target reported unreachable, and LP RETIRES on that answer.
         */
        void confine(Coord a, Coord b, int margin) {
            blo = new Coord(Math.min(a.x, b.x) - margin, Math.min(a.y, b.y) - margin);
            bhi = new Coord(Math.max(a.x, b.x) + margin, Math.max(a.y, b.y) + margin);
        }

        World(GameUI gui, long seg, boolean strict) {
            this(gui, seg, strict, true);
        }

        /**
         * @param opensGates whether whoever is asking can OPERATE a gateway. False makes a shut
         *                   gateway a wall, which is what it is to a caller that cannot open one.
         */
        World(GameUI gui, long seg, boolean strict, boolean opensGates) {
            this(gui, seg, strict, opensGates, true);
        }

        /**
         * @param avoidKeepouts whether keep-out circles are impassable. Default on; false for a
         *                      question that must answer about a tile in the abstract.
         */
        World(GameUI gui, long seg, boolean strict, boolean opensGates, boolean avoidKeepouts) {
            this.gui = gui;
            this.seg = seg;
            this.strict = strict;
            this.opensGates = opensGates;
            this.avoidKeepouts = avoidKeepouts;
            this.off = offsetOf(gui);
            this.pl = playerPos(gui);
            this.refused = Refused.snapshot(seg);
            this.gates = gateTiles(false);
            this.shutGates = gateTiles(true);
            this.keepout = avoidKeepouts ? snapKeepouts(this.pl) : new haven.automated.pathfinder.Map.Keepout[0];
        }

        /** The player's world position, or null when it cannot be told. */
        private static Coord2d playerPos(GameUI gui) {
            if ((gui == null) || (gui.map == null))
                return null;
            Gob me = gui.map.player();
            return (me == null) ? null : me.rc;
        }

        /** The keep-out circles in force, minus any that cover the bot's own start position. */
        private static haven.automated.pathfinder.Map.Keepout[] snapKeepouts(Coord2d pl) {
            haven.automated.pathfinder.Map.Keepout[] all = haven.automated.pathfinder.Map.keepouts();
            if ((pl == null) || (all.length == 0))
                return all;
            java.util.List<haven.automated.pathfinder.Map.Keepout> out = new java.util.ArrayList<>(all.length);
            for (haven.automated.pathfinder.Map.Keepout k : all) {
                if ((k == null) || (k.r <= 0) || (pl.dist(k.c) <= k.r + haven.automated.pathfinder.World.KEEPOUT_PLAYER_RADIUS))
                    continue;
                out.add(k);
            }
            return out.toArray(new haven.automated.pathfinder.Map.Keepout[0]);
        }

        /** Whether this segment tile lies inside one of the keep-out circles in force at construction. */
        private boolean inKeepout(Coord t) {
            if (!avoidKeepouts || (off == null) || (keepout.length == 0))
                return false;
            /* The tile centre in world coordinates. Segment tiles key by the same pitch as
             * Observed, and off is what the router's world-coordinate seam adds back in -
             * see gateTiles, which does world.add(off).floor(tilesz) the other way. */
            double wx = t.x * MCache.tilesz.x + MCache.tilesz.x / 2.0 - off.x;
            double wy = t.y * MCache.tilesz.y + MCache.tilesz.y / 2.0 - off.y;
            if (pl != null) {
                double pdx = wx - pl.x, pdy = wy - pl.y;
                if ((pdx * pdx) + (pdy * pdy) < haven.automated.pathfinder.World.KEEPOUT_PLAYER_RADIUS * haven.automated.pathfinder.World.KEEPOUT_PLAYER_RADIUS)
                    return false;
            }
            for (haven.automated.pathfinder.Map.Keepout k : keepout) {
                double kx = wx - k.c.x, ky = wy - k.c.y;
                if ((kx * kx) + (ky * ky) < k.r * k.r)
                    return true;
            }
            return false;
        }

        /** The player's world position relative to the segment origin, or null when it cannot be told. */
        private static Coord2d offsetOf(GameUI gui) {
            if ((gui == null) || (gui.map == null))
                return null;
            Gob me = gui.map.player();
            WorldAnchor here = WorldAnchor.capturePlayer(gui);
            if ((me == null) || (here == null))
                return null;
            return here.sc.sub(me.rc);
        }

        /**
         * Every loaded gate gob's tile position, in the SEGMENT-relative tile space that
         * {@link Observed} keys by - not raw world coordinates. Empty when no off is known, which
         * leaves the observed data as the only source.
         *
         * A gob's {@code rc} is a world coordinate; the Observed record for the same tile is keyed
         * by segment-tile. Without the {@code off} offset applied here, this set never intersects
         * the tiles the router asks about and the gate-passable fallback in {@link #passable(Coord)}
         * is dead code. See {@link Observed#observe} for the same idiom.
         */
        private Set<Coord> gateTiles(boolean shutOnly) {
            Set<Coord> out = new HashSet<>();
            if (off == null)
                return out;
            for (Gob g : GateManager.loaded(gui)) {
                if (shutOnly && GateManager.isOpen(g))
                    continue;
                out.add(g.rc.add(off).floor(MCache.tilesz));
            }
            return out;
        }

        private byte state(Coord t) {
            Coord gc = Terrain.floorDiv(t, MCache.cmaps);
            byte[] g = obs.get(gc);
            if (g == null) {
                g = Observed.gridOf(seg, gc);
                if (g == null)
                    g = new byte[MCache.cmaps.x * MCache.cmaps.y];
                obs.put(gc, g);
            }
            Coord in = t.sub(gc.mul(MCache.cmaps));
            return g[(in.y * MCache.cmaps.x) + in.x];
        }

        /** The water class of a tile, or -1 where the map file cannot say. */
        private int wet(Coord t) {
            Coord gc = Terrain.floorDiv(t, MCache.cmaps);
            Object o = water.get(gc);
            if (o == null) {
                byte[] g = Terrain.classes(gui, seg, gc);
                water.put(gc, o = (g == null) ? NONE : g);
            }
            if (o == NONE)
                return -1;
            Coord in = t.sub(gc.mul(MCache.cmaps));
            return ((byte[]) o)[(in.y * MCache.cmaps.x) + in.x];
        }

        /* The record-level questions below are the seam's grid adapter answering for what the
         * RECORD says, not for what the planner decided. The planner's passable() folds gates,
         * strict unseen, water dilation and refusals into its answer; the mover (BotNav) asks
         * different questions - can a disc BE here, can a hop CROSS here - and its answers must
         * not inherit planner decisions. These keep exactly the record's own semantics, so a
         * site that moves onto the seam answers the same way it did when it read Observed and
         * Terrain directly. */

        /**
         * Whether the record says a body stands here: SOLID, WALL or TIGHT.
         *
         * TIGHT included on purpose - every caller of this is asking "can we be here", and a tile
         * with no standable middle answers no. This is exactly what {@link Observed#solid} counts.
         * Gateway tiles are never solid.
         */
        public boolean recordSolid(Coord t) {
            byte s = state(t);
            return (s == Observed.SOLID) || (s == Observed.WALL) || (s == Observed.TIGHT);
        }

        /**
         * Whether the record says a hop cannot cross here: SOLID or WALL, but not TIGHT.
         *
         * The crossing question, and it deliberately excludes TIGHT - a tight tile cannot hold a
         * body but a line can cross it. This is the test {@link BotNav#clearSpan} blocks on.
         */
        public boolean recordBlocking(Coord t) {
            byte s = state(t);
            return (s == Observed.SOLID) || (s == Observed.WALL);
        }

        /** Whether the record says a wall stands here. */
        public boolean recordWall(Coord t) {
            return state(t) == Observed.WALL;
        }

        /** Whether the record says a gateway stands here. */
        public boolean recordGate(Coord t) {
            return state(t) == Observed.GATE;
        }

        /**
         * Whether the map file marks this tile uncrossable water: deep, blocked, or shallow when
         * the mover is avoiding water. This is the terrain half of {@link BotNav#clearSpan}'s
         * block test.
         */
        public boolean waterBlocks(Coord t) {
            int w = wet(t);
            return (w == Terrain.DEEP) || (w == Terrain.BLOCKED)
                || ((w == Terrain.SHALLOW) && haven.automated.pathfinder.Map.BLOCK_WATER);
        }

        /**
         * Why a tile is impassable, in a word, or null when it is not.
         *
         * The router has never been able to answer this, and it is the one question its failures
         * actually raise. A route that goes the long way round leaves NO trace of the ground it
         * refused - it just quietly comes back longer - so "it will not go through a gap it plainly
         * fits through" has no log line at all, and diagnosing it has meant reasoning about which
         * rule MIGHT have fired. There are five candidates and they are not interchangeable: a wall
         * is a fact, a refusal is an inference from one failed click, and the water dilation below
         * is a GUESS about ground the map file has not described yet. Which one sealed a gap decides
         * whether the fix is data, a threshold, or nothing at all.
         *
         * Kept exactly in step with {@link #passable} - same order, same tests - so it can never
         * describe a decision the router did not make.
         */
        public String why(Coord t) {
            byte s = state(t);
            if ((s == Observed.SOLID) || (s == Observed.WALL)) {
                if (gates.contains(t))
                    return null;
                return (s == Observed.WALL) ? "wall" : "solid";
            }
            if (s == Observed.GATE)
                return null;
            if (strict && (s == Observed.UNSEEN))
                return "never looked at";
            int w = wet(t);
            if (w == Terrain.DEEP)
                return "deep water";
            if (w == Terrain.BLOCKED)
                return "rock, cave or void";
            if ((w == Terrain.SHALLOW) && haven.automated.pathfinder.Map.BLOCK_WATER)
                return "shallow water";
            if (w < 0) {
                for (int i = 0; i < 8; i++) {
                    if (wet(t.add(DX[i], DY[i])) == Terrain.DEEP)
                        // Named at length because it is the only one of these that is not evidence.
                        return "no map file here and deep water next to it - assumed wet";
                }
            }
            if (inKeepout(t))
                return "keep-out circle";
            return null;
        }

        /**
         * Whether a character could STOP here, as opposed to merely cross it.
         *
         * Everything that picks a spot rather than a step wants this one: a goal to search toward,
         * a place to stand beside a tree, a waypoint. The difference is {@link Observed#TIGHT} -
         * ground with a walkable channel but nothing standable in the middle, which is passable and
         * is not a destination.
         */
        public boolean standable(Coord t) {
            return passable(t) && (state(t) != Observed.TIGHT);
        }

        /**
         * Whether a gateway tile is something the asker can actually get through.
         *
         * Yes for anyone with a gate layer, which is the historic answer and stays the default. For
         * an asker that cannot open one, only a gateway we can SEE standing open counts - and a
         * gateway out of render counts as shut, because the cost of being wrong that way is one
         * target passed over, while the other way round is what the logs show: walking at a wall
         * until the attempt budget dies.
         */
        private boolean openTo(Coord t) {
            if (opensGates)
                return true;
            return gates.contains(t) && !shutGates.contains(t);
        }

        public boolean passable(Coord t) {
            /* Outside the box, if one was set - see confine(). Tested before anything else because
             * the whole point is to not look at those tiles at all. */
            if ((blo != null)
                    && ((t.x < blo.x) || (t.y < blo.y) || (t.x > bhi.x) || (t.y > bhi.y)))
                return false;
            byte s = state(t);
            /* BOTH of them. Observed keeps walls apart from other solids so that the enclosure
             * inference can reason about walls alone - see Observed.WALL - and routing must not
             * inherit that distinction, because to a character they stop it identically.
             *
             * Testing only SOLID here made every palisade in the world invisible to the router
             * while looking entirely correct: walls were being recorded, the file had them, every
             * other consumer read them through Observed.solid which covers both. Only this one
             * inlined the test and dropped a case. What it produced was a route straight through
             * the south wall with its first waypoint ON a wall tile, which the local pathfinder
             * then refused - correctly - on every hop, which travel read as "a wall is in the way",
             * which sent the gate check off to open an air lock the route never needed. Opening
             * gates, walking into the chamber, shutting itself in, failing to reach water twelve
             * tiles away: all of it downstream of this line. */
            if ((s == Observed.SOLID) || (s == Observed.WALL)) {
                /* A gateway may have been placed after this tile was recorded, so the observed
                 * data still marks it solid. The loaded gob list is a second opinion: a gate
                 * standing here is passable - opening it is the task layer's problem. */
                if (gates.contains(t))
                    return openTo(t);
                return false;
            }
            /* A gateway settles it before the ground is looked at, since a gate is the one place a
             * wall is meant to be walked through. Whether it is open right now is the task layer's
             * problem, not the route's - unless the asker has no task layer. */
            if (s == Observed.GATE)
                return openTo(t);
            /* A refused tile is EXPENSIVE, not impassable - see cost() below.
             *
             * It was impassable here, and that was a bad mistake. A refusal is an inference from one
             * failed click, and making an inference absolute let two of them seal the only way out of
             * where the bot was standing: the router returned null, travel fell back on its single
             * greedy leg aimed at the destination, and that leg crossed fifteen solid tiles. "No
             * route" is the worst answer this can give - it throws away the entire route layer and
             * hands the journey to a straight line - so nothing short of a wall or deep water should
             * ever produce it.
             *
             * Cost achieves what was actually wanted. A* will go a long way round rather than through
             * a refused tile, which is all that was needed to stop it returning the identical route
             * after a failed leg, and it will still go through one when there is genuinely no other
             * way - which is the case where being wrong about the refusal must not be fatal. */
            if (strict && (s == Observed.UNSEEN))
                return false;
            int w = wet(t);
            if (w == Terrain.DEEP)
                return false;
            // Rock, cave mouth and the nil tile - refused unconditionally, like the deep, and for
            // the same reason: no setting makes them crossable. Until this class existed the planner
            // could not see them at all and routed straight through.
            if (w == Terrain.BLOCKED)
                return false;
            if ((w == Terrain.SHALLOW) && haven.automated.pathfinder.Map.BLOCK_WATER)
                return false;
            /* Ground the map file cannot answer for, touching water it can, is taken to be more of
             * the same. Water is contiguous and a river does not end where a character stopped
             * looking, so a bot that walks down one bank until the record runs out and then turns
             * across it is not finding a crossing - it is finding the edge of its own knowledge.
             * One tile of dilation, no more: enough to close that edge, not enough to invent a lake.
             *
             * Keyed on the WATER record being missing, not on the tile being unseen. Those came
             * apart the moment observation became dense: Observed marks everything within sight as
             * open, water included, since it records what stands on the ground rather than what the
             * ground is. So a river in plain view is "seen", and if the map file has not been
             * written for that grid yet - it is written behind the client, so the newest ground is
             * exactly the ground it lacks - the dilation was skipped on the very tiles it existed
             * for, and the crossing came back passable. */
            if (w < 0) {
                for (int i = 0; i < 8; i++) {
                    if (wet(t.add(DX[i], DY[i])) == Terrain.DEEP)
                        return false;
                }
            }
            if (inKeepout(t))
                return false;
            return true;
        }

        public int cost(Coord t) {
            if (refused.contains(t))
                return REFUSED_COST;
            byte s = state(t);
            /* SHUT gateways only, and only ones we can actually see to be shut.
             *
             * A recorded GATE tile says a gateway is there, not whether it is standing open, so
             * charging on the record alone taxed every open door in the base and sent routes the long
             * way round. The live gob is the only thing that knows, so that is what is asked. A
             * gateway out of render costs nothing, which is the right way to be wrong: the worst case
             * is a route that walks up to a shut gate and opens it, which is a gate doing its job. */
            if (shutGates.contains(t))
                return GATE_COST;
            /* Threading a gap is worth doing and worth avoiding when there is room elsewhere. Dear
             * enough that an open lane a few tiles longer wins, cheap enough that it still beats
             * the hundred-tile way round which is what the alternative used to be. */
            if (s == Observed.TIGHT)
                return TIGHT_COST;
            return (s == Observed.UNSEEN) ? UNKNOWN : 1;
        }

        /**
         * The {@link haven.automated.pathfinder.World} seam's world-coordinate form of the tile
         * questions. The grid above answers in segment-relative tiles, which are world coordinates
         * shifted by {@link #off}; every question converts before delegating. When no off could be
         * captured (no player, no anchor) the world is unlocatable and everything is refused.
         */

        /** The seam's question: may a disc centred at {@code wc} STOP here. */
        @Override
        public boolean standable(Coord2d wc) {
            if (off == null)
                return false;
            return standable(wc.add(off).floor(MCache.tilesz));
        }

        /** The seam's question: may a route CROSS here. */
        @Override
        public boolean passable(Coord2d wc) {
            if (off == null)
                return false;
            return passable(wc.add(off).floor(MCache.tilesz));
        }

        /** The seam's question: routing cost through here. Never called on an impassable point. */
        @Override
        public int cost(Coord2d wc) {
            if (off == null)
                return Integer.MAX_VALUE / 2;
            return cost(wc.add(off).floor(MCache.tilesz));
        }

        /** The seam's question: why this point was refused, or null when nothing objects. */
        @Override
        public String why(Coord2d wc) {
            if (off == null)
                return "no player position";
            return why(wc.add(off).floor(MCache.tilesz));
        }
    }
}
