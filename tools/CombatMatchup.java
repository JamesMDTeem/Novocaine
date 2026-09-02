/*
 * Answers "can I take this thing, and with what", by simulating the fight.
 *
 *   javac -d %TEMP%\matchup -sourcepath src src\haven\combat\data\Pack.java tools\CombatMatchup.java
 *   java -cp %TEMP%\matchup CombatMatchup badger
 *   java -cp %TEMP%\matchup CombatMatchup            (every opponent the corpus knows)
 *
 * NOT part of the client build - build.xml compiles src/ only.
 *
 * Every opponent stat the corpus produced is an interval, so every fight here is simulated
 * twice: once against the toughest reading the data allows and once against the weakest. A
 * matchup that only wins against the weakest has the answer "not known", and this says so
 * rather than picking a midpoint and sounding certain.
 *
 * The policy is deliberately dumb - always throw the highest-damage move that is legal right
 * now. That is not the optimizer; it is the floor the optimizer has to beat, and having it
 * first means the optimizer can be measured against something instead of admired.
 */

import haven.combat.Combatant;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Sim;
import haven.combat.data.Pack;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CombatMatchup {
    static final Path MOVES = Paths.get("data", "combat", "moves_sheet.json");
    static final Path FOES = Paths.get("data", "combat", "opponents.json");

    /* The character that fought the corpus, from the log headers and its gear dump. */
    static Combatant me() {
        Combatant c = new Combatant("me");
        c.str = 82; c.agi = 81; c.unarmed = 58; c.melee = 111;
        c.weaponDamage = 90; c.weaponQl = 28.68; c.weaponPen = 0.125;
        c.armHard = 5; c.armSoft = 2;
        c.hp = c.maxHp = 100;
        c.mu = 1.0;
        return(c);
    }

    /** One simulated fight, run to a kill or to a stalemate. */
    static final class Outcome {
        long ticks;
        int swings;
        double dealt;
        boolean killed;
        String stalled;
        final List<String> opening = new ArrayList<String>();
    }

    /* A fight that has stopped making progress will never start again: this model has no
     * randomness, so an unchanged state produces an unchanged decision forever. Capping on
     * simulated time rather than iterations keeps the number meaningful in the report. */
    static final long MAX_TICKS = 100 * 60 / 6 * 10;

    static Outcome fight(Combatant me, Combatant foe, List<Move> deck) {
        Sim sim = new Sim(me, foe);
        Outcome out = new Outcome();
        double before = foe.hp;
        while(foe.alive() && (sim.tick < MAX_TICKS)) {
            Move best = null;
            double bestDmg = -1;
            for(Move m : deck) {
                if(sim.refuse(me, m) != null)
                    continue;
                /* Score on what the move would actually do from here, which for an attack
                 * into a closed opening is nothing - so a deck of pure attacks correctly
                 * prefers whichever one opens the colour it also reads. */
                double d = 0;
                if(m.deals() && (m.school >= 0)) {
                    double[] own = new double[m.schools.length];
                    for(int i = 0; i < own.length; i++)
                        own[i] = foe.opening(m.schools[i]);
                    d = Formulas.dealtDamage(
                        Formulas.rawDamage(me.damageBase(m), me.damageShare(m),
                                           me.damageQuality(m), me.str,
                                           Formulas.combined(own)),
                        foe.armHard, foe.armSoft,
                        (m.damageShare > 0) ? me.weaponPen : 0.0);
                }
                /* Tie-break towards opening, so an opening move is thrown when nothing can
                 * yet hurt - otherwise the deck's first attack is chosen forever and the
                 * fight never starts. */
                double score = (d * 1000) + m.openings[Math.max(0, m.school)];
                if(score > bestDmg) {
                    bestDmg = score;
                    best = m;
                }
            }
            if(best == null) {
                /* Nothing legal now, so wait for OUR cooldown - not Sim.nextTick(), which
                 * answers for either side and so returns the current tick whenever the
                 * opponent could act. This loop drives one side only. */
                if(me.readyAt <= sim.tick) {
                    out.stalled = "no legal move and no cooldown to wait for";
                    break;
                }
                sim.advanceTo(me.readyAt);
                continue;
            }
            sim.advanceTo(Math.max(sim.tick, me.readyAt));
            Sim.Result r = sim.use(me, best);
            if(!r.ok) {
                out.stalled = "refused: " + r.why;
                break;
            }
            out.swings++;
            out.dealt += r.dealt;
            if(out.swings <= 6)
                out.opening.add(String.format("%s %.0f", best.name, r.dealt));
        }
        out.ticks = sim.tick;
        out.killed = !foe.alive();
        if((out.stalled == null) && !out.killed)
            out.stalled = "no kill within " + Formulas.ticksToSeconds(MAX_TICKS) + " s";
        if(before <= 0)
            out.stalled = "opponent has no known hitpoints";
        return(out);
    }

    static String describe(Outcome o) {
        if(!o.killed)
            return("no kill - " + o.stalled);
        return(String.format("%d swings, %.0f s, %.0f damage",
                             o.swings, Formulas.ticksToSeconds(o.ticks), o.dealt));
    }

    public static void main(String[] args) throws Exception {
        Map<String, Move> moves = Pack.moves(MOVES);
        Map<String, Pack.Opponent> foes = Pack.opponents(FOES);

        /* The deck the corpus was fought with. */
        List<Move> deck = new ArrayList<Move>();
        for(String n : new String[] {"Quick Barrage", "Knock Its Teeth Out", "Full Circle"}) {
            Move m = moves.get(n);
            if(m != null)
                deck.add(m);
        }
        System.out.println("deck: " + deck);
        System.out.println("policy: always the highest-damage legal move\n");

        System.out.printf("%-14s %-6s %-34s %-34s%n",
                          "opponent", "eng", "vs the toughest reading", "vs the weakest reading");
        System.out.println("-".repeat(92));
        for(Pack.Opponent o : foes.values()) {
            if(!o.simulable()) {
                System.out.printf("%-14s %-6d %s%n", o.name, o.engagements,
                                  "not enough measured - " + missing(o));
                continue;
            }
            Outcome easy = fight(me(), o.weakest(), deck);
            if(!o.hpBounded()) {
                /* It survived everything we ever did to it, so nothing caps its health.
                 * The floor is a floor, not an answer. */
                System.out.printf("%-14s %-6d %-34s %-34s%n", o.name, o.engagements,
                                  "no ceiling on its hitpoints", describe(easy));
                continue;
            }
            Outcome hard = fight(me(), o.toughest(), deck);
            System.out.printf("%-14s %-6d %-34s %-34s%n",
                              o.name, o.engagements, describe(hard), describe(easy));
            if(hard.killed != easy.killed)
                System.out.printf("%-21s %s%n", "",
                                  "-> the corpus does not settle this matchup");
        }
        System.out.println();
        for(Pack.Opponent o : foes.values()) {
            if(o.simulable()) {
                Outcome hard = fight(me(), o.toughest(), deck);
                if(!hard.opening.isEmpty())
                    System.out.printf("  %-12s opens: %s%n", o.name,
                                      String.join(", ", hard.opening));
            }
        }
    }

    static String missing(Pack.Opponent o) {
        List<String> want = new ArrayList<String>();
        if(Double.isNaN(o.dwLo))
            want.add("defence weight");
        if(Double.isNaN(o.hpLo))
            want.add("hitpoints");
        return("no " + String.join(", no ", want));
    }
}
