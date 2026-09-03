/*
 * Checks for the plan search - haven.combat.Optimizer and FoeModel.
 *
 * NOT part of the client build. Run on demand:
 *
 *   javac -d %TEMP%\optcheck src\haven\combat\*.java tools\CombatOptimizerCheck.java
 *   java -cp %TEMP%\optcheck CombatOptimizerCheck
 *
 * An optimizer is the hardest thing in this project to check, because its output is a
 * recommendation and a recommendation has no obviously right answer. So every case below is
 * built so the answer IS known in advance - a deck with one useful card, a foe that cannot
 * act, a defence that must pay for itself over a long fight and must not over a short one -
 * and the check asserts the known answer rather than whatever the search happens to say.
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import java.util.ArrayList;
import java.util.List;

import haven.combat.Combatant;
import haven.combat.FoeModel;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Optimizer;

public class CombatOptimizerCheck {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? (want == null) : got.equals(want);
        System.out.printf("  %-58s %-16s %s%n", what, got, ok ? "ok" : "WANT " + want);
        if(!ok)
            failures++;
    }

    static void near(String what, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        System.out.printf("  %-58s %-16s %s%n", what, String.format("%.2f", got),
                          ok ? "ok" : String.format("WANT %.2f +/- %.2f", want, tol));
        if(!ok)
            failures++;
    }

    public static void main(String[] args) {
        inertFoe();
        paretoIsNotOneNumber();
        defenceEarnsItsPlace();
        clocksAreSeparate();
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED"
                           : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Combatant me() {
        Combatant c = new Combatant("ZzxcuV3");
        c.str = 94; c.agi = 111; c.unarmed = 81; c.melee = 125;
        c.weaponDamage = 90; c.weaponQl = 28.68; c.weaponPen = 0.125;
        c.hp = c.maxHp = 300;
        c.blockSkill = 125; c.blockMult = 2.5;
        return(c);
    }

    static Combatant foe(double hp, double skill) {
        Combatant c = new Combatant("target");
        c.agi = 111;
        c.hp = c.maxHp = hp;
        c.blockSkill = skill;
        c.blockMult = 1.0;
        return(c);
    }

    /* Quick Barrage: weapon, 25% damage, +10% Cornered, cooldown 20. */
    static Move barrage() {
        return(Move.of("Quick Barrage").kind(Move.Kind.ATTACK).weight(Move.Weight.WEAPON)
               .school(Formulas.RED).opens(Formulas.RED, 10)
               .damageShare(0.25).cooldown(20).build());
    }

    /* Full Circle: weapon at 90%, full damage, two colours, cooldown 40. */
    static Move fullCircle() {
        return(Move.of("Full Circle").kind(Move.Kind.ATTACK).weight(Move.Weight.MELEE)
               .weightMu(0.9).school(Formulas.YELLOW).alsoSchool(Formulas.RED)
               .opens(Formulas.RED, 5).damageShare(1.0).cooldown(40).build());
    }

    /* Quick Dodge at level 1: takes 20% of standing green, cooldown 25, no damage. */
    static Move quickDodge() {
        return(Move.of("Quick Dodge").kind(Move.Kind.MANEUVER)
               .reduces(Formulas.GREEN, 0.20).cooldown(25).build());
    }

    /** A foe that cannot act. The fastest kill is then the only axis, and it is checkable. */
    static void inertFoe() {
        System.out.println("\nagainst an opponent that never acts");
        List<Move> deck = Optimizer.deck(barrage(), fullCircle());
        List<Optimizer.Plan> plans = Optimizer.search(me(), foe(120, 20), deck,
                                                      FoeModel.inert(), 40, 600);
        check("it finds at least one killing plan", !plans.isEmpty(), true);
        boolean allFree = true;
        for(Optimizer.Plan p : plans) {
            if(p.hpLost != 0)
                allFree = false;
        }
        check("every plan costs nothing, since nothing hit us", allFree, true);
        check("and the frontier collapses to one plan on one axis", plans.size(), 1);
        Optimizer.Plan best = plans.get(0);
        check("  which kills", best.killed, true);
        System.out.printf("      %d ticks: %s%n", best.ticks, best);
    }

    /**
     * A foe that hurts. Now there are two axes and the frontier must show the trade.
     *
     * The known answer is structural rather than numeric: with a defensive card in the deck
     * and an opponent that opens us every tick-period, there must exist BOTH a plan that is
     * fastest and a plan that is cheapest, and they must not be the same plan - otherwise
     * the frontier is not doing anything and a single scalar would have sufficed.
     */
    static void paretoIsNotOneNumber() {
        System.out.println("\nagainst an opponent that hits back");
        double[] press = {14, 0, 0, 0};
        FoeModel hard = new FoeModel(30, press, 312.5, 220.0, 20, 20);
        List<Move> deck = Optimizer.deck(barrage(), fullCircle(), quickDodge());
        List<Optimizer.Plan> plans = Optimizer.search(me(), foe(200, 20), deck, hard, 60, 900);
        check("the frontier holds more than one plan", plans.size() > 1, true);
        double fastCost = plans.get(0).hpLost, cheapest = Double.MAX_VALUE;
        long slowest = 0;
        for(Optimizer.Plan p : plans) {
            cheapest = Math.min(cheapest, p.hpLost);
            slowest = Math.max(slowest, p.ticks);
        }
        check("the fastest plan is not also the cheapest", fastCost > cheapest, true);
        check("  so a single objective would have hidden a real choice", slowest
              > plans.get(0).ticks, true);
        for(Optimizer.Plan p : plans)
            System.out.printf("      %4d ticks  %6.1f hp   %s%n", p.ticks, p.hpLost, p);
    }

    /**
     * A defensive card must pay for itself in a long fight and must not in a short one.
     *
     * This is the claim that makes "kill it fastest" insufficient, so it is the one worth
     * pinning. Damage scales with the SQUARE of the opening, and a reduction takes a share,
     * so the payback grows with how much fight is left. Against a target that dies almost
     * at once there is nothing left to protect and defending is pure loss.
     */
    static void defenceEarnsItsPlace() {
        System.out.println("\na defensive card pays back over a long fight, not a short one");
        double[] press = {14, 0, 0, 0};
        FoeModel steady = new FoeModel(45, press, 312.5, 90.0, 20, 20);
        /* ONE deck for both cases. Comparing a deck holding Quick Dodge against a deck
         * without it confounds "defending helps" with "having another card helps", and it
         * also made the first version of this check meaningless: the deck without a defence
         * held Full Circle, which ends the fight fast enough that no defence could pay, and
         * both sides came out at the same 63.5 hitpoints. The claim is about the FRONTIER of
         * a single deck - the cheapest line should defend and the fastest should not. */
        List<Move> deck = Optimizer.deck(barrage(), quickDodge());
        Combatant tough = me();
        tough.hp = tough.maxHp = 900;

        List<Optimizer.Plan> slow = Optimizer.search(tough, foe(400, 20), deck, steady,
                                                     60, 2500);
        check("a long fight leaves a real choice on the frontier", slow.size() > 1, true);
        Optimizer.Plan fastest = slow.get(0), cheapest = slow.get(slow.size() - 1);
        check("  the fastest line does not defend", dodges(fastest), 0L);
        check("  the cheapest line does", dodges(cheapest) > 0, true);
        check("  and it costs fewer hitpoints", cheapest.hpLost < fastest.hpLost, true);
        for(Optimizer.Plan p : slow)
            System.out.printf("      long:  %4d ticks  %6.1f hp  %d dodge(s)%n",
                              p.ticks, p.hpLost, dodges(p));

        /* The MARGINAL RETURN on defending, which is the quantity that actually decides it.
         *
         * "Defending buys nothing in a short fight" was the obvious claim and it is false -
         * a dodge still saves a hitpoint over a hundred ticks, because the opponent still
         * gets two swings in. What collapses is the RATE: the same 25 ticks buys 20 
         * hitpoints in a long fight and one in a short one, because a reduction pays back
         * over whatever remains and a short fight leaves almost nothing.
         *
         * That is the whole reason "kill it fastest" is insufficient as an objective, so it
         * is the thing worth pinning rather than the cruder version of it. */
        List<Optimizer.Plan> quick = Optimizer.search(tough, foe(30, 20), deck, steady,
                                                      60, 600);
        check("a short fight still leaves a choice", quick.size() > 1, true);
        double slowGain = ret(slow), quickGain = ret(quick);
        check("defending pays back far better the longer the fight has to run",
              slowGain > (quickGain * 5), true);
        System.out.printf("      long:  %.3f hp saved per extra tick spent defending%n",
                          slowGain);
        System.out.printf("      short: %.3f%n", quickGain);
        for(Optimizer.Plan p : quick)
            System.out.printf("      short: %4d ticks  %6.1f hp  %d dodge(s)%n",
                              p.ticks, p.hpLost, dodges(p));
    }

    /** Hitpoints saved per extra tick spent, between the fastest and cheapest plans. */
    static double ret(List<Optimizer.Plan> frontier) {
        Optimizer.Plan fast = frontier.get(0), cheap = frontier.get(frontier.size() - 1);
        long extra = cheap.ticks - fast.ticks;
        return((extra <= 0) ? 0.0 : (fast.hpLost - cheap.hpLost) / extra);
    }

    static long dodges(Optimizer.Plan p) {
        long n = 0;
        for(Move m : p.moves) {
            if("Quick Dodge".equals(m.name))
                n++;
        }
        return(n);
    }

    static double cheapest(List<Optimizer.Plan> plans) {
        double c = Double.NaN;
        for(Optimizer.Plan p : plans) {
            if(p.killed && (Double.isNaN(c) || (p.hpLost < c)))
                c = p.hpLost;
        }
        return(c);
    }

    /**
     * The clocks are separate, which is the whole reason this is not turn-based.
     *
     * A move's cooldown is a window the opponent swings in. Doubling every cooldown must
     * therefore cost hitpoints even though the same moves land in the same order - if it
     * does not, the search is alternating turns and the model is wrong.
     */
    static void clocksAreSeparate() {
        System.out.println("\ncooldowns are windows the opponent gets to act in");
        double[] press = {14, 0, 0, 0};
        FoeModel hard = new FoeModel(30, press, 312.5, 220.0, 20, 20);
        List<Move> quick = new ArrayList<Move>();
        quick.add(Move.of("Jab").kind(Move.Kind.ATTACK).weight(Move.Weight.WEAPON)
                  .school(Formulas.RED).opens(Formulas.RED, 10)
                  .damageShare(0.25).cooldown(20).build());
        List<Move> slow = new ArrayList<Move>();
        slow.add(Move.of("Jab").kind(Move.Kind.ATTACK).weight(Move.Weight.WEAPON)
                 .school(Formulas.RED).opens(Formulas.RED, 10)
                 .damageShare(0.25).cooldown(40).build());
        Combatant tough = me();
        tough.hp = tough.maxHp = 900;
        FoeModel steady = new FoeModel(45, press, 312.5, 90.0, 20, 20);
        double fast = cheapest(Optimizer.search(tough, foe(150, 20), quick, steady, 20, 2500));
        double slug = cheapest(Optimizer.search(tough, foe(150, 20), slow, steady, 20, 2500));
        check("both cooldowns still kill it", !Double.isNaN(fast) && !Double.isNaN(slug), true);
        check("the same move on a longer cooldown costs more hitpoints", slug > fast, true);
        System.out.printf("      cooldown 20: %.1f hp lost;  cooldown 40: %.1f%n", fast, slug);
    }
}
