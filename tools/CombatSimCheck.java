/*
 * Checks for the combat state machine - haven.combat.Sim, Combatant and Move.
 *
 * NOT part of the client build - build.xml compiles src/ only, and this lives in tools/ so it can
 * never reach a release jar. Run on demand:
 *
 *   javac -d %TEMP%\simcheck src\haven\combat\*.java tools\CombatSimCheck.java
 *   java -cp %TEMP%\simcheck CombatSimCheck
 *
 * That compiles because haven.combat imports nothing from haven. If this file ever stops
 * compiling on its own, a client type has leaked into the model and ADR-0002's seam is gone.
 *
 * CombatFormulaCheck covers the arithmetic. This covers the sequencing: what a move reads, in
 * what order it changes things, and what it refuses. Those are the parts a formula check cannot
 * see, and they are where a simulator quietly goes wrong.
 *
 * As there, every expectation is a number the game produced. The character is the one that fought
 * the logged corpus - strength 82, agility 81, Unarmed Combat 58, Melee Combat 111, a bronze sword
 * at quality 28.68 - and the opponents are the ones it fought.
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import haven.combat.Combatant;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Sim;

public class CombatSimCheck {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? (want == null) : got.equals(want);
        System.out.printf("  %-54s %-20s %s%n", what, got, ok ? "ok" : "WANT " + want);
        if(!ok)
            failures++;
    }

    static void near(String what, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        System.out.printf("  %-54s %-20s %s%n", what, String.format("%.2f", got),
                          ok ? "ok" : String.format("WANT %.2f +/- %.2f", want, tol));
        if(!ok)
            failures++;
    }

    /**
     * Unarmed attacks penetrate 30% of armour, which the model gave as zero.
     *
     * Two sources state it - Jorb, quoted on the wiki, and DDDsDD999's combat guide - and
     * the direction of the old error is the point: zero understated unarmed damage against
     * anything armoured, so every unarmed-versus-armoured matchup this model judged came
     * out pessimistic. That is the question the project was built to answer.
     */
    static void unarmedPenetration() {
        System.out.println("\nunarmed attacks penetrate 30% of armour");
        near("the constant", Formulas.UNARMED_ARMPEN, 0.30, 1e-9);
        /* A bear's 65 hard soak against a Knock Its Teeth Out that swings 100 raw. With no
         * penetration nothing lands; with 30% of it bypassing armour outright, 30 does. */
        near("100 raw into 65 hard soak, penetrating nothing",
             Formulas.dealtDamage(100, 65, 0, 0.0), 35.0, 1e-9);
        near("  and penetrating the unarmed 30%",
             Formulas.dealtDamage(100, 65, 0, Formulas.UNARMED_ARMPEN), 35.0, 1e-9);
        /* Where it actually bites is armour big enough to stop the attack dead. */
        near("40 raw into 65 hard soak is nothing without penetration",
             Formulas.dealtDamage(40, 65, 0, 0.0), 0.0, 1e-9);
        near("  but 12 gets through with it",
             Formulas.dealtDamage(40, 65, 0, Formulas.UNARMED_ARMPEN), 12.0, 1e-9);
        check("so an unarmed attack is never fully soaked",
              Formulas.dealtDamage(10, 1000, 0, Formulas.UNARMED_ARMPEN) > 0, true);

        /* But only against armour it can penetrate. The boar check below is four logged
         * hits that soaked EXACTLY 15 from raw 18 to raw 42, which no penetration produces,
         * and the guide names the exception: some animals' armour ignores penetration
         * entirely. Both sources are right about different opponents. */
        Combatant animal = new Combatant("boar");
        animal.hp = animal.maxHp = 1000;
        animal.armHard = 15;
        animal.defenceWeight = 111;
        check("an animal's armour is penetration-immune by default", animal.penetrable, false);
        Combatant a = me();
        a.ip = 9;
        animal.openings[Formulas.RED] = 46;
        Sim.Result r = new Sim(a, animal).use(a, kito());
        near("  so the logged boar still soaks its flat 15", r.raw - r.dealt, 15.0, 1.5);

        Combatant player = new Combatant("player");
        player.hp = player.maxHp = 1000;
        player.armHard = 15;
        player.defenceWeight = 111;
        player.penetrable = true;
        player.openings[Formulas.RED] = 46;
        Combatant b = me();
        b.ip = 9;
        Sim.Result pr = new Sim(b, player).use(b, kito());
        check("  a player with the same armour soaks less", (pr.raw - pr.dealt) < 15.0, true);
        check("  and takes more through it", pr.dealt > r.dealt, true);
    }

    public static void main(String[] args) {
        beeSwarm();
        unarmedPenetration();
        boarArmour();
        takeAim();
        quickBarrage();
        refusals();
        zigZag();
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED"
                           : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /* ---- the character and its moves, exactly as the logs and the character sheet have them ---- */

    static Combatant me() {
        Combatant c = new Combatant("ZzxcuV3");
        c.str = 82; c.agi = 81; c.unarmed = 58; c.melee = 111;
        c.weaponDamage = 90; c.weaponQl = 28.68; c.weaponPen = 0.125;
        c.armHard = 5; c.armSoft = 2;
        c.hp = c.maxHp = 100;
        return(c);
    }

    /* "Knock Its Teeth Out: Initiative points: 1 / Attack weight: Unarmed * mu / Attack type:
     * Oppressive / Openings: +20% Cornered / Damage: 30 / Grievous damage: 25% / Cooldown: 35" */
    static Move kito() {
        return(Move.of("Knock Its Teeth Out").res("paginae/atk/knockteeth")
               .kind(Move.Kind.ATTACK).weight(Move.Weight.UNARMED)
               .school(Formulas.RED).opens(Formulas.RED, 20)
               .flatDamage(30).grievous(0.25).ipCost(1).cooldown(35).build());
    }

    /* "Quick Barrage: Attack weight: According to weapon * mu / Attack type: Oppressive /
     * Openings: +10% Cornered / Damage: According to weapon * 25% / Cooldown: 20 / If your
     * opponent has more than 25% of Oppressive openings, Quick Barrage also gains you 1 Point of
     * Initiative against that opponent" */
    static Move barrage() {
        return(Move.of("Quick Barrage").res("paginae/atk/barrage")
               .kind(Move.Kind.ATTACK).weight(Move.Weight.WEAPON)
               .school(Formulas.RED).opens(Formulas.RED, 10)
               .damageShare(0.25).cooldown(20)
               .ipGain(1).gainWhenAbove(Formulas.RED, 0.25).build());
    }

    /* "Take Aim gains you 1 Point of Initiative ... The cooldown of Take Aim increases by 20% for
     * each Point of Initiative you have." Cooldown: 30 / mu. */
    static Move takeAimMove() {
        return(Move.of("Take Aim").res("paginae/atk/takeaim")
               .kind(Move.Kind.MANEUVER).weight(Move.Weight.NONE)
               .cooldown(30).cooldownMu(true).ipScale(0.20).ipGain(1).build());
    }

    /* "Zig-Zag Ruse: Opponents' initiative points: +2 / Cooldown: 50" */
    static Move zigZagMove() {
        return(Move.of("Zig-Zag Ruse").res("paginae/atk/zigzag")
               .kind(Move.Kind.MANEUVER).weight(Move.Weight.NONE)
               .cooldown(50).foeIpGain(2).build());
    }

    /**
     * Four Knock Its Teeth Out against a bee swarm, run forward from the fight's real starting
     * state and never corrected back to it.
     *
     * This is the check the whole state machine exists to pass. Only one number is taken from the
     * fight after its start - the +24 the first attack opened, which fixes the swarm's defence
     * weight. Everything after that is the simulator's own trajectory: three more damage figures
     * and four more opening values, each computed from the state the previous prediction left
     * behind, so an error anywhere compounds instead of being washed out.
     */
    static void beeSwarm() {
        System.out.println("bee swarm - four Knock Its Teeth Out, simulated forward");
        Combatant a = me();
        a.ip = 4;
        Combatant bee = new Combatant("bee swarm");
        /* Recovered from the cooldowns the game reported: Knock Its Teeth Out (base 35) came back
         * at 32 ticks and Full Circle (base 40) at 36. Only a relative agility at or past the
         * factor-two cap satisfies both, so the swarm is somewhere at or below 40 and the
         * multiplier is pinned at 0.9. */
        bee.agi = 40;
        bee.hp = bee.maxHp = 1000;
        bee.armHard = bee.armSoft = 0;
        Move m = kito();
        bee.defenceWeight = Formulas.defenceWeight(a.attackWeight(m), 24, 20, 0);
        near("swarm defence weight, from the first attack's +24",
             bee.defenceWeight, 58 / 1.728, 0.01);

        Sim sim = new Sim(a, bee);
        double[] wantDamage = {0, 5, 16, 27};
        double[] wantOpening = {24, 43, 56, 67};
        int[] wantIp = {3, 2, 1, 0};
        for(int i = 0; i < 4; i++) {
            sim.advanceTo(a.readyAt);
            Sim.Result r = sim.use(a, m);
            check("attack " + (i + 1) + " is legal", r.ok, true);
            check("attack " + (i + 1) + " cooldown is the reported 32", r.cooldown, 32L);
            near("attack " + (i + 1) + " deals", r.dealt, wantDamage[i], 1.0);
            near("attack " + (i + 1) + " leaves Cornered at", bee.openings[Formulas.RED],
                 wantOpening[i], 1.0);
            check("attack " + (i + 1) + " leaves initiative at", a.ip, wantIp[i]);
        }
        /* The log's fifth move is Full Circle, not a fifth Knock Its Teeth Out - which is what
         * running out of initiative looks like from the outside. */
        sim.advanceTo(a.readyAt);
        check("a fifth is refused with no initiative left", sim.refuse(a, m),
              "needs 1 initiative, has 0");
    }

    /**
     * The same move against a boar, which wears armour.
     *
     * The openings here are the ones the log recorded before each attack rather than the
     * simulator's own, because that fight interleaves the boar's moves and this check is about
     * soak, not sequencing. What it pins is that raw damage splits into the ARM and SHP channels
     * the client draws: the boar soaked a flat 15 every time, from a raw 18 up to a raw 42, which
     * is hard soak with no soft component at all.
     */
    static void boarArmour() {
        System.out.println("\nboar - armour splits the damage the client draws");
        Combatant a = me();
        a.ip = 9;
        Combatant boar = new Combatant("boar");
        boar.agi = 81;
        boar.hp = boar.maxHp = 1000;
        boar.armHard = 15; boar.armSoft = 0;
        boar.defenceWeight = 111;
        Move m = kito();
        Sim sim = new Sim(a, boar);

        double[] opening = {46, 56, 64, 70};
        double[] wantDealt = {3, 13, 20, 27};
        for(int i = 0; i < opening.length; i++) {
            boar.openings[Formulas.RED] = opening[i];
            sim.advanceTo(a.readyAt);
            Sim.Result r = sim.use(a, m);
            near("at " + (int)opening[i] + "% Cornered, through armour", r.dealt, wantDealt[i], 1.5);
            near("at " + (int)opening[i] + "% Cornered, soaked", r.raw - r.dealt, 15.0, 1.5);
        }
    }

    /**
     * Take Aim, six times, from no initiative.
     *
     * This is the check that pins the order of operations. Take Aim's cooldown scales with the
     * initiative held and the move itself grants a point, so reading initiative after the grant
     * instead of before would produce 36, 42, 48 ... - the same sequence shifted by one, and
     * plausible enough to go unnoticed. The game reported 30, 36, 42, 48.
     */
    static void takeAim() {
        System.out.println("\nTake Aim - cooldown reads the initiative held going in");
        Combatant a = me();
        Combatant foe = new Combatant("bee swarm");
        foe.agi = 40; foe.hp = foe.maxHp = 1000; foe.defenceWeight = 100;
        Sim sim = new Sim(a, foe);
        Move m = takeAimMove();
        long[] want = {30, 36, 42, 48, 54, 60};
        for(int i = 0; i < want.length; i++) {
            sim.advanceTo(a.readyAt);
            Sim.Result r = sim.use(a, m);
            check("use " + (i + 1) + " at " + i + " initiative", r.cooldown, want[i]);
            check("use " + (i + 1) + " leaves initiative at", a.ip, i + 1);
        }
        /* A maneuver takes no relative-agility modifier, so a slower opponent changes nothing. */
        Combatant a2 = me();
        Combatant fast = new Combatant("faster");
        fast.agi = 300; fast.hp = fast.maxHp = 100; fast.defenceWeight = 100;
        Sim s2 = new Sim(a2, fast);
        check("unchanged against a far faster opponent", s2.use(a2, m).cooldown, 30L);
    }

    /**
     * Quick Barrage's conditional initiative gain.
     *
     * The corpus separates twenty-eight uses of this move without a single ambiguity: every one
     * that granted a point found the target's Cornered at 27 or above, and every one that granted
     * nothing found it at 25 or below. That fixes both halves of the rule - the threshold is
     * strictly more than 25%, and it is read before the move's own +10% lands, since a use at 25
     * would otherwise have crossed it and granted.
     */
    static void quickBarrage() {
        System.out.println("\nQuick Barrage - the gain is conditional, and read before the move opens");
        Move m = barrage();
        Combatant a = me();
        Combatant foe = new Combatant("Covert");
        foe.agi = 81; foe.hp = foe.maxHp = 100; foe.defenceWeight = 111;
        Sim sim = new Sim(a, foe);

        foe.openings[Formulas.RED] = 25;
        sim.use(a, m);
        check("at 25% Cornered, no initiative", a.ip, 0);

        a.readyAt = 0;
        foe.openings[Formulas.RED] = 27;
        sim.use(a, m);
        check("at 27% Cornered, one point", a.ip, 1);

        /* And the damage at a known opening, against the same unarmoured partner the armour
         * experiment used: the bronze sword's 90 base at 25% share, quality 28.68, strength 82. */
        Combatant b = me();
        Combatant bare = new Combatant("Covert, armour off");
        bare.agi = 81; bare.hp = bare.maxHp = 100; bare.defenceWeight = 111;
        bare.armHard = bare.armSoft = 0;
        bare.openings[Formulas.RED] = 65;
        Sim s2 = new Sim(b, bare);
        near("unarmoured at 65% Cornered dealt 21", s2.use(b, m).dealt, 21.0, 1.0);
    }

    static void refusals() {
        System.out.println("\nrefusals");
        Combatant a = me();
        Combatant foe = new Combatant("foe");
        foe.agi = 81; foe.hp = foe.maxHp = 100; foe.defenceWeight = 111;
        Sim sim = new Sim(a, foe);
        Move m = barrage();
        Sim.Result first = sim.use(a, m);
        check("the first use is legal", first.ok, true);
        check("a second in the same tick is refused", sim.use(a, m).ok, false);
        sim.advanceTo(a.readyAt - 1);
        check("still refused one tick early", sim.use(a, m).ok, false);
        sim.advanceTo(a.readyAt);
        check("legal again when the cooldown expires", sim.use(a, m).ok, true);

        Combatant dead = me();
        dead.hp = 0;
        check("a dead combatant cannot act", new Sim(dead, foe).refuse(dead, m), "dead");
    }

    /**
     * Zig-Zag Ruse hands the opponent two initiative, which is the cost of using it.
     *
     * Logged from both sides of the same pair of fights: when the character used it their
     * opponent went from 4 to 6, and when the opponent used it the character went from 1 to 3.
     */
    static void zigZag() {
        System.out.println("\nZig-Zag Ruse - the opponent gains, not the user");
        Combatant a = me();
        a.ip = 3;
        Combatant foe = new Combatant("Covert");
        foe.agi = 81; foe.hp = foe.maxHp = 100; foe.defenceWeight = 111;
        foe.ip = 4;
        Sim sim = new Sim(a, foe);
        Sim.Result r = sim.use(a, zigZagMove());
        check("the user's initiative is untouched", a.ip, 3);
        check("the opponent gains two", foe.ip, 6);
        check("cooldown is the flat 50 reported against every opponent", r.cooldown, 50L);
    }
}
