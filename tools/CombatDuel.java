/*
 * Two of us, same character, different decks - which deck actually wins?
 *
 * The deck search answers "what kills this animal fastest". That question has a fixed
 * answer because the animal does not adapt. A person does, and a deck that is best
 * against one deck need not be best against the deck built to beat it, so "the best deck"
 * against people is not a thing the search can produce by itself.
 *
 * What can be produced is a best RESPONSE, and iterating that is the standard way in:
 * take a deck, build the deck that beats it, build the deck that beats THAT, and keep the
 * pool. If the chain closes on itself the pool has a stable answer; if it cycles - deck A
 * beats B beats C beats A - then there is no single best deck and the honest output is the
 * cycle, not a winner.
 *
 * WHAT THIS IS NOT. The opponent here throws its deck's AVERAGE action, because that is
 * what FoeModel can express and what everything downstream is checked against. A real
 * player sets up a combination and holds a finisher. So a deck that wins here has beaten
 * an opponent playing its cards in no particular order, which is a lower bound on a
 * competent one - the right direction to be wrong in, but it has to be said out loud
 * rather than left for the reader to discover.
 *
 * Run:  java CombatDuel [-char NAME] [-rounds N] [-owned]
 */

import haven.combat.Advisor;
import haven.combat.Combatant;
import haven.combat.FoeModel;
import haven.combat.Move;
import haven.combat.Optimizer;
import haven.combat.data.Pack;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CombatDuel {
    static final int BEAM = 200, HORIZON = 4000;

    /** One side of a duel: the deck, and the character holding it. */
    static final class Side {
        final String label;
        final CombatDeckSearch.Deck deck;
        final List<Move> moves;

        Side(String label, CombatDeckSearch.Deck deck, Map<String, Move> sheet) {
            this.label = label;
            this.deck = deck;
            this.moves = deck.moves(sheet);
        }
    }

    /**
     * One fight, from the point of view of `a`.
     *
     * Returns the ticks `a` needs to put `b` down, or -1 if it cannot inside the horizon
     * or dies trying. Dying trying is the part the optimizer does not check for itself:
     * it accumulates what we lose and keeps planning past zero, because for a creature
     * matchup the question was how much a kill costs, not whether we outlast it. Here it
     * is exactly whether we outlast it.
     */
    static long killTicks(Side a, Side b, Pack.Fighter who, Map<String, Move> sheet) {
        Combatant me = CombatDeckSearch.withStance(who.combatant(), a.deck, sheet);
        Combatant foe = CombatDeckSearch.withStance(who.combatant(), b.deck, sheet);
        FoeModel model = FoeModel.fromDeck(b.moves, foe, me.defenceWeight());
        List<Optimizer.Plan> front =
            Optimizer.search(me, foe, a.moves, model, BEAM, HORIZON);
        Optimizer.Plan best = null;
        for(Optimizer.Plan p : front) {
            if(!p.killed)
                continue;
            /* Killed it but spent more health than we have: the blow that would have
             * finished it never landed, because we were down first. */
            if(!Double.isNaN(p.hpLost) && (p.hpLost >= me.maxHp))
                continue;
            if((best == null) || (p.ticks < best.ticks))
                best = p;
        }
        return((best == null) ? -1 : best.ticks);
    }

    /** Whether `a` puts `b` down first. Ties and double failures are draws. */
    static int duel(Side a, Side b, Pack.Fighter who, Map<String, Move> sheet) {
        long ta = killTicks(a, b, who, sheet);
        long tb = killTicks(b, a, who, sheet);
        if((ta < 0) && (tb < 0))
            return(0);
        if(tb < 0)
            return(1);
        if(ta < 0)
            return(-1);
        return((ta == tb) ? 0 : ((ta < tb) ? 1 : -1));
    }

    public static void main(String[] argv) throws Exception {
        String charName = "ZzxcuV3";
        int rounds = 5;
        boolean ownedOnly = false;
        for(int i = 0; i < argv.length; i++) {
            if("-char".equals(argv[i]) && ((i + 1) < argv.length))
                charName = argv[++i];
            else if("-rounds".equals(argv[i]) && ((i + 1) < argv.length))
                rounds = Integer.parseInt(argv[++i]);
            else if("-owned".equals(argv[i]))
                ownedOnly = true;
        }
        Path root = Paths.get("data", "combat");
        Map<String, Move> sheet =
            CombatDeckSearch.byRes(Pack.moves(root.resolve("moves_sheet.json")));
        Map<String, Pack.Fighter> chars = Pack.characters(root.resolve("characters.json"));
        Pack.Fighter who = chars.get(charName);
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

        System.out.printf("both sides are %s (str %.0f, agi %.0f, melee %.0f, hp %.0f)%n",
                          who.name, who.str, who.agi, who.melee, who.hp);
        System.out.printf("%d card(s) available; %d round(s) of best response%n%n",
                          sheet.size(), rounds);
        System.out.println("THE OPPONENT PLAYS ITS DECK'S AVERAGE ACTION, not its best line.");
        System.out.println("A real player sets up a combination and holds a finisher; this");
        System.out.println("one throws the deck unordered. So a deck that wins below has");
        System.out.println("beaten a weaker opponent than a person would be. Read it as a");
        System.out.println("lower bound, and read a CYCLE as the real finding: if A beats B");
        System.out.println("beats C beats A, no single deck is the answer and the five saved");
        System.out.println("slots are for covering the cycle, not for picking a winner.");
        System.out.println();

        List<Side> pool = new ArrayList<Side>();
        Combatant base = who.combatant();

        /* Round zero has nothing to respond to, so it responds to a standing target: the
         * fastest killer against an opponent that never acts. That is the deck someone
         * brings who has not thought about being hit back, which is the right thing for
         * the chain to start from and knock down. */
        Combatant dummy = who.combatant();
        CombatDeckSearch.Deck seed = CombatDeckSearch.build(
            sheet, base, dummy, FoeModel.inert(), Advisor.Aim.FASTEST);
        pool.add(new Side("open field", seed, sheet));
        System.out.printf("  %-14s %s%n", "seed", CombatDeckSearch.shorten(seed, sheet));

        for(int r = 1; r <= rounds; r++) {
            Side target = pool.get(pool.size() - 1);
            Combatant foe = CombatDeckSearch.withStance(who.combatant(), target.deck, sheet);
            Combatant me = CombatDeckSearch.withStance(who.combatant(), target.deck, sheet);
            FoeModel model = FoeModel.fromDeck(target.moves, foe, me.defenceWeight());
            CombatDeckSearch.Deck resp = CombatDeckSearch.build(
                sheet, base, foe, model, Advisor.Aim.SAFEST);
            Side s = new Side("answer " + r, resp, sheet);
            /* A response identical to something already in the pool means the chain has
             * closed: the best answer to the current deck is a deck already found, so
             * iterating further only repeats. */
            boolean dup = false;
            for(Side old : pool) {
                if(old.deck.levels.equals(resp.levels))
                    dup = true;
            }
            System.out.printf("  %-14s %s%s%n", "answer " + r,
                              CombatDeckSearch.shorten(resp, sheet),
                              dup ? "   <- already in the pool; the chain has closed" : "");
            if(dup)
                break;
            pool.add(s);
        }

        System.out.println();
        System.out.println("ROUND ROBIN - who beats whom, both sides played by the same character");
        int[] wins = new int[pool.size()];
        int[] losses = new int[pool.size()];
        int[] draws = new int[pool.size()];
        for(int i = 0; i < pool.size(); i++) {
            for(int j = i + 1; j < pool.size(); j++) {
                int r = duel(pool.get(i), pool.get(j), who, sheet);
                if(r > 0) {
                    wins[i]++;
                    losses[j]++;
                } else if(r < 0) {
                    wins[j]++;
                    losses[i]++;
                } else {
                    draws[i]++;
                    draws[j]++;
                }
            }
        }
        System.out.printf("  %-14s %-5s %-5s %-5s %s%n", "deck", "won", "lost", "drew", "cards");
        for(int i = 0; i < pool.size(); i++) {
            System.out.printf("  %-14s %-5d %-5d %-5d %s%n", pool.get(i).label,
                              wins[i], losses[i], draws[i],
                              CombatDeckSearch.shorten(pool.get(i).deck, sheet));
        }

        int unbeaten = 0;
        for(int i = 0; i < pool.size(); i++) {
            if(losses[i] == 0)
                unbeaten++;
        }
        System.out.println();
        if(pool.size() < 2)
            System.out.println("  one deck in the pool - nothing to compare it against.");
        else if(unbeaten == 1)
            System.out.println("  one deck went unbeaten. That is a candidate, not a proof:"
                               + " it beat this pool.");
        else if(unbeaten == 0)
            System.out.println("  nothing went unbeaten - the pool contains a cycle, so there"
                               + " is no single best deck here.");
        else
            System.out.printf("  %d decks went unbeaten, so this pool does not separate them.%n",
                              unbeaten);
    }
}
