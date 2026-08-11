package haven.automated;

import haven.GameUI;

/**
 * Feeds the nearest smelter {@code count} coal from the inventory.
 *
 * A thin wrapper over {@link AddItemToDevice}, which is the shared body this one, AddBranchesToFurnace
 * and AddWoodBlocksToSmokeShed used to each carry a copy of.
 */
public class AddCoalToSmelter implements Runnable {
    private final AddItemToDevice inner;

    public AddCoalToSmelter(GameUI gui, int count) {
        this.inner = new AddItemToDevice(gui, count,
            res -> res.name.contains("smelter"), "No smelters found",
            n -> n.contains("Coal") && !n.contains("stack"), "No coal found in the inventory",
            "Not enough coal.");
    }

    @Override
    public void run() {
        inner.run();
    }
}
