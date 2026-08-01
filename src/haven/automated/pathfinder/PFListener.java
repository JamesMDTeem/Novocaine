package haven.automated.pathfinder;

/**
 * Callback notified when a {@link Pathfinder} thread completes or refuses a journey.
 *
 * Registered via {@link Pathfinder#addListener}. {@link #pfDone} is called from the pathfinder's
 * background thread after the route finishes, a {@link Pathfinder.Refusal} occurs, or
 * {@code terminate} is set. Callers check {@link Pathfinder#refusal} to distinguish success
 * from the four refusal cases.
 */
public interface PFListener {
    void pfDone(final Pathfinder thread);
}
