package haven.automated;

import haven.Coord2d;
import haven.Coord3f;
import haven.GameUI;
import haven.Gob;
import haven.KeyBinding;
import haven.KeyMatch;
import haven.MapView;
import haven.OCache;
import haven.Coord;
import haven.Utils;
import haven.Widget;

import java.awt.event.KeyEvent;

/**
 * Walking with four held keys (after brodgar-io-client's WASD Movement addon).
 *
 * The keys go by the screen, not by the way the character faces: forward walks up the screen,
 * back down it, left and right across it, and two together walk the diagonal between. The
 * direction is solved through the view's own projection - the same three-probe inverse the RTS
 * camera pans with - so it holds for every camera, and turning the camera with a key still held
 * turns the walk with it. Let go of everything and the character stops.
 *
 * The four bindings start unbound, so nothing changes until a player assigns them. A key only
 * walks when nothing else wanted it: the press arrives through GameUI.globtype, which a focused
 * text field never lets it reach, so typing in chat writes rather than walks. A left click on
 * the map cancels the walk, and losing window focus lets go of every key, so alt-tab does not
 * leave the character walking.
 */
public class WasdWalk {
    public static final KeyBinding kb_fwd = KeyBinding.get("wasd-forward", KeyMatch.nil);
    public static final KeyBinding kb_left = KeyBinding.get("wasd-left", KeyMatch.nil);
    public static final KeyBinding kb_back = KeyBinding.get("wasd-back", KeyMatch.nil);
    public static final KeyBinding kb_right = KeyBinding.get("wasd-right", KeyMatch.nil);

    /** How far ahead the walk target sits: a few tiles, so the character never reaches it between re-sends. */
    private static final double LEAD = 44.0;
    /** How often the target is re-sent while a key is held, so it stays ahead and follows the camera. */
    private static final double RESEND = 0.25;

    private static boolean fwd, left, back, right;
    private static boolean walking = false;
    private static double sx = 0, sy = 0, last = 0;

    /** A key press no widget took. True if it was one of ours. */
    public static boolean keydown(GameUI gui, Widget.GlobKeyEvent ev) {
        if (kb_fwd.key().match(ev)) fwd = true;
        else if (kb_left.key().match(ev)) left = true;
        else if (kb_back.key().match(ev)) back = true;
        else if (kb_right.key().match(ev)) right = true;
        else return false;
        tick(gui, true);
        return true;
    }

    /** Every key release the client sees. Matched on the key alone, since modifiers may have changed. */
    public static void keyup(KeyEvent ev) {
        if (!(fwd || left || back || right))
            return;
        if (is(kb_fwd, ev)) fwd = false;
        if (is(kb_left, ev)) left = false;
        if (is(kb_back, ev)) back = false;
        if (is(kb_right, ev)) right = false;
    }

    private static boolean is(KeyBinding kb, KeyEvent ev) {
        KeyMatch k = kb.key();
        if (k == null || k == KeyMatch.nil)
            return false;
        int code = ev.getKeyCode();
        if (k.code != KeyEvent.VK_UNDEFINED && k.code == code)
            return true;
        /* A letter or digit bound by its character: VK codes for those are the upper-case char. */
        return (k.chr != 0) && (Character.toUpperCase(k.chr) == code);
    }

    /** A left click on the map: the player has taken over, whatever is still held. */
    public static void cancel() {
        fwd = left = back = right = false;
        walking = false;
    }

    /** Called every frame from GameUI.tick. */
    public static void tick(GameUI gui) {
        tick(gui, false);
    }

    private static void tick(GameUI gui, boolean now) {
        if (!(fwd || left || back || right) && !walking)
            return;
        if (gui.ui != null && gui.ui.wnd != null && !gui.ui.wnd.focused())
            fwd = left = back = right = false;
        double dx = (right ? 1 : 0) - (left ? 1 : 0);
        double dy = (back ? 1 : 0) - (fwd ? 1 : 0);
        MapView map = gui.map;
        Gob pl = (map == null) ? null : map.player();
        if (pl == null)
            return;
        if (dx == 0 && dy == 0) {
            if (walking) {
                /* Let go (or opposite keys cancelling): stand where we are. */
                map.wdgmsg("click", Coord.z, pl.rc.floor(OCache.posres), 1, 0);
                walking = false;
            }
            return;
        }
        double t = Utils.rtime();
        boolean turned = (dx != sx) || (dy != sy);
        if (!now && !turned && walking && (t - last) < RESEND)
            return;
        Coord2d dir = unproject(map, pl.rc, dx, dy);
        if (dir == null)
            return;
        double len = Math.hypot(dir.x, dir.y);
        if (len < 1e-9)
            return;
        Coord2d to = pl.rc.add(dir.x * LEAD / len, dir.y * LEAD / len);
        map.wdgmsg("click", Coord.z, to.floor(OCache.posres), 1, 0);
        walking = true;
        sx = dx;
        sy = dy;
        last = t;
    }

    /* The world direction that moves a point at `at` by (dx, dy) on screen, through the view's own
     * projection: three screenxf probes and a 2x2 inverse, so it is right for any camera. */
    private static Coord2d unproject(MapView map, Coord2d at, double dx, double dy) {
        Coord3f p0 = map.screenxf(at), px = map.screenxf(at.add(1, 0)), py = map.screenxf(at.add(0, 1));
        if (p0 == null || px == null || py == null)
            return null;
        double a = px.x - p0.x, b = py.x - p0.x, c = px.y - p0.y, d = py.y - p0.y;
        double det = (a * d) - (b * c);
        if (Math.abs(det) < 1e-9)
            return null;
        return Coord2d.of(((d * dx) - (b * dy)) / det, ((a * dy) - (c * dx)) / det);
    }
}
