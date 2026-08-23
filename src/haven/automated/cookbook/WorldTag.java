package haven.automated.cookbook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The world the current session is playing in, for tagging cookbook uploads.
 *
 * The server namespaces the cookbook per world, because one account can have characters in
 * several live worlds at once and a dish's values are only meaningful within the world it was
 * cooked in. Nothing in the game session says which world a character is in - but the
 * character-selection screen does: the server sends a per-character discriminator as the
 * {@code srv} message on {@link haven.Charlist}, which is the small label shown under a
 * character's name whenever an account's characters are not all in the same world.
 *
 * {@link #set} is called with that discriminator at the moment a character is played, so the
 * tag is settled before any food can be inspected.
 *
 * <h2>Why the discriminator is normalized rather than sent as-is</h2>
 *
 * The tag has to match the "W16.2" form the server already stores, or an upload opens a fresh,
 * empty namespace and the existing catalog looks like it vanished. The display string is the
 * server's to choose and has changed shape before, so anything world-shaped is reduced to
 * {@code W<number>} and anything unrecognized is treated as unknown rather than guessed at.
 *
 * <h2>Unknown means "say nothing", not "invent something"</h2>
 *
 * {@link #current()} returns null when no character has been played yet, when the server sent
 * no discriminator (a single-world account, where the label is hidden because there is nothing
 * to disambiguate), or when the string did not look like a world. Callers then omit the tag
 * entirely and the server falls back to its own configured world - which is exactly the
 * behavior from before any of this existed. A wrong guess would silently split the catalog in
 * two; staying quiet cannot.
 */
public class WorldTag {
    /**
     * Matches a world designation anywhere in the discriminator: "World 16.2", "W16.2",
     * "w 16", "Hafen World 16" all yield the number. Anchoring is deliberately loose because
     * the server decides this string's shape, not us.
     */
    private static final Pattern WORLD_PATTERN =
            Pattern.compile("\\bw(?:orld)?\\s*\\.?\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    /** Written from the UI thread on character select, read from the upload scheduler. */
    private static volatile String current = null;

    /** The raw discriminator, kept only so the Options panel can explain an unrecognized one. */
    private static volatile String rawDisc = null;

    private WorldTag() {
    }

    /**
     * Records the world of the character being played, from its character-screen
     * discriminator. A null or unrecognized value clears the tag rather than keeping the
     * previous character's - logging in as a character whose world we cannot identify must
     * not file its food under the world of the last character we could.
     */
    public static void set(String disc) {
        rawDisc = disc;
        current = normalize(disc);
    }

    /** The current session's world tag, or null when it is not known - see the class note. */
    public static String current() {
        return current;
    }

    /**
     * What the character screen actually said, or null if it said nothing. Only for showing
     * the player why a discriminator was not recognized; never use it as a tag.
     */
    public static String rawDisc() {
        return rawDisc;
    }

    /**
     * Reduces a character-screen discriminator to the server's world form, or null when it
     * does not look like a world at all.
     */
    static String normalize(String disc) {
        if (disc == null) {
            return null;
        }
        Matcher matcher = WORLD_PATTERN.matcher(disc);
        if (!matcher.find()) {
            return null;
        }
        return "W" + matcher.group(1);
    }
}
