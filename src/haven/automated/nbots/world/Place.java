package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
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
    /**
     * Draw this one on the ground. Persisted rather than kept per-session, because the reason to
     * tick it is usually "I don't trust this rectangle yet", and that outlives one login.
     */
    public boolean show = false;

    /**
     * What was last SEEN inside this place - full resource name to how many of them.
     *
     * Gobs are not in the map file, so a place a hundred tiles away can report nothing standing in
     * it, and every consumer used to read that as "there is nothing there". A bot at a work site
     * would decide its own water place held no barrel, on the strength of a scan taken from too far
     * away to see one, and go looking for water somewhere else - which is how a base with a full
     * barrel in it ends up sending bots out through its own gates.
     *
     * So what was seen is remembered, and only ever replaced by a fresh look taken from close enough
     * for the answer to mean something ({@link #observe}). An out-of-range scan now changes nothing,
     * which is the correct amount for it to change.
     *
     * Persisted, because the knowledge is worth more across a relog than a stale entry costs: the
     * worst case is one wasted walk to a barrel somebody moved, and the first look on arrival
     * corrects it.
     */
    public final java.util.Map<String, Integer> memory = new java.util.LinkedHashMap<>();

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

    /**
     * Somewhere inside the place actually worth walking to, in live world coordinates.
     *
     * The centre is the wrong answer and was being used anyway, for two separate reasons that
     * happen to point the same way.
     *
     * It is the likeliest tile in the rectangle to be OCCUPIED, because players draw an area AROUND
     * the things in it - so "walk to the water place" meant "walk onto a barrel", which the local
     * pathfinder answers by refusing to move at all.
     *
     * And arriving at it was judged by DISTANCE, with a tolerance stretched to cover the whole
     * rectangle so a big storage area would register as reached. A four-tile tolerance around a
     * point two tiles inside a palisade includes ground on the far side of that palisade: the bot
     * is then entitled to satisfy the journey by standing outside the wall, and a router asked to
     * get it there will oblige. The tolerance was quietly redefining the destination as a disc that
     * did not fit inside the place.
     *
     * So the destination is a TILE, chosen inside the rectangle, on ground the records say a
     * character can stand on, nearest to whoever is asking. Then the tolerance can be small, and
     * "arrived" can mean what {@link #contains} means.
     *
     * Falls back to the centre when nothing in the rectangle qualifies - a place drawn entirely on
     * water is the player's to fix, and refusing to walk anywhere near it does not help them see
     * that.
     */
    public Coord2d destination(GameUI gui, Coord2d from) {
        Coord2d nw = (anchor == null) ? null : anchor.resolve(gui);
        if (nw == null)
            return null;
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        Gob me = (gui.map == null) ? null : gui.map.player();
        Coord2d mid = centre(gui);
        if ((here == null) || (me == null))
            return mid;
        Coord2d off = here.sc.sub(me.rc);
        Coord2d want = (from == null) ? mid : from;
        Coord2d best = null;
        double bestd = Double.MAX_VALUE;
        for (int ty = 0; ty < Math.max(h, 1); ty++) {
            for (int tx = 0; tx < Math.max(w, 1); tx++) {
                // The middle of the tile, so the aim is never on a boundary between two of them.
                Coord2d at = nw.add((tx + 0.5) * MCache.tilesz.x, (ty + 0.5) * MCache.tilesz.y);
                Coord tile = at.add(off).floor(MCache.tilesz);
                if (Observed.solid(here.seg, tile) || !Terrain.ground(gui, here.seg, tile))
                    continue;
                double d = want.dist(at);
                if (d < bestd) {
                    bestd = d;
                    best = at;
                }
            }
        }
        return (best == null) ? mid : best;
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
        /* Every look is also a chance to learn, so memory keeps itself up to date with no call site
         * having to remember to do it - "update when re-rendered" falls out of somebody asking.
         * observe() decides for itself whether this look was taken from close enough to count. */
        observe(gui, out);
        return out;
    }

    /**
     * Whether this place is close enough RIGHT NOW for a scan of it to mean anything.
     *
     * The whole rectangle has to be inside the range objects stream in at ({@link Observed#sees()}),
     * not just its centre: a scan that can see two thirds of a storage area and reports what it found
     * is the same trap one tile further out, and half-remembering an area is worse than not
     * refreshing it. Being strict costs a walk closer; being loose costs forgetting a barrel.
     */
    public boolean observable(GameUI gui) {
        Coord2d nw = (anchor == null) ? null : anchor.resolve(gui);
        Gob me = ((gui == null) || (gui.map == null)) ? null : gui.map.player();
        if ((nw == null) || (me == null))
            return false;
        double reach = Observed.sees() * MCache.tilesz.x;
        // The far corner, so both extents are covered by one distance test.
        Coord2d se = nw.add(Math.max(w, 1) * MCache.tilesz.x, Math.max(h, 1) * MCache.tilesz.y);
        return (me.rc.dist(nw) <= reach) && (me.rc.dist(se) <= reach);
    }

    /**
     * Replaces what this place remembers with what is standing in it, if it can currently be seen.
     *
     * Deliberately all-or-nothing. A scan taken from out of range is not evidence of absence, so it
     * is discarded rather than merged - merging is what would let one distant look quietly delete a
     * barrel. An observable place with nothing in it DOES clear the memory, which is the other half
     * of being correct: a barrel that was carted away has to be forgettable.
     */
    public void observe(GameUI gui, List<Gob> live) {
        if (!observable(gui))
            return;
        java.util.Map<String, Integer> now = new java.util.LinkedHashMap<>();
        for (Gob g : live) {
            try {
                Resource res = g.getres();
                if (res == null)
                    continue;
                String n = res.name;
                // Characters wander through; remembering them would make every place "contain" whoever
                // last walked over it.
                if (n.startsWith("gfx/borka/") || n.contains("/body"))
                    continue;
                now.merge(n, 1, Integer::sum);
            } catch (Loading | NullPointerException ignored) {
            }
        }
        synchronized (memory) {
            if (memory.equals(now))
                return;
            memory.clear();
            memory.putAll(now);
        }
        Places.save();
    }

    /** How many things matching {@code pattern} this place is known to hold, seen or remembered. */
    public int remembers(Alias pattern) {
        int n = 0;
        synchronized (memory) {
            for (java.util.Map.Entry<String, Integer> e : memory.entrySet()) {
                if (pattern.matchesPart(e.getKey()))
                    n += e.getValue();
            }
        }
        return n;
    }

    /**
     * The best answer available about what is in here: live when it can be seen, memory when it
     * cannot. For DECIDING (is this place worth walking to) - acting on a gob still needs a live one.
     */
    public java.util.Map<String, Integer> known(GameUI gui) {
        if (observable(gui))
            return contents(gui);
        synchronized (memory) {
            java.util.Map<String, Integer> out = new java.util.TreeMap<>();
            for (java.util.Map.Entry<String, Integer> e : memory.entrySet()) {
                String kind = kindOf(e.getKey());
                if (kind != null)
                    out.merge(kind, e.getValue(), Integer::sum);
            }
            return out;
        }
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
        /* toArray() rather than passing the set: the bundled org.json declares its collection
         * constructor as JSONArray(Collection<Object>), and generics are invariant, so a
         * Set<String> does not match it. Overload resolution silently falls through to
         * JSONArray(Object), which accepts anything and then throws at runtime because a Set is
         * not an array. The array constructor is the one that works for every element type. */
        o.put("roles", new JSONArray(roles.toArray()));
        o.put("accepts", accepts.store());
        o.put("provides", provides.store());
        o.put("show", show);
        /* Built key by key rather than handed the map: the bundled org.json's JSONObject(Map) is
         * declared Map<String,Object>, and generics are invariant, so a Map<String,Integer> misses
         * it and falls through to JSONObject(Object) - the bean constructor, which silently writes
         * an empty object instead of throwing. Same family of trap as the JSONArray note above. */
        JSONObject mem = new JSONObject();
        synchronized (memory) {
            for (java.util.Map.Entry<String, Integer> e : memory.entrySet())
                mem.put(e.getKey(), e.getValue().intValue());
        }
        o.put("memory", mem);
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
        p.show = o.optBoolean("show", false);
        JSONObject mem = o.optJSONObject("memory");
        if (mem != null) {
            for (String k : mem.keySet())
                p.memory.put(k, mem.optInt(k, 0));
        }
        return p;
    }

    /**
     * What is standing inside this place, counted by kind - "barrel x3, cupboard x1".
     *
     * For the manager window, and the reason it is worth having: the commonest mistake when drawing
     * a place is a rectangle that looks right and is a tile short of the thing it was drawn around.
     * A count that says "nothing" while you are looking straight at the barrels tells you that in
     * one glance, where the alternative is running a bot and interpreting why it failed.
     *
     * Only counts what is RENDERED, so it is blank from across the map. That is honest rather than
     * limiting - gobs are not in the map file, so there is nothing to report from afar.
     */
    public java.util.Map<String, Integer> contents(GameUI gui) {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        for (Gob g : gobsWithin(gui)) {
            String kind = kindOf(g);
            if (kind != null)
                out.merge(kind, 1, Integer::sum);
        }
        return out;
    }

    /**
     * A gob's resource name reduced to something a player would recognise.
     *
     * The last path element with its material and variant suffixes taken off, so
     * "gfx/terobjs/cupboard" reads as "cupboard" and the four granite variants under
     * gfx/terobjs/bumlings collapse into one line rather than four. Characters and the player's own
     * body are dropped - every place contains the person standing in it, which is not information.
     */
    private static String kindOf(Gob g) {
        try {
            Resource res = g.getres();
            return (res == null) ? null : kindOf(res.name);
        } catch (Loading | NullPointerException e) {
            return null;
        }
    }

    /** The same reduction applied to a bare resource name, for reading it back out of {@link #memory}. */
    private static String kindOf(String n) {
        if (n == null)
            return null;
        if (n.startsWith("gfx/borka/") || n.contains("/body"))
            return null;
        String tail = n.substring(n.lastIndexOf('/') + 1);
        // Trailing digits are variant numbers ("granite3"), and -m/-f are material suffixes.
        tail = tail.replaceAll("[0-9]+$", "");
        tail = tail.replaceAll("-[a-z]$", "");
        return tail.isEmpty() ? null : tail;
    }

    public String toString() {
        return "place(" + name + " " + roles + " " + w + "x" + h + ")";
    }
}
