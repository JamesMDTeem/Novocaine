package haven.automated;

import haven.GameUI;

/**
 * Feeds the nearest kiln or oven {@code count} branches from the inventory.
 *
 * A thin wrapper over {@link AddItemToDevice}, which is the shared body this one, AddCoalToSmelter
 * and AddWoodBlocksToSmokeShed used to each carry a copy of.
 */
public class AddBranchesToFurnace implements Runnable {
    private final AddItemToDevice inner;

    public AddBranchesToFurnace(GameUI gui, int count) {
        this.inner = new AddItemToDevice(gui, count,
            res -> res.basename().equals("kiln") || res.basename().equals("oven"), "No Kiln or Oven found",
            n -> n.equals("Branch"), "No branch found in the inventory",
            "Not enough branches.");
    }

    @Override
    public void run() {
        inner.run();
    }
}
