package haven.automated.nbots.core;

/**
 * What a {@link Task} did. Three states, not two, and no side effects.
 *
 * The three states exist because "it didn't work" is two genuinely different facts and the
 * caller has to act on them differently:
 *
 * - {@link #blocked} - not now. A beast is standing on the target, every work slot is taken, the
 *   container is full, the flower menu didn't open in time. The right response is to set this
 *   target aside and come back to it, because the world will change.
 * - {@link #failed} - not going to work. There is no shovel anywhere on the character, no place is
 *   tagged for water, the gob offers no option we recognise. Retrying costs a walk and changes
 *   nothing; the target (or the whole job) should be retired.
 *
 * The bots already needed exactly this distinction before it had a name - it is what
 * BotNav.hazardBlocked encodes, and what decides defer-versus-retire in the run loops. Collapsing
 * it into a single failure state is what made the LP bot upstream retire every target near one
 * wandering bear and then report that it had finished.
 *
 * Deliberately a pure value. nurgling2's equivalent (Results.ERROR) writes to the chat log from
 * its own constructor, so merely RETURNING a failure is a user-visible event - which means a task
 * that is retried three times spams three times, and a caller that handles a failure quietly
 * cannot. Here the reason travels with the value and the runner decides whether anyone hears it.
 */
public final class Outcome {
    public enum Kind {
        OK, BLOCKED, FAILED
    }

    private static final Outcome OK = new Outcome(Kind.OK, null);

    public final Kind kind;
    /** Human-readable reason, for logs and chat. Null when {@link #ok()}. */
    public final String reason;

    private Outcome(Kind kind, String reason) {
        this.kind = kind;
        this.reason = reason;
    }

    public static Outcome ok() {
        return OK;
    }

    /** Not now - the world should be given a chance to change, then the caller may retry. */
    public static Outcome blocked(String reason) {
        return new Outcome(Kind.BLOCKED, reason);
    }

    /** Not going to work - retrying this exact thing is wasted effort. */
    public static Outcome failed(String reason) {
        return new Outcome(Kind.FAILED, reason);
    }

    public boolean isOk() {
        return kind == Kind.OK;
    }

    public boolean isBlocked() {
        return kind == Kind.BLOCKED;
    }

    public boolean isFailed() {
        return kind == Kind.FAILED;
    }

    /**
     * Convenience for the very common "if this step didn't succeed, pass its outcome straight up".
     * Keeps composition readable without every task growing an if-else ladder.
     */
    public static boolean bad(Outcome o) {
        return o != null && !o.isOk();
    }

    public String toString() {
        return (reason == null) ? kind.toString() : (kind + ": " + reason);
    }
}
