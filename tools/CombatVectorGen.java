/*
 * Generates data/combat/golden-vectors.json from haven.combat.Formulas.
 *
 *   javac -d %TEMP%\vecgen src\haven\combat\Formulas.java src\haven\combat\log\JsonObj.java tools\CombatVectorGen.java
 *   java -cp %TEMP%\vecgen CombatVectorGen
 *
 * ADR-0002 makes Java authoritative and Python the follower, and this is the seam between
 * them. The vectors are produced from the Java implementation and the Python evaluator is
 * checked against them by tools/combat/model_check.py - so the two cannot drift silently, and
 * a game patch that changes a constant shows up as a failing check rather than as a bot that
 * is quietly wrong.
 *
 * Regenerate deliberately, after a change to Formulas, and review the diff: a vector file that
 * changes without an intended model change is the alarm this exists to raise.
 */

import haven.combat.Formulas;
import haven.combat.log.JsonObj;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class CombatVectorGen {
    static final List<String> out = new ArrayList<String>();

    static String args(double... vs) {
        StringBuilder b = new StringBuilder("[");
        for(int i = 0; i < vs.length; i++) {
            if(i > 0)
                b.append(',');
            b.append(JsonObj.num(vs[i]));
        }
        return(b.append(']').toString());
    }

    static void vec(String fn, String argsJson, double want) {
        out.add(new JsonObj().put("fn", fn).raw("args", argsJson)
                .raw("want", JsonObj.num(want)).end());
    }

    public static void main(String[] a) throws IOException {
        /* Spreads chosen to cross every branch: below and above the soft-soak interval, both
         * agility caps and the neutral point, zero and full openings. */
        double[] ql = {1, 10, 28.68, 60, 100};
        double[] str = {10, 45, 82, 150, 400};
        double[] op = {0, 0.05, 0.25, 0.5, 0.65, 0.87, 1.0};
        for(double q : ql) {
            for(double s : str) {
                for(double o : op)
                    vec("rawDamage", args(90, 0.25, q, s, o),
                        Formulas.rawDamage(90, 0.25, q, s, o));
            }
        }

        double[] raw = {0, 1, 3, 10, 21, 40, 110, 500};
        double[] hard = {0, 5, 12, 75};
        double[] soft = {0, 8, 35, 60};
        double[] pen = {0, 0.05, 0.125, 0.3, 1.0};
        for(double r : raw) {
            for(double h : hard) {
                for(double sf : soft) {
                    for(double p : pen)
                        vec("dealtDamage", args(r, h, sf, p), Formulas.dealtDamage(r, h, sf, p));
                }
            }
        }

        double[] wa = {1, 43, 81, 111, 193};
        double[] wd = {1, 4, 61, 111, 375, 0};
        double[] ob = {5, 10, 15, 20, 25};
        double[] oc = {0, 0.24, 0.5, 0.87, 1.0};
        for(double w : wa) {
            for(double d : wd) {
                for(double b : ob) {
                    for(double c : oc) {
                        vec("openingGain", args(w, d, b, c), Formulas.openingGain(w, d, b, c));
                        vec("defenceWeight", args(w, Formulas.openingGain(w, d, b, c), b, c),
                            Formulas.defenceWeight(w, Formulas.openingGain(w, d, b, c), b, c));
                    }
                }
            }
        }

        double[] agi = {0, 1, 20, 59, 81, 135, 300};
        for(double m : agi) {
            for(double f : agi)
                vec("agilityCooldownFactor", args(m, f), Formulas.agilityCooldownFactor(m, f));
        }

        /* Equalization, on its own grid so the BAND EDGES are covered exactly. The factor
         * must be 1 across the whole dead zone including both ends - skillFoe = skillMe/2
         * and skillFoe = 2*skillMe - and depart continuously outside it. A grid that
         * straddles the edges without landing on them would pass while the comparison was
         * strict where it should be inclusive. */
        double[] mine = {1, 10, 50, 58, 81, 100, 125, 400};
        for(double m : mine) {
            for(double f : new double[] {0, m / 4, m / 2 - 1, m / 2, m / 2 + 1, m * 0.75,
                                         m, m * 1.5, m * 2 - 1, m * 2, m * 2 + 1, m * 4,
                                         m * 10}) {
                vec("equalize", args(m, f), Formulas.equalize(m, f));
                vec("openingGainEq", args(m, 0.8, f, 2.5, 20, 0.25),
                    Formulas.openingGainEq(m, 0.8, f, 2.5, 20, 0.25));
            }
        }

        double[] base = {20, 30, 35, 40, 50, 80};
        double[] mu = {0.5, 1.0, 1.25, 1.5};
        double[] ipsc = {0, 0.2};
        int[] ips = {0, 1, 3, 5, 12};
        for(double b : base) {
            for(double m : mu) {
                for(double sc : ipsc) {
                    for(int ip : ips) {
                        for(int md = 0; md < 2; md++) {
                            for(int at = 0; at < 2; at++) {
                                vec("cooldownTicks",
                                    args(b, md, m, sc, ip, at, 81, 59),
                                    Formulas.cooldownTicks(b, md == 1, m, sc, ip, at == 1,
                                                           81, 59));
                            }
                        }
                        vec("muFromCooldown", args(b, b, sc, ip),
                            Formulas.muFromCooldown(b, b, sc, ip));
                    }
                }
            }
        }

        double[][] sets = {{0, 0, 0, 0}, {0.5, 0, 0, 0}, {0.5, 0, 0, 0.5},
                           {0.1, 0.2, 0.3, 0.4}, {1.0, 0.5, 0, 0}};
        for(double[] s : sets)
            vec("combined", args(s[0], s[1], s[2], s[3]), Formulas.combined(s));

        for(double t : new double[] {0, 1, 18, 35, 60, 100})
            vec("ticksToSeconds", args(t), Formulas.ticksToSeconds(t));

        StringBuilder b = new StringBuilder("[");
        for(int i = 0; i < out.size(); i++) {
            if(i > 0)
                b.append(',');
            b.append("\n  ").append(out.get(i));
        }
        b.append("\n ]");
        String json = new JsonObj()
            .put("generated_by", "tools/CombatVectorGen.java")
            .put("model", "haven.combat.Formulas")
            .put("count", out.size())
            .raw("vectors", b.toString())
            .end();
        Path p = Paths.get("data", "combat", "golden-vectors.json");
        Files.createDirectories(p.getParent());
        Files.write(p, (json + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + p + " with " + out.size() + " vectors");
    }
}
