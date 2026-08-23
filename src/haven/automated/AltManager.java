package haven.automated;

import haven.AccountList;
import haven.Button;
import haven.Charlist;
import haven.Config;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.LoginScreen;
import haven.RemoteUI;
import haven.RichText;
import haven.Session;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;

import java.util.Map;
import java.util.Objects;

/**
 * Alt account switcher.
 *
 * Lists every account this client has saved in {@link AccountList} and switches between them with
 * one click. Nurgling's equivalent is login-screen only: its account list fills the credential box
 * on the login screen, so a switch is a manual logout, a click to fill the box, and a manual
 * character pick. This window runs in-game and closes that loop: a click remembers the character
 * currently being played, drops the session back to the login screen, auto-logs-in the target
 * account, and auto-plays that account's last-used character.
 *
 * The whole feature is gated by the "Alt Manager" checkbox under Advanced Settings -&gt; Gameplay
 * Automation; with it off this window shows a notice instead of the account list.
 *
 * The pending-switch state lives here as statics rather than on {@link GameUI} so that LoginScreen
 * and Charlist - two vendored upstream files this fork edits as little as possible - can each pull
 * from it with a single one-line hook.
 */
public class AltManager extends Window {

    /** Account to log in as once the login screen appears. Cleared when it is consumed. */
    private static volatile String pendingAccount;

    /** Character to auto-play once the charlist loads, or null to stop at the charlist. */
    private static volatile String pendingChar;

    /**
     * Deadline for the whole pending switch, measured from when the switch was requested. Guards
     * against a stale request (e.g. the target account has no matching character) leaking into a
     * later, unrelated login.
     */
    private static volatile long pendingDeadline;
    private static final long PENDING_TIMEOUT_MS = 20000;

    private final GameUI gui;

    public AltManager(GameUI gui) {
        super(UI.scale(new Coord(240, 120)), "Alt Manager", true);
        this.gui = gui;

        if (!Utils.getprefb("altManager", false)) {
            Label why = add(new Label("Alt Manager is disabled."), UI.scale(new Coord(12, 8)));
            add(new Label("Turn it on under Advanced Settings"), why.pos("bl").adds(0, 6).x(0));
            add(new Label("-> Gameplay Automation."), why.pos("bl").adds(0, 24).x(0));
            pack();
            return;
        }

        Widget prev = add(new Label("Switch to:"), UI.scale(new Coord(12, 8)));
        prev.tooltip = RichText.render("Picks an account, and the character that account played last.", UI.scale(260));

        String current = currentAccount();

        synchronized (AccountList.accountmap) {
            for (Map.Entry<String, String> e : AccountList.accountmap.entrySet()) {
                final String name = e.getKey();
                String charName = Utils.getpref("nova.alt-lastchar/" + name, null);
                String label = name + ((charName != null) ? ("  (as " + charName + ")") : "");
                if (name.equals(current))
                    label = name + "  (current)";
                prev = add(new Button(UI.scale(220), label, false) {
                    @Override
                    public void click() {
                        switchTo(name);
                    }
                }, prev.pos("bl").adds(0, 2));
            }
        }

        if (AccountList.accountmap.isEmpty())
            prev = add(new Label("No saved accounts."), prev.pos("bl").adds(0, 6));
        pack();
    }

    private String currentAccount() {
        if (gui.ui != null && gui.ui.sess != null && gui.ui.sess.user != null)
            return gui.ui.sess.user.name;
        return null;
    }

    /**
     * Records the current account -&gt; current character, then drops the session back to the login
     * screen with a pending auto-login + auto-play for the requested account.
     */
    private void switchTo(String targetAccount) {
        String curAccount = currentAccount();
        String curChar = Config.playername;
        if (curAccount != null && !curAccount.isEmpty() && curChar != null && !curChar.isEmpty())
            Utils.setpref("nova.alt-lastchar/" + curAccount, curChar);

        if (Utils.getprefb("altKeepLoggedIn", false))
            gui.error("Keep-logged-in switching is not supported yet; logging out instead.");

        pendingAccount = targetAccount;
        pendingChar = Utils.getpref("nova.alt-lastchar/" + targetAccount, null);
        pendingDeadline = System.currentTimeMillis() + PENDING_TIMEOUT_MS;

        gui.nbots.close("AltManager");

        // Same path as the charlist "Log out" button: close the session so Main builds a fresh
        // Bootstrap and the login screen appears.
        if (gui.ui != null && gui.ui.rcvr instanceof RemoteUI) {
            Session sess = ((RemoteUI) gui.ui.rcvr).sess;
            synchronized (sess) {
                sess.close();
            }
        }
    }

    private static void clearPending() {
        pendingAccount = null;
        pendingChar = null;
        pendingDeadline = 0;
    }

    /**
     * Login-screen hook (one line in {@code LoginScreen.uimsg}): if a switch is pending, fill and
     * submit the credential box for the target account.
     */
    public static void maybeAutoLogin(LoginScreen ls) {
        String account = pendingAccount;
        if (account == null)
            return;
        if (System.currentTimeMillis() > pendingDeadline) {
            clearPending();
            return;
        }
        String pass = AccountList.accountmap.get(account);
        if (pass == null) {
            clearPending();
            return;
        }
        pendingAccount = null;
        pendingDeadline = System.currentTimeMillis() + PENDING_TIMEOUT_MS;
        ls.loginAsAccount(account, pass);
    }

    /**
     * Charlist hook (one line in {@code Charlist.uimsg}): play the pending character as soon as the
     * charlist has added it.
     */
    public static void maybeAutoPlay(Charlist cl, Charlist.Char c) {
        String target = pendingChar;
        if (target == null)
            return;
        if (System.currentTimeMillis() > pendingDeadline) {
            clearPending();
            return;
        }
        if (target.equals(c.name)) {
            clearPending();
            cl.wdgmsg("play", c.name);
            Config.setPlayerName(c.name);
            // c.disc may still be null here: auto-play fires from the "add" message, and the
            // per-character world arrives in its own later "srv" message. WorldTag treats that
            // as unknown and the upload falls back to the server's configured world, rather
            // than tagging this character with whichever world we happened to log in from last.
            haven.automated.cookbook.WorldTag.set(c.disc);
            Config.initAutomapper(cl.ui);
        }
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && (Objects.equals(msg, "close"))) {
            if (gui.nbots != null)
                gui.nbots.forget(this);
            reqdestroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    @Override
    public void reqdestroy() {
        Utils.setprefc("wndc-nbot-AltManager", this.c);
        super.reqdestroy();
    }
}
