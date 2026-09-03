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
 *
 * The second half of the report runs the optimizer against the OPPONENT'S OWN MODEL, loaded
 * from the pack's threat block. Until that block existed there was nothing on the other side
 * of the fight: every plan cost zero hitpoints, so "least damage taken" ranked everything
 * equally and the frontier collapsed to whatever was fastest. That is why the greedy floor
 * came first and why it is still printed - a frontier that does not beat it is not evidence
 * of a good plan, it is evidence of a broken search.
 */

import haven.combat.Combatant;
import haven.combat.FoeModel;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Optimizer;
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

        System.out.println("\nthe optimizer, against each opponent's own model");
        System.out.println("time and hitpoints, every plan that is not beaten on both");
        System.out.println("-".repeat(92));
        int modelled = 0, planned = 0, noSkill = 0, equalized = 0, noCeiling = 0;
        for(Pack.Opponent o : foes.values()) {
            if(o.threat != null)
                modelled++;
            if(!o.simulable() || !o.hpBounded()) {
                if(!o.hasSkill)
                    noSkill++;
                else if(o.skillEqualized || o.skillDisputed)
                    equalized++;
                else if(!o.hpBounded())
                    noCeiling++;
                continue;
            }
            planned++;
            frontier(o, deck);
        }
        /* Counted over EVERY opponent, not inside the loop above, which is where this was
         * first written and where it was wrong by a factor of ten: the loop skips anything
         * unsimulable, so it reported 3 threat models out of 35 when there are 30. The
         * number that was really being reported is `planned`, and the two are worth
         * keeping apart because they say different things about what is missing. */
        System.out.printf("%n%d of %d opponents carry a threat model - we know what they"
                          + " do to us.%n", modelled, foes.size());
        System.out.printf("%d of those can be planned against. What stops the rest:%n%n",
                          planned);
        /* Counted apart, because they are three different problems with three different
         * fixes and lumping them produced a confidently wrong diagnosis: this once blamed
         * equalization for all of them, when equalization accounts for one. */
        if(noSkill > 0) {
            System.out.printf("  %2d  no combat skill recovered at all. Not a thin"
                              + " measurement - NO%n", noSkill);
            System.out.println("      measurement: every fight we have against them is");
            System.out.println("      contaminated, because they swarm and the gate that");
            System.out.println("      keeps openings attributable needs us alone with one");
            System.out.println("      of them. Fighting more of them the same way adds");
            System.out.println("      nothing. Fighting ONE of them, away from the rest,");
            System.out.println("      adds everything.");
        }
        if(equalized > 0) {
            System.out.printf("  %2d  skill only bounded, not named - equalization. Their"
                              + " skill is%n", equalized);
            System.out.println("      within a factor of two of ours, so every gain we");
            System.out.println("      logged was pinned and returned our own weight. More");
            System.out.println("      fights will not fix this either. Equalization compares");
            System.out.println("      SKILLS, so no change of card escapes it - only a");
            System.out.println("      different school, or a trained one.");
        }
        if(noCeiling > 0) {
            System.out.printf("  %2d  nothing caps its hitpoints - it survived everything we"
                              + "%n", noCeiling);
            System.out.println("      ever did. Killing one settles it.");
        }
    }

    /* Wide enough that the initiative curve comes out monotone - see
     * Optimizer.beamWasEnough, which exists because a narrower beam silently reported that
     * four initiative was WORSE than two. */
    static final int BEAM = 120;

    /**
     * The frontier against one opponent, printed.
     *
     * Two numbers, not one. "Best" is not a thing a fight has: a plan that kills in nine
     * seconds for forty hitpoints and one that takes twenty seconds for six are both
     * correct answers, and which is wanted depends on what else is nearby and how far the
     * hearth is. Collapsing them into a single score would be picking for the player.
     */
    static void frontier(Pack.Opponent o, List<Move> deck) {
        FoeModel model = o.threat;
        if(model == null) {
            System.out.printf("  %-14s no threat model - never seen it act on us%n", o.name);
            return;
        }
        List<Optimizer.Plan> all =
            Optimizer.search(me(), o.toughest(), deck, model, BEAM, MAX_TICKS);
        List<Optimizer.Plan> front = Optimizer.frontier(all);
        if(front.isEmpty()) {
            System.out.printf("  %-14s no plan kills it within the cap%n", o.name);
            return;
        }
        /* The hitpoint figures below are the whole point of the frontier and they are the
         * thinnest thing in this report, so the evidence travels with them. A "1.4 hp"
         * that rests on four observed gaps and one landed hit is not the same claim as
         * one resting on four hundred, and printed bare the two look identical. */
        System.out.printf("  %-14s acts every %d ticks (%d gap%s), damage from %d hit%s%n",
                          o.name, model.period, model.nGaps, model.nGaps == 1 ? "" : "s",
                          model.nHits, model.nHits == 1 ? "" : "s");
        if(model.multiClock()) {
            StringBuilder sb = new StringBuilder();
            for(int m : model.modes)
                sb.append(sb.length() == 0 ? "" : " and ").append(m);
            System.out.println("      two clocks, " + sb + " ticks - the period above is a"
                               + " blend it never actually exhibits");
        }
        if(!model.knowsDamage()) {
            System.out.println("      it has never landed on us, so damage taken reads zero"
                               + " because it is UNMEASURED, not because it is safe");
        } else if((model.nHits < 10) || (model.nGaps < 10)) {
            System.out.println("      THIN - too few observations to trust the hitpoint"
                               + " column; the ordering is likely right and the numbers"
                               + " are not");
        }
        for(Optimizer.Plan pl : front) {
            System.out.printf("      %5.1f s  %6.1f hp   %s%n",
                              Formulas.ticksToSeconds(pl.ticks), pl.hpLost,
                              names(pl.moves));
        }
    }

    static String names(List<Move> ms) {
        List<String> out = new ArrayList<String>();
        for(Move m : ms)
            out.add(m.name);
        if(out.size() > 8)
            return(String.join(", ", out.subList(0, 8)) + ", ...");
        return(String.join(", ", out));
    }

    static String missing(Pack.Opponent o) {
        List<String> want = new ArrayList<String>();
        if(o.skillEqualized) {
            /* Not a gap in the corpus - a fact about the matchup. Its combat skill is
             * within a factor of two of ours, so every gain we logged equalized and told
             * us only that it is somewhere in that band. Simulating the midpoint would be
             * inventing the number the estimator refused to invent. */
            want.add(String.format(java.util.Locale.ROOT,
                                   "skill only bounded to %.0f-%.0f (equalized with ours)",
                                   o.skillLo, o.skillHi));
        } else if(o.skillDisputed) {
            want.add("its moves disagree on its skill - some equalized, some did not");
        } else if(!o.hasSkill || Double.isNaN(o.skill)) {
            want.add("no combat skill");
        }
        if(Double.isNaN(o.hpLo))
            want.add("no hitpoints");
        return(String.join(", ", want));
    }
}
