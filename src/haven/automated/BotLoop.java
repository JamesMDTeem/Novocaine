package haven.automated;

import haven.GameUI;
import haven.Gob;
import haven.Resource;

import java.util.function.Predicate;

/**
 * The shared seam for the one-shot bots that walk up to a gob, take something and click it.
 *
 * Three "Add" bots (AddBranchesToFurnace, AddCoalToSmelter, AddWoodBlocksToSmokeShed) were the
 * same program with the gob name, the item name and the error strings substituted; the vitals
 * blocks and the belt scans that those bots duplicate are also written out in the older bot
 * classes. The crew bots model the shape this seam points at in {@code nbots/NBot}'s run loop -
 * everything a bot does is a step in a loop that survives a thrown task - but the one-shots stay
 * Runnable, so the seam is static helpers rather than a base class.
 *
 * A new one-shot bot writes its target and item predicates here in one place and a thin Runnable
 * wrapper, instead of another 97-line copy. Only the pieces a caller actually needs exist; the
 * rest of the vitals migrate up as their bots do.
 */
public final class BotLoop {
    private BotLoop() {
    }

    /**
     * The gob nearest to the player whose resource passes {@code matches}, or null if none does.
     *
     * The scan runs under the object-cache lock, like every other walk of the gob list, and takes
     * the nearest by distance to the player - the same rule the "Add" bots used, and the one that
     * keeps the click reachable without a pathfinder hop.
     */
    public static Gob nearestGob(GameUI gui, Predicate<Resource> matches) {
        synchronized (gui.map.glob.oc) {
            Gob found = null;
            for (Gob gob : gui.map.glob.oc) {
                Resource res = gob.getres();
                if (res == null || !matches.test(res))
                    continue;
                if (found == null || gob.rc.dist(gui.map.player().rc) < found.rc.dist(gui.map.player().rc))
                    found = gob;
            }
            return found;
        }
    }
}
