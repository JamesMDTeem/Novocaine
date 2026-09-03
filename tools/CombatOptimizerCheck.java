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

    /**
     * A fleeing opponent has stopped fighting, so nothing bought after that buys anything.
     *
     * An animal that has taken enough extends an olive branch and runs, and it stops
     * swinging when it does. Every point of initiative and every reduction spent past that
     * moment is spent on an opponent that cannot hurt us. The frontier should therefore
     * stop trading time for hitpoints once the flight starts - the cheapest plan and the
     * fastest plan converge, because there is no longer anything to be cheap about.
     */
    static void fleeing() {
        System.out.println("\na fleeing opponent stops fighting, so defence stops paying");
        double[] press = {14, 0, 0, 0};
        List<Move> deck = Optimizer.deck(barrage(), quickDodge());
        Combatant tough = me();
        tough.hp = tough.maxHp = 900;

        FoeModel stands = new FoeModel(45, press, 312.5, 90.0, 20, 20);
        FoeModel runs = new FoeModel(45, press, 312.5, 90.0, 20, 20, 0.60);
        Combatant a = foe(400, 20), b = foe(400, 20);
        check("one that fights to the death is not fleeing at full health",
              stands.fleeing(a), false);
        b.hp = 200;
        check("one that runs at 60% is, once it is down to half", runs.fleeing(b), true);

        List<Optimizer.Plan> fight = Optimizer.search(tough, a, deck, stands, 60, 2500);
        List<Optimizer.Plan> flee = Optimizer.search(tough, b == null ? a : foe(400, 20),
                                                     deck, runs, 60, 2500);
        double fightSpread = spread(fight), fleeSpread = spread(flee);
        check("against one that fights, the frontier trades time for hitpoints",
              fightSpread > 0, true);
        check("against one that runs, there is far less left to trade",
              fleeSpread < fightSpread, true);
        System.out.printf("      fights back: %d plan(s), %.1f hp between fastest and cheapest%n",
                          fight.size(), fightSpread);
        System.out.printf("      runs at 60%%: %d plan(s), %.1f hp%n", flee.size(), fleeSpread);
        double a1 = cheapest(fight), a2 = cheapest(flee);
        check("and it costs less overall", a2 < a1, true);
        System.out.printf("      cheapest: %.1f hp against one that fights, %.1f against one that runs%n",
                          a1, a2);
    }

    /**
     * How much initiative is worth bringing, which is a question about the PROLOGUE.
     *
     * Take Aim is thrown at range before the fight, kiting, where the time is nearly free.
     * So the question is not what a point costs but how many to collect, and the answer has
     * to diminish: a deck that will throw one initiative-spender wants one point, and a
     * second buys nothing. If the curve did not flatten, the search would be claiming that
     * initiative helps without being spent.
     */
    static void startingInitiative() {
        System.out.println("\nhow much initiative is worth bringing to the fight");
        double[] press = {14, 0, 0, 0};
        FoeModel steady = new FoeModel(45, press, 312.5, 90.0, 20, 20);
        /* One spender, costing 2 - so the second point is the last one that can matter. */
        Move kito = Move.of("Knock Its Teeth Out").kind(Move.Kind.ATTACK)
            .weight(Move.Weight.UNARMED).school(Formulas.RED).opens(Formulas.RED, 20)
            .flatDamage(30).ipCost(2).cooldown(35).build();
        List<Move> deck = Optimizer.deck(barrage(), kito);
        Combatant tough = me();
        tough.hp = tough.maxHp = 900;
        /* Beam 120, not 40. At 40 this curve RISES at four points - 95.4 against 63.5 at
         * two - which cannot be true, since a plan available with two points is available
         * with four and the extra goes unspent. The search simply missed it. */
        double[] v = Optimizer.valueOfStartingIp(tough, foe(300, 20), deck, steady, 120,
                                                 2500, 4);
        for(int i = 0; i < v.length; i++)
            System.out.printf("      %d IP: %s hp%n", i,
                              Double.isNaN(v[i]) ? "no kill" : String.format("%.1f", v[i]));
        check("bringing initiative never costs hitpoints, at any amount",
              Optimizer.beamWasEnough(v), true);
        check("  and the pool that gets spent is where the gain is", v[2] < v[0], true);
        check("  past which a further point buys nothing", v[4], v[2]);
        check("and the return diminishes rather than running away",
              (v[0] - v[2]) >= (v[2] - v[4]) - 1e-9, true);
        /* The same curve at a beam that is too narrow must be REJECTED rather than used. */
        double[] narrow = Optimizer.valueOfStartingIp(tough, foe(300, 20), deck, steady, 40,
                                                      2500, 4);
        check("a too-narrow beam is caught rather than trusted",
              Optimizer.beamWasEnough(narrow), false);
    }

    /** Hitpoints between the fastest plan on a frontier and the cheapest. */
    static double spread(List<Optimizer.Plan> f) {
        if(f.size() < 2)
            return(0);
        return(f.get(0).hpLost - f.get(f.size() - 1).hpLost);
    }

    public static void main(String[] args) {
        inertFoe();
        fleeing();
        startingInitiative();
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
