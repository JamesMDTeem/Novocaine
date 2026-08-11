package haven.automated;

import haven.GameUI;

/**
 * Feeds the nearest smoke shed {@code count} wood blocks from the inventory.
 *
 * A thin wrapper over {@link AddItemToDevice}, which is the shared body this one, AddCoalToSmelter
 * and AddBranchesToFurnace used to each carry a copy of.
 */
public class AddWoodBlocksToSmokeShed implements Runnable {
    private final AddItemToDevice inner;

    public AddWoodBlocksToSmokeShed(GameUI gui, int count) {
        this.inner = new AddItemToDevice(gui, count,
            res -> res.name.contains("smokeshed"), "No smoke shed found",
            n -> n.contains("Block of") && !n.contains("stack"), "No wood block found in the inventory",
            "Not enough wood blocks.");
    }

    @Override
    public void run() {
        inner.run();
    }
}
