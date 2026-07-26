package haven.automated.lp;

import haven.automated.nbots.core.NLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * One character's LP-discovery log: which products (per resource) this character has been seen
 * to obtain, plus which recipes it has been seen to craft. Client-side only - the server never
 * reports discovery state, so this only knows what it has watched happen; on a character that
 * predates the tracking everything reads as undiscovered until obtained once, which self-corrects
 * with use. Stood in for the LP parts of nurgling2's NCharacterInfo.
 *
 * Persisted as lp/<chrid>.json in the client's working directory (next to hitboxes.db et al) -
 * keyed by chrid alone, the same per-character convention Hurricane's own "mapfile/<chrid>" pref
 * uses. Writes happen when {@link #flushIfDirty()} is called from the UI tick and on
 * character/session teardown via LpContext.
 */
public class LpLog {
    private final HashMap<String, ArrayList<String>> lpExplorer = new HashMap<>();
    // Every value ever added to lpExplorer, across all resources - kept in lockstep with it so
    // IsLpExplorerContainsAnywhere() (checked every tick per bark-tracked tree) is an O(1) lookup
    // instead of scanning every resource's discovered-product list.
    private final Set<String> lpExplorerAllValues = new HashSet<>();
    private final Set<String> craftedRecipes = new HashSet<>();

    /** Set when something LP-relevant changed and should be persisted on the next tick. */
    public volatile boolean newLpExplorer = false;

    private final Path file;
    /** Where clearLpExplorer() parks the log it is about to wipe, so a reset can be undone. */
    private final Path backup;

    public LpLog(String chrid) {
        String name = sanitize(chrid);
        this.file = Paths.get("lp", name + ".json");
        this.backup = Paths.get("lp", name + ".cleared.json");
        JSONObject root = read(file);
        if (root != null)
            apply(root);
    }

    private static String sanitize(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public boolean IsLpExplorerContains(String name) {
        synchronized (lpExplorer) {
            return lpExplorer.containsKey(name);
        }
    }

    public boolean IsLpExplorerContains(String name, String var) {
        synchronized (lpExplorer) {
            ArrayList<String> vars = lpExplorer.get(name);
            return vars != null && vars.contains(var);
        }
    }

    // Some products (e.g. "Treebark"/"Tough Bark") are the exact same curiosity regardless of
    // which resource produced them, unlike most products which are uniquely named per resource -
    // for those, discovery needs checking across every resource's list, not just one.
    public boolean IsLpExplorerContainsAnywhere(String var) {
        synchronized (lpExplorer) {
            return lpExplorerAllValues.contains(var);
        }
    }

    public void LpExplorerAdd(String name, String var) {
        synchronized (lpExplorer) {
            lpExplorer.computeIfAbsent(name, k -> new ArrayList<>()).add(var);
            lpExplorerAllValues.add(var);
        }
    }

    public boolean isRecipeCrafted(String recipe) {
        synchronized (craftedRecipes) {
            return craftedRecipes.contains(recipe);
        }
    }

    public void addCraftedRecipe(String recipe) {
        if (recipe == null)
            return;
        synchronized (craftedRecipes) {
            if (!craftedRecipes.add(recipe))
                return;
        }
        newLpExplorer = true;
    }

    /** Total discovered product entries across every resource - what a reset would cost. */
    public int size() {
        synchronized (lpExplorer) {
            int n = 0;
            for (ArrayList<String> vars : lpExplorer.values())
                n += vars.size();
            return n;
        }
    }

    private boolean isEmpty() {
        synchronized (lpExplorer) {
            if (!lpExplorer.isEmpty())
                return false;
        }
        synchronized (craftedRecipes) {
            return craftedRecipes.isEmpty();
        }
    }

    /**
     * Wipes this character's entire LP-discovery log (and the crafted-recipe log alongside it),
     * persists the empty state immediately (so a client closed right after the reset doesn't come
     * back with the old log), and drops the static discovery caches so overlays and markers
     * recompute against the cleared data on the next tick.
     *
     * A copy is parked in the backup file first, so {@link #restoreBackup()} can undo the reset.
     * That copy is written from the LIVE state rather than by copying the JSON on disk: a discovery
     * made since the last flush exists only in memory, and copying the file would silently leave it
     * out of the very snapshot meant to make the reset safe.
     *
     * @return whether a restorable copy was saved.
     */
    public synchronized boolean clearLpExplorer() {
        // An already-empty log has nothing worth preserving, and writing that empty state over the
        // backup would turn a second press of Reset into the thing the backup exists to prevent -
        // destroying what the first press saved.
        boolean saved = !isEmpty() && writeTo(backup);
        synchronized (lpExplorer) {
            lpExplorer.clear();
            lpExplorerAllValues.clear();
        }
        synchronized (craftedRecipes) {
            craftedRecipes.clear();
        }
        write();
        LpExplorer.reset();
        return saved;
    }

    /** Whether there is a saved copy from a previous reset that {@link #restoreBackup()} can use. */
    public boolean hasBackup() {
        return Files.exists(backup);
    }

    /**
     * Puts back the log that the last reset wiped, replacing whatever is recorded now.
     *
     * The backup is parsed BEFORE the live state is touched, so a truncated or hand-edited copy
     * fails harmlessly instead of clearing the log it was supposed to rescue. The backup file is
     * deliberately left in place afterwards: restoring is itself destructive (anything discovered
     * since the reset is dropped), and keeping the copy means that mistake is recoverable too.
     *
     * @return how many discovered products came back, or -1 if there was no readable backup.
     */
    public synchronized int restoreBackup() {
        JSONObject root = read(backup);
        if (root == null)
            return -1;
        synchronized (lpExplorer) {
            lpExplorer.clear();
            lpExplorerAllValues.clear();
        }
        synchronized (craftedRecipes) {
            craftedRecipes.clear();
        }
        int n = apply(root);
        write();
        LpExplorer.reset();
        return n;
    }

    /** Persists if anything changed since the last write; called from the UI tick. */
    public void flushIfDirty() {
        if (!newLpExplorer)
            return;
        newLpExplorer = false;
        write();
    }

    public void write() {
        writeTo(file);
    }

    private boolean writeTo(Path target) {
        try {
            JSONObject root = new JSONObject();
            JSONObject lp = new JSONObject();
            synchronized (lpExplorer) {
                for (HashMap.Entry<String, ArrayList<String>> e : lpExplorer.entrySet())
                    // toArray() is required, not stylistic - see the note in Place.toJson. Passing
                    // the collection compiles and then throws, which is why this file had never
                    // once written itself successfully.
                    lp.put(e.getKey(), new JSONArray(e.getValue().toArray()));
            }
            root.put("lp", lp);
            synchronized (craftedRecipes) {
                root.put("crafted", new JSONArray(craftedRecipes.toArray()));
            }
            Files.createDirectories(target.getParent());
            // Write-then-rename: this file is rewritten in full on every discovery, so a client
            // killed mid-write (alt-F4, crash, power loss) would otherwise leave a truncated JSON
            // that fails to parse on next load - silently costing the character its entire
            // discovery history. The rename is atomic, so the log is always either the old
            // complete version or the new one.
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, root.toString(2).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            NLog.crash("LpLog.write " + target, e);
            return false;
        }
    }

    /** Parses a log file without touching the live state, or null if it's missing/unreadable. */
    private static JSONObject read(Path src) {
        try {
            if (!Files.exists(src))
                return null;
            return new JSONObject(new String(Files.readAllBytes(src), StandardCharsets.UTF_8));
        } catch (Exception e) {
            NLog.crash("LpLog.load " + src, e);
            return null;
        }
    }

    /** Merges a parsed log into the live state. @return discovered products added. */
    private int apply(JSONObject root) {
        int n = 0;
        JSONObject lp = root.optJSONObject("lp");
        if (lp != null) {
            synchronized (lpExplorer) {
                for (String res : lp.keySet()) {
                    JSONArray vars = lp.getJSONArray(res);
                    ArrayList<String> list = new ArrayList<>(vars.length());
                    for (int i = 0; i < vars.length(); i++) {
                        String v = vars.getString(i);
                        list.add(v);
                        lpExplorerAllValues.add(v);
                        n++;
                    }
                    lpExplorer.put(res, list);
                }
            }
        }
        JSONArray crafted = root.optJSONArray("crafted");
        if (crafted != null) {
            synchronized (craftedRecipes) {
                for (int i = 0; i < crafted.length(); i++)
                    craftedRecipes.add(crafted.getString(i));
            }
        }
        return n;
    }
}
