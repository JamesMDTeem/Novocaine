package haven.automated.nbots.world;

import haven.Coord2d;

/**
 * The outcome of a {@link BotNav} journey. Replaces boolean returns with rich
 * failure context so callers can distinguish "arrived", "blocked by a gate
 * that needs opening", "no route exists", "aborted", etc.
 *
 * This is a sealed-interface pattern compatible with Java 15. The four
 * implementations are package-private to enforce that callers use the static
 * factory methods and switch on the concrete types.
 */
public interface TravelResult {

    /** True if the destination was reached within tolerance. */
    boolean isArrived();

    /** True if the journey was blocked by something that may change (gate, beast, etc.). */
    boolean isBlocked();

    /** True if the journey failed permanently (no route, different segment, etc.). */
    boolean isFailed();

    /** True if the bot was stopped before the journey could complete. */
    boolean isAborted();

    /** The position where the journey ended, or null if unknown. */
    Coord2d position();

    /** A human-readable reason, for logs and chat. Null for success. */
    String reason();

    /**
     * The destination was reached within tolerance.
     */
    static TravelResult arrived(Coord2d position) {
        return new Arrived(position);
    }

    /**
     * The journey was blocked by a gate, beast, or other transient obstacle.
     * The caller should retry after the world changes.
     */
    static TravelResult blocked(Coord2d position, String reason) {
        return new Blocked(position, reason);
    }

    /**
     * The journey failed permanently - no route exists, different segment, etc.
     * The caller should not retry the same destination.
     */
    static TravelResult failed(Coord2d position, String reason) {
        return new Failed(position, reason);
    }

    /**
     * The bot was stopped (abort signalled) before the journey could complete.
     */
    static TravelResult aborted(Coord2d position) {
        return new Aborted(position);
    }

    // -------------------------------------------------------------------------
    // Package-private implementations
    // -------------------------------------------------------------------------

    final class Arrived implements TravelResult {
        private final Coord2d position;

        Arrived(Coord2d position) {
            this.position = position;
        }

        @Override
        public boolean isArrived() { return true; }
        @Override
        public boolean isBlocked() { return false; }
        @Override
        public boolean isFailed() { return false; }
        @Override
        public boolean isAborted() { return false; }
        @Override
        public Coord2d position() { return position; }
        @Override
        public String reason() { return null; }

        @Override
        public String toString() {
            return "Arrived@" + (position != null ? position : "?");
        }
    }

    final class Blocked implements TravelResult {
        private final Coord2d position;
        private final String reason;

        Blocked(Coord2d position, String reason) {
            this.position = position;
            this.reason = reason;
        }

        @Override
        public boolean isArrived() { return false; }
        @Override
        public boolean isBlocked() { return true; }
        @Override
        public boolean isFailed() { return false; }
        @Override
        public boolean isAborted() { return false; }
        @Override
        public Coord2d position() { return position; }
        @Override
        public String reason() { return reason; }

        @Override
        public String toString() {
            return "Blocked@" + (position != null ? position : "?") + ": " + reason;
        }
    }

    final class Failed implements TravelResult {
        private final Coord2d position;
        private final String reason;

        Failed(Coord2d position, String reason) {
            this.position = position;
            this.reason = reason;
        }

        @Override
        public boolean isArrived() { return false; }
        @Override
        public boolean isBlocked() { return false; }
        @Override
        public boolean isFailed() { return true; }
        @Override
        public boolean isAborted() { return false; }
        @Override
        public Coord2d position() { return position; }
        @Override
        public String reason() { return reason; }

        @Override
        public String toString() {
            return "Failed@" + (position != null ? position : "?") + ": " + reason;
        }
    }

    final class Aborted implements TravelResult {
        private final Coord2d position;

        Aborted(Coord2d position) {
            this.position = position;
        }

        @Override
        public boolean isArrived() { return false; }
        @Override
        public boolean isBlocked() { return false; }
        @Override
        public boolean isFailed() { return false; }
        @Override
        public boolean isAborted() { return true; }
        @Override
        public Coord2d position() { return position; }
        @Override
        public String reason() { return "bot stopped"; }

        @Override
        public String toString() {
            return "Aborted@" + (position != null ? position : "?");
        }
    }
}