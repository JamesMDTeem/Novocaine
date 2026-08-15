package haven.automated.study;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out the Study Plan: which curiosities, out of everything visible in the open containers,
 * to put in the Study Grid to get the most LP per hour out of the Attention pool.
 *
 * This class deliberately imports nothing from haven. No Widget, no Coord, no GItem. The whole
 * feature's arithmetic lives here and the window is a view of it, which means the part that can
 * actually be wrong can be reasoned about — and later tested — without standing up a UI. Every
 * type it needs (Curio, Group, Placement, Plan) is defined here as plain data for the same reason:
 * a haven.Coord in a signature would be a small dependency with no upside.
 *
 * The selection is greedy, not exact. Curiosities are ranked by LP/Hour per point of Mental Weight
 * and taken in that order while both budgets hold — Attention, and physical room in the grid. A
 * knapsack solver would occasionally beat this by a few LP/Hour, at the cost of being something
 * nobody can check by eye. Greedy has the property that the ranking IS the grab order: take rows
 * top-down until the cut line and you have built the plan.
 */
public class StudyPlanner {
    /** Study Grid size assumed when the real one can't be read (the character sheet was never opened). */
    public static final int FALLBACK_GRID_SIZE = 4;

    /** One curiosity, reduced to the numbers the plan is made of. */
    public static class Curio {
        public final String name;
        public final int lp;
        public final int mentalWeight;
        /** Full study time in seconds — see the note on lpPerHour. */
        public final int studyTime;
        public final int lpPerHour;
        public final int slotsWide, slotsHigh;

        public Curio(String name, int lp, int mentalWeight, int studyTime, int lpPerHour,
                     int slotsWide, int slotsHigh) {
            this.name = name;
            this.lp = lp;
            this.mentalWeight = mentalWeight;
            this.studyTime = studyTime;
            this.lpPerHour = lpPerHour;
            this.slotsWide = Math.max(1, slotsWide);
            this.slotsHigh = Math.max(1, slotsHigh);
        }

        /**
         * What the ranking is on: LP/Hour bought per point of Attention spent. A curiosity with no
         * Mental Weight costs nothing to study and so is never worth passing over — it sorts above
         * everything that does have a cost.
         */
        public double attentionEfficiency() {
            if (mentalWeight <= 0)
                return Double.MAX_VALUE;
            return (double) lpPerHour / (double) mentalWeight;
        }

        public int slots() {
            return slotsWide * slotsHigh;
        }

        /**
         * Two copies group together only if every number matches, not just the name. Quality moves
         * LP and study time, so two things both called "Cave Clay" can be genuinely different
         * curiosities; merging them would report a plan the player cannot actually build.
         */
        public boolean sameAs(Curio o) {
            return name.equals(o.name) && lp == o.lp && mentalWeight == o.mentalWeight
                    && studyTime == o.studyTime && lpPerHour == o.lpPerHour
                    && slotsWide == o.slotsWide && slotsHigh == o.slotsHigh;
        }
    }

    /** A row of the plan: one kind of curiosity, however many copies were found. */
    public static class Group {
        public final Curio curio;
        /** How many copies exist across all the open containers. Display only — see {@link #selected}. */
        public final int available;
        /**
         * Whether to take one. Not a count: the game refuses to study two curiosities of the same
         * kind at once, so owning seven Toy Chariots still means studying exactly one.
         */
        public boolean selected;
        /**
         * Total Mental Weight committed once this row's copy is in the grid, or -1 for a row that
         * contributed nothing — a running total that didn't move is noise, not data.
         */
        public int cumulativeWeight = -1;

        Group(Curio curio, int available) {
            this.curio = curio;
            this.available = available;
        }
    }

    /** Where one selected copy sits in the Study Grid. Slot coordinates, not pixels. */
    public static class Placement {
        public final int x, y, w, h;
        /** Index into {@link Plan#groups}, so the mini-grid can colour a cell by what occupies it. */
        public final int group;

        Placement(int x, int y, int w, int h, int group) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.group = group;
        }
    }

    public static class Plan {
        /** Every group found, best first. Selected rows come first only incidentally — sort order is efficiency. */
        public final List<Group> groups;
        public final List<Placement> placements;
        public final int budget;
        public final int usedWeight;
        public final int totalLp;
        public final int totalLpPerHour;
        public final int gridWidth, gridHeight;

        Plan(List<Group> groups, List<Placement> placements, int budget, int usedWeight,
             int totalLp, int totalLpPerHour, int gridWidth, int gridHeight) {
            this.groups = groups;
            this.placements = placements;
            this.budget = budget;
            this.usedWeight = usedWeight;
            this.totalLp = totalLp;
            this.totalLpPerHour = totalLpPerHour;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
        }

        public boolean isEmpty() {
            return groups.isEmpty();
        }

        /** How many rows made the cut, i.e. where to stop grabbing. */
        public int selectedRows() {
            int n = 0;
            for (Group g : groups) {
                if (g.selected)
                    n++;
            }
            return n;
        }
    }

    /**
     * @param curios every curiosity found in the open containers, one entry per physical copy
     * @param budget the Attention pool — full Intelligence, ignoring whatever is already studying
     * @param gridWidth  Study Grid width in slots
     * @param gridHeight Study Grid height in slots
     */
    public static Plan plan(List<Curio> curios, int budget, int gridWidth, int gridHeight) {
        if (gridWidth <= 0)
            gridWidth = FALLBACK_GRID_SIZE;
        if (gridHeight <= 0)
            gridHeight = FALLBACK_GRID_SIZE;
        if (budget < 0)
            budget = 0;

        List<Group> groups = group(curios);
        groups.sort(ORDER);

        boolean[][] occupied = new boolean[gridHeight][gridWidth];
        List<Placement> placements = new ArrayList<>();
        Set<String> alreadyStudying = new HashSet<>();
        int usedWeight = 0, totalLp = 0, totalLpPerHour = 0;

        for (int gi = 0; gi < groups.size(); gi++) {
            Group g = groups.get(gi);
            /* One per kind. The game will not study two curiosities of the same name at once, so a
             * second copy is not a choice the player has. Matching on name rather than on the full
             * stat tuple is deliberate: two qualities of Toy Chariot are still Toy Chariots, and
             * since the better one sorts first, it is the one that gets in. */
            if (alreadyStudying.contains(g.curio.name))
                continue;
            if (usedWeight + g.curio.mentalWeight > budget)
                continue;
            int[] spot = firstFit(occupied, gridWidth, gridHeight, g.curio.slotsWide, g.curio.slotsHigh);
            if (spot == null)
                continue;
            /* Skip rather than stop: a row that doesn't fit says nothing about the cheaper or
             * smaller rows below it, and either budget may still have room for one of those. */
            occupy(occupied, spot[0], spot[1], g.curio.slotsWide, g.curio.slotsHigh);
            placements.add(new Placement(spot[0], spot[1], g.curio.slotsWide, g.curio.slotsHigh, gi));
            usedWeight += g.curio.mentalWeight;
            totalLp += g.curio.lp;
            totalLpPerHour += g.curio.lpPerHour;
            alreadyStudying.add(g.curio.name);
            g.selected = true;
            g.cumulativeWeight = usedWeight;
        }

        return new Plan(partition(groups, placements), placements, budget, usedWeight, totalLp,
                totalLpPerHour, gridWidth, gridHeight);
    }

    /**
     * Moves the selected rows to the top, keeping the ranking within each half.
     *
     * Selection stopped being a prefix of the ranking the moment one-per-kind arrived: a row can be
     * passed over for a reason that does not apply to the rows beneath it — a kind already taken,
     * too heavy, no room — and the loop above deliberately skips rather than stops. Left in ranking
     * order the list interleaves taken and untaken rows, so a cut line drawn at the first untaken
     * one has selected rows below it and says something false.
     *
     * Partitioning restores the property the whole display rests on: everything above the line is
     * what you take, in the order you take it. It also makes the running ΣMW column read straight
     * down instead of stepping over blanks.
     *
     * Placements carry an index into this list, so they are rewritten to match.
     */
    private static List<Group> partition(List<Group> ranked, List<Placement> placements) {
        List<Group> ordered = new ArrayList<>(ranked.size());
        int[] remap = new int[ranked.size()];
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).selected) {
                remap[i] = ordered.size();
                ordered.add(ranked.get(i));
            }
        }
        for (int i = 0; i < ranked.size(); i++) {
            if (!ranked.get(i).selected) {
                remap[i] = ordered.size();
                ordered.add(ranked.get(i));
            }
        }
        for (int i = 0; i < placements.size(); i++) {
            Placement p = placements.get(i);
            placements.set(i, new Placement(p.x, p.y, p.w, p.h, remap[p.group]));
        }
        return ordered;
    }

    /**
     * Efficiency first, then raw LP/Hour to break ties between equally efficient curiosities in
     * favour of finishing sooner, then name so the list stops shuffling between refreshes.
     */
    private static final Comparator<Group> ORDER =
            Comparator.comparingDouble((Group g) -> -g.curio.attentionEfficiency())
                    .thenComparingInt(g -> -g.curio.lpPerHour)
                    .thenComparing(g -> g.curio.name);

    private static List<Group> group(List<Curio> curios) {
        List<Curio> kinds = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (Curio c : curios) {
            int at = -1;
            for (int i = 0; i < kinds.size(); i++) {
                if (kinds.get(i).sameAs(c)) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                kinds.add(c);
                counts.add(1);
            } else {
                counts.set(at, counts.get(at) + 1);
            }
        }
        List<Group> groups = new ArrayList<>(kinds.size());
        for (int i = 0; i < kinds.size(); i++)
            groups.add(new Group(kinds.get(i), counts.get(i)));
        return groups;
    }

    /**
     * Row-major first fit: left to right, top to bottom, which is the order a player fills a grid
     * by hand. The plan is placed into an empty grid by design, so this is a layout to copy rather
     * than a claim about where things will land next to what is already in there.
     */
    private static int[] firstFit(boolean[][] occupied, int gridWidth, int gridHeight, int w, int h) {
        if (w > gridWidth || h > gridHeight)
            return null;
        for (int y = 0; y <= gridHeight - h; y++) {
            for (int x = 0; x <= gridWidth - w; x++) {
                if (fits(occupied, x, y, w, h))
                    return new int[] {x, y};
            }
        }
        return null;
    }

    private static boolean fits(boolean[][] occupied, int x, int y, int w, int h) {
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                if (occupied[y + dy][x + dx])
                    return false;
            }
        }
        return true;
    }

    private static void occupy(boolean[][] occupied, int x, int y, int w, int h) {
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++)
                occupied[y + dy][x + dx] = true;
        }
    }
}
