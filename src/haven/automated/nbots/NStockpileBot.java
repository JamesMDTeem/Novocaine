package haven.automated.nbots;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.UI;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.task.Drink;
import haven.automated.nbots.task.MakePile;
import haven.automated.nbots.task.PileTransfer;
import haven.automated.nbots.task.StowHand;
import haven.automated.nbots.task.TravelTo;
import haven.automated.nbots.world.AreaDraw;
import haven.automated.nbots.world.Crowd;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;
import haven.automated.nbots.world.Stockpile;
import haven.automated.nbots.world.StockpileChatHook;
import haven.automated.nbots.world.WorkSlots;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Carries the contents of one yard of stockpiles into another, a pack at a time.
 *
 * The job itself is simple - draw from a pile over there, walk, put into a pile over here, start a
 * new one when they are all full - and nurgling2 has all of it in
 * {@code TransferToPiles}/{@code PileMaker}. What it does not have is any answer for the fact that
 * a crew is doing this at once, and that is what the shift below is actually built around.
 *
 * <h2>Everything here is re-read, and nothing is an error</h2>
 *
 * Three things happen constantly with several bots in one yard, and all three are ordinary:
 *
 * <ul>
 *   <li><b>The pile we were walking to has gone.</b> Another bot took the last of it, which deletes
 *       the gob. The candidate list is rebuilt from the live object cache on every pass, and
 *       {@link PileTransfer} treats a pile that vanishes mid-transfer as a completed transfer, so
 *       this costs one skipped target rather than a thrown exception.</li>
 *   <li><b>The pile we were going to unload into is full.</b> Filled by another bot while we
 *       walked. The fill loop reads free space off the live widget every round, stops when it hits
 *       zero, and the shift moves to the next pile - or starts a new one.</li>
 *   <li><b>Two bots want the same pile.</b> Each reserves a STANDING POSITION around it
 *       ({@link haven.automated.nbots.task.TakeWorkSlot}), so several can work one pile from
 *       different sides and none of them parks against another. Two bots that pick the same empty
 *       square for a new pile are settled by a claim on the SQUARE, held across the walk to it -
 *       the one conflict nothing observable can catch, since both bots are far away and the square
 *       is empty at the moment they choose.</li>
 * </ul>
 *
 * <h2>The two areas are told apart by role, not by guesswork</h2>
 *
 * Source and destination are {@link Place}s tagged {@link PlaceRoles#PILES_FROM} and
 * {@link PlaceRoles#PILES_TO}. They have to be genuinely distinct or the bot would empty a pile
 * into its neighbour and call it work, so a pile lying in BOTH areas is skipped rather than worked
 * from either end - a misconfiguration that is otherwise invisible until you notice the numbers
 * never go down.
 *
 * <h2>Nothing loops without a bound</h2>
 *
 * Every loop in this bot and in the tasks it runs is bounded twice: by a hard attempt count, and by
 * a no-progress counter that ends the shift when a full round trip moves nothing. That is the other
 * half of the lock-ups this replaces - {@code SoilStockpileDropper} spins {@code while(true)} with a
 * bare {@code continue} and no sleep, and {@code TakeItemsFromPile} loops on a window reference it
 * never re-checks.
 */
public class NStockpileBot extends NBot {
    private static final String LOG = "nbot-stockpile.log";

    /** The places this bot's own draw buttons own and replace. */
    private static final String MY_SOURCE = "Stockpiles from";
    private static final String MY_TARGET = "Stockpiles to";

    /**
     * Hard bound on round trips in one shift.
     *
     * Generous - a big yard is a lot of packs - but finite, because "walked there, moved nothing,
     * walked back" is a shape a shift must not be able to hold forever.
     */
    private static final int MAX_TRIPS = 400;

    /** How many trips in a row may move nothing before the shift is over. */
    private static final int STALL_LIMIT = 3;

    /** How many new piles one trip may start. A pack's worth never needs more than a couple. */
    private static final int MAX_NEW_PER_TRIP = 4;

    /**
     * How many times one unload may re-scan the target area for piles it has not tried yet.
     *
     * More than one because a crewmate may start a new pile while we are unloading, and that is
     * better to use than to make another beside it; bounded low because the only thing that keeps
     * a later pass from being empty is other people's work, and waiting on that is what a busy
     * yard's next trip is for.
     */
    private static final int DELIVERY_PASSES = 4;

    private Place from;
    private Place to;

    /** Built on first use; a bot that never draws an area never arms anything. */
    private AreaDraw drawFrom;
    private AreaDraw drawTo;

    /**
     * Everything this trip is carrying that belongs in a pile, by item resource.
     *
     * A SET, not one resource, because a pile does not deal in only its own item. Drawing from a
     * soil pile puts soil in the pack and, often enough, an earthworm as well - and a bot that
     * matches on the pile's declared item alone cannot see the worms, never unloads them, and
     * carries them back and forth for the rest of the shift taking up pack space that should have
     * been soil. Learned from what actually came out ({@link PileTransfer#touched}) rather than
     * from a list of byproducts, so it is right for piles nobody has thought about.
     */
    private final Set<String> cargoItems = new LinkedHashSet<>();
    /** The source pile's OWN item, which a target pile has to match before we fill it. */
    private String cargoPrimary;
    /** The source pile's gob resource, so target piles of the same kind can be found. */
    private String cargoPile;
    /**
     * Cargo the target piles have demonstrably refused, dropped from the accounting.
     *
     * Only ever set when a pile with room in it took something ELSE from the same pack in the same
     * visit - which is the one observation that distinguishes "this pile will not have it" from
     * "this pile was full". Without it, a byproduct that turns out NOT to be pile-compatible would
     * count as undelivered cargo for ever: the bot would never fetch again, would stall three
     * trips later, and would end the shift over one worm.
     */
    private final Set<String> undeliverable = new LinkedHashSet<>();

    /** Where the last pile we put something into was, so a new one is started beside it. */
    private Coord2d lastFilled;

    /** The pile last attempted to fill, so a "That stockpile is already full" refusal can retire it. */
    private Gob lastAttempted;

    private final StockpileChatHook chatHook;

    /** Items actually put into piles during the current delivery. See the counter's use in haul(). */
    private int delivered;

    /** Piles given up on this shift, so one odd object cannot stall the whole run. */
    private final Set<Long> retired = new HashSet<>();

    /** Whether the "your two areas overlap" warning has already been given this shift. */
    private boolean warnedOverlap;

    public NStockpileBot(GameUI gui) {
        super(gui, "NStockpileBot", "Stockpile Mover (crew)", LOG, UI.scale(330, 215));
        settings.places("from", "Take from", PlaceRoles.PILES_FROM);
        settings.action("drawfrom", "Draw source area", this::armSource);
        settings.flag("newpiles", "Start new piles when full", true);
        settings.places("to", "Put into", PlaceRoles.PILES_TO);
        settings.action("drawto", "Draw target area", this::armTarget);
        settings.line("kind", "Only this kind (blank = any)", "", 132);
        settings.line("retire_ttl", "Retire TTL (min)", "5", 32);
        settings.layout(this, UI.scale(10, 22), 2, UI.scale(155));
        pack();
        chatHook = new StockpileChatHook(() -> lastAttempted, this::syncRetireTtl);
        if (gui != null && gui.chat != null)
            gui.chat.addSyslogHook(chatHook);
    }

    private void armSource() {
        if (drawFrom == null)
            drawFrom = new AreaDraw(gui, MY_SOURCE, PlaceRoles.PILES_FROM);
        drawFrom.arm();
    }

    private void armTarget() {
        if (drawTo == null)
            drawTo = new AreaDraw(gui, MY_TARGET, PlaceRoles.PILES_TO);
        drawTo.arm();
    }

    /**
     * Applies a finished drag and pins the matching picker to what was drawn.
     *
     * Pinned rather than left automatic for the same reason the cleanup bot pins its work area:
     * drawing a rectangle IS the instruction, and a picker still reading "automatic" afterwards
     * would let the bot choose some other area having just been shown which one was meant.
     */
    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (drawFrom != null && drawFrom.tick() != null)
            settings.showPlace("from", MY_SOURCE);
        if (drawTo != null && drawTo.tick() != null)
            settings.showPlace("to", MY_TARGET);
    }

    /**
     * Drops outstanding drags. Closing the window with one armed would leave the map waiting for a
     * rectangle on behalf of a bot that is no longer there, and eat the player's next ground click.
     */
    @Override
    public void reqdestroy() {
        if (drawFrom != null)
            drawFrom.cancel();
        if (drawTo != null)
            drawTo.cancel();
        if (chatHook != null && gui != null && gui.chat != null)
            gui.chat.removeSyslogHook(chatHook);
        super.reqdestroy();
    }

    @Override
    protected String title() {
        return "Stockpile Mover";
    }

    // ------------------------------------------------------------------ the shift

    @Override
    protected Outcome work() throws InterruptedException {
        syncRetireTtl();
        retired.clear();
        warnedOverlap = false;
        cargoItems.clear();
        undeliverable.clear();
        cargoPrimary = null;
        cargoPile = null;
        lastFilled = null;
        lastAttempted = null;

        from = pick("from", PlaceRoles.PILES_FROM);
        if (from == null)
            return Outcome.failed(Places.whyNothing(gui, PlaceRoles.PILES_FROM)
                + " - or press \"Draw source area\"");
        to = pick("to", PlaceRoles.PILES_TO);
        if (to == null)
            return Outcome.failed(Places.whyNothing(gui, PlaceRoles.PILES_TO)
                + " - or press \"Draw target area\"");
        if (from.name.equalsIgnoreCase(to.name))
            return Outcome.failed("the source and target areas are the same place");

        ctx.log("moving piles from \"" + from.name + "\" to \"" + to.name + "\""
            + (filter().isEmpty() ? "" : " (only " + filter() + ")"));

        /* Sharing is the whole point of this bot, so neither area is claimed on its own account -
         * several characters emptying one yard into another is the case it exists for. What IS
         * honoured is the player having ticked "Only one bot at a time" on a place themselves,
         * which is what passing `false` here means: claim if the PLACE says so, never because the
         * bot wants to. */
        if (!Places.claim(from, false))
            return Outcome.blocked("another bot is already working " + from.name);
        if (!Places.claim(to, false)) {
            Places.releaseClaim(from, false);
            return Outcome.blocked("another bot is already working " + to.name);
        }
        try {
            return haul();
        } finally {
            Places.releaseClaim(to, false);
            Places.releaseClaim(from, false);
        }
    }

    /** The round trips themselves, once both ends are settled and held. */
    private Outcome haul() throws InterruptedException {
        int moved = 0;
        int trips = 0;
        int stalled = 0;

        while (running() && (trips < MAX_TRIPS) && (stalled < STALL_LIMIT)) {
            trips++;
            /* Before anything else, make sure nothing is stuck to the cursor.
             *
             * A map click made while holding something drops it instead of walking, so a bot that
             * inherits a full hand from a botched placement cannot travel, cannot approach, and
             * cannot recover - it simply stands there with a clod of soil on the pointer. The
             * unwind in MakePile is the real fix; this is the belt to its braces, and it costs one
             * field read when the hand is already empty. */
            new StowHand().run(ctx);

            // Claims expire on their own, so a long shift has to say it is still here. Free when
            // neither place is exclusive, which is the normal case.
            Places.renewClaim(from, false);
            Places.renewClaim(to, false);

            /* Upkeep is an ERRAND - it can walk the character to a barrel on the far side of the
             * base - so it only runs with an empty pack, i.e. immediately before a fetch.
             *
             * Running it every trip regardless is what "they keep going back to base with plenty
             * of soil still in their inventory" was: a bot holding a full load would dip below the
             * drink threshold, break off on the way to the target area, walk home for water, and
             * walk back, still carrying everything. The load is already in hand and the target is
             * already the destination - finishing the delivery first costs nothing and removes the
             * detour entirely.
             *
             * A loaded bot still DRINKS, from what it is carrying. That is the cheap half and it
             * needs no walk at all. */
            if (carrying() > 0) {
                Drink.sipIfCarried(ctx);
            } else if (!upkeep()) {
                return Outcome.failed(fatalStop);
            }

            /* Deliver first when we are already carrying something. A shift can start with a pack
             * half full of soil from the last one, and fetching more before unloading it means
             * arriving at the source with no room and taking nothing - which reads as "the source
             * is empty" and ends the shift with a full pack. */
            if (carrying() == 0) {
                Outcome f = fetch();
                if (f.isFailed())
                    return f;
                if (carrying() == 0) {
                    // Nothing left to pick up, and nothing in hand. That is the job done - but only
                    // believe it from inside the source area, since a scan only sees what is
                    // rendered and upkeep may have walked us across the base to a barrel.
                    if (inside(from))
                        break;
                    ctx.log("no piles in sight from here; going back to " + from.name);
                    if (!new TravelTo(from).run(ctx).isOk())
                        break;
                    continue;
                }
            }

            /* Counted from what the transfers actually reported, not from the change in what we
             * are carrying. The two came apart once cargo could be RECLASSIFIED mid-delivery:
             * marking a byproduct undeliverable drops it out of `carrying()` without a single item
             * having moved, which would read here as progress - inflating the total and, worse,
             * resetting the stall counter on a trip that achieved nothing. */
            delivered = 0;
            Outcome d = deliver();
            moved += delivered;
            setStatus("Moved " + moved + " so far.");

            if (delivered == 0) {
                stalled++;
                if (d.isFailed())
                    return d;
                ctx.log("trip " + trips + " moved nothing: " + (d.reason == null ? "no reason given" : d.reason));
            } else {
                stalled = 0;
            }
            if (carrying() == 0) {
                // The pack is clear of anything we still mean to deliver, so the next trip is free
                // to pick a different kind of pile.
                cargoItems.clear();
                undeliverable.clear();
                cargoPrimary = null;
                cargoPile = null;
            }
        }

        if (carrying() > 0)
            report("finished still carrying " + carrying() + " - " + to.name + " has no room left.");
        report("moved " + moved + " item(s) from " + from.name + " to " + to.name + ".");
        setStatus("Done: " + moved + " moved.");
        return Outcome.ok();
    }

    /**
     * How a place is chosen: the pinned one, else the one we are standing in, else the nearest.
     *
     * A pinned area wins outright because naming one is an explicit instruction - a crew whose bots
     * each have their own pair posted must not have that quietly overridden by whichever area a
     * character happens to be standing in when its shift begins.
     */
    private Place pick(String key, String role) {
        String pinned = settings.place(key);
        if (!pinned.isEmpty()) {
            Place p = Places.byName(pinned);
            if (p != null)
                return p;
        }
        Place p = Places.containing(gui, role);
        return (p != null) ? p : Places.nearest(gui, role);
    }

    // ------------------------------------------------------------------ fetching

    /**
     * Goes to the source area and fills the pack from one kind of pile.
     *
     * One kind per trip, decided by whichever pile first gives us anything. Mixing kinds would work
     * for the carrying and fall apart at the other end: a pile takes one item resource, so a pack
     * of soil and clay needs two separate rounds of destination piles anyway, and doing that per
     * trip is a great deal of walking for no gain.
     */
    private Outcome fetch() throws InterruptedException {
        Outcome t = new TravelTo(from).run(ctx);
        if (!t.isOk())
            return t;

        for (Gob pile : candidates(from)) {
            if (!running())
                throw new InterruptedException();
            if (ctx.freeSpace() <= 0)
                break;
            String res = Stockpile.resname(pile);
            // Captured before the transfer: emptying the pile deletes the gob, and reading its
            // resource afterwards is reading a corpse.
            if ((cargoPile != null) && !cargoPile.equals(res))
                continue;

            PileTransfer draw = PileTransfer.draw(pile);
            Outcome o = draw.run(ctx);
            if (o.isFailed()) {
                ctx.log("giving up on source pile #" + pile.id + ": " + o.reason);
                retired.add(pile.id);
                continue;
            }
            if (draw.moved() > 0) {
                cargoPrimary = draw.item();
                cargoPile = res;
                /* Everything that actually came out, not just what the pile says it is made of.
                 * A soil pile hands over earthworms too, and they belong back in a soil pile -
                 * measuring it is what saves this from needing to know that. */
                cargoItems.addAll(draw.touched());
                if (cargoPrimary != null)
                    cargoItems.add(cargoPrimary);
            }
            /* Blocked means every side of it is taken or we could not get there - a crewmate is on
             * it, or will be off it shortly - so it is left alone for this pass and NOT retired.
             * Retiring on blocked is what makes a crew give up on a busy yard. */
        }
        return Outcome.ok();
    }

    // ------------------------------------------------------------------ delivering

    /** Goes to the target area and unloads, starting new piles when the existing ones are full. */
    private Outcome deliver() throws InterruptedException {
        if (carrying() == 0)
            return Outcome.ok();
        Outcome t = new TravelTo(to).run(ctx);
        if (!t.isOk())
            return t;

        /* The candidate list is rebuilt on every pass rather than iterated once, because the
         * interesting changes all happen DURING the unload: a crewmate fills the pile we were
         * about to use, or starts a new one we should be using before making our own. A list
         * captured up front can see neither.
         *
         * `done` is what stops that becoming a second walk round the whole yard. A pile we opened
         * and left is a pile with no room in it - filling only ever takes room away - so there is
         * nothing to gain by opening it again this trip. A pile that came back BLOCKED is the
         * exception and is deliberately not recorded: blocked means a crewmate is standing on the
         * only side we could reach, which is exactly the kind of thing that resolves itself in the
         * time it takes to visit the next pile. */
        final Set<Long> done = new HashSet<>();
        for (int pass = 0; running() && (carrying() > 0) && (pass < DELIVERY_PASSES); pass++) {
            List<Gob> open = acceptors();
            open.removeIf(g -> done.contains(g.id));
            if (open.isEmpty())
                break;
            for (Gob pile : open) {
                if (!running())
                    throw new InterruptedException();
                if (carrying() == 0)
                    return Outcome.ok();
                lastAttempted = pile;
                PileTransfer fill = PileTransfer.fill(pile, deliverable(), cargoPrimary);
                Outcome o = fill.run(ctx);
                if (o.isFailed()) {
                    // The pile holds something else, or cannot be read. Either way it will never
                    // take this, so cross it off for the shift rather than coming back to it.
                    ctx.log("not using target pile #" + pile.id + ": " + o.reason);
                    retired.add(pile.id);
                    done.add(pile.id);
                    continue;
                }
                if (o.isBlocked())
                    continue;
                done.add(pile.id);
                if (fill.moved() == 0 && carrying() > 0) {
                    // 0-move ok is full (bulkFill returns ok for full, not failed — do not
                    // change PileTransfer semantics). Promote to retired so remaining
                    // DELIVERY_PASSES in this deliver() skip it via acceptors() isRetired.
                    // Capacity from Open/FULL_AT when available; 0 handled gracefully by
                    // Stockpile.retire which still learns sdt via learnFull.
                    int cap = 0;
                    try {
                        // Future: if PileTransfer exposes open capacity, use it here.
                        // For bulk path no window was opened, so cap stays 0 and retire
                        // still records sdt TTL via learnFull(res, sdt).
                    } catch (RuntimeException ignored) {}
                    syncRetireTtl();
                    Stockpile.retire(pile, cap);
                }
                if (fill.moved() > 0) {
                    delivered += fill.moved();
                    lastFilled = pile.rc;
                }
                /* Guarded on the MEASURED set, not on the reported count. `touched` comes from
                 * counting the pack before and after; `moved` is the transfer's own estimate, and
                 * an estimate that is wrong high turns this into "the pile took nothing of ours,
                 * so write off everything we carry". One bad count wrote off a whole pack. An
                 * empty `touched` means nothing demonstrably moved, and nothing can be inferred
                 * from a pile that did nothing. */
                if (!fill.touched().isEmpty())
                    noteRefusals(fill.touched());
            }
        }
        if (carrying() == 0)
            return Outcome.ok();

        if (!settings.on("newpiles"))
            return Outcome.blocked("every pile in " + to.name + " is full");
        return startNew();
    }

    /**
     * Starts new piles beside the full ones and empties the pack into them.
     *
     * Bounded per trip because a pack holds a knowable amount: needing a fifth new pile for one
     * load means something is refusing what we think it is accepting, and walking on placing piles
     * would turn that into a yard full of one-item stockpiles.
     */
    private Outcome startNew() throws InterruptedException {
        for (int i = 0; running() && (carrying() > 0) && (i < MAX_NEW_PER_TRIP); i++) {
            MakePile mk = new MakePile(to, cargoPrimary, cargoPile, lastFilled);
            Outcome o = mk.run(ctx);
            if (!o.isOk())
                return o;
            Gob pile = mk.made();
            if (pile == null)
                return Outcome.blocked("the new pile didn't appear");
            lastFilled = pile.rc;
            /* Creating the pile normally empties the pack by itself - MakePile shift-places, which
             * builds and fills in one gesture - so the usual case here is that there is nothing
             * left to do. Checking beats visiting: the fill below would otherwise walk back to a
             * pile we are standing next to, take an item to hand and shift-click it, to move zero
             * items. */
            if (carrying() == 0)
                return Outcome.ok();
            lastAttempted = pile;
            PileTransfer fill = PileTransfer.fill(pile, deliverable(), cargoPrimary);
            Outcome f = fill.run(ctx);
            if (f.isFailed())
                return f;
            if (fill.moved() > 0)
                delivered += fill.moved();
            if (!fill.touched().isEmpty())
                noteRefusals(fill.touched());
            if (fill.moved() == 0 && carrying() > 0) {
                // A brand-new pile that takes nothing is not a pile we understand; another one
                // would do exactly the same.
                return Outcome.blocked("a new pile wouldn't take anything");
            }
        }
        return (carrying() == 0) ? Outcome.ok()
                                 : Outcome.blocked("couldn't fit the rest into " + to.name);
    }

    // ------------------------------------------------------------------ choosing piles

    /**
     * Piles in the source area worth trying, nearest first, skipping ones already crowded.
     *
     * Says why it came back empty, and that is not decoration. An empty list is how this bot
     * FINISHES - the shift breaks out of the loop and reports success - so the difference between
     * "the yard is clear" and "every pile was filtered out by a setting" is the difference between
     * a job done and a job never started, and from outside the two are the same clean exit after a
     * few seconds. A whole run's logs said nothing at all between "moving piles from A to B" and
     * "shift end", which is the least useful thing a log can do.
     *
     * Counted per reason rather than logged per pile: a yard has dozens, and one line naming which
     * filter ate them is what actually answers the question.
     */
    private List<Gob> candidates(Place place) {
        List<Gob> out = new ArrayList<>();
        Gob me = ctx.player();
        List<Gob> others = Crowd.others(gui);
        List<Gob> inArea = Stockpile.within(gui, place);
        int byRetired = 0, byKind = 0, byOverlap = 0, byCrowd = 0;
        for (Gob g : inArea) {
            if (retired.contains(g.id)) {
                byRetired++;
                continue;
            }
            if (!wanted(g)) {
                byKind++;
                continue;
            }
            if (bothEnds(g)) {
                byOverlap++;
                continue;
            }
            // Already fully surrounded. A cheap pre-filter; TakeWorkSlot makes the real call.
            if (Crowd.workersOn(others, g.rc) >= WorkSlots.around(g).count) {
                byCrowd++;
                continue;
            }
            out.add(g);
        }
        if (out.isEmpty()) {
            ctx.log("no source pile to work in \"" + place.name + "\": "
                + inArea.size() + " stockpile(s) inside it"
                + (inArea.isEmpty() ? " - either the area does not cover them or none is loaded"
                                    : "")
                + (byKind > 0 ? ", " + byKind + " rejected by the \"" + filter() + "\" filter" : "")
                + (byOverlap > 0 ? ", " + byOverlap + " lie in both areas" : "")
                + (byCrowd > 0 ? ", " + byCrowd + " already fully surrounded" : "")
                + (byRetired > 0 ? ", " + byRetired + " given up on earlier this shift" : "")
                + "; standing " + (inside(place) ? "inside" : "OUTSIDE") + " the area");
        }
        if (me != null)
            out.sort((a, b) -> Double.compare(me.rc.dist(a.rc), me.rc.dist(b.rc)));
        return out;
    }

    /**
     * Piles in the target area that might still take what we are carrying.
     *
     * Ones that LOOK full go last rather than being dropped. The look is a learned guess about a
     * sprite state ({@link Stockpile#fullHint}), and a guess is good enough to decide what to try
     * first and not good enough to decide what to ignore - being wrong the other way would have the
     * bot start a new pile beside a half-empty one.
     */
    private List<Gob> acceptors() {
        syncRetireTtl();
        Stockpile.expireSweep();
        List<Gob> out = new ArrayList<>();
        Gob me = ctx.player();
        List<Gob> others = Crowd.others(gui);
        for (Gob g : Stockpile.within(gui, to)) {
            if (retired.contains(g.id) || bothEnds(g))
                continue;
            if ((cargoPile != null) && !cargoPile.equals(Stockpile.resname(g)))
                continue;
            if (Stockpile.isRetired(g) || Stockpile.isRetiredSpot(Stockpile.spotKeyFor(gui, g)))
                continue;
            if (Stockpile.fullHint(g))
                continue;
            if (Crowd.workersOn(others, g.rc) >= WorkSlots.around(g).count)
                continue;
            out.add(g);
        }
        out.sort((a, b) -> {
            boolean fa = Stockpile.fullHint(a), fb = Stockpile.fullHint(b);
            if (fa != fb)
                return fa ? 1 : -1;
            if (me == null)
                return 0;
            return Double.compare(me.rc.dist(a.rc), me.rc.dist(b.rc));
        });
        return out;
    }

    /**
     * Whether a pile lies in both areas at once.
     *
     * Which is a configuration mistake rather than a situation, and one that otherwise shows up as
     * a bot that works all day and moves nothing: it would take a load out of a pile and put it
     * straight back into the same pile, since the pile qualifies at both ends. Said once per shift
     * because it is the player's to fix, not the bot's to route around.
     */
    private boolean bothEnds(Gob g) {
        if (!from.contains(gui, g.rc) || !to.contains(gui, g.rc))
            return false;
        if (!warnedOverlap) {
            warnedOverlap = true;
            reportError("\"" + from.name + "\" and \"" + to.name + "\" overlap - piles in both are"
                + " being skipped. Redraw one of them so they don't touch.");
        }
        return true;
    }

    /** Whether the player's kind filter, if they typed one, admits this pile. */
    private boolean wanted(Gob g) {
        String want = filter();
        if (want.isEmpty())
            return true;
        String res = Stockpile.resname(g);
        return (res != null) && res.toLowerCase().contains(want);
    }

    private String filter() {
        return settings.str("kind").toLowerCase();
    }

    private int retireTtlMinutes() {
        String raw = settings.str("retire_ttl");
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 1) return 1;
            if (v > 30) return 30;
            return v;
        } catch (Exception e) {
            return 5;
        }
    }

    private void syncRetireTtl() {
        Stockpile.setRetireTtlMinutes(retireTtlMinutes());
    }

    /** The cargo still worth trying to deliver - everything learned, less what has been refused. */
    private Set<String> deliverable() {
        Set<String> out = new LinkedHashSet<>(cargoItems);
        out.removeAll(undeliverable);
        return out;
    }

    private int carrying() {
        return PileTransfer.carrying(ctx, deliverable());
    }

    /**
     * Works out which cargo a pile that was taking things would NOT take, and stops counting it.
     *
     * Called only after a fill that moved SOMETHING, and that condition is the whole of the
     * inference. A pile that took nothing tells us nothing - it was full, or busy, or we could not
     * reach it. A pile that took soil out of this pack and left the worms had room, had our
     * attention, and declined them; nothing else is going to take them either.
     *
     * Without this the residue is permanent cargo: {@link #carrying} never reaches zero, so the
     * bot never fetches again, stalls three trips later, and ends a shift with a full source yard
     * over one item it cannot put down.
     */
    private void noteRefusals(Set<String> sent) {
        for (String r : deliverable()) {
            if (sent.contains(r) || (PileTransfer.carrying(ctx, r) <= 0))
                continue;
            undeliverable.add(r);
            ctx.log("target piles won't take " + PileTransfer.shortName(r)
                + " - carrying it but no longer counting it as cargo");
        }
    }

    private boolean inside(Place p) {
        Gob me = ctx.player();
        return (me != null) && (p != null) && p.contains(gui, me.rc);
    }
}
