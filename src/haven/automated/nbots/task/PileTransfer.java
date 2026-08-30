package haven.automated.nbots.task;

import haven.Coord;
import haven.GItem;
import haven.Gob;
import haven.Inventory;
import haven.Resource;
import haven.WItem;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.Reach;
import haven.automated.nbots.world.Stockpile;
import haven.res.ui.stackinv.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves items between the pack and one stockpile, in whichever direction the caller asked for.
 *
 * One class for both directions rather than two, on the {@link WorkGob#menu}/{@link WorkGob#destroy}
 * precedent: taking a spot, moving what will fit and reporting what actually shifted is the whole of
 * the task, and the direction is one sign. Two classes would have been two copies of the part that
 * is hard to get right.
 *
 * <h2>One gesture, not a hundred messages</h2>
 *
 * The game has bulk forms of both directions, and they are what this uses:
 *
 * <ul>
 *   <li><b>Shift + right-click with an EMPTY hand</b> fills the pack from the pile.</li>
 *   <li><b>Shift + right-click while HOLDING one of the pile's items</b> puts everything in the
 *       pack that belongs in the pile into it. The held item is what tells the two gestures
 *       apart, which is why {@link #bulk} takes one to hand on purpose - and why the cursor is
 *       cleared before and after, since a bare right-click with a full cursor is a third thing
 *       again (it toggles the item into its stockpile form, and a bot in that state cannot
 *       move).</li>
 * </ul>
 *
 * That replaces a per-item {@code xfer2} loop - open the window, read the count, work out a batch,
 * fire two dozen messages, wait for the count to settle, go round again - with one click and one
 * wait. The loop is still here as {@link #viaWindow} and still runs when the gesture measurably
 * moved nothing, because it is the only one that can say WHY: a pile of the wrong thing is a
 * failure the caller should act on, a pile with no room is not, and a bulk gesture moves nothing
 * either way.
 *
 * <h2>The part that is hard to get right</h2>
 *
 * Both directions can be ended by somebody else at any moment, and neither ending is an error:
 *
 * <ul>
 *   <li>Drawing from a pile another bot is also drawing from ends when the pile RUNS OUT - which
 *       deletes the gob and closes the window mid-transfer. What we took, we took; the caller wants
 *       the next pile, not a failure.</li>
 *   <li>Filling a pile another bot is also filling ends when it FILLS UP. Again not a failure: the
 *       caller still has items and wants the next pile, or a new one.</li>
 * </ul>
 *
 * So the loop is written as a convergence on observed state rather than as a plan. It asks the live
 * widget what the pile holds, moves what will fit, looks again, and stops when the pile stops
 * changing - and every one of those reads goes through {@link Stockpile.Open#alive}. There is no
 * expected count anywhere that a second bot could invalidate, which is precisely what nurgling2's
 * version has at every step (a Gob captured before the walk, a {@code getStockpile()} assumed to
 * still be open, a {@code target_size} computed once and then transferred blindly) and why it
 * throws when the pile it was working on disappears.
 *
 * The loop is also bounded twice over - by attempts and by a no-progress counter - so a pile that
 * accepts nothing and refuses nothing costs a second rather than the shift.
 */
public class PileTransfer implements Task {
    /**
     * How many items to ask for in one go.
     *
     * Each item is a separate message either way, so the batch is only about how often we stop to
     * look. Big enough that a full pack is one or two rounds, small enough that a batch which is
     * going to be refused is noticed quickly.
     */
    private static final int BATCH = 24;

    /** How many rounds that move nothing before the pile is treated as done with us. */
    private static final int IDLE_LIMIT = 2;

    /** Hard bound on rounds, so no combination of server answers can loop here forever. */
    private static final int MAX_ROUNDS = 40;

    /** Polls to wait for a deliberate pickup to reach the cursor. */
    private static final int HAND_TICKS = 40;
    /** Polls to wait for a bulk gesture to show ANY effect before calling it inapplicable. */
    private static final int BULK_START_TICKS = 60;
    /** Polls to wait for a bulk gesture to finish once it has started. */
    private static final int BULK_SETTLE_TICKS = 200;
    /** Consecutive unchanged readings that mean a bulk transfer has stopped. */
    private static final int BULK_STABLE = 3;

    private final Gob pile;
    /** Positive to put items in, negative to take them out. */
    private final int dir;
    /** Everything we mean to put in. Null when drawing - the pile decides what comes out. */
    private final Set<String> wantItems;
    /**
     * The item the pile itself must be made of before we will fill it, or null to accept any.
     *
     * Kept separate from {@link #wantItems} because the two answer different questions. This one
     * is "is this the right pile", and it has to stay narrow: "gfx/terobjs/stockpile-ore" is one
     * pile resource and half a dozen different metals, so matching on the pile alone would tip
     * cassiterite into the iron. {@code wantItems} is "which of the things in my pack belong in
     * it", and that one has to be WIDE - see the class comment on byproducts.
     */
    private final String requireItem;

    private int moved;
    private String item;
    private boolean vanished;
    /**
     * Set only when an OPEN pile reported no free space. The one honest answer to "is it full".
     *
     * Nothing else may set it, and in particular a transfer that moved nothing may not. A bulk
     * gesture moves nothing when the pile is full, when the pile holds something else, when the
     * pack's remaining cargo is a byproduct no pile takes, and when the item never reached the
     * cursor - four situations with four different right answers, and the gesture reports the same
     * thing for all of them. Treating that silence as "full" is what taught the yard-wide
     * {@link Stockpile#fullHint} threshold that a nearly empty pile was full.
     */
    private boolean full;
    /** The slot held for the length of the transfer, renewed by the loops. See {@link #run}. */
    private TakeWorkSlot spot;

    /** Pack contents by resource when the transfer began, for working out what actually shifted. */
    private Map<String, Integer> before = java.util.Collections.emptyMap();
    /** Resources whose count in the pack changed, and which way. Filled in as the task finishes. */
    private final Set<String> touched = new LinkedHashSet<>();

    private PileTransfer(Gob pile, int dir, Set<String> wantItems, String requireItem) {
        this.pile = pile;
        this.dir = dir;
        this.wantItems = wantItems;
        this.requireItem = requireItem;
    }

    /** Empties as much of a pile into the pack as will fit. */
    public static PileTransfer draw(Gob pile) {
        return new PileTransfer(pile, -1, null, null);
    }

    /**
     * Puts everything we carry of any of {@code itemRes} into a pile.
     *
     * {@code requireItem} is checked against the pile's own item before anything is sent, so a bot
     * carrying soil cannot be sent to unload it into the clay pile next door because the two happen
     * to be in the same storage area.
     */
    public static PileTransfer fill(Gob pile, Set<String> itemRes, String requireItem) {
        return new PileTransfer(pile, 1, itemRes, requireItem);
    }

    /** How many items actually changed hands. Zero is an ordinary answer, not a failure. */
    public int moved() {
        return moved;
    }

    /** The resource name of the item this pile is made of, once it has been read. */
    public String item() {
        return item;
    }

    /**
     * Which item resources actually moved between the pack and the pile.
     *
     * The answer to "what does this pile deal in", measured rather than declared, and the reason
     * this class needs no list of byproducts. Drawing from a soil pile puts SOIL in the pack and
     * also, quite often, an EARTHWORM - a different item with a different resource, which a bot
     * matching on the pile's declared item alone simply does not see. It never unloads them, so
     * they ride along trip after trip, taking pack space that should be carrying soil until the
     * bot is walking back and forth mostly full of worms.
     *
     * Reading the delta gets that right without naming worms, or naming anything: whatever came
     * out of a pile is by definition a thing that pile deals in, so it can go back into one.
     */
    public Set<String> touched() {
        return touched;
    }

    /** Whether the pile ceased to exist during the transfer - i.e. we emptied the last of it. */
    public boolean vanished() {
        return vanished;
    }

    /**
     * Whether the pile was OPENED and found to have no room. See {@link #full}.
     *
     * The only thing a caller may retire a pile on. {@code moved() == 0} is not this.
     */
    public boolean full() {
        return full;
    }

    /** What the pack holds, counted by item resource. The basis of {@link #touched}. */
    private static Map<String, Integer> census(BotCtx ctx) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Inventory inv = ctx.gui.maininv;
        if (inv == null)
            return out;
        for (WItem wi : inv.getAllItems()) {
            String r = resname(wi);
            if (r != null)
                out.merge(r, count(wi), Integer::sum);
        }
        return out;
    }

    /** Records which resources the pack gained or lost while this task ran. */
    private void settle(BotCtx ctx) {
        Map<String, Integer> now = census(ctx);
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(now.keySet());
        String biggest = null;
        int most = 0;
        for (String k : keys) {
            int was = before.getOrDefault(k, 0);
            int is = now.getOrDefault(k, 0);
            // Only in the direction this transfer was going. A resource that moved the OTHER way
            // is something else happening at the same time, not evidence about this pile.
            int delta = (dir < 0) ? (is - was) : (was - is);
            if (delta <= 0)
                continue;
            touched.add(k);
            if (delta > most) {
                most = delta;
                biggest = k;
            }
        }
        /* Name the pile's own item from what came out of it, when nothing else has.
         *
         * {@link #item} used to be set in exactly one place - reading the open pile's ISBox in
         * {@link #viaWindow} - which was fine while that was the only route. The bulk gesture does
         * not open anything, so on the fast path the field stayed null and every caller that asked
         * what this pile deals in got null: {@code MakePile} takes it as the item to build from,
         * found none, and answered "nowhere to put a new pile" without ever picking anything up.
         * A whole shift ending on a field that was simply never filled in.
         *
         * The biggest gain is the pile's item because that is what a pile is mostly made of - soil
         * comes out by the pack-load and an earthworm one at a time. Draw direction only: what a
         * FILL removed from the pack says what we were carrying, not what the pile is.
         */
        if ((dir < 0) && (item == null) && (biggest != null))
            item = biggest;
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        if (pile == null)
            return Outcome.failed("no pile");
        if (ctx.gob(pile.id) == null) {
            // Gone before we even set off. The caller's list was built a moment ago and somebody
            // else has finished this one off since; there is nothing here to fail about.
            vanished = true;
            return Outcome.ok();
        }

        /* Nothing on the cursor before we go anywhere near a pile.
         *
         * Every gesture below is a click ON the pile, and a click means something completely
         * different while holding a stockpilable item - a bare right-click toggles that item into
         * its stockpile form instead of opening or depositing anything, and the bot walks away in
         * placement form, which is the state it cannot move in. The fill path puts an item back on
         * the cursor DELIBERATELY a moment later; the point is that it starts from a known one. */
        new StowHand().run(ctx);

        /* A reserved side of the pile rather than a walk at it, for the same reason Deposit takes
         * one at a cupboard: a crew all told to work the same yard converges on the same pile
         * within a second of each other, and whoever arrives second parks against whoever arrived
         * first. Which side each takes can only be settled BEFORE the walk. */
        /* Held for the whole transfer and RENEWED by the loops, which is the half that was missing.
         *
         * A claim stands 25 seconds unrenewed ({@link WorkClaims#TTL_MS}) and this is by a wide
         * margin the longest-running task that takes one: a bulk gesture waits up to eleven
         * seconds, and the window fallback opens, reads, and then runs up to forty batches of a
         * three-second settle each - minutes, on a pile that is being worked from both ends. The
         * walk to get here is on top of that. So the reservation routinely expired while we were
         * standing in it, a crewmate claimed the same side of the same pile, and walked into us.
         * Every other task that holds a slot across real work renews it; this one did not. */
        spot = new TakeWorkSlot(pile, Reach.toActOn(pile));
        before = census(ctx);
        try {
            Outcome got = spot.run(ctx);
            if (!got.isOk())
                return got;

            /* The bulk gesture first, the window second.
             *
             * One shift-click does what the window path does in two dozen messages plus a settle
             * per batch, so it is the normal route and the window is the fallback. Which is also
             * the safer way round to be wrong: if the gesture turns out not to apply to some pile,
             * nothing moves, the code below notices, and it opens the window exactly as before. A
             * fallback that runs only when the fast path measurably achieved nothing cannot mask a
             * bug in the fast path - it just costs the slow path's messages. */
            Outcome quick = bulk(ctx);
            if (quick != null)
                return quick;

            ctx.log("bulk " + label() + " moved nothing on #" + pile.id + "; using the window");
            /* Empty the cursor before falling back, or the fallback cannot work at all.
             *
             * The bulk FILL gesture is defined by holding one of the pile's items, so arriving
             * here from that path means something is still on the cursor - and the first thing
             * viaWindow does is right-click the pile to open it. A right-click with a full cursor
             * is not "open", it is the toggle: the held item becomes a stockpile ghost, the window
             * never appears, this reports "the pile's window didn't open", and the character is
             * left in the one state it cannot walk out of. Every fallback from a fill was doing
             * this. */
            new StowHand().run(ctx);
            return viaWindow(ctx);
        } finally {
            /* The cursor is cleared BEFORE the census is taken, or the item the fill path is
             * holding counts as one that left the pack - reporting one more item moved than
             * actually did, every single time. */
            new StowHand().run(ctx);
            spot.release();
            // On EVERY exit, including the failures: what moved, moved, and the caller needs to
            // know about it whether or not the task as a whole is reporting success.
            settle(ctx);
        }
    }

    /**
     * The one-click route. Returns the outcome if it did anything, or null to fall back.
     *
     * Null rather than a blocked outcome, because "the gesture moved nothing" is not yet a fact
     * about the pile - it may be full, it may be empty, or the gesture may not apply to it - and
     * only the window can tell those apart. So only the window gets to conclude anything.
     */
    private Outcome bulk(BotCtx ctx) throws InterruptedException {
        if (dir < 0)
            return bulkDraw(ctx);
        return bulkFill(ctx);
    }

    /** Shift + right-click with an empty hand: the pile fills the pack. */
    private Outcome bulkDraw(BotCtx ctx) throws InterruptedException {
        int start = packSize(ctx);
        Stockpile.takeAll(ctx, pile);
        moved = awaitBulk(ctx, start);
        if (moved > 0) {
            vanished = (ctx.gob(pile.id) == null);
            ctx.status("Taking " + moved + ".");
            return Outcome.ok();
        }
        /* Nothing came. A pack with no free SQUARE is the ordinary reason, and it is not the whole
         * story now that things stack: a full-looking pack with a part-filled stack of soil in it
         * still has room for soil, which is why the gesture is tried before this is consulted
         * rather than as a gate in front of it. Asked afterwards it is exactly the right question -
         * the gesture has already established that nothing fits. */
        if (ctx.freeSpace() <= 0)
            return Outcome.ok();
        return null;                   // gave nothing and we had room; let the window say why
    }

    /**
     * Shift + right-click holding one of the pile's items: the pack fills the pile.
     *
     * Two things here are easy to get wrong and were both wrong.
     *
     * THE BASELINE IS TAKEN AFTER THE PICKUP. Putting an item on the cursor removes it from the
     * inventory, so a pack size measured before that changes by one the moment we pick it up - and
     * {@link #awaitBulk}, which is watching for exactly that change, reported one item transferred
     * into a pile that had accepted nothing. On a FULL pile that was catastrophic rather than
     * merely wrong: the caller saw progress it had not made, credited the trip, then asked
     * {@code noteRefusals} which cargo the pile had declined - and with nothing actually moved the
     * honest answer was "all of it", so every scrap of soil in the pack was written off as
     * undeliverable. The bot then believed it was empty, could not fetch (the pack was full), and
     * ended the shift on the first full pile it met.
     *
     * A REFUSAL IS NOT AN ANSWER, and this used to assume it was. The reasoning was that the
     * server replies to the gesture on a full pile, so nothing moving means full; but the client
     * never reads that reply here, and nothing moving is equally what a pile of the wrong thing
     * does, what a pack holding only an earthworm does, and what a pickup that did not land does.
     * Reporting "full" for all four taught the caller to retire piles that were empty.
     *
     * So a bulk fill that measurably moved nothing now falls through to the window, which is the
     * only thing that can tell the four apart - exactly as the class comment says. The cost is one
     * right-click and a read on a pile we are already standing at with its slot reserved; the
     * alternative was a guess that could not be checked and was usually wrong.
     */
    private Outcome bulkFill(BotCtx ctx) throws InterruptedException {
        List<WItem> hold = carried(ctx, wantItems);
        if (hold.isEmpty())
            return Outcome.ok();
        hold.get(0).item.wdgmsg("take", Coord.z);
        ctx.nav.waitUntil(() -> StowHand.held(ctx), HAND_TICKS);
        if (!StowHand.held(ctx))
            return null;               // never got hold of it; let the window try

        int start = packSize(ctx);
        Stockpile.putAll(ctx, pile);
        int fromPack = awaitBulk(ctx, start);
        /* The item on the cursor goes in with the rest when the gesture works, and it is not in
         * the pack count either before or after - so it is counted here or not at all. An empty
         * cursor afterwards is what says it went. */
        boolean handWent = !StowHand.held(ctx);
        moved = fromPack + (handWent ? 1 : 0);
        vanished = (ctx.gob(pile.id) == null);
        if (moved <= 0) {
            if (vanished)
                return Outcome.ok();   // emptied out from under us; there is nothing left to open
            // No log line here: run() announces the fall-through for both directions, and a busy
            // yard would otherwise get two lines per full pile.
            return null;               // let the window say why. See the method comment.
        }
        ctx.status("Filling " + moved + ".");
        return Outcome.ok();
    }

    /**
     * The original per-item route, kept whole as the fallback.
     *
     * This is the one that can say WHY nothing moved, because it opens the pile and reads it: a
     * pile of the wrong thing is a failure the caller should act on, a pile with no room is not.
     * The bulk gesture cannot tell those apart - it moves nothing either way.
     */
    private Outcome viaWindow(BotCtx ctx) throws InterruptedException {
        Stockpile.Open open = Stockpile.open(ctx, pile);
        if (open == null) {
            if (ctx.gob(pile.id) == null) {
                vanished = true;
                return Outcome.ok();
            }
            return Outcome.blocked("the pile's window didn't open");
        }
        try {
            item = open.awaitItem();
            if (item == null) {
                // Either it emptied while we waited, or the server never told us what it holds.
                // Neither is worth retrying from here.
                vanished = (ctx.gob(pile.id) == null);
                return vanished ? Outcome.ok() : Outcome.blocked("couldn't read what the pile holds");
            }
            /* ROOM BEFORE IDENTITY, on the fill side.
             *
             * A pile with nothing in it for us is not a pile of the wrong thing, and asking the
             * questions the other way round conflates them - which is how the first FULL pile in
             * the target area killed a shift. A full pile takes nothing from the bulk gesture, so
             * it falls back to here; the identity test then ran, disagreed, and returned FAILED,
             * which the caller reads as "cross this pile off for good". Every full pile retired
             * itself and the run ended a few piles later.
             *
             * Nothing to do is Outcome.ok with nothing moved. The caller already knows what to do
             * with that: try the next pile, and start a new one when they are all like this. */
            if ((dir > 0) && (open.free() <= 0)) {
                // Read off the open pile, so this is the one place entitled to say "full". The
                // caller retires on THIS and not on a transfer that happened to move nothing.
                full = true;
                return Outcome.ok();
            }

            if (!accepts(item)) {
                // Failed, not blocked: this pile will never take what we are carrying, so the
                // caller should cross it off rather than come back to it.
                ctx.log("pile #" + pile.id + " holds " + item + ", we carry "
                    + wantItems + " (wanted " + requireItem + ")");
                return Outcome.failed("that pile holds " + shortName(item));
            }
            return (dir < 0) ? drawLoop(ctx, open) : fillLoop(ctx, open);
        } finally {
            open.close();
        }
    }

    /**
     * Whether a pile made of {@code its} item is one we may put our cargo into.
     *
     * Two ways to say yes, and the second is what makes this survive the two resource names not
     * being written in the same namespace. {@link #requireItem} is the source pile's own item, and
     * where that came from matters: on the window path it is an {@code ISBox} resource, and on the
     * bulk path it is an INVENTORY resource inferred from what landed in the pack. Those describe
     * the same commodity and there is no guarantee the server spells them identically, so a plain
     * equals between one of each is a test that can fail for a pile that is perfectly correct.
     *
     * {@code wantItems} is the measured set - everything that actually came out of a pile of this
     * kind - so a pile whose item is in it is a pile we have already taken that very thing from.
     * That is stronger evidence than any string comparison, and it is why the narrow test is kept
     * as well rather than replaced: between them they accept the right piles and still refuse the
     * cassiterite-into-iron case the narrow one exists for.
     */
    private boolean accepts(String its) {
        if (its == null)
            return false;
        if ((requireItem == null) || requireItem.equals(its))
            return true;
        return (wantItems != null) && wantItems.contains(its);
    }

    /**
     * How many items are in the pack, for measuring a bulk transfer by its effect.
     *
     * ITEMS, not slots. Soil stacks five to a square, so a transfer that empties one square moved
     * five things - and counting squares would report it as one, under-reporting every bulk move
     * by up to a factor of five and, worse, reporting a move of four out of a stack of five as no
     * change at all.
     */
    private static int packSize(BotCtx ctx) {
        Inventory inv = ctx.gui.maininv;
        if (inv == null)
            return 0;
        try {
            int n = 0;
            for (WItem wi : inv.getAllItems())
                n += count(wi);
            return n;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Waits for a bulk transfer to start and then to finish, and reports how much it shifted.
     *
     * Two waits, not one, and they ask different questions. The first is "did the gesture do
     * anything at all", and its expiry is a real answer - it is what sends the caller to the
     * window. The second is "has it stopped", which needs a stable reading rather than a target,
     * because how many items a bulk move will shift is precisely what we do not know in advance.
     */
    private int awaitBulk(BotCtx ctx, int start) throws InterruptedException {
        ctx.nav.waitUntil(() -> packSize(ctx) != start, BULK_START_TICKS);
        if (packSize(ctx) == start)
            return 0;
        int last = packSize(ctx);
        int stable = 0;
        for (int i = 0; (i < BULK_SETTLE_TICKS) && (stable < BULK_STABLE); i++) {
            renewSlot();
            ctx.nav.pause(2);
            int now = packSize(ctx);
            if (now == last) {
                stable++;
            } else {
                stable = 0;
                last = now;
            }
        }
        return Math.abs(last - start);
    }

    /**
     * Tells the claim registry we are still standing in our slot.
     *
     * Cheap to call on every poll - {@link TakeWorkSlot#renew} rate-limits itself to one write per
     * {@link haven.automated.nbots.world.WorkClaims#RENEW_MS} - so it goes at the top of every loop
     * that can run longer than a claim's life rather than at some place chosen to be often enough.
     */
    private void renewSlot() {
        if (spot != null)
            spot.renew();
    }

    /**
     * Takes until the pack is full, the pile is empty, or it stops giving.
     *
     * The pile emptying is the expected way this ends and is reported as success - including when
     * it empties because another bot took the last of it while we were mid-batch, which from here
     * is indistinguishable and equally fine.
     */
    private Outcome drawLoop(BotCtx ctx, Stockpile.Open open) throws InterruptedException {
        int idle = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (!ctx.running())
                throw new InterruptedException();
            // Each round is up to a three-second settle, so the reservation has to be told we are
            // still standing in it or a crewmate takes this side of the pile out from under us.
            renewSlot();
            if (!open.alive()) {
                vanished = (ctx.gob(open.id()) == null);
                break;
            }
            int room = ctx.freeSpace();
            if (room <= 0)
                break;
            int has = open.count();
            if (has <= 0)
                break;
            int want = Math.min(BATCH, Math.min(room, has));
            int got = open.draw(want);
            moved += got;
            ctx.status("Taking " + shortName(item) + " (" + moved + ").");
            /* Room is counted in inventory CELLS, which over-counts anything bigger than one
             * square, so a batch that stops short because the pack is actually full looks exactly
             * like a pile that has stopped giving. Both want the same thing - stop asking - so
             * they share the counter rather than being told apart. */
            idle = (got > 0) ? 0 : (idle + 1);
            if (idle >= IDLE_LIMIT)
                break;
        }
        if (ctx.gob(open.id()) == null)
            vanished = true;
        return Outcome.ok();
    }

    /** Puts in until the pack is empty of this item, or the pile has no room. */
    private Outcome fillLoop(BotCtx ctx, Stockpile.Open open) throws InterruptedException {
        int idle = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (!ctx.running())
                throw new InterruptedException();
            renewSlot();
            if (!open.alive())
                break;
            int room = open.free();
            if (room <= 0) {
                // Somebody else filled it while we were walking, or during the last batch. Still
                // an authoritative reading off an open pile, so it counts as full.
                full = true;
                break;
            }
            int have = carrying(ctx, wantItems);
            if (have <= 0)
                break;
            int want = Math.min(BATCH, Math.min(room, have));
            int put = open.stow(want);
            moved += put;
            ctx.status("Filling " + shortName(item) + " (" + moved + ").");
            idle = (put > 0) ? 0 : (idle + 1);
            if (idle >= IDLE_LIMIT)
                break;
        }
        return Outcome.ok();
    }

    // ------------------------------------------------------------------ the pack

    /**
     * Everything in the main pack made of one resource.
     *
     * By RESOURCE and not by displayed name, which is what lets this work on a pile the fork has
     * never been told about: the open pile says which resource it is made of, and the same string
     * identifies the items belonging in it. Name matching would need nurgling2's thirteen-branch
     * item-name-to-pile-resource table, and would still get "Ore" wrong, since one ore pile takes
     * one metal and not the other five.
     */
    public static List<WItem> carried(BotCtx ctx, Set<String> itemRes) {
        List<WItem> out = new ArrayList<>();
        Inventory inv = ctx.gui.maininv;
        if ((inv == null) || (itemRes == null) || itemRes.isEmpty())
            return out;
        for (WItem wi : inv.getAllItems()) {
            String r = resname(wi);
            if ((r != null) && itemRes.contains(r))
                out.add(wi);
        }
        return out;
    }

    /** The single-resource form, for callers that genuinely mean exactly one thing. */
    public static List<WItem> carried(BotCtx ctx, String itemRes) {
        return (itemRes == null) ? new ArrayList<>()
                                 : carried(ctx, java.util.Collections.singleton(itemRes));
    }

    /**
     * How many matching ITEMS the pack holds - stacks counted by their contents, not as one each.
     *
     * The distinction is the whole of the bot's accounting. Soil stacks five to a slot, so a pack
     * holding thirty soil shows six slots, and a {@code carrying()} built on slot counts reports
     * six: every "is there anything left to deliver" test answered with a fifth of the truth, and
     * every "how much did that trip move" was out by the same factor.
     */
    public static int carrying(BotCtx ctx, Set<String> itemRes) {
        int n = 0;
        for (WItem wi : carried(ctx, itemRes))
            n += count(wi);
        return n;
    }

    public static int carrying(BotCtx ctx, String itemRes) {
        int n = 0;
        for (WItem wi : carried(ctx, itemRes))
            n += count(wi);
        return n;
    }

    /**
     * What one inventory slot is made of, looking THROUGH a stack wrapper to its members.
     *
     * A stacked slot is a wrapper item holding the real ones: its displayed name is
     * "&lt;item&gt;, stack of" rather than the item's, and nothing guarantees its resource is the
     * member's either. Reading the wrapper and comparing that against a pile's item is a match
     * that can quietly never succeed - the bot would carry a pack full of stacked soil and see no
     * soil in it at all. The members are the items; the wrapper is packaging.
     */
    private static String resname(WItem wi) {
        try {
            GItem it = wi.item;
            if (it == null)
                return null;
            GItem member = stacked(it);
            Resource res = (member == null ? it : member).getres();
            return (res == null) ? null : res.name;
        } catch (RuntimeException e) {
            // Includes Loading - an item whose resource has not arrived is one we cannot yet
            // classify, which is not the same as one that does not match.
            return null;
        }
    }

    /** How many real items one slot holds: a stack's member count, or one for a plain item. */
    private static int count(WItem wi) {
        try {
            GItem it = wi.item;
            if (it == null)
                return 0;
            if (it.contents instanceof ItemStack)
                return Math.max(1, ((ItemStack) it.contents).order.size());
            return 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** The first member of a stacked slot, or null when the slot is a plain item. */
    private static GItem stacked(GItem it) {
        try {
            if (!(it.contents instanceof ItemStack))
                return null;
            List<GItem> order = ((ItemStack) it.contents).order;
            return order.isEmpty() ? null : order.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A resource name reduced to its last element, for a status line. */
    public static String shortName(String res) {
        if (res == null)
            return "items";
        int cut = res.lastIndexOf('/');
        return (cut < 0) ? res : res.substring(cut + 1);
    }

    @Override
    public String label() {
        return (dir < 0) ? "draw from pile" : "fill pile";
    }
}
