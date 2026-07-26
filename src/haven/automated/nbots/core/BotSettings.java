package haven.automated.nbots.core;

import haven.CheckBox;
import haven.Coord;
import haven.GOut;
import haven.Label;
import haven.OldDropBox;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.Utils;
import haven.Widget;

import java.util.ArrayList;
import java.util.List;

/**
 * A bot's settings, declared once and rendered automatically.
 *
 * Every bot so far has hand-written the same three things per option: a field, an anonymous
 * CheckBox subclass overriding set() to assign the field, and an add() at hand-computed
 * coordinates. That is three chances to get it wrong per option, and none of it is persisted
 * unless the bot also remembers to write a pref. Declaring a setting here gives all of it:
 *
 *   flag("trees", "Trees", false);            // persisted, readable as on("trees")
 *   number("radius", "Work radius", 300);     // persisted, readable as num("radius")
 *   ...
 *   layout(window, UI.scale(10, 22));         // widgets appear, in order
 *
 * Prefs are keyed per BOT ("nbot.cleanup.trees"), so two bots can both have a "radius" without
 * colliding, and a setting survives closing the window.
 *
 * Deliberately not nurgling2's Map<String,Object> settings bag passed to a constructor: that gets
 * the bot parameterisable (which matters for chaining bots into a scenario later) but gives up
 * the declaration site, so nothing knows a setting's label, type or default without reading the
 * bot's body. Here the declaration is the single source for the pref key, the default, the
 * widget and the label - and reading them out by name keeps the scenario door open.
 */
public class BotSettings {
    private final String botid;
    private final List<Entry> entries = new ArrayList<>();

    public BotSettings(String botid) {
        this.botid = botid;
    }

    private abstract static class Entry {
        final String key, label;

        Entry(String key, String label) {
            this.key = key;
            this.label = label;
        }

        abstract Widget widget(BotSettings owner);

        /** Height this entry occupies when laid out, in scaled pixels. */
        abstract int height();
    }

    private static class Flag extends Entry {
        final boolean def;

        Flag(String key, String label, boolean def) {
            super(key, label);
            this.def = def;
        }

        Widget widget(BotSettings owner) {
            return new CheckBox(label) {
                {
                    a = owner.on(key);
                }

                public void set(boolean val) {
                    owner.set(key, val);
                    a = val;
                }
            };
        }

        int height() {
            return UI.scale(20);
        }
    }

    private static class Number extends Entry {
        final int def;

        Number(String key, String label, int def) {
            super(key, label);
            this.def = def;
        }

        Widget widget(BotSettings owner) {
            return new TextEntry(UI.scale(60), Integer.toString(owner.num(key))) {
                @Override
                public void changed() {
                    try {
                        int v = Integer.parseInt(text().trim());
                        if (v >= 0)
                            owner.set(key, v);
                    } catch (NumberFormatException ignored) {
                        // Half-typed numbers are normal while editing; keep the last good value
                        // rather than resetting the field under the player's cursor.
                    }
                }
            };
        }

        int height() {
            return UI.scale(24);
        }
    }

    /**
     * One of several named alternatives, persisted by INDEX.
     *
     * By index rather than by label so that renaming an option in the source - "Work an area" to
     * "Inside an area", say - doesn't silently reset every player's setting to the default, which
     * storing the string would. The cost is that reordering the options does exactly that instead,
     * and reordering is a rarer edit than rewording.
     */
    private static class Choice extends Entry {
        final String[] options;
        final int def;

        Choice(String key, String label, int def, String[] options) {
            super(key, label);
            this.options = options;
            this.def = def;
        }

        Widget widget(BotSettings owner) {
            return new OldDropBox<String>(UI.scale(132), options.length, UI.scale(17)) {
                {
                    super.change(options[owner.pick(key)]);
                }

                protected String listitem(int i) {
                    return options[i];
                }

                protected int listitems() {
                    return options.length;
                }

                protected void drawitem(GOut g, String item, int i) {
                    g.aimage(Text.renderstroked(item).tex(),
                        Coord.of(UI.scale(3), g.sz().y / 2), 0.0, 0.5);
                }

                public void change(String item) {
                    super.change(item);
                    for (int i = 0; i < options.length; i++) {
                        if (options[i].equals(item))
                            owner.set(key, i);
                    }
                }
            };
        }

        int height() {
            return UI.scale(38);
        }
    }

    public BotSettings flag(String key, String label, boolean def) {
        entries.add(new Flag(key, label, def));
        return this;
    }

    public BotSettings number(String key, String label, int def) {
        entries.add(new Number(key, label, def));
        return this;
    }

    public BotSettings choice(String key, String label, int def, String... options) {
        entries.add(new Choice(key, label, def, options));
        return this;
    }

    // ------------------------------------------------------------------ values

    private String prefkey(String key) {
        return "nbot." + botid + "." + key;
    }

    public boolean on(String key) {
        for (Entry e : entries) {
            if (e.key.equals(key) && e instanceof Flag)
                return Utils.getprefb(prefkey(key), ((Flag) e).def);
        }
        return false;
    }

    public int num(String key) {
        for (Entry e : entries) {
            if (e.key.equals(key) && e instanceof Number)
                return Utils.getprefi(prefkey(key), ((Number) e).def);
        }
        return 0;
    }

    /**
     * The index currently selected for a choice.
     *
     * Clamped on read rather than trusted: the stored value is an index into a list that a later
     * version of the bot may have shortened, and an out-of-range read here would throw inside a
     * widget constructor - which in this client means taking the UI thread down with it.
     */
    public int pick(String key) {
        for (Entry e : entries) {
            if (e.key.equals(key) && e instanceof Choice) {
                Choice c = (Choice) e;
                int v = Utils.getprefi(prefkey(key), c.def);
                return (v < 0 || v >= c.options.length) ? c.def : v;
            }
        }
        return 0;
    }

    /** True if a choice is currently on the given option index. Reads better at the call site. */
    public boolean picked(String key, int option) {
        return pick(key) == option;
    }

    public void set(String key, boolean value) {
        Utils.setprefb(prefkey(key), value);
    }

    public void set(String key, int value) {
        Utils.setprefi(prefkey(key), value);
    }

    /** True if any of the named flags is on - the "did you tick anything at all" check. */
    public boolean anyOn(String... keys) {
        for (String k : keys) {
            if (on(k))
                return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ layout

    /**
     * Adds every declared setting to a window, stacked from {@code at} downwards in two columns.
     *
     * @return the y coordinate below the last row, so the caller can place its own widgets after.
     */
    public int layout(Widget parent, Coord at, int columns, int colwidth) {
        int perColumn = (entries.size() + columns - 1) / columns;
        int maxy = at.y;
        /* Accumulated rather than row-index times height. Multiplying only ever worked because
         * every setting was a checkbox and so the same height as every other; the first entry of a
         * different size - a dropdown, say - would have had every row below it computed from ITS
         * height, stacking widgets on top of each other. */
        int y = at.y;
        for (int i = 0; i < entries.size(); i++) {
            if ((i % perColumn) == 0)
                y = at.y;
            Entry e = entries.get(i);
            int col = i / perColumn;
            int x = at.x + col * colwidth;
            if (e instanceof Number) {
                // A number needs its own label, since a text field has nowhere to put one.
                parent.add(new Label(e.label), new Coord(x, y + UI.scale(3)));
                parent.add(e.widget(this), new Coord(x + labelWidth(e.label), y));
            } else if (e instanceof Choice) {
                // A dropbox is as wide as its widest option, so its label goes ABOVE it rather
                // than beside it - side by side, a long option name pushes the box off the window.
                parent.add(new Label(e.label), new Coord(x, y));
                parent.add(e.widget(this), new Coord(x, y + UI.scale(16)));
            } else {
                parent.add(e.widget(this), new Coord(x, y));
            }
            y += e.height();
            maxy = Math.max(maxy, y);
        }
        return maxy;
    }

    private static int labelWidth(String text) {
        // Text.std would give the exact width, but that needs a render pass; a per-character
        // estimate is enough to keep a field clear of its label and costs nothing.
        return UI.scale(8 + text.length() * 6);
    }
}
