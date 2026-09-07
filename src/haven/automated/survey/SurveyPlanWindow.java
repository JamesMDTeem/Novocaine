package haven.automated.survey;

import haven.Area;
import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Label;
import haven.Loading;
import haven.MCache;
import haven.Scrollport;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.Stoppable;
import haven.automated.helpers.AreaSelectCallback;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.world.WorldAnchor;

import java.util.List;
import java.util.Set;

/**
 * The plan for levelling a region flat, as a work list a crew can share.
 *
 * Three things the window is for, in order of how load-bearing they are:
 *
 * <p><b>Where to draw each survey.</b> Every row is one rectangle, and every rectangle is also
 * painted on the ground by {@link SurveyOverlay} with its work-order number over the middle.
 * That pairing is the whole point: placing a survey goes through a drag this client has never
 * driven, so the row is an instruction a player has to carry out by hand, and nothing in the game
 * shows them the absolute tile coordinates the row is written in. The row says what, the ground
 * says where, and the number is the same in both.
 *
 * <p><b>What level to set it to.</b> Every survey gets the SAME level, the region's mean. This is
 * the part that is automated, because it is the part that was verified: setting the level writes
 * the target plane and sends it, and the server keeps it. Note that the survey window's own
 * "Ground plane" button is NOT the same thing and is wrong for this job - it levels a survey to
 * its own mean, which terraces the region rather than flattening it.
 *
 * <p><b>Who is working what, and what is finished.</b> Claims are exclusive per survey, and that
 * is correctness rather than tidiness: {@code NSurveyBot} records that two characters manning one
 * survey do not halve the work but corrupt it, each draining soil the other is still counting.
 * Done-marks are a separate, permanent thing - see {@link SurveyPlanStore#done} - and they are
 * what decides which rectangle is lit up as next.
 */
public class SurveyPlanWindow extends Window implements Stoppable {
    private static final Coord WSZ = UI.scale(new Coord(540, 470));
    private static final int LISTW = UI.scale(512);

    /**
     * One survey-hop of walking, priced in pickups.
     *
     * Fixed rather than exposed because sweeping it showed the answer converges by 1 and stops
     * moving - only "walking is free" gives a different partition, and carrying cost is known to
     * rise with distance. A dial here would be a dial with one useful setting.
     */
    private static final double DISTANCE_WEIGHT = 1.0;

    /** The largest survey the game will let you draw, per side. */
    private static final int MAX_SIDE = 31;

    /**
     * The largest region that will be planned, per side, in tiles.
     *
     * Not a limit of the maths - the planner will happily partition anything - but of patience.
     * The search re-solves a min-cost flow whose size grows with the square of the survey count,
     * so cost climbs roughly with the fourth power of the region's side. This is about two and a
     * half grids across and is already the slow end of usable; past it the honest answer is that
     * the region is two jobs.
     */
    private static final int MAX_REGION = 250;

    /** How often the list is rebuilt to pick up a crewmate's claims and done-marks, in seconds. */
    private static final double LIST_PERIOD = 2.0;

    /** How often an unplaced plan retries resolving its anchor, in seconds. */
    private static final double PLACE_PERIOD = 0.5;

    private final GameUI gui;
    private final Scrollport list;
    private final Label status;
    private final Label regionlbl;
    private SurveyPlan plan;
    private boolean rebuild = true;
    private double nextlist = 0;
    /** The last survey scale actually seen. Server-supplied, so it can only be observed. */
    private float lastGran = 0;
    /** False while a loaded plan is still in some previous session's coordinates. */
    private boolean placed;
    private double nextplace = 0;

    /** The region to plan, once both corners are known. Null falls back to the current grid. */
    private Area region;
    /** The corner marks of a region being picked out on foot; see {@link Corners}. */
    private final Corners corners = new Corners();

    /** Set by the drag callback, consumed by {@link #tick}; see {@link #armDrag}. */
    private volatile boolean dragdone;
    private volatile Coord draga, dragb;
    private boolean dragarmed;

    /** The planning worker's result, and its message. Both consumed by {@link #tick}. */
    private volatile SurveyPlan computed;
    private volatile String computedmsg;
    private volatile boolean planning;

    public SurveyPlanWindow(GameUI gui) {
        super(WSZ, "Survey Planner");
        this.gui = gui;

        int y = UI.scale(4);
        add(new Label("Levels a region to one flat plane. Draw each survey as numbered on the ground,"
            + " then set its level. Violet bands are stockpile ground both surveys reach."),
            new Coord(UI.scale(6), y));
        y += UI.scale(18);

        add(new Button(UI.scale(110), "Plan this grid") {
            public void click() {
                replan(null);
            }
        }, new Coord(UI.scale(6), y));

        add(new Button(UI.scale(100), "Draw & plan") {
            public void click() {
                armDrag();
            }
        }, new Coord(UI.scale(122), y));

        add(new Button(UI.scale(76), "Corner A") {
            public void click() {
                corner(true);
            }
        }, new Coord(UI.scale(228), y));

        add(new Button(UI.scale(76), "Corner B") {
            public void click() {
                corner(false);
            }
        }, new Coord(UI.scale(310), y));

        add(new Button(UI.scale(100), "Replan region") {
            public void click() {
                if (region == null)
                    status.settext("Nothing drawn yet - use 'Draw & plan', or set both corners.");
                else
                    replan(region);
            }
        }, new Coord(UI.scale(392), y));
        y += UI.scale(26);

        add(new Button(UI.scale(150), "Set level on open survey") {
            public void click() {
                setLevel();
            }
        }, new Coord(UI.scale(6), y));

        add(new CheckBox("Show plan on the ground") {
            {a = SurveyOverlay.enabled();}

            public void set(boolean val) {
                SurveyOverlay.enabled(val);
                a = val;
            }
        }, new Coord(UI.scale(166), y + UI.scale(3)));

        add(new CheckBox("Show stockpile spots") {
            {a = SurveyOverlay.piles();}

            public void set(boolean val) {
                SurveyOverlay.piles(val);
                a = val;
            }
        }, new Coord(UI.scale(350), y + UI.scale(3)));
        y += UI.scale(26);

        regionlbl = add(new Label(""), new Coord(UI.scale(6), y));
        y += UI.scale(18);

        status = add(new Label(""), new Coord(UI.scale(6), y));
        y += UI.scale(18);

        list = add(new Scrollport(new Coord(LISTW, WSZ.y - y - UI.scale(12))),
            new Coord(UI.scale(6), y));

        plan = SurveyPlanStore.load();
        /* A loaded plan already knows its own region, so the drawn region survives a relog with
         * it. Without this the plan came back and the region did not, and 'Replan region' reported
         * nothing drawn for a plan that was plainly on screen. */
        if (plan != null)
            region = plan.region;
        placed = false;
        describe();
        describeRegion();
    }

    /**
     * Restates a loaded plan in this session's coordinates.
     *
     * Called from the tick until it succeeds. Resolution genuinely can fail for a while after a
     * login - the map file writes its grid records as ground is explored, so the player's own grid
     * may not have an entry for the first few seconds - and treating that first failure as final
     * is what would turn a transient state into a permanent verdict. The same mistake
     * {@code WorldAnchor.offsetTo} exists to undo elsewhere.
     */
    private void place() {
        if (plan == null || placed)
            return;
        if (plan.anchor == null) {
            /* Written before plans were anchored, or by a client whose map file could not place
             * the player. The coordinates are whatever session wrote them, so they are shown as-is
             * and honestly labelled rather than silently trusted. */
            placed = true;
            SurveyOverlay.show(plan);
            rebuild = true;
            status.settext("Plan loaded, but it was saved without an anchor - if the rectangles are"
                + " not where you expect, replan.");
            return;
        }
        double now = Utils.rtime();
        if (now < nextplace)
            return;
        nextplace = now + PLACE_PERIOD;
        Coord2d live = plan.anchor.resolve(gui);
        if (live == null) {
            status.settext("Plan loaded; waiting for the map to place it...");
            return;
        }
        placed = true;
        plan = plan.rebase(live.floor(MCache.tilesz).add(plan.anchorOff));
        region = plan.region;
        SurveyOverlay.show(plan);
        rebuild = true;
        describe();
        describeRegion();
    }

    /**
     * Something durable to hang a fresh plan's coordinates off.
     *
     * Captured at the PLAYER, not at the region's corner. {@code WorldAnchor.capture} needs the
     * grid it is handed to be loaded AND recorded in the map file, which is reliably true of where
     * you are standing and routinely false of a corner several grids away - and an anchor captured
     * from the player always carries a server-assigned grid id, which is the half that means the
     * same thing on a crewmate's client.
     */
    private SurveyPlan anchor(SurveyPlan p) {
        try {
            WorldAnchor a = WorldAnchor.capturePlayer(gui);
            if (a == null)
                return p;
            Coord me = gui.map.player().rc.floor(MCache.tilesz);
            return p.anchored(a, p.region.ul.sub(me));
        } catch (RuntimeException e) {
            // Loading included: no anchor is a degradation, not a failure. The plan is still
            // correct for this session, it just will not survive a relog.
            return p;
        }
    }

    // ------------------------------------------------------------------ choosing a region

    /**
     * Hands the next map drag to this window.
     *
     * Armed here, APPLIED FROM {@link #tick} - the callback runs inside MapView's own mouse-up,
     * under its monitor and with the selector still mid-teardown, which is the hazard
     * {@code AreaDraw} documents at length. The same one-frame deferral is the answer.
     */
    private void armDrag() {
        if (gui == null || gui.map == null)
            return;
        dragdone = false;
        draga = dragb = null;
        /* A spent Selector left over from a previous drag reads the next click as "cancel", so
         * the first click of a fresh draw is silently lost without this. */
        gui.map.unregisterAreaSelect();
        gui.map.registerAreaSelect(new AreaSelectCallback() {
            public void areaselect(Coord p, Coord q) {
                draga = new Coord(Math.min(p.x, q.x), Math.min(p.y, q.y));
                dragb = new Coord(Math.max(p.x, q.x), Math.max(p.y, q.y));
                gui.map.areaSelect = false;
                dragdone = true;
            }
        });
        /* Registering the callback is not enough on its own: MapView only builds a selector while
         * areaSelect is true, and forgetting the flag is why a draw button can look inert. */
        gui.map.areaSelect = true;
        dragarmed = true;
        status.settext("Drag the region to survey on the map.");
    }

    /** Applies a finished drag. Called once per frame from {@link #tick}. */
    private void takeDrag() {
        if (!dragdone)
            return;
        dragdone = false;
        dragarmed = false;
        if (gui != null && gui.map != null)
            gui.map.unregisterAreaSelect();
        if (draga == null || dragb == null)
            return;
        // A drag supersedes any corner selection part-way through, so it does not leave half of
        // one lying around for the next corner press to pair with.
        corners.clear();
        /* No +1. MapView hands over a haven.Area whose br is already exclusive, so the difference
         * IS the tile count; adding one puts an extra row and column on every region drawn. */
        setRegion(Area.corn(draga, dragb), true);
    }

    /**
     * Takes one corner of the region from where the character is standing.
     *
     * The drag is the pleasant way to do this and cannot do the big case: the selector only
     * reaches ground that is actually rendered, which is well under a grid at any usable zoom, so
     * a region spanning several grids is not draggable at all. Walking the two corners has no such
     * limit, and a region worth planning is one somebody has walked anyway - the heights have to
     * have been loaded or there is nothing to plan from.
     */
    private void corner(boolean first) {
        try {
            if (gui.map == null || gui.map.player() == null) {
                status.settext("Cannot see where you are standing yet - try again in a moment.");
                return;
            }
            corners.press(first, gui.map.player().rc.floor(MCache.tilesz));
            if (corners.complete()) {
                // The second corner completes the region, so it plans; the first cannot.
                setRegion(corners.region(), true);
            } else {
                status.settext("Corner " + (first ? "A" : "B") + " set.");
                describeRegion();
            }
        } catch (Loading l) {
            status.settext("Your position is still loading - try again in a moment.");
        } catch (RuntimeException e) {
            NLog.crash("SurveyPlanWindow.corner", e);
            status.settext("Could not read your position: " + e);
        }
    }

    /**
     * Takes a region and plans it, in one motion.
     *
     * Setting the region and planning it used to be two button presses, and the gap between them
     * was a trap: a rejected drag left no region at all, so the next press reported nothing set and
     * gave no hint that a drag had been made and thrown away. Planning straight off the drag is
     * what makes it one action - the same reasoning {@code PlacesWindow} records for its Re-draw
     * button, which applies its drag rather than merely arming it.
     *
     * @param replan false when the region came from a single corner press and is not final yet
     */
    private void setRegion(Area a, boolean replan) {
        Coord sz = a.sz();
        if (sz.x < 2 || sz.y < 2) {
            /* A press that barely moves is easy to make, because the first click both arms the
             * selector and begins the drag. Re-arming rather than reporting and stopping is what
             * keeps that from looking like a button that does nothing. */
            // Arm first: armDrag writes its own prompt, so setting ours afterwards is what keeps
            // the reason visible rather than replacing it with a bare "drag the region".
            armDrag();
            status.settext("That was " + sz.x + "x" + sz.y + " tiles - too small. Drag again.");
            return;
        }
        if (sz.x > MAX_REGION || sz.y > MAX_REGION) {
            status.settext("That is " + sz.x + "x" + sz.y + " tiles; " + MAX_REGION
                + " a side is the most that can be planned at once. Draw a smaller region.");
            return;
        }
        /* The corner marks are NOT rewritten from the region here. They are the corner workflow's
         * own state - a selection part-way through - and a region arriving from anywhere else has
         * no business completing it. Deriving them from the region is what made every press of
         * 'Corner A' land on an already-complete pair. */
        region = a;
        describeRegion();
        if (replan)
            replan(region);
    }

    private void describeRegion() {
        /* A half-finished corner pair is reported AHEAD of the region, because during those two
         * button presses the region on record is the previous one and saying so reads as the new
         * selection having already taken effect. */
        if (corners.partial()) {
            boolean haveA = corners.a != null;
            regionlbl.settext("Region: corner " + (haveA ? "A" : "B") + " at "
                + (haveA ? corners.a : corners.b) + " - walk to the far corner and set "
                + (haveA ? "B" : "A") + ".");
        } else if (region != null) {
            Coord sz = region.sz();
            regionlbl.settext(String.format("Region: %dx%d tiles at %s.", sz.x, sz.y, region.ul));
        } else {
            regionlbl.settext("Region: none - 'Plan this grid' uses the grid you are standing on.");
        }
    }

    // ------------------------------------------------------------------ planning

    /**
     * Works out a fresh plan, for a drawn region or for the grid the player is standing on.
     *
     * The heights are read HERE, on the UI thread, and only the search runs on the worker. Reading
     * is cheap and touches the player gob and the map cache, which is not somewhere to be from
     * another thread; the search is the expensive half and touches nothing but its own arrays.
     *
     * <p>Refuses when any vertex is still loading. A plan built from unloaded ground looks
     * perfectly reasonable - the numbers are all there - and is entirely wrong, because the
     * missing vertices read as zero and drag the mean down with them.
     */
    private void replan(Area area) {
        if (planning) {
            status.settext("Already planning - give it a moment.");
            return;
        }
        final Heights hs;
        try {
            hs = (area == null) ? Heights.read(gui) : Heights.read(gui, area);
        } catch (Loading l) {
            status.settext("Terrain still loading - try again in a moment.");
            return;
        } catch (RuntimeException e) {
            NLog.crash("SurveyPlanWindow.replan", e);
            status.settext("Could not read the ground: " + e);
            return;
        }
        if (hs.missing > 0) {
            status.settext(hs.missing + " vertices still loading - walk the region and retry.");
            return;
        }
        planning = true;
        computed = null;
        computedmsg = null;
        status.settext("Planning " + (hs.w - 1) + "x" + (hs.h - 1) + " tiles...");
        /* Off the UI thread because the search is not quick and gets much less quick with size:
         * a grid is a moment, and the biggest region this window will accept is long enough that
         * doing it inline would look like the client had hung. */
        Thread t = new Thread(() -> {
            try {
                computed = SurveyPlanner.compute(hs, MAX_SIDE, DISTANCE_WEIGHT);
            } catch (RuntimeException e) {
                NLog.crash("SurveyPlanWindow.compute", e);
                computedmsg = "Could not plan: " + e;
            } finally {
                planning = false;
            }
        }, "survey-planner");
        t.setDaemon(true);
        t.start();
    }

    /** Takes the worker's result. Called once per frame from {@link #tick}. */
    private void takePlan() {
        SurveyPlan got = computed;
        String msg = computedmsg;
        if (got != null) {
            computed = null;
            plan = anchor(got);
            placed = true;   // freshly computed, so already in this session's coordinates
            SurveyPlanStore.save(plan);
            SurveyOverlay.show(plan);
            rebuild = true;
            describe();
        } else if (msg != null) {
            computedmsg = null;
            status.settext(msg);
        }
    }

    // ------------------------------------------------------------------ setting the level

    /**
     * Writes the plan's level into whichever survey is open.
     *
     * A survey that matches no planned rectangle still gets the level if the player asks for it,
     * with a note rather than a refusal: a hand-drawn survey at the right level is unplanned, not
     * wrong, and the whole region only comes out flat if everything reaches the same height.
     */
    private void setLevel() {
        if (plan == null) {
            status.settext("No plan yet - press 'Plan this grid' first.");
            return;
        }
        Area open = SurveyProbe.openArea(gui);
        if (open == null) {
            status.settext("No 'Land survey' window open.");
            return;
        }
        float gran = SurveyProbe.openGran(gui);
        if (gran <= 0) {
            status.settext("Could not read the survey's scale.");
            return;
        }
        lastGran = gran;
        int dz = plan.targetDz(gran);
        if (!SurveyProbe.setLevel(gui, dz)) {
            status.settext("Could not set the level - see logs/survey.log.");
            return;
        }
        SurveyPlan.SurveySpec match = matching(open);
        status.settext(match == null
            ? "Set to dz " + dz + ". (This survey is not one of the planned rectangles.)"
            : "Set survey " + plan.step(match.index) + " to dz " + dz + "; expect "
              + balance(match.net) + ".");
    }

    /** The planned survey occupying exactly this rectangle, or null. */
    private SurveyPlan.SurveySpec matching(Area area) {
        if (plan == null)
            return null;
        for (SurveyPlan.SurveySpec s : plan.surveys) {
            if (s.tiles.equals(area))
                return s;
        }
        return null;
    }

    private void describe() {
        if (plan == null) {
            status.settext("No plan yet. Stand on the grid and press 'Plan this grid'.");
            return;
        }
        double carry = 0, far = 0;
        for (SurveyPlan.Transfer t : plan.transfers) {
            carry += t.amount;
            /* No shared edge means no band, and no band means that soil really does get carried
             * rather than handed over a boundary. Worth saying out loud, because it is the part of
             * the plan that costs somebody a walk. */
            if (plan.reachZone(t) == null)
                far += t.amount;
        }
        int fin = SurveyPlanStore.done(plan).size();
        String s = String.format("%d surveys, %d done, every one at %s. %,.0f units (~%,.0f "
            + "pile-loads) move between surveys.",
            plan.surveys.size(), fin, level(), carry, carry / SurveyPlan.PILE_CAP);
        if (far > 0.5)
            s += String.format(" %,.0f of it has no shared edge and must be carried.", far);
        status.settext(s);
    }

    /**
     * The plan's target, in the units a player can actually check it against.
     *
     * Emphatically NOT {@code targetDz(1)}. The window's scale is {@code gran}, which the resource
     * derives from a server-supplied number and which therefore cannot be worked out client-side -
     * it has to be observed off a live survey. Printing a dz at gran=1 puts a number on screen that
     * looks like the one the survey window shows and is not it, which is precisely the number
     * somebody would copy in by hand if the button ever failed them. So until a survey has been
     * open, the target is quoted in raw client z, which is honest about being a different unit.
     */
    private String level() {
        float gran = SurveyProbe.openGran(gui);
        if (gran > 0)
            lastGran = gran;
        if (lastGran > 0)
            return "dz " + plan.targetDz(lastGran);
        return String.format("z %.2f (open a survey to see it in the window's own units)",
            plan.targetZ);
    }

    /** How a survey's net reads to somebody about to work it. */
    private static String balance(double net) {
        if (net > 0.5)
            return String.format("%,.0f short", net);
        if (net < -0.5)
            return String.format("%,.0f spare", -net);
        return "balanced";
    }

    // ------------------------------------------------------------------ the list

    private static void clear(Widget parent) {
        for (Widget w = parent.child; w != null; ) {
            Widget next = w.next;
            w.destroy();
            w = next;
        }
    }

    private void refresh() {
        clear(list.cont);
        int y = 0;
        if (plan == null) {
            list.cont.add(new Label("No plan yet."), new Coord(0, y));
            return;
        }
        Set<Integer> fin = SurveyPlanStore.done(plan);
        List<SurveyPlan.SurveySpec> order = plan.order();
        for (SurveyPlan.SurveySpec spec : order) {
            final SurveyPlan.SurveySpec s = spec;
            Coord sz = s.tiles.sz();
            boolean held = SurveyPlanStore.taken(s.index);
            final boolean isdone = fin.contains(s.index);

            /* The leading number is the work-order position, which is what the ground shows too.
             * The rectangle's own coordinates stay on the row for anyone reading the plan file or
             * checking a survey they have already drawn, but they are no longer how you find it. */
            list.cont.add(new Label(String.format("%2d.  %s  %dx%d",
                plan.step(s.index), s.tiles.ul, sz.x, sz.y)), new Coord(0, y + UI.scale(3)));
            list.cont.add(new Label(balance(s.net)), new Coord(UI.scale(210), y + UI.scale(3)));

            /* Surpluses are dug first and their piles feed the shortfalls, so saying which is
             * which on the row is what makes the order legible without reading the transfer list. */
            list.cont.add(new Label(s.net > 0 ? "needs soil" : "digs out"),
                new Coord(UI.scale(300), y + UI.scale(3)));

            list.cont.add(new Button(UI.scale(56), isdone ? "Undo" : "Done") {
                public void click() {
                    SurveyPlanStore.setDone(plan, s.index, !isdone);
                    rebuild = true;
                    describe();
                }
            }, new Coord(UI.scale(370), y));

            if (held) {
                list.cont.add(new Label("taken"), new Coord(UI.scale(434), y + UI.scale(3)));
            } else {
                list.cont.add(new Button(UI.scale(60), "Claim") {
                    public void click() {
                        if (!SurveyPlanStore.claim(s.index))
                            status.settext("Survey " + plan.step(s.index)
                                + " was just taken by someone else.");
                        rebuild = true;
                    }
                }, new Coord(UI.scale(434), y));
            }
            y += UI.scale(22);
        }
    }

    public void tick(double dt) {
        takeDrag();
        takePlan();
        place();
        double now = Utils.rtime();
        /* Claims and done-marks are shared, so the list goes stale on its own when a crewmate
         * works. Rebuilding on a slow timer is what keeps two clients showing the same thing. */
        if (now > nextlist) {
            nextlist = now + LIST_PERIOD;
            rebuild = true;
        }
        if (rebuild) {
            rebuild = false;
            refresh();
        }
        super.tick(dt);
    }

    /**
     * Releases every claim this window took, and takes the ground rectangles down.
     *
     * Claims lapse on their own after {@code WorkClaims.TTL_MS}, but leaving sixteen of them to
     * time out means a crew that closes the window sees the whole list as taken for the next half
     * minute. Done-marks are NOT released - they are the record of work that actually happened.
     */
    public void stop() {
        if (dragarmed && gui != null && gui.map != null) {
            gui.map.areaSelect = false;
            gui.map.unregisterAreaSelect();
            dragarmed = false;
        }
        SurveyOverlay.clear(gui);
        if (plan == null)
            return;
        for (SurveyPlan.SurveySpec s : plan.surveys)
            SurveyPlanStore.release(s.index);
    }

    public void reqdestroy() {
        stop();
        super.reqdestroy();
    }
}
