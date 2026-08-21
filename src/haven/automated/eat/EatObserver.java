package haven.automated.eat;

import haven.BAttrWnd;
import haven.FlowerMenu;
import haven.GItem;
import haven.GameUI;
import haven.Indir;
import haven.ItemInfo;
import haven.Loading;
import haven.ResData;
import haven.Resource;
import haven.automated.cookbook.FoodService;
import haven.res.ui.tt.q.qbuff.QBuff;
import haven.resutil.FoodInfo;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Logs real before/after state around every eat, so the Eating Helper's planner can be calibrated
 * against measurement instead of the wiki. See the "Eating Helper" plan: the variety-reduction
 * coefficients, the satiation formulas, and the tier-invariant check are all either unverified or
 * only wiki-sourced, and {@link BAttrWnd} already reports every one of the values needed to settle
 * them for real - cap, bar contents, satiations, hunger level - on every server push.
 *
 * Attributing a state change to a specific food needs the item, which {@link BAttrWnd} has no way
 * to know on its own. Correlation follows the same assumption the nbots Eat task already relies on
 * (see {@code nbots/task/Eat.java}): only one interact menu is open at a time, so the flower menu
 * that appears immediately after an item's "iact" click belongs to that item. {@link #onIact} notes
 * the candidate and takes the "before" snapshot immediately, since nothing server-side has happened
 * yet at the moment the click is sent; {@link #onFlowerMenuOpened} pairs the very next menu with it;
 * {@link #onFlowerMenuChosen} finalizes the record only if that menu's chosen petal was "Eat". Any
 * ambiguity - a second click before the first menu resolves, no live candidate, quality or FoodInfo
 * not yet loaded - drops the sample rather than guessing, per the plan's "record a confidence flag
 * and discard" rule.
 *
 * Off by default (see {@code OptWnd.eatObserverCheckBox}). Every hook checks that flag first and
 * costs nothing when it's off. When it's on, every method here is defensive about exceptions -
 * particularly {@link Loading}, which the resources involved can throw at any time - because these
 * hooks are spliced into core BAttrWnd/FlowerMenu/WItem update paths and must never be the reason
 * the real attribute window breaks.
 *
 * Known gap: current character Energy isn't captured yet (no live-readable field for it was found
 * while wiring this up), so the "eating below 8000% Energy gives only Energy" claim from the Hunger
 * wiki page can't be checked from this log alone yet - only FEP/hunger/satiation/variety can.
 *
 * The log carries two kinds of line, distinguished by {@code "type"}: {@code "eat"} is the
 * correlated before/after record described above, one per confirmed eat. The other four
 * ({@code "food"}, {@code "trig"}, {@code "glut"}, {@code "satiation"}) are a raw, uncorrelated
 * stream - one line per {@link BAttrWnd} push, exactly as it arrived, with no attempt to attribute
 * it to anything. They exist because before/after snapshotting has a real blind spot: a single food
 * whose FEP clears the bar on its own fills and resets it before the "after" snapshot can see the
 * accumulated total, so an eat that instantly overflows the bar (a real observed case, not just a
 * hypothetical - see the plan) looks identical, from the "eat" record alone, to a hook that silently
 * captured nothing. The raw stream is what settles which one actually happened: replaying it against
 * an eat's timestamp shows the true sequence of pushes in between.
 */
public class EatObserver {
    private static volatile String chrid;

    private static final Object lock = new Object();

    /** Copy-on-write snapshot of everything BAttrWnd has last reported. Replaced, never mutated. */
    private static final class State {
        final double cap;
        final Map<String, Double> bar;
        final Map<String, Double> satiation;
        final double glut, gmod;

        State(double cap, Map<String, Double> bar, Map<String, Double> satiation, double glut, double gmod) {
            this.cap = cap;
            this.bar = bar;
            this.satiation = satiation;
            this.glut = glut;
            this.gmod = gmod;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("cap", cap);
            o.put("bar", new JSONObject(bar));
            o.put("satiation", new JSONObject(satiation));
            o.put("glut", glut);
            o.put("gmod", gmod);
            return o;
        }
    }

    private static volatile State state =
            new State(0, new LinkedHashMap<>(), new LinkedHashMap<>(), 0, 0);

    /** Most recent {@code ftrig} - a level-up firing - so a pending eat can tell if one landed
     *  inside its before/after window. */
    private static volatile String lastTrigName = null;
    private static volatile long lastTrigTs = 0;

    private static final class PendingClick {
        final String name;
        final double quality;
        final State before;
        final long ts;

        PendingClick(String name, double quality, State before, long ts) {
            this.name = name;
            this.quality = quality;
            this.before = before;
            this.ts = ts;
        }
    }

    /** How long a click may wait for its flower menu, or a menu for its "act" confirmation, before
     *  being treated as unresolved and dropped. Interact menus are a direct request/response over
     *  the same connection the rest of the game runs on - this is generous, not a tight budget. */
    private static final long CORRELATION_WINDOW_MS = 3000;

    /** Grace period after "Eat" is confirmed before the "after" snapshot is taken, so a food/glut/
     *  const update that lands a beat after "act" (ordering between them isn't guaranteed) is still
     *  captured rather than missed. */
    private static final long AFTER_CAPTURE_DELAY_MS = 300;

    private static PendingClick pendingClick = null;

    private static final WeakHashMap<FlowerMenu, PendingClick> menuOwners = new WeakHashMap<>();

    private static Path logFile;

    public static synchronized void bind(GameUI g) {
        chrid = (g != null) ? g.chrid : null;
        logFile = null;
        synchronized (lock) {
            pendingClick = null;
            menuOwners.clear();
        }
    }

    private static boolean enabled() {
        return haven.OptWnd.eatObserverCheckBox != null && haven.OptWnd.eatObserverCheckBox.a;
    }

    // ------------------------------------------------------------------ BAttrWnd hooks

    public static void onFoodBar(double cap, List<BAttrWnd.FoodMeter.El> els) {
        if (!enabled())
            return;
        try {
            Map<String, Double> bar = new LinkedHashMap<>();
            for (BAttrWnd.FoodMeter.El el : els) {
                String name = resolveEventName(el);
                bar.merge(name, el.a, Double::sum);
            }
            synchronized (lock) {
                state = new State(cap, bar, state.satiation, state.glut, state.gmod);
            }
            JSONObject raw = new JSONObject();
            raw.put("type", "food");
            raw.put("ts", System.currentTimeMillis());
            raw.put("cap", cap);
            raw.put("bar", new JSONObject(bar));
            write(raw);
        } catch (Exception e) {
            // Never let a logging hook break the real FEP bar it's piggybacking on.
        }
    }

    private static String resolveEventName(BAttrWnd.FoodMeter.El el) {
        try {
            return el.ev().nm;
        } catch (Loading l) {
            return "?loading";
        } catch (Exception e) {
            return "?unknown";
        }
    }

    public static void onFoodTrig(Indir<Resource> ev) {
        if (!enabled())
            return;
        try {
            String name;
            try {
                name = ev.get().flayer(BAttrWnd.FoodMeter.Event.class).nm;
            } catch (Loading l) {
                name = "?loading";
            } catch (Exception e) {
                name = ev.get().name;
            }
            lastTrigName = name;
            lastTrigTs = System.currentTimeMillis();
            JSONObject raw = new JSONObject();
            raw.put("type", "trig");
            raw.put("ts", lastTrigTs);
            raw.put("stat", name);
            write(raw);
        } catch (Exception e) {
            // ditto
        }
    }

    /** Hunger decays continuously in real time, so the server pushes glut/gmod far more often than
     *  anything eat-related changes - one real session logged a glut push roughly every 0.8s over
     *  20+ hours, 99.7% of that file. {@link #state} still tracks every push (before/after snapshots
     *  need the live value), but the raw stream only samples it, since a decay curve this smooth
     *  doesn't need sub-second resolution to be useful and the alternative drowns the eat-relevant
     *  lines this stream exists to make checkable. */
    private static final long GLUT_LOG_INTERVAL_MS = 5000;
    private static volatile long lastGlutLogTs = 0;

    public static void onGlut(double glut, double lglut, double gmod) {
        if (!enabled())
            return;
        try {
            synchronized (lock) {
                state = new State(state.cap, state.bar, state.satiation, glut, gmod);
            }
            long now = System.currentTimeMillis();
            if (now - lastGlutLogTs < GLUT_LOG_INTERVAL_MS)
                return;
            lastGlutLogTs = now;
            JSONObject raw = new JSONObject();
            raw.put("type", "glut");
            raw.put("ts", now);
            raw.put("glut", glut);
            raw.put("gmod", gmod);
            write(raw);
        } catch (Exception e) {
            // ditto
        }
    }

    public static void onSatiation(ResData t, double a) {
        if (!enabled())
            return;
        try {
            String resName;
            try {
                resName = t.res.get().name;
            } catch (Loading l) {
                resName = "?loading";
            } catch (Exception e) {
                resName = "?unknown";
            }
            // Two logically distinct satiation groups can share one representative icon (observed:
            // "gfx/invobjs/meat" carrying more than one live penalty value at once), so the resource
            // name alone isn't always a unique key. sdt is the rest of what the server actually sent
            // for this group - append it (hex, since it's arbitrary binary) whenever it's non-empty
            // so two same-icon groups don't collide into one entry here the way they would upstream.
            String name = resName;
            try {
                byte[] sdt = t.sdt.fin();
                if (sdt.length > 0)
                    name = resName + "#" + toHex(sdt);
            } catch (Exception e) {
                // sdt inspection is a best-effort disambiguator, not load-bearing - fall back to the
                // resource name alone rather than lose the sample.
            }
            // a is the server's raw value; FoodInfo.tipimg treats (1 - el.a), itself 1 - a again,
            // as the FEP/hunger multiplier - so the penalty this log wants is 1 - a directly. See
            // the plan's satiation section for the derivation.
            double penalty = 1.0 - a;
            synchronized (lock) {
                Map<String, Double> satiation = new LinkedHashMap<>(state.satiation);
                if (a >= 1.0)
                    satiation.remove(name);
                else
                    satiation.put(name, penalty);
                state = new State(state.cap, state.bar, satiation, state.glut, state.gmod);
            }
            JSONObject raw = new JSONObject();
            raw.put("type", "satiation");
            raw.put("ts", System.currentTimeMillis());
            raw.put("res", name);
            raw.put("raw", a);
            raw.put("penalty", penalty);
            write(raw);
        } catch (Exception e) {
            // ditto
        }
    }

    // ------------------------------------------------------------------ interact/eat correlation

    /** Call at every site that sends an item's "iact" click - see WItem.java and nbots Eat.java. */
    public static void onIact(GItem item) {
        if (!enabled() || item == null)
            return;
        try {
            PendingClick p = capture(item);
            if (p == null)
                return;
            synchronized (lock) {
                // A second click before the first resolved is exactly the ambiguity the plan says
                // to discard rather than guess through - drop both, don't queue.
                pendingClick = p;
            }
        } catch (Loading l) {
            // Info not ready this pass - same as Eat.java's own isFood(): reconsidered next time.
        } catch (Exception e) {
            // Never let a logging hook break the real interact click.
        }
    }

    /**
     * Call where a food item is "take"n while the table's Feast cursor is active (see
     * {@code GItem.wdgmsg}'s "take" branch) - eating there never opens a FlowerMenu at all, so
     * there is no "act"/"Eat" confirmation to wait for. The click itself is the confirmed eat.
     */
    public static void onFeastEat(GItem item) {
        if (!enabled() || item == null)
            return;
        try {
            PendingClick p = capture(item);
            if (p == null)
                return;
            FoodService.scheduler.schedule(() -> finish(p), AFTER_CAPTURE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Loading l) {
            // Info not ready this pass - nothing to do; this click just goes unlogged.
        } catch (Exception e) {
            // Never let a logging hook break the real feast click.
        }
    }

    /** Shared by {@link #onIact} and {@link #onFeastEat}: null if the item isn't (yet, resolvably)
     *  food, otherwise a snapshot of it and the "before" state at this exact moment. */
    private static PendingClick capture(GItem item) throws Loading {
        List<ItemInfo> infos = item.info();
        FoodInfo fi = ItemInfo.find(FoodInfo.class, infos);
        if (fi == null)
            return null; // not food (or not resolved yet) - nothing worth tracking this click for
        QBuff qb = ItemInfo.find(QBuff.class, infos);
        double quality = (qb != null) ? qb.q : 10.0;
        return new PendingClick(item.getname(), quality, state, System.currentTimeMillis());
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b)
            sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Call from FlowerMenu.added() - pairs the just-opened menu with the most recent unresolved
     *  click, if one exists and is still within the correlation window. */
    public static void onFlowerMenuOpened(FlowerMenu menu) {
        if (!enabled() || menu == null)
            return;
        try {
            synchronized (lock) {
                PendingClick p = pendingClick;
                if (p == null)
                    return;
                pendingClick = null; // consumed either way - one menu per click
                if (System.currentTimeMillis() - p.ts > CORRELATION_WINDOW_MS)
                    return;
                menuOwners.put(menu, p);
            }
        } catch (Exception e) {
            // Never let a logging hook break the real menu.
        }
    }

    /** Call from FlowerMenu.uimsg's "act" branch with the resolved petal's name. */
    public static void onFlowerMenuChosen(FlowerMenu menu, String petalName) {
        if (!enabled() || menu == null)
            return;
        try {
            PendingClick p;
            synchronized (lock) {
                p = menuOwners.remove(menu);
            }
            if (p == null || !"Eat".equals(petalName))
                return;
            if (System.currentTimeMillis() - p.ts > CORRELATION_WINDOW_MS)
                return;
            FoodService.scheduler.schedule(() -> finish(p), AFTER_CAPTURE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Never let a logging hook break the real menu.
        }
    }

    private static void finish(PendingClick p) {
        try {
            State after = state;
            JSONObject rec = new JSONObject();
            rec.put("type", "eat");
            rec.put("ts", p.ts);
            rec.put("food", p.name);
            rec.put("quality", p.quality);
            rec.put("before", p.before.toJson());
            rec.put("after", after.toJson());
            if (lastTrigTs >= p.ts && lastTrigTs <= System.currentTimeMillis())
                rec.put("levelup", lastTrigName);
            else
                rec.put("levelup", JSONObject.NULL);
            write(rec);
        } catch (Exception e) {
            // Best-effort logging; a failed write here must never surface to the player.
        }
    }

    // ------------------------------------------------------------------ persistence

    private static synchronized Path logFile() {
        if (logFile == null) {
            String id = chrid;
            String name = (id == null || id.isEmpty())
                    ? "unknown" : id.replaceAll("[^A-Za-z0-9._-]", "_");
            logFile = Paths.get("eatlog", name + ".jsonl");
        }
        return logFile;
    }

    private static synchronized void write(JSONObject rec) {
        try {
            Path file = logFile();
            Files.createDirectories(file.getParent());
            Files.write(file, (rec.toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // Best-effort logging; a failed write here must never surface to the player.
        }
    }
}
