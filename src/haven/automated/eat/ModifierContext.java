package haven.automated.eat;

import haven.Button;
import haven.GameUI;
import haven.Label;
import haven.UI;
import haven.Widget;
import haven.Window;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The account/table multipliers that scale FEP and hunger gain, read the same way
 * {@code FoodInfo.tipimg} already reads them for the hover tooltip - so the Eating Helper's
 * planner sees the same live numbers a player already trusts, instead of a second
 * hand-maintained copy of the same formula drifting out of sync with it over time.
 *
 * {@code FoodInfo.tipimg} itself is left untouched rather than refactored to call this: it is
 * a working, high-traffic tooltip rendered on every food hover, and the risk of a subtle
 * regression there outweighs removing one small duplicate. This class exists so *new* code
 * (the planner window) has one correct place to read these values from, not to migrate old
 * code that already works.
 *
 * <h2>Reading a real table</h2>
 *
 * There is no clean live field anywhere for "am I at a feasting table right now", so the table
 * is found by looking for an open {@link Window} captioned "Table" that has a {@link Button}
 * child (the Feast button), then parsing the numbers off its {@link Label} children. Both of
 * those numbers are read straight from the open window, so a table's actual bonus is used -
 * they depend on the table's quality and on how many people are feasting at it, and no default
 * could stand in for that.
 *
 * The parse is deliberately tolerant of how the server words those labels. It accepts a decimal
 * ("Food event bonus: 12.5%"), a signed value ("+10%"), and any leading text, because guessing
 * wrong here silently mis-scales every number the planner produces. A label that yields nothing
 * parseable leaves its multiplier at 1.0 rather than at a made-up value.
 *
 * <h2>Remembering the last real table</h2>
 *
 * Those values only exist while the window is actually open, which is a problem for planning:
 * you want to know what a meal is worth at your table *before* you walk over and open it. So the
 * last set of values genuinely observed at a table is kept in {@link #lastSeenTable()}, and
 * {@code EatHelperWindow}'s "Assume at a table" checkbox plans against those. That checkbox used
 * to do nothing at all - it OR'd itself into a flag that only gated values which were still 1.0
 * whenever no table was open, so ticking it changed no number in the plan. It is now inert only
 * when nothing has ever been observed, and the window says so instead of silently planning flat.
 */
public class ModifierContext {
    /** FEP multiplier from current hunger level (BAttrWnd.GlutMeter.gmod). */
    public final double gmod;
    /** Verified/subscribed account multiplier: 1.5 both, 1.3 subscribed only, 1.2 verified only, else 1.0. */
    public final double accountMult;
    /** Table food-event bonus, e.g. 1.1 for a +10% bonus. 1.0 when not at a table. */
    public final double tableFoodEventBonus;
    /** Table hunger modifier as a fraction, e.g. 0.75 for a 75% hunger cost. 1.0 when not at a table. */
    public final double tableHungerMod;
    /** Whether an open Table window with a Feast button was actually found. */
    public final boolean atTable;

    private ModifierContext(double gmod, double accountMult, double tableFoodEventBonus,
                             double tableHungerMod, boolean atTable) {
        this.gmod = gmod;
        this.accountMult = accountMult;
        this.tableFoodEventBonus = tableFoodEventBonus;
        this.tableHungerMod = tableHungerMod;
        this.atTable = atTable;
    }

    /** The FEP bonus and hunger modifier last read off a real, open Table window. */
    public static final class TableValues {
        public final double foodEventBonus;
        public final double hungerMod;
        /** When these were read, for the window's "last seen" wording. */
        public final long observedAt;

        TableValues(double foodEventBonus, double hungerMod, long observedAt) {
            this.foodEventBonus = foodEventBonus;
            this.hungerMod = hungerMod;
            this.observedAt = observedAt;
        }
    }

    private static volatile TableValues lastSeenTable = null;

    /**
     * The last table values actually observed this session, or null if no table has been opened.
     * Null is the honest answer for "we have never seen your table" - callers must say so rather
     * than substituting a plausible-looking constant, since the real figure depends on the table's
     * quality and the number of feasters and is not guessable.
     */
    public static TableValues lastSeenTable() {
        return lastSeenTable;
    }

    /** Forgets the remembered table, e.g. on character switch. */
    public static void reset() {
        lastSeenTable = null;
    }

    /** Live snapshot from the given UI, or null if the character sheet isn't available yet. */
    public static ModifierContext resolve(UI ui) {
        if (ui == null || ui.gui == null || ui.gui.chrwdg == null
                || ui.gui.chrwdg.battr == null || ui.gui.chrwdg.battr.glut == null)
            return null;

        double gmod = ui.gui.chrwdg.battr.glut.gmod;

        double accountMult = 1.0;
        if (GameUI.subscribedAccount && GameUI.verifiedAccount)
            accountMult = 1.5;
        else if (GameUI.subscribedAccount)
            accountMult = 1.3;
        else if (GameUI.verifiedAccount)
            accountMult = 1.2;

        double tableFoodEventBonus = 1.0;
        double tableHungerMod = 1.0;
        boolean atTable = false;

        Window feastingWindow = findTableWindow(ui);
        if (feastingWindow != null) {
            atTable = true;
            for (Widget wdg : feastingWindow.children()) {
                if (!(wdg instanceof Label))
                    continue;
                String labelString = ((Label) wdg).texts;
                if (labelString == null)
                    continue;
                if (labelString.startsWith("Food event bonus")) {
                    double n = extractNumber(labelString);
                    // A bonus reads as a percentage added on top: "+10%" is a 1.1x multiplier.
                    tableFoodEventBonus = (n > 0.0) ? 1.0 + (n / 100.0) : 1.0;
                } else if (labelString.startsWith("Hunger modifier")) {
                    double n = extractNumber(labelString);
                    // A modifier reads as a percentage *of* the normal cost: "75%" is 0.75x. A
                    // value at or above 100 is not a discount, so it changes nothing.
                    tableHungerMod = (n > 0.0 && n < 100.0) ? (n / 100.0) : 1.0;
                }
            }
            // Only remember a table that gave up at least one real modifier - a window whose
            // labels parsed to nothing is not evidence about what feasting there is worth.
            if (tableFoodEventBonus != 1.0 || tableHungerMod != 1.0)
                lastSeenTable = new TableValues(tableFoodEventBonus, tableHungerMod,
                        System.currentTimeMillis());
        }

        return new ModifierContext(gmod, accountMult, tableFoodEventBonus, tableHungerMod, atTable);
    }

    /**
     * The open feasting-table window, or null. Caption comparison is the right way round on
     * purpose: {@code Window.cap} is nullable and plenty of windows are built without one, and
     * {@code wnd.cap.equals("Table")} throws on the first of those it meets - which the planner
     * would then report as "character sheet not loaded yet", hiding the real fault entirely.
     */
    private static Window findTableWindow(UI ui) {
        for (Window wnd : ui.gui.getAllWindows()) {
            if (!"Table".equals(wnd.cap))
                continue;
            for (Widget wdg : wnd.children()) {
                if (wdg instanceof Button)
                    return wnd;
            }
        }
        return null;
    }

    /**
     * First number in the string, decimals and sign included. The old {@code \d+} form truncated
     * "12.5%" to 12 and would have read the "2" out of a "+2" before any real figure.
     */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static double extractNumber(String str) {
        Matcher matcher = NUMBER.matcher(str);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
