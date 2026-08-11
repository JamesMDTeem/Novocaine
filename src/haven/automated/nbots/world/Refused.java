package haven.automated.nbots.world;

import haven.Coord;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Tiles the LOCAL pathfinder has refused to walk to while our own record insisted they were clear.
 *
 * This exists because of one failure that nothing else in the stack can break out of. {@link
 * Observed} deliberately under-records free-placed objects - a stockpile or a barrel dropped
 * anywhere clips slivers off two to four tiles, and blocking all of them seals gaps a character
 * walks straight through, so it keeps only the tiles whose CENTRE a character could not stand on.
 * Under-blocking is the safe direction, and the stated reason it is safe is that the local
 * pathfinder does exact geometry on arrival.
 *
 * That reasoning holds everywhere except where it matters most: when the unrecorded object sits on
 * a tile A* chose as a WAYPOINT. Then the local pathfinder cannot route to it, travel fails the
 * leg, travel re-plans - and re-planning consults the same record, which still says the tile is
 * open, so it returns the identical route. Four times, then the shift dies. The log shape is
 * unmistakable and it is the same three lines over and over: {@code no way from here to there},
 * {@code seen=open ground=dry box=none}, {@code 0 solid; nothing in the way}.
 *
 * So this is the client's own opinion, remembered long enough to change the next answer. It is
 * NOT written to {@code botmap.json} and never shared with the crew, deliberately: it is an
 * inference from a refusal rather than something anybody looked at, and the shared file's whole
 * merge rule is that a tile is written only by a client that observed it. An entry that is wrong
 * costs one detour; an entry that is wrong in the shared file would outlive the object and be
 * handed to every other bot.
 *
 * Entries expire, because the object that caused the refusal is furniture: stockpiles get taken
 * apart and carts get driven away, and a tile refused once should not be refused for the rest of
 * the session.
 */
public class Refused {
    /**
     * How long a refusal is believed for.
     *
     * Long enough to outlast the re-plan it exists to change - travel re-plans within
     * milliseconds - and short enough that furniture being moved fixes itself without a restart.
     */
    private static final long KEEP = 90_000;

    private static final Map<Long, Map<Coord, Long>> refused = new HashMap<>();

    /**
     * Records that the pathfinder would not take us to this tile.
     *
     * The caller is responsible for establishing that our record disagreed - see {@link
     * haven.automated.nbots.world.Router#walkable}. A refusal our record already explains teaches
     * nothing, and a refusal caused by a shut gateway must never land here: gate tiles are passable
     * to the router on purpose, and noting one would make the router plan around the only place a
     * wall is meant to be crossed.
     */
    public static synchronized void note(long seg, Coord segTile) {
        if (segTile == null)
            return;
        refused.computeIfAbsent(seg, k -> new HashMap<>())
            .put(segTile, System.currentTimeMillis() + KEEP);
    }

    /** How many refusals are currently believed, for the route description to report. */
    public static synchronized int count(long seg) {
        Map<Coord, Long> m = refused.get(seg);
        if (m == null)
            return 0;
        sweep(m);
        return m.size();
    }

    /**
     * The live set, copied once, for a search to consult without taking this lock per tile.
     *
     * A* asks about hundreds of tiles per search and several bots search at once, so a synchronized
     * lookup in that inner loop is the same mistake the per-tile locking in {@link Observed} and
     * {@link Terrain} already cost once - which is why {@code Router.World} caches everything else
     * per search too. There are only ever a handful of these, so copying is nothing, and a snapshot
     * that goes stale mid-search is not a problem: the whole point is that the answer only has to
     * change between one route and the next.
     */
    public static synchronized Set<Coord> snapshot(long seg) {
        Map<Coord, Long> m = refused.get(seg);
        if ((m == null) || m.isEmpty())
            return Collections.emptySet();
        sweep(m);
        return new HashSet<>(m.keySet());
    }

    private static void sweep(Map<Coord, Long> m) {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Coord, Long>> it = m.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Coord, Long> entry = it.next();
            if (entry.getValue() < now) {
                it.remove();
            }
        }
    }
}
