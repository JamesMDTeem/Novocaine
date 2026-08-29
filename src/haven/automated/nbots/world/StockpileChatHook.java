package haven.automated.nbots.world;

import haven.Gob;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Listens for the server's "stockpile full" refusal in the System log and retires
 * the pile the bot just tried to fill, without opening its window.
 *
 * Two phrasings are observed, both matched case-insensitively:
 * <ul>
 *   <li>"That stockpile is already full" - the bulk-fill refusal.</li>
 *   <li>"gstockpile.*?full" - the window-path variant, matched loosely.</li>
 * </ul>
 *
 * Bot-local: holds a supplier for {@code lastAttempted}. A null return is a no-op,
 * so a hook with no current target does nothing. Registered per-bot in
 * {@link haven.automated.nbots.NStockpileBot}'s constructor, never as a global
 * static initializer.
 */
public class StockpileChatHook implements java.util.function.Consumer<String> {

    private final Supplier<Gob> lastAttempted;
    private final Runnable ttlSync;

    private static final Pattern P_FULL = Pattern.compile(
        "That stockpile is already full", Pattern.CASE_INSENSITIVE);

    private static final Pattern P_GSTOCKPILE = Pattern.compile(
        "gstockpile.*?full", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public StockpileChatHook(Supplier<Gob> lastAttempted) {
        this(lastAttempted, null);
    }

    public StockpileChatHook(Supplier<Gob> lastAttempted, Runnable ttlSync) {
        this.lastAttempted = lastAttempted;
        this.ttlSync = ttlSync;
    }

    /**
     * Whether the given text matches either full-stockpile pattern (case-insensitive).
     */
    public static boolean isFullMessage(String text) {
        if (text == null)
            return false;
        return P_FULL.matcher(text).find() || P_GSTOCKPILE.matcher(text).find();
    }

    /**
     * If the text is a stockpile-full refusal and a pile was last attempted, retires it.
     * No-op when text does not match or lastAttempted is null.
     */
    public void onMessage(String text) {
        if (!isFullMessage(text))
            return;
        Gob g = (lastAttempted != null) ? lastAttempted.get() : null;
        if (g != null) {
            if (ttlSync != null) ttlSync.run();
            Stockpile.retire(g, 0);
        }
    }

    @Override
    public void accept(String text) {
        onMessage(text);
    }
}
