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
    private static final Coord WSZ = UI.scale(700, 520);
    private static final int LISTW = UI.scale(200);
    /** Where the selected place is remembered between sessions. */
    private static final String SELPREF = "nbotPlacesSelected";

    private final GameUI gui;
    private final Scrollport list;
    private final Widget detail;
    private final Label hint;
    private final TextEntry newName;

    /** The rectangle most recently drawn, waiting for a name. */
    private Coord pendingA, pendingB;
    /** Whether a drag we armed is still outstanding. */
    private boolean drawing;
    /**
     * The place a drag was armed FOR, when "Re-draw" armed it.
     *
     * Re-draw used to only consume a rectangle that happened to be lying around, so the sequence
     * was draw, select, re-draw - three steps for what reads as one, and the first two of them
     * were not signposted anywhere. Arming the drag from the button means the only thing left to
     * do is the drag, and this is what the finished drag has to be applied to.
     */
    private String redrawFor;
    /** The place whose editor is on the right, by name - not by reference, since rows are rebuilt. */
    private String selected;

    /** Counts down to the next contents refresh, in seconds. */
    private double recount = 0;
    /**
     * The live "what's inside" line, kept so the refresh can rewrite it in place.
     *
     * Rebuilding the whole editor for it is what made the rule fields impossible to type in: the
     * refresh destroyed the focused TextEntry a couple of seconds into editing it, and focus landed
     * back on the name field at the top of the window. Nothing else in the editor changes on its
     * own, so nothing else needs rebuilding.
     */
    private Label inside;
    /**
     * Set by a button that wants the panels rebuilt; acted on at the start of the next tick.
     *
     * Every one of those buttons is INSIDE a panel, so rebuilding where it is asked for would
     * destroy the widget currently dispatching the click and leave the event system holding a
     * detached object. Nothing in this client survives that reliably - it is the same shape as the
     * two white-screens already found in this window - and a one-frame delay is invisible.
     */
    private boolean rebuild = false;
    /**
     * Set when a drag has finished and the map's selector still needs taking down.
     *
     * Clearing {@code areaSelect} disarms the NEXT drag but leaves both the callback and MapView's
     * spent Selector standing, and a spent Selector eats the first click of whatever is armed
     * next - which is the "Re-draw leaves the mouse stuck in the drawing state" behaviour: the
     * click that should have started the new rectangle went on cancelling the old one instead.
     * Deferred to the next tick for the same reason {@link #rebuild} is - the callback runs inside
     * MapView's own mouse-up, under its monitor, with that Selector still mid-teardown.
     */
    private boolean disarm = false;

    public PlacesWindow(GameUI gui) {
        super(WSZ, "Bot Places");
        this.gui = gui;

        int y = UI.scale(4);
        add(new Label("Where the bots go for water, food, tools, storage and work."),
            new Coord(UI.scale(6), y));
        y += UI.scale(18);
        hint = add(new Label(""), new Coord(UI.scale(6), y));
        y += UI.scale(20);

        /* Laid out from what the widgets actually measure rather than from a guessed row height.
         * A Button's height comes from its own images and a wide one is TALLER than a narrow one,
         * so the hand-counted 28 that used to be added here was short by several pixels for this
         * row - which is why the first rank of list tickboxes sat on top of "Draw on map". */
        Widget draw = add(new Button(UI.scale(96), "Draw on map") {
            @Override
            public void click() {
                redrawFor = null;
                startDraw();
            }
        }, new Coord(UI.scale(6), y));
        newName = add(new TextEntry(UI.scale(170), ""), new Coord(UI.scale(110), y));
        Widget addBtn = add(new Button(UI.scale(52), "Add") {
            @Override
            public void click() {
                addPending();
            }
        }, new Coord(UI.scale(286), y));
        Widget show = add(new CheckBox("Show ticked areas on the ground") {
            {
                a = NBotConfig.on(NBotConfig.Key.showAreas);
            }

            public void set(boolean val) {
                NBotConfig.set(NBotConfig.Key.showAreas, val);
                a = val;
                if (!val)
                    PlaceOverlay.clear(PlacesWindow.this.gui);
            }
        }, new Coord(UI.scale(350), y + UI.scale(4)));
        y = bottom(UI.scale(8), draw, newName, addBtn, show);

        int paneh = Math.max(UI.scale(120), WSZ.y - y - UI.scale(10));
        list = add(new Scrollport(new Coord(LISTW, paneh)), new Coord(UI.scale(6), y));
        /* A plain container rather than a Scrollport: the editor is a fixed set of controls that
         * fits, and a scroll frame around it would only add a bar that never moves. */
        detail = add(new Widget(new Coord(WSZ.x - LISTW - UI.scale(24), paneh)),
            new Coord(LISTW + UI.scale(14), y));
        String last = Utils.getpref(SELPREF, "");
        selected = last.isEmpty() ? null : last;
        refresh();
        pack();
    }

    /** The lowest edge of a row of widgets, plus a gap. Layout by measurement, not by arithmetic. */
    private static int bottom(int gap, Widget... ws) {
        int b = 0;
        for (Widget w : ws) {
            if (w != null)
                b = Math.max(b, w.c.y + w.sz.y);
        }
        return b + gap;
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
                 * would double-remove its overlay and drop the mouse grab early. The real
                 * teardown is deferred to the next tick - see `disarm`. */
                drawing = false;
                gui.map.areaSelect = false;
                disarm = true;
                pendingA = new Coord(Math.min(a.x, b.x), Math.min(a.y, b.y));
                pendingB = new Coord(Math.max(a.x, b.x), Math.max(a.y, b.y));
                if (redrawFor != null) {
                    // The drag was armed BY Re-draw, so it is already answered - applying it here
                    // is what makes that button one action rather than the first of three.
                    applyRedraw(redrawFor);
                    return;
                }
                hint.settext("Drawn " + (pendingB.x - pendingA.x) + "x"
                    + (pendingB.y - pendingA.y) + " tiles - name it and press Add.");
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
            // No +1 - MapView's Area has an EXCLUSIVE br, so the difference is the
            // tile count. See AreaDraw.tick.
            pendingB.x - pendingA.x, pendingB.y - pendingA.y));
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
        /* Arm the drag rather than demanding one has already happened. A rectangle drawn earlier
         * is still honoured - it costs nothing and somebody who drew first meant it - but the
         * button no longer refuses to do anything until the player has guessed that order. */
        if (pendingA == null) {
            redrawFor = p.name;
            startDraw();
            hint.settext("Drag the new area for \"" + p.name + "\" on the map...");
            hint.setcolor(Color.YELLOW);
            return;
        }
        applyRedraw(p.name);
    }

    /** Puts the pending rectangle onto a named place, keeping its roles and rules. */
    private void applyRedraw(String name) {
        redrawFor = null;
        Place p = Places.byName(name);
        if (p == null || pendingA == null)
            return;
        WorldAnchor anchor = WorldAnchor.capture(gui, pendingA.mul(MCache.tilesz));
        if (anchor == null) {
            gui.error("Couldn't anchor that spot - walk into the area and try again.");
            return;
        }
        p.anchor = anchor;
        p.w = pendingB.x - pendingA.x;
        p.h = pendingB.y - pendingA.y;
        pendingA = pendingB = null;
        Places.add(p);
        // The overlay caches the old rectangle; leaving it up reads as a re-draw that didn't take.
        PlaceOverlay.clear(gui);
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
                /* Measured off the scroll CONTENT, not the port. The port gives its scrollbar a
                 * strip of its own width, so a row sized against LISTW ran under the bar and had
                 * its right-hand end clipped off - which is most of a name, since the extent is
                 * on that end. */
                int roww = Math.max(UI.scale(40), list.cont.sz.x - UI.scale(22));
                Button row = new Button(roww, label(p)) {
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

        /* Two states in the UI for a three-state field, and that is currently enough: every role
         * defaults to shared, so "unticked" (no opinion) already resolves to shared and an
         * explicit FALSE would say nothing extra. If a role ever starts defaulting to exclusive
         * this has to become a three-way control so the player can say "share it anyway".
         *
         * Ticking this holds the area whoever is working it. It is not how the survey bot gets its
         * exclusivity - that bot claims regardless, because it cannot share at all - so leaving
         * this unticked does not make surveying unsafe. See Places.claim. */
        detail.add(new CheckBox("Only one bot at a time") {
            {
                a = Boolean.TRUE.equals(p.exclusive);
            }

            public void set(boolean val) {
                p.exclusive = val ? Boolean.TRUE : null;
                Places.add(p);
                a = val;
            }
        }, new Coord(0, y));
        y += UI.scale(22);

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
        inside = detail.add(new Label(contents(p)), new Coord(0, y));
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
        // Wide enough for the longest "Category - Group" label; the categories cost width, and a
        // picker that ends mid-word is no better than the unsorted list it replaced.
        detail.add(new OldDropBox<String>(UI.scale(220), 12, UI.scale(17)) {
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
                g.aimage(Text.strokedtex(item),
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
        /* What is KNOWN, not what is rendered: from across the map this reports what was last seen
         * there rather than "nothing", which is the same correction the bots got. The label below
         * says which of the two you are looking at, since "barrel x1" means different things when
         * it is being watched and when it is being remembered. */
        boolean seeing = p.observable(gui);
        Map<String, Integer> counts = p.known(gui);
        if (counts.isEmpty())
            return seeing ? "nothing inside it" : "nothing remembered inside it";
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
        // Said plainly, because acting on a remembered barrel and acting on one you can see are
        // different amounts of confident.
        if (!seeing)
            sb.append("  (remembered)");
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
     * ONE LABEL is rewritten, not the editor. Rebuilding the editor here is what made the item
     * rules impossible to type: a couple of seconds into editing, the focused TextEntry was
     * destroyed under the cursor and focus jumped to the name field at the top of the window. The
     * contents line is the only thing in the editor that changes without the player doing
     * something, so it is the only thing that needs re-reading. Two seconds because the answer
     * changes when a bot moves something, which is not a fast process.
     */
    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (disarm) {
            disarm = false;
            // Safe here and not in the callback: the mouse-up that armed this has long returned,
            // so the Selector is fully torn down and destroying it cannot race its own cleanup.
            if (gui.map != null && !drawing)
                gui.map.unregisterAreaSelect();
        }
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
            Place p = current();
            if (p != null && inside != null)
                inside.settext(contents(p));
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
        /* Remembered because the window is opened to check on ONE place far more often than to
         * survey all of them, and re-finding it in the list every time is the whole cost of
         * opening the window. Stored by name, so a place deleted meanwhile simply falls back to
         * the first in the list. */
        Utils.setpref(SELPREF, (selected == null) ? "" : selected);
        super.reqdestroy();
    }

    /** Referenced by PlaceRoles so the well-known list is reachable from the window's javadoc. */
    static final List<String> ROLES = PlaceRoles.KNOWN;
}
