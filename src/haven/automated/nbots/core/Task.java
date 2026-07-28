package haven.automated.nbots.core;

/**
 * One unit of bot work, from "swing at this rock until it's gone" up to "run the whole cleanup
 * shift". Bots are compositions of these rather than monolithic loops with private helpers.
 *
 * The single-method shape is what makes composition free: a task that needs to walk somewhere,
 * equip something and then work an object simply runs three tasks, and every one of those is
 * independently usable by the next bot. Before this existed, the cellar digger's chip loop and the
 * cleanup bot's work loop were the same eighty lines written twice, and a third bot would have
 * written them a third time.
 *
 * Everything a task needs arrives in {@link BotCtx} - deliberately, rather than through statics.
 * (nurgling2's actions reach for NUtils.getGameUI() and friends from anywhere, which reads fine
 * until you want to know what a given action can actually touch.) A task's signature is therefore
 * an honest statement of its dependencies.
 *
 * Implementations must honour {@link BotCtx#running()}: every wait in BotNav already throws
 * InterruptedException the moment the bot is stopped, so a task that only ever waits through those
 * helpers gets Stop-responsiveness for free.
 */
public interface Task {
    Outcome run(BotCtx ctx) throws InterruptedException;

    /** Short label for logs and the bot window's status line. */
    default String label() {
        return getClass().getSimpleName();
    }
}
