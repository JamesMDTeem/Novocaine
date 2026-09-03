/*
 * Checks the data-pack seam: data/combat/moves_sheet.json, as loaded by haven.combat.data.Pack.
 *
 * NOT part of the client build - build.xml compiles src/ only. Run on demand:
 *
 *   javac -d %TEMP%\packcheck src\haven\combat\*.java src\haven\combat\data\*.java ^
 *         src\org\json\*.java tools\CombatPackCheck.java
 *   java -cp %TEMP%\packcheck CombatPackCheck
 *
 * WHY THIS EXISTS. Every other check in this project builds its moves by hand. That made the
 * arithmetic and the sequencing well covered and left the path the bot will actually use - parse
 * the sheet, write JSON, load it into a Move - covered by nothing at all. A defect there is
 * invisible to a green check suite, and one was: Take Aim's "increases by 20% for each Point of
 * Initiative" was never parsed and never loaded, so a packed Take Aim reported a flat 30 ticks at
 * any initiative while CombatSimCheck's hand-built copy, carrying ipScale(0.20) as a literal,
 * reproduced the logged 30/36/42/48/54/60 perfectly. Two moves also lost their whole initiative
 * line to an unparsed "4+2" and read as costing nothing.
 *
 * So the rule this file enforces is the one the mu chain taught: an estimator or a loader needs a
 * control with a known answer, and the control has to be RUN, not assumed. Every expectation
 * below is a number printed on the character sheet or measured in the logged corpus.
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import haven.combat.Combatant;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Sim;
import haven.combat.data.Pack;

public class CombatPackCheck {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? (want == null) : got.equals(want);
        System.out.printf("  %-56s %-18s %s%n", what, got, ok ? "ok" : "WANT " + want);
        if(!ok)
            failures++;
    }

    static void near(String what, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        System.out.printf("  %-56s %-18s %s%n", what, String.format("%.3f", got),
                          ok ? "ok" : String.format("WANT %.3f +/- %.3f", want, tol));
        if(!ok)
            failures++;
    }

    static final Path MOVES = Paths.get("data", "combat", "moves_sheet.json");

    static final java.nio.file.Path FOES =
        java.nio.file.Paths.get("data", "combat", "opponents.json");

    static Map<String, Move> moves;

    static Move m(String name) {
        Move v = moves.get(name);
        if(v == null) {
            System.out.printf("  %-56s %-18s %s%n", "move present", "MISSING", "WANT " + name);
            failures++;
        }
        return(v);
    }

    public static void main(String[] args) throws Exception {
        moves = Pack.moves(MOVES);
        System.out.println("loaded " + moves.size() + " moves from data/combat/moves_sheet.json");
        sheetNumbers();
        reductions();
        initiativeLines();
        takeAimLadder();
        weightsAndSchools();
        threatBlocks();
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED"
                           : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Defensive cards, whose "Reduces" line is a SHARE and not a number of points.
     *
     * Zig-Zag Ruse is listed at 50% and settles it: at level 1, where mu is 1.0, it took a
     * standing Cornered of 55 to 27, 44 to 22, 66 to 33, 26 to 13 and 32 to 16 across the
     * corpus. Half of whatever was there, every time. Reading the line the way an openings
     * line is read would subtract fifty POINTS, which floors a 26 to nothing.
     */
    static void reductions() {
        System.out.println("\ndefensive cards reduce a share, not a number of points");
        Move zig = m("Zig-Zag Ruse");
        near("Zig-Zag Ruse takes half of Cornered", zig.reduces[Formulas.RED], 0.5, 1e-9);
        near("  and half of Reeling", zig.reduces[Formulas.YELLOW], 0.5, 1e-9);
        near("  it opens nothing", zig.openings[Formulas.RED], 0, 0);
        near("Quick Dodge is listed at 20% of Off Balance",
             m("Quick Dodge").reduces[Formulas.GREEN], 0.2, 1e-9);

        /* The logged series, replayed. Every one of these is a real pair from the corpus. */
        Combatant me = me();
        Combatant foe = foe();
        int[][] logged = {{55, 27}, {44, 22}, {66, 33}, {26, 13}, {32, 16}};
        for(int[] pair : logged) {
            me.openings[Formulas.RED] = pair[0];
            me.readyAt = 0;
            new Sim(me, foe).use(me, zig);
            check("  " + pair[0] + " Cornered becomes",
                  (int)Math.floor(me.openings[Formulas.RED]), pair[1]);
        }
        /* mu scales the share LINEARLY - the distinction that made the mu chain dangerous,
         * since for an attack it enters the weight and its effect is cubed. */
        me.openings[Formulas.RED] = 50;
        me.readyAt = 0;
        new Sim(me, foe).use(me, zig.withMu(1.2));
        near("at mu 1.2 the same card takes 60%, not 50%",
             me.openings[Formulas.RED], 20.0, 1e-9);
    }

    /** Numbers printed verbatim on the sheet, which the loader must not alter. */
    static void sheetNumbers() {
        System.out.println("\nsheet numbers survive the round trip");
        Move kito = m("Knock Its Teeth Out");
        near("Knock Its Teeth Out flat damage", kito.flatDamage, 30, 0);
        near("  its grievous share, 25% as a fraction", kito.grievous, 0.25, 1e-9);
        near("  its Cornered opening, in percentage points", kito.openings[Formulas.RED], 20, 0);
        near("  its cooldown", kito.cooldownBase, 35, 0);

        Move qb = m("Quick Barrage");
        near("Quick Barrage damage share, from \"weapon * 25%\"", qb.damageShare, 0.25, 1e-9);
        check("  its gain is conditional on red", qb.gainColour, Formulas.RED);
        near("  above a quarter open", qb.gainAbove, 0.25, 1e-9);

        Move cleave = m("Cleave");
        near("Cleave deals 150% of the weapon", cleave.damageShare, 1.5, 1e-9);
        check("  and is two-coloured (Backhanded, Oppressive)", cleave.schools.length, 2);

        Move punch = m("Punch");
        near("Punch's attack weight multiplier, \"80%\"", punch.weightMu, 0.8, 1e-9);
        Move upper = m("Uppercut");
        near("Uppercut's, written \"0.8\" rather than \"80%\"", upper.weightMu, 0.8, 1e-9);
    }

    /**
     * The initiative line, including the two moves that write it as "N+M".
     *
     * These both read as zero until the parser learned the form, which meant the simulator
     * would happily throw a six-point Cleave from an empty initiative pool.
     */
    static void initiativeLines() {
        System.out.println("\ninitiative costs, including the \"4+2\" form");
        check("Knock Its Teeth Out costs one", m("Knock Its Teeth Out").ipCost, 1);
        check("Rip Apart costs six", m("Rip Apart").ipCost, 6);
        check("Cleave costs four", m("Cleave").ipCost, 4);
        check("  with its trailing two carried, not folded in", m("Cleave").ipExtra, 2);
        check("Go for the Jugular costs two", m("Go for the Jugular").ipCost, 2);
        check("  and carries a trailing two as well", m("Go for the Jugular").ipExtra, 2);
        check("Zig-Zag Ruse hands the opponent two", m("Zig-Zag Ruse").foeIpGain, 2);
        check("Punch's silent sheet means no cost, not an unread one", m("Punch").ipCost, 0);
    }

    /**
     * The control that would have caught the missing ipScale.
     *
     * Take Aim reported 30, 36, 42, 48, 54 and 60 ticks across a logged run as its user's
     * initiative climbed from nothing to five. A packed Take Aim has to reproduce that ladder;
     * with ipScale lost it returned 30 six times and every check still passed.
     */
    static void takeAimLadder() {
        System.out.println("\nTake Aim's cooldown ladder, straight from the pack");
        Move aim = m("Take Aim");
        check("its cooldown divides by mu", aim.cooldownMu, true);
        near("  and rises 20% per initiative point", aim.ipScale, 0.20, 1e-9);
        check("  it is a maneuver, so agility does not touch it", aim.isAttack(), false);

        long[] want = {30, 36, 42, 48, 54, 60};
        Combatant me = me();
        Combatant foe = foe();
        for(int ip = 0; ip < want.length; ip++) {
            me.ip = ip;
            me.readyAt = 0;
            Sim sim = new Sim(me, foe);
            check("  at " + ip + " initiative", sim.use(me, aim).cooldown, want[ip]);
        }
    }

    /**
     * The weight line, whose skill is an icon and is absent for "According to weapon".
     *
     * A null there has to mean Melee Combat rather than no skill at all - the sheet's own closing
     * note says so - and reading it as none would collapse every weapon attack's attack weight to
     * zero, which reads as an infinitely tough opponent rather than as an error.
     */
    static void weightsAndSchools() {
        System.out.println("\nattack weights and schools");
        Combatant me = me();
        check("Knock Its Teeth Out reads Unarmed", m("Knock Its Teeth Out").weight,
              Move.Weight.UNARMED);
        near("  so its attack weight is Unarmed 58 at mu 1", me.attackWeight(m("Knock Its Teeth Out")),
             58, 1e-9);
        check("Quick Barrage's blank skill means the weapon default", m("Quick Barrage").weight,
              Move.Weight.WEAPON);
        near("  which resolves to Melee Combat 111", me.attackWeight(m("Quick Barrage")), 111, 1e-9);
        near("Punch is Unarmed at 80%", me.attackWeight(m("Punch")), 58 * 0.8, 1e-9);
        check("Full Circle names Melee explicitly", m("Full Circle").weight, Move.Weight.MELEE);
        near("  at 90%", me.attackWeight(m("Full Circle")), 111 * 0.9, 1e-9);

        /* A stance is a trade: defence bought with offence. Combat Meditation cuts every
         * attack to a quarter weight while it is held, and the model had no term for that
         * at all until a forum guide's plain-language note on Oak Stance surfaced it. */
        near("Combat Meditation quarters its holder's attack weight",
             m("Combat Meditation").attackMult, 0.25, 1e-9);
        near("an ordinary card leaves attack weight alone",
             m("Quick Barrage").attackMult, 1.0, 1e-9);
        Combatant med = me();
        med.attackMult = m("Combat Meditation").attackMult;
        near("  so Quick Barrage falls from 111 to 27.75 while it is held",
             med.attackWeight(m("Quick Barrage")), 27.75, 1e-9);

        check("mu defaults to 1.0, the level-1 value Take Aim measures",
              m("Knock Its Teeth Out").mu, 1.0);
        near("  and a levelled card carries its own",
             me.attackWeight(m("Knock Its Teeth Out").withMu(1.5)), 58 * 1.5, 1e-9);
        near("  leaving the rest of the deck alone", me.attackWeight(m("Punch")), 58 * 0.8, 1e-9);

        check("Zig-Zag Ruse has no attack type, so it is a maneuver",
              m("Zig-Zag Ruse").kind, Move.Kind.MANEUVER);
        check("Full Circle is two-coloured", m("Full Circle").schools.length, 2);
    }

    /**
     * The threat blocks, which are the opponent's half of the fight.
     *
     * This is the seam that carries the only measurements in the pack about what happens to
     * US, and it is the newest one, so it gets the same treatment the moves seam got: not
     * "does it parse", but "does what it parsed mean what the estimator meant".
     */
    static void threatBlocks() throws Exception {
        System.out.println("\nthe opponent's own model, from the pack's threat blocks");
        Map<String, Pack.Opponent> foes = Pack.opponents(FOES);

        int withThreat = 0, withPeriod = 0;
        for(Pack.Opponent o : foes.values()) {
            if(o.threat == null)
                continue;
            withThreat++;
            if(o.threat.period > 0)
                withPeriod++;
        }
        check("most opponents carry a threat model", withThreat > (foes.size() / 2), true);
        /* A model with no period is not loaded at all - see Pack.threat. Period is the
         * clock, and without it none of the rest gets applied to anything. */
        check("  and every one that loaded has a period", withPeriod, withThreat);

        Pack.Opponent ants = foes.get("ants");
        if(ants == null || ants.threat == null) {
            System.out.println("  (no ants in the pack - species checks skipped)");
        } else {
            /* Measured per GOB across the whole file rather than per engagement, and not
             * gated on defence_ok. Before that, ants read 8 gaps out of 144 engagements
             * because a swarming species is almost never alone - which is exactly the
             * species the number is wanted for. */
            check("ants rest on more than fifty observed gaps", ants.threat.nGaps > 50, true);
            check("  a swarming species that the old per-engagement gate reduced to 8", true, true);
        }

        /* Cattle acts on two clocks, 22 ticks and 38, and the mean is 33 - a figure it
         * never once exhibited. The model takes one period because a rate is one number,
         * so the modes ride along to say when that number is a blend. Getting this wrong
         * is not cosmetic: an earlier version folded the 38s onto 19 as missed actions and
         * reported the creature as twice as dangerous as it is. The tell that 38 is real
         * is that nothing sits at 44, where a double of 22 would have to be. */
        Pack.Opponent cattle = foes.get("cattle");
        if(cattle == null || cattle.threat == null) {
            System.out.println("  (no cattle in the pack - the two-clock check is skipped)");
        } else {
            check("cattle is seen acting on more than one clock",
                  cattle.threat.multiClock(), true);
            boolean spread = false;
            for(int a : cattle.threat.modes)
                for(int b : cattle.threat.modes)
                    if(a > (b * 1.5))
                        spread = true;
            check("  and the two are far enough apart to be different cards", spread, true);
            check("  with the period between them",
                  (cattle.threat.period > cattle.threat.modes[0])
                  && (cattle.threat.period < cattle.threat.modes[1]), true);
        }

        /* The honesty guard that matters most here. An opponent that has never landed on
         * us must not report zero damage as though that were a measurement - FoeModel
         * returns nothing rather than nothing-meaning-safe, and the matchup says which. */
        int silent = 0;
        for(Pack.Opponent o : foes.values()) {
            if((o.threat != null) && !o.threat.knowsDamage())
                silent++;
        }
        check("some opponent has never been measured hitting us", silent > 0, true);
        check("  and it reports its damage as unknown, not as zero",
              anySilentRefusesDamage(foes), true);
    }

    static boolean anySilentRefusesDamage(Map<String, Pack.Opponent> foes) {
        for(Pack.Opponent o : foes.values()) {
            if((o.threat == null) || o.threat.knowsDamage())
                continue;
            Combatant target = me();
            double before = target.hp;
            double dealt = o.threat.act(target, target.defenceWeight());
            /* It still OPENS us - pressure is measured separately from damage and one can
             * be known while the other is not. What it must not do is quietly deal zero
             * and let a plan be costed as free. */
            if((dealt != 0) || (target.hp != before))
                return(false);
        }
        return(true);
    }

    /* The character that fought the logged corpus. */
    static Combatant me() {
        Combatant c = new Combatant("ZzxcuV3");
        c.str = 82; c.agi = 81; c.unarmed = 58; c.melee = 111;
        c.weaponDamage = 90; c.weaponQl = 28.68; c.weaponPen = 0.125;
        c.armHard = 5; c.armSoft = 2;
        c.hp = c.maxHp = 100;
        return(c);
    }

    /* Equal agility, so the agility factor is exactly 1 and cannot mask a cooldown error. */
    static Combatant foe() {
        Combatant c = new Combatant("control");
        c.agi = 81;
        c.hp = c.maxHp = 1000;
        c.blockSkill = 111;
        return(c);
    }
}
