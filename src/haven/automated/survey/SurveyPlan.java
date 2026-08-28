package haven.automated.survey;

import haven.Area;
import haven.Coord;

import java.util.ArrayList;
import java.util.List;

/**
 * A worked-out set of surveys that levels a region to one flat plane, and what each will cost.
 *
 * Plain data: {@link SurveyPlanner} produces it, {@link SurveyPlanStore} persists it, and the
 * window renders it. Nothing here reaches into the game.
 *
 * <p>{@link #targetZ} is in raw client z and is the level EVERY survey is set to - not each
 * survey's own mean, which would terrace the region rather than flatten it. Converting it to the
 * survey window's quantised units needs the server-supplied {@code gran}, which is why
 * {@link #targetDz} takes it as an argument instead of the plan storing one.
 */
public class SurveyPlan {
    /** North-west corner of the region, in absolute tile coordinates. */
    public final Coord ul;
    /** The one level every survey is set to, in raw client z. */
    public final double targetZ;
    public final List<SurveySpec> surveys;
    public final List<Transfer> transfers;

    public SurveyPlan(Coord ul, double targetZ, List<SurveySpec> surveys, List<Transfer> transfers) {
        this.ul = ul;
        this.targetZ = targetZ;
        this.surveys = surveys;
        this.transfers = transfers;
    }

    /**
     * The target in the units a survey window works in, for the {@code gran} that window reports.
     *
     * Matches what {@code LandSurvey.updmap} does to the ground it compares against -
     * {@code round(getfz(vc) * gran)} - so the plan's level and the window's reading are quantised
     * the same way and the predicted net is comparable to the displayed one.
     */
    public int targetDz(float gran) {
        return Math.round((float) (targetZ * gran));
    }

    /**
     * The work order: surveys with soil to spare first, then the ones that need it.
     *
     * A shortfall cannot be filled before the stockpiles feeding it exist, so the surpluses have to
     * be dug first. Stable within each group, so the plan's own index order survives and the list
     * reads the same way twice.
     */
    public List<SurveySpec> order() {
        List<SurveySpec> out = new ArrayList<>(surveys);
        out.sort((a, b) -> Boolean.compare(a.net > 0, b.net > 0));
        return out;
    }

    /** One survey to draw: where it goes, and how much soil it will have spare or short. */
    public static class SurveySpec {
        public final int index;
        /** The rectangle to draw, in absolute tile coordinates. */
        public final Area tiles;
        /** Positive needs soil brought in, negative has a surplus to give away. */
        public final double net;

        public SurveySpec(int index, Area tiles, double net) {
            this.index = index;
            this.tiles = tiles;
            this.net = net;
        }
    }

    /**
     * Soil moving from one survey to another.
     *
     * {@link #stockpile} is a tile inside the SOURCE survey, as near the destination as its own
     * boundary allows. That is where the surplus should be piled, so the next survey can be drawn
     * to take in that strip and let its own levelling consume the pile rather than anyone carrying
     * it further.
     */
    public static class Transfer {
        public final int from, to;
        public final double amount;
        public final Coord stockpile;

        public Transfer(int from, int to, double amount, Coord stockpile) {
            this.from = from;
            this.to = to;
            this.amount = amount;
            this.stockpile = stockpile;
        }
    }
}
