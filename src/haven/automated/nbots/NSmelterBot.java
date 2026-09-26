package haven.automated.nbots;

import haven.Config;
import haven.Coord;
import haven.Coord2d;
import haven.GItem;
import haven.GameUI;
import haven.Gob;
import haven.Inventory;
import haven.ItemInfo;
import haven.LayerMeter;
import haven.Loading;
import haven.Makewindow;
import haven.MenuGrid;
import haven.ResDrawable;
import haven.Resource;
import haven.UI;
import haven.VMeter;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Widgets;
import haven.automated.nbots.task.Approach;
import haven.automated.nbots.task.Drink;
import haven.automated.nbots.task.MakePile;
import haven.automated.nbots.task.PileTransfer;
import haven.automated.nbots.task.StowHand;
import haven.automated.nbots.task.TravelTo;
import haven.automated.nbots.task.Upkeep;
import haven.automated.nbots.world.AreaDraw;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;
import haven.automated.nbots.world.Reach;
import haven.automated.nbots.world.Stockpile;
import haven.automated.nbots.world.Walk;
import haven.automated.nbots.world.WorkSpot;
import haven.res.ui.stackinv.ItemStack;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static haven.OCache.posres;

/**
 * Runs a yard of ore smelters: fills them, fuels them, lights them, waits, empties them, stacks
 * the bars, and goes round again until the ore runs out.
 *
 * nurgling2 has the same job as {@code SmelterAction}, and it is where the shape of this came from
 * - its order of work, its fuel figures, and the "is every piece well mined" test that decides
 * between them. What it does not do is keep the two kinds of ore apart. It fills from whatever the
 * piles hand over, and one ordinary piece among twenty-four well-mined ones costs the whole
 * smelter the three coal that well-mined ore saves.
 *
 * <h2>One area, told apart by contents</h2>
 *
 * The whole setup is a single place tagged {@link PlaceRoles#SMELTING} drawn round the lot.
 * Smelters are found by resource; everything else in there - stockpiles and containers - is
 * opened once and remembered by what it turned out to hold, so a coal pile, an ore chest and a
 * pile of cat gold need no labels to be used for the right thing. A yard spread over several
 * areas can tag them {@code smelt-ore}, {@code smelt-fuel}, {@code smelt-ignite} and
 * {@code smelt-bars}; any that exists is used for its own job, and any that doesn't falls back
 * to the smelting area.
 *
 * <h2>Well mined is a property of the piece, not the stack</h2>
 *
 * Ore stacks in the pack regardless of whether it was well mined, so one stack can hold both. The
 * bot reads every member of every stack ({@link #pieces}) and loads a smelter one kind at a time:
 * an empty smelter takes whichever kind the pack holds most of, a part-filled one keeps the kind
 * it already has. Leftovers of the other kind ride along to the next smelter, which - being
 * empty - picks them up first. Only when the source runs dry does "fill to the brim" meet "don't
 * mix", and which of the two wins is the player's call ({@code mix}).
 *
 * <h2>What has not been seen working</h2>
 *
 * Three facts it depends on come from reading nurgling2 rather than from watching the game, and
 * each is logged so a run settles it:
 *
 * <ul>
 *   <li>the fire flag is bit 2 of the smelter's first sdt byte ({@link #lit});</li>
 *   <li>the orange meter in the smelter window is the fuel, out of {@link #FUEL_UNITS};</li>
 *   <li>the spark recipe's name, which is a setting for that reason.</li>
 * </ul>
 *
 * A fourth was settled against it: shift-clicking one piece inside a stack does nothing, so ore
 * moves by whole slot ({@link #load}).
 */
public class NSmelterBot extends NBot {
    private static final String LOG = "nbot-smelter.log";

    /** The place this bot's own draw button owns and replaces. */
    private static final String MY_AREA = "Smelting";

    /** Ore Smelter and Smith's Smelter share this. The stack furnace is {@code primsmelter}. */
    private static final String SMELTER_RES = "gfx/terobjs/smelter";

    /** What goes into a smelter, by item basename. The client's own ore lists, both of them. */
    private static final Set<String> ORES = new HashSet<>();
    static {
        ORES.addAll(Config.oreItemBaseNames);
        ORES.addAll(Config.preciousOreItemBaseNames);
    }

    private static final String CATGOLD = "catgold";
    private static final String BRANCH = "branch";
    private static final String SLAG = "slag";
    /** Every metal bar is {@code gfx/invobjs/bar-<metal>}. */
    private static final String BAR = "bar-";

    /**
     * Ore by the name the game shows, from nurgling2's {@code SmelterAction}.
     *
     * The client's own {@code Config} lists are keyed by resource basename and are short - ten
     * ores and six precious ones, nothing named the way the game names them - and the first run
     * opened an ore pile, found its contents on neither list, and walked off without a word. So
     * three things count as ore now: those basenames, these names, and whatever an ore pile turns
     * out to hold ({@link #learnedOre}).
     */
    private static final Set<String> ORE_NAMES = new HashSet<>(Arrays.asList(
        "Cassiterite", "Lead Glance", "Wine Glance", "Chalcopyrite", "Malachite", "Peacock Ore",
        "Cinnabar", "Heavy Earth", "Iron Ochre", "Bloodstone", "Black Ore", "Galena", "Silvershine",
        "Horn Silver", "Direvein", "Schrifterz", "Leaf Ore", "Meteorite", "Dross"));
    private static final Predicate<String> IS_COAL = b -> (b != null) && Config.coalItemBaseNames.contains(b);
    private static final Predicate<String> IS_CATGOLD = CATGOLD::equals;
    private static final Predicate<String> IS_BRANCH = BRANCH::equals;
    private static final Predicate<String> IS_BAR = b -> (b != null) && b.startsWith("bar-");

    /** The tooltip line a well-mined piece carries. WItem draws its marker off the same string. */
    private static final String WELL_MINED = "Well mined";

    /**
     * Which stockpiles are worth opening for each supply, by the suffix of the pile's resource
     * ({@code gfx/terobjs/stockpile-ore} and so on).
     *
     * A pile says what kind it is from across the yard, so there is no reason to walk up to one
     * that cannot hold what we want. The first run did - it opened every pile in the area, the
     * bar piles included, looking for ore. Cat gold is stacked with the stones.
     */
    private static final Set<String> ORE_PILES = new HashSet<>(Arrays.asList("ore"));
    private static final Set<String> COAL_PILES = new HashSet<>(Arrays.asList("coal"));
    private static final Set<String> CATGOLD_PILES = new HashSet<>(Arrays.asList("stone"));
    private static final Set<String> BRANCH_PILES = new HashSet<>(Arrays.asList("branch"));
    /** What bars are stacked in, so a new pile joins the row of existing ones. */
    private static final String BAR_PILE = "gfx/terobjs/stockpile-metal";

    /** Containers worth opening for supplies. Deposit's list, less the barrel. */
    private static final Alias CONTAINERS = new Alias("containers",
        "cupboard", "chest", "crate", "coffer", "box");

    /** The firebrand recipe under either name it has gone by. */
    private static final String[] FIREBRAND_RECIPES = {"Firebrand", "Light fire"};

    /**
     * The fuel meter's colour and scale, from nurgling2's {@code Container.FuelLvl}, which reads a
     * smelter as 30 units of fuel with one coal per unit.
     */
    private static final Color FUEL_COLOR = new Color(255, 128, 0);
    private static final int FUEL_UNITS = 30;

    /** Polls (25ms each) for a container window to come up. */
    private static final int OPEN_TICKS = 80;
    /** Polls for a batch of transfers to show in the counts. */
    private static final int MOVE_TICKS = 80;
    /** Polls for an item to reach the cursor. */
    private static final int HAND_TICKS = 40;
    /** Polls to wait after the last coal before opening the smelter to read its meter. */
    private static final int METER_SETTLE = 20;

    /** The fuel meter once two readings a third of a second apart agree. */
    private int settledFuel(View v) throws InterruptedException {
        int last = v.fuel();
        for (int k = 0; k < 10; k++) {
            ctx.nav.pause(12);
            int now = v.fuel();
            if (now == last)
                return now;
            last = now;
        }
        return last;
    }

    /** Polls between right-clicks when feeding coal from a held stack. */
    private static final int COAL_GAP = 4;
    /** Polls for a smelter to catch once the igniter is applied. */
    private static final int LIGHT_TICKS = 240;

    /**
     * How long one burn may take before the wait is abandoned. Far longer than any real burn -
     * this is only here so a smelter stuck reading "lit" cannot hold the shift for ever.
     */
    private static final long BURN_LIMIT_MS = 4L * 60 * 60 * 1000;

    /** Hard bound on fill-light-wait-empty rounds in one shift. */
    private static final int MAX_CYCLES = 200;
    /** Hand-out-then-fetch rounds in one fill. */
    private static final int FILL_ROUNDS = 40;
    /** Walkable ground within three tiles, in square tiles, that counts as being out in the open. */
    private static final double OPEN_GROUND = 8;
    /** A stretch of ground at least this big, in square tiles, counts as open. */
    private static final double OPEN_AREA = 15;
    /** Less walkable ground than this round us, in square tiles, and we are shut in. */
    private static final double BOXED_IN = 6;
    /** How many standing spots to try before giving up on a target. */
    private static final int SIDES = 6;
    /** Further than this and the grid would be too big: walk nearer by the ordinary route first. */
    private static final double FAR = 11 * 30;
    /**
     * Character half-widths to look for standing spots at, widest first. The first is what the
     * client pathfinder assumes. The narrower ones exist for #789695737 in the test yard: its only
     * open face is a corridor whose ways in are 4.2 and 4.8 units wide - shut at the pathfinder's
     * 6, open at 5 - and it is reachable in the game, so the real character is narrower than the
     * pathfinder's. Walked with plain server moves and judged by arriving, so a gap the server
     * will not let us through costs one failed spot, not a wedge.
     */
    private static final double[] WIDTHS = {haven.automated.pathfinder.World.HALFWIDTH, 2.5, 2.0};
    /**
     * A standing spot this close to the target's edge is one the server's own approach from cannot
     * go wrong: a click there is a step, not a walk. The fifth run's spots for the north-east
     * smelter were 20 to 33 units out, and from that far the server walked in at an angle the gap
     * would not take - what James does by hand is stand right up against it first.
     */
    private static final double CLOSE = 8.0;
    /** Standing spots tried per target, across all widths. */
    private static final int MAX_TRIES = 10;
    /**
     * Of those, how many may be spots whose straight line to the target is blocked - worth a try,
     * since the server's approach is not quite a straight line, but rarely right. The sixth run
     * spent twenty seconds on six of them round one stone pile.
     */
    private static final int MAX_BLOCKED = 2;

    /**
     * Pile kinds whose window names the KIND and not the item. An ore pile of Wine Glance and
     * Cassiterite reports itself as "ore-iron", a pile of cat gold as "stone" - so what one holds
     * is only known by taking a piece out and looking at it ({@link #probe}).
     */
    private static final Set<String> MIXED_PILES = new HashSet<>(Arrays.asList("ore", "stone"));
    /**
     * Pack slots the fill leaves empty. Room for the one item the next step needs to handle -
     * a coal to hand, a spark - when ore of the wrong grade is still being carried.
     */
    private static final int RESERVE = 2;

    /**
     * Recipe name -> how many of its ingredient one craft takes, read off the crafting window.
     * Kept across shifts: it is a fact about the game. Overrides the per-igniter settings, which
     * are only a first guess - the ninth run trusted "1 cat gold per spark", had one piece left,
     * and failed six crafts in a row walking the row.
     */
    private final Map<String, Integer> recipeUses = new HashMap<>();
    /** Recipes whose ingredient list has been written to the log. */
    private final Set<String> recipeLogged = new HashSet<>();

    /**
     * Smelters that have finished smelting while their fire still burns - more coal went in than
     * the batch needed, so the smoke goes on after the lava has stopped and the bars have formed.
     * The fire flag alone called those "still running" for as long as the spare coal lasted.
     * Judged by opening the smelter and finding no ore left in it; cleared when ore goes back in.
     */
    private final Set<Long> finished = new HashSet<>();
    /** "id:state" pairs already opened to check - each smelter is looked into once per state. */
    private final Set<String> checkedState = new HashSet<>();
    /** When this bot lit each smelter, for {@link #busy}'s grace period. */
    private final Map<Long, Long> litAt = new HashMap<>();
    /** The state bit that is on while a smelter is actually smelting. See {@link #busy}. */
    private static final int LAVA = 4;
    /** The last state byte seen per smelter, so changes can be logged while the meaning is learned. */
    private final Map<Long, Integer> lastState = new HashMap<>();

    /** Targets whose grid has already been drawn into the log this shift. */
    private final Set<Long> pictured = new HashSet<>();

    /**
     * The way back out after standing somewhere only a narrower-than-pathfinder character fits.
     * Walked before anything that asks the client pathfinder for a route, which would find none
     * from in there.
     */
    private List<Coord2d> exitLegs;
    /** How close to a chosen standing spot counts as being on it. */
    private static final double ON_SPOT = 11 * 0.6;
    /** New bar piles one delivery may start. */
    private static final int MAX_NEW_PILES = 4;

    private enum Grade { WELL, PLAIN }


    private Place area;
    private AreaDraw draw;

    /**
     * Item basenames learned to be ore: whatever came out of an ore pile, and anything whose name
     * is on {@link #ORE_NAMES}. Kept across shifts - it is a fact about the game, not the yard.
     */
    private final Set<String> learnedOre = new HashSet<>();
    private final Predicate<String> isOre = b -> (b != null) && !CATGOLD.equals(b)
        && (ORES.contains(b) || learnedOre.contains(b));

    /**
     * Gob id -> where we last stood when working it succeeded. A place that is known to work, so
     * every later visit - to fuel it, to light it, to empty it - walks straight there instead of
     * finding out again which side is blocked.
     */
    private final Map<Long, Coord2d> workedFrom = new HashMap<>();
    /** The character width the spot in {@link #workedFrom} was reached at. */
    private final Map<Long, Double> workedWidth = new HashMap<>();
    /** The width the current standing spot was reached at, for {@link #worked}. */
    private double standingWidth = haven.automated.pathfinder.World.HALFWIDTH;
    /** A walk that stops this close to its spot is close enough to try from. */
    private static final double NEAR_ENOUGH = 8.0;

    /** Pile id -> the basename of what it holds. A pile only ever holds one thing. */
    private final Map<Long, String> pileItem = new HashMap<>();
    /** Container id -> basenames seen in it on the last visit, so empty-of-it chests are skipped. */
    private final Map<Long, Set<String>> boxHeld = new HashMap<>();
    /** Supplies that would not open this shift. */
    private final Set<Long> dead = new HashSet<>();
    /**
     * Piles found full, and when. A full pile stays full until somebody takes from it, which a
     * smelting yard's bar and ore piles mostly never see - so this outlives the shift and a pile is
     * looked at again only after {@link #FULL_RECHECK_MS}. It used {@link Stockpile#retire}, whose
     * five minutes run out long before a fifty-minute burn does, and every stacking round walked
     * back to open the same full piles (James).
     */
    private final Map<Long, Long> fullPiles = new HashMap<>();
    private static final long FULL_RECHECK_MS = 60L * 60 * 1000;
    /** "pileid:res" pairs a bar pile has refused. */
    private final Set<String> refused = new HashSet<>();
    /** Ore loaded into smelters so far this fill, for spotting rounds that achieve nothing. */
    private int loadedTotal;
    /** Rounds in a row that loaded nothing before the fill gives up on what is left. */
    private static final int FILL_STALL = 3;
    /** Whether the ore sources ran out during this cycle's fill. */
    private boolean oreDry;
    /** Whether the fuel meter could not be found, said once per shift. */
    private boolean warnedMeter;
    private int barsPiled;

    public NSmelterBot(GameUI gui) {
        super(gui, "NSmelterBot", "Smelter (crew)", LOG, UI.scale(360, 250));
        settings.places("area", "Smelting area", PlaceRoles.SMELTING);
        settings.action("draw", "Draw smelting area", this::arm);
        settings.number("coal_wm", "Coal (well-mined)", 9);
        settings.number("coal", "Coal (other ore)", 12);
        settings.flag("separate", "Keep well-mined apart", true);
        settings.flag("mix", "Top up with other kind", false);
        settings.line("spark", "Spark recipe (blank = firebrand)", "Pyrite Spark", 150);
        settings.number("catgold", "Cat gold / spark", 1);
        settings.number("branches", "Branches / brand", 2);
        settings.flag("loop", "Repeat until ore runs out", true);
        settings.layout(this, UI.scale(10, 22), 2, UI.scale(175));
        pack();
    }

    private void arm() {
        if (draw == null)
            draw = new AreaDraw(gui, MY_AREA, PlaceRoles.SMELTING);
        draw.arm();
    }

    /** Pins the picker to a freshly drawn area, as the stockpile mover does. */
    @Override
    public void tick(double dt) {
        super.tick(dt);
        if ((draw != null) && (draw.tick() != null))
            settings.showPlace("area", MY_AREA);
    }

    @Override
    public void reqdestroy() {
        if (draw != null)
            draw.cancel();
        super.reqdestroy();
    }

    @Override
    protected String title() {
        return "Smelter";
    }

    // ------------------------------------------------------------------ the shift

    @Override
    protected Outcome work() throws InterruptedException {
        pileItem.clear();
        boxHeld.clear();
        dead.clear();
        refused.clear();
        warnedMeter = false;
        barsPiled = 0;
        pictured.clear();
        finished.clear();
        checkedState.clear();
        lastState.clear();

        if (settings.pinnedMissing("area"))
            return Outcome.failed("\"" + settings.place("area") + "\" no longer exists"
                + " - pick a smelting area again");
        area = pick();
        if (area == null)
            return Outcome.failed(Places.whyNothing(gui, PlaceRoles.SMELTING)
                + " - or press \"Draw smelting area\"");

        /* One bot to a yard. Two would each open the smelter the other is loading, and nothing
         * observable would tell them which of them had just put in the coal. */
        if (!Places.claim(area, true))
            return Outcome.blocked("another bot is already working " + area.name);
        try {
            return shift();
        } finally {
            Places.releaseClaim(area, true);
        }
    }

    private Outcome shift() throws InterruptedException {
        int batches = 0;
        for (int cycle = 0; running() && (cycle < MAX_CYCLES); cycle++) {
            new StowHand().run(ctx);
            Places.renewClaim(area, true);
            if (!upkeep())
                return Outcome.failed(fatalStop);
            Outcome t = goTo(area);
            if (!t.isOk())
                return t;

            List<Gob> all = smelters();
            if (all.isEmpty())
                return Outcome.failed("no ore smelters inside " + area.name);
            ctx.log("cycle " + cycle + ": " + all.size() + " smelter(s), " + idle(all).size() + " not burning");

            /* Emptying and loading are one visit, not two passes: every idle smelter is opened by
             * the fill anyway, so that is where its bars come out. The first run did them as
             * separate rounds and walked the whole row twice to find twelve empty smelters. */
            List<Spot> loaded = new ArrayList<>();
            if (settings.on("loop") || (batches == 0)) {
                loaded = fill(tour(idle(all)));
                returnOre();
                pileBars();
            } else {
                collect(tour(idle(all)));
            }
            if (!loaded.isEmpty()) {
                int lit = fireUp(loaded);
                batches++;
                report("lit " + lit + " of " + loaded.size() + " loaded smelter(s)");
            }

            if (burning(smelters()).isEmpty()) {
                if (loaded.isEmpty())
                    break;
                // Loaded and not one of them caught: another round would load nothing and fail the
                // same way, so say so now rather than two hundred cycles later.
                return Outcome.blocked("loaded " + loaded.size() + " smelter(s) but couldn't light any");
            }
            Outcome w = awaitBurnout();
            if (!w.isOk())
                return w;
        }

        pileBars();
        report("done - " + batches + " batch(es), " + barsPiled + " bar(s) stacked.");
        setStatus("Done: " + batches + " batch(es).");
        return Outcome.ok();
    }

    /** The pinned area, else the one we are standing in, else the nearest. */
    private Place pick() {
        Place pinned = settings.pinnedPlace("area");
        if (pinned != null)
            return pinned;
        Place p = Places.containing(gui, PlaceRoles.SMELTING);
        return (p != null) ? p : Places.nearest(gui, PlaceRoles.SMELTING);
    }

    /** The area for one job: its own role if one is tagged, the smelting area otherwise. */
    private Place place(String role) {
        Place p = Places.containing(gui, role);
        if (p == null)
            p = Places.nearest(gui, role);
        return (p != null) ? p : area;
    }

    private Outcome goTo(Place p) throws InterruptedException {
        reachOpenGround();
        Gob me = ctx.player();
        if ((me != null) && p.contains(gui, me.rc))
            return Outcome.ok();
        return new TravelTo(p).run(ctx);
    }

    // ------------------------------------------------------------------ smelters

    private List<Gob> smelters() {
        List<Gob> out = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                String r = Stockpile.resname(g);
                if ((r != null) && r.equals(SMELTER_RES) && area.contains(gui, g.rc))
                    out.add(g);
            }
        }
        return tour(out);
    }

    /** Not smelting: unlit, or lit but found finished. Ready to empty and load. */
    private List<Gob> idle(List<Gob> all) {
        List<Gob> out = new ArrayList<>();
        for (Gob g : all) {
            if (!busy(g))
                out.add(g);
        }
        return out;
    }

    /** Smelting: lit and not yet found finished. */
    private List<Gob> burning(List<Gob> all) {
        List<Gob> out = new ArrayList<>();
        for (Gob g : all) {
            if (busy(g))
                out.add(g);
        }
        return out;
    }

    /**
     * Smelting: the fire is lit AND the lava is running - bit 4 of the state byte.
     *
     * Read off the run logs: smelting shows 70 (64+4+2) or 7 (4+2+1) the moment it is lit; the
     * overfilled north-east smelter, finished with its spare coal still burning, showed 122
     * (64+32+16+8+2) - fire on, lava bit clear; finished and gone out showed 57 and 25. So the lava bit
     * alone separates "still smelting" from "done", with no need to open anything - the tenth run
     * opened every smelter again after lighting to find that out.
     *
     * A smelter lit in the last minute counts as busy whatever it shows, so a moment between the
     * fire catching and the lava starting cannot read as finished.
     */
    private boolean busy(Gob g) {
        if (!lit(g))
            return false;
        Long at = litAt.get(g.id);
        if ((at != null) && (System.currentTimeMillis() - at < 60_000))
            return true;
        return (state(g) & LAVA) != 0;
    }

    /** The first state byte, or -1. */
    private static int state(Gob g) {
        try {
            ResDrawable rd = (g == null) ? null : g.getattr(ResDrawable.class);
            return ((rd == null) || (rd.sdt == null)) ? -1 : rd.sdt.checkrbuf(0);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Whether a smelter is burning: bit 2 of its first state byte.
     *
     * nurgling2's {@code LightObject} gives the ore smelter a fire flag of 2 against its model
     * attribute, which is the state bytes read little-endian - so the flag lives in byte 0.
     * {@code checkrbuf} answers -1 for a missing byte, and -1 has every bit set, so it is
     * excluded rather than read as "on fire".
     */
    private static boolean lit(Gob g) {
        if (g == null)
            return false;
        try {
            ResDrawable rd = g.getattr(ResDrawable.class);
            if ((rd == null) || (rd.sdt == null))
                return false;
            int b = rd.sdt.checkrbuf(0);
            return (b != -1) && ((b & 2) != 0);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String sdt(Gob g) {
        try {
            ResDrawable rd = g.getattr(ResDrawable.class);
            return (rd == null || rd.sdt == null) ? "-" : Integer.toString(rd.sdt.checkrbuf(0));
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /**
     * Waits for every burning smelter to go out.
     *
     * Only carried water is drunk here. A trip to a barrel would take the smelters out of sight,
     * and a smelter that is not loaded reads as not burning - the wait would end on the walk.
     */
    private Outcome awaitBurnout() throws InterruptedException {
        long start = System.currentTimeMillis();
        for (int poll = 0; running(); poll++) {
            watchStates();
            List<Gob> burning = burning(smelters());
            if (burning.isEmpty()) {
                ctx.log("all smelters done after " + ((System.currentTimeMillis() - start) / 60000) + " min");
                return Outcome.ok();
            }
            long mins = (System.currentTimeMillis() - start) / 60000;
            if (System.currentTimeMillis() - start > BURN_LIMIT_MS)
                return Outcome.blocked(burning.size() + " smelter(s) still read as burning after " + mins + " min");
            setStatus("Smelting: " + burning.size() + " burning, " + mins + " min so far.");
            if (poll % 30 == 0) {
                Places.renewClaim(area, true);
                Drink.sipIfCarried(ctx);
                Upkeep.resume(ctx);
            }
            ctx.nav.pause(40);
        }
        throw new InterruptedException();
    }

    /**
     * Logs every change in a smelter's state byte, and looks inside any burning smelter in a state
     * it has not been checked in before. Finding no ore marks it {@link #finished}.
     *
     * Once per smelter per state, so a steady burn costs nothing: the look happens when the state
     * moves - the lava stopping is a change, whatever bit it turns out to be - and once for a smelter
     * first seen burning, since that one may have finished before we were watching.
     */
    /** Logs every change in a smelter's state byte - the record the lava bit was read from. */
    private void watchStates() {
        for (Gob g : smelters()) {
            int now = state(g);
            Integer was = lastState.put(g.id, now);
            if ((was != null) && (was != now))
                ctx.log("smelter #" + g.id + " state " + was + " -> " + now + (lit(g) ? " (fire on)" : " (fire out)")
                    + (busy(g) ? ", smelting" : ", done"));
        }
    }

    // ------------------------------------------------------------------ an open smelter

    /**
     * A smelter's window while it is open in front of us.
     *
     * Everything is re-read off the live widgets, as the pile handle does: the counts are what the
     * server last said, not what we think we sent.
     */
    private final class View {
        final Gob gob;
        final Window wnd;
        final Inventory inv;

        View(Gob gob, Window wnd, Inventory inv) {
            this.gob = gob;
            this.wnd = wnd;
            this.inv = inv;
        }

        List<WItem> pieces() {
            return NSmelterBot.pieces(inv);
        }

        int free() {
            return inv.getFreeSpace();
        }

        boolean alive() {
            return (wnd.parent != null) && (ctx.gob(gob.id) != null);
        }

        /**
         * WELL only if every piece of ore inside is well mined; PLAIN if any is not, or cannot be
         * read yet; null if there is no ore in it at all.
         *
         * An unreadable piece counts as ordinary because the two mistakes are not equal: three
         * coal too many is waste, three too few is a smelter that goes out with ore still in it.
         */
        Grade grade() {
            boolean any = false;
            for (WItem p : pieces()) {
                if (!isOre.test(basename(p)))
                    continue;
                any = true;
                if (!Boolean.TRUE.equals(wellMined(p)))
                    return Grade.PLAIN;
            }
            return any ? Grade.WELL : null;
        }

        /** Fuel units in the smelter, or -1 when the meter can't be found. */
        int fuel() {
            List<LayerMeter.Meter> all = new ArrayList<>();
            for (VMeter vm : meters(wnd))
                all.addAll(vm.meters());
            for (LayerMeter.Meter m : all) {
                if ((m.c != null) && (m.c.getRGB() == FUEL_COLOR.getRGB()))
                    return (int) Math.round(m.a * FUEL_UNITS);
            }
            if (!warnedMeter) {
                warnedMeter = true;
                StringBuilder sb = new StringBuilder();
                for (LayerMeter.Meter m : all)
                    sb.append(' ').append(String.format("%.2f@%06x", m.a, (m.c == null) ? 0 : (m.c.getRGB() & 0xffffff)));
                ctx.log("no orange fuel meter in the smelter window; meters:" + (sb.length() == 0 ? " none" : sb));
            }
            // A lone meter is the fuel whatever its colour - a smelter window has nothing else to meter.
            return (all.size() == 1) ? (int) Math.round(all.get(0).a * FUEL_UNITS) : -1;
        }

        void close() throws InterruptedException {
            NSmelterBot.this.close(wnd);
        }
    }

    private View view(Gob sm) throws InterruptedException {
        Window w = open(sm);
        if (w == null)
            return null;
        Inventory inv = Widgets.find(w, Inventory.class);
        if (inv == null) {
            close(w);
            return null;
        }
        settle(inv);
        ctx.log("smelter #" + sm.id + " open: sdt=" + sdt(sm) + ", " + pieces(inv).size() + " item(s), "
            + inv.getFreeSpace() + " free");
        return new View(sm, w, inv);
    }

    // ------------------------------------------------------------------ emptying

    /**
     * Takes the bars out of every idle smelter and drops the slag, stacking the bars whenever the
     * pack fills and once at the end. Only for the last round of a run that is not refilling -
     * otherwise {@link #visit} empties each smelter on the same visit that loads it.
     */
    private void collect(List<Gob> idle) throws InterruptedException {
        for (Gob sm : idle) {
            if (!running())
                throw new InterruptedException();
            for (int round = 0; round < 4; round++) {
                View v = view(sm);
                if (v == null)
                    break;
                boolean more;
                try {
                    dropSlag(v);
                    more = takeBars(v);
                } finally {
                    v.close();
                }
                if (!more)
                    break;
                // The pack filled with bars still inside. Stack what we have, then come back.
                pileBars();
                goTo(area);
            }
        }
        pileBars();
    }

    private void dropSlag(View v) throws InterruptedException {
        List<WItem> slag = matching(v.pieces(), SLAG::equals);
        if (slag.isEmpty())
            return;
        for (WItem p : slag)
            p.item.wdgmsg("drop", Coord.z, 1);
        ctx.nav.waitUntil(() -> !v.alive() || matching(v.pieces(), SLAG::equals).isEmpty(), MOVE_TICKS);
        ctx.log("dropped " + slag.size() + " slag from #" + v.gob.id);
    }

    /** @return true when bars are still inside because the pack is full. */
    private boolean takeBars(View v) throws InterruptedException {
        Predicate<String> isBar = b -> (b != null) && b.startsWith(BAR);
        List<WItem> bars = matching(v.pieces(), isBar);
        if (bars.isEmpty())
            return false;
        int before = bars.size();
        for (WItem p : bars)
            p.item.wdgmsg("transfer", Coord.z, 1);
        ctx.nav.waitUntil(() -> !v.alive() || matching(v.pieces(), isBar).isEmpty() || ctx.freeSpace() == 0, MOVE_TICKS);
        ctx.nav.pause(4);
        int left = v.alive() ? matching(v.pieces(), isBar).size() : 0;
        ctx.log("took " + (before - left) + " bar(s) from #" + v.gob.id + (left > 0 ? ", " + left + " left (pack full)" : ""));
        return left > 0;
    }

    // ------------------------------------------------------------------ bars into piles

    /** Puts every bar we carry into a bar pile, starting new ones when none will take them. */
    private void pileBars() throws InterruptedException {
        Set<String> kinds = new LinkedHashSet<>();
        for (WItem p : pieces(gui.maininv)) {
            String b = basename(p);
            if ((b != null) && b.startsWith(BAR))
                kinds.add(resname(p));
        }
        kinds.remove(null);
        if (kinds.isEmpty())
            return;
        Place bars = place(PlaceRoles.SMELT_BARS);
        if (!goTo(bars).isOk()) {
            reportError("couldn't get to " + bars.name + " to stack bars");
            return;
        }
        for (String res : kinds) {
            if (!running())
                throw new InterruptedException();
            setStatus("Stacking " + PileTransfer.shortName(res) + ".");
            int start = PileTransfer.carrying(ctx, res);
            Coord2d last = null;
            final String kind = base(res);
            for (Gob pile : tour(Stockpile.within(gui, bars))) {
                if (PileTransfer.carrying(ctx, res) <= 0)
                    break;
                // Bar piles only. Every other kind would refuse a bar - after a walk to find out.
                if (!BAR_PILE.equals(Stockpile.resname(pile)))
                    continue;
                if (refused.contains(pile.id + ":" + res) || isFull(pile) || dead.contains(pile.id)
                    || Stockpile.fullHint(pile))
                    continue;
                /* The same as putting ore back: open it (which proves we can reach it and says
                 * whether it has room), then one bar to hand and shift-click - the pile takes every
                 * bar in the pack of its kind. A pile of another metal takes none, and is not asked
                 * again for this kind. */
                final Stockpile.Open[] opened = {null};
                if (!fromSomeSide(pile, () -> (opened[0] = Stockpile.open(ctx, pile)) != null)) {
                    // Packed in: nothing will change that this shift, so it is not walked to again.
                    dead.add(pile.id);
                    continue;
                }
                Stockpile.Open o = opened[0];
                try {
                    logLook(pile, o);
                    if (o.free() <= 0) {
                        markFull(pile);
                        continue;
                    }
                    int before = PileTransfer.carrying(ctx, res);
                    if (!takeToHand(kind::equals))
                        break;
                    Stockpile.putAll(ctx, pile);
                    ctx.nav.waitUntil(() -> PileTransfer.carrying(ctx, res) < before - 1, MOVE_TICKS);
                    ctx.nav.pause(4);
                    if (PileTransfer.carrying(ctx, res) < before)
                        last = pile.rc;
                    else
                        refused.add(pile.id + ":" + res);
                } finally {
                    new StowHand().run(ctx);
                    o.close();
                }
            }
            for (int i = 0; (i < MAX_NEW_PILES) && (PileTransfer.carrying(ctx, res) > 0); i++) {
                MakePile mk = new MakePile(bars, res, BAR_PILE, last).only(this::outOfTheWay);
                Outcome o = mk.run(ctx);
                Gob made = mk.made();
                if (!o.isOk() || (made == null)) {
                    reportError("couldn't start a pile for " + PileTransfer.shortName(res)
                        + (o.reason == null ? "" : ": " + o.reason));
                    break;
                }
                pileItem.put(made.id, base(res));
                last = made.rc;
                /* Shift-placing fills the new pile from the pack as far as it goes, so bars left
                 * over mean it is full - no second fill needed, and PileTransfer's would walk to it
                 * by the ring of standing spots the rest of this bot no longer trusts. */
                if (PileTransfer.carrying(ctx, res) > 0)
                    markFull(made);
            }
            barsPiled += Math.max(0, start - PileTransfer.carrying(ctx, res));
        }
    }

    /**
     * Whether a new bar pile may stand here without getting in the way of the yard.
     *
     * Not within two tiles of a smelter - that ground is where the smelters are worked from, and
     * the north-east one has only a few units of it - and not in a lane: a square without open
     * ground on at least three of its four sides is a way through, and a pile in it closes it.
     */
    private boolean outOfTheWay(Coord2d at) {
        for (Gob sm : smelters()) {
            if (at.dist(sm.rc) < Reach.radius(sm) + 2 * 11)
                return false;
        }
        // Not in a gateway's way. The run that shut itself in had tried twice to start a pile beside one.
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (haven.automated.nbots.world.GateManager.isGate(g) && (g.rc.dist(at) < 11 * 2.5))
                    return false;
            }
        }
        int open = 0;
        double t = 11;
        Coord2d[] around = {at.add(t, 0), at.add(-t, 0), at.add(0, t), at.add(0, -t)};
        for (Coord2d p : around) {
            if (!haven.automated.nbots.world.BotNav.occupied(gui, p))
                open++;
        }
        if (open < 3)
            return false;
        /* And the one that matters: the pile must not cut any ground off - neither shut a pocket,
         * which is where the character stands to place it and was shut in, nor close a way through. */
        boolean seals = WorkSpot.cutsOff(gui, at, Stockpile.FOOTPRINT, WIDTHS[0]);
        if (seals)
            ctx.log("not piling at (" + (int) at.x + "," + (int) at.y + "): it would cut ground off");
        return !seals;
    }

    // ------------------------------------------------------------------ loading ore

    /** What the fill knows about one smelter between visits, so it is not reopened just to ask. */
    private static final class Spot {
        final Gob gob;
        /** Free slots, or -1 when it has not been read (or still holds bars to come back for). */
        int free = -1;
        /** The grade of the ore inside, null while there is none. */
        Grade grade;
        /** Fuel units on the last visit, -1 if the meter could not be read. */
        int fuel = -1;
        /** Could not be opened from any side this fill. */
        boolean dead;

        Spot(Gob gob) {
            this.gob = gob;
        }
    }

    /**
     * Fills the idle smelters with ore and returns the ones with ore in them, ready to fuel.
     *
     * Ore is carried out to the smelters rather than fetched for them. Each round hands out
     * everything the pack holds - a smelter takes the grade already inside it, an empty one takes
     * whichever grade we have most of - and only then goes back to the piles, for as much as the
     * smelters still open can take. The shape before this fetched for one smelter at a time, and in
     * the third run a smelter waiting on nine well-mined pieces sent the character to the piles ten
     * times running, back each time with ordinary ore it would not use and kept carrying.
     *
     * The first round is also the survey: every smelter is opened once, emptied of bars and slag,
     * loaded from whatever is already carried, and read. After that a smelter is only visited when
     * the pack holds something for it.
     */
    private List<Spot> fill(List<Gob> idle) throws InterruptedException {
        oreDry = false;
        boolean separate = settings.on("separate");
        List<Spot> spots = new ArrayList<>();
        for (Gob g : idle)
            spots.add(new Spot(g));
        unstack(isOre);
        int stalled = 0;
        for (int round = 0; running() && (round < FILL_ROUNDS); round++) {
            int loadedBefore = loadedTotal;
            for (Spot sp : tourSpots(spots)) {
                if (!running())
                    throw new InterruptedException();
                if (sp.dead || (sp.free == 0))
                    continue;
                Grade want = separate ? ((sp.grade != null) ? sp.grade : majority()) : null;
                if ((sp.free > 0) && (carriedFor(want, separate) == 0))
                    continue;   // read already, and nothing we carry belongs in it
                setStatus("Loading smelter #" + sp.gob.id + ".");
                visit(sp, want, separate);
            }
            List<Spot> open = new ArrayList<>();
            int room = 0;
            for (Spot sp : spots) {
                if (!sp.dead && (sp.free != 0)) {
                    open.add(sp);
                    room += (sp.free < 0) ? 25 : sp.free;
                }
            }
            if (open.isEmpty())
                break;
            /* A round after the first that loads nothing is a trip wasted, and they come in runs:
             * one part-filled smelter wanting well-mined ore sent a run to the piles fourteen times,
             * four ordinary pieces a trip, until the pack was full of ore nothing would take. */
            stalled = ((round > 0) && (loadedTotal == loadedBefore)) ? stalled + 1 : 0;
            if (stalled >= FILL_STALL) {
                ctx.log(FILL_STALL + " rounds loaded nothing - leaving " + open.size() + " smelter(s) part-filled");
                break;
            }
            boolean canMix = separate && settings.on("mix") && (carriedFor(null, false) > 0);
            if (oreDry) {
                // Nothing more is coming. Top up with the other kind if that is allowed.
                if (canMix) {
                    separate = false;
                    continue;
                }
                break;
            }
            if (count(IS_BAR) > 0) {
                /* Bars taken out of the smelters are stacked before any more ore is drawn. Carried
                 * through the fill, they took the room the ore needed to unstack in - the tenth run
                 * held 45 bars and could not load a smelter for want of space. */
                pileBars();
                goTo(area);
            }
            int pack = ctx.freeSpace();
            if (pack <= 0) {
                if (canMix) {
                    separate = false;
                    continue;
                }
                ctx.log("pack full of ore no open smelter will take - leaving " + open.size() + " part-filled");
                break;
            }
            /* As much as the pack holds once unstacked - every ore piece is a slot of its own
             * after unstack() - and no more than the open smelters can take. */
            int n = Math.min(room, pack - RESERVE);
            if (n <= 0) {
                if (canMix) {
                    separate = false;
                    continue;
                }
                ctx.log("pack full of ore no open smelter will take - leaving " + open.size() + " part-filled");
                break;
            }
            setStatus("Fetching " + n + " ore.");
            if (fetch(place(PlaceRoles.SMELT_ORE), isOre, n, ORE_PILES) <= 0) {
                oreDry = true;
                ctx.log("no more ore to fetch");
            } else {
                // A new piece's tooltip lands a moment after the piece; until then well mined reads as not.
                ctx.nav.pause(8);
            }
            goTo(area);
        }
        List<Spot> loaded = new ArrayList<>();
        for (Spot sp : spots) {
            if (!sp.dead && (sp.grade != null))
                loaded.add(sp);
        }
        return loaded;
    }

    /**
     * Puts whatever ore the fill could not place back into the ore piles.
     *
     * Ore of one grade with no smelter left that will take it - every open one already holds the
     * other - used to ride along to the next round, and the next, filling the pack a trip at a time
     * until there was no room to draw ore, coal or cat gold at all. The same gesture as filling a
     * pile by hand: one piece to the cursor, shift and right-click the pile, and it takes every
     * piece in the pack that belongs in it.
     */
    private void returnOre() throws InterruptedException {
        if (count(isOre) == 0)
            return;
        new StowHand().run(ctx);
        Place op = place(PlaceRoles.SMELT_ORE);
        if (!goTo(op).isOk())
            return;
        int start = count(isOre);
        setStatus("Putting " + start + " ore back.");
        for (Gob pile : sources(op, ORE_PILES)) {
            if (!running())
                throw new InterruptedException();
            if (count(isOre) == 0)
                break;
            if (!Stockpile.is(pile) || isFull(pile) || dead.contains(pile.id) || Stockpile.fullHint(pile))
                continue;
            /* Opened first, to read its free space. A full pile refuses the put, and read as a
             * failed attempt that sent the sixth run to ten different sides of one full pile, one
             * after the other, before it tried the next pile. Full is a fact about the pile: retire
             * it and move on. */
            final Stockpile.Open[] opened = {null};
            if (!fromSomeSide(pile, () -> (opened[0] = Stockpile.open(ctx, pile)) != null))
                continue;
            Stockpile.Open o = opened[0];
            try {
                logLook(pile, o);
                if (o.free() <= 0) {
                    markFull(pile);
                    continue;
                }
                int before = count(isOre);
                if (!takeToHand(isOre))
                    break;
                Stockpile.putAll(ctx, pile);
                ctx.nav.waitUntil(() -> count(isOre) < before - 1, MOVE_TICKS);
                ctx.nav.pause(4);
            } finally {
                // Whatever did not go stays in the pack; nothing may be walked about in the hand.
                new StowHand().run(ctx);
                o.close();
            }
        }
        ctx.log("put " + (start - count(isOre)) + " of " + start + " leftover ore back");
        goTo(area);
    }

    /** One visit: empty out what the last burn left, load what we carry for it, read it. */
    private void visit(Spot sp, Grade want, boolean separate) throws InterruptedException {
        View v = view(sp.gob);
        if (v == null) {
            sp.dead = true;
            return;
        }
        boolean barsLeft;
        try {
            dropSlag(v);
            barsLeft = takeBars(v);
            noteOre(v.pieces());
            Grade inside = v.grade();
            if (separate && (inside != null))
                want = inside;
            if ((v.free() > 0) && (!separate || (want != null))) {
                int moved = load(v, separate ? want : null);
                loadedTotal += moved;
                if (moved > 0)
                    finished.remove(sp.gob.id);
            }
            sp.free = v.free();
            sp.grade = v.grade();
            sp.fuel = v.fuel();
        } finally {
            v.close();
        }
        if (barsLeft) {
            // The pack filled with bars before the smelter emptied: stack them and come back.
            pileBars();
            goTo(area);
            sp.free = -1;
        }
    }

    private List<Spot> tourSpots(List<Spot> spots) {
        Map<Long, Spot> byId = new HashMap<>();
        List<Gob> gobs = new ArrayList<>();
        for (Spot sp : spots) {
            byId.put(sp.gob.id, sp);
            gobs.add(sp.gob);
        }
        List<Spot> out = new ArrayList<>();
        for (Gob g : tour(gobs))
            out.add(byId.get(g.id));
        return out;
    }

    /** Loose ore in the pack that would go into a smelter wanting {@code want}. */
    /**
     * Loose ore in the pack that would go into a smelter wanting {@code want}. Loose only: a
     * stacked piece cannot go in, and counting it sent the tenth run round every smelter in the
     * row, opening each to load nothing, with its stacks unable to unstack for lack of room.
     */
    private int carriedFor(Grade want, boolean separate) {
        int n = 0;
        for (WItem top : gui.maininv.getAllItems()) {
            if (members(top).size() != 1)
                continue;
            if (!isOre.test(basename(top)))
                continue;
            if (separate && (want != null) && (grade(top) != want))
                continue;
            n++;
        }
        return n;
    }

    /**
     * Sends ore of one grade (or any, when {@code want} is null) from the pack into an open smelter.
     *
     * Loose pieces only - a smelter will not take a stack (third run: every whole stack sent,
     * none arrived; every loose piece arrived), and {@link #unstack} has already broken them up at
     * the pile. Where every piece of a kind in the pack is the grade we want and they all fit, one
     * Ctrl+Shift+click sends the lot; otherwise the pieces are sent one message each. All of it is
     * fired together and waited on once - waiting after each slot is what made the third run
     * stand at every smelter for seconds.
     */
    private int load(View v, Grade want) throws InterruptedException {
        int start = oreIn(v);
        int room = v.free();
        Map<String, List<WItem>> byKind = new LinkedHashMap<>();
        for (WItem top : gui.maininv.getAllItems()) {
            if (members(top).size() > 1)
                continue;
            String b = basename(top);
            if (isOre.test(b))
                byKind.computeIfAbsent(b, k -> new ArrayList<>()).add(top);
        }
        int expect = 0, bulk = 0;
        for (List<WItem> all : byKind.values()) {
            if (room <= 0)
                break;
            List<WItem> ok = new ArrayList<>();
            for (WItem piece : all) {
                if ((want == null) || (grade(piece) == want))
                    ok.add(piece);
            }
            if (ok.isEmpty())
                continue;
            if ((ok.size() == all.size()) && (ok.size() <= room)) {
                ok.get(0).item.wdgmsg("transfer", Coord.z, -1);
                bulk++;
                room -= ok.size();
                expect += ok.size();
            } else {
                for (WItem piece : ok) {
                    if (room <= 0)
                        break;
                    piece.item.wdgmsg("transfer", Coord.z, 1);
                    room--;
                    expect++;
                }
            }
        }
        if (expect == 0)
            return 0;
        final int target = start + expect;
        ctx.nav.waitUntil(() -> !v.alive() || (oreIn(v) >= target) || (v.free() <= 0), MOVE_TICKS);
        ctx.nav.pause(3);
        int moved = v.alive() ? (oreIn(v) - start) : 0;
        ctx.log("loaded " + moved + " of " + expect + " " + (want == null ? "ore" : want + " ore") + " into #"
            + v.gob.id + " (" + bulk + " kind(s) in one go), " + (v.alive() ? v.free() : -1) + " free");
        return moved;
    }

    /**
     * Breaks up every stack of something in the pack - the game's Ctrl+Shift+Right-click on a stack.
     *
     * Done right after drawing from a pile, not in front of a smelter: a smelter will not take a
     * stack, and unstacking there left the character at an open window waiting for the pack to
     * rearrange itself between every batch. Only as many stacks as there is room to spread out.
     */
    private void unstack(Predicate<String> what) throws InterruptedException {
        for (int pass = 0; pass < 3; pass++) {
            List<WItem> stacks = new ArrayList<>();
            for (WItem top : gui.maininv.getAllItems()) {
                List<WItem> in = members(top);
                if ((in.size() > 1) && what.test(basename(in.get(0))))
                    stacks.add(top);
            }
            if (stacks.isEmpty())
                return;
            stacks.sort((a, b) -> Integer.compare(members(a).size(), members(b).size()));
            /* Measured once the pack has stopped changing. Read the instant a draw returned, the
             * pieces were still settling into their stacks, the pack read full, and eight draws in
             * one run left their ore stacked and unloadable. */
            settle(gui.maininv);
            int room = ctx.freeSpace();
            List<WItem> sent = new ArrayList<>();
            for (WItem st : stacks) {
                int size = members(st).size();
                if (room < size - 1)
                    continue;
                st.item.wdgmsg("iact", Coord.z, 3);
                room -= size - 1;
                sent.add(st);
            }
            if (sent.isEmpty()) {
                ctx.log("no room in the pack to unstack " + stacks.size() + " stack(s)");
                return;
            }
            ctx.nav.waitUntil(() -> {
                for (WItem st : sent) {
                    if (st.parent != null)
                        return false;
                }
                return true;
            }, MOVE_TICKS);
            ctx.nav.pause(2);
        }
    }

    private int oreIn(View v) {
        return matching(v.pieces(), isOre).size();
    }

    /** The real items in one inventory slot: a stack's members, or the item itself. */
    private static List<WItem> members(WItem top) {
        Widget c = (top.item == null) ? null : top.item.contents;
        if (c instanceof ItemStack) {
            try {
                List<WItem> out = new ArrayList<>();
                for (GItem g : new ArrayList<>(((ItemStack) c).order)) {
                    WItem m = ((ItemStack) c).wmap.get(g);
                    if (m != null)
                        out.add(m);
                }
                if (!out.isEmpty())
                    return out;
            } catch (RuntimeException e) {
                // Changed while copied; treat as the wrapper alone this once.
            }
        }
        return Collections.singletonList(top);
    }

    private Grade majority() {
        int well = 0, plain = 0;
        for (WItem p : pieces(gui.maininv)) {
            if (!isOre.test(basename(p)))
                continue;
            Boolean w = wellMined(p);
            if (w == null)
                continue;
            if (w)
                well++;
            else
                plain++;
        }
        if (well + plain == 0)
            return null;
        return (well > plain) ? Grade.WELL : Grade.PLAIN;
    }

    private int carried(Grade g) {
        int n = 0;
        for (WItem p : pieces(gui.maininv)) {
            if (isOre.test(basename(p)) && (grade(p) == g))
                n++;
        }
        return n;
    }

    /** A piece's grade, or null while its tooltip is still loading. */
    private static Grade grade(WItem p) {
        Boolean w = wellMined(p);
        return (w == null) ? null : (w ? Grade.WELL : Grade.PLAIN);
    }

    private static Boolean wellMined(WItem p) {
        try {
            for (ItemInfo i : p.item.info()) {
                if ((i instanceof ItemInfo.AdHoc) && WELL_MINED.equals(((ItemInfo.AdHoc) i).str.text))
                    return true;
                // nurgling2 builds it as its own tip class; harmless to recognise that too.
                if ("WellMined".equals(i.getClass().getSimpleName()))
                    return true;
            }
            return false;
        } catch (Loading l) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ fuel and fire

    /**
     * Fuels and lights the loaded smelters in one visit each, and says how many are burning.
     *
     * Everything that has to be fetched is fetched first - coal for all of them, and cat gold or
     * branches for an igniter each - so the round of the row is one walk, and each stop adds the
     * coal and lights the smelter there and then. The shape before this made three rounds (read the
     * fuel, add coal, light), each one walking the whole row; the fuel reading now comes from the
     * fill's own last visit.
     */
    /** Smelter id -> fuel short of its figure after the coal round. Those are not lit. */
    private final Map<Long, String> shortOfFuel = new HashMap<>();

    private int fireUp(List<Spot> loaded) throws InterruptedException {
        List<Gob> gobs = new ArrayList<>();
        for (Spot sp : loaded)
            gobs.add(sp.gob);
        fuelAll(loaded);
        return lightAll(gobs);
    }

    /**
     * The coal round: every loaded smelter topped up to its grade's figure, in walking order.
     *
     * Coal is left stacked. Nothing about feeding a smelter needs it loose - the Add Coal
     * scripts take a piece straight out of a stack and the server keeps the hand filled from
     * whatever is in the pack - and unstacking it is what let the sixth run's coal take every
     * slot in the pack, leaving no room for a single piece of cat gold.
     */
    private void fuelAll(List<Spot> loaded) throws InterruptedException {
        shortOfFuel.clear();
        Map<Long, Integer> need = new HashMap<>();
        int coal = 0;
        List<Gob> gobs = new ArrayList<>();
        Map<Long, Integer> targets = new HashMap<>();
        for (Spot sp : loaded) {
            int target = (sp.grade == Grade.WELL) ? settings.num("coal_wm") : settings.num("coal");
            int n = Math.max(0, target - Math.max(0, sp.fuel));
            ctx.log("#" + sp.gob.id + ": " + sp.grade + " ore, fuel " + sp.fuel + " of " + target + " -> add " + n);
            targets.put(sp.gob.id, target);
            if (n > 0) {
                need.put(sp.gob.id, n);
                coal += n;
                gobs.add(sp.gob);
            }
        }
        if (coal == 0)
            return;
        stockCoal(coal);
        goTo(area);
        for (Gob g : tour(gobs)) {
            if (!running())
                throw new InterruptedException();
            Gob sm = ctx.gob(g.id);
            if (sm == null)
                continue;
            int n = need.get(sm.id);
            if (count(IS_COAL) < n) {
                stockCoal(coal);
                goTo(area);
            }
            if (!fromSomeSide(sm, clearLine(sm))) {
                reportError("couldn't get to smelter #" + sm.id + " to fuel it");
                continue;
            }
            setStatus("Fuelling smelter #" + sm.id + ".");
            int target = targets.get(sm.id);
            int fuel = addCoal(sm, target, target - n);
            coal -= n;
            ctx.log("#" + sm.id + ": fuel now " + fuel + " of " + target);
            if ((fuel >= 0) && (fuel < target)) {
                String why = (count(IS_COAL) == 0 ? "ran out of coal" : "it stopped taking it");
                shortOfFuel.put(sm.id, fuel + " of " + target + " coal");
                reportError("smelter #" + sm.id + " has " + fuel + " of " + target + " coal - " + why
                    + "; not lighting it");
            }
        }
    }

    /**
     * The fire round: an igniter's makings for every smelter fetched in one go, then each one lit
     * in walking order - the spark crafted standing at it, since a spark is used up by the lighting.
     */
    private int lightAll(List<Gob> gobs) throws InterruptedException {
        int unlit = 0;
        for (Gob g : gobs) {
            if (!lit(ctx.gob(g.id)))
                unlit++;
        }
        Recipe r = (unlit > 0) ? igniter(unlit) : null;
        goTo(area);
        int burning = 0, failures = 0;
        for (Gob g : tour(gobs)) {
            if (!running())
                throw new InterruptedException();
            Gob sm = ctx.gob(g.id);
            if (sm == null)
                continue;
            if (lit(sm)) {
                burning++;
                continue;
            }
            /* Lit on too little coal, a batch stops half smelted and the ore in it is stuck there
             * until someone notices - the last run lit one on 1 coal of 9 after the coal ran out. */
            if (shortOfFuel.containsKey(sm.id)) {
                ctx.log("not lighting #" + sm.id + ": only " + shortOfFuel.get(sm.id));
                unlit--;
                continue;
            }
            if ((r == null) || (count(r.ingredient) < r.per)) {
                r = igniter(Math.max(1, unlit));
                goTo(area);
            }
            if (r == null) {
                reportError("nothing to light the smelters with - see the log");
                break;
            }
            if (!fromSomeSide(sm, clearLine(sm))) {
                reportError("couldn't get to smelter #" + sm.id + " to light it");
                continue;
            }
            setStatus("Lighting smelter #" + sm.id + ".");
            Outcome o = light(sm, r);
            if (!o.isOk() && (count(r.ingredient) < r.per)) {
                /* Short of makings. Fetch more - or fall back to branches - and try this smelter
                 * again, rather than walking on to the next with the same empty pack. */
                r = igniter(Math.max(1, unlit));
                goTo(area);
                if (r == null) {
                    reportError("ran out of cat gold and branches - lit " + burning + ", "
                        + unlit + " still unlit");
                    break;
                }
                if (fromSomeSide(sm, clearLine(sm)))
                    o = light(sm, r);
            }
            unlit--;
            if (o.isOk()) {
                burning++;
                failures = 0;
                continue;
            }
            reportError("smelter #" + sm.id + " not lit: " + o.reason);
            /* Two in a row that failed with the makings in the pack: something else is wrong, and
             * it will be wrong at every smelter left. Stop and say so. */
            if (++failures >= 2) {
                reportError("stopping lighting after two failures in a row - " + burning + " burning, "
                    + unlit + " still unlit");
                break;
            }
        }
        return burning;
    }

    /**
     * Brings the coal carried up to {@code n}, or as near as the pack holds. Left in stacks, so a
     * pack holds a great deal of it; {@link #fuelAll} comes back for more if it runs out.
     */
    private void stockCoal(int n) throws InterruptedException {
        int want = n - count(IS_COAL);
        if (want <= 0)
            return;
        setStatus("Fetching " + want + " coal.");
        fetch(place(PlaceRoles.SMELT_FUEL), IS_COAL, want, COAL_PILES, false);
    }

    /**
     * Brings a smelter's fuel up to {@code target}, and says what the meter reads afterwards
     * (-1 if it cannot be read). The caller is standing at it.
     *
     * The game's own way: pick up a stack, and every plain right-click on the smelter puts one piece
     * of it in until the stack is gone. So the clicks are the count - one per coal owed, never more
     * than the stack on the cursor holds, the rest put back - and they go at a steady pace rather
     * than waiting on the cursor after each one: a stack held on the cursor shows nothing changing
     * as it shrinks, so every such wait ran its full second ("1-2-34", James).
     *
     * Then the smelter is opened and its fuel meter read, which is the one figure that cannot be
     * wrong. Counting the PACK instead, the eighth run's way, read a stack put back in as a single
     * piece for a moment and reported "added 10 of 9" and "9 of 7". A click that did not land shows
     * as a shortfall on the meter and is made up on the next round; there is never more on the
     * cursor, or more clicks, than the meter says is owed, so the smelter cannot be overfilled.
     */
    private int addCoal(Gob sm, int target, int fuel) throws InterruptedException {
        new StowHand().run(ctx);
        for (int round = 0; (round < 3) && running(); round++) {
            int owed = target - Math.max(0, fuel);
            if (owed <= 0)
                return fuel;
            while ((owed > 0) && running()) {
                int size = takeCoal(owed);
                if (size <= 0)
                    break;
                int clicks = Math.min(size, owed);
                for (int c = 0; (c < clicks) && (gui.vhand != null); c++) {
                    itemact(sm, 0);
                    ctx.nav.pause(COAL_GAP);
                }
                owed -= clicks;
                new StowHand().run(ctx);
            }
            /* The meter lags the clicks. Read the moment after the last one, it showed less than had
             * gone in, the difference was put in again, and smelters came out at 12 of 9 and 16 of
             * 12. So: wait, open, and read until two readings agree. */
            ctx.nav.pause(METER_SETTLE);
            View v = view(sm);
            if (v == null)
                return -1;
            try {
                fuel = settledFuel(v);
            } finally {
                v.close();
            }
            if (fuel < 0)
                return -1;      // no meter to check against: the clicks were the count
            if (count(IS_COAL) == 0)
                return fuel;
        }
        return fuel;
    }

    /**
     * Picks up the coal to feed with and says how many pieces it holds, read in the pack before
     * picking it up. The biggest stack that fits in {@code owed}, else the smallest there is - only
     * {@code owed} clicks are made with it either way, and the rest goes back. 0 if there is none.
     */
    private int takeCoal(int owed) throws InterruptedException {
        WItem best = null;
        int size = 0;
        for (WItem top : gui.maininv.getAllItems()) {
            int k = members(top).size();
            if (!IS_COAL.test(basename(members(top).get(0))))
                continue;
            boolean fits = k <= owed, bestFits = (best != null) && (size <= owed);
            if ((best == null)
                || (fits && (!bestFits || (k > size)))
                || (!fits && !bestFits && (k < size))) {
                best = top;
                size = k;
            }
        }
        if (best == null)
            return 0;
        best.item.wdgmsg("take", Coord.z);
        ctx.nav.waitUntil(() -> gui.vhand != null, HAND_TICKS);
        return (gui.vhand == null) ? 0 : size;
    }

    /** A recipe that can be made: its menu entry, what it is made from, and how much of it. */
    private static final class Recipe {
        final MenuGrid.Pagina pag;
        final Predicate<String> ingredient;
        /** How many of the ingredient one craft takes. Corrected from the crafting window. */
        int per;

        Recipe(MenuGrid.Pagina pag, Predicate<String> ingredient, int per) {
            this.pag = pag;
            this.ingredient = ingredient;
            this.per = per;
        }
    }

    /**
     * Gathers the makings of igniters for {@code smelters} smelters - cat gold for a pyrite spark,
     * else branches for a firebrand - and says which recipe to use. Null if neither can be made.
     *
     * Walks, so it must run with an empty hand; the craft itself waits until we are standing at the
     * smelter ({@link #light}), because a map click made while holding something drops it.
     */
    private Recipe igniter(int smelters) throws InterruptedException {
        String why = "no spark recipe set";
        String spark = settings.str("spark").trim();
        if (!spark.isEmpty()) {
            int per = uses(spark, settings.num("catgold"));
            Outcome o = gather(spark, IS_CATGOLD, per * smelters, "cat gold", CATGOLD_PILES);
            // Pieces that have just arrived cannot be named for a moment; count them once they can.
            final int wantCat = per * smelters;
            ctx.nav.waitUntil(() -> count(IS_CATGOLD) >= wantCat, OPEN_TICKS);
            if (o.isOk() || ((pagina(spark) != null) && (count(IS_CATGOLD) >= per))) {
                enoughFor(count(IS_CATGOLD) / per, smelters, "cat gold", "sparks");
                return new Recipe(pagina(spark), IS_CATGOLD, per);
            }
            why = o.reason;
        }
        for (String name : FIREBRAND_RECIPES) {
            int per = uses(name, settings.num("branches"));
            Outcome f = gather(name, IS_BRANCH, per * smelters, "branches", BRANCH_PILES);
            final int wantBr = per * smelters;
            ctx.nav.waitUntil(() -> count(IS_BRANCH) >= wantBr, OPEN_TICKS);
            if (f.isOk() || ((pagina(name) != null) && (count(IS_BRANCH) >= per))) {
                enoughFor(count(IS_BRANCH) / per, smelters, "branches", "firebrands");
                return new Recipe(pagina(name), IS_BRANCH, per);
            }
            why += "; " + f.reason;
            if (!f.isFailed())
                break;      // the recipe exists but we are short of branches - the other name won't help
        }
        ctx.log("no igniter: " + why);
        return null;
    }

    /** What one craft of a recipe takes: learned from its window if it has been open, else the setting. */
    private int uses(String recipe, int setting) {
        MenuGrid.Pagina pag = pagina(recipe);
        Integer seen = null;
        try {
            seen = (pag == null) ? null : recipeUses.get(pag.button().name());
        } catch (RuntimeException e) {
            // Button not loaded yet: fall back to the setting.
        }
        return Math.max(1, (seen != null) ? seen : setting);
    }

    /** Says so when what was gathered will not light every smelter. */
    private void enoughFor(int crafts, int smelters, String what, String igniters) {
        if (crafts >= smelters)
            return;
        String msg = "only enough " + what + " for " + crafts + " of " + smelters + " " + igniters;
        ctx.log(msg);
        report(msg + " - the rest will need more fetched, or branches");
    }

    /** Crafts an igniter where we stand and puts it to the smelter. */
    private Outcome light(Gob sm, Recipe r) throws InterruptedException {
        new StowHand().run(ctx);
        Outcome made = make(r);
        if (!made.isOk()) {
            ctx.log("lighting #" + sm.id + ": " + made.reason);
            return made;
        }
        String spark = basename(gui.vhand);
        for (int tryNo = 0; tryNo < 3; tryNo++) {
            if (tryNo > 0) {
                /* Didn't catch from there - the click's walk met something. Put the spark away (a
                 * map click with it on the cursor would drop it), stand somewhere else with a clear
                 * line, and use the same spark: a spark is only used up by lighting something. */
                new StowHand().run(ctx);
                workedFrom.remove(sm.id);
                Gob me = ctx.player();
                final Coord2d failed = (me == null) ? null : me.rc;
                Attempt elsewhere = () -> {
                    Gob now = ctx.player();
                    return (now != null) && ((failed == null) || (now.rc.dist(failed) > ON_SPOT))
                        && WorkSpot.lineTo(gui, sm, now.rc, standingWidth);
                };
                if (!fromSomeSide(sm, elsewhere) || (spark == null) || !takeToHand(spark::equals))
                    break;
            }
            itemact(sm, 0);
            ctx.nav.waitUntil(() -> lit(ctx.gob(sm.id)), LIGHT_TICKS);
            if (lit(ctx.gob(sm.id))) {
                litAt.put(sm.id, System.currentTimeMillis());
                ctx.log("lighting #" + sm.id + ": burning, sdt=" + sdt(sm));
                new StowHand().run(ctx);
                return Outcome.ok();
            }
            ctx.log("lighting #" + sm.id + ": did not catch from here, sdt=" + sdt(sm));
        }
        new StowHand().run(ctx);
        return Outcome.blocked("it didn't catch from any side");
    }

    /**
     * Makes sure a recipe is known and its ingredient is in the pack, fetching it if not.
     *
     * FAILED means the recipe is not in the crafting menu at all; BLOCKED means it is, but there is
     * not enough to make it from.
     */
    private Outcome gather(String recipe, Predicate<String> ingredient, int per, String what,
                           Set<String> pileKinds) throws InterruptedException {
        if (pagina(recipe) == null)
            return Outcome.failed("no '" + recipe + "' in the crafting menu");
        if (count(ingredient) < per) {
            setStatus("Fetching " + what + ".");
            fetch(place(PlaceRoles.SMELT_IGNITE), ingredient, per - count(ingredient), pileKinds, false);
        }
        if (count(ingredient) < per)
            return Outcome.blocked("not enough " + what + " for " + recipe);
        return Outcome.ok();
    }

    /**
     * Crafts one igniter and leaves it on the cursor. Never walks.
     *
     * The product is found by what is NEW - on the cursor, or in the pack - rather than by name,
     * since neither the spark's item name nor where the craft puts it is something this was
     * written knowing.
     */
    private Outcome make(Recipe r) throws InterruptedException {
        Set<GItem> before = new HashSet<>();
        for (WItem p : pieces(gui.maininv))
            before.add(p.item);
        if (gui.vhand != null)
            before.add(gui.vhand.item);

        String name = r.pag.button().name();
        /* The window already open on this recipe is used as it is. Pressing the recipe button
         * again has the game REPLACE the window, and a wait for "a window on this recipe" was
         * satisfied at once by the old one - so the craft went to a window already on its way out,
         * and every spark after the first "produced nothing" (eighth run). When the button has to
         * be pressed, the wait is for a window that was not there before. */
        Makewindow mw = makewnd();
        if ((mw == null) || (mw.parent == null) || !name.equalsIgnoreCase(mw.rcpnm)) {
            final Makewindow old = mw;
            r.pag.button().use(new MenuGrid.Interaction(1, 0));
            ctx.nav.waitUntil(() -> {
                Makewindow now = makewnd();
                return (now != null) && (now != old) && name.equalsIgnoreCase(now.rcpnm);
            }, OPEN_TICKS);
            mw = makewnd();
            if ((mw == null) || (mw == old) || !name.equalsIgnoreCase(mw.rcpnm))
                return Outcome.blocked("the crafting window didn't open on " + name);
        }
        /* What the craft takes, from the window itself - the settings are only a guess, and a guess
         * of one cat gold per spark with one piece left had the craft fail six times over. */
        int takes = ingredientCount(mw, r.ingredient);
        if (takes > 0) {
            recipeUses.put(name, takes);
            r.per = takes;
        }
        String inputs = describeInputs(mw);
        if (!inputs.startsWith("(") && recipeLogged.add(name))
            ctx.log(name + " takes: " + inputs);
        int have = count(r.ingredient);
        if (have < r.per)
            return Outcome.blocked("not enough to craft " + name + ": it takes " + r.per + ", we have " + have);
        mw.wdgmsg("make", 0);
        ctx.nav.waitUntil(() -> ctx.onProgress() || (fresh(before, r.ingredient) != null), OPEN_TICKS);
        ctx.nav.waitUntil(() -> !ctx.onProgress(), 400);
        ctx.nav.waitUntil(() -> fresh(before, r.ingredient) != null, OPEN_TICKS);
        WItem made = fresh(before, r.ingredient);
        if (made == null)
            return Outcome.blocked("the " + name + " craft produced nothing");
        if (gui.vhand != made) {
            made.item.wdgmsg("take", Coord.z);
            ctx.nav.waitUntil(() -> gui.vhand != null, HAND_TICKS);
        }
        ctx.log("crafted " + name + ": " + basename(gui.vhand));
        return (gui.vhand != null) ? Outcome.ok() : Outcome.blocked("couldn't pick up the " + name);
    }

    /** How many of the ingredient the open recipe takes, or 0 if the window does not say yet. */
    private static int ingredientCount(Makewindow mw, Predicate<String> ingredient) {
        try {
            for (Makewindow.Input in : mw.inputs) {
                Resource r = in.spec.item.res.get();
                if ((r != null) && ingredient.test(r.basename()))
                    return in.spec.num;
            }
        } catch (RuntimeException e) {
            // Includes Loading: the inputs arrive a moment after the window.
        }
        return 0;
    }

    /** The recipe's inputs as "name x N", for the log. */
    private static String describeInputs(Makewindow mw) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Makewindow.Input in : mw.inputs) {
                Resource r = in.spec.item.res.get();
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append((r == null) ? "?" : r.basename()).append(" x").append(in.spec.num);
            }
        } catch (RuntimeException e) {
            sb.append("(not loaded)");
        }
        return (sb.length() == 0) ? "(nothing listed)" : sb.toString();
    }

    /**
     * Whatever appeared since the snapshot: on the cursor first, then in the pack.
     *
     * Leftover ingredients are not a product even when they are new widgets. Using one piece out
     * of a stack of two leaves a single item where the stack was, and the server sends that as a
     * fresh item - which would otherwise be taken to hand and rubbed on the smelter.
     */
    private WItem fresh(Set<GItem> before, Predicate<String> ingredient) {
        WItem h = gui.vhand;
        if ((h != null) && !before.contains(h.item))
            return h;
        for (WItem p : pieces(gui.maininv)) {
            if (!before.contains(p.item) && !ingredient.test(basename(p)))
                return p;
        }
        return null;
    }

    private Makewindow makewnd() {
        return (gui.makewnd == null) ? null : gui.makewnd.makeWidget;
    }

    /** The menu entry by exact name, else the first whose name contains it. */
    private MenuGrid.Pagina pagina(String name) {
        String want = name.trim().toLowerCase();
        MenuGrid.Pagina loose = null;
        synchronized (gui.menu.paginae) {
            for (MenuGrid.Pagina pag : gui.menu.paginae) {
                try {
                    String n = pag.button().name();
                    if (n == null)
                        continue;
                    if (n.equalsIgnoreCase(want))
                        return pag;
                    if ((loose == null) && n.toLowerCase().contains(want))
                        loose = pag;
                } catch (RuntimeException e) {
                    // Includes Loading: the button resource hasn't arrived, so it can't be this one.
                }
            }
        }
        return loose;
    }

    // ------------------------------------------------------------------ supplies

    /**
     * Brings up to {@code n} pieces of something into the pack from the piles and containers in a
     * place, and says how many arrived.
     *
     * A pile or container is opened once to learn what it holds and never opened again for
     * anything else this shift, which is what lets one undifferentiated area serve as ore store,
     * coal store and tinder box at once.
     */
    private int fetch(Place p, Predicate<String> want, int n, Set<String> pileKinds)
            throws InterruptedException {
        return fetch(p, want, n, pileKinds, true);
    }

    /**
     * @param loose whether what arrives is to be unstacked. Ore has to be - a smelter will not take
     *              a stack - so its draws are sized by free slots. Coal and cat gold need not be.
     */
    private int fetch(Place p, Predicate<String> want, int n, Set<String> pileKinds, boolean loose)
            throws InterruptedException {
        if (!goTo(p).isOk())
            return 0;
        /* Counted by what each source says it handed over, not by counting the pack. A piece that
         * has only just arrived cannot be told apart yet - its resource is still loading - so the
         * pack under-counted, the shortfall was drawn again, and the fifth run fetched 62 ore into
         * room for far fewer: "no room in the pack to unstack 30 stack(s)", every smelter after
         * that opened and left empty, and no room at all for coal or cat gold.
         *
         * And unstacked after EACH source, so the free-slot count the next draw is sized by is the
         * real one: every piece takes a slot of its own once unstacked. */
        int got = 0;
        for (Gob src : sources(p, pileKinds)) {
            if (!running())
                throw new InterruptedException();
            int need = loose ? Math.min(n - got, ctx.freeSpace()) : (n - got);
            if (need <= 0)
                break;
            int moved = Stockpile.is(src) ? fromPile(src, want, need) : fromBox(src, want, need);
            got += moved;
            if ((moved > 0) && loose)
                unstack(want);
            // Nothing came and nowhere to put it: the pack is full, not the source empty.
            if ((moved == 0) && (ctx.freeSpace() <= 0))
                break;
        }
        ctx.log("fetched " + got + " from " + p.name);
        return got;
    }

    private boolean isFull(Gob pile) {
        Long at = fullPiles.get(pile.id);
        return (at != null) && (System.currentTimeMillis() - at < FULL_RECHECK_MS);
    }

    /**
     * A pile's look against what it holds. A pile's model grows with its contents, and its state
     * value is that look; {@link Stockpile#fullHint} learns the value a full pile shows and piles
     * that look it are passed by without a walk. Logged so the whole scale can be read off later.
     */
    private void logLook(Gob pile, Stockpile.Open o) {
        try {
            ctx.log("pile #" + pile.id + " (" + Stockpile.kind(pile) + ") looks " + pile.sdt() + ", holds "
                + o.count() + " of " + o.capacity());
        } catch (RuntimeException e) {
            // The window went while we read it; nothing to learn this time.
        }
    }

    private void markFull(Gob pile) {
        fullPiles.put(pile.id, System.currentTimeMillis());
        Stockpile.retire(pile);
        ctx.log("pile #" + pile.id + " (" + Stockpile.kind(pile) + ") is full - not coming back to it for an hour");
    }

    /** Piles of the right kinds, and every container, in a place - in the order to walk them. */
    private List<Gob> sources(Place p, Set<String> pileKinds) {
        List<Gob> out = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (dead.contains(g.id) || !p.contains(gui, g.rc))
                    continue;
                String r = Stockpile.resname(g);
                if (r == null)
                    continue;
                if (Stockpile.is(g) ? pileKinds.contains(Stockpile.kind(g))
                                    : (CONTAINERS.matchesPart(r) && !r.contains("smelter")))
                    out.add(g);
            }
        }
        return tour(out);
    }

    private int fromPile(Gob pile, Predicate<String> want, int need) throws InterruptedException {
        String held = pileItem.get(pile.id);
        if ((held != null) && !want.test(held))
            return 0;
        final Stockpile.Open[] opened = {null};
        if (!fromSomeSide(pile, () -> (opened[0] = Stockpile.open(ctx, pile)) != null)) {
            dead.add(pile.id);
            return 0;
        }
        Stockpile.Open o = opened[0];
        try {
            String res = o.awaitItem();
            if (res == null) {
                ctx.log("pile #" + pile.id + " (" + Stockpile.kind(pile) + ") opened but never said what it holds");
                return 0;
            }
            String b = base(res);
            String kind = Stockpile.kind(pile);
            int got = 0;
            if (MIXED_PILES.contains(kind) && !want.test(b)) {
                /* The window names the kind of pile, not what is in it. Take one piece out and
                 * look: the sixth run read a pile of cat gold as "stone - not wanted" and walked
                 * away from the only cat gold in the yard. */
                String real = probe(o, want);
                if (real == null) {
                    ctx.log("pile #" + pile.id + " (" + kind + ") gave nothing to look at");
                    return 0;
                }
                pileItem.put(pile.id, real);
                if (!want.test(real)) {
                    ctx.log("pile #" + pile.id + " (" + kind + ") holds " + real + " - not wanted");
                    return 0;
                }
                b = real;
                got = 1;
            } else {
                pileItem.put(pile.id, b);
            }
            // An ore pile is the authority on what ore is. Cat gold is the one mineral that might
            // share it and must never go in a smelter.
            if ("ore".equals(kind) && !CATGOLD.equals(b) && learnedOre.add(b))
                ctx.log("learned ore: " + b);
            if (need > got)
                got += o.draw(Math.min(need - got, o.count()));
            ctx.log("drew " + got + " " + b + " from pile #" + pile.id);
            return got;
        } finally {
            o.close();
        }
    }

    /**
     * Takes one piece out of an open pile and says what it is, putting it back if it is not
     * {@code want}. Null if nothing came out or it could not be read in time.
     */
    private String probe(Stockpile.Open o, Predicate<String> want) throws InterruptedException {
        Set<GItem> before = new HashSet<>();
        for (WItem p : pieces(gui.maininv))
            before.add(p.item);
        if (o.draw(1) <= 0)
            return null;
        // The piece arrives before its resource does; wait until it can be named.
        final WItem[] seen = {null};
        ctx.nav.waitUntil(() -> {
            for (WItem p : pieces(gui.maininv)) {
                if (!before.contains(p.item) && (basename(p) != null)) {
                    seen[0] = p;
                    return true;
                }
            }
            return false;
        }, OPEN_TICKS);
        if (seen[0] == null)
            return null;
        String b = basename(seen[0]);
        if (!want.test(b) && o.alive())
            o.stow(1);
        return b;
    }

    private int fromBox(Gob box, Predicate<String> want, int need) throws InterruptedException {
        Set<String> held = boxHeld.get(box.id);
        if (held != null) {
            boolean any = false;
            for (String b : held)
                any |= want.test(b);
            if (!any)
                return 0;
        }
        Window w = open(box);
        if (w == null) {
            dead.add(box.id);
            return 0;
        }
        try {
            Inventory inv = Widgets.find(w, Inventory.class);
            if (inv == null)
                return 0;
            settle(inv);
            List<WItem> all = pieces(inv);
            noteOre(all);
            Set<String> seen = new HashSet<>();
            for (WItem p : all)
                seen.add(basename(p));
            seen.remove(null);
            boxHeld.put(box.id, seen);
            List<WItem> take = matching(all, want);
            int n = Math.min(need, take.size());
            if (n <= 0)
                return 0;
            // Counted by what LEFT the container - it is already loaded, so it can be counted.
            int inBox = take.size();
            for (int i = 0; i < n; i++) {
                take.get(i).item.wdgmsg("transfer", Coord.z, 1);
                ctx.nav.pause(1);
            }
            ctx.nav.waitUntil(() -> matching(pieces(inv), want).size() <= inBox - n, MOVE_TICKS);
            ctx.nav.pause(4);
            int moved = inBox - matching(pieces(inv), want).size();
            ctx.log("took " + moved + " from container #" + box.id);
            return moved;
        } finally {
            close(w);
        }
    }

    // ------------------------------------------------------------------ containers

    /**
     * Walks to a container, right-clicks it and returns the window that came up.
     *
     * The window is found as the one that was not there before the click, not by its caption:
     * a yard of identical chests all open windows with the same name.
     */
    private Window open(Gob g) throws InterruptedException {
        if ((g == null) || (ctx.gob(g.id) == null))
            return null;
        final Window[] got = {null};
        if (fromSomeSide(g, () -> (got[0] = click(g)) != null))
            return got[0];
        ctx.log("#" + g.id + " (" + Stockpile.resname(g) + ") didn't open from any side");
        return null;
    }

    /** Right-clicks a container and waits for a window that was not there before. */
    private Window click(Gob g) throws InterruptedException {
        Set<Window> before = new HashSet<>(containerWindows());
        Coord at = g.rc.floor(posres);
        gui.map.wdgmsg("click", Coord.z, at, 3, 0, 0, (int) g.id, at, 0, -1);
        ctx.nav.waitUntil(() -> newWindow(before) != null, OPEN_TICKS);
        return newWindow(before);
    }

    private interface Attempt {
        boolean run() throws InterruptedException;
    }

    /**
     * Stands somewhere near a gob from which {@code attempt} succeeds, and remembers where.
     *
     * Where to stand comes from {@link WorkSpot}: a fine grid of the ground round the target, built
     * from live collision boxes and rock tiles, flooded from where we are. The spots it offers are
     * reachable by construction and ranked by whether the server's straight walk from there to the
     * target meets anything. Getting there is its own legs, walked with plain server moves - never
     * the pathfinder's straight-line fallback, which in the fourth run walked the character into a
     * gap narrower than itself beside a mine support and left it unable to move for the rest of
     * the shift.
     *
     * Success is judged by the attempt itself - the window opening, the pile answering - and a spot
     * where it failed is not tried again in the same call.
     */
    private boolean fromSomeSide(Gob g, Attempt attempt) throws InterruptedException {
        List<Coord2d> failedAt = new ArrayList<>();
        // Where it worked last time: the fuel-and-light pass comes back to spots the fill found.
        Coord2d known = workedFrom.get(g.id);
        // Out to open ground first - unless we are already standing where this one is worked from.
        if ((known == null) || (distTo(known) > ON_SPOT))
            reachOpenGround();
        standingWidth = WIDTHS[0];
        if ((known != null) && standAt(known, workedWidth.getOrDefault(g.id, WIDTHS[0]))
            && tryHere(attempt, failedAt))
            return worked(g);
        standingWidth = WIDTHS[0];
        if (inReach(g) && tryHere(attempt, failedAt))
            return worked(g);

        if (dist(g) > FAR)
            new Approach(g, FAR / 2).run(ctx);

        /* Close spots with a clear line first, at every width from the pathfinder's down - right up
         * against the target is where the click cannot go wrong. Then, only if none of those
         * worked, the further ones at the full width: clear lines before blocked, nearest first. */
        List<WorkSpot.Spot> later = new ArrayList<>();
        int tried = 0;
        boolean anyClose = false;
        /* Narrower-than-pathfinder ways in only to reach a smelter - the north-east one has no other.
         * To a pile they are never needed, and they are dangerous: the click on a pile has the server
         * walk us further in, deeper than the spot we worked out a way back from, and two runs ended
         * wedged that way beside the metal piles. */
        double[] widths = isSmelter(g) ? WIDTHS : new double[] {WIDTHS[0]};
        for (double width : widths) {
            for (WorkSpot.Spot sp : WorkSpot.find(gui, g, width, MAX_TRIES)) {
                if (!sp.clear || (sp.gap > CLOSE)) {
                    if (width == WIDTHS[0])
                        later.add(sp);
                    continue;
                }
                anyClose = true;
                if (tried >= MAX_TRIES)
                    break;
                tried++;
                if (standOn(g, sp, width, tried) && tryHere(attempt, failedAt))
                    return worked(g);
            }
        }
        if (!anyClose && boxedIn())
            throw new InterruptedException();
        if (!anyClose && pictured.add(g.id))
            ctx.log("nowhere to stand right up against #" + g.id + " at any width:\n"
                + WorkSpot.picture(gui, g, WIDTHS[WIDTHS.length - 1]));
        later.sort((a, b) -> (a.clear != b.clear) ? (a.clear ? -1 : 1) : Double.compare(a.gap, b.gap));
        int blocked = 0;
        for (WorkSpot.Spot sp : later) {
            if (tried >= MAX_TRIES)
                break;
            /* Never for a pile: a click on a pile from a blocked line has the server walk us straight
             * into the blockage and pin us there - that is how a run ended shut in beside the metal
             * piles, standing on a cell the grid itself calls solid. */
            if (!sp.clear && (!isSmelter(g) || (++blocked > MAX_BLOCKED)))
                continue;
            tried++;
            if (standOn(g, sp, WIDTHS[0], tried) && tryHere(attempt, failedAt))
                return worked(g);
        }
        ctx.log("#" + g.id + " (" + Stockpile.resname(g) + "): no spot worked (" + tried + " tried)");
        return false;
    }

    /** Walks to one standing spot; says so in the log when it cannot. */
    private boolean standOn(Gob g, WorkSpot.Spot sp, double width, int n) throws InterruptedException {
        if (!running())
            throw new InterruptedException();
        Gob me = ctx.player();
        Coord2d from = (me == null) ? null : me.rc;
        boolean narrow = width < WIDTHS[0];
        standingWidth = width;
        boolean there = walk(sp.legs) && (distTo(sp.at) <= ON_SPOT);
        if (narrow)
            exitLegs = wayBack(sp.legs);
        ctx.log("#" + g.id + " spot " + n + " " + sp + (narrow ? " [" + (2 * width) + "u-wide way in]" : "")
            + (there ? "" : ": couldn't get there, stopped " + (int) distTo(sp.at) + "u short"));
        /* A walk that stops a few units short at a narrow way in is usually as good as there: the
         * click is tried from where we stand, and judged by whether it works. Giving up at six units
         * cost the north-east smelter its best two spots on most visits. */
        return there || (distTo(sp.at) <= NEAR_ENOUGH);
    }

    /**
     * The way back out along legs just walked in: the spot itself first - a click on the target may
     * have had the server walk us further in than it - then every leg in reverse. NOT back to where
     * the trip started. The first version went all the way back there, and when that happened to
     * be the pocket beside the mine support, it walked straight back into it and wedged.
     */
    private static List<Coord2d> wayBack(List<Coord2d> legs) {
        List<Coord2d> back = new ArrayList<>();
        for (int i = legs.size() - 1; i >= 0; i--)
            back.add(legs.get(i));
        return back;
    }

    /** On ground joined to a good stretch of the yard at the pathfinder's width. */
    private boolean inOpen() {
        return WorkSpot.roomAround(gui, WIDTHS[0], 8) >= OPEN_AREA;
    }

    /**
     * Gets out to open ground if we are not on it: back along the legs we came in by if we have
     * them, else by {@link WorkSpot#escape} at the narrowest width - the way out of wherever the
     * last click left us.
     */
    private void reachOpenGround() throws InterruptedException {
        leaveNarrows();
        if (inOpen())
            return;
        List<Coord2d> out = WorkSpot.escape(gui, WIDTHS[0], WIDTHS[WIDTHS.length - 1], 8, OPEN_AREA);
        if ((out == null) || out.isEmpty())
            return;
        ctx.log("not on open ground - walking out (" + out.size() + " leg(s))");
        walk(out);
    }

    /**
     * Walks back out of a gap only a narrow character fits, if we are in one - leg by leg, stopping
     * as soon as there is open ground round us at the pathfinder's width, since from there every
     * other kind of walking works.
     */
    private void leaveNarrows() throws InterruptedException {
        if (exitLegs == null)
            return;
        List<Coord2d> legs = exitLegs;
        exitLegs = null;
        ctx.log("leaving the narrow way in");
        for (Coord2d leg : legs) {
            if (!running())
                throw new InterruptedException();
            if (WorkSpot.roomAround(gui, WIDTHS[0], 3) >= OPEN_GROUND)
                return;
            if (distTo(leg) > 1.0)
                Walk.straightTo(ctx.nav, gui, leg, 2.0);
        }
    }

    /**
     * Walks back to a spot known to work, at the width it was first reached at - the north-east
     * smelter's spot is only reachable narrower than the pathfinder's width, and routing back to it
     * at full width found no way and started the whole search over every visit.
     */
    private boolean standAt(Coord2d spot, double width) throws InterruptedException {
        standingWidth = width;
        if (distTo(spot) <= ON_SPOT)
            return true;
        Gob me = ctx.player();
        Coord2d from = (me == null) ? null : me.rc;
        List<Coord2d> legs = WorkSpot.route(gui, spot, width);
        if (legs == null)
            return false;
        boolean there = walk(legs) && (distTo(spot) <= ON_SPOT);
        if (width < WIDTHS[0])
            exitLegs = wayBack(legs);
        return there || (distTo(spot) <= NEAR_ENOUGH);
    }

    /** Walks straight legs with plain server moves. False if one of them stops short. */
    private boolean walk(List<Coord2d> legs) throws InterruptedException {
        for (Coord2d leg : legs) {
            if (!running())
                throw new InterruptedException();
            if (distTo(leg) <= 1.0)
                continue;
            if (!Walk.straightTo(ctx.nav, gui, leg, 2.0) && (distTo(leg) > 3.0))
                return false;
        }
        return true;
    }

    private double distTo(Coord2d p) {
        Gob me = ctx.player();
        return ((me == null) || (p == null)) ? Double.MAX_VALUE : me.rc.dist(p);
    }

    /**
     * An attempt that succeeds where the server's straight walk from here to {@code sm} meets
     * nothing else - for fuelling and lighting, which are clicks the server walks in to act on.
     *
     * It used to be "anywhere in reach", which is how the north-west smelter got a spark crafted
     * behind a mine support: the server walked the character straight at the smelter, into the
     * support, and the spark never reached it.
     */
    private Attempt clearLine(Gob sm) {
        return () -> {
            Gob me = ctx.player();
            return (me != null) && WorkSpot.lineTo(gui, sm, me.rc, standingWidth);
        };
    }

    /**
     * Whether we are shut in - less than a few square tiles of ground we can walk to. Sets
     * {@link #fatalStop} when we are, so the shift ends saying why rather than trying every target
     * in the yard from a pocket, which is what the run that shut itself in did.
     */
    private boolean boxedIn() throws InterruptedException {
        if (inOpen())
            return false;
        /* Not on open ground is not the same as shut in. The north-east smelter's corridor is a few
         * square tiles at the pathfinder's width and was twice read as "shut in" from inside it,
         * though the 5-unit way out was right there. Walk out at the narrow width first. */
        reachOpenGround();
        if (inOpen())
            return false;
        // Then once more with a step in whichever direction the server will allow.
        Gob me = ctx.player();
        if (me != null) {
            ctx.log("still hemmed in - trying to step clear");
            Walk.unstick(ctx.nav, gui, area.centre(gui));
            if (inOpen())
                return false;
        }
        double room = WorkSpot.roomAround(gui, WIDTHS[0], 8);
        me = ctx.player();
        fatalStop = "shut in at " + ((me == null) ? "?" : ((int) me.rc.x + "," + (int) me.rc.y))
            + " with " + String.format("%.1f", room) + " square tiles to move in - probably by a pile;"
            + " clear a way out and start again";
        ctx.log(fatalStop);
        return true;
    }

    /** Runs the attempt unless it has already failed from where we are standing. */
    private boolean tryHere(Attempt attempt, List<Coord2d> failedAt) throws InterruptedException {
        Gob me = ctx.player();
        if (me == null)
            return false;
        for (Coord2d f : failedAt) {
            if (f.dist(me.rc) < ON_SPOT)
                return false;
        }
        if (attempt.run())
            return true;
        failedAt.add(me.rc);
        return false;
    }

    private static boolean isSmelter(Gob g) {
        return SMELTER_RES.equals(Stockpile.resname(g));
    }

    private boolean worked(Gob g) {
        Gob me = ctx.player();
        if (me != null) {
            workedFrom.put(g.id, me.rc);
            /* A spot off open ground is one only a narrower character gets back to, whatever width
             * it was found at: the smelter beside the north-east corridor was worked from inside it
             * and remembered at full width, which no route back could honour. */
            double w = inOpen() ? standingWidth : Math.min(standingWidth, WIDTHS[1]);
            workedWidth.put(g.id, w);
        }
        return true;
    }

    private boolean inReach(Gob g) {
        return dist(g) <= Reach.toActOn(g);
    }

    private double dist(Gob g) {
        Gob me = ctx.player();
        return ((me == null) || (g == null)) ? Double.MAX_VALUE : me.rc.dist(g.rc);
    }

    private Window newWindow(Set<Window> before) {
        for (Window w : containerWindows()) {
            if (!before.contains(w))
                return w;
        }
        return null;
    }

    /** Every window with an inventory in it, other than the pack's own. */
    private List<Window> containerWindows() {
        List<Window> out = new ArrayList<>();
        try {
            for (Widget w = gui.child; w != null; w = w.next) {
                if (!(w instanceof Window))
                    continue;
                Inventory inv = Widgets.find(w, Inventory.class);
                if ((inv != null) && (inv != gui.maininv))
                    out.add((Window) w);
            }
        } catch (RuntimeException e) {
            // The tree changed under us; the caller polls again.
        }
        return out;
    }

    private void close(Window w) throws InterruptedException {
        if ((w == null) || (w.parent == null))
            return;
        w.wdgmsg("close");
        ctx.nav.waitUntil(() -> w.parent == null, 40);
    }

    /** Waits for a freshly opened inventory's contents to stop arriving. */
    private void settle(Inventory inv) throws InterruptedException {
        int last = -1;
        for (int i = 0; i < 10; i++) {
            int now = pieces(inv).size();
            if ((now == last) && (i >= 2))
                return;
            last = now;
            ctx.nav.pause(2);
        }
    }

    private void itemact(Gob g, int mod) {
        Coord at = g.rc.floor(posres);
        gui.map.wdgmsg("itemact", Coord.z, at, mod, 0, (int) g.id, at, 0, -1);
    }

    private boolean takeToHand(Predicate<String> want) throws InterruptedException {
        // A loose piece where there is one: a click on a piece inside a stack does nothing.
        List<WItem> loose = new ArrayList<>();
        for (WItem top : gui.maininv.getAllItems()) {
            if ((members(top).size() == 1) && want.test(basename(top)))
                loose.add(top);
        }
        List<WItem> have = loose.isEmpty() ? matching(pieces(gui.maininv), want) : loose;
        if (have.isEmpty())
            return false;
        have.get(0).item.wdgmsg("take", Coord.z);
        ctx.nav.waitUntil(() -> gui.vhand != null, HAND_TICKS);
        return gui.vhand != null;
    }

    // ------------------------------------------------------------------ items

    /**
     * Every real item in an inventory, looking through stacks to their members.
     *
     * A stack is packaging: the members are the items, each with its own tooltip, and that is the
     * only level at which "well mined" means anything.
     */
    private static List<WItem> pieces(Inventory inv) {
        List<WItem> out = new ArrayList<>();
        if (inv == null)
            return out;
        for (WItem wi : inv.getAllItems())
            out.addAll(members(wi));
        return out;
    }

    private static List<WItem> matching(List<WItem> items, Predicate<String> want) {
        List<WItem> out = new ArrayList<>();
        for (WItem p : items) {
            if (want.test(basename(p)))
                out.add(p);
        }
        return out;
    }

    private int count(Predicate<String> want) {
        return matching(pieces(gui.maininv), want).size();
    }

    private static String basename(WItem wi) {
        try {
            Resource r = (wi == null || wi.item == null) ? null : wi.item.getres();
            return (r == null) ? null : r.basename();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String resname(WItem wi) {
        try {
            Resource r = (wi == null || wi.item == null) ? null : wi.item.getres();
            return (r == null) ? null : r.name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String base(String res) {
        int cut = res.lastIndexOf('/');
        return (cut < 0) ? res : res.substring(cut + 1);
    }

    /**
     * The order to walk to some gobs in: the nearest to us, then the nearest to THAT one, and so on.
     *
     * The first version sorted by distance from where the character stood when the list was made.
     * That is not a walking order - after the first stop every "next nearest" is measured from a
     * spot already left behind, and the character zig-zags across the row.
     */
    private List<Gob> tour(List<Gob> gobs) {
        List<Gob> left = new ArrayList<>(gobs);
        List<Gob> out = new ArrayList<>(gobs.size());
        Gob me = ctx.player();
        Coord2d at = (me == null) ? null : me.rc;
        while (!left.isEmpty()) {
            Gob best = left.get(0);
            if (at != null) {
                for (Gob g : left) {
                    if (g.rc.dist(at) < best.rc.dist(at))
                        best = g;
                }
            }
            left.remove(best);
            out.add(best);
            at = best.rc;
        }
        return out;
    }

    /** Learns the basename of anything whose shown name is a known ore. */
    private void noteOre(List<WItem> items) {
        for (WItem p : items) {
            String n;
            try {
                n = p.item.getname();
            } catch (RuntimeException e) {
                continue;
            }
            if ((n == null) || !ORE_NAMES.contains(n))
                continue;
            String b = basename(p);
            if ((b != null) && !CATGOLD.equals(b) && learnedOre.add(b))
                ctx.log("learned ore: " + b + " (" + n + ")");
        }
    }

    private static List<VMeter> meters(Widget root) {
        List<VMeter> out = new ArrayList<>();
        for (Widget w = root.child; w != null; w = w.next) {
            if (w instanceof VMeter)
                out.add((VMeter) w);
            out.addAll(meters(w));
        }
        return out;
    }
}
