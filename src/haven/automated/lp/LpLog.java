package haven.automated.lp;

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

    public LpLog(String chrid) {
        this.file = Paths.get("lp", sanitize(chrid) + ".json");
        load();
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

    /**
     * Wipes this character's entire LP-discovery log (and the crafted-recipe log alongside it),
     * persists the empty state immediately (so a client closed right after the reset doesn't come
     * back with the old log), and drops the static discovery caches so overlays and markers
     * recompute against the cleared data on the next tick.
     */
    public void clearLpExplorer() {
        synchronized (lpExplorer) {
            lpExplorer.clear();
            lpExplorerAllValues.clear();
        }
        synchronized (craftedRecipes) {
            craftedRecipes.clear();
        }
        write();
        LpExplorer.reset();
    }

    /** Persists if anything changed since the last write; called from the UI tick. */
    public void flushIfDirty() {
        if (!newLpExplorer)
            return;
        newLpExplorer = false;
        write();
    }

    public void write() {
        try {
            JSONObject root = new JSONObject();
            JSONObject lp = new JSONObject();
            synchronized (lpExplorer) {
                for (HashMap.Entry<String, ArrayList<String>> e : lpExplorer.entrySet())
                    lp.put(e.getKey(), new JSONArray(e.getValue()));
            }
            root.put("lp", lp);
            synchronized (craftedRecipes) {
                root.put("crafted", new JSONArray(craftedRecipes));
            }
            Files.createDirectories(file.getParent());
            // Write-then-rename: this file is rewritten in full on every discovery, so a client
            // killed mid-write (alt-F4, crash, power loss) would otherwise leave a truncated JSON
            // that fails to parse on next load - silently costing the character its entire
            // discovery history. The rename is atomic, so the log is always either the old
            // complete version or the new one.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, root.toString(2).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            NLog.crash("LpLog.write " + file, e);
        }
    }

    private void load() {
        try {
            if (!Files.exists(file))
                return;
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONObject lp = root.optJSONObject("lp");
            if (lp != null) {
                for (String res : lp.keySet()) {
                    JSONArray vars = lp.getJSONArray(res);
                    ArrayList<String> list = new ArrayList<>(vars.length());
                    for (int i = 0; i < vars.length(); i++) {
                        String v = vars.getString(i);
                        list.add(v);
                        lpExplorerAllValues.add(v);
                    }
                    lpExplorer.put(res, list);
                }
            }
            JSONArray crafted = root.optJSONArray("crafted");
            if (crafted != null) {
                for (int i = 0; i < crafted.length(); i++)
                    craftedRecipes.add(crafted.getString(i));
            }
        } catch (Exception e) {
            NLog.crash("LpLog.load " + file, e);
        }
    }
}
