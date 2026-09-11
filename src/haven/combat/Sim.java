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

    /**
     * What {@link #use} WOULD do, without doing it.
     *
     * The point of this is that a prediction can be written into the log at the moment a move
     * is thrown, so that the residual between what the model expected and what happened is a
     * FACT IN THE FILE rather than something recomputed later. Recomputing is not the same
     * thing and is worse in a way that hides itself: every change to the data pack silently
     * rewrites the history, so a fix can never be shown to have helped, because the "before"
     * number moves with it.
     *
     * It runs the real {@link #use} against copies rather than reimplementing it. A separate
     * prediction path would be a second copy of the sequencing rules, and the two would drift -
     * at which point the residual measures the drift instead of the game.
     */
    public Result predict(Combatant actor, Move m) {
        Combatant ca = a.copy(), cb = b.copy();
        Sim ghost = new Sim(ca, cb);
        ghost.tick = tick;
        return(ghost.use((actor == a) ? ca : cb, m));
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
     * The damage half of a swing, against ONE target.
     *
     * Extracted so that an attack landing on several opponents deals its damage the same
     * way to each of them rather than through a second copy of these rules. The copy is
     * the thing to avoid: the armour ramp, the penetration split and the overkill cap are
     * all claims about the game that took measurement to settle, and a splash path with
     * its own version of them would drift from this one silently.
     *
     * @param scale what share of the swing this target takes - see Move.targetDamage.
     * @return raw, dealt and grievous, in that order.
     */
    private double[] strike(Combatant actor, Move m, Combatant target, double scale) {
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
            /* WHAT THIS TARGET TAKES, which is not always a full swing: Storm of
             * Swords gives its five targets 100%, 125%, 150%, 175% and 200% of the
             * weapon's damage. Applied to the raw swing before armour, because that is
             * what the sheet says it scales - the target's own armour and its own
             * opening are read normally underneath it. */
            raw *= scale;

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
            /* Capped by what is left, which is not a rounding detail. "If you hit someone
             * for 1000, but they only have 100 HP, it's treated as 100 damage, and they
             * will only lose 40 HHP." Uncapped, an overkill blow reports hard hitpoints
             * that the game never takes - and hard hitpoints are what decide whether a
             * fight leaves a lasting wound, so the error lands squarely on the question a
             * matchup is asked. */
            grievous = Math.min(dealt, Math.max(0, target.hp)) * m.grievous;
            target.hp -= dealt;
        }

        return(new double[] {raw, dealt, grievous});
    }

    /**
     * The opening half of a swing, against ONE target. See {@link #strike}.
     *
     * Openings are NOT scaled by the target's damage share. Storm of Swords escalates what
     * its later targets take in damage and says nothing about what it opens on them, and
     * the corpus shows a multi-target card raising openings on up to five opponents at
     * once without any sign of a gradient. So each target is opened as though it were the
     * only one.
     *
     * @return percentage points actually opened, per colour, after the falloff.
     */
    private double[] land(Combatant actor, Move m, Combatant target) {
        return(land(actor, m, target, 1.0));
    }

    /**
     * @param reach how much of this target the attack got, 0..1 - see Formulas.SWEEP_REACH.
     *              1.0 for the main target and for anything the model is sure of.
     */
    private double[] land(Combatant actor, Move m, Combatant target, double reach) {
        /* Opportunity Knocks, and nothing else in the sheet. It multiplies the single
         * greatest standing opening, taking neither the weight ratio nor the (1 - Oc)
         * falloff that every openings line takes - so it cannot go through the loop below,
         * and putting it there would silently apply both.
         *
         * Before that loop, so it reads the opening the target ARRIVED with rather than
         * one this same use just made. A card that boosts what it also opens would
         * otherwise compound with itself in a single tick, which no card is documented to
         * do and which would be invisible in the result. */
        if(m.boostGreatest > 0) {
            int best = -1;
            for(int c = 0; c < 4; c++) {
                if((best < 0) || (target.opening(c) > target.opening(best)))
                    best = c;
            }
            if((best >= 0) && (target.opening(best) > 0)) {
                /* opening() is a fraction and open() takes percentage POINTS. Mixing them
                 * here made a 50-point opening grow by a fifth of a point instead of by
                 * twenty, which is a boost of nothing at all - and it would have read as a
                 * card that simply does not work rather than as a unit error. */
                double points = target.opening(best) * 100.0;
                target.open(best, points * m.boostGreatest * m.mu);
            }
        }
        double[] opened = new double[4];
        for(int c = 0; c < 4; c++) {
            if(m.openings[c] > 0) {
                /* The SKILLS equalize and the multipliers do not, so the two halves go in
                 * separately. Passing a lumped Wa and Wd here was wrong in a way that only
                 * showed against an opponent near our own skill: inside the equalization
                 * band the skill ratio is pinned to 1, and a lumped call cannot express
                 * that. */
                opened[c] = Formulas.openingGainEq(
                    actor.skill(m.weight), m.weightMu * m.mu * actor.attackMult,
                    target.blockSkill, target.blockMult,
                    m.openings[c] * reach, target.opening(c));
                target.open(c, opened[c]);
            }
        }
        return(opened);
    }

    /** What the target's own stance does to whoever just swung. See {@link #use}. */
    private void parried(Combatant actor, Move m, Combatant target) {
        /* WHAT THE DEFENDER'S STANCE DOES TO WHOEVER JUST SWUNG. Parry opens the attacker
         * when it is attacked, which is not something the attacker's card can express and
         * not something the stance can do by being thrown, since a stance is never thrown.
         * So it is read off the target here, at the moment the blow resolves.
         *
         * Optimizer keeps its own copy of this, applied when the opponent acts. That is
         * not a duplicate: against a creature the opponent acts through a FoeModel and
         * never enters this method, so the two paths cover different fights and neither
         * fires twice. It also means only this one can tell an attack from a maneuver -
         * a FoeModel action carries no card, so the Optimizer's copy applies to every
         * action the creature takes and this one applies only to a swing, which is what
         * "when attacked" says.
         *
         * A weapon is required, which is measured rather than assumed - the corpus shows
         * the opening only where the defender was armed. */
        if(m.isAttack() && target.armed())
            trigger(actor, target.whenAttacked);
    }

    /**
     * A stance's answer to a blow, landing on whoever swung.
     *
     * ONE PLACE, because there were two and they disagreed. This path - a player swinging
     * at a player - handed over the full ten points however open the target already was,
     * while the optimizer's copy, which is the one that fires when a CREATURE swings at
     * us, took the falloff. Parry was therefore worth more in a duel than in a hunt for no
     * reason but which method the blow went through, and the player-versus-player answer
     * is exactly where a stance is being chosen between Parry and Shield Up.
     *
     * The falloff is the version kept. Every openings line in the game takes it - an
     * attack opens a share of what is still CLOSED - and the sheet writes this one as an
     * openings line like any other, under a condition. What is left out is the weight
     * ratio the same formula carries, because there is nobody to read it from: a stance
     * declares no attack weight, and inventing one would be a guess where the falloff is
     * a rule.
     */
    public static void trigger(Combatant on, double[] pct) {
        if(pct == null)
            return;
        for(int c = 0; c < 4; c++) {
            if(pct[c] > 0)
                on.open(c, pct[c] * (1.0 - on.opening(c)));
        }
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

        double[] hit = strike(actor, m, target, 1.0);
        double raw = hit[0], dealt = hit[1], grievous = hit[2];

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
        double[] opened = land(actor, m, target);
        /* Openings this move puts on its USER, which happen once however many opponents it
         * lands on - they are the user's own exposure, not something each target does. */
        for(int c = 0; c < 4; c++) {
            if(m.openingsSelf[c] > 0) {
                actor.open(c, Formulas.openingGainEq(
                    actor.skill(m.weight), m.weightMu * m.mu * actor.attackMult,
                    actor.blockSkill, actor.blockMult,
                    m.openingsSelf[c], actor.opening(c)));
            }
        }

        parried(actor, m, target);

        actor.ip -= m.ipCost;
        if(gains)
            actor.ip += m.ipGain;
        target.ip += m.foeIpGain;

        return(new Result(raw, dealt, grievous, opened, cd, actor.ip, target.ip));
    }

    /**
     * The same attack, against one of the OTHER opponents it lands on.
     *
     * Called once per extra target after {@link #use} has resolved the main one, because
     * three cards in the sheet hit more than one: Full Circle hits everything in range,
     * Punch 'em Both hits one more, and Storm of Swords hits up to five for a rising share
     * of the weapon. See Move.targets for the measurement that confirmed it happens.
     *
     * What an extra target gets is the damage and the openings, and nothing else. The
     * cooldown is the swing's and is paid once. The reductions are on the user and happen
     * once. The initiative is the part worth being explicit about: this model carries one
     * initiative number per combatant, while the game keeps it per relation - so a card
     * that gains a point "against your opponent" would, applied per target, hand out one
     * point per body standing nearby. It is applied to the main target only, which is the
     * conservative reading and the one the prose supports.
     *
     * @param index which target this is, counting the main one as zero.
     */
    public Result splash(Combatant actor, Move m, Combatant target, int index) {
        return(splash(actor, m, target, index, 1.0));
    }

    /**
     * @param reach how much of this target the attack got, 0..1. The model does not know
     *              where anybody is standing, so the LAST target a sweep reaches takes a
     *              share rather than a full swing - see Formulas.SWEEP_REACH and
     *              Optimizer.step. It scales the damage and the openings alike, because
     *              both are what a blow that half landed would deliver half of.
     */
    public Result splash(Combatant actor, Move m, Combatant target, int index, double reach) {
        if(!m.splashes() || (target == null) || !target.alive() || !(reach > 0))
            return(new Result("not a target of this move"));
        double[] hit = strike(actor, m, target, m.targetScale(index) * reach);
        double[] opened = land(actor, m, target, reach);
        parried(actor, m, target);
        return(new Result(hit[0], hit[1], hit[2], opened, 0, actor.ip, target.ip));
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
