package haven.automated;

import java.awt.Color;

import haven.GameUI;
import haven.Gob;
import haven.OptWnd;

/**
 * Passive safety escape: fires hearth-fire travel the moment an unknown (non-friend) player is
 * seen, so an unattended character can pull itself home rather than sit in reach of an intruder.
 *
 * "Unknown" is decided by {@link Gob#isFriend()}, which exempts party members, kin-group members
 * (per the per-colour "exclude from aggro" toggles under Advanced Settings), and village/realm
 * members - so the escape is only armed against players the character has not marked safe. This
 * deliberately reuses the existing aggro-exclusion checkboxes as the per-colour config rather
 * than adding a parallel set.
 *
 * The trigger is edge-triggered from {@code Gob.ctick} (see the one-line call next to
 * {@code playPlayerAlarm}) and rate-limited by {@link #COOLDOWN_MS}: the action fires at most once
 * per cooldown window, and the hearth channel (~4.8s, measured in {@code HearthTravel}) completes
 * well inside it. The action itself is a non-blocking {@code gui.act("travel", "hearth")}; the
 * passive watcher in {@code HearthTravel} records the landing for the travel-vs-walk decision.
 *
 * Firing is unconditional with respect to running bots on purpose: it is a safety escape, and the
 * character coming home is the correct outcome even mid-task. Weariness is spent per travel
 * (unreadable from the client), so this is expected to be enabled only when a character is being
 * left unattended; toggle it off to disable.
 */
public class AutoHearth {
    /** Minimum interval between two auto-hearth triggers, in milliseconds. */
    private static final long COOLDOWN_MS = 60_000L;

    /** Last auto-hearth trigger time, or 0 when never fired this session. */
    private static volatile long lastFiredAt = 0;

    private AutoHearth() {}

    /**
     * Called from {@code Gob.ctick} once a player gob's identity is known. No-op unless the
     * auto-hearth toggle is on and the gob is a real, non-friend player in sight.
     */
    public static void check(Gob gob) {
        if (!OptWnd.autoHearthOnUnknownPlayerCheckBox.a)
            return;
        if (gob == null || gob.glob == null || gob.glob.sess == null || gob.glob.sess.ui == null)
            return;
        if (gob.getres() == null || !gob.getres().name.equals("gfx/borka/body"))
            return;
        GameUI gui = gob.glob.sess.ui.gui;
        if (gui == null || gui.map == null)
            return;
        if (gob.id == gui.map.plgob)
            return;
        if (gob.isFriend())
            return;
        long now = System.currentTimeMillis();
        if (now - lastFiredAt < COOLDOWN_MS)
            return;
        lastFiredAt = now;
        gui.msg("Auto-Hearth: unknown player spotted, traveling to hearth fire.", Color.WHITE);
        gui.act("travel", "hearth");
    }
}
