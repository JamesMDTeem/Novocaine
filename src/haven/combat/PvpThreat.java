package haven.combat;

import java.util.List;

/**
 * The blow a PERSON could land on us next, and the restoration that shrinks it most.
 *
 * WHY A PERSON NEEDS THIS AND A CREATURE DOES NOT. A creature is a measured mix of cards, and its
 * worst swing is one of them. A person holds a finisher until the openings are there and pays for
 * it in initiative we can SEE (the relation's opponent IP). The live advice planned a person as the
 * average of the cards seen from them (FoeModel.fromDeck), which cannot see a card before it is
 * thrown - and the card that ends a fight is thrown once. The three spars with Dunki on 2026-09-26
 * were all lost that way: Quick Barrage into our red (each one earning them a point while our red
 * stood over 25%) or Take Aim and Flex, then a Cleave for 462 and 506 of our 470 hitpoints. At the
 * Cleave our red was 72; at their fourth point it was 47, and a Cleave there is a third of the bar.
 * The damage model was right about every one of those blows (Cleave 461 predicted, 462 landed) -
 * nothing asked it.
 *
 * So the question is asked of every card they plausibly hold - the ones seen, the ones their weapon
 * and stance imply, and a restoration for every colour, which every player can be assumed to carry
 * (James) - at the initiative they hold now plus {@code lookahead}, since a Quick Barrage or Take
 * Aim buys a point before we act again. A restoration is priced with what it hands them: Zig-Zag
 * Ruse gives every opponent two points, so at four it is the card that makes the Cleave affordable.
 *
 * Pure: the model's own combatants and cards; Prediction stages the person.
 */
public final class PvpThreat {
    private PvpThreat() {}

    /** A blow: the card, and what it would deal and wound through our armour. */
    public static final class Blow {
        public final Move card;
        public final double dealt, grievous;

        Blow(Move card, double dealt, double grievous) {
            this.card = card;
            this.dealt = dealt;
            this.grievous = grievous;
        }
    }

    /**
     * The worst blow {@code them} could land on {@code us} with any of {@code cards} it can afford
     * now or after gaining {@code lookahead} more initiative. Read on copies.
     */
    public static Blow worst(Combatant them, Combatant us, List<Move> cards, int lookahead) {
        Blow worst = new Blow(null, 0, 0);
        for(Move f : cards) {
            if(f.stance || !f.deals())
                continue;
            if(them.ip + lookahead < f.ipRequirement())
                continue;
            Combatant t = them.copy();
            t.ip = Math.max(t.ip, f.ipRequirement());
            t.readyAt = 0;
            Combatant u = us.copy();
            Sim s = new Sim(t, u);
            Sim.Result r = s.use(t, f);
            if(r.ok && (r.dealt > worst.dealt))
                worst = new Blow(f, r.dealt, r.grievous);
        }
        return(worst);
    }

    /** What the guard decided, and the blows either side of it. */
    public static final class Call {
        /** The restoration to throw, or null. */
        public final Move move;
        public final Blow now, after;

        Call(Move move, Blow now, Blow after) {
            this.move = move;
            this.now = now;
            this.after = after;
        }
    }

    /**
     * Past {@code cap}, the restoration from {@code ours} that leaves the smallest worst blow,
     * counting what it hands the opponent in initiative - when it brings the blow under the cap or
     * takes at least {@code cut} of it off. Null when nothing does, and the blows say why.
     */
    public static Call guard(Combatant us, Combatant them, List<Move> theirs, List<Move> ours,
                             double cap, double cut, int lookahead) {
        Blow now = worst(them, us, theirs, lookahead);
        if(!(now.dealt > cap))
            return(new Call(null, now, now));
        Move best = null;
        Blow bestAfter = now;
        for(Move r : ours) {
            if(!closesStanding(r, us))
                continue;
            Combatant u = us.copy();
            u.readyAt = 0;
            Combatant t = them.copy();
            Sim s = new Sim(u, t);
            if(!s.use(u, r).ok)
                continue;
            Blow after = worst(t, u, theirs, lookahead);
            if(after.dealt < bestAfter.dealt - 1e-9) {
                best = r;
                bestAfter = after;
            }
        }
        if((best != null) && ((bestAfter.dealt <= cap) || (bestAfter.dealt <= (1.0 - cut) * now.dealt)))
            return(new Call(best, now, bestAfter));
        return(new Call(null, now, bestAfter));
    }

    static boolean closesStanding(Move m, Combatant us) {
        for(int c = 0; c < 4; c++) {
            if((m.reduces[c] > 0) && (us.opening(c) >= 0.01))
                return(true);
        }
        return(false);
    }
}
