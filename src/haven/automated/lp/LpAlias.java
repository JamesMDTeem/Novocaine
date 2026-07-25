package haven.automated.lp;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A named group of item names (e.g. every axe the game calls an axe) - the minimal stand-in for
 * nurgling2's NAlias, which LpTask uses to say "this task first needs one of these equipped".
 */
public class LpAlias {
    public final List<String> names;

    public LpAlias(String... names) {
        this.names = Collections.unmodifiableList(Arrays.asList(names));
    }

    // Case-insensitive: these names are hand-transcribed from the game's own item tooltips, where a
    // single miscapitalized letter ("Butcher's cleaver" vs "Butcher's Cleaver") silently turns a
    // tool the player IS holding into one the bot thinks it hasn't got, and retires the task.
    public boolean matches(String itemName) {
        if (itemName == null)
            return false;
        for (String name : names) {
            if (name.equalsIgnoreCase(itemName))
                return true;
        }
        return false;
    }
}
