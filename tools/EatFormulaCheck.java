/*
 * Checks the two closed forms the Eating Helper stands on, against real logs.
 *
 * NOT part of the client build - build.xml compiles src/ only, and this lives in tools/ so it can
 * never reach a release jar. Run on demand:
 *
 *   javac -d %TEMP%\eatcheck src\org\json\*.java src\haven\automated\eat\EatPlanner.java tools\EatFormulaCheck.java
 *   java -cp %TEMP%\eatcheck EatFormulaCheck [logdir]
 *
 * That compiles because haven.automated.eat.EatPlanner imports nothing from haven - the same seam
 * haven.combat.Formulas keeps, and for the same reason. If this file ever stops compiling on its
 * own, a client type has leaked into the planner.
 *
 * The default log directory is bin/eatlog, which is where EatObserver writes. Those files are the
 * evidence, not fixtures: every expectation below is checked against what the server actually sent
 * this client, so a game patch that moves either formula fails here rather than silently biasing
 * every plan. Records predating the topStat stamp are skipped and counted, not guessed at.
 *
 * The two forms:
 *
 *   gmod      = 3^(1 - 2*glut)
 *   reduction = sqrt(0.4 * gmod * topStat / m)     for the m-th distinct food of a bar
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import haven.automated.eat.EatPlanner;

public class EatFormulaCheck {
    static int failures = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  %-56s %-26s %s%n", what, detail, ok ? "ok" : "FAIL");
        if (!ok)
            failures++;
    }

    static void near(String what, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        System.out.printf("  %-56s %-26s %s%n", what, String.format("%.6f", got),
                          ok ? "ok" : String.format("WANT %.6f +/- %.6f", want, tol));
        if (!ok)
            failures++;
    }

    /** Highest distinct-food index a step is tested against; mirrors the server's own bound. */
    private static final int MAX_M = 64;
    /** How far a step's ratio may sit from 1/sqrt(m) and still count as that m. */
    private static final double RATIO_TOL = 0.005;

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "bin/eatlog");

        System.out.println("Closed forms, at points that need no log:");
        // glut 0.5 is where the multiplier crosses 1.0; it is the one value worth naming, because
        // a formula that got the exponent wrong would still pass at glut 0.
        near("gmodFor(0) = 3", EatPlanner.gmodFor(0), 3.0, 1e-12);
        near("gmodFor(0.5) = 1", EatPlanner.gmodFor(0.5), 1.0, 1e-12);
        near("gmodFor(1) = 1/3", EatPlanner.gmodFor(1.0), 1.0 / 3.0, 1e-12);
        near("varietyStep(gmod 3, top 100, m 1)", EatPlanner.varietyStep(3, 100, 1),
             Math.sqrt(120.0), 1e-12);
        // The series past the first food is the part the old sqrt(n) model got wrong, so pin it.
        double two = EatPlanner.varietyStep(3, 100, 1) + EatPlanner.varietyStep(3, 100, 2);
        near("two distinct foods = sqrt(120)*(1+1/sqrt2)", two,
             Math.sqrt(120.0) * (1 + 1 / Math.sqrt(2)), 1e-12);

        if (!Files.isDirectory(dir)) {
            System.out.println();
            System.out.println("No log directory at " + dir.toAbsolutePath()
                               + " - closed-form checks only.");
            System.out.println(failures == 0 ? "PASS" : failures + " FAILED");
            System.exit(failures == 0 ? 0 : 1);
        }

        int gmodPairs = 0;
        double gmodWorst = 0;
        int steps = 0, matched = 0, noTopStat = 0;
        double errSum = 0;
        List<Double> freshConstants = new ArrayList<>();

        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path f : files) {
                try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty())
                            continue;
                        JSONObject o;
                        try {
                            o = new JSONObject(line);
                        } catch (Exception e) {
                            continue; // a truncated last line is normal for a log being appended to
                        }

                        // The hunger curve is stamped on every record type, so check it on all of
                        // them rather than only where an eat happened to be logged. A record
                        // written before the session's first glut push carries 0/0 and says
                        // nothing; that is the unset State, not a measurement.
                        double glut = o.optDouble("glut", Double.NaN);
                        double gmod = o.optDouble("gmod", Double.NaN);
                        if (!Double.isNaN(glut) && !Double.isNaN(gmod) && !(glut == 0 && gmod == 0)) {
                            gmodPairs++;
                            gmodWorst = Math.max(gmodWorst, Math.abs(EatPlanner.gmodFor(glut) - gmod));
                        }

                        if (!"eat".equals(o.optString("type", null)))
                            continue;
                        JSONObject before = o.optJSONObject("before");
                        JSONObject after = o.optJSONObject("after");
                        if (before == null || after == null)
                            continue;
                        double top = o.optDouble("topStat", Double.NaN);
                        if (Double.isNaN(top) || top <= 0) {
                            noTopStat++;
                            continue;
                        }
                        double beforeCap = before.optDouble("cap", Double.NaN);
                        double afterCap = after.optDouble("cap", Double.NaN);
                        double beforeGmod = before.optDouble("gmod", Double.NaN);
                        if (Double.isNaN(beforeCap) || Double.isNaN(afterCap)
                            || Double.isNaN(beforeGmod) || beforeGmod <= 0)
                            continue;

                        double step = beforeCap - afterCap;
                        if (step <= 1e-9)
                            continue; // unchanged, or the bar resolving and restoring the cap

                        double unit = EatPlanner.varietyStep(beforeGmod, top, 1);
                        if (unit <= 0)
                            continue;
                        steps++;

                        double ratio = step / unit;
                        int bestM = 1;
                        double bestErr = Double.MAX_VALUE;
                        for (int m = 1; m <= MAX_M; m++) {
                            double err = Math.abs(ratio - 1.0 / Math.sqrt(m));
                            if (err < bestErr) {
                                bestErr = err;
                                bestM = m;
                            }
                        }
                        if (bestErr < RATIO_TOL) {
                            matched++;
                            errSum += Math.abs(step - EatPlanner.varietyStep(beforeGmod, top, bestM));
                        }
                        // A drop from a settled cap has m = 1 by construction, so it pins the
                        // constant with nothing else left unknown.
                        if (Math.abs(beforeCap - top) < 1e-9)
                            freshConstants.add(step * step / (beforeGmod * top));
                    }
                }
            }
        }

        System.out.println();
        System.out.println("Replayed " + dir + ":");
        if (gmodPairs > 0) {
            System.out.printf("  %-56s %d%n", "(glut, gmod) pairs checked", gmodPairs);
            // 1e-5 rather than 1e-12: these arrive as float32 over the wire.
            near("worst |3^(1-2*glut) - gmod|", gmodWorst, 0.0, 1e-5);
        } else {
            System.out.println("  no (glut, gmod) pairs in the logs - hunger curve unchecked");
        }

        if (steps > 0) {
            double rate = matched / (double) steps;
            System.out.printf("  %-56s %d%n", "cap-decrease events", steps);
            check("matched an integer m", rate >= 0.98,
                  String.format("%d/%d = %.1f%%", matched, steps, rate * 100));
            if (matched > 0)
                near("mean abs error, cap points", errSum / matched, 0.0, 5e-3);
            if (!freshConstants.isEmpty()) {
                Collections.sort(freshConstants);
                double median = freshConstants.get(freshConstants.size() / 2);
                System.out.printf("  %-56s %d%n", "fresh-bar events pinning the constant",
                                  freshConstants.size());
                near("measured constant (EatPlanner.VARIETY_CONST)", median,
                     EatPlanner.VARIETY_CONST, 5e-3);
            } else {
                System.out.println("  no fresh-bar events - constant unchecked");
            }
        } else {
            System.out.println("  no cap-decrease events in the logs - variety formula unchecked");
        }
        if (noTopStat > 0) {
            System.out.printf("  %-56s %d%n",
                              "eat records skipped (predate the topStat stamp)", noTopStat);
        }

        System.out.println();
        System.out.println(failures == 0 ? "PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
