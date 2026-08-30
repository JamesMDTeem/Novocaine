package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.ISBox;
import haven.Indir;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.Window;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static haven.OCache.posres;

/**
 * Everything the bots know about stockpiles: recognising one, reading how full it is, moving items
 * in and out of it, and finding somewhere to stand a new one.
 *
 * <h2>The pile is never trusted to stay still</h2>
 *
 * A stockpile is the one kind of container in this game that can cease to exist while you are
 * looking at it: taking the last item out DELETES the gob, and the window with it. With several
 * characters working one yard that is not an edge case, it is the normal way a shift goes - one bot
 * empties a pile a second bot was walking towards, or fills the pile a third was about to add to.
 *
 * nurgling2's equivalent treats both as impossible. {@code TransferToPiles} loops
 * {@code while(gob.ngob.getModelAttribute() != 31 && ...)} over a Gob reference captured before the
 * walk, re-reads nothing, and calls {@code gui.getStockpile()} - the open window - without checking
 * whether it is still there; {@code TakeItemsFromPile} loops {@code while (gui.getStockpile()!=null)}
 * with no bound at all, and {@code SoilStockpileDropper} spins {@code while(true) { if (count <= MIN)
 * continue; }}, which is a hot loop with no sleep in it. Those are the lock-ups and the "errors when
 * the pile disappeared" - not bad luck, but the absence of any re-read.
 *
 * So the rule here is that nothing is remembered across a message. {@link Open#alive} is asked
 * before every action and after every wait, the counts come off the live widget every time
 * ({@link ISBox#count}), and a pile that vanishes mid-transfer is an ordinary, successful end to
 * the transfer rather than a failure - the items that did move, moved.
 *
 * <h2>What a pile holds is read off the pile</h2>
 *
 * nurgling maps item name to pile resource through a thirteen-branch if-else
 * ({@code TransferToPiles.getStockpileName}), so a pile it has not been taught about cannot be
 * used. It does not need to: the open pile's own {@link ISBox} carries the resource of the item it
 * is made of, so "what does this pile take" and "which of the things I am carrying belong in it"
 * are both answered by {@link Open#item} with no table to keep up to date. A pile the fork has
 * never heard of works on the first try.
 */
public class Stockpile {
    /** The window caption the server gives an open pile. Sacks share the widget but not the name. */
    public static final String WINDOW = "Stockpile";

    /** Every stockpile resource starts with this; the kind follows as a suffix. */
    private static final String PREFIX = "gfx/terobjs/stockpile";

    /** For {@link Place#gobsWithin(GameUI, Alias)}, which matches on a resource-name fragment. */
    public static final Alias ANY = new Alias("stockpile", PREFIX);

    /**
     * Polls to wait for the pile window and its box to arrive. One poll is 25ms, so this is two
     * seconds - a server round trip on a bad day, not a guess at a good one.
     */
    private static final int OPEN_TICKS = 80;
    /** Polls to wait for one batch of transfers to show up in the counts. Two dozen acks, so: longer. */
    private static final int SETTLE_TICKS = 120;

    private Stockpile() {}

    // ------------------------------------------------------------------ recognising one

    public static String resname(Gob g) {
        if (g == null)
            return null;
        try {
            Resource res = g.getres();
            return (res == null) ? null : res.name;
        } catch (Loading | NullPointerException e) {
            return null;
        }
    }

    public static boolean is(Gob g) {
        String n = resname(g);
        return (n != null) && n.startsWith(PREFIX);
    }

    /**
     * The pile's kind as a player would say it - "soil", "board", "ore".
     *
     * Only for messages and for the player's own name filter. Deciding whether two piles hold the
     * same thing goes through the full resource name, since "stockpile-ore" is one kind and half a
     * dozen different metals.
     */
    public static String kind(Gob g) {
        String n = resname(g);
        if (n == null)
            return null;
        int cut = n.lastIndexOf('-');
        return (cut < 0 || cut + 1 >= n.length()) ? "stockpile" : n.substring(cut + 1);
    }

    /**
     * Every stockpile currently rendered inside a place.
     *
     * Deliberately NOT {@link Place#gobsWithin(GameUI, Alias)}, which is the obvious way to write
     * this and the wrong one here. That call also OBSERVES - it replaces the place's remembered
     * contents and, when they have changed, writes the shared places file under a cross-process
     * lock. Which is right for a bot that looks at a storage area once a trip, and ruinous for one
     * that re-scans the same yard several times a minute while its contents change with every
     * transfer: every scan would find a different count, so every scan would take the lock and
     * rewrite the file that every other client in the crew is also reading.
     *
     * A scan is a scan. The remembered contents are maintained by the callers that genuinely visit
     * a place, and nothing here needs them.
     */
    public static List<Gob> within(GameUI gui, Place place) {
        List<Gob> out = new ArrayList<>();
        if ((gui == null) || (gui.map == null) || (place == null))
            return out;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (is(g) && place.contains(gui, g.rc))
                    out.add(g);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the full-from-outside hint

    /**
     * The lowest sprite state at which a pile of a given resource has actually been SEEN to be
     * full, learned by opening piles rather than tabulated.
     *
     * A pile's model tells you roughly how much is in it - that is what the growing heap on the
     * ground is - so a bot that has already learned what "full" looks like for soil can skip the
     * walk-and-open for every other full soil pile in the yard. What it must not do is decide a
     * pile is full on a number nobody checked: nurgling hard-codes {@code getModelAttribute() != 31}
     * for EVERY pile type, which is one pile's capacity applied to all of them, and a wrong answer
     * there means either a pile that is never filled or one that is opened forever.
     *
     * So the number is only ever written from an open pile that reported no free space, it is only
     * ever used to ORDER candidates ({@link #fullHint}), and a pile that looks full is still opened
     * when nothing else is available. Being wrong therefore costs one wasted open, not a stall.
     *
     * Keyed on the pile's resource name; concurrent because bots read and write it from their own
     * threads.
     */
    private static final ConcurrentHashMap<String, Integer> FULL_AT = new ConcurrentHashMap<>();

    /** Records that a pile of this resource was full while showing this sprite state. */
    private static void learnFull(String res, int sdt) {
        if (res == null || sdt <= 0)
            return;
        FULL_AT.merge(res, sdt, Math::min);
    }

    /**
     * Whether this pile probably has no room left, from across the yard.
     *
     * A hint, never an answer - see {@link #FULL_AT}. False whenever nothing has been learned about
     * this kind of pile yet, which is the safe way to be wrong: an unopened pile gets opened.
     */
    public static boolean fullHint(Gob g) {
        String res = resname(g);
        if (res == null)
            return false;
        Integer at = FULL_AT.get(res);
        return (at != null) && (g.sdt() >= at.intValue());
    }

    // ------------------------------------------------------------------ retire: known-full until TTL

    /** How long a retired pile/spot stays retired without being re-proven full. Default 5 min, configurable 1-30 via NStockpileBot retire_ttl. */
    private static volatile long RETIRE_TTL_MS = 5 * 60 * 1000;

    /** Current retire TTL in ms (default 5m if never set). */
    public static long retireTtlMs() {
        return RETIRE_TTL_MS;
    }

    /** Set retire TTL from minutes, clamped 1-30. */
    public static void setRetireTtlMinutes(int minutes) {
        int clamped = Math.max(1, Math.min(30, minutes));
        RETIRE_TTL_MS = clamped * 60L * 1000L;
    }

    /** Set retire TTL directly in ms, clamped to 1-30 minutes. */
    public static void setRetireTtlMs(long ms) {
        long clamped = Math.max(1 * 60 * 1000L, Math.min(30 * 60 * 1000L, ms));
        RETIRE_TTL_MS = clamped;
    }

    /**
     * One retirement, which is a deadline and nothing else.
     *
     * It carried a {@code capacity} as well, and no reader ever looked at it - the one caller that
     * had to supply a number passed a literal zero under a comment saying a real one might be
     * available later. A field nobody reads is not a placeholder, it is a thing every call site has
     * to invent a value for.
     */
    private static final class RetireEntry {
        final long expireMs;
        RetireEntry(long expireMs) {
            this.expireMs = expireMs;
        }
        boolean expired(long now) {
            return now >= expireMs;
        }
    }

    private static final ConcurrentHashMap<Long, RetireEntry> RETIRED_BY_GOB = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RetireEntry> RETIRED_BY_SPOT = new ConcurrentHashMap<>();

    /**
     * Retire a pile gob so acceptors skip it until TTL expires.
     *
     * DELIBERATELY does not touch {@link #FULL_AT}. It used to, and that was the single most
     * damaging line in this class: {@code FULL_AT} is global, keyed on the pile RESOURCE, merged
     * with {@code min} and never reset, so one retire wrote a threshold that applied to every pile
     * of that kind for the rest of the session and could only ever get stricter. Retiring from a
     * transfer that merely moved nothing - which happens for a pile that is full, a pile that wants
     * something else, and a pack holding only a byproduct - then taught the client that a nearly
     * EMPTY pile's sprite state meant full, and {@link #fullHint} began refusing the whole yard.
     *
     * The threshold has exactly one honest source and it is still the only one: {@link Open#free}
     * reading zero off an open pile. See {@link #FULL_AT}.
     */
    public static void retire(Gob gob) {
        if (gob == null)
            return;
        RETIRED_BY_GOB.put(gob.id, new RetireEntry(System.currentTimeMillis() + RETIRE_TTL_MS));
    }

    /**
     * Retire a placement SQUARE (a {@link #spotKey}) until TTL expires.
     *
     * For ground the server refused to stand a pile on. Nothing the client can see says why - the
     * refusal is simply no pile appearing - so there is nothing to re-derive and nothing that would
     * make the next attempt go differently. {@link #spotsIn} is deterministic and sorted, so
     * without this the same refused square comes back first on every trip and is walked to again.
     */
    public static void retireSpot(String spotKey) {
        if (spotKey == null)
            return;
        RETIRED_BY_SPOT.put(spotKey, new RetireEntry(System.currentTimeMillis() + RETIRE_TTL_MS));
    }

    /** Whether this gob is currently retired (lazy-expires on check). */
    public static boolean isRetired(Gob gob) {
        if (gob == null)
            return false;
        RetireEntry e = RETIRED_BY_GOB.get(gob.id);
        if (e == null)
            return false;
        if (e.expired(System.currentTimeMillis())) {
            RETIRED_BY_GOB.remove(gob.id, e);
            return false;
        }
        return true;
    }

    /** Whether this spotKey is currently retired (lazy-expires on check). */
    public static boolean isRetiredSpot(String spotKey) {
        if (spotKey == null)
            return false;
        RetireEntry e = RETIRED_BY_SPOT.get(spotKey);
        if (e == null)
            return false;
        if (e.expired(System.currentTimeMillis())) {
            RETIRED_BY_SPOT.remove(spotKey, e);
            return false;
        }
        return true;
    }

    /** Removes expired entries from both retire maps. */
    public static void expireSweep() {
        long now = System.currentTimeMillis();
        RETIRED_BY_GOB.entrySet().removeIf(en -> en.getValue().expired(now));
        RETIRED_BY_SPOT.entrySet().removeIf(en -> en.getValue().expired(now));
    }

    // ------------------------------------------------------------------ opening one

    /**
     * Right-clicks a pile and waits for its window.
     *
     * Any pile window already up is closed first. Two of them open at once is not a state this
     * client distinguishes - {@link GameUI#getwnd} returns whichever comes first in the widget list
     * - so the only way to know which pile the box in front of us belongs to is to have opened
     * exactly one.
     *
     * @return the handle, or null if the pile went away or never opened. Never throws for either.
     */
    public static Open open(BotCtx ctx, Gob pile) throws InterruptedException {
        if ((pile == null) || (ctx.gob(pile.id) == null))
            return null;
        /* Shut the previous pile and WAIT for it to go, remembering which widget it was.
         *
         * Closing is a round trip to the server, so for a few frames after asking, the window is
         * still in the tree - and the loop below, which cannot tell one pile's window from
         * another's, would bind to it and then read the OLD pile's counts as if they were this
         * one's. That is a bot that empties a pile it is standing nowhere near, or decides a pile
         * with room in it is full. Holding on to the stale reference is what makes the two
         * distinguishable at all. */
        Window stale = ctx.gui.getwnd(WINDOW);
        if (stale != null) {
            stale.wdgmsg("close");
            ctx.nav.waitUntil(() -> ctx.gui.getwnd(WINDOW) != stale, OPEN_TICKS);
        }
        long id = pile.id;
        ctx.gui.map.wdgmsg("click", Coord.z, pile.rc.floor(posres), 3, 0, 0, (int) id,
            pile.rc.floor(posres), 0, -1);

        Window wnd = null;
        ISBox box = null;
        for (int i = 0; i < OPEN_TICKS; i++) {
            if (!ctx.running())
                throw new InterruptedException();
            /* Gone while we were clicking on it: somebody else took the last item out. Not an
             * error and not worth a message - it is what a shared yard looks like. */
            if (ctx.gob(id) == null)
                return null;
            wnd = ctx.gui.getwnd(WINDOW);
            if (wnd == stale)
                wnd = null;
            box = (wnd == null) ? null : Widgets.find(wnd, ISBox.class);
            if (box != null)
                break;
            ctx.nav.pause(1);
        }
        if (box == null) {
            closeAny(ctx);
            return null;
        }
        return new Open(ctx, id, wnd, box);
    }

    // ------------------------------------------------------------------ the bulk gestures

    /**
     * The modifier that turns each of the pile gestures from "one item" into "as many as fit".
     *
     * {@code UI.MOD_SHIFT}, which is {@code KeyMatch.S}, which is 1 - the same 1 {@link ISBox}
     * writes by hand with the comment "modflags set to 1 to emulate only SHIFT pressed". Named
     * here so the three senders below do not each carry a bare literal.
     */
    private static final int SHIFT = haven.UI.MOD_SHIFT;

    /**
     * Empties as much of a pile into the pack as will fit, in ONE message.
     *
     * Shift plus right-click on a stockpile with an EMPTY HAND is a bulk withdraw: the server
     * fills the inventory from the pile and stops when it runs out of either. That makes the whole
     * of {@code drawLoop} - open the window, read the count, work out a batch, fire two dozen
     * {@code xfer2}s, wait for the count to settle, go round again - one click and one wait.
     *
     * Whether the hand is empty is the entire difference between this and {@link #putAll}: the
     * same gesture holding a stockpilable item deposits instead of withdrawing. So the caller has
     * to be certain about the cursor before sending either, which is why both are private to a
     * task that clears it first.
     */
    public static void takeAll(BotCtx ctx, Gob pile) {
        if (pile == null)
            return;
        Coord at = pile.rc.floor(posres);
        ctx.gui.map.wdgmsg("click", Coord.z, at, 3, SHIFT, 0, (int) pile.id, at, 0, -1);
    }

    /**
     * Puts everything in the pack that belongs in a pile into it, in ONE message.
     *
     * Shift plus right-click on a stockpile while HOLDING one of its items. Note this is
     * {@code itemact} and not {@code click}: right-clicking with something on the cursor is using
     * that thing on what is under it, and {@code MapView.iteminteract} is where the client turns
     * that into a message. Sending {@code click} instead would be a bare right-click, which with a
     * full cursor toggles the held item into its stockpile form - the wrong gesture entirely, and
     * one that leaves the bot unable to move.
     *
     * The caller must have an item of the right kind on the cursor. That is the gesture's own
     * requirement, not this method's choice.
     */
    public static void putAll(BotCtx ctx, Gob pile) {
        if (pile == null)
            return;
        Coord at = pile.rc.floor(posres);
        ctx.gui.map.wdgmsg("itemact", Coord.z, at, SHIFT, 0, (int) pile.id, at, 0, -1);
    }

    /**
     * Commits a placement so the new pile is filled from the pack rather than holding one item.
     *
     * Shift plus left-click while in stockpile form. Without the shift the pile is created with
     * exactly the clod that made it and everything else has to be put in afterwards, which is a
     * second gesture and, before this existed, a whole second visit.
     *
     * The angle is zero because a stockpile has no meaningful facing; the button is 1 because that
     * is a left click, and {@code MapView.mousedown} passes both straight through.
     */
    public static void placeAll(BotCtx ctx, Coord2d spot) {
        ctx.gui.map.wdgmsg("place", spot.floor(posres), 0, 1, SHIFT);
    }

    /** Shuts whatever pile window is up, if any. Safe to call when there isn't one. */
    public static void closeAny(BotCtx ctx) {
        Window w = ctx.gui.getwnd(WINDOW);
        if (w != null)
            w.wdgmsg("close");
    }

    /**
     * A pile that is open in front of us, for as long as it stays that way.
     *
     * Every reader goes through {@link #alive}, and every count is re-read from the widget rather
     * than cached, because the two things this class exists to survive - the pile being emptied out
     * from under us, and being filled up from under us - both show up as a changed count or a
     * missing window and nothing else.
     */
    public static final class Open {
        private final BotCtx ctx;
        private final long gobid;
        private final Window wnd;
        private final ISBox box;

        private Open(BotCtx ctx, long gobid, Window wnd, ISBox box) {
            this.ctx = ctx;
            this.gobid = gobid;
            this.wnd = wnd;
            this.box = box;
        }

        public long id() {
            return gobid;
        }

        /** The pile gob, or null if it has been emptied away. */
        public Gob gob() {
            return ctx.gob(gobid);
        }

        /**
         * Whether this handle still refers to something.
         *
         * Both halves matter and neither implies the other. The gob going means the pile was
         * emptied; the window going means it was closed - by walking out of range, by the player,
         * or by the server replacing it - and acting on a stale {@link ISBox} after that sends
         * transfers to a widget id the server has already forgotten.
         */
        public boolean alive() {
            return (ctx.gob(gobid) != null) && (ctx.gui.getwnd(WINDOW) == wnd);
        }

        public int count() {
            return box.count();
        }

        public int capacity() {
            return box.capacity();
        }

        /**
         * How much more this pile will take, and the place the full-from-outside hint is learned.
         *
         * Learning here rather than at a call site because this is the only moment the two facts
         * needed are both to hand: an authoritative free-space reading, and the sprite state the
         * pile is showing while it reads that way.
         */
        public int free() {
            int f = box.free();
            if (f == 0) {
                Gob g = gob();
                if (g != null)
                    learnFull(resname(g), g.sdt());
            }
            return f;
        }

        /**
         * The resource name of the item this pile is made of, or null while it is still loading.
         *
         * Null is "not yet", not "nothing" - the server sends the resource by id and the client
         * fetches it - so a caller that needs it waits ({@link #awaitItem}) rather than concluding
         * the pile is empty of anything it recognises.
         */
        public String item() {
            try {
                Indir<Resource> ir = box.contents();
                if (ir == null)
                    return null;
                Resource res = ir.get();
                return (res == null) ? null : res.name;
            } catch (RuntimeException e) {
                // Includes Loading: the resource has not arrived yet, which is "not known", not
                // "not there" - see the method comment.
                return null;
            }
        }

        /** {@link #item}, given a moment to resolve. Null if it never does, or the pile went. */
        public String awaitItem() throws InterruptedException {
            for (int i = 0; i < OPEN_TICKS; i++) {
                if (!ctx.running())
                    throw new InterruptedException();
                if (!alive())
                    return null;
                String n = item();
                if (n != null)
                    return n;
                ctx.nav.pause(1);
            }
            return null;
        }

        /**
         * Asks for {@code n} items to come out, and waits for the pile to settle.
         *
         * Fire-then-observe rather than one-at-a-time-and-confirm. Each item is its own message to
         * the server whichever way it is done, so confirming each one turns a thirty-item pile into
         * thirty round trips; and it is not more correct, because the answer that matters is the
         * one this waits for - what the pile ACTUALLY holds now. If the server moved fewer than we
         * asked (the pile ran out, someone else got there first) the caller sees it in the count
         * and goes round again.
         *
         * @return how many the pile actually gave up, which may be zero, and may be short.
         */
        public int draw(int n) throws InterruptedException {
            return shift(-1, n);
        }

        /** The same in the other direction: asks for {@code n} items to go in. */
        public int stow(int n) throws InterruptedException {
            return shift(1, n);
        }

        private int shift(int dir, int n) throws InterruptedException {
            if ((n <= 0) || !alive())
                return 0;
            int before = count();
            box.transfer(dir, n);
            int want = (dir < 0) ? Math.max(0, before - n) : Math.min(capacity(), before + n);
            /* Two ways out, and the second is the one that makes this safe. Reaching the expected
             * count is the ordinary end; the pile ceasing to exist is the end when the batch
             * emptied it, and waiting for a count off a dead widget would otherwise burn the whole
             * timeout every single time a pile is finished off. */
            ctx.nav.waitUntil(() -> !alive() || (count() == want), SETTLE_TICKS);
            /* One more settle after it stops moving. The server acknowledges each item separately,
             * so a batch that is going to fall short does so gradually, and reading the count the
             * instant it stops matching `want` would read it mid-flight. */
            int last = reading(dir, before);
            for (int i = 0; i < 3; i++) {
                if (!alive())
                    break;
                ctx.nav.pause(2);
                int now = reading(dir, before);
                if (now == last)
                    break;
                last = now;
            }
            return Math.abs(reading(dir, before) - before);
        }

        /**
         * What the pile holds now, including when it no longer exists.
         *
         * A dead handle's {@link ISBox} keeps whatever number it last drew, so reading it straight
         * would credit us with the items still "in" a pile that has been deleted. A pile that is
         * gone after a DRAW is gone because it ran out, which is a count of zero and the one case
         * where the number matters. Gone after a fill is not something the server does, so it is
         * reported as no movement - the safe way to be wrong about a log line.
         */
        private int reading(int dir, int before) {
            if (alive())
                return count();
            return (dir < 0) ? 0 : before;
        }

        /** Shuts this pile's window. Safe when it has already gone. */
        public void close() {
            if (ctx.gui.getwnd(WINDOW) == wnd)
                wnd.wdgmsg("close");
        }
    }

    // ------------------------------------------------------------------ where a new pile goes

    /**
     * A claim key for the ground a new pile is about to be placed on.
     *
     * Derived from the map SEGMENT tile rather than from live world coordinates, because the whole
     * point is that two different clients agree it is the same square - and live coordinates are
     * private to one client's session. Same reasoning as {@link WorldAnchor}.
     *
     * A plain string rather than a method on {@link WorkClaims}: keys there are deliberately opaque,
     * so a new kind of reservation needs nothing added to the registry.
     */
    public static String spotKey(long seg, Coord tile) {
        return "pilespot-" + Long.toHexString(seg) + "-" + tile.x + "-" + tile.y;
    }

    /**
     * Somewhere inside {@code place} to stand a new pile, in live world coordinates.
     *
     * Ordered rather than picked, so the caller can walk down the list claiming each in turn: the
     * spot has to be reserved across the walk to it, and a single answer would have two bots that
     * chose in the same second both walk to the same square and place on top of each other. That
     * is the same gap {@link WorkClaims} exists to close for work slots.
     *
     * Candidates are scored on two things, in order. TOUCHING an existing pile of the same kind
     * first, because "make a new one next to it" is what a yard is meant to look like and a
     * scattering of piles across a storage area is both ugly and slower to work; then nearest to
     * whoever is asking, so a bot that has just filled the pile it is standing at does not walk to
     * the far corner to start the next one.
     *
     * Only ever returns ground the map records say a character could stand on and nothing is
     * standing on. A square that passes here and is then refused by the server simply produces no
     * pile, and the caller moves to the next one - which is cheaper than trying to be exact about
     * a footprint the client does not authoritatively know.
     */
    public static List<Coord2d> spotsIn(GameUI gui, Place place, String likeRes, Coord2d from) {
        List<Coord2d> out = new ArrayList<>();
        if ((gui == null) || (place == null))
            return out;
        Coord2d nw = place.nw(gui);
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        Gob me = (gui.map == null) ? null : gui.map.player();
        if ((nw == null) || (here == null) || (me == null))
            return out;
        Coord2d off = here.sc.sub(me.rc);
        Coord2d want = (from == null) ? me.rc : from;

        List<Gob> like = new ArrayList<>();
        for (Gob g : within(gui, place)) {
            if ((likeRes == null) || likeRes.equals(resname(g)))
                like.add(g);
        }
        List<Gob> crowd = Crowd.others(gui);
        /* ONE object-cache snapshot for the whole sweep.
         *
         * The per-point form of this test rebuilds the snapshot on every call, and this loop runs
         * once per TILE of a storage area: a fifteen-by-fifteen yard took the cache lock and copied
         * every loaded gob two hundred and twenty-five times to place one pile, and again for each
         * of the four a trip may start. On the bot thread that reads as a hitch, and it leaves the
         * positions the rest of the sweep is comparing against already out of date. */
        List<Gob> solids = BotNav.solids(gui);

        List<Scored> found = new ArrayList<>();
        for (int ty = 0; ty < Math.max(place.h, 1); ty++) {
            for (int tx = 0; tx < Math.max(place.w, 1); tx++) {
                // The middle of the tile: a pile placed on a tile boundary is a pile half in the
                // next square, and the server rounds it somewhere we did not choose.
                Coord2d at = nw.add((tx + 0.5) * MCache.tilesz.x, (ty + 0.5) * MCache.tilesz.y);
                Coord tile = at.add(off).floor(MCache.tilesz);
                if (Observed.solid(here.seg, tile) || !Terrain.ground(gui, here.seg, tile))
                    continue;
                /* Ground the server has already refused a pile on, within the retire TTL. Nothing
                 * observable changed when it refused - no pile appeared, and that is the whole of
                 * the reply - so this list, being deterministic and sorted, would otherwise offer
                 * the same square first on every trip and walk to it again each time. */
                if (isRetiredSpot(spotKey(here.seg, tile)))
                    continue;
                // Something is already there - another pile, a cart, a wall we have not recorded.
                if (BotNav.occupied(solids, at))
                    continue;
                if (Crowd.occupied(crowd, at, Crowd.PERSONAL_SPACE))
                    continue;
                /* WE are standing on it. Crowd.others() deliberately leaves us out - it answers
                 * "who else is here" - and this is the one caller for which our own position is
                 * an obstacle rather than the origin: a pile cannot be placed under its own
                 * placer, and the caller has no way to step off a square it is aiming at. */
                if (me.rc.dist(at) < MCache.tilesz.x * 0.9)
                    continue;
                found.add(new Scored(at, adjacent(like, at), want.dist(at)));
            }
        }
        found.sort((a, b) -> (a.touching != b.touching) ? (a.touching ? -1 : 1)
                                                       : Double.compare(a.dist, b.dist));
        for (Scored s : found)
            out.add(s.at);
        return out;
    }

    /**
     * The segment tile a live world position falls on, for {@link #spotKey}.
     *
     * Separate from {@link #spotsIn} because the caller needs both the place to walk to and the key
     * to reserve it under, and recomputing the offset at the call site would be a second copy of
     * the one bit of this that is easy to get subtly wrong.
     */
    public static Coord tileOf(GameUI gui, Coord2d wc) {
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        Gob me = ((gui == null) || (gui.map == null)) ? null : gui.map.player();
        if ((here == null) || (me == null) || (wc == null))
            return null;
        return wc.add(here.sc.sub(me.rc)).floor(MCache.tilesz);
    }

    /** The segment this client is currently on, or 0 if it cannot say. */
    public static long segment(GameUI gui) {
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        return (here == null) ? 0 : here.seg;
    }

    /** Whether a candidate square touches one of the piles we are extending. */
    private static boolean adjacent(List<Gob> piles, Coord2d at) {
        double reach = MCache.tilesz.x * 1.5;
        for (Gob g : piles) {
            if (g.rc.dist(at) <= reach)
                return true;
        }
        return false;
    }

    private static final class Scored {
        final Coord2d at;
        final boolean touching;
        final double dist;

        Scored(Coord2d at, boolean touching, double dist) {
            this.at = at;
            this.touching = touching;
            this.dist = dist;
        }
    }
}
