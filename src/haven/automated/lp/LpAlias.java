package haven.automated.lp;

import haven.automated.nbots.core.Alias;

/**
 * A named group of item names (e.g. every axe the game calls an axe) - what LpTask uses to say
 * "this task first needs one of these equipped".
 *
 * Now just the LP assistant's name for the shared {@link Alias}. It was written before the bot
 * framework existed and had its own copy of the same case-insensitive matching; keeping the name
 * (LpTask and LpTargets read better for it) while inheriting the behaviour means there is one
 * matcher in the fork rather than two that could drift.
 */
public class LpAlias extends Alias {
    public LpAlias(String... names) {
        super(null, names);
    }
}
