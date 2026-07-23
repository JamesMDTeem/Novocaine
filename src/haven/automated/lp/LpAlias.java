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

    public boolean matches(String itemName) {
        return itemName != null && names.contains(itemName);
    }
}
