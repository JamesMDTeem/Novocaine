package haven;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A bounded, self-disposing cache of textures rendered from a key.
 *
 * Widget draw code repeatedly needs a texture of some small derived thing - a stack count, a
 * seasoning icon, a kin's name - and the obvious way to write that is to render it where it is
 * drawn. That is a mistake with a large bill attached, because a {@code Tex} is not a picture: the
 * first draw uploads it to the GPU, and unless something disposes it, the texture is freed only
 * when the finalizer eventually gets to it. Written inside a {@code draw()} that is exactly one
 * GPU texture created, uploaded and abandoned <em>per item per frame</em>.
 *
 * That is not a hypothetical. A friend's client was measured at ~50 texture allocations per frame
 * with a full cellar open, and framerate fell monotonically with that number - 128 fps at four
 * allocations a frame, 30 fps at fifty. Every one of them was a distinct object: 48,964 unique
 * identities in one log.
 *
 * So: render once, keep it, draw the same texture every frame after.
 *
 * <h2>Bounded on purpose</h2>
 *
 * The keys here come from the server - item counts, player names - so an unbounded map is just a
 * slower leak with better manners. Past {@code max} entries the least recently used one is
 * disposed and dropped, which is safe because entries are fetched and drawn in the same breath:
 * for an entry to be evicted between the two, a single frame would have to touch more distinct
 * keys than the whole cache holds.
 *
 * <h2>UI thread only</h2>
 *
 * Unsynchronised, because every caller is a {@code draw()} and there is only one of those. Do not
 * call it from a bot thread; render the texture there and you will crash the GL thread instead,
 * which is a worse bug than the one this fixes.
 */
public class TexCache<K> {
    private final Function<K, Tex> render;
    private final Map<K, Tex> back;

    public TexCache(int max, Function<K, Tex> render) {
        this.render = render;
        this.back = new LinkedHashMap<K, Tex>(16, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<K, Tex> eldest) {
                if (size() <= max)
                    return (false);
                /* Dispose on the way out. Dropping the reference alone would put us back where we
                 * started, just at a far lower rate: the GL texture would live until the finalizer
                 * noticed, which is the whole failure mode this class exists to end. */
                Tex old = eldest.getValue();
                if (old != null)
                    old.dispose();
                return (true);
            }
        };
    }

    /**
     * The texture for this key, rendering it on first ask.
     *
     * A null from the render function is cached as a miss-free absence: it is not stored, so a key
     * that could not be rendered yet - a resource still loading, say - is retried next frame.
     */
    public Tex get(K key) {
        Tex tex = back.get(key);
        if (tex == null) {
            tex = render.apply(key);
            if (tex != null)
                back.put(key, tex);
        }
        return (tex);
    }

    /** Drops and disposes everything. For when the thing the textures depend on has changed. */
    public void clear() {
        for (Tex tex : back.values()) {
            if (tex != null)
                tex.dispose();
        }
        back.clear();
    }

    public int size() {
        return (back.size());
    }
}
