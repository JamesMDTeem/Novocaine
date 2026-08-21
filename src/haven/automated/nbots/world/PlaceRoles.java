package haven.automated.nbots.world;

import java.util.Arrays;
import java.util.List;

/**
 * The roles the bots shipped so far understand, and what each one promises.
 *
 * Roles are plain strings, not an enum, and this class is a list of the well-known ones rather than
 * the definition of what is allowed. That is the point: a bot added next month can look for a role
 * nothing here has heard of, and it will work - the place manager simply shows whatever roles are
 * actually in use alongside these. nurgling2's equivalent is a sixty-entry enum that a new kind of
 * place has to be added to in three files before it can exist at all.
 *
 * What each role means is a contract between whoever tags the place and the bot that looks for it,
 * so it is written down here rather than left implied.
 */
public class PlaceRoles {
    /**
     * Somewhere carried water containers can be filled: a water barrel, or a stretch of fresh
     * water to wade into. NOT somewhere to drink from directly - you drink from what you carry.
     */
    public static final String WATER = "water";

    /**
     * Somewhere with food a bot may eat. Whatever is in the containers here is fair game, so this
     * is how you decide what counts as bot fodder - by what you put in it, not by a rule the bot
     * has to guess at.
     */
    public static final String FOOD = "food";

    /** Somewhere tools are kept, for a bot that needs one it isn't carrying. */
    public static final String TOOLS = "tools";

    /**
     * Somewhere to leave output. A cleanup crew's stone, a digger's spoil. Pair it with an
     * `accepts` rule when one dump is for one kind of thing.
     */
    public static final String DUMP = "dump";

    /** General storage, read and write, filtered by the place's own accepts/provides rules. */
    public static final String STORE = "store";

    /**
     * The area a bot should confine itself to. A cleanup bot given one of these clears inside it
     * and nothing outside, which is both how you split a site between a crew and how you stop a
     * bot wandering off after one more tree.
     */
    public static final String WORK = "work";

    public static final List<String> KNOWN =
        Arrays.asList(WATER, FOOD, TOOLS, DUMP, STORE, WORK);

    /**
     * Whether a place carrying this role is one-bot-at-a-time purely by virtue of the role.
     *
     * Every role shipped so far says no, and that is a decision rather than an oversight. Sharing
     * is the common case and the one that degrades gracefully: two bots at the same water barrel
     * queue for it, two bots clearing one big area simply cover it faster, and the pathfinder
     * already keeps them out of each other's way. Locking those by default would idle a crew for
     * no gain.
     *
     * The cases that genuinely break under two workers - surveying is the one we have - are a
     * property of the BOT, not of the area, which is why {@link Places#claim} takes the bot's own
     * requirement as well. WORK in particular cannot carry the answer: a survey area and a cleanup
     * area are both tagged WORK, and only one of them minds company.
     *
     * A player who wants a specific area held by one bot regardless sets the per-place override
     * ({@link Place#exclusive}), which beats this either way.
     */
    public static boolean exclusiveByDefault(String role) {
        return false;
    }

    private PlaceRoles() {}
}
