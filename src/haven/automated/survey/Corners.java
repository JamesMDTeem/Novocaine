package haven.automated.survey;

import haven.Area;
import haven.Coord;

/**
 * The two corner marks of a region being picked out on foot, and the rule for pressing them.
 *
 * Its own class for two reasons, one structural and one earned. The structural one: this is the
 * only part of choosing a region that is pure data, and keeping it free of every widget import is
 * what lets {@link SurveyPlannerCheck} drive it - the check harness runs against {@code
 * build/classes} with no game attached, and merely naming a {@link haven.Window} from it pulls in
 * {@code Resource}'s static initialiser and a library that is not there.
 *
 * <p>The earned one: the press rule broke twice. Corners were derived from whatever region had
 * last been set, so a region arriving from a map drag - or from a previous pair - left BOTH marks
 * filled in, and the next press of "corner A" landed on a pair that already looked complete. It
 * therefore paired where the player was standing with a corner set for something else. While
 * completing a pair merely recorded a region that was easy to overrule, that was survivable; once
 * the second corner started planning immediately, the same mistake planned a nonsense region on
 * what the player experienced as the FIRST press.
 *
 * <p>So the rule is stated once, here, and nothing else writes these fields: a complete pair is a
 * FINISHED selection, and pressing either corner against one starts a new selection rather than
 * extending the old.
 */
public class Corners {
    public Coord a, b;

    /** Both corners marked - a selection that is finished, not one press short of a new one. */
    public boolean complete() {
        return (a != null) && (b != null);
    }

    /** Exactly one corner marked: a selection part-way through. */
    public boolean partial() {
        return (a == null) != (b == null);
    }

    public void clear() {
        a = b = null;
    }

    /** Marks a corner at {@code tc}, starting over when the pair was already complete. */
    public void press(boolean first, Coord tc) {
        if (complete())
            clear();
        if (first)
            a = tc;
        else
            b = tc;
    }

    /**
     * The region the pair describes, or null while it is incomplete.
     *
     * Inclusive of both marked tiles, hence the {@code +1}: a player standing ON a corner means
     * that tile to be in the region, and {@link Area}'s {@code br} is exclusive. This is the
     * opposite of the map drag, which arrives as an Area whose {@code br} is exclusive already and
     * must NOT be adjusted.
     */
    public Area region() {
        if (!complete())
            return null;
        return Area.corn(a.min(b), a.max(b).add(1, 1));
    }
}
