package haven.automated.alchemy;

import haven.ItemSpec;
import haven.Loading;
import haven.UI;
import haven.Widget;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the Alchemy Book's model.
 *
 * The book is not part of this source tree: the server ships it as the resource
 * {@code ui/alchbook}, whose code layers define {@code haven.res.ui.alchbook.*}.
 * Those classes are loaded by the resource classloader, so we can only reach them
 * reflectively -- which also means the compiler cannot check any of it. The
 * scratchpad script {@code check-alchbook-contract.sh} asserts every member named
 * here still exists; run it after a client update before trusting a silent result.
 *
 * Book entries never pass through {@link haven.GItem#info()}. They are
 * {@code EffectSpec} owners that call {@code ItemInfo.buildinfo} inside the
 * resource code, which is why the old item-pipeline hook saw nothing.
 *
 * Reading is only safe on the UI thread: {@code RecipeList.tick} / {@code EffectList.tick}
 * mutate the very maps we walk. Call from {@link haven.GameUI#tick}, never a timer.
 */
public class AlchemyBook {
    public static final String BOOK_CLASS = "haven.res.ui.alchbook.Book";

    private static final Map<String, Field> fields = new ConcurrentHashMap<>();
    private static final Map<String, Method> methods = new ConcurrentHashMap<>();

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        String key = cls.getName() + "#" + name;
        Field f = fields.get(key);
        if (f == null) {
            f = cls.getField(name);
            fields.put(key, f);
        }
        return f;
    }

    private static Object get(Object obj, String name) throws ReflectiveOperationException {
        return field(obj.getClass(), name).get(obj);
    }

    /** {@code EffectInfo.desc()} -- the effect's display name, e.g. "Quicksilver Poisoning". */
    private static String desc(Object effectInfo) throws ReflectiveOperationException {
        String key = effectInfo.getClass().getName() + "#desc";
        Method m = methods.get(key);
        if (m == null) {
            m = effectInfo.getClass().getMethod("desc");
            m.setAccessible(true);
            methods.put(key, m);
        }
        try {
            return (String) m.invoke(effectInfo);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // reflection wraps the cause; Loading must stay Loading so the
            // caller can skip this entry and retry on the next poll
            if (e.getCause() instanceof Loading)
                throw (Loading) e.getCause();
            throw e;
        }
    }

    /** Depth-first search of the widget tree for the server-created Book widget. */
    public static Widget find(UI ui) {
        if (ui == null || ui.root == null)
            return null;
        return find(ui.root);
    }

    private static Widget find(Widget wdg) {
        if (BOOK_CLASS.equals(wdg.getClass().getName()))
            return wdg;
        for (Widget ch : wdg.children()) {
            Widget hit = find(ch);
            if (hit != null)
                return hit;
        }
        return null;
    }

    /**
     * Snapshot both tabs. Returns null if the book widget does not exist yet
     * (character has not unlocked alchemy, or the server has not sent it).
     *
     * Entries whose resources are still downloading throw {@link Loading} and are
     * skipped; the next poll picks them up.
     */
    public static JSONObject snapshot(UI ui) throws ReflectiveOperationException {
        Widget book = find(ui);
        if (book == null)
            return null;

        JSONObject out = new JSONObject();
        out.put("ingredients", ingredients(get(book, "el")));
        out.put("elixirs", elixirs(get(book, "rl")));
        return out;
    }

    /** EffectList.knowledge : Map&lt;Input, KnownEffects&gt; -- the Ingredients tab. */
    private static JSONArray ingredients(Object effectList) throws ReflectiveOperationException {
        JSONArray arr = new JSONArray();
        Object map = get(effectList, "knowledge");
        for (Object ik : ((Map<?, ?>) map).values()) {
            try {
                JSONObject o = new JSONObject();
                o.put("input", input(get(ik, "input")));
                o.put("effects", effects(get(ik, "effs")));
                arr.put(o);
            } catch (Loading l) {
                // resource still loading -- next poll will catch it
            }
        }
        return arr;
    }

    /** RecipeList.recipes : Map&lt;RKey, Recipe&gt; -- the Elixirs tab. */
    private static JSONArray elixirs(Object recipeList) throws ReflectiveOperationException {
        JSONArray arr = new JSONArray();
        Object map = get(recipeList, "recipes");
        for (Object rcp : ((Map<?, ?>) map).values()) {
            try {
                JSONObject o = new JSONObject();
                o.put("elixir", spec((ItemSpec) get(rcp, "rcp")));
                JSONArray inputs = new JSONArray();
                for (Object inp : (List<?>) get(rcp, "inputs"))
                    inputs.put(input(inp));
                o.put("inputs", inputs);
                o.put("effects", effects(get(rcp, "effects")));
                // mmeffects backs the book's "Negatives" column
                o.put("negatives", effects(get(rcp, "mmeffects")));
                arr.put(o);
            } catch (Loading l) {
                // resource still loading -- next poll will catch it
            }
        }
        return arr;
    }

    /**
     * Input -&gt; {res, name, sub[]}. {@code sub} is the nested contributor list:
     * for an elixir input it names which ingredients contributed that slot.
     */
    private static JSONObject input(Object inp) throws ReflectiveOperationException {
        JSONObject o = spec((ItemSpec) get(inp, "type"));
        List<?> sub = (List<?>) get(inp, "sub");
        if (!sub.isEmpty()) {
            JSONArray subs = new JSONArray();
            for (Object s : sub)
                subs.put(input(s));
            o.put("sub", subs);
        }
        return o;
    }

    private static JSONObject spec(ItemSpec spec) {
        JSONObject o = new JSONObject();
        o.put("res", spec.getres().name);
        o.put("name", spec.name());
        return o;
    }

    private static JSONArray effects(Object effs) throws ReflectiveOperationException {
        JSONArray arr = new JSONArray();
        for (Object eff : (Collection<?>) effs)
            arr.put(desc(eff));
        return arr;
    }
}
