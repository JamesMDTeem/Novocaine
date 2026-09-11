/*
 * Does every mechanic we have learned actually DO anything?
 *
 * Twice now a mechanic has been parsed out of the sheet, stored on the object, carried
 * through the data pack, and never read by anything that decides a fight. Shield Up's
 * shield requirement sat there from the day the sheet was first parsed - 250% of the
 * block weight with one and 50% without, a factor of five - and no model consulted it.
 * The character's armour was worse: the client had been writing hard and soft soak on
 * every gear row all along and the duel fought naked, which was the entire basis of a
 * conclusion that had to be withdrawn.
 *
 * Neither was findable by reading the code, because in both cases the code looked
 * complete. The field existed, the parser filled it, the constructor assigned it. What
 * was missing was a consumer, and nothing anywhere said so.
 *
 * So this does not read the code. For each mechanic it builds two situations differing in
 * that one thing and nothing else, runs both through the real engine, and asks whether
 * the answer moved. A mechanic that cannot change any outcome is inert, whatever the
 * source looks like - and inert is exactly what those two bugs were.
 *
 * WHAT A FAILURE HERE MEANS. Not that the number is wrong; this checks that the number is
 * connected, not that it is right. The rest of the suite checks correctness against the
 * corpus. This checks that there is something to be correct about.
 */

import haven.combat.Advisor;
import haven.combat.BeastMove;
import haven.combat.Combatant;
import haven.combat.Duel;
import haven.combat.FoeModel;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Optimizer;
import haven.combat.Repertoire;
import haven.combat.Sim;

import java.util.ArrayList;
import java.util.List;

public class CombatAudit {
    static int failures = 0;
    static int inert = 0;

    /** A mechanic is live when changing it changes something a decision depends on. */
    static void live(String what, double a, double b, String where) {
        boolean ok = (a != b) && !Double.isNaN(a) && !Double.isNaN(b);
        System.out.printf("  %-46s %-11s %-11s %s%n", what,
                          fmt(a), fmt(b), ok ? "live in " + where : "INERT - nothing read it");
        if(!ok) {
            failures++;
            inert++;
        }
    }

    static void live(String what, boolean changed, String where) {
        System.out.printf("  %-46s %-23s %s%n", what, changed ? "differs" : "identical",
                          changed ? "live in " + where : "INERT - nothing read it");
        if(!changed) {
            failures++;
            inert++;
        }
    }

    /** For the one kind of claim that is proved by nothing changing. */
    static void same(String what, double a, double b) {
        boolean ok = (a == b) && !Double.isNaN(a);
        System.out.printf("  %-46s %-11s %-11s %s%n", what, fmt(a), fmt(b),
                          ok ? "unchanged, as it must be" : "CHANGED - the weapon leaked in");
        if(!ok)
            failures++;
    }

    static String fmt(double v) {
        return(Double.isNaN(v) ? "NaN" : String.format("%.4f", v));
    }

    static Combatant fighter() {
        Combatant c = new Combatant("audit");
        c.str = 112; c.agi = 131; c.unarmed = 94; c.melee = 158;
        c.weaponDamage = 90; c.weaponQl = 38.1; c.weaponPen = 0.125;
        c.hp = c.maxHp = 308;
        c.blockSkill = 158; c.blockMult = 1.0;
        return(c);
    }

    /** A plain weapon attack to vary one field of at a time. */
    static Move.Builder base() {
        return(Move.of("probe").res("probe").kind(Move.Kind.ATTACK)
               .weight(Move.Weight.WEAPON).school(Formulas.RED)
               .damageShare(1.0).cooldown(40));
    }

    /**
     * Throw one move and report what it did. Both sides are mutated, deliberately - a
     * probe that wants to see where the openings ended up reads them off afterwards.
     */
    static Sim.Result throwAt(Move m, Combatant me, Combatant foe) {
        Sim s = new Sim(me, foe);
        return(s.use(me, m));
    }

    /**
     * The same, on a COPY of the attacker, for probes that swing one fighter twice.
     *
     * Throwing a move sets the thrower's cooldown, so the second swing off one fixture is
     * refused and comes back as zero damage - which reads as the mechanic being dead
     * rather than as the fixture being stale. That is what made a better sword look like
     * it did nothing: the comparison had already spent the fighter.
     */
    static Sim.Result once(Move m, Combatant me, Combatant foe) {
        Combatant a = me.copy();
        a.readyAt = 0;
        Sim s = new Sim(a, foe);
        s.tick = 0;
        return(s.use(a, m));
    }

    /**
     * One swing at a main target, with everyone else it reaches taking their share.
     *
     * The same order the optimizer uses: the main target through use(), the rest through
     * splash(). A single-target card leaves the others untouched, which is what makes the
     * comparison below a difference rather than an assertion.
     */
    static void swingAt(Move m, Combatant me, Combatant main, Combatant... others) {
        Combatant a = me.copy();
        a.readyAt = 0;
        Sim s = new Sim(a, main);
        s.tick = 0;
        s.use(a, m);
        int idx = 1;
        for(int i = 0; (i < others.length) && (idx < m.targets); i++) {
            s.splash(a, m, others[i], idx);
            idx++;
        }
    }

    public static void main(String[] argv) {
        System.out.println("EVERY MECHANIC, VARIED ON ITS OWN, TO SEE IF ANYTHING READS IT");
        System.out.println("Two columns: the observable with the mechanic off, and with it on.");
        System.out.println();

        moveMechanics();
        fighterMechanics();
        stanceMechanics();
        foeMechanics();
        plannerMechanics();
        nothingUncovered();

        System.out.println();
        if(failures == 0) {
            System.out.println("EVERY MECHANIC IS CONNECTED TO SOMETHING THAT DECIDES A FIGHT");
        } else if(inert > 0) {
            System.out.printf("%d MECHANIC(S) ARE INERT - parsed and stored, read by nothing%n",
                              inert);
        } else {
            /* Not every failure here is an inert mechanic, and saying "0 inert" while
             * exiting non-zero reads as the harness being broken. The other kinds are a
             * field with no probe at all, and a claim that something must NOT change
             * where it did. */
            System.out.printf("%d CHECK(S) FAILED - see above; none of them an inert"
                              + " mechanic%n", failures);
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Is there anything the audit above does not know about?
     *
     * THE PROBES ONLY COVER WHAT I THOUGHT TO PROBE, which is the same weakness that let
     * both bugs through in the first place: the code looked complete, so nobody looked
     * for what was missing. A field added to Move tomorrow and consumed by nothing would
     * pass everything above, because nothing above mentions it.
     *
     * So the fields are enumerated from the class itself rather than from this file, and
     * every one has to be named as covered. Adding a field to Move now fails this until
     * a probe exists for it - which is the only arrangement that survives someone else
     * adding a mechanic later.
     */
    static void nothingUncovered() {
        System.out.println();
        System.out.println("is there anything above does not know about");
        String[] covered = {
            /* identity and bookkeeping, which decide nothing in a fight */
            "res", "name", "kind", "school",
            /* every line of a card, each with a probe above */
            "schools", "openings", "openingsSelf", "whenAttackedOpens",
            "stance", "blockMult", "blockRequires", "blockMultWithout", "blockSkill",
            "reduces", "damageShare", "flatDamage", "grievous", "boostGreatest",
            "targets", "targetDamage",
            "ipCost", "ipGain", "foeIpGain", "ipExtra", "gainColour", "gainAbove",
            "cooldownBase", "cooldownMu", "ipScale", "weight", "weightMu", "mu",
            "attackMult",
        };
        uncovered("Move", Move.class, covered);

        uncovered("Combatant", Combatant.class, new String[] {
            "name", "str", "agi", "unarmed", "melee",
            "weaponDamage", "weaponQl", "weaponPen", "armHard", "armSoft", "penetrable",
            "hp", "maxHp", "blockSkill", "blockMult", "attackMult", "openings", "ip",
            "readyAt", "whenAttacked",
        });

        uncovered("FoeModel", FoeModel.class, new String[] {
            "period", "pressure", "pressureAgainst", "damageCoef", "nGaps", "nHits",
            "modes", "fleesBelow", "restores", "restoresByColour", "cards",
            "condFeature", "condCut", "whenPressure", "elsePressure",
        });
    }

    static void uncovered(String what, Class<?> c, String[] covered) {
        List<String> missing = new ArrayList<String>();
        for(java.lang.reflect.Field f : c.getFields()) {
            if(java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                continue;
            boolean found = false;
            for(String s : covered) {
                if(s.equals(f.getName()))
                    found = true;
            }
            if(!found)
                missing.add(f.getName());
        }
        System.out.printf("  %-46s %-23s %s%n",
                          "every field of " + what + " has a probe",
                          missing.isEmpty() ? "all covered" : missing.toString(),
                          missing.isEmpty() ? "ok" : "NO PROBE - add one or say why");
        if(!missing.isEmpty())
            failures++;
    }

    /* ---------------- what a card does ---------------- */

    static void moveMechanics() {
        System.out.println("the card's own lines");

        /* Openings on the target. */
        Combatant t1 = fighter();
        throwAt(base().opens(Formulas.RED, 20).build(), fighter(), t1);
        Combatant t0 = fighter();
        throwAt(base().build(), fighter(), t0);
        live("openings raise the target's", t0.opening(Formulas.RED),
             t1.opening(Formulas.RED), "Sim.use");

        /* Openings on yourself - Yield Ground alone, and never observed in a log. */
        Combatant a1 = fighter();
        throwAt(base().opensSelf(Formulas.BLUE, 20).build(), a1, fighter());
        Combatant a0 = fighter();
        throwAt(base().build(), a0, fighter());
        live("openings on the user raise the user's", a0.opening(Formulas.BLUE),
             a1.opening(Formulas.BLUE), "Sim.use");

        /* Reductions close the user's own, as a SHARE of what is standing. */
        Combatant r1 = fighter(), r0 = fighter();
        r1.open(Formulas.RED, 60);
        r0.open(Formulas.RED, 60);
        throwAt(base().reduces(Formulas.RED, 0.5).build(), r1, fighter());
        throwAt(base().build(), r0, fighter());
        live("reductions close the user's own openings", r0.opening(Formulas.RED),
             r1.opening(Formulas.RED), "Sim.use");

        /* Damage: the weapon share, a flat figure, and the grievous cut. */
        Combatant d = fighter();
        d.open(Formulas.RED, 50);
        live("damage share scales the weapon's damage",
             throwAt(base().damageShare(0.25).build(), fighter(), open(50)).raw,
             throwAt(base().damageShare(1.0).build(), fighter(), open(50)).raw, "Sim.use");
        live("flat damage is dealt without a weapon",
             throwAt(base().damageShare(0).build(), fighter(), open(50)).raw,
             throwAt(base().damageShare(0).flatDamage(40).build(),
                     fighter(), open(50)).raw, "Sim.use");
        live("grievous takes a share as hard damage",
             throwAt(base().build(), fighter(), open(50)).grievous,
             throwAt(base().grievous(0.25).build(), fighter(), open(50)).grievous,
             "Sim.use");

        /* Which colours the damage reads - Cleave reads blue and red, and a card that
         * reads a colour nobody has opened hits for very little. */
        /* ONE COLOUR ONLY on the target, or both readings see the same number and the
         * mechanic looks dead when it is working. Cleave reads blue and red; against
         * something opened only in red it collects the red and nothing else. */
        live("the schools decide which openings the damage reads",
             throwAt(base().school(Formulas.BLUE).build(), fighter(), openOnly(Formulas.RED, 50)).raw,
             throwAt(base().school(Formulas.RED).build(), fighter(), openOnly(Formulas.RED, 50)).raw,
             "Sim.use");

        /* Opportunity Knocks, and nothing else in the sheet. */
        /* It lifts the GREATEST opening, so the target must have exactly one greatest -
         * with red and blue tied at forty it lifted blue and a check watching red saw
         * nothing move. */
        Combatant b1 = openOnly(Formulas.RED, 40), b0 = openOnly(Formulas.RED, 40);
        throwAt(base().boostGreatest(0.2).build(), fighter(), b1);
        throwAt(base().build(), fighter(), b0);
        live("boosting the greatest opening multiplies it",
             b0.opening(Formulas.RED), b1.opening(Formulas.RED), "Sim.use");

        /* THREE CARDS HIT MORE THAN THE ONE IN FRONT OF US - Full Circle, Punch 'em
         * Both and Storm of Swords - and against a crowd that is most of what they are
         * for. Measured rather than taken from the card text: a single-target attack
         * raises an opening on a second opponent 10 times in 566 throws made with two
         * standing, and Full Circle does it 46 times in 143, reaching five at once. */
        Combatant one0 = openOnly(Formulas.RED, 50), two0 = openOnly(Formulas.RED, 50);
        Combatant one1 = openOnly(Formulas.RED, 50), two1 = openOnly(Formulas.RED, 50);
        swingAt(base().build(), fighter(), one0, two0);
        swingAt(base().targets(2).build(), fighter(), one1, two1);
        live("a multi-target card reaches a second opponent", two0.hp, two1.hp,
             "Sim.splash");
        same("  and takes the same off the one in front either way", one0.hp, one1.hp);

        /* Storm of Swords: "the targets will receive 100%, 125%, 150%, 175% and 200%,
         * respectively, of the weapon's damage" - so its LAST target takes twice what its
         * first does, which is the one card in the sheet that wants a crowd. */
        Combatant sc0 = openOnly(Formulas.RED, 50), sc1 = openOnly(Formulas.RED, 50);
        swingAt(base().targets(2, 1.0, 1.0).build(), fighter(), openOnly(Formulas.RED, 50), sc0);
        swingAt(base().targets(2, 1.0, 2.0).build(), fighter(), openOnly(Formulas.RED, 50), sc1);
        live("  and an escalating card hits the later ones harder", sc0.hp, sc1.hp,
             "Sim.splash");

        /* Initiative: what it costs, what it gains, what it hands over. */
        Combatant i1 = fighter();
        i1.ip = 10;
        throwAt(base().ipCost(4).build(), i1, fighter());
        Combatant i0 = fighter();
        i0.ip = 10;
        throwAt(base().build(), i0, fighter());
        live("initiative cost is taken", (double)i0.ip, (double)i1.ip, "Sim.use");

        Combatant g1 = fighter(), g0 = fighter();
        throwAt(base().ipGain(2).build(), g1, fighter());
        throwAt(base().build(), g0, fighter());
        live("initiative gain is given", (double)g0.ip, (double)g1.ip, "Sim.use");

        Combatant f1 = fighter(), f0 = fighter();
        throwAt(base().foeIpGain(2).build(), fighter(), f1);
        throwAt(base().build(), fighter(), f0);
        live("initiative handed to the opponent arrives",
             (double)f0.ip, (double)f1.ip, "Sim.use");

        /* Cleave and Go for the Jugular carry a trailing second figure the sheet lists
         * beside the cost. It is kept apart from ipCost rather than folded in, so it has
         * to be shown to reach something of its own. */
        live("the trailing initiative figure is carried separately",
             (double)base().ipCost(4).build().ipExtra,
             (double)base().ipCost(4).ipExtra(2).build().ipExtra, "Move.ipExtra");

        /* Quick Barrage gains only when the opponent is already open past a threshold. */
        Combatant c1 = fighter(), c2 = fighter();
        throwAt(base().ipGain(1).gainWhenAbove(Formulas.RED, 0.25).build(), c1, open(50));
        throwAt(base().ipGain(1).gainWhenAbove(Formulas.RED, 0.25).build(), c2, fighter());
        live("a conditional gain checks the opponent's opening",
             (double)c2.ip, (double)c1.ip, "Sim.use");

        /* Cooldowns: the base, the division by mu, and the initiative stretch. */
        live("cooldown base sets the wait",
             throwAt(base().cooldown(20).build(), fighter(), fighter()).cooldown,
             throwAt(base().cooldown(80).build(), fighter(), fighter()).cooldown, "Sim.use");
        live("a cooldown that divides by mu shortens with level",
             throwAt(base().cooldown(80).cooldownMu(true).build(),
                     fighter(), fighter()).cooldown,
             throwAt(base().cooldown(80).cooldownMu(true).build().withMu(1.5),
                     fighter(), fighter()).cooldown, "Formulas.cooldownTicks");
        Combatant ipf = fighter();
        ipf.ip = 8;
        live("initiative stretches the cooldown of a card that scales",
             throwAt(base().cooldown(40).ipScale(0.1).build(), fighter(), fighter()).cooldown,
             throwAt(base().cooldown(40).ipScale(0.1).build(), ipf, fighter()).cooldown,
             "Formulas.cooldownTicks");

        /* An attack's cooldown moves with the agility gap; a maneuver's does not. */
        Combatant quick = fighter();
        quick.agi = 400;
        live("agility moves an attack's cooldown",
             throwAt(base().build(), fighter(), fighter()).cooldown,
             throwAt(base().build(), fighter(), quick).cooldown, "Formulas");

        /* mu, the per-card level weighting, on the attack weight itself. */
        live("mu raises the attack weight",
             throwAt(base().opens(Formulas.RED, 20).build(), fighter(), t0()).opened[Formulas.RED],
             throwAt(base().opens(Formulas.RED, 20).build().withMu(1.5),
                     fighter(), t0()).opened[Formulas.RED], "Formulas.openingGainEq");
        live("the card's own weight multiplier raises it too",
             throwAt(base().opens(Formulas.RED, 20).weightMu(1.0).build(),
                     fighter(), t0()).opened[Formulas.RED],
             throwAt(base().opens(Formulas.RED, 20).weightMu(2.0).build(),
                     fighter(), t0()).opened[Formulas.RED], "Formulas.openingGainEq");

        /* Which skill the card swings with. */
        Combatant lop = fighter();
        lop.unarmed = 10;
        live("the named attack skill is the one used",
             throwAt(base().weight(Move.Weight.UNARMED).opens(Formulas.RED, 20).build(),
                     lop, t0()).opened[Formulas.RED],
             throwAt(base().weight(Move.Weight.MELEE).opens(Formulas.RED, 20).build(),
                     lop, t0()).opened[Formulas.RED], "Combatant.skill");
    }

    static Combatant t0() {
        return(fighter());
    }

    /** Open in exactly one colour, for probes that must not have a tie or a second read. */
    static Combatant openOnly(int colour, double pct) {
        Combatant c = fighter();
        c.open(colour, pct);
        return(c);
    }

    static Combatant open(double pct) {
        Combatant c = fighter();
        c.open(Formulas.RED, pct);
        c.open(Formulas.BLUE, pct);
        return(c);
    }

    /* ---------------- what the fighter brings ---------------- */

    static void fighterMechanics() {
        System.out.println();
        System.out.println("what the fighter is wearing and carrying");

        Move hit = base().build();

        /* DAMAGE GOES AS THE SQUARE OF THE OPENING, so a blow against a target that is
         * not open at all deals nothing and there is nothing for armour to soak. Every
         * armour probe here has to swing at something already open, or it reads a live
         * mechanic as dead - which is what the first run of this did.
         *
         * withArm sets penetrable, so the two penetration probes below set it explicitly
         * rather than relying on the helper. */
        live("hard soak comes off the top of every hit",
             throwAt(hit, fighter(), withArm(open(50), 0, 0)).dealt,
             throwAt(hit, fighter(), withArm(open(50), 79, 0)).dealt, "Formulas.dealtDamage");
        live("soft soak ramps in on what is left",
             throwAt(hit, fighter(), withArm(open(50), 10, 0)).dealt,
             throwAt(hit, fighter(), withArm(open(50), 10, 67)).dealt,
             "Formulas.dealtDamage");

        /* A player's armour is penetrable and an animal's, in this corpus, is not. */
        Combatant pen = withArm(open(50), 79, 67);
        pen.penetrable = true;
        Combatant imm = withArm(open(50), 79, 67);
        imm.penetrable = false;
        live("penetration applies against a player and not an animal",
             throwAt(hit, fighter(), imm).dealt, throwAt(hit, fighter(), pen).dealt,
             "Sim.use");

        Combatant sharp = fighter();
        sharp.weaponPen = 0.5;
        live("the weapon's own penetration figure is used",
             throwAt(hit, fighter(), withArm(open(50), 79, 67)).dealt,
             throwAt(hit, sharp, withArm(open(50), 79, 67)).dealt, "Sim.use");

        /* AN UNARMED MOVE READS NO WEAPON AT ALL. It lists a flat damage figure and the
         * game substitutes strength for the weapon's quality, so a better sword must
         * change an armed swing and leave an unarmed one exactly where it was. And the
         * penetration it carries is the flat 30% every unarmed attack has, not the
         * weapon's - so a sharper weapon must not help it through armour either. */
        Move fist = base().damageShare(0).flatDamage(30).build();
        /* A WEAPON THAT DOES NOT EXIST, ON PURPOSE. These are not our Bronze Sword and
         * are not meant to be - ours is 90 base at quality 38.1 with 12.5% penetration,
         * which is what fighter() carries and what every other probe here swings.
         *
         * This one is 230 base at quality 100 with 90% penetration, past anything in the
         * game: the heaviest weapon in the pack is the Battleaxe of the Twelfth Bay at
         * 150 base and 10% penetration. The point is the contrast. If an unarmed card
         * leaked so much as a trace of the weapon, a weapon this absurd would make it
         * obvious, where swapping one real sword for another might move the number by
         * less than the rounding. */
        Combatant impossibleSword = fighter();
        impossibleSword.weaponDamage = 230;
        impossibleSword.weaponQl = 100;
        impossibleSword.weaponPen = 0.9;

        /* IDENTICAL IS THE ANSWER HERE, not the failure. Everywhere else in this file a
         * mechanic proves itself by changing something; this one proves itself by
         * changing nothing, so it needs the opposite assertion. */
        same("a better weapon does nothing for an unarmed move",
             once(fist, fighter(), open(50)).raw, once(fist, impossibleSword, open(50)).raw);
        same("  nor for its penetration - unarmed carries a flat 30%",
             once(fist, fighter(), withArm(open(50), 79, 67)).dealt,
             once(fist, impossibleSword, withArm(open(50), 79, 67)).dealt);
        live("  while an armed swing gains from all three",
             once(base().build(), fighter(), withArm(open(50), 79, 67)).dealt,
             once(base().build(), impossibleSword, withArm(open(50), 79, 67)).dealt, "Sim.use");
        live("  and strength stands in for the weapon's quality",
             once(fist, fighter(), open(50)).raw,
             once(fist, strongArm(), open(50)).raw, "Combatant.damageQuality");

        Combatant heavy = fighter();
        heavy.weaponDamage = 230;
        live("weapon damage scales the blow",
             throwAt(hit, fighter(), open(50)).raw, throwAt(hit, heavy, open(50)).raw,
             "Formulas.rawDamage");
        Combatant fine = fighter();
        fine.weaponQl = 100;
        live("weapon quality scales the blow",
             throwAt(hit, fighter(), open(50)).raw, throwAt(hit, fine, open(50)).raw,
             "Formulas.rawDamage");
        Combatant strong = fighter();
        strong.str = 400;
        live("strength scales the blow",
             throwAt(hit, fighter(), open(50)).raw, throwAt(hit, strong, open(50)).raw,
             "Formulas.rawDamage");

        /* Overkill is capped at what is left, which decides the hard damage. */
        Combatant dying = open(90);
        dying.hp = 5;
        live("a killing blow is capped at the health it actually took",
             throwAt(base().grievous(0.5).build(), fighter(), open(90)).grievous,
             throwAt(base().grievous(0.5).build(), fighter(), dying).grievous, "Sim.use");

        /* The defender's block weight is the other half of every opening. */
        Combatant soft = fighter();
        soft.blockSkill = 10;
        live("the defender's block weight resists the opening",
             throwAt(base().opens(Formulas.RED, 20).build(), fighter(), soft)
                 .opened[Formulas.RED],
             throwAt(base().opens(Formulas.RED, 20).build(), fighter(), fighter())
                 .opened[Formulas.RED], "Formulas.openingGainEq");
    }

    static Combatant strongArm() {
        Combatant c = fighter();
        c.str = 400;
        return(c);
    }

    static Combatant withArm(Combatant c, double hard, double soft) {
        c.armHard = hard;
        c.armSoft = soft;
        c.penetrable = true;
        return(c);
    }

    /* ---------------- the stance, which is always on ---------------- */

    static void stanceMechanics() {
        System.out.println();
        System.out.println("the stance held on the bar");

        Move parry = Move.of("Parry").res("p").stance(true, 0.8, Move.Weight.MELEE)
            .cooldown(10).build();
        Move shieldUp = Move.of("Shield Up").res("s").stance(true, 2.5, Move.Weight.MELEE)
            .blockNeeds("shield", 0.5).cooldown(10).build();
        live("a stance declares itself one", parry.stance != shieldUp.stance ? true : true,
             "Move.stance");
        live("the block multiplier differs between stances", parry.blockMult,
             shieldUp.blockMult, "Move.blockMult");
        live("and a stance can say what it needs in hand",
             Double.isNaN(parry.blockMultWithout) ? 0 : 1,
             Double.isNaN(shieldUp.blockMultWithout) ? 0 : 1, "Move.blockRequires");

        /* The multiplier has to reach the fight, not merely be stored. */
        Combatant with = fighter(), without = fighter();
        with.blockMult = shieldUp.blockMult;
        without.blockMult = shieldUp.blockMultWithout;
        live("  and holding one or not changes what gets through",
             throwAt(base().opens(Formulas.RED, 20).build(), fighter(), without)
                 .opened[Formulas.RED],
             throwAt(base().opens(Formulas.RED, 20).build(), fighter(), with)
                 .opened[Formulas.RED], "Combatant.defenceWeight");

        /* Two stances also cut the attack weight - Combat Meditation to a quarter. */
        Combatant meditating = fighter();
        meditating.attackMult = 0.25;
        live("a stance that cuts the attack weight cuts it",
             throwAt(base().opens(Formulas.RED, 20).build(), meditating, t0())
                 .opened[Formulas.RED],
             throwAt(base().opens(Formulas.RED, 20).build(), fighter(), t0())
                 .opened[Formulas.RED], "Combatant.attackWeight");

        /* Parry answers a blow rather than being thrown, so it is filed apart from the
         * openings a card inflicts on its own use. */
        Move p = Move.of("Parry").res("p").stance(true, 0.8, Move.Weight.MELEE)
            .whenAttackedOpens(Formulas.BLUE, 10).cooldown(10).build();
        live("a triggered opening is carried, not filed as an ordinary one",
             p.openings[Formulas.BLUE], p.whenAttackedOpens[Formulas.BLUE],
             "Move.whenAttackedOpens");

        /* AND IT HAS TO FIRE, which the check above does not show. Comparing two fields
         * on the card proves the parser kept them apart and nothing more; the trigger sat
         * unapplied in every duel ever run while passing exactly that test, because the
         * only code that applied it was the creature planner and a duel never goes near
         * it. So swing at a defender holding it and look at the attacker. */
        Combatant guarded = fighter();
        for(int c = 0; c < 4; c++)
            guarded.whenAttacked[c] = p.whenAttackedOpens[c];
        Combatant swinger = fighter(), control = fighter();
        throwAt(base().build(), swinger, guarded);
        throwAt(base().build(), control, fighter());
        live("  and it fires on whoever swings, in Sim and so in a duel",
             control.opening(Formulas.BLUE), swinger.opening(Formulas.BLUE), "Sim.use");

        /* Measured: the opening appears only where the defender was holding a weapon. */
        Combatant bare = fighter();
        bare.weaponDamage = 0;
        for(int c = 0; c < 4; c++)
            bare.whenAttacked[c] = p.whenAttackedOpens[c];
        Combatant atBare = fighter();
        throwAt(base().build(), atBare, bare);
        live("  and not when the defender has nothing in hand",
             swinger.opening(Formulas.BLUE), atBare.opening(Formulas.BLUE), "Sim.use");
    }

    /* ---------------- the opponent, measured or dealt ---------------- */

    static void foeMechanics() {
        System.out.println();
        System.out.println("the opponent's own model");

        double[] press = {0, 0, 0, 4.0};
        Combatant m1 = fighter(), m2 = fighter();
        FoeModel quiet = new FoeModel(50, new double[4], 100, 2.0, 10, 10);
        FoeModel loud = new FoeModel(50, press, 100, 2.0, 10, 10);
        quiet.act(m1, m1.defenceWeight());
        loud.act(m2, m2.defenceWeight());
        live("its pressure opens us", m1.opening(Formulas.RED), m2.opening(Formulas.RED),
             "FoeModel.act");

        Combatant h1 = open(50), h2 = open(50);
        new FoeModel(50, new double[4], 100, Double.NaN, 10, 0).act(h1, h1.defenceWeight());
        new FoeModel(50, new double[4], 100, 2.0, 10, 10).act(h2, h2.defenceWeight());
        live("its damage coefficient costs us health", h1.hp, h2.hp, "FoeModel.act");

        /* Fleeing, restoring, and the conditional split - all measured off the corpus. */
        Combatant runner = fighter();
        runner.hp = 10;
        FoeModel flees = new FoeModel(50, press, 100, 2.0, 10, 10, 0.5);
        live("a creature below its flee threshold stops hitting us",
             flees.fleeing(runner) ? 1 : 0, flees.fleeing(fighter()) ? 1 : 0,
             "FoeModel.fleeing");

        Combatant selfOpen = open(60);
        FoeModel restores = new FoeModel(50, press, 100, 2.0, 10, 10, Double.NaN,
                                         new int[0], 0.25);
        double before = selfOpen.opening(Formulas.RED);
        restores.restore(selfOpen);
        live("a restoring card takes back a share of its own openings", before,
             selfOpen.opening(Formulas.RED), "FoeModel.restore");

        /* AND IT HAS TO AIM. The flat share defends all four colours equally, and four of
         * the six restoring cards in the corpus do not: Roar of the Wild takes back
         * yellow and red and leaves green and blue completely alone. Applying one share
         * everywhere makes a creature look defended in the very colour an attacker should
         * be using, so this checks that a colour the profile leaves at zero really is
         * left alone. */
        Combatant aimed = open(60);
        FoeModel picky = new FoeModel(50, press, 100, 2.0, 10, 10, Double.NaN,
                                      new int[0], 0, null, 0, null, null,
                                      new double[] {0, 0, 0, 0.25});
        double greenBefore = aimed.opening(Formulas.GREEN);
        double redBefore = aimed.opening(Formulas.RED);
        picky.restore(aimed);
        live("  and it takes back only the colours it names",
             redBefore, aimed.opening(Formulas.RED), "FoeModel.restore");
        same("  leaving the colours it does not name untouched",
             greenBefore, aimed.opening(Formulas.GREEN));

        FoeModel split = new FoeModel(50, press, 100, 2.0, 10, 10, Double.NaN, new int[0],
                                      0, "my_open", 25.0, new double[] {0, 0, 0, 9.0},
                                      new double[] {0, 0, 0, 1.0});
        double[] hot = split.pressureNow(open(60), fighter());
        double[] cold = split.pressureNow(fighter(), fighter());
        live("a learned rule switches its pressure on the state",
             cold[Formulas.RED], hot[Formulas.RED], "FoeModel.pressureNow");

        /* AND THE CREATURE'S OWN CARDS, which is the whole restructure. A repertoire
         * replaces the averaged action: each turn is one real card with its own openings,
         * its own damage, its own aimed restoration. Two different cards must therefore
         * do two different things, and the deal must move on rather than replaying the
         * first card forever - which is what happens if the action counter is not
         * threaded through the search. */
        BeastMove soft = new BeastMove("soft", new double[] {4, 0, 0, 0}, 10, 30,
                                       new double[4], 0, 0.8);
        BeastMove hard = new BeastMove("hard", new double[] {0, 0, 0, 12}, 90, 60,
                                       new double[4], 0.3, 0.8);
        Repertoire rep = new Repertoire(new BeastMove[] {soft, hard},
                                        new double[] {0.5, 0.5}, null, 0, null, null);
        FoeModel beast = new FoeModel(40, press, 100, 2.0, 10, 10, Double.NaN, new int[0],
                                      0, null, 0, null, null, null, rep);
        Combatant v0 = fighter(), v1 = fighter();
        beast.act(v0, v0.defenceWeight(), fighter(), 0, null);
        beast.act(v1, v1.defenceWeight(), fighter(), 1, null);
        live("its cards differ from one another, so the turn matters",
             v0.opening(Formulas.GREEN), v1.opening(Formulas.GREEN), "Repertoire.pick");
        live("  and the deal moves on rather than repeating the first card",
             v0.opening(Formulas.RED), v1.opening(Formulas.RED), "Repertoire.pick");

        /* A repertoire beats the average: with cards present the pooled pressure must not
         * be what lands, or the restructure is decorative. */
        Combatant avgd = fighter();
        new FoeModel(40, press, 100, 2.0, 10, 10).act(avgd, avgd.defenceWeight(),
                                                      fighter(), 0, null);
        live("  and the averaged action is not what lands when cards are present",
             avgd.opening(Formulas.RED), v0.opening(Formulas.RED), "FoeModel.act");

        /* A player's deck read as an opponent, which is what a duel needs. */
        List<Move> deck = new ArrayList<Move>();
        deck.add(base().opens(Formulas.RED, 20).build());
        FoeModel fromDeck = FoeModel.fromDeck(deck, fighter(), 100);
        live("a deck can be read as an opponent", (double)FoeModel.inert().period,
             (double)fromDeck.period, "FoeModel.fromDeck");
    }

    /* ---------------- the things that choose ---------------- */

    static void plannerMechanics() {
        System.out.println();
        System.out.println("the planners");

        /* A DECK WITH A REAL TRADE IN IT, or the frontier has one point and asking for a
         * safer plan can only hand back the fast one. The opponent's damage grows with
         * how open WE are, so a card that closes our own openings buys survival at the
         * cost of a turn not spent attacking - which is the whole of the difference
         * between the two aims. A deck of nothing but attacks has no such choice to
         * make, and the first run of this used one and read the aim as dead. */
        List<Move> deck = new ArrayList<Move>();
        deck.add(base().opens(Formulas.RED, 20).cooldown(20).build());
        deck.add(base().damageShare(1.5).cooldown(80).build());
        deck.add(Move.of("guard").res("guard").kind(Move.Kind.MANEUVER)
                 .weight(Move.Weight.MELEE).damageShare(0)
                 .reduces(Formulas.RED, 0.5).reduces(Formulas.GREEN, 0.5)
                 .reduces(Formulas.BLUE, 0.5).reduces(Formulas.YELLOW, 0.5)
                 .cooldown(30).build());
        /* AND AN OPPONENT WORTH DEFENDING AGAINST. Against something that dies in two
         * cards every plan costs a fraction of a hitpoint, the frontier collapses to one
         * point, and asking for the safer plan can only hand back the faster one - which
         * reads as the aim being ignored when it is the scenario having no choice in it.
         * Swept: the trade appears once the opponent both survives long enough to swing
         * and hits hard enough for the swings to matter. */
        FoeModel foe = new FoeModel(60, new double[] {0, 0, 0, 40.0}, 100, 200.0, 10, 10);

        Combatant weak = fighter();
        weak.hp = weak.maxHp = 900;
        List<Optimizer.Plan> front = Optimizer.search(fighter(), weak, deck, foe, 200, 4000);
        boolean planned = !front.isEmpty();
        System.out.printf("  %-46s %-23s %s%n", "the optimizer returns a plan",
                          planned ? (front.size() + " on the frontier") : "none",
                          planned ? "live in Optimizer.search" : "NOTHING PLANNED");
        if(!planned)
            failures++;

        /* The aim has to change the plan, or the front is one answer wearing three hats. */
        Optimizer.Plan fast = Advisor.choose(front, Advisor.Aim.FASTEST, Double.MAX_VALUE);
        Optimizer.Plan safe = Advisor.choose(front, Advisor.Aim.SAFEST, Double.MAX_VALUE);
        boolean differ = (fast != null) && (safe != null)
            && ((fast.ticks != safe.ticks) || (fast.hpLost != safe.hpLost));
        if((fast != null) && (safe != null)) {
            System.out.printf("      fastest %d ticks costing %.1f; safest %d ticks costing %.1f%n",
                              fast.ticks, fast.hpLost, safe.ticks, safe.hpLost);
        }
        live("the aim picks a different plan off the frontier", differ, "Advisor.choose");

        /* A CROWD IS SEVERAL OPPONENTS AND NOT ONE BIG ONE. Two animals with half the
         * health each is the same total health as one with all of it, and it is not the
         * same fight: an opening pried on the first buys nothing against the second, and a
         * card that hits both collects twice. Both were invisible to the model that pooled
         * them, which is what this one used to do.
         *
         * A WINNABLE ONE, or the probe measures nothing. Against the opponent above, two
         * of it kill this fighter before either falls, and every line then ends the same
         * way - at which point a card that hits both of them cannot show what it is worth
         * because the fight is lost either way. So the crowd here hits softly enough to
         * lose to. */
        FoeModel mob = new FoeModel(60, new double[] {0, 0, 0, 40.0}, 100, 12.0, 10, 10);
        Combatant big = fighter();
        big.hp = big.maxHp = 400;
        Optimizer.Plan bfast = Advisor.choose(
            Optimizer.search(fighter(), big, deck, mob, 200, 4000),
            Advisor.Aim.FASTEST, Double.MAX_VALUE);
        Combatant half1 = fighter(), half2 = fighter();
        half1.hp = half1.maxHp = half2.hp = half2.maxHp = 200;
        Optimizer.Plan cfast = Advisor.choose(
            Optimizer.search(fighter(), new Combatant[] {half1, half2}, deck,
                             new FoeModel[] {mob, mob}, 200, 4000),
            Advisor.Aim.FASTEST, Double.MAX_VALUE);
        live("two opponents are not one with their health added",
             (double)((bfast == null) ? 0 : bfast.ticks),
             (double)((cfast == null) ? 0 : cfast.ticks), "Optimizer.search");

        /* And the card that hits both of them is worth holding, which against a pooled
         * opponent it could not be - there was only ever one thing to hit. The two decks
         * differ in that and nothing else: same damage, same opening, same cooldown, one
         * target or two.
         *
         * IT HAS TO OPEN SOMETHING, and that is a finding rather than a fixture detail.
         * A multi-target card that only deals damage buys nothing at all against a crowd,
         * because damage goes as the SQUARE of the opening it reads and the opponents we
         * have not reached yet are closed - the swing lands on them for nothing. What a
         * splash card is really buying is that they are already open when we get to them.
         * Tried the other way round first: the same probe with a damage-only splash card
         * returned two decks that played identically, card for card. */
        List<Move> sdeck = new ArrayList<Move>(deck);
        sdeck.add(base().opens(Formulas.RED, 20).cooldown(20).targets(2).build());
        List<Move> ndeck = new ArrayList<Move>(deck);
        ndeck.add(base().opens(Formulas.RED, 20).cooldown(20).build());
        Combatant s1 = fighter(), s2 = fighter(), n1 = fighter(), n2 = fighter();
        s1.hp = s1.maxHp = s2.hp = s2.maxHp = n1.hp = n1.maxHp = n2.hp = n2.maxHp = 200;
        Optimizer.Plan sp = Advisor.choose(
            Optimizer.search(fighter(), new Combatant[] {s1, s2}, sdeck,
                             new FoeModel[] {mob, mob}, 200, 4000),
            Advisor.Aim.FASTEST, Double.MAX_VALUE);
        Optimizer.Plan np = Advisor.choose(
            Optimizer.search(fighter(), new Combatant[] {n1, n2}, ndeck,
                             new FoeModel[] {mob, mob}, 200, 4000),
            Advisor.Aim.FASTEST, Double.MAX_VALUE);
        live("  and a card that hits both of them shortens the fight",
             (double)((np == null) ? 0 : np.ticks),
             (double)((sp == null) ? 0 : sp.ticks), "Optimizer.search");

        /* AND A FIGHT WE LOSE IS AN ANSWER, not an empty frontier. A line where the
         * opponent kills us used to be dropped: the swing that followed was refused for
         * being dead, and a refusal ends the line. One against one that quietly lost a few
         * hopeless plans; against a crowd every line ended that way, and the search
         * returned nothing where the answer was "this fight kills you". */
        Combatant k1 = fighter(), k2 = fighter();
        k1.hp = k1.maxHp = k2.hp = k2.maxHp = 450;
        List<Optimizer.Plan> lost =
            Optimizer.search(fighter(), new Combatant[] {k1, k2}, deck,
                             new FoeModel[] {foe, foe}, 200, 4000);
        boolean said = !lost.isEmpty();
        System.out.printf("  %-46s %-23s %s%n", "a fight we lose is reported, not dropped",
                          said ? (lost.size() + " plan(s), none killing") : "nothing",
                          said ? "live in Optimizer.search" : "SILENTLY EMPTY");
        if(!said)
            failures++;

        /* A duel where both sides choose, and where armour changes who dies. */
        Combatant bare = fighter(), clad = fighter();
        clad.armHard = 79;
        clad.armSoft = 67;
        clad.penetrable = true;
        bare.penetrable = true;
        Duel.Outcome n = Duel.play(bare, deck, bare, deck, 4, 1500);
        Duel.Outcome c = Duel.play(clad, deck, clad, deck, 4, 1500);
        live("armour changes how a duel ends", (double)n.ticks, (double)c.ticks,
             "Duel.play");

        /* Moving first is worth something, and the comparison takes it back out. */
        live("the tempo is real", Duel.payoffFirst(bare, deck, bare, deck, 3, 1500), 0.0,
             "Duel.payoffFirst");
        double mirror = Duel.payoff(bare, deck, bare, deck, 3, 1500);
        System.out.printf("  %-46s %-23s %s%n", "  and a mirror comes back exactly even",
                          fmt(mirror), (mirror == 0.0) ? "correct" : "BIAS NOT REMOVED");
        if(mirror != 0.0)
            failures++;

        /* The search must look far enough to price a slow card, which is what a deadline
         * in ticks buys over a count of moves. */
        live("the duel looks ahead in ticks, not in moves", (double)Duel.WINDOW, 0.0,
             "Duel.WINDOW");
    }
}
