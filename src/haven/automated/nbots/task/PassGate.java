package haven.automated.nbots.task;

import haven.Coord2d;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.Gates;
import haven.automated.nbots.world.Place;

/**
 * Opens the gateway between here and somewhere, walks through it, and shuts it again.
 *
 * Travel already does this for itself when a leg dies at a wall, which is where it matters most and
 * where no bot should have to ask for it. This exists for the other case: a bot that knows perfectly
 * well it is about to leave the base and would rather deal with the gate deliberately than discover
 * it by failing at it. Same code either way - the whole of it lives in {@link Gates}, and this is
 * the {@link Task} face of it so a shift can compose it with everything else.
 *
 * Not finding a gate is BLOCKED, not failed. There being no gateway between here and there is a
 * perfectly ordinary state of affairs - most journeys have no wall in them at all - and reporting it
 * as a failure would have callers retire destinations that are simply out in the open.
 */
public class PassGate implements Task {
    private final Coord2d point;
    private final Place place;

    public PassGate(Coord2d towards) {
        this.point = towards;
        this.place = null;
    }

    public PassGate(Place towards) {
        this.point = null;
        this.place = towards;
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        Coord2d dest = (place != null) ? place.centre(ctx.gui) : point;
        if (dest == null)
            return Outcome.failed("nowhere to head for");
        return Gates.pass(ctx.nav, ctx.gui, dest, ctx.log)
            ? Outcome.ok()
            : Outcome.blocked("no gateway worth using between here and there");
    }

    @Override
    public String label() {
        return "pass a gate";
    }
}
