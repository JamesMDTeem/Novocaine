package haven.automated.lp;

import haven.GameUI;
import haven.UI;

/**
 * The LP-assistant's one handle on the live client state. Everything in haven.automated.lp that
 * can't be handed a GameUI directly (static helpers called from overlays, resolvers, the
 * discovery hook) reaches the current session through here instead - the same role
 * NUtils.getGameUI() played in nurgling2. Bound from GameUI's constructor, unbound on destroy,
 * mirroring how the alchemy fork drives its statics from GameUI.tick.
 *
 * Also owns the per-character LpLog lifecycle: the log is created lazily on first access (the
 * session's username isn't attached yet during GameUI's constructor), flushed from tick, and
 * written out on unbind. LP discovery is per-character but LpExplorer's caches are static, so a
 * character switch resets them alongside the new log.
 */
public class LpContext {
    private static volatile GameUI gui;
    private static volatile LpLog log;

    /**
     * Called from GameUI's constructor. Self-cleaning: a new GameUI means a new character (or
     * relog), so any previous character's log is flushed and dropped here - GameUI has no destroy
     * override to hang an unbind on, and this way there's nothing to forget. The log itself is
     * built lazily on first log() call, since ui/session aren't attached yet during construction.
     */
    public static synchronized void bind(GameUI g) {
        LpLog l = log;
        if (l != null)
            l.write();
        log = null;
        gui = g;
        LpExplorer.reset();
    }

    public static GameUI gui() {
        return gui;
    }

    public static UI ui() {
        GameUI g = gui;
        return g != null ? g.ui : null;
    }

    /** The bound character's LP log, or null when no session/character is live yet. */
    public static LpLog log() {
        LpLog l = log;
        if (l != null)
            return l;
        synchronized (LpContext.class) {
            if (log == null) {
                GameUI g = gui;
                if (g == null || g.chrid == null || g.chrid.isEmpty())
                    return null;
                log = new LpLog(g.chrid);
                // A different character's conclusions may still be cached statically.
                LpExplorer.reset();
            }
            return log;
        }
    }

    // Last observed Yesteryear season state, so a season flip can bump the marker generation
    // (which drives an immediate marker re-render) instead of every marker polling the season on
    // a short timer. -1 = not yet sampled.
    private static int lastYesteryear = -1;

    /** Called every frame from GameUI.tick: persists pending discoveries, watches the season. */
    public static void tick() {
        LpLog l = log;
        if (l != null)
            l.flushIfDirty();

        // A season boundary changes which seasonal fruit variant (normal vs "Yesteryear's ") is
        // current, so markers must recompute - but it happens a few times a year, far too rarely
        // to justify a per-marker per-second season check. Sample it once here and nudge the
        // generation only on an actual flip.
        int y = HarvestState.isYesteryearSeason() ? 1 : 0;
        if (y != lastYesteryear) {
            lastYesteryear = y;
            LpExplorer.bumpGeneration();
        }
    }
}
