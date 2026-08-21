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
 * The table piece is the fragile part: there is no clean live field anywhere for "am I at a
 * feasting table right now". It is scraped by finding an open {@link Window} captioned
 * "Table" with a {@link Button} child (the Feast button), then regex-parsing the leading
 * number off {@link Label} children whose text starts with "Food event bonus" or "Hunger
 * modifier". That only returns non-default values while the window is actually open, which is
 * why {@code EatHelperWindow} offers an explicit "assume a table" override rather than relying
 * on this alone.
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

        Window feastingWindow = null;
        outerLoop:
        for (Window wnd : ui.gui.getAllWindows()) {
            if (wnd.cap.equals("Table")) {
                for (Widget wdg : wnd.children()) {
                    if (wdg instanceof Button) {
                        feastingWindow = wnd;
                        break outerLoop;
                    }
                }
            }
        }
        if (feastingWindow != null) {
            atTable = true;
            for (Widget wdg : feastingWindow.children()) {
                if (wdg instanceof Label) {
                    String labelString = ((Label) wdg).texts;
                    if (labelString.startsWith("Food event bonus")) {
                        double n = extractNumber(labelString);
                        tableFoodEventBonus = (n > 0.0) ? 1.0 + (n / 100) : 1.0;
                    } else if (labelString.startsWith("Hunger modifier")) {
                        double n = extractNumber(labelString);
                        tableHungerMod = (n < 100 && n > 0.0) ? (n / 100) : 1.0;
                    }
                }
            }
        }

        return new ModifierContext(gmod, accountMult, tableFoodEventBonus, tableHungerMod, atTable);
    }

    private static double extractNumber(String str) {
        Matcher matcher = Pattern.compile("\\d+").matcher(str);
        if (matcher.find())
            return Double.parseDouble(matcher.group());
        return 0;
    }
}
