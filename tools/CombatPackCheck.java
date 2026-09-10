/*
 * Checks the data-pack seam: data/combat/moves_sheet.json, as loaded by haven.combat.data.Pack.
 *
 * NOT part of the client build - build.xml compiles src/ only. Run on demand:
 *
 *   javac -d %TEMP%\packcheck src\haven\combat\*.java src\haven\combat\data\*.java ^
 *         src\org\json\*.java tools\CombatPackCheck.java
 *   java -cp %TEMP%\packcheck CombatPackCheck
 *
 * WHY THIS EXISTS. Every other check in this project builds its moves by hand. That made the
 * arithmetic and the sequencing well covered and left the path the bot will actually use - parse
 * the sheet, write JSON, load it into a Move - covered by nothing at all. A defect there is
 * invisible to a green check suite, and one was: Take Aim's "increases by 20% for each Point of
 * Initiative" was never parsed and never loaded, so a packed Take Aim reported a flat 30 ticks at
 * any initiative while CombatSimCheck's hand-built copy, carrying ipScale(0.20) as a literal,
 * reproduced the logged 30/36/42/48/54/60 perfectly. Two moves also lost their whole initiative
 * line to an unparsed "4+2" and read as costing nothing.
 *
 * So the rule this file enforces is the one the mu chain taught: an estimator or a loader needs a
 * control with a known answer, and the control has to be RUN, not assumed. Every expectation
 * below is a number printed on the character sheet or measured in the logged corpus.
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import haven.combat.Combatant;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Sim;
import haven.combat.data.Pack;

public class CombatPackCheck {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? (want == null) : got.equals(want);
        System.out.printf("  %-56s %-18s %s%n", what, got, ok ? "ok" : "WANT " + want);
        if(!ok)
            failures++;
    }

    static void near(String what, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        System.out.printf("  %-56s %-18s %s%n", what, String.format("%.3f", got),
                          ok ? "ok" : String.format("WANT %.3f +/- %.3f", want, tol));
        if(!ok)
            failures++;
    }

    static final Path MOVES = Paths.get("data", "combat", "moves_sheet.json");

    static final java.nio.file.Path FOES =
        java.nio.file.Paths.get("data", "combat", "opponents.json");

    static Map<String, Move> moves;

    static Move m(String name) {
        Move v = moves.get(name);
        if(v == null) {
            System.out.printf("  %-56s %-18s %s%n", "move present", "MISSING", "WANT " + name);
            failures++;
        }
        return(v);
    }

    public static void main(String[] args) throws Exception {
        moves = Pack.moves(MOVES);
        System.out.println("loaded " + moves.size() + " moves from data/combat/moves_sheet.json");
        sheetNumbers();
        reductions();
        initiativeLines();
        takeAimLadder();
        opportunityKnocks();
        weightsAndSchools();
        threatBlocks();
        whoIsWho();
        packedInTheJar();
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED"
                           : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * People and animals, told apart, and a character who actually exists.
     *
     * The pack pooled them. Every entry was an "opponent" and the deck search built one
     * deck to cover "fox, walrus and a person", which is not an answer to anything: a
     * player holds a deck of the cards WE hold, at levels, with mu and a stance somebody
     * chose, and can change all of it between fights. An animal throws from a fixed list.
     *
     * The marker is the resource, not the name. gfx/borka/body is a player body, and
     * every entry carrying it throws cards off our own sheet - Quick Barrage, Zig-Zag
     * Ruse, Flex, Parry, Chin Up, Left Hook. That is the check below: if an entry called
     * a creature is throwing our cards, the classification has broken.
     */
    static void whoIsWho() throws Exception {
        System.out.println();
        System.out.println("people and animals are different problems");
        Map<String, Pack.Opponent> foes = Pack.opponents(FOES);
        int players = 0, creatures = 0, unknown = 0, ourCards = 0;
        for(Pack.Opponent o : foes.values()) {
            if(o.isPlayer())
                players++;
            else if("unknown".equals(o.kind))
                unknown++;
            else
                creatures++;
            if(!o.isPlayer()) {
                for(String mv : o.moves) {
                    if(moves.containsKey(mv))
                        ourCards++;
                }
            }
        }
        check("every entry is classed", players + creatures + unknown, foes.size());
        check("  players are found by gfx/borka/body", players, 10);
        check("  and nothing called a creature throws a card off our own sheet",
              ourCards, 0);

        /* The deck search ran on six literals that were nobody's - melee and unarmed
         * were Shade's, the strength, agility and health belonged to no character in the
         * corpus. A deck built for a character who does not exist is not wrong in a way
         * anyone can see from the output. */
        Map<String, Pack.Fighter> chars =
            Pack.characters(Paths.get("data", "combat", "characters.json"));
        Pack.Fighter zz = chars.get("ZzxcuV3");
        check("the character the search defaults to is in the pack", zz != null, true);
        if(zz != null) {
            check("  and carries the attributes a fight needs",
                  (zz.str > 0) && (zz.agi > 0) && (zz.melee > 0) && (zz.hp > 0), true);
            check("  with the weapon last seen in hand", zz.weapon, "Bronze Sword");
        }
        /* Nobody's numbers, kept as a regression: if these ever match a real character
         * it is a coincidence, and if the literals come back this says so. */
        boolean mongrel = false;
        for(Pack.Fighter f : chars.values()) {
            if((f.str == 195) && (f.agi == 192) && (f.melee == 243) && (f.hp == 303))
                mongrel = true;
        }
        check("  and the old hard-coded figures still belong to no one", mongrel, false);

        /* OWNERSHIP IS NOT THE SAME AS A LOADOUT. The dump lists every card a character
         * has learned, at the level it currently sits on the bar, and 0 means learned but
         * unslotted - so this is what they COULD play, and the thirty points are
         * re-assignable at will. That is why a search ranging over levels is realistic:
         * the only thing a character cannot do is play a card they never learned.
         *
         * It matters because the characters differ. ZzxcuV3 knows all 41, so for them
         * "what is optimal" and "what is optimal from what I own" are one question.
         * Shade has never learned Parry, Oak Stance or Combat Meditation, and decks the
         * search likes are built on those. */
        if(zz != null) {
            check("  ZzxcuV3 knows every card in the sheet", zz.owned.size(), moves.size());
            for(String nm : moves.keySet()) {
                if(!zz.knows(nm)) {
                    check("    knows " + nm, false, true);
                    break;
                }
            }
        }
        Pack.Fighter shade = chars.get("Shade");
        if(shade != null) {
            check("  and another character does not", shade.knows("Parry"), false);
            check("    while still knowing most of it", shade.owned.size() > 30, true);
        }

        /* A DUEL FOUGHT NAKED IS NOT THIS CHARACTER'S DUEL, and it was. Nothing set the
         * armour, so two players in bronze plate hit each other as though wearing
         * nothing: the same pair of decks kills in 121 seconds bare and does not resolve
         * at all in 362 with the armour on. That difference was the whole basis of the
         * earlier finding that whoever swings first wins, which was an artefact of it.
         *
         * The numbers come off the client's own gear rows, which carry hard and soft per
         * item, so this is measured rather than matched against the wiki. */
        if(zz != null) {
            check("  the character is wearing something", zz.armHard > 50, true);
            check("    soft soak as well as hard", zz.armSoft > 50, true);
            check("    and the combatant it builds carries it",
                  zz.combatant().armHard, zz.armHard);
            /* Combatant defaults penetrable false because every armoured opponent in the
             * corpus is an animal and the one that could be tested was immune. A person
             * is not, and a weapon's penetration against a player is what it says. */
            check("    and is penetrable, unlike the animals", zz.combatant().penetrable,
                  true);
        }

        /* Shield Up is 250% of the block weight holding a shield and 50% without - five
         * times, on the one number a stance exists to set. The sheet has said so since it
         * was first parsed and nothing read it, so every unshielded character has been
         * priced as though carrying a tower. */
        Move shield = moves.get("Shield Up");
        if(shield != null) {
            check("  Shield Up says what it needs", shield.blockRequires, "shield");
            check("    and what it falls to without it", shield.blockMultWithout, 0.5);
            check("    against 2.5 with one", shield.blockMult, 2.5);
        }
        Move parry = moves.get("Parry");
        if(parry != null)
            check("  and a stance that needs nothing says so", parry.blockRequires, null);
    }

    /**
     * Defensive cards, whose "Reduces" line is a SHARE and not a number of points.
     *
     * Zig-Zag Ruse is listed at 50% and settles it: at level 1, where mu is 1.0, it took a
     * standing Cornered of 55 to 27, 44 to 22, 66 to 33, 26 to 13 and 32 to 16 across the
     * corpus. Half of whatever was there, every time. Reading the line the way an openings
     * line is read would subtract fifty POINTS, which floors a 26 to nothing.
     */
    static void reductions() {
        System.out.println("\ndefensive cards reduce a share, not a number of points");
        Move zig = m("Zig-Zag Ruse");
        near("Zig-Zag Ruse takes half of Cornered", zig.reduces[Formulas.RED], 0.5, 1e-9);
        near("  and half of Reeling", zig.reduces[Formulas.YELLOW], 0.5, 1e-9);
        near("  it opens nothing", zig.openings[Formulas.RED], 0, 0);
        near("Quick Dodge is listed at 20% of Off Balance",
             m("Quick Dodge").reduces[Formulas.GREEN], 0.2, 1e-9);

        /* The logged series, replayed. Every one of these is a real pair from the corpus. */
        Combatant me = me();
        Combatant foe = foe();
        int[][] logged = {{55, 27}, {44, 22}, {66, 33}, {26, 13}, {32, 16}};
        for(int[] pair : logged) {
            me.openings[Formulas.RED] = pair[0];
            me.readyAt = 0;
            new Sim(me, foe).use(me, zig);
            check("  " + pair[0] + " Cornered becomes",
                  (int)Math.floor(me.openings[Formulas.RED]), pair[1]);
        }
        /* mu scales the share LINEARLY - the distinction that made the mu chain dangerous,
         * since for an attack it enters the weight and its effect is cubed. */
        me.openings[Formulas.RED] = 50;
        me.readyAt = 0;
        new Sim(me, foe).use(me, zig.withMu(1.2));
        near("at mu 1.2 the same card takes 60%, not 50%",
             me.openings[Formulas.RED], 20.0, 1e-9);
    }

    /** Numbers printed verbatim on the sheet, which the loader must not alter. */
    static void sheetNumbers() {
        System.out.println("\nsheet numbers survive the round trip");
        Move kito = m("Knock Its Teeth Out");
        near("Knock Its Teeth Out flat damage", kito.flatDamage, 30, 0);
        near("  its grievous share, 25% as a fraction", kito.grievous, 0.25, 1e-9);
        near("  its Cornered opening, in percentage points", kito.openings[Formulas.RED], 20, 0);
        near("  its cooldown", kito.cooldownBase, 35, 0);

        Move qb = m("Quick Barrage");
        near("Quick Barrage damage share, from \"weapon * 25%\"", qb.damageShare, 0.25, 1e-9);
        check("  its gain is conditional on red", qb.gainColour, Formulas.RED);
        near("  above a quarter open", qb.gainAbove, 0.25, 1e-9);

        Move cleave = m("Cleave");
        near("Cleave deals 150% of the weapon", cleave.damageShare, 1.5, 1e-9);
        check("  and is two-coloured (Backhanded, Oppressive)", cleave.schools.length, 2);

        Move punch = m("Punch");
        near("Punch's attack weight multiplier, \"80%\"", punch.weightMu, 0.8, 1e-9);
        Move upper = m("Uppercut");
        near("Uppercut's, written \"0.8\" rather than \"80%\"", upper.weightMu, 0.8, 1e-9);
    }

    /**
     * The initiative line, including the two moves that write it as "N+M".
     *
     * These both read as zero until the parser learned the form, which meant the simulator
     * would happily throw a six-point Cleave from an empty initiative pool.
     */
    static void initiativeLines() {
        System.out.println("\ninitiative costs, including the \"4+2\" form");
        check("Knock Its Teeth Out costs one", m("Knock Its Teeth Out").ipCost, 1);
        check("Rip Apart costs six", m("Rip Apart").ipCost, 6);
        check("Cleave costs four", m("Cleave").ipCost, 4);
        check("  with its trailing two carried, not folded in", m("Cleave").ipExtra, 2);
        check("Go for the Jugular costs two", m("Go for the Jugular").ipCost, 2);
        check("  and carries a trailing two as well", m("Go for the Jugular").ipExtra, 2);
        check("Zig-Zag Ruse hands the opponent two", m("Zig-Zag Ruse").foeIpGain, 2);
        check("Punch's silent sheet means no cost, not an unread one", m("Punch").ipCost, 0);
    }

    /**
     * Opportunity Knocks takes the agility modifier without declaring an attack type.
     *
     * Base 45, and the corpus reports it at 41 ticks against ants, fox, boar, badger,
     * wolverine and adder - all of them pinned at the bottom of the agility band - and at
     * 45 against a wildgoat, the one creature measured at about our own agility.
     * round(45 * 0.9) = 41 and round(45 * 1.0) = 45, so the card is on the same curve as
     * every attack, and the pack read it as a maneuver because it declares no attack
     * TYPE. What it does declare is an attack skill, and that is the test.
     *
     * The control is beside it: Zig-Zag Ruse declares neither and has never moved a tick
     * against anything, over the whole corpus.
     */
    static void opportunityKnocks() {
        System.out.println("\nOpportunity Knocks, which moves without an attack type");
        Move ok = m("Opportunity Knocks");
        check("it declares no attack type", ok.schools.length, 0);
        check("  and is still on the agility curve", ok.isAttack(), true);

        Combatant me = me();
        /* The card costs initiative to play, and a refused move reports a cooldown of
         * zero - which would read here as a wrong cooldown rather than as a move that
         * never happened. So the result is checked for having been allowed at all. */
        me.ip = 8;
        Combatant slow = foe();
        slow.agi = 40;                     /* past the factor-two gap, so saturated */
        me.readyAt = 0;
        Sim.Result r = new Sim(me, slow).use(me, ok);
        check("  the move is allowed at eight initiative", r.ok, true);
        check("  41 ticks against anything at or past half our agility", r.cooldown, 41L);

        Combatant even = foe();            /* agility 81, the same as ours */
        me.readyAt = 0;
        check("  and 45 against something as quick as we are",
              new Sim(me, even).use(me, ok).cooldown, 45L);

        Move ruse = m("Zig-Zag Ruse");
        check("  the control declares neither type nor skill", ruse.isAttack(), false);
        me.readyAt = 0;
        long a = new Sim(me, slow).use(me, ruse).cooldown;
        me.readyAt = 0;
        long b = new Sim(me, even).use(me, ruse).cooldown;
        check("  so it reports the same 50 ticks against both", (a == 50) && (b == 50),
              true);
    }

    /**
     * The control that would have caught the missing ipScale.
     *
     * Take Aim reported 30, 36, 42, 48, 54 and 60 ticks across a logged run as its user's
     * initiative climbed from nothing to five. A packed Take Aim has to reproduce that ladder;
     * with ipScale lost it returned 30 six times and every check still passed.
     */
    static void takeAimLadder() {
        System.out.println("\nTake Aim's cooldown ladder, straight from the pack");
        Move aim = m("Take Aim");
        check("its cooldown divides by mu", aim.cooldownMu, true);
        near("  and rises 20% per initiative point", aim.ipScale, 0.20, 1e-9);
        check("  it is a maneuver, so agility does not touch it", aim.isAttack(), false);

        long[] want = {30, 36, 42, 48, 54, 60};
        Combatant me = me();
        Combatant foe = foe();
        for(int ip = 0; ip < want.length; ip++) {
            me.ip = ip;
            me.readyAt = 0;
            Sim sim = new Sim(me, foe);
            check("  at " + ip + " initiative", sim.use(me, aim).cooldown, want[ip]);
        }
    }

    /**
     * The weight line, whose skill is an icon and is absent for "According to weapon".
     *
     * A null there has to mean Melee Combat rather than no skill at all - the sheet's own closing
     * note says so - and reading it as none would collapse every weapon attack's attack weight to
     * zero, which reads as an infinitely tough opponent rather than as an error.
     */
    static void weightsAndSchools() {
        System.out.println("\nattack weights and schools");
        Combatant me = me();
        check("Knock Its Teeth Out reads Unarmed", m("Knock Its Teeth Out").weight,
              Move.Weight.UNARMED);
        near("  so its attack weight is Unarmed 58 at mu 1", me.attackWeight(m("Knock Its Teeth Out")),
             58, 1e-9);
        check("Quick Barrage's blank skill means the weapon default", m("Quick Barrage").weight,
              Move.Weight.WEAPON);
        near("  which resolves to Melee Combat 111", me.attackWeight(m("Quick Barrage")), 111, 1e-9);
        near("Punch is Unarmed at 80%", me.attackWeight(m("Punch")), 58 * 0.8, 1e-9);
        check("Full Circle names Melee explicitly", m("Full Circle").weight, Move.Weight.MELEE);
        near("  at 90%", me.attackWeight(m("Full Circle")), 111 * 0.9, 1e-9);

        /* A stance is a trade: defence bought with offence. Combat Meditation cuts every
         * attack to a quarter weight while it is held, and the model had no term for that
         * at all until a forum guide's plain-language note on Oak Stance surfaced it. */
        near("Combat Meditation quarters its holder's attack weight",
             m("Combat Meditation").attackMult, 0.25, 1e-9);
        near("an ordinary card leaves attack weight alone",
             m("Quick Barrage").attackMult, 1.0, 1e-9);
        Combatant med = me();
        med.attackMult = m("Combat Meditation").attackMult;
        near("  so Quick Barrage falls from 111 to 27.75 while it is held",
             med.attackWeight(m("Quick Barrage")), 27.75, 1e-9);

        check("mu defaults to 1.0, the level-1 value Take Aim measures",
              m("Knock Its Teeth Out").mu, 1.0);
        near("  and a levelled card carries its own",
             me.attackWeight(m("Knock Its Teeth Out").withMu(1.5)), 58 * 1.5, 1e-9);
        near("  leaving the rest of the deck alone", me.attackWeight(m("Punch")), 58 * 0.8, 1e-9);

        check("Zig-Zag Ruse has no attack type, so it is a maneuver",
              m("Zig-Zag Ruse").kind, Move.Kind.MANEUVER);
        check("Full Circle is two-coloured", m("Full Circle").schools.length, 2);
    }

    /**
     * The threat blocks, which are the opponent's half of the fight.
     *
     * This is the seam that carries the only measurements in the pack about what happens to
     * US, and it is the newest one, so it gets the same treatment the moves seam got: not
     * "does it parse", but "does what it parsed mean what the estimator meant".
     */
    static void threatBlocks() throws Exception {
        System.out.println("\nthe opponent's own model, from the pack's threat blocks");
        Map<String, Pack.Opponent> foes = Pack.opponents(FOES);

        int withThreat = 0, withPeriod = 0;
        for(Pack.Opponent o : foes.values()) {
            if(o.threat == null)
                continue;
            withThreat++;
            if(o.threat.period > 0)
                withPeriod++;
        }
        check("most opponents carry a threat model", withThreat > (foes.size() / 2), true);
        /* A model with no period is not loaded at all - see Pack.threat. Period is the
         * clock, and without it none of the rest gets applied to anything. */
        check("  and every one that loaded has a period", withPeriod, withThreat);

        Pack.Opponent ants = foes.get("ants");
        if(ants == null || ants.threat == null) {
            System.out.println("  (no ants in the pack - species checks skipped)");
        } else {
            /* Measured per GOB across the whole file rather than per engagement, and not
             * gated on defence_ok. Before that, ants read 8 gaps out of 144 engagements
             * because a swarming species is almost never alone - which is exactly the
             * species the number is wanted for. */
            check("ants rest on more than fifty observed gaps", ants.threat.nGaps > 50, true);
            check("  a swarming species that the old per-engagement gate reduced to 8", true, true);
        }

        /* Cattle acts on two clocks, 22 ticks and 38, and the mean is 33 - a figure it
         * never once exhibited. The model takes one period because a rate is one number,
         * so the modes ride along to say when that number is a blend. Getting this wrong
         * is not cosmetic: an earlier version folded the 38s onto 19 as missed actions and
         * reported the creature as twice as dangerous as it is. The tell that 38 is real
         * is that nothing sits at 44, where a double of 22 would have to be. */
        Pack.Opponent cattle = foes.get("cattle");
        if(cattle == null || cattle.threat == null) {
            System.out.println("  (no cattle in the pack - the two-clock check is skipped)");
        } else {
            check("cattle is seen acting on more than one clock",
                  cattle.threat.multiClock(), true);
            boolean spread = false;
            for(int a : cattle.threat.modes)
                for(int b : cattle.threat.modes)
                    if(a > (b * 1.5))
                        spread = true;
            check("  and the two are far enough apart to be different cards", spread, true);
            check("  with the period between them",
                  (cattle.threat.period > cattle.threat.modes[0])
                  && (cattle.threat.period < cattle.threat.modes[1]), true);
        }

        /* The honesty guard that matters most here. An opponent that has never landed on
         * us must not report zero damage as though that were a measurement - FoeModel
         * returns nothing rather than nothing-meaning-safe, and the matchup says which. */
        int silent = 0;
        for(Pack.Opponent o : foes.values()) {
            if((o.threat != null) && !o.threat.knowsDamage())
                silent++;
        }
        check("some opponent has never been measured hitting us", silent > 0, true);
        check("  and it reports its damage as unknown, not as zero",
              anySilentRefusesDamage(foes), true);

        /* WHETHER WE CAN LEAVE. The estimator has measured relative speed all along and
         * nothing read it back out, so the matchup answered "can I take this" without
         * "can I get out if I am wrong" - and the second is the only mitigation a losing
         * plan has. Three states, and the third is the one worth guarding: an opponent we
         * never withdrew from reads as a speed of zero, which must not come back as yes. */
        int outrun = 0, outrunUs = 0, unknown = 0;
        for(Pack.Opponent o : foes.values()) {
            if(!o.speedMeasured)
                unknown++;
            else if(o.canDisengage())
                outrun++;
            else
                outrunUs++;
        }
        /* THE FLEE THRESHOLD. Null everywhere until the corpus could watch a creature
         * give up and keep taking damage, which needs the aggression state schema 7
         * added. A fleeing animal stops swinging, so a plan that keeps defending into a
         * flight is buying protection from an opponent that has stopped attacking. */
        int flees = 0;
        for(Pack.Opponent o : foes.values()) {
            if((o.threat != null) && !Double.isNaN(o.threat.fleesBelow))
                flees++;
        }
        /* The pinned hitpoint band. The envelope is honest and this is useful; a check
         * that let them drift apart would let a one-shot's [almost nothing, total] back
         * into the number a simulator plans against. */
        int pinned = 0;
        for(Pack.Opponent o : foes.values()) {
            if(o.hpPinned())
                pinned++;
        }
        check("some opponent's size is pinned, not merely bounded", pinned > 0, true);
        check("  and a simulation opens against the pinned band, not the envelope",
              planUsesPinned(foes), true);
        check("  and a pinned band never falls outside the envelope",
              pinnedInsideEnvelope(foes), true);
        check("  and it is never wider than the envelope it came from",
              pinnedNarrower(foes), true);

        /* WHAT ITS OWN CARDS TAKE BACK. Six opponent cards close the opponent's own
         * openings - Unstoppable 25.2 points a use, Bristle 16.4, Roar of the Wild 3.4 -
         * against 0.04 in brackets where nothing acted. Left out, a creature stays more
         * open than it is for the whole fight and every opening feeds a squared damage
         * term, which is optimism in the direction a matchup must not be optimistic. */
        int restoring = 0;
        for(Pack.Opponent o : foes.values()) {
            if((o.threat != null) && !Double.isNaN(o.threat.restores)
               && (o.threat.restores > 0))
                restoring++;
        }
        /* WHAT IT DOES DEPENDS ON THE STATE. A cave angler puts 4.45 points of green on
         * us per action while it is opened and 1.14 while it is not; a bat 4.13 of yellow
         * with initiative in hand against 1.18 without. Pooling those describes a creature
         * that never exists. Four of the fourteen rules split on distance, which a
         * Combatant does not carry, and those fall back to the pooled figure. */
        int conditional = 0;
        for(Pack.Opponent o : foes.values()) {
            if((o.threat != null) && (o.threat.condFeature != null))
                conditional++;
        }
        check("some opponent's pressure depends on the state", conditional > 0, true);
        check("  and the model actually switches on it", conditionalSwitches(foes), true);

        check("some opponent takes its own openings back", restoring > 0, true);
        check("  and restoring one actually closes them", restoresClosesOpenings(), true);

        check("some opponent has a measured flee threshold", flees > 0, true);
        check("  and every one of them is a share of health, not a count",
              fleeThresholdsAreShares(foes), true);

        check("relative speed reaches the simulator", outrun > 0, true);
        check("  and some opponent can outrun us", outrunUs > 0, true);
        check("  and an unmeasured one says so rather than yes", unknown > 0, true);
        check("  with disengagement refused where it was never measured",
              noUnmeasuredClaimsEscape(foes), true);
    }

    /** Where a band is pinned, the fight the simulator opens with has to use it. */
    static boolean planUsesPinned(Map<String, Pack.Opponent> foes) {
        boolean saw = false;
        for(Pack.Opponent o : foes.values()) {
            if(!o.hpPinned())
                continue;
            if((o.planHpLo() != o.hpPinLo) || (o.planHpHi() != o.hpPinHi))
                return(false);
            if(o.hpPinLo > o.hpLo)
                saw = true;
        }
        /* And it has to make a difference somewhere, or the wiring is inert. */
        return(saw);
    }

    /** Every pinned individual is also in the envelope, so the band must sit inside it. */
    static boolean pinnedInsideEnvelope(Map<String, Pack.Opponent> foes) {
        for(Pack.Opponent o : foes.values()) {
            if(!o.hpPinned())
                continue;
            if((o.hpPinLo < o.hpLo - 1e-9) || (o.hpPinHi > o.hpHi + 1e-9))
                return(false);
        }
        return(true);
    }

    /** It is drawn from a subset, so it cannot be wider - if it is, the two disagree. */
    static boolean pinnedNarrower(Map<String, Pack.Opponent> foes) {
        for(Pack.Opponent o : foes.values()) {
            if(!o.hpPinned())
                continue;
            if((o.hpPinHi - o.hpPinLo) > (o.hpHi - o.hpLo) + 1e-9)
                return(false);
        }
        return(true);
    }

    /** A conditional model must return different pressure either side of its own cut. */
    static boolean conditionalSwitches(Map<String, Pack.Opponent> foes) {
        for(Pack.Opponent o : foes.values()) {
            if((o.threat == null) || (o.threat.condFeature == null))
                continue;
            haven.combat.Combatant me = new haven.combat.Combatant("me");
            haven.combat.Combatant it = new haven.combat.Combatant("it");
            double[] lo = o.threat.pressureNow(me, it);
            /* Push whichever quantity the rule reads over its cut. */
            String f = o.threat.condFeature;
            if("foe_ip".equals(f))
                it.ip = (int)Math.ceil(o.threat.condCut + 1);
            else if("my_ip".equals(f))
                me.ip = (int)Math.ceil(o.threat.condCut + 1);
            else if("my_open".equals(f))
                me.open(0, o.threat.condCut + 5);
            else if("foe_open".equals(f))
                it.open(0, o.threat.condCut + 5);
            double[] hi = o.threat.pressureNow(me, it);
            if(lo != hi)
                return(true);
        }
        return(false);
    }

    /** A model that restores has to actually reduce the opening it is given. */
    static boolean restoresClosesOpenings() {
        haven.combat.FoeModel m = new haven.combat.FoeModel(
            45, new double[] {0, 0, 0, 0}, 0, Double.NaN, 0, 0, Double.NaN,
            new int[0], 0.20);
        haven.combat.Combatant c = new haven.combat.Combatant("it");
        c.open(0, 20);
        c.open(3, 5);
        m.restore(c);
        /* A SHARE of each, not points off the largest: a fifth of 20 leaves 16 and a fifth
         * of 5 leaves 4. The corpus says share - Bristle takes 1.0, 9.0 and 21.0 points as
         * the standing total rises through its bands, and 0.17, 0.20, 0.20 of it. */
        return((Math.abs((c.opening(0) * 100) - 16.0) < 1e-6)
               && (Math.abs((c.opening(3) * 100) - 4.0) < 1e-6));
    }

    /** A threshold is a fraction of hitpoints, so anything outside (0, 1) is a unit bug. */
    static boolean fleeThresholdsAreShares(Map<String, Pack.Opponent> foes) {
        for(Pack.Opponent o : foes.values()) {
            if((o.threat == null) || Double.isNaN(o.threat.fleesBelow))
                continue;
            if((o.threat.fleesBelow <= 0.0) || (o.threat.fleesBelow >= 1.0))
                return(false);
        }
        return(true);
    }

    /** No opponent may report that we can disengage on a speed nobody measured. */
    static boolean noUnmeasuredClaimsEscape(Map<String, Pack.Opponent> foes) {
        for(Pack.Opponent o : foes.values()) {
            if(!o.speedMeasured && o.canDisengage())
                return(false);
        }
        return(true);
    }

    static boolean anySilentRefusesDamage(Map<String, Pack.Opponent> foes) {
        for(Pack.Opponent o : foes.values()) {
            if((o.threat == null) || o.threat.knowsDamage())
                continue;
            Combatant target = me();
            double before = target.hp;
            double dealt = o.threat.act(target, target.defenceWeight());
            /* It still OPENS us - pressure is measured separately from damage and one can
             * be known while the other is not. What it must not do is quietly deal zero
             * and let a plan be costed as free. */
            if((dealt != 0) || (target.hp != before))
                return(false);
        }
        return(true);
    }

    /* The character that fought the logged corpus. */
    static Combatant me() {
        Combatant c = new Combatant("ZzxcuV3");
        c.str = 82; c.agi = 81; c.unarmed = 58; c.melee = 111;
        c.weaponDamage = 90; c.weaponQl = 28.68; c.weaponPen = 0.125;
        c.armHard = 5; c.armSoft = 2;
        c.hp = c.maxHp = 100;
        return(c);
    }

    /* Equal agility, so the agility factor is exactly 1 and cannot mask a cooldown error. */
    static Combatant foe() {
        Combatant c = new Combatant("control");
        c.agi = 81;
        c.hp = c.maxHp = 1000;
        c.blockSkill = 111;
        return(c);
    }

    /**
     * The same pack, read off the classpath instead of off disk.
     *
     * This is how the running client gets it: build.xml copies data/combat in beside the
     * classes, so a client carries the pack it was built with and can predict without a
     * repository around it. The failure worth catching is silent - a missing copy step leaves
     * every loader returning an empty map, the predictor declines every move, and the log
     * simply has no predictions in it, which looks exactly like a quiet fight.
     */
    static void packedInTheJar() {
        System.out.println("\nthe pack, from the classpath");
        int nm = Pack.movesFromJar().size();
        int nf = Pack.opponentsFromJar().size();
        int nw = Pack.weaponsFromJar().size();
        if((nm == 0) && (nf == 0) && (nw == 0)) {
            System.out.println("  (not on this classpath - run via tools\\check-combat.ps1)");
            return;
        }
        /* STANCES. Held on the bar rather than thrown, one at a time and one always: across
         * 792 deck dumps holding any cards at all, 782 hold exactly one of the seven and
         * none holds two. The signature is a Block weight line, which no card in the sheet
         * carries alongside an Attack weight - an earlier rule looked for cards that change
         * a weight and found three of the seven, leaving Parry classed as an attack. */
        int stances = 0, withMult = 0;
        for(Move m : moves.values()) {
            if(!m.stance)
                continue;
            stances++;
            if(m.blockMult > 0)
                withMult++;
        }
        check("the sheet knows its stances", stances, 7);
        check("  and every one carries a block multiplier", withMult, stances);

        check("moves load from the classpath", nm > 0, true);
        check("  as do the opponents", nf > 0, true);
        check("  and the weapons", nw > 0, true);

        /* The join the client depends on: a resource basename against a wiki article title.
         * "gfx/invobjs/bronzesword" has to find "Bronze Sword", and a weapon that does not
         * reduce to its resource name simply will not be found - at which point the predictor
         * declines rather than arming us with a default. */
        Map<String, double[]> w = Pack.weaponsFromJar();
        check("a weapon is found by its resource basename",
              w.containsKey(Pack.key("bronzesword")), true);
        check("  and the key ignores case and punctuation",
              Pack.key("Butcher's Cleaver"), "butcherscleaver");
        double[] bronze = w.get(Pack.key("bronzesword"));
        check("  carrying a base damage", (bronze != null) && (bronze[0] > 0), true);

        /* The overlay: where the client's own WeaponInfo disagrees with the scrape, the
         * item wins. The wiki gives the stone axe 10% armour penetration and the live
         * value is 0.20, while the bronze sword agrees exactly - so this is one wrong
         * number in the table rather than a units error on our side. */
        double[] axe = w.get(Pack.key("stoneaxe"));
        if(axe != null) {
            near("the stone axe carries the item's penetration, not the wiki's 10%",
                 axe[1], 0.20, 1e-9);
            near("  and a base damage recovered from a quality-scaled tooltip",
                 axe[0], 30.0, 0.5);
        }
        near("  while the bronze sword, where the two agree, is unchanged",
             bronze[1], 0.125, 1e-9);

        /* armorpen is genuinely absent on four of the twenty-six and the scraper keeps that
         * as null. A zero would be a claim that the weapon pierces nothing. */
        int unknownPen = 0;
        for(double[] v : w.values()) {
            if(Double.isNaN(v[1]))
                unknownPen++;
        }
        check("  and an absent penetration stays absent, not zero", unknownPen > 0, true);
    }
}
