package haven.automated.nbots.world;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.Resource;
import haven.automated.nbots.core.Alias;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A named patch of ground the bots know something about: where the water is, where food is kept,
 * where to dump stone.
 *
 * This replaces the single stored water anchor, which worked for one bot needing one destination
 * and generalises to nothing - food, tools and somewhere to put output each want the same
 * treatment, and four global anchors is not a design.
 *
 * Two choices worth spelling out, both departures from nurgling2's NArea:
 *
 * ANCHORING. A place is one {@link WorldAnchor} (its north-west corner) plus a size in tiles.
 * nurgling stores a set of per-GRID rectangles keyed by grid id, and both it and its NGlobalCoord
 * bookmark resolve only while that grid is loaded - which is exactly the case a bot walking back
 * to base from three hundred tiles away is in. Anchoring to the map SEGMENT instead means the
 * destination still has coordinates when nothing there is rendered, which is the whole point.
 *
 * ROLES ARE STRINGS. nurgling keys area purpose off a 60-entry Specialisation.SpecName enum, so
 * teaching a new bot about a new kind of place means editing an enum, a widget and a switch. Here a
 * role is a free string with the common ones named in {@link PlaceRoles}; a future bot can invent
 * "beehives" without touching anything that already exists, and the manager window lists whatever
 * roles are actually in use alongside the known ones.
 *
 * Item rules are one list of {@link Alias} patterns per direction ({@link #accepts} for things that
 * go in, {@link #provides} for things that come out) rather than nurgling's parallel jin/jout JSON
 * arrays with their own threshold fields - one concept, matched the same way everywhere.
 */
public class Place {
    public String name;
    /** North-west corner, anchored so it survives the area not being rendered. */
    public WorldAnchor anchor;
    /** Extent from the anchor, in tiles. A 0x0 place is a point - legal, and useful for a barrel. */
    public int w, h;
    public final Set<String> roles = new LinkedHashSet<>();
    /** Item-name patterns this place takes IN (a dump, a store). */
    public Alias accepts = new Alias("accepts");
    /** Item-name patterns this place gives OUT (a food store, a seed store). */
    public Alias provides = new Alias("provides");

    public Place(String name, WorldAnchor anchor, int w, int h) {
        this.name = name;
        this.anchor = anchor;
        this.w = Math.max(0, w);
        this.h = Math.max(0, h);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * The centre of the place in live world coordinates, or null if it can't be resolved - meaning
     * either the map file doesn't know where we are yet, or the place is in a different map segment
     * and there is no offset between the two to apply.
     */
    public Coord2d centre(GameUI gui) {
        Coord2d nw = (anchor == null) ? null : anchor.resolve(gui);
        if (nw == null)
            return null;
        return nw.add((w * haven.MCache.tilesz.x) / 2.0, (h * haven.MCache.tilesz.y) / 2.0);
    }

    /** True if a live world position falls inside this place. */
    public boolean contains(GameUI gui, Coord2d wc) {
        Coord2d nw = (anchor == null || wc == null) ? null : anchor.resolve(gui);
        if (nw == null)
            return false;
        double ex = nw.x + Math.max(w, 1) * haven.MCache.tilesz.x;
        double ey = nw.y + Math.max(h, 1) * haven.MCache.tilesz.y;
        return wc.x >= nw.x && wc.x < ex && wc.y >= nw.y && wc.y < ey;
    }

    public boolean reachable(GameUI gui) {
        return centre(gui) != null;
    }

    /**
     * Every gob currently rendered inside this place.
     *
     * Only meaningful once the bot has actually walked here - which is the normal order of
     * operations (travel to the place, then look at what's in it) and the reason this is a
     * separate step from resolving the anchor rather than something a place can answer from afar.
     */
    public List<Gob> gobsWithin(GameUI gui) {
        List<Gob> out = new ArrayList<>();
        if (gui == null || gui.map == null)
            return out;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (contains(gui, g.rc))
                    out.add(g);
            }
        }
        return out;
    }

    /** Gobs within this place whose resource name matches, e.g. every barrel in the water place. */
    public List<Gob> gobsWithin(GameUI gui, Alias resPattern) {
        List<Gob> out = new ArrayList<>();
        for (Gob g : gobsWithin(gui)) {
            try {
                Resource res = g.getres();
                if (res != null && resPattern.matchesPart(res.name))
                    out.add(g);
            } catch (Loading | NullPointerException ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ persistence

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("anchor", (anchor == null) ? "" : anchor.store());
        o.put("w", w);
        o.put("h", h);
        o.put("roles", new JSONArray(roles));
        o.put("accepts", accepts.store());
        o.put("provides", provides.store());
        return o;
    }

    public static Place fromJson(JSONObject o) {
        WorldAnchor a = WorldAnchor.parse(o.optString("anchor", ""));
        if (a == null)
            return null;
        Place p = new Place(o.optString("name", "?"), a, o.optInt("w", 0), o.optInt("h", 0));
        JSONArray r = o.optJSONArray("roles");
        if (r != null) {
            for (int i = 0; i < r.length(); i++)
                p.roles.add(r.getString(i));
        }
        p.accepts = Alias.parse("accepts", o.optString("accepts", ""));
        p.provides = Alias.parse("provides", o.optString("provides", ""));
        return p;
    }

    public String toString() {
        return "place(" + name + " " + roles + " " + w + "x" + h + ")";
    }
}
