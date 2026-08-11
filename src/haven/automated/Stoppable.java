package haven.automated;

/**
 * A windowed bot that can be asked to stop its work thread.
 *
 * Every windowed bot in the fork implements this: the stock Bots-tab bots below
 * haven.automated and the crew bots in haven.automated.nbots. The registry that opens them
 * only knows this interface, so it can stop any of them without caring which generation it is.
 */
public interface Stoppable {
    /** Stop the bot's work thread and release its map/input. Safe to call twice. */
    void stop();
}
