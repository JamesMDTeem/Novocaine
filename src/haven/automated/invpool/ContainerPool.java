package haven.automated.invpool;

import haven.GItem;
import haven.GameUI;
import haven.Inventory;
import haven.Loading;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.res.ui.stackinv.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the player can reach, gathered from every container they have opened rather than only the
 * ones still on screen.
 *
 * A helper that reads only open windows makes the player hold six chests open at once to get one
 * answer. This remembers each container's contents when its window goes away, so containers can be
 * opened one at a time and still counted together. The memory lasts exactly as long as the helper
 * is enabled -- {@link #clear()} on disable -- because a stale chest three regions away is worse
 * than no chest at all.
 *
 * <h2>What is live and what is remembered</h2>
 * An open container is re-read every refresh, so taking ingredients out of it, or crafting with
 * them, shows up immediately. The player's own inventory and belt are containers like any other by
 * this definition, and being always open they are always live -- which is what makes consumed items
 * disappear from the count as soon as they are consumed. Only a container whose window has been
 * closed is frozen, and it thaws the moment it is opened again.
 *
 * <h2>Where this cannot be exact</h2>
 * The server hands out a fresh widget id every time a container is opened, and nothing in the
 * client ties that window back to the object in the world it belongs to. So reopening a chest looks
 * exactly like opening a second, identical one. The compromise: a newly opened container whose
 * contents match a remembered one exactly is taken to be that one, and replaces it. That covers
 * reopening a chest you did not touch. Reopen one you did take from and its old contents are still
 * remembered alongside the new, counting some items twice -- so {@link #sources()} is meant to be
 * shown to the player and {@link #forget()} kept within reach, rather than the drift being left to
 * be discovered as a recipe that cannot be brewed.
 *
 * @param <T> what the caller pulls out of each item -- a curiosity, an ingredient name.
 */
public class ContainerPool<T> {
    /** Reads one item into zero or more entries; stacks are descended into before it is called. */
    public interface Extractor<T> {
        void take(WItem item, List<T> into);
    }

    /** One container's remembered contents. */
    public static class Source {
        public final String name;
        public final List<Object> items;
        /** Item names as read, sorted, used to recognise the same container opened again. */
        public final List<String> fingerprint;
        public boolean open;

        Source(String name, List<Object> items, List<String> fingerprint, boolean open) {
            this.name = name;
            this.items = items;
            this.fingerprint = fingerprint;
            this.open = open;
        }

        public int size() {
            return items.size();
        }
    }

    /** Player-side containers worth counting. Equipment, Study and the sheet hold no stock. */
    private static final List<String> OWN_CONTAINERS = List.of("Inventory", "Belt");

    /** Keyed by the container inventory's widget id, which is unique per opening. */
    private final Map<Integer, Source> sources = new LinkedHashMap<Integer, Source>();

    /** Forgets every container. Called when the helper is switched off. */
    public void clear() {
        sources.clear();
    }

    /** Forgets containers that are no longer open, keeping the live ones. */
    public void forget() {
        sources.entrySet().removeIf(e -> !e.getValue().open);
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** How many containers are being counted right now. */
    public int sourceCount() {
        return sources.size();
    }

    /**
     * Whether anything other than the player's own inventory and belt is being counted, open or
     * remembered. Helpers that only make sense in front of a chest use this to decide whether to be
     * on screen: counting the backpack should change the numbers, not put the window up permanently.
     */
    public boolean hasExternal() {
        for (Source s : sources.values()) {
            if (!OWN_CONTAINERS.contains(s.name))
                return true;
        }
        return false;
    }

    /** One entry per container, open ones first, for showing the player what is counted. */
    public List<Source> sources() {
        List<Source> out = new ArrayList<Source>(sources.values());
        out.sort(Comparator.comparing((Source s) -> !s.open).thenComparing(s -> s.name));
        return out;
    }

    /** Everything currently counted, live containers and remembered ones together. */
    @SuppressWarnings("unchecked")
    public List<T> items() {
        List<T> all = new ArrayList<T>();
        for (Source s : sources.values()) {
            for (Object o : s.items)
                all.add((T) o);
        }
        return all;
    }

    /**
     * Re-reads every open container and marks the rest as remembered.
     *
     * A container still loading is left alone rather than recorded as empty: an empty read would
     * replace a good snapshot with nothing, and the next refresh is a fraction of a second away.
     */
    public void refresh(GameUI gui, Extractor<T> extractor) {
        if (gui == null)
            return;

        for (Source s : sources.values())
            s.open = false;

        /* The player's own inventory is read straight off GameUI rather than through its window,
         * because closing the inventory panel hides the window without the backpack ceasing to
         * exist. Going through the window would freeze it into a remembered snapshot the moment the
         * panel was closed, and consumed items would stop disappearing from the count. Keyed by the
         * inventory's own widget id, so reading it here and again below costs nothing. */
        if (gui.maininv != null)
            read("Inventory", gui.maininv, extractor);

        for (Window w : gui.getAllWindows()) {
            if (w.cap == null || !w.visible)
                continue;
            if (Inventory.PLAYER_INVENTORY_NAMES.contains(w.cap) && !OWN_CONTAINERS.contains(w.cap))
                continue;
            for (Widget child : w.children()) {
                Inventory inv = Inventory.fromWidget(child);
                if (inv != null)
                    read(w.cap, inv, extractor);
            }
        }
    }

    private void read(String name, Inventory inv, Extractor<T> extractor) {
        int key = inv.wdgid();
        if (key < 0)
            return;

        List<Object> found = new ArrayList<Object>();
        List<String> fingerprint = new ArrayList<String>();
        for (WItem wi : inv.getAllItems())
            collect(wi, extractor, found, fingerprint);
        fingerprint.sort(Comparator.naturalOrder());

        /* A container that reads as empty while it is still arriving must not overwrite what we
         * already have for it; a genuinely empty one has nothing to contribute either way. */
        Source existing = sources.get(key);
        if (found.isEmpty() && existing != null) {
            existing.open = true;
            return;
        }

        if (existing == null)
            dropMatchingMemory(name, fingerprint);

        sources.put(key, new Source(name, found, fingerprint, true));
    }

    /**
     * Drops a remembered container that this newly opened one is evidently the same as: same
     * caption, same contents. Without it, closing and reopening a chest counts it twice.
     */
    private void dropMatchingMemory(String name, List<String> fingerprint) {
        sources.entrySet().removeIf(e -> {
            Source s = e.getValue();
            return !s.open && s.name.equals(name) && s.fingerprint.equals(fingerprint);
        });
    }

    /**
     * A stacked item is not the item it looks like: the slot holds a wrapper whose own info carries
     * nothing, and the real items are the GItems inside its {@link ItemStack}. Stopping at the top
     * level reads a chest of stacked leeches as an empty chest.
     */
    private void collect(WItem wi, Extractor<T> extractor, List<Object> into, List<String> fingerprint) {
        try {
            GItem item = wi.item;
            if (item.contents instanceof ItemStack) {
                ItemStack stack = (ItemStack) item.contents;
                for (GItem gi : stack.order) {
                    WItem member = stack.wmap.get(gi);
                    if (member != null)
                        collect(member, extractor, into, fingerprint);
                }
                return;
            }
            String name = item.getname();
            if (name != null && !name.isEmpty())
                fingerprint.add(name);
            List<T> taken = new ArrayList<T>(1);
            extractor.take(wi, taken);
            into.addAll(taken);
        } catch (Loading l) {
            /* Still arriving from the server; it will be here next refresh. */
        } catch (RuntimeException e) {
            /* One bad item must not cost us the other thirty in the container. Resource loading
             * raises LoadException and friends, none of which are Loading. */
        }
    }
}
