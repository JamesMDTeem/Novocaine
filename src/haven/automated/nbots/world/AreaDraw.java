package haven.automated.nbots.world;

import haven.Coord;
import haven.GameUI;
import haven.MCache;
import haven.automated.helpers.AreaSelectCallback;

import java.awt.Color;

/**
 * Drawing one place on the map from somewhere that is not the places window.
 *
 * Bot Places is the right home for the full model - many places, many roles, item rules - but it
 * is the wrong amount of ceremony for the commonest thing anybody actually does, which is "clear
 * THAT patch". Making that require opening a second window, drawing, naming, ticking a role and
 * coming back is enough friction that people use nearest-first with a big radius instead and then
 * wonder why the bot wandered off.
 *
 * So a bot window can own one of these and get a button. The rectangle it draws replaces the same
 * place every time, named after the bot, so there is no naming step and no accumulating clutter -
 * a bot's work area is a property of the bot, not a thing to be managed. Anyone who wants several
 * named areas still has the places window, and the two agree because this writes through
 * {@link Places} like everything else.
 *
 * The drag is armed here but APPLIED FROM THE OWNING WINDOW'S TICK, not from the callback. The
 * callback runs inside MapView's own mouse-up, under its monitor, with the selector still
 * mid-teardown - tearing it down or doing real work from in there is the same hazard the places
 * window documents, and the same one-frame deferral is the answer.
 */
public class AreaDraw {
    private final GameUI gui;
    private final String placeName;
    private final String role;

    /** Set by the drag callback; consumed by {@link #tick}. */
    private volatile boolean finished;
    private volatile Coord a, b;
    /** True between arming and the drag being applied, so tick knows a teardown is owed. */
    private boolean armed;

    public AreaDraw(GameUI gui, String placeName, String role) {
        this.gui = gui;
        this.placeName = placeName;
        this.role = role;
    }

    /** Hands the next map drag to us. Safe to call again; a previous arming is dropped. */
    public void arm() {
        if (gui == null || gui.map == null)
            return;
        finished = false;
        a = b = null;
        /* Clear any selector left over from a previous draw before arming a new one. MapView keeps
         * a spent Selector after a drag finishes and reads a click with one still around as
         * "cancel", so without this the next draw silently loses its first click. */
        gui.map.unregisterAreaSelect();
        gui.map.registerAreaSelect(new AreaSelectCallback() {
            @Override
            public void areaselect(Coord p, Coord q) {
                a = new Coord(Math.min(p.x, q.x), Math.min(p.y, q.y));
                b = new Coord(Math.max(p.x, q.x), Math.max(p.y, q.y));
                // Only record and disarm. The work happens a frame later - see the class comment.
                gui.map.areaSelect = false;
                finished = true;
            }
        });
        /* Arming the drag is a separate flag from registering the callback, and registering
         * without setting it is why a draw button can look like it does nothing: MapView only
         * builds a selector while areaSelect is true. */
        gui.map.areaSelect = true;
        armed = true;
        gui.msg("Drag the work area for " + placeName + " on the map.", Color.WHITE);
    }

    public boolean armed() {
        return armed;
    }

    /**
     * Applies a finished drag. Call once per frame from the owning window's tick.
     *
     * @return the place if one was just created or replaced, otherwise null.
     */
    public Place tick() {
        if (!finished)
            return null;
        finished = false;
        armed = false;
        if (gui != null && gui.map != null)
            gui.map.unregisterAreaSelect();
        if (a == null || b == null)
            return null;

        WorldAnchor anchor = WorldAnchor.capture(gui, a.mul(MCache.tilesz));
        if (anchor == null) {
            gui.error("Couldn't anchor that spot - stand nearer to it and try again.");
            return null;
        }
        /* No +1. MapView hands over a haven.Area, whose br is EXCLUSIVE - that is what its own
         * sz(), area() and contains() all assume - so the difference IS the tile count and adding
         * one puts an extra row and an extra column on every area ever drawn. Which is not a
         * cosmetic tile: the water place drawn to stop short of the palisade came out one row
         * longer, standing on it, so the place a bot was sent to overlapped a wall and the block
         * it sits in stopped being enterable from the side the water is on. */
        int w = b.x - a.x;
        int h = b.y - a.y;

        /* Replaced in place when it already exists, so any roles or item rules the player has
         * added to this bot's area by hand in the places window survive being re-drawn. Only the
         * rectangle is the button's business. */
        Place p = Places.byName(placeName);
        if (p == null) {
            p = new Place(placeName, anchor, w, h);
        } else {
            p.anchor = anchor;
            p.w = w;
            p.h = h;
        }
        p.roles.add(role);
        Places.add(p);
        // The overlay caches the old rectangle; leaving it up reads as a draw that didn't take.
        PlaceOverlay.clear(gui);
        gui.msg("Work area for " + placeName + " set to " + w + "x" + h + " tiles.", Color.GREEN);
        return p;
    }

    /** Drops an outstanding arming. For a window being closed mid-draw. */
    public void cancel() {
        finished = false;
        armed = false;
        a = b = null;
        if (gui != null && gui.map != null)
            gui.map.unregisterAreaSelect();
    }
}
