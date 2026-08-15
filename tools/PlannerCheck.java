/*
 * Hand-computed scenarios checked against haven.automated.study.StudyPlanner.
 *
 * NOT part of the client build - build.xml compiles src/ only, and this file lives in tools/ so it
 * can never be pulled into a release jar. The fork has no test framework and this does not add one;
 * it is a single file with a main(), run on demand:
 *
 *   javac -d %TEMP%\plannercheck src\haven\automated\study\StudyPlanner.java tools\PlannerCheck.java
 *   java -cp %TEMP%\plannercheck PlannerCheck
 *
 * That works because StudyPlanner deliberately imports nothing from haven - see its class comment.
 * If this file ever stops compiling on its own, something has leaked a UI type into the planner and
 * the seam the whole feature was designed around is gone.
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import haven.automated.study.StudyPlanner;
import haven.automated.study.StudyPlanner.Curio;
import haven.automated.study.StudyPlanner.Group;
import haven.automated.study.StudyPlanner.Placement;
import haven.automated.study.StudyPlanner.Plan;

import java.util.ArrayList;
import java.util.List;

public class PlannerCheck {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? want == null : got.equals(want);
        System.out.printf("  %-46s %-18s %s%n", what, got, ok ? "ok" : "WANT " + want);
        if (!ok)
            failures++;
    }

    static void add(List<Curio> l, int n, Curio c) {
        for (int i = 0; i < n; i++)
            l.add(c);
    }

    public static void main(String[] args) {
        scenarioOnePerKind();
        scenarioMixed();
        scenarioFootprintBlocks();
        scenarioZeroWeight();
        scenarioEmptyAndDegenerate();
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /*
     * The rule that matters most: the game refuses to study two curiosities of the same kind at
     * once. Owning seven Toy Chariots is still one Toy Chariot in the grid, and a second quality
     * of the same name is the same kind - the better one wins and the other drops below the cut.
     */
    static void scenarioOnePerKind() {
        System.out.println("one of each kind only");
        List<Curio> c = new ArrayList<>();
        add(c, 7, new Curio("Toy Chariot", 300, 3, 3600, 300, 1, 1));
        add(c, 2, new Curio("Toy Chariot", 600, 3, 3600, 600, 1, 1)); // a better quality, same kind
        add(c, 4, new Curio("Cave Clay", 100, 1, 3600, 100, 1, 1));
        Plan p = StudyPlanner.plan(c, 1000, 4, 4);

        check("best Toy Chariot first", p.groups.get(0).curio.lpPerHour, 600);
        check("best Toy Chariot taken", p.groups.get(0).selected, true);
        check("owned count still reported", p.groups.get(0).available, 2);
        check("worse Toy Chariot refused", selectedOf(p, "Toy Chariot", 300), false);
        check("Cave Clay taken", selectedOf(p, "Cave Clay", 100), true);
        check("exactly one placement per kind", p.placements.size(), 2);
        check("weight is one of each, not all nine", p.usedWeight, 4);
        check("LP is one of each", p.totalLp, 700);
        check("budget nowhere near spent", p.usedWeight < p.budget, true);
        /* Ranked order is [TC600, TC300, CaveClay] - the skipped duplicate sits between two taken
         * rows. Partitioning must lift Cave Clay above it or the cut line lies. */
        check("taken rows partitioned to the top", names(p), "[Toy Chariot, Cave Clay, Toy Chariot]");
        check("no taken row below an untaken one", selectedFormPrefix(p), true);
        check("placements still point at taken rows", placementsPointAtSelectedRows(p), true);
    }

    /* Budget 100, grid 4x4. Alpha eff 100 (lph 1000), Gamma eff 100 (lph 100, loses the tie),
     * Beta eff 40, Delta eff 12.5 and too heavy to ever fit. One copy of each at most. */
    static void scenarioMixed() {
        System.out.println("\nranking, attention cutoff, skip-don't-stop");
        List<Curio> c = new ArrayList<>();
        add(c, 3, new Curio("Alpha", 1000, 10, 3600, 1000, 1, 1));
        add(c, 2, new Curio("Beta", 2000, 50, 3600, 2000, 2, 2));
        add(c, 5, new Curio("Gamma", 100, 1, 3600, 100, 1, 1));
        add(c, 1, new Curio("Delta", 5000, 200, 7200, 2500, 1, 1));
        Plan p = StudyPlanner.plan(c, 100, 4, 4);

        check("row order", names(p), "[Alpha, Gamma, Beta, Delta]");
        check("Alpha taken", p.groups.get(0).selected, true);
        check("Gamma taken", p.groups.get(1).selected, true);
        check("Beta taken", p.groups.get(2).selected, true);
        check("Delta refused (too heavy)", p.groups.get(3).selected, false);
        check("Delta cumulative is blank", p.groups.get(3).cumulativeWeight, -1);
        check("Alpha cumulative", p.groups.get(0).cumulativeWeight, 10);
        check("Gamma cumulative", p.groups.get(1).cumulativeWeight, 11);
        check("Beta cumulative", p.groups.get(2).cumulativeWeight, 61);
        check("used weight", p.usedWeight, 61);
        check("within budget", p.usedWeight <= p.budget, true);
        check("total LP", p.totalLp, 3100);
        check("total LP/h", p.totalLpPerHour, 3100);
        check("placements", p.placements.size(), 3);
        check("selected rows (the cut line)", p.selectedRows(), 3);
        check("no overlapping placements", noOverlap(p), true);
        check("all placements inside grid", inBounds(p), true);
        check("no taken row below an untaken one", selectedFormPrefix(p), true);
        check("placements point at taken rows", placementsPointAtSelectedRows(p), true);
    }

    /*
     * A heavy row that cannot fit must not end the plan - the cheaper rows under it are still
     * viable. Whale is unaffordable, yet everything below it still gets taken.
     */
    static void scenarioFootprintBlocks() {
        System.out.println("\ngrid space runs out before the budget does");
        List<Curio> c = new ArrayList<>();
        for (int i = 1; i <= 6; i++)
            add(c, 1, new Curio("Big" + i, 900, 1, 3600, 900, 2, 2));
        add(c, 1, new Curio("Small", 10, 1, 3600, 10, 1, 1));
        Plan p = StudyPlanner.plan(c, 100000, 4, 4);

        check("four 2x2 fill a 4x4", countSelected(p), 4);
        check("Small refused, no room left", selectedOf(p, "Small", 10), false);
        check("no overlapping placements", noOverlap(p), true);

        /* A 5-wide grid leaves a 1-wide strip - room for the Small the 4x4 had no space for. */
        Plan wide = StudyPlanner.plan(c, 100000, 5, 4);
        check("5-wide fits four Big plus Small", countSelected(wide), 5);
        check("Small now taken", selectedOf(wide, "Small", 10), true);

        /* Unaffordable heavy row, then affordable light ones underneath it. */
        List<Curio> mixed = new ArrayList<>();
        add(mixed, 1, new Curio("Whale", 90000, 9999, 3600, 90000, 1, 1));
        add(mixed, 1, new Curio("Minnow", 5, 1, 3600, 5, 1, 1));
        Plan skip = StudyPlanner.plan(mixed, 10, 4, 4);
        check("Whale refused", selectedOf(skip, "Whale", 90000), false);
        check("Minnow still taken", selectedOf(skip, "Minnow", 5), true);
        /* Whale outranks Minnow but cannot be afforded, so the taken row must be lifted above it. */
        check("Minnow lifted above the refused Whale", names(skip), "[Minnow, Whale]");
        check("no taken row below an untaken one", selectedFormPrefix(skip), true);
        check("prefix holds when the grid fills too", selectedFormPrefix(p), true);
        check("placements point at taken rows", placementsPointAtSelectedRows(p), true);
    }

    /* A curiosity that costs no Attention should never be passed over for one that does. */
    static void scenarioZeroWeight() {
        System.out.println("\nzero mental weight outranks everything");
        List<Curio> c = new ArrayList<>();
        add(c, 1, new Curio("Free", 5, 0, 3600, 5, 1, 1));
        add(c, 1, new Curio("Costly", 9999, 1, 3600, 9999, 1, 1));
        Plan p = StudyPlanner.plan(c, 0, 4, 4);

        check("row order", names(p), "[Free, Costly]");
        check("Free taken on a zero budget", p.groups.get(0).selected, true);
        check("Costly refused on a zero budget", p.groups.get(1).selected, false);
    }

    static void scenarioEmptyAndDegenerate() {
        System.out.println("\nempty and degenerate input");
        Plan empty = StudyPlanner.plan(new ArrayList<>(), 100, 4, 4);
        check("empty plan reports empty", empty.isEmpty(), true);
        check("empty plan has no placements", empty.placements.size(), 0);

        List<Curio> c = new ArrayList<>();
        add(c, 1, new Curio("Huge", 100, 1, 3600, 100, 9, 9));
        Plan tooBig = StudyPlanner.plan(c, 100, 4, 4);
        check("item larger than the grid is refused", tooBig.groups.get(0).selected, false);

        Plan zeroGrid = StudyPlanner.plan(c, 100, 0, 0);
        check("zero grid falls back to 4x4", zeroGrid.gridWidth + "x" + zeroGrid.gridHeight, "4x4");

        Plan negBudget = StudyPlanner.plan(c, -50, 4, 4);
        check("negative budget clamped to 0", negBudget.budget, 0);
    }

    /**
     * The invariant the whole display rests on: every taken row comes before every untaken one, so
     * the red cut line separates them honestly. This is what broke when one-per-kind made selection
     * stop being a prefix of the ranking.
     */
    static boolean selectedFormPrefix(Plan p) {
        boolean seenUnselected = false;
        for (Group g : p.groups) {
            if (g.selected && seenUnselected)
                return false;
            if (!g.selected)
                seenUnselected = true;
        }
        return true;
    }

    /** Placements index into the row list; after partitioning they must still point at taken rows. */
    static boolean placementsPointAtSelectedRows(Plan p) {
        for (Placement pl : p.placements) {
            if (pl.group < 0 || pl.group >= p.groups.size())
                return false;
            if (!p.groups.get(pl.group).selected)
                return false;
        }
        return true;
    }

    static String names(Plan p) {
        List<String> n = new ArrayList<>();
        for (Group g : p.groups)
            n.add(g.curio.name);
        return n.toString();
    }

    static boolean selectedOf(Plan p, String name, int lp) {
        for (Group g : p.groups) {
            if (g.curio.name.equals(name) && g.curio.lp == lp)
                return g.selected;
        }
        throw new IllegalStateException("no such group: " + name + "/" + lp);
    }

    static int countSelected(Plan p) {
        int n = 0;
        for (Group g : p.groups) {
            if (g.selected)
                n++;
        }
        return n;
    }

    static boolean noOverlap(Plan p) {
        boolean[][] seen = new boolean[p.gridHeight][p.gridWidth];
        for (Placement pl : p.placements) {
            for (int y = pl.y; y < pl.y + pl.h; y++) {
                for (int x = pl.x; x < pl.x + pl.w; x++) {
                    if (seen[y][x])
                        return false;
                    seen[y][x] = true;
                }
            }
        }
        return true;
    }

    static boolean inBounds(Plan p) {
        for (Placement pl : p.placements) {
            if (pl.x < 0 || pl.y < 0 || pl.x + pl.w > p.gridWidth || pl.y + pl.h > p.gridHeight)
                return false;
        }
        return true;
    }
}
