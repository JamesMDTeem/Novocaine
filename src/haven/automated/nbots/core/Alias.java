package haven.automated.nbots.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A named group of names, matched loosely - the one place this fork decides whether a string the
 * game gave us is "an axe", "a stone", "water".
 *
 * Bots need this constantly and for two different kinds of string, which is why it handles both:
 *
 * - ITEM names, taken from tooltips ("Butcher's Cleaver", "Waterskin"). Hand-transcribed, so
 *   matching is case-insensitive: one miscapitalised letter silently turns a tool the character IS
 *   holding into one the bot thinks it hasn't got.
 * - RESOURCE names, taken from the game data ("gfx/invobjs/small/shovel-m",
 *   "gfx/terobjs/bumlings/granite3"). These carry material and variant suffixes, so matching on a
 *   FRAGMENT is what makes "any shovel" expressible at all.
 *
 * Hence two predicates rather than one. {@link #matches} is the whole-string, case-insensitive
 * comparison for item names; {@link #matchesPart} is the substring test for resource names and for
 * user-written rules. Keeping them separate stops "Stone" from matching "Stone Axe" when what was
 * meant was the item, and stops "/shovel" from failing when what was meant was the resource.
 *
 * nurgling2 splits the same job across NAlias plus a handful of NParser entry points; folding it
 * into one type with two clearly-named predicates keeps call sites readable about which they want.
 */
public class Alias {
    public final String name;
    public final List<String> names;

    /**
     * An unnamed alias. A static factory rather than an {@code Alias(String...)} constructor,
     * which would be ambiguous with the named form below for every single-argument call - and
     * silently pick the wrong one for two-argument calls.
     */
    public static Alias of(String... names) {
        return new Alias(null, names);
    }

    public Alias(String name, String... names) {
        this.name = name;
        this.names = Collections.unmodifiableList(Arrays.asList(names));
    }

    public Alias(String name, List<String> names) {
        this.name = name;
        this.names = Collections.unmodifiableList(new ArrayList<>(names));
    }

    /** Whole-string, case-insensitive. For item names as the game displays them. */
    public boolean matches(String s) {
        if (s == null)
            return false;
        for (String n : names) {
            if (n.equalsIgnoreCase(s))
                return true;
        }
        return false;
    }

    /**
     * Substring, case-insensitive. For resource names, which carry material and variant suffixes,
     * and for user-written rules where "stone" should catch every kind of stone.
     */
    public boolean matchesPart(String s) {
        if (s == null)
            return false;
        String lower = s.toLowerCase();
        for (String n : names) {
            if (lower.contains(n.toLowerCase()))
                return true;
        }
        return false;
    }

    /** Either test - used by place rules, where the player may have typed either kind of name. */
    public boolean matchesEither(String s) {
        return matches(s) || matchesPart(s);
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }

    /** Comma-separated, for storing a user-entered rule and showing it back to them. */
    public String store() {
        return String.join(", ", names);
    }

    public static Alias parse(String name, String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String t = part.trim();
                if (!t.isEmpty())
                    out.add(t);
            }
        }
        return new Alias(name, out);
    }

    public String toString() {
        return (name == null) ? names.toString() : (name + names);
    }
}
