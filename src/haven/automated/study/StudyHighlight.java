package haven.automated.study;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which curiosities the current study plan wants taken, so they can be picked out of a wall of
 * containers by eye instead of by reading names off the plan and hunting for them.
 *
 * A plain static set rather than something the containers subscribe to: the reader is
 * {@link haven.WItem#draw}, which runs for every item in every open container on every frame and
 * cannot afford to walk anything. One volatile reference and a hash lookup is the whole cost.
 *
 * <h2>Names, not items</h2>
 * The plan selects a <em>kind</em> of curiosity, never a particular copy -- the game refuses to
 * study two of a kind at once, so seven Toy Chariots still means studying exactly one. Highlighting
 * by name therefore lights up every copy, which is the useful behaviour when they are spread over
 * six chests: any one of them will do, take whichever is nearest. How many to take is the plan's
 * business and the plan says one per row.
 *
 * The set is published by {@link StudyHelperWindow} and emptied whenever it stops having a plan --
 * on disable, on close, and when there is no container in reach. A highlight that outlives the plan
 * it came from is worse than none, because it looks equally authoritative.
 */
public class StudyHighlight {
    /** Empty unless a study plan is currently on screen. Replaced wholesale, never mutated. */
    private static volatile Set<String> wanted = Collections.emptySet();

    private StudyHighlight() {
    }

    /** Replaces the highlighted set. A null or empty collection turns highlighting off. */
    public static void set(Iterable<String> names) {
        if (names == null) {
            wanted = Collections.emptySet();
            return;
        }
        Set<String> next = new HashSet<String>();
        for (String n : names) {
            if (n != null && !n.isEmpty())
                next.add(n);
        }
        wanted = next.isEmpty() ? Collections.<String>emptySet() : next;
    }

    public static void clear() {
        wanted = Collections.emptySet();
    }

    /** True when nothing is highlighted, so callers can skip the work entirely. */
    public static boolean idle() {
        return wanted.isEmpty();
    }

    public static boolean wants(String itemName) {
        return itemName != null && wanted.contains(itemName);
    }
}
