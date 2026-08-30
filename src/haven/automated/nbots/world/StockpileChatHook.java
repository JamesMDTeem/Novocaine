package haven.automated.nbots.world;

import haven.Gob;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Listens for the server's "that stockpile is full" refusal and retires the pile the bot is
 * currently trying to fill, without opening its window.
 *
 * <h2>The exact wording is not known, so the match is by shape</h2>
 *
 * The refusal was originally matched against two literal strings taken from a plan rather than
 * from a live server, and neither has ever been confirmed to arrive. A listener that silently
 * matches nothing is indistinguishable from one that is never called, which is the worst state for
 * this to be in - so the test is now "a line that mentions a stockpile and calls it full", which
 * covers every phrasing the server could reasonably use and still cannot fire on ordinary chat,
 * and every match is LOGGED. If the log line never appears in a session where piles were filling
 * up, the wording is wrong and the log is what says so.
 *
 * <h2>It only ever speaks about the pile we are at</h2>
 *
 * The bot supplies {@code lastAttempted} and clears it the moment a transfer ends. That is
 * load-bearing rather than tidy: this hook sees EVERY server notice the client receives, including
 * ones caused by the player working a pile by hand in another corner of the base, and a stale
 * reference would let one of those retire a pile the bot merely touched last. A null supplier
 * reading is a no-op, which is the state the field is in for most of a shift.
 *
 * Registered per-bot in {@link haven.automated.nbots.NStockpileBot}'s constructor and removed in
 * its {@code reqdestroy}, never as a global static initializer.
 */
public class StockpileChatHook implements Consumer<String> {

    /**
     * A line that names a stockpile and says it is full, in either order.
     *
     * Both halves are required, so "your inventory is full" and any line merely mentioning a
     * stockpile are both refused. Case-insensitive because server text is not ours to predict.
     */
    private static final Pattern P_FULL = Pattern.compile(
        "(stockpile.*\\bfull\\b)|(\\bfull\\b.*stockpile)", Pattern.CASE_INSENSITIVE);

    private final Supplier<Gob> lastAttempted;
    private final Consumer<String> log;

    public StockpileChatHook(Supplier<Gob> lastAttempted) {
        this(lastAttempted, null);
    }

    public StockpileChatHook(Supplier<Gob> lastAttempted, Consumer<String> log) {
        this.lastAttempted = lastAttempted;
        this.log = log;
    }

    /** Whether the given text is a stockpile-full refusal. */
    public static boolean isFullMessage(String text) {
        return (text != null) && P_FULL.matcher(text).find();
    }

    /**
     * Retires the pile currently being filled, if this text is a full refusal.
     *
     * No-op when the text does not match or no transfer is in progress.
     */
    public void onMessage(String text) {
        if (!isFullMessage(text))
            return;
        Gob g = (lastAttempted == null) ? null : lastAttempted.get();
        if (g == null)
            return;
        Stockpile.retire(g);
        if (log != null)
            log.accept("server says pile #" + g.id + " is full (\"" + text.trim() + "\") - retiring it");
    }

    @Override
    public void accept(String text) {
        onMessage(text);
    }
}
