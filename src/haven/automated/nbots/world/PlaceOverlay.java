package haven.automated.nbots.world;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.MCache;
import haven.Material;
import haven.Utils;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.NLog;
import haven.render.BaseColor;
import haven.render.States;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Painting the defined places onto the ground, so you can see what a bot is actually aiming at.
 *
 * A place is four numbers in a file, and until you can see it there is no way to tell a rectangle
 * that covers the barrels from one that stops a tile short of them - which is the difference
 * between a bot that fills its waterskins and one that walks to the right spot and reports finding
 * no barrel. Every bug of that shape is invisible without this and obvious with it.
 *
 * The drawing itself costs nothing new: the client already renders tinted ground rectangles for the
 * drag-select box ({@code MapView.Selector}), through {@link MCache.RectOverlay} and an
 * {@link MCache.OverlayInfo} that says what colour to use. This registers the same kind of overlay
 * for each shown place and keeps it up to date, which is why area visualisation needed no change to
 * the renderer at all.
 *
 * Refreshed on a timer rather than every frame. Each place has to have its anchor resolved, which
 * is a map-file lookup, and the rectangles only move when the client re-bases its coordinates -
 * a couple of times a second is far more often than that happens.
 */
public class PlaceOverlay {
    /** Ticks between refreshes, in seconds. */
    private static final double PERIOD = 0.5;

    /** Green, and as faint as the client's own selection box. Ground you can still see through. */
    private static final MCache.OverlayInfo AREA = new MCache.OverlayInfo() {
        final Material mat = new Material(new BaseColor(64, 255, 128, 32), States.maskdepth);

        public Collection<String> tags() {
            return Arrays.asList("show");
        }

        public Material mat() {
            return mat;
        }
    };

    /** Place name -> the overlay currently registered for it. */
    private static final Map<String, MCache.RectOverlay> shown = new HashMap<>();
    private static double next = 0;

    private PlaceOverlay() {}

    /**
     * Brings the drawn rectangles in line with what is defined and ticked.
     *
     * Called from the game window's tick. Everything it touches is either a plain field or MCache's
     * own synchronised overlay set, and it never throws: this runs on the UI thread, where an
     * escaping exception does not log an error, it ends the client.
     */
    public static void tick(GameUI gui) {
        try {
            if (gui == null || gui.map == null || gui.ui == null || gui.ui.sess == null)
                return;
            double now = Utils.rtime();
            if (now < next)
                return;
            next = now + PERIOD;

            MCache mcache = gui.ui.sess.glob.map;
            Set<String> wanted = new HashSet<>();
            if (NBotConfig.on(NBotConfig.Key.showAreas)) {
                for (Place p : Places.all()) {
                    if (!p.show)
                        continue;
                    Area a = area(gui, p);
                    // Unresolvable means the place is on another continent, or the map file has
                    // not caught up yet. Either way there is nowhere on screen to draw it.
                    if (a == null)
                        continue;
                    wanted.add(p.name);
                    MCache.RectOverlay ol = shown.get(p.name);
                    if (ol == null) {
                        ol = mcache.new RectOverlay(AREA, a);
                        mcache.add(ol);
                        shown.put(p.name, ol);
                    } else {
                        ol.update(a);
                    }
                }
            }
            // Anything drawn that should not be: deleted, unticked, or walked out of range of.
            shown.entrySet().removeIf(e -> {
                if (wanted.contains(e.getKey()))
                    return false;
                mcache.remove(e.getValue());
                return true;
            });
        } catch (RuntimeException e) {
            NLog.crash("place overlay tick", e);
        }
    }

    /**
     * A place as a rectangle of map tiles, or null if it cannot be placed right now.
     *
     * A zero-extent place - a point, which is a legal way to mark a single barrel - still gets one
     * tile, because an empty Area draws nothing and a marker you cannot see is not a marker.
     */
    private static Area area(GameUI gui, Place p) {
        Coord2d nw = (p.anchor == null) ? null : p.anchor.resolve(gui);
        if (nw == null)
            return null;
        Coord ul = nw.floor(MCache.tilesz);
        return Area.sized(ul, new Coord(Math.max(1, p.w), Math.max(1, p.h)));
    }

    /** Drops every drawn rectangle. For a place being deleted, or the window closing. */
    public static void clear(GameUI gui) {
        try {
            if (gui == null || gui.ui == null || gui.ui.sess == null)
                return;
            MCache mcache = gui.ui.sess.glob.map;
            for (MCache.RectOverlay ol : shown.values())
                mcache.remove(ol);
            shown.clear();
        } catch (RuntimeException e) {
            NLog.crash("place overlay clear", e);
        }
    }
}
