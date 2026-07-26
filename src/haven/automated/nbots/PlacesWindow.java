package haven.automated.nbots;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.Label;
import haven.MCache;
import haven.OldDropBox;
import haven.Scrollport;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.helpers.AreaSelectCallback;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.world.ItemGroups;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceOverlay;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;
import haven.automated.nbots.world.WorldAnchor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defining and tagging the places bots use.
 *
 * Rebuilt as a two-pane window, because the flat list it started as does not survive success. Every
 * place carried its whole editor - roles, both item rules, a delete button - so three places filled
 * the window and ten made it unusable, and the thing you actually do most often, which is glance
 * down the list to see what exists, was the thing it was worst at. Now the list is a list, and one
 * selected place gets the space to be edited properly.
 *
 * The area itself is still drawn on the MAP rather than typed in: Hurricane carries a drag-select
 * rectangle ({@link haven.MapView#registerAreaSelect}), so "Draw on map" hands control to that and
 * takes the rectangle back. That API had never actually been driven - nothing in the client set
 * MapView.areaSelect, so registering a callback alone armed nothing, and unregistering without a
 * live selector threw. Both are handled here and in the guard in MapView.unregisterAreaSelect.
 *
 * Three things the flat version had no room for, each of which existed to answer a question that
 * previously cost a bot run to answer:
 *
 * - SHOW ON GROUND. The commonest mistake is a rectangle that looks right and stops one tile short
 *   of the barrels. See {@link PlaceOverlay}.
 * - WHAT'S INSIDE. A live count of the objects the place actually contains, which is the same
 *   question from the other side and is answerable without walking anywhere.
 * - ITEM GROUPS. "All prepared hides" as one pick rather than eleven typed words. See
 *   {@link ItemGroups}.
 */
public class PlacesWindow extends Window {
    private static final Coord WSZ = UI.scale(560, 430);
    private static final int LISTW = UI.scale(170);

    private final GameUI gui;
    private final Scrollport list;
    private final Widget detail;
    private final Label hint;
    private final TextEntry newName;

    /** The rectangle most recently drawn, waiting for a name. */
    private Coord pendingA, pendingB;
    /** Whether a drag we armed is still outstanding. */
    private boolean drawing;
    /** The place whose editor is on the right, by name - not by reference, since rows are rebuilt. */
    private String selected;

    /** Counts down to the next contents refresh, in seconds. */
    private double recount = 0;
    /**
     * Set by a button that wants the panels rebuilt; acted on at the start of the next tick.
     *
     * Every one of those buttons is INSIDE a panel, so rebuilding where it is asked for would
     * destroy the widget currently dispatching the click and leave the event system holding a
     * detached object. Nothing in this client survives that reliably - it is the same shape as the
     * two white-screens already found in this window - and a one-frame delay is invisible.
     */
    private boolean rebuild = false;

    public PlacesWindow(GameUI gui) {
        super(WSZ, "Bot Places");
        this.gui = gui;

        int y = 4;
        add(new Label("Where the bots go for water, food, tools, storage and work."), UI.scale(6, y));
        y += 18;
        hint = add(new Label(""), UI.scale(6, y));
        y += 20;

        add(new Button(UI.scale(96), "Draw on map") {
            @Override
            public void click() {
                startDraw();
            }
        }, UI.scale(6, y));
        newName = add(new TextEntry(UI.scale(150), ""), UI.scale(108, y));
        add(new Button(UI.scale(52), "Add") {
            @Override
            public void click() {
                addPending();
            }
        }, UI.scale(264, y));
        add(new CheckBox("Show ticked areas on the ground") {
            {
                a = NBotConfig.on(NBotConfig.Key.showAreas);
            }

            public void set(boolean val) {
                NBotConfig.set(NBotConfig.Key.showAreas, val);
                a = val;
                if (!val)
                    PlaceOverlay.clear(PlacesWindow.this.gui);
            }
        }, UI.scale(328, y + 3));
        y += 28;

        list = add(new Scrollport(new Coord(LISTW, WSZ.y - UI.scale(y + 14))), UI.scale(6, y));
        /* A plain container rather than a Scrollport: the editor is a fixed set of controls that
         * fits, and a scroll frame around it would only add a bar that never moves. */
        detail = add(new Widget(new Coord(WSZ.x - LISTW - UI.scale(24), WSZ.y - UI.scale(y + 14))),
            new Coord(LISTW + UI.scale(14), UI.scale(y)));
        refresh();
        pack();
    }

    // ------------------------------------------------------------------ defining

    /**
     * Hands the next map drag to us.
     *
     * The callback arrives on the UI thread with TILE coordinates, which is what makes anchoring
     * cheap: a tile coord converts straight to a world position, and from there to a
     * {@link WorldAnchor} that survives the area not being rendered.
     */
    private void startDraw() {
        if (gui.map == null)
            return;
        hint.settext("Drag a rectangle on the map...");
        hint.setcolor(Color.YELLOW);
        /* Clears any selector left over from a previous draw before arming a new one: MapView
         * treats a click with one still around as "cancel", so without this the second draw
         * would silently eat its first click. */
        gui.map.unregisterAreaSelect();
        gui.map.registerAreaSelect(new AreaSelectCallback() {
            @Override
            public void areaselect(Coord a, Coord b) {
                if (!drawing)
                    return;
                /* Only disarm. This runs inside MapView's own mouse-up, under its monitor and
                 * with the selector still mid-teardown, so destroying the selector from here
                 * would double-remove its overlay and drop the mouse grab early. The window's
                 * close path does the real cleanup. */
                drawing = false;
                gui.map.areaSelect = false;
                pendingA = new Coord(Math.min(a.x, b.x), Math.min(a.y, b.y));
                pendingB = new Coord(Math.max(a.x, b.x), Math.max(a.y, b.y));
                hint.settext("Drawn " + (pendingB.x - pendingA.x + 1) + "x"
                    + (pendingB.y - pendingA.y + 1) + " tiles - name it and press Add.");
                hint.setcolor(Color.WHITE);
            }
        });
        /* Arming the drag is a separate flag from the callback, and registering without setting
         * it is why "Draw on map" did nothing: MapView.mousedown only builds a selector while
         * areaSelect is true, and nothing in the client had ever set it. */
        gui.map.areaSelect = true;
        drawing = true;
    }

    private void addPending() {
        String name = newName.text().trim();
        if (name.isEmpty()) {
            gui.error("Give the place a name first.");
            return;
        }
        if (pendingA == null) {
            gui.error("Draw the area on the map first.");
            return;
        }
        WorldAnchor anchor = WorldAnchor.capture(gui, pendingA.mul(MCache.tilesz));
        if (anchor == null) {
            gui.error("Couldn't anchor that spot - walk into the area and try again.");
            return;
        }
        Places.add(new Place(name, anchor,
            pendingB.x - pendingA.x + 1, pendingB.y - pendingA.y + 1));
        pendingA = pendingB = null;
        newName.settext("");
        selected = name;
        hint.settext("Added \"" + name + "\". Tick what it's for.");
        hint.setcolor(Color.GREEN);
        rebuild = true;
    }

    /**
     * Replaces the selected place's rectangle with a freshly drawn one, keeping its roles and rules.
     *
     * Worth being its own action rather than delete-and-re-add: getting the extent right is the
     * fiddly part and getting it wrong is common, so the alternative would be re-ticking half a
     * dozen roles every time a rectangle needed nudging a tile.
     */
    private void redrawSelected() {
        Place p = current();
        if (p == null)
            return;
        if (pendingA == null) {
            gui.error("Draw the new area on the map first.");
            return;
        }
        WorldAnchor anchor = WorldAnchor.capture(gui, pendingA.mul(MCache.tilesz));
        if (anchor == null) {
            gui.error("Couldn't anchor that spot - walk into the area and try again.");
            return;
        }
        p.anchor = anchor;
        p.w = pendingB.x - pendingA.x + 1;
        p.h = pendingB.y - pendingA.y + 1;
        pendingA = pendingB = null;
        Places.add(p);
        hint.settext("Re-drew \"" + p.name + "\" as " + p.w + "x" + p.h + ".");
        hint.setcolor(Color.GREEN);
        rebuild = true;
    }

    private Place current() {
        return (selected == null) ? null : Places.byName(selected);
    }

    // ------------------------------------------------------------------ the list

    private static void clear(Widget parent) {
        for (Widget w = parent.child; w != null; ) {
            Widget next = w.next;
            w.destroy();
            w = next;
        }
    }

    private void refresh() {
        clear(list.cont);
        int y = 0;
        List<Place> places = Places.all();
        if (places.isEmpty()) {
            list.cont.add(new Label("No places yet."), new Coord(0, y));
        } else {
            if (current() == null)
                selected = places.get(0).name;
            for (Place p : places) {
                final Place place = p;
                boolean sel = p.name.equals(selected);
                /* The tick is what draws the area, and it is on the LIST row rather than in the
                 * editor on purpose: seeing several areas at once is the point of it, and that
                 * means being able to tick more than one without selecting each in turn. */
                list.cont.add(new CheckBox("") {
                    {
                        a = place.show;
                    }

                    public void set(boolean val) {
                        place.show = val;
                        a = val;
                        Places.add(place);
                    }
                }, new Coord(0, y + UI.scale(2)));
                Button row = new Button(LISTW - UI.scale(22), label(p)) {
                    @Override
                    public void click() {
                        selected = place.name;
                        rebuild = true;
                    }
                };
                if (sel)
                    row.change("> " + label(place), Color.YELLOW);
                list.cont.add(row, new Coord(UI.scale(20), y));
                y += UI.scale(22);
            }
        }
        detail();
    }

    private String label(Place p) {
        String extent = (p.w <= 1 && p.h <= 1) ? "point" : (p.w + "x" + p.h);
        return p.name + " (" + extent + ")";
    }

    // ------------------------------------------------------------------ the editor

    private void detail() {
        clear(detail);
        Place p = current();
        if (p == null) {
            detail.add(new Label("Draw an area on the map and give it a name."), Coord.z);
            return;
        }
        int cw = detail.sz.x;
        int y = 0;

        Label title = new Label(p.name);
        title.setcolor(p.reachable(gui) ? Color.WHITE : Color.GRAY);
        detail.add(title, new Coord(0, y));
        detail.add(new Label(p.reachable(gui)
            ? (p.w + "x" + p.h + " tiles")
            : "not on this part of the map"), new Coord(UI.scale(120), y));
        int delw = UI.scale(52);
        detail.add(new Button(delw, "Delete") {
            @Override
            public void click() {
                Places.remove(p.name);
                selected = null;
                // Immediately, rather than waiting for the overlay's own half-second sweep to
                // notice - a rectangle still glowing after you deleted it reads as a failed delete.
                PlaceOverlay.clear(gui);
                rebuild = true;
            }
        }, new Coord(Math.max(0, cw - delw), y));
        detail.add(new Button(UI.scale(64), "Re-draw") {
            @Override
            public void click() {
                redrawSelected();
            }
        }, new Coord(Math.max(0, cw - delw - UI.scale(70)), y));
        y += UI.scale(24);

        detail.add(new Label("What it's for"), new Coord(0, y));
        y += UI.scale(16);
        List<String> roles = new ArrayList<>(Places.knownRoles());
        int colw = UI.scale(100);
        int cols = Math.max(1, cw / colw);
        for (int i = 0; i < roles.size(); i++) {
            final String role = roles.get(i);
            detail.add(new CheckBox(display(role)) {
                {
                    a = p.hasRole(role);
                }

                public void set(boolean val) {
                    if (val)
                        p.roles.add(role);
                    else
                        p.roles.remove(role);
                    Places.add(p);
                    a = val;
                }
            }, new Coord((i % cols) * colw, y + (i / cols) * UI.scale(18)));
        }
        y += ((roles.size() + cols - 1) / cols) * UI.scale(18) + UI.scale(8);

        /* Spelled out because the two are easy to confuse, and getting it wrong is what made
         * "water" look broken: a role is the whole answer for water, food, tools and work, and the
         * item rules below only matter for places bots put things into or take things out of. */
        detail.add(new Label("Ticking a role is enough. The rules below are only for storage."),
            new Coord(0, y));
        y += UI.scale(20);

        y = rule(y, cw, "Takes in", p.accepts.store(), true);
        y = rule(y, cw, "Gives out", p.provides.store(), false);

        detail.add(new Label("What's inside right now"), new Coord(0, y));
        y += UI.scale(16);
        detail.add(new Label(contents(p)), new Coord(0, y));
    }

    /**
     * One item rule: a label, a full-width text field, and a group picker that appends to it.
     *
     * The picker writes into the field rather than replacing it, so groups compose - hides plus
     * bones is two picks - and whatever is already typed survives.
     */
    private int rule(int y, int cw, String label, String value, boolean in) {
        detail.add(new Label(label + ":"), new Coord(0, y + UI.scale(3)));
        int lblw = UI.scale(66);
        final RuleField field = new RuleField(Math.max(UI.scale(80), cw - lblw), value, in);
        detail.add(field, new Coord(lblw, y));
        y += UI.scale(22);

        detail.add(new Label("add group:"), new Coord(0, y + UI.scale(3)));
        final List<String> groups = ItemGroups.names();
        detail.add(new OldDropBox<String>(UI.scale(150), 10, UI.scale(17)) {
            {
                super.change("(pick one)");
            }

            protected String listitem(int i) {
                return groups.get(i);
            }

            protected int listitems() {
                return groups.size();
            }

            protected void drawitem(GOut g, String item, int i) {
                g.aimage(Text.renderstroked(item).tex(),
                    Coord.of(UI.scale(3), g.sz().y / 2), 0.0, 0.5);
            }

            public void change(String item) {
                /* The box is left reading "(pick one)" rather than showing the group just added,
                 * because it is an action and not a state - the rule is the state, and it is right
                 * there in the field above. Showing the last pick would suggest otherwise, and
                 * would also stop the same group being picked twice after an edit. */
                super.change("(pick one)");
                field.settext(ItemGroups.add(field.text(), item));
                field.apply();
            }
        }, new Coord(lblw, y));
        return y + UI.scale(26);
    }

    /**
     * A rule field that writes itself back to the selected place.
     *
     * A named class rather than an anonymous one because the group picker has to be able to say
     * "and now save that", and {@code TextEntry.changed} is protected - which an anonymous subclass
     * cannot widen and nothing outside haven can call. One public method solves it, and it keeps
     * typing and picking on exactly the same save path.
     */
    private class RuleField extends TextEntry {
        private final boolean in;

        RuleField(int w, String value, boolean in) {
            super(w, value);
            this.in = in;
        }

        public void apply() {
            Place p = current();
            if (p == null)
                return;
            if (in)
                p.accepts = Alias.parse("accepts", text());
            else
                p.provides = Alias.parse("provides", text());
            Places.add(p);
        }

        @Override
        protected void changed() {
            apply();
        }
    }

    /** The contents count as one line, or a plain statement that there is nothing to report. */
    private String contents(Place p) {
        if (!p.reachable(gui))
            return "(walk there to see)";
        Map<String, Integer> counts = p.contents(gui);
        if (counts.isEmpty())
            return "nothing rendered inside it";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(e.getKey());
            if (e.getValue() > 1)
                sb.append(" x").append(e.getValue());
            // One line, so a place with fifty kinds of thing in it truncates rather than
            // overflowing the window and hiding the controls above it.
            if (sb.length() > 90) {
                sb.append(", ...");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * A role as the player should read it. Roles are stored lower-case because they are matched
     * and persisted as plain strings, so the capital belongs at the point of display rather than
     * in the data - otherwise "Water" and "water" become two different roles.
     */
    private static String display(String role) {
        if (role == null || role.isEmpty())
            return role;
        return Character.toUpperCase(role.charAt(0)) + role.substring(1);
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Re-reads the contents line every couple of seconds.
     *
     * Only the editor is rebuilt, and only when a place is selected: the list does not change on
     * its own, and rebuilding it here would destroy the row widget under whatever the mouse was
     * doing to it. Two seconds because the answer changes when a bot moves something, which is not
     * a fast process.
     */
    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (rebuild) {
            rebuild = false;
            refresh();
            // Skip the contents pass this frame; refresh() has just rebuilt the editor anyway.
            recount = 2.0;
            return;
        }
        recount -= dt;
        if (recount <= 0) {
            recount = 2.0;
            if (current() != null)
                detail();
        }
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && Objects.equals(msg, "close")) {
            drawing = false;
            if (gui.map != null)
                gui.map.unregisterAreaSelect();
            if (gui.nbotPlaces == this)
                gui.nbotPlaces = null;
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }

    @Override
    public void reqdestroy() {
        Utils.setprefc("wndc-nbotPlacesWindow", this.c);
        super.reqdestroy();
    }

    /** Referenced by PlaceRoles so the well-known list is reachable from the window's javadoc. */
    static final List<String> ROLES = PlaceRoles.KNOWN;
}
