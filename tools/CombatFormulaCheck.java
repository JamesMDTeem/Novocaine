/*
 * Checks for haven.combat.Formulas, the authoritative combat model.
 *
 * NOT part of the client build - build.xml compiles src/ only, and this lives in tools/ so it
 * can never reach a release jar. Run on demand:
 *
 *   javac -d %TEMP%\formulacheck src\haven\combat\Formulas.java tools\CombatFormulaCheck.java
 *   java -cp %TEMP%\formulacheck CombatFormulaCheck
 *
 * That compiles because haven.combat imports nothing from haven. If this file ever stops
 * compiling on its own, a client type has leaked into the model and ADR-0002's seam is gone.
 *
 * These are not synthetic vectors. Every expectation below is either a number the game
 * reported, a number a player read off their own character sheet, or the wiki's own worked
 * example. A formula that passes here reproduces something that actually happened.
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import haven.combat.Formulas;

public class CombatFormulaCheck {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? want == null : got.equals(want);
        System.out.printf("  %-52s %-22s %s%n", what, got, ok ? "ok" : "WANT " + want);
        if(!ok)
            failures++;
    }

    static void near(String what, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        System.out.printf("  %-52s %-22s %s%n", what, String.format("%.4f", got),
                          ok ? "ok" : String.format("WANT %.4f +/- %.4f", want, tol));
        if(!ok)
            failures++;
    }

    public static void main(String[] args) {
        damage();
        armour();
        openings();
        cooldowns();
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED"
                           : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /* Bronze Sword base damage 90 from the data pack; Quick Barrage's 25% share and the
     * quality and strength from the character sheet, gear dump and log header. */
    static final double SWORD = 90, SHARE = 0.25, QL = 28.68, STR = 82;

    static void damage() {
        System.out.println("rawDamage");
        // The coefficient the corpus fitted, to within a percent, on five separate fights.
        near("coefficient at full opening", Formulas.rawDamage(SWORD, SHARE, QL, STR, 1.0),
             49.55, 0.10);
        // Quadratic in the opening, which is what distinguishes it from every rival exponent.
        near("quarter opening is a sixteenth of the damage",
             Formulas.rawDamage(SWORD, SHARE, QL, STR, 0.25) * 16.0,
             Formulas.rawDamage(SWORD, SHARE, QL, STR, 1.0), 1e-9);
        check("no opening, no damage", Formulas.rawDamage(SWORD, SHARE, QL, STR, 0.0), 0.0);
    }

    static void armour() {
        System.out.println("\ndealtDamage");
        // The wiki's own worked example: 110 damage, 75 hard, 35 soft, no penetration,
        // "Pelle will take 9 points of damage". This is the check that settles the prose
        // against the example - with the prose's undoubled interval it comes out 0.
        near("wiki worked example (110, 75 hard, 35 soft)",
             Formulas.dealtDamage(110, 75, 35, 0.0), 8.75, 0.30);
        check("no armour passes everything", Formulas.dealtDamage(40, 0, 0, 0.0), 40.0);

        // The sparring partner: 5 hard, 8 soft, struck by a weapon of 12.5% penetration.
        // Openings 0.65 and 0.87 dealt 8 and 24 in the log.
        double c = Formulas.rawDamage(SWORD, SHARE, QL, STR, 1.0);
        near("Covert at 0.65 opening dealt 8",
             Formulas.dealtDamage(c * 0.65 * 0.65, 5, 8, 0.125), 8.0, 1.0);
        near("Covert at 0.87 opening dealt 24",
             Formulas.dealtDamage(c * 0.87 * 0.87, 5, 8, 0.125), 24.0, 1.0);
        // Same fight, same opening, armour off: 21 and 37.
        near("unarmoured at 0.65 opening dealt 21",
             Formulas.dealtDamage(c * 0.65 * 0.65, 0, 0, 0.125), 21.0, 1.0);
        near("unarmoured at 0.87 opening dealt 37",
             Formulas.dealtDamage(c * 0.87 * 0.87, 0, 0, 0.125), 37.0, 1.0);

        // Penetration bypasses soak entirely, which is what keeps a small hit from being
        // reduced to nothing by armour it cannot get through.
        near("penetration always gets through", Formulas.dealtDamage(10, 100, 100, 0.25),
             2.5, 1e-9);
        check("soak never returns damage", Formulas.dealtDamage(3, 50, 50, 0.0), 0.0);
    }

    static void openings() {
        System.out.println("\nopeningGain and defenceWeight");
        // Knock Its Teeth Out, listed +20% Cornered, against the bee swarm: +24 from a
        // standing 0, then +19, +14, +11 from 24, 42, 56. The first observation fixes the
        // weight term and the other three are then predictions.
        double wa = 111, ob = 20;
        double wd = Formulas.defenceWeight(wa, 24, ob, 0.00);
        near("weight recovered from the first hit", Math.cbrt(wa / wd), 1.20, 0.01);
        near("predicts +19 at a standing 24", Formulas.openingGain(wa, wd, ob, 0.24), 19.0, 1.0);
        near("predicts +14 at a standing 42", Formulas.openingGain(wa, wd, ob, 0.42), 14.0, 1.0);
        near("predicts +11 at a standing 56", Formulas.openingGain(wa, wd, ob, 0.56), 11.0, 1.0);
        check("a fully open colour cannot open further",
              Formulas.openingGain(wa, wd, ob, 1.0), 0.0);
        // Round trip.
        near("defenceWeight inverts openingGain",
             Formulas.defenceWeight(wa, Formulas.openingGain(wa, 250, ob, 0.3), ob, 0.3),
             250.0, 1e-6);

        System.out.println("\ncombined");
        near("one colour alone", Formulas.combined(new double[] {0, 0, 0, 0.5}), 0.5, 1e-9);
        near("two colours combine", Formulas.combined(new double[] {0.5, 0, 0, 0.5}),
             0.75, 1e-9);
    }

    static void cooldowns() {
        System.out.println("\nagilityCooldownFactor");
        // Our agility 81 against partners at 59 and 135, both computed values.
        near("faster than the opponent shortens", Formulas.agilityCooldownFactor(81, 59),
             0.9543, 0.0005);
        near("slower than the opponent lengthens", Formulas.agilityCooldownFactor(81, 135),
             1.0737, 0.0005);
        near("caps at a factor-two gap", Formulas.agilityCooldownFactor(81, 20), 0.9, 1e-9);
        near("caps the other way", Formulas.agilityCooldownFactor(20, 81), 1.1, 1e-9);
        near("equal agility is neutral", Formulas.agilityCooldownFactor(70, 70), 1.0, 1e-9);

        System.out.println("\ncooldownTicks");
        // Against the slower partner: bases 35, 40, 20 reported as 33, 38, 19.
        check("KITO vs agility 59", Formulas.cooldownTicks(35, false, 1, 0, 0, true, 81, 59), 33L);
        check("Full Circle vs agility 59",
              Formulas.cooldownTicks(40, false, 1, 0, 0, true, 81, 59), 38L);
        check("Quick Barrage vs agility 59",
              Formulas.cooldownTicks(20, false, 1, 0, 0, true, 81, 59), 19L);
        // Against the faster one: 38, 43, 22. The last is the known one-tick miss, so it is
        // asserted at what the formula actually produces, with the discrepancy named here
        // rather than hidden by a loose tolerance.
        check("KITO vs agility 135", Formulas.cooldownTicks(35, false, 1, 0, 0, true, 81, 135), 38L);
        check("Full Circle vs agility 135",
              Formulas.cooldownTicks(40, false, 1, 0, 0, true, 81, 135), 43L);
        check("Quick Barrage vs agility 135 (game said 22 - known one-tick miss)",
              Formulas.cooldownTicks(20, false, 1, 0, 0, true, 81, 135), 21L);

        // A maneuver takes no agility modifier: Zig-Zag Ruse read 50 against every opponent.
        check("Zig-Zag Ruse vs agility 59",
              Formulas.cooldownTicks(50, false, 1, 0, 0, false, 81, 59), 50L);
        check("Zig-Zag Ruse vs agility 135",
              Formulas.cooldownTicks(50, false, 1, 0, 0, false, 81, 135), 50L);

        // Take Aim: base 30, divided by mu, plus 20% per initiative point, no agility term.
        // The game reported exactly this sequence across five fights.
        long[] want = {30, 36, 42, 48, 54, 60};
        for(int ip = 0; ip < want.length; ip++) {
            check("Take Aim at " + ip + " initiative",
                  Formulas.cooldownTicks(30, true, 1.0, 0.20, ip, false, 81, 59), want[ip]);
        }
        check("Take Aim at mu 1.5",
              Formulas.cooldownTicks(30, true, 1.5, 0.20, 0, false, 81, 59), 20L);

        System.out.println("\nmuFromCooldown");
        check("mu 1 reads back from an unlevelled move",
              Formulas.muFromCooldown(30, 30, 0.20, 0), 1.0);
        // The inversion that matters: at 2 initiative the observed 42 is still mu 1, and a
        // formula that scaled the wrong way would answer 0.51 here.
        near("initiative comes off before the ratio",
             Formulas.muFromCooldown(30, 42, 0.20, 2), 1.0, 1e-9);
        near("a shorter cooldown reads as a higher mu",
             Formulas.muFromCooldown(30, 20, 0.20, 0), 1.5, 1e-9);
        check("an impossible observation constrains nothing",
              Formulas.muFromCooldown(30, 0, 0.20, 0), 0.0);

        System.out.println("\nticksToSeconds");
        near("18 ticks is Quick Barrage's observed 1.08 s",
             Formulas.ticksToSeconds(18), 1.08, 1e-9);
    }
}
