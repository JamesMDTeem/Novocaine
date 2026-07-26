package haven.automated.nbots;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.MCache;
import haven.Scrollport;
import haven.TextEntry;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.helpers.AreaSelectCallback;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;
import haven.automated.nbots.world.WorldAnchor;

import java.awt.Color;
import java.util.List;
import java.util.Objects;

/**
 * Defining and tagging the places bots use.
 *
 * The area itself is drawn on the MAP, not typed in here: Hurricane carries a drag-to-select
 * rectangle ({@link haven.MapView#registerAreaSelect}), so "Draw on map" hands control over to
 * that and takes the rectangle back rather than asking anyone to type coordinates.
 *
 * That API had never actually been driven - nothing in the client set MapView.areaSelect, so
 * registering a callback alone armed nothing, and unregistering without a live selector threw.
 * Both are handled here and in the two-line guard in MapView.unregisterAreaSelect.
 *
 * What a place is FOR is a set of role checkboxes plus two free-text item rules. Roles are plain
 * strings, so the list shown is the well-known ones plus anything already in use - a bot added
 * later can look for a role nothing here has heard of and it will simply work.
 */
public class PlacesWindow extends Window {
    private final GameUI gui;
    private final Scrollport list;
    private final Label hint;

    /** The rectangle most recently drawn, waiting for a name. */
    private Coord pendingA, pendingB;

    /** Whether a drag we armed is still outstanding. */
    private boolean drawing;

    public PlacesWindow(GameUI gui) {
        super(UI.scale(360, 400), "Bot Places");
        this.gui = gui;

        int y = 4;
        add(new Label("Where the bots go for water, food, tools and storage."), UI.scale(10, y));
        y += 20;

        hint = add(new Label(""), UI.scale(10, y));
        y += 20;

        add(new Button(UI.scale(110), "Draw on map") {
            @Override
            public void click() {
                startDraw();
            }
        }, UI.scale(10, y));
        newName = add(new TextEntry(UI.scale(140), ""), UI.scale(130, y));
        add(new Button(UI.scale(60), "Add") {
            @Override
            public void click() {
                addPending();
            }
        }, UI.scale(280, y));
        y += 30;

        list = add(new Scrollport(UI.scale(340, 250)), UI.scale(10, y));
        refresh();
        pack();
    }

    private final TextEntry newName;

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
        hint.settext("Added \"" + name + "\". Tick what it's for.");
        hint.setcolor(Color.GREEN);
        refresh();
    }

    // ------------------------------------------------------------------ listing

    private void refresh() {
        for (Widget w = list.cont.child; w != null; ) {
            Widget next = w.next;
            w.destroy();
            w = next;
        }
        int y = 0;
        List<Place> places = Places.all();
        if (places.isEmpty()) {
            list.cont.add(new Label("No places yet."), new Coord(0, y));
            return;
        }
        for (Place p : places) {
            y = row(p, y);
        }
    }

    private int row(Place place, int y) {
        String extent = (place.w <= 1 && place.h <= 1) ? "point" : (place.w + "x" + place.h);
        Label title = new Label(place.name + "  (" + extent + ")");
        title.setcolor(place.reachable(gui) ? Color.WHITE : Color.GRAY);
        list.cont.add(title, new Coord(0, y));
        list.cont.add(new Button(UI.scale(50), "Delete") {
            @Override
            public void click() {
                Places.remove(place.name);
                refresh();
            }
        }, new Coord(UI.scale(260), y - UI.scale(3)));
        y += UI.scale(20);

        int x = 0;
        for (String role : Places.knownRoles()) {
            list.cont.add(new CheckBox(role) {
                {
                    a = place.hasRole(role);
                }

                public void set(boolean val) {
                    if (val)
                        place.roles.add(role);
                    else
                        place.roles.remove(role);
                    Places.add(place);
                    a = val;
                }
            }, new Coord(UI.scale(x), y));
            x += 80;
            if (x > 240) {
                x = 0;
                y += UI.scale(18);
            }
        }
        y += UI.scale(22);

        list.cont.add(new Label("takes in:"), new Coord(0, y + UI.scale(3)));
        list.cont.add(new TextEntry(UI.scale(110), place.accepts.store()) {
            @Override
            public void changed() {
                place.accepts = Alias.parse("accepts", text());
                Places.add(place);
            }
        }, new Coord(UI.scale(60), y));
        list.cont.add(new Label("gives out:"), new Coord(UI.scale(180), y + UI.scale(3)));
        list.cont.add(new TextEntry(UI.scale(100), place.provides.store()) {
            @Override
            public void changed() {
                place.provides = Alias.parse("provides", text());
                Places.add(place);
            }
        }, new Coord(UI.scale(245), y));
        return y + UI.scale(30);
    }

    // ------------------------------------------------------------------ lifecycle

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
