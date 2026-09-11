/*
 * Which deck wins PVP, without playing every deck against every deck.
 *
 * THE EXHAUSTIVE VERSION IS NOT LARGE, IT IS IMPOSSIBLE. A deck is at most ten cards and
 * exactly thirty points with at most five on one, so spending the budget needs six cards
 * or more, and exactly one of the seven stances is held. That is 3.3e14 legal decks and
 * 5.6e28 pairs. One duel here costs about three milliseconds, so the full table is 2.6e26
 * seconds, or 8.2e18 years. No amount of patience or hardware closes a gap of nineteen
 * orders of magnitude.
 *
 * WHAT REPLACES IT, AND WHY IT ANSWERS THE SAME QUESTION. The point of playing every deck
 * against every deck is to be sure nothing out there beats your answer. That can be shown
 * without listing them, and this is the standard way: keep a small pool, solve for the
 * best mixture over it, then go and search the WHOLE deck space for anything that beats
 * that mixture. If the search finds something, it joins the pool and the mixture is
 * resolved. When the best deck in 3.3e14 cannot beat the mixture, the mixture is an
 * equilibrium of the full game - and that is exactly the guarantee the exhaustive table
 * was for, reached by a few hundred duels instead of 5.6e28.
 *
 * THE MIXTURE IS THE ANSWER, NOT A DECK. If the equilibrium puts weight on four decks,
 * that is the game saying no single deck is safe against a thinking opponent, which is
 * what the five save slots are for. A single-deck equilibrium would say the opposite.
 *
 * Where it is still approximate, and both of these can only overstate how good the
 * answer is:
 *   - the best-response search is the greedy from CombatDeckSearch, not an exact
 *     maximiser, so "nothing beats the mixture" means "nothing the greedy found".
 *   - both sides look a fixed number of plies ahead. A deeper opponent may see a line
 *     neither side found here.
 *
 * Run:  java CombatMeta [-char NAME] [-rounds N] [-depth D] [-owned]
 */

import haven.combat.Combatant;
import haven.combat.Duel;
import haven.combat.Move;
import haven.combat.data.Pack;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CombatMeta {
    static final long HORIZON = 3000;
    /** Plies for the payoff matrix, where accuracy matters more than speed. */
    static final int JUDGE_DEPTH = 4;
    /** Plies inside the best-response search, which runs thousands of duels. */
    static final int SEARCH_DEPTH = 3;
    /** Fictitious-play iterations. Cheap - it is arithmetic on a small matrix. */
    static final int FP_ITERS = 20000;

    /**
     * The best mixture over the pool, by fictitious play.
     *
     * The game is symmetric and zero-sum: both sides pick a deck, and the payoff to one
     * is minus the payoff to the other. Fictitious play on such a game converges to an
     * equilibrium, and it is a few lines of arithmetic - each step, best-respond to the
     * average of everything the opponent has played so far, and count that response.
     *
     * A mixture rather than a single deck because the game may not have a best deck. If
     * A beats B beats C beats A, any pure choice loses to something, and the equilibrium
     * says so by spreading weight.
     */
    static double[] nash(double[][] p) {
        int n = p.length;
        double[] countA = new double[n], countB = new double[n];
        double[] payA = new double[n], payB = new double[n];
        for(int it = 0; it < FP_ITERS; it++) {
            int ba = 0, bb = 0;
            for(int i = 1; i < n; i++) {
                if(payA[i] > payA[ba])
                    ba = i;
                if(payB[i] > payB[bb])
                    bb = i;
            }
            countA[ba]++;
            countB[bb]++;
            /* A's payoff for each deck against what B just played, and the reverse. The
             * game is symmetric, so B's payoff matrix is the transpose negated. */
            for(int i = 0; i < n; i++) {
                payA[i] += p[i][bb];
                payB[i] += -p[ba][i];
            }
        }
        double[] out = new double[n];
        double tot = 0;
        for(int i = 0; i < n; i++)
            tot += countA[i];
        for(int i = 0; i < n; i++)
            out[i] = (tot > 0) ? (countA[i] / tot) : (1.0 / n);
        return(out);
    }

    /**
     * What a deck is worth against the pool, weighted by the mixture. Higher is better.
     *
     * EACH SIDE WEARS ITS OWN STANCE. This handed the opponent whatever stance the
     * candidate was holding, which is not a small slip now that a stance is worth a
     * factor of five on the block weight - it priced Shield Up's opponent as though they
     * too were behind a shield. The pool carries decks rather than card lists so that
     * each one can be dressed in the stance it actually chose.
     */
    static double against(Pack.Fighter who, Map<String, Move> sheet, CombatDeckSearch.Deck d,
                          List<CombatDeckSearch.Deck> pool, double[] mix, int depth) {
        Combatant me = CombatDeckSearch.withStance(who.combatant(), d, sheet);
        List<Move> deck = d.moves(sheet);
        double v = 0;
        for(int i = 0; i < pool.size(); i++) {
            if(mix[i] <= 1e-6)
                continue;               /* the mixture never plays it; do not pay for it */
            Combatant foe = CombatDeckSearch.withStance(who.combatant(), pool.get(i), sheet);
            v += mix[i] * Duel.payoff(me, deck, foe, pool.get(i).moves(sheet), depth, HORIZON);
        }
        return(v);
    }

    public static void main(String[] argv) throws Exception {
        String charName = "ZzxcuV3";
        int rounds = 6, depth = SEARCH_DEPTH;
        boolean ownedOnly = false;
        for(int i = 0; i < argv.length; i++) {
            if("-char".equals(argv[i]) && ((i + 1) < argv.length))
                charName = argv[++i];
            else if("-rounds".equals(argv[i]) && ((i + 1) < argv.length))
                rounds = Integer.parseInt(argv[++i]);
            else if("-depth".equals(argv[i]) && ((i + 1) < argv.length))
                depth = Integer.parseInt(argv[++i]);
            else if("-owned".equals(argv[i]))
                ownedOnly = true;
        }
        Path root = Paths.get("data", "combat");
        Map<String, Move> sheet =
            CombatDeckSearch.byRes(Pack.moves(root.resolve("moves_sheet.json")));
        Map<String, Pack.Fighter> chars = Pack.characters(root.resolve("characters.json"));
        final Pack.Fighter who = chars.get(charName);
        if(who == null) {
            System.out.printf("no character named %s. known: %s%n", charName, chars.keySet());
            return;
        }
        if(ownedOnly) {
            Map<String, Move> mine = new LinkedHashMap<String, Move>();
            for(Map.Entry<String, Move> e : sheet.entrySet()) {
                if(who.knows(e.getValue().name))
                    mine.put(e.getKey(), e.getValue());
            }
            sheet = mine;
        }
        /* The search's stance rules are about the CHARACTER - which stance dominates
         * depends on the shield in hand and on melee against unarmed - and these tools
         * call build() directly rather than through its main, so they have to set the
         * context themselves or every stance looks equally good. */
        CombatDeckSearch.HELD_SHIELD = who.shield;
        CombatDeckSearch.STANCE_OWNER = who.combatant();
        final Map<String, Move> sh = sheet;

        System.out.printf("both sides are %s (str %.0f, agi %.0f, melee %.0f, hp %.0f, %s)%n",
                          who.name, who.str, who.agi, who.melee, who.hp,
                          (who.weapon == null) ? "bare-handed"
                              : String.format("%s q%.1f", who.weapon, who.weaponQl));
        System.out.printf("%d cards available; lookahead %d plies searching, %d judging%n",
                          sheet.size(), depth, JUDGE_DEPTH);
        System.out.println();
        System.out.println("EVERY DECK AGAINST EVERY DECK IS 5.6e28 PAIRS - 8.2e18 years at three");
        System.out.println("milliseconds each. So instead: hold a pool, solve it for the best");
        System.out.println("mixture, then search all 3.3e14 decks for anything that beats that");
        System.out.println("mixture. Whatever is found joins the pool. When nothing beats it, the");
        System.out.println("mixture is an equilibrium of the whole game - the same guarantee the");
        System.out.println("full table was for.");
        System.out.println();
        System.out.println("Both sides play to win, both look the same distance ahead, and every");
        System.out.println("pairing is played both ways round so that moving first counts for");
        System.out.println("nothing. A mirror scores exactly zero, which is the test that the");
        System.out.println("numbers below are about the cards.");
        System.out.println();

        final Combatant me = who.combatant();
        /* A stance is always held, and the deck search picks which. Until it has, a
         * character with no block weight at all is not a legal fighter. */
        if(me.blockSkill <= 0)
            me.blockSkill = me.melee;

        List<List<Move>> pool = new ArrayList<List<Move>>();
        List<CombatDeckSearch.Deck> decks = new ArrayList<CombatDeckSearch.Deck>();
        List<String> names = new ArrayList<String>();

        /* The pool has to start somewhere, and it starts with the deck that kills a
         * standing target fastest - what someone brings who has not thought about being
         * hit back. Everything after this is a reply to what is already there. */
        CombatDeckSearch.Deck seed = CombatDeckSearch.build(
            sheet, me, who.combatant(), haven.combat.FoeModel.inert(),
            haven.combat.Advisor.Aim.FASTEST);
        decks.add(seed);
        pool.add(seed.moves(sheet));
        names.add("open field");
        System.out.printf("  %-12s %s%n", "seed", CombatDeckSearch.shorten(seed, sheet));

        double[][] pay = new double[1][1];
        double[] mix = {1.0};
        final int fdepth = depth;
        for(int r = 1; r <= rounds; r++) {
            final List<CombatDeckSearch.Deck> fpool =
                new ArrayList<CombatDeckSearch.Deck>(decks);
            final double[] fmix = mix;
            CombatDeckSearch.Deck resp = CombatDeckSearch.build(sh,
                new CombatDeckSearch.Scorer() {
                    public double score(CombatDeckSearch.Deck d) {
                        /* A deck with no stance is not a deck - one is always up. Same
                         * refusal the kill-time scorer makes, for the same reason. */
                        if(!CombatDeckSearch.hasStance(d, sh))
                            return(9e9);
                        if(d.moves(sh).isEmpty())
                            return(9e9);
                        /* Negated: the greedy takes the lowest, and we want the highest
                         * payoff against what the opponent actually plays. */
                        return(-against(who, sh, d, fpool, fmix, fdepth));
                    }
                });
            List<Move> rm = resp.moves(sheet);
            Combatant rc = CombatDeckSearch.withStance(who.combatant(), resp, sheet);
            double gain = against(who, sheet, resp, decks, mix, JUDGE_DEPTH);

            System.out.printf("  %-12s %s%n", "answer " + r,
                              CombatDeckSearch.shorten(resp, sheet));
            System.out.printf("  %-12s scores %+.3f against the current mixture%n", "", gain);

            /* AN AVERAGE OF ZERO HAS TWO VERY DIFFERENT CAUSES and they must not be
             * reported as the same thing. Either the decks are genuinely matched, and
             * each half is near zero; or each side wins the fight it opens, and the
             * halves are +1 and +1. The second is not a draw, it is the model saying the
             * cards did not decide this fight - the tempo did - and an equilibrium
             * declared over a table of those is an equilibrium over nothing.
             *
             * Full decks make this the common case rather than a curiosity: thirty points
             * on both sides kills fast enough that the first swing often settles it. */
            int both = 0, pairs = 0;
            for(int i = 0; i < pool.size(); i++) {
                if(mix[i] <= 1e-6)
                    continue;
                pairs++;
                Combatant fc = CombatDeckSearch.withStance(who.combatant(), decks.get(i), sheet);
                List<Move> fm = decks.get(i).moves(sheet);
                double f1 = Duel.payoffFirst(rc, rm, fc, fm, JUDGE_DEPTH, HORIZON);
                double f2 = Duel.payoffFirst(fc, fm, rc, rm, JUDGE_DEPTH, HORIZON);
                if((f1 > 0.5) && (f2 > 0.5))
                    both++;
            }
            if(both > 0)
                System.out.printf("  %-12s but %d of %d pairing(s) went to whoever swung"
                                  + " first, so that%n  %-12s zero is tempo and not a"
                                  + " matched pair%n", "", both, pairs, "");

            /* THE STOPPING RULE IS THE WHOLE GUARANTEE. If the best deck the search can
             * find does no better than breaking even against the mixture, then nothing it
             * can reach beats the mixture, and the mixture is an equilibrium. Stopping
             * for any other reason - a round count, a repeat - is stopping early, and the
             * report has to say which of the two happened. */
            /* FAILING TO FIND A GOOD DECK IS NOT THE SAME AS THERE NOT BEING ONE, and
             * the difference is visible in the number. A best response that comes back
             * near zero has searched and found nothing better, which is what convergence
             * looks like. One that comes back at minus nine tenths has not converged on
             * anything - it has returned a deck that loses badly, which no maximiser
             * would choose, so the greedy underneath has lost its way.
             *
             * It loses its way here for a reason worth recording: with armour on, almost
             * every pair of decks stalemates and the margins are hundredths. A greedy
             * that climbs on differences that small is walking on a flat surface. */
            if(gain <= -0.25) {
                System.out.println("             which is far WORSE than even. A maximiser does not");
                System.out.println("             return a deck that loses badly, so the greedy has");
                System.out.println("             failed rather than converged - the margins here are");
                System.out.println("             hundredths and it has nothing to climb. Read the pool");
                System.out.println("             below as what was tried, NOT as an equilibrium.");
                break;
            }
            if(gain <= 1e-3) {
                if(both > 0) {
                    System.out.println("             So this is NOT an equilibrium worth the name.");
                    System.out.println("             The decks are not separable at this deck size:");
                    System.out.println("             whoever opens wins, whatever either side brings.");
                } else {
                    System.out.println("             which is no better than even, so nothing the search");
                    System.out.println("             can reach beats the mixture. It is an equilibrium.");
                }
                break;
            }
            decks.add(resp);
            pool.add(rm);
            names.add("answer " + r);

            int n = pool.size();
            double[][] np = new double[n][n];
            for(int i = 0; i < n; i++) {
                for(int j = i + 1; j < n; j++) {
                    Combatant ci = CombatDeckSearch.withStance(who.combatant(), decks.get(i), sheet);
                    Combatant cj = CombatDeckSearch.withStance(who.combatant(), decks.get(j), sheet);
                    double v = Duel.payoff(ci, pool.get(i), cj, pool.get(j),
                                           JUDGE_DEPTH, HORIZON);
                    np[i][j] = v;
                    np[j][i] = -v;
                }
            }
            pay = np;
            mix = nash(pay);
            if(r == rounds)
                System.out.println("             (round limit reached, not convergence)");
        }

        System.out.println();
        System.out.println("THE POOL, PLAYED OFF AGAINST ITSELF");
        System.out.printf("  %-12s %-8s %s%n", "deck", "weight", "against each of the others");
        for(int i = 0; i < pool.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for(int j = 0; j < pool.size(); j++)
                sb.append(String.format("%7.2f", pay[i][j]));
            System.out.printf("  %-12s %-8.3f %s%n", names.get(i), mix[i], sb);
        }
        System.out.println();
        System.out.println("  the mixture, and what it means to play it");
        int used = 0;
        for(int i = 0; i < pool.size(); i++) {
            if(mix[i] > 0.01) {
                used++;
                System.out.printf("    %5.1f%%  %s%n", mix[i] * 100.0,
                                  CombatDeckSearch.shorten(decks.get(i), sheet));
            }
        }
        System.out.println();
        double spread = 0;
        for(int i = 0; i < pool.size(); i++) {
            for(int j = 0; j < pool.size(); j++)
                spread = Math.max(spread, Math.abs(pay[i][j]));
        }
        if(spread < 0.1) {
            System.out.printf("  NOTHING HERE SEPARATES BY MORE THAN %.2f. Every pairing in this"
                              + " pool%n", spread);
            System.out.println("  is close to a stalemate, which is what heavy armour on both");
            System.out.println("  sides does: a Bronze Sword swing loses most of itself to 79");
            System.out.println("  points of hard soak, and neither side can finish. The weights");
            System.out.println("  below are dividing up a difference too small to act on.");
        } else if(used <= 1) {
            System.out.println("  ONE DECK CARRIES THE WHOLE WEIGHT. Against a thinking opponent");
            System.out.println("  there is a single best deck here, and the other slots are free");
            System.out.println("  for the creature decks.");
        } else {
            System.out.printf("  %d DECKS SHARE THE WEIGHT, so there is no single best deck: each"
                              + " one%n", used);
            System.out.println("  loses to something else in the pool. That is what the five saved");
            System.out.println("  slots are for, and the percentages are how often to bring each.");
        }
    }
}
