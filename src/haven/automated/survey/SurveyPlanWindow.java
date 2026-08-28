package haven.automated.survey;

import haven.Area;
import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.Loading;
import haven.Scrollport;
import haven.UI;
import haven.Widget;
import haven.Window;
import haven.automated.Stoppable;
import haven.automated.nbots.core.NLog;

import java.awt.Color;
import java.util.List;

/**
 * The plan for levelling a grid flat, as a work list a crew can share.
 *
 * Three things the window is for, in order of how load-bearing they are:
 *
 * <p><b>Where to draw each survey.</b> Every row is one rectangle, given as its north-west corner
 * and size in tiles. Drawing them is still manual - placing a survey goes through a drag this
 * client has never driven - so the row is an instruction, and the predicted balance beside it is
 * what tells you whether you drew it where the plan meant.
 *
 * <p><b>What level to set it to.</b> Every survey gets the SAME level, the region's mean. This is
 * the part that is automated, because it is the part that was verified: setting the level writes
 * the target plane and sends it, and the server keeps it. Note that the survey window's own
 * "Ground plane" button is NOT the same thing and is wrong for this job - it levels a survey to its
 * own mean, which terraces the region instead of flattening it.
 *
 * <p><b>Who is working what.</b> Claims are exclusive per survey, and that is correctness rather
 * than tidiness: {@code NSurveyBot} records that two characters manning one survey do not halve the
 * work but corrupt it, each draining soil the other is still counting. Different surveys at the
 * same time is the intended case - it is why the plan makes sixteen of them - so the list shows
 * what other characters have taken and refuses a row already held.
 */
public class SurveyPlanWindow extends Window implements Stoppable {
    private static final Coord WSZ = UI.scale(new Coord(470, 420));
    private static final int LISTW = UI.scale(452);

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

    private final GameUI gui;
    private final Scrollport list;
    private final Label status;
    private SurveyPlan plan;
    private boolean rebuild = true;

    public SurveyPlanWindow(GameUI gui) {
        super(WSZ, "Survey Planner");
        this.gui = gui;

        int y = UI.scale(4);
        add(new Label("Levels a grid to one flat plane. Draw each survey as listed, then set its level."),
            new Coord(UI.scale(6), y));
        y += UI.scale(18);

        add(new Button(UI.scale(110), "Plan this grid") {
            public void click() {
                replan();
            }
        }, new Coord(UI.scale(6), y));

        add(new Button(UI.scale(150), "Set level on open survey") {
            public void click() {
                setLevel();
            }
        }, new Coord(UI.scale(122), y));
        y += UI.scale(26);

        status = add(new Label(""), new Coord(UI.scale(6), y));
        y += UI.scale(18);

        list = add(new Scrollport(new Coord(LISTW, WSZ.y - y - UI.scale(12))),
            new Coord(UI.scale(6), y));

        plan = SurveyPlanStore.load();
        describe();
    }

    // ------------------------------------------------------------------ actions

    /**
     * Works out a fresh plan for the grid the player is standing on.
     *
     * Refuses when any vertex is still loading. A plan built from unloaded ground looks perfectly
     * reasonable - the numbers are all there - and is entirely wrong, because the missing vertices
     * read as zero and drag the mean down with them.
     */
    private void replan() {
        try {
            Heights hs = Heights.read(gui);
            if (hs.missing > 0) {
                status.settext(hs.missing + " vertices still loading - walk the grid and retry.");
                return;
            }
            plan = SurveyPlanner.compute(hs, MAX_SIDE, DISTANCE_WEIGHT);
            SurveyPlanStore.save(plan);
            rebuild = true;
            describe();
        } catch (Loading l) {
            status.settext("Terrain still loading - try again in a moment.");
        } catch (RuntimeException e) {
            NLog.crash("SurveyPlanWindow.replan", e);
            status.settext("Could not plan: " + e);
        }
    }

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
        int dz = plan.targetDz(gran);
        if (!SurveyProbe.setLevel(gui, dz)) {
            status.settext("Could not set the level - see logs/survey.log.");
            return;
        }
        SurveyPlan.SurveySpec match = matching(open);
        status.settext(match == null
            ? "Set to dz " + dz + ". (This survey is not one of the planned rectangles.)"
            : "Set survey " + match.index + " to dz " + dz + "; expect "
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
        double carry = 0;
        for (SurveyPlan.Transfer t : plan.transfers)
            carry += t.amount;
        status.settext(String.format("%d surveys, all set to dz %d. %,.0f units to carry between them.",
            plan.surveys.size(), plan.targetDz(1.0f), carry));
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
        List<SurveyPlan.SurveySpec> order = plan.order();
        for (SurveyPlan.SurveySpec spec : order) {
            final SurveyPlan.SurveySpec s = spec;
            Coord sz = s.tiles.br.sub(s.tiles.ul);
            boolean held = SurveyPlanStore.taken(s.index);

            list.cont.add(new Label(String.format("%2d.  %s  %dx%d",
                s.index, s.tiles.ul, sz.x, sz.y)), new Coord(0, y + UI.scale(3)));
            list.cont.add(new Label(balance(s.net)), new Coord(UI.scale(210), y + UI.scale(3)));

            /* Surpluses are dug first and their piles feed the shortfalls, so saying which is
             * which on the row is what makes the order legible without reading the transfer list. */
            list.cont.add(new Label(s.net > 0 ? "needs soil" : "digs out"),
                new Coord(UI.scale(300), y + UI.scale(3)));

            if (held) {
                list.cont.add(new Label("taken"), new Coord(UI.scale(390), y + UI.scale(3)));
            } else {
                list.cont.add(new Button(UI.scale(56), "Claim") {
                    public void click() {
                        if (!SurveyPlanStore.claim(s.index))
                            status.settext("Survey " + s.index + " was just taken by someone else.");
                        rebuild = true;
                    }
                }, new Coord(UI.scale(386), y));
            }
            y += UI.scale(22);
        }
    }

    public void tick(double dt) {
        if (rebuild) {
            rebuild = false;
            refresh();
        }
        super.tick(dt);
    }

    /**
     * Releases every claim this window took.
     *
     * Claims lapse on their own after {@code WorkClaims.TTL_MS}, but leaving sixteen of them to
     * time out means a crew that closes the window sees the whole list as taken for the next half
     * minute.
     */
    public void stop() {
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
