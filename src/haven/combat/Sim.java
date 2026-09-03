package haven.combat;

/**
 * The fight state machine: two combatants, a clock in server ticks, and one operation that
 * applies a move.
 *
 * {@link Formulas} says what a number is; this says when it is read and what it changes. The two
 * are separate because the arithmetic is settled and the sequencing is not - every ordering
 * decision below is a claim about the game, and several of them were chosen only after the
 * corpus ruled on them.
 *
 * Two harnesses hold this to the logs. {@code tools/CombatSimCheck.java} covers the sequencing
 * against figures transcribed by hand, and {@code tools/combat/replay.py} drives the model from
 * the log files themselves, so a new fight becomes new coverage without anyone editing a check.
 * (This javadoc previously named a {@code tools/CombatReplay.java} that was never written.)
 *
 * Deterministic, with no random element anywhere. That is not a simplification: fits of the
 * logged damage against this model return an R-squared of 0.9966 or better, which leaves no room
 * for a variance term. If the game does roll dice, it rolls them too tightly to see.
 *
 * Per ADR-0002 this imports nothing from {@code haven}.
 */
public final class Sim {
    public final Combatant a, b;

    /** Server ticks since the fight began. One tick is 0.06 seconds. */
    public long tick;

    public Sim(Combatant a, Combatant b) {
        this.a = a;
        this.b = b;
    }

    public Combatant other(Combatant c) {
        return((c == a) ? b : a);
    }

    /** What applying a move did, or why it could not be applied. */
    public static final class Result {
        /** False when the move was refused; {@link #why} then says what stopped it. */
        public final boolean ok;
        /** Null on success. */
        public final String why;
        /** Damage before armour, and after it. Both 0 for a move that deals none. */
        public final double raw, dealt;
        /** Hard hitpoints dealt, from the move's grievous share. */
        public final double grievous;
        /** Percentage points actually opened on the target, per colour, after the falloff. */
        public final double[] opened;
        /** The cooldown the actor incurred, in ticks. */
        public final long cooldown;
        /** Initiative the actor and the target ended the move with. */
        public final int actorIp, targetIp;

        private Result(String why) {
            this.ok = false;
            this.why = why;
            this.raw = this.dealt = this.grievous = 0;
            this.opened = new double[4];
            this.cooldown = 0;
            this.actorIp = this.targetIp = 0;
        }

        private Result(double raw, double dealt, double grievous, double[] opened, long cooldown,
                       int actorIp, int targetIp) {
            this.ok = true;
            this.why = null;
            this.raw = raw;
            this.dealt = dealt;
            this.grievous = grievous;
            this.opened = opened;
            this.cooldown = cooldown;
            this.actorIp = actorIp;
            this.targetIp = targetIp;
        }
    }

    /** Whether a move is legal for this actor right now, without applying it. */
    public String refuse(Combatant actor, Move m) {
        if(tick < actor.readyAt)
            return("on cooldown until tick " + actor.readyAt);
        if(actor.ip < m.ipCost)
            return("needs " + m.ipCost + " initiative, has " + actor.ip);
        if(!actor.alive())
            return("dead");
        return(null);
    }

    /**
     * Applies one move by {@code actor} against the other combatant, at the current tick.
     *
     * The order of operations is the part that is a claim about the game rather than about
     * arithmetic, so it is spelled out and justified:
     *
     * 1. Legality, against the state as it stands.
     * 2. The cooldown, computed from the initiative the actor holds BEFORE this move changes it.
     *    Take Aim settles this: it gains a point per use and reports 30, 36, 42, 48, 54 and 60
     *    ticks across a run - each cooldown scaled by the initiative held going in, never by the
     *    point the same use granted.
     * 3. Damage, read against the target's opening in the move's own school as it stands before
     *    this move opens anything further.
     * 4. Openings, which the move's own damage therefore does not benefit from.
     * 5. Reductions, on the actor's own openings - a defensive card closes what is standing
     *    on its user, by a SHARE of it rather than a number of points.
     * 6. Initiative, last, so that a conditional gain is judged on the same opening the damage
     *    was.
     */
    public Result use(Combatant actor, Move m) {
        String no = refuse(actor, m);
        if(no != null)
            return(new Result(no));
        Combatant target = other(actor);

        /* The deck weighting is the move's own, not the actor's: Take Aim's cooldown divides by
         * Take Aim's mu, which says nothing about how the rest of the deck is weighted. */
        long cd = Formulas.cooldownTicks(m.cooldownBase, m.cooldownMu, m.mu, m.ipScale,
                                         actor.ip, m.isAttack(), actor.agi, target.agi);
        actor.readyAt = tick + cd;

        double raw = 0, dealt = 0, grievous = 0;
        if(m.deals() && (m.school >= 0)) {
            /* The opening the attack reads is the combined one over its OWN attack types -
             * one colour for most moves, two for Full Circle and Sting. Never the combined
             * opening over all four: that mistake made the damage coefficient appear to rise
             * with Melee Combat, when what had really happened was that another colour was
             * standing open. */
            double[] own = new double[m.schools.length];
            for(int i = 0; i < own.length; i++)
                own[i] = target.opening(m.schools[i]);
            raw = Formulas.rawDamage(actor.damageBase(m), actor.damageShare(m),
                                     actor.damageQuality(m), actor.str,
                                     Formulas.combined(own));
            /* Armour penetration. A weapon move carries the weapon's; an unarmed move carries
             * the flat 30% that ALL unarmed attacks have.
             *
             * This was a modelling choice giving unarmed moves ZERO, flagged as unmeasurable
             * because the one opponent logged with penetrable armour had no soft soak, where
             * both readings predict the same split. It is not a choice - two sources state it:
             * "Unarmed attacks usually have around 30%" (Jorb, quoted on the wiki) and
             * "UA attacks have a set 30% Armor penetration value" (DDDsDD999's combat guide).
             *
             * The direction matters. Zero understates unarmed damage against anything armoured,
             * so every matchup this model has judged unarmed-versus-armoured was pessimistic -
             * which is the whole "mammoth with a weapon, or unarmed with Knock Its Teeth Out"
             * question this project exists to answer. */
            double pen = target.penetrable
                ? ((m.damageShare > 0) ? actor.weaponPen : Formulas.UNARMED_ARMPEN)
                : 0.0;
            dealt = Formulas.dealtDamage(raw, target.armHard, target.armSoft, pen);
            /* One observation, from a boar: 17 soft hitpoints alongside 4 hard, against a move
             * listed at 25% grievous. That reads as a share of the damage that got through
             * rather than of the damage swung, but one observation is one observation. */
            grievous = dealt * m.grievous;
            target.hp -= dealt;
        }

        /* The conditional-gain test is taken here, before the move's own openings land. */
        boolean gains = (m.gainColour < 0) || (target.opening(m.gainColour) > m.gainAbove);

        /* Reductions land before the openings this move inflicts, and on the ACTOR - a
         * defensive card closes its user's own openings. Order matters only for a card
         * that both reduces and opens the same colour on itself, which nothing in the
         * sheet does today; put here because a card cannot benefit from an opening it
         * gives itself in the same use. */
        for(int c = 0; c < 4; c++) {
            if(m.reduces[c] > 0)
                actor.close(c, m.reduces[c] * m.mu);
        }

        double[] opened = new double[4];
        for(int c = 0; c < 4; c++) {
            if(m.openings[c] > 0) {
                opened[c] = Formulas.openingGain(actor.attackWeight(m), target.defenceWeight,
                                                 m.openings[c], target.opening(c));
                target.open(c, opened[c]);
            }
            if(m.openingsSelf[c] > 0) {
                actor.open(c, Formulas.openingGain(actor.attackWeight(m), actor.defenceWeight,
                                                   m.openingsSelf[c], actor.opening(c)));
            }
        }

        actor.ip -= m.ipCost;
        if(gains)
            actor.ip += m.ipGain;
        target.ip += m.foeIpGain;

        return(new Result(raw, dealt, grievous, opened, cd, actor.ip, target.ip));
    }

    /** The earliest tick at which either side can act. */
    public long nextTick() {
        long t = Math.min(a.readyAt, b.readyAt);
        return((t < tick) ? tick : t);
    }

    /** Moves the clock forward. Never backward - a replay feeding times out of order is a bug. */
    public void advanceTo(long t) {
        if(t < tick)
            throw(new IllegalArgumentException("cannot rewind from " + tick + " to " + t));
        tick = t;
    }

    public boolean over() {
        return(!a.alive() || !b.alive());
    }
}
