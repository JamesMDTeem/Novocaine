/*
 * Checks for the live advice - Prediction.adviseLive, the planner the fight view's recommendation
 * and the auto-fighter both use.
 *
 * NOT part of the client build. Run by tools/check-combat.ps1, against the real data pack staged on
 * the classpath the way the client reads it.
 *
 * The live advice promises to answer in ANY fight, and every way it does that is a place it could
 * quietly answer wrong: a creature the pack does not know, a player whose cards were never seen, a
 * crowd of all three, a bar holding cards the sheet has never heard of. So each of those gets a
 * case, and each case asserts what the answer must be - including that it SAYS when it leaned on a
 * stand-in, which is the property that keeps a guess from reading as a measurement.
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import haven.automated.combat.Prediction;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class LiveAdviceCheck {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? (want == null) : got.equals(want);
        System.out.printf("  %-62s %-18s %s%n", what, got, ok ? "ok" : "WANT " + want);
        if(!ok)
            failures++;
    }

    static final int BEAM = 60;
    static final long HORIZON = 2500;
    static final int[] FRESH = {0, 0, 0, 0};
    /* The cards me() holds, for sweeps that try each of them. */
    static final String[] BAR = {"paginae/atk/barrage", "paginae/atk/cleave", "paginae/atk/fullcircle",
                                 "paginae/atk/sting", "paginae/atk/takeaim", "paginae/atk/qdodge",
                                 "paginae/atk/sidestep", "paginae/atk/jump", "paginae/atk/zigzag"};

    /* A character like the corpus's: sword in hand, a deck with attacks and restorations. */
    static Prediction.Me me() {
        SortedMap<String, Integer> attrs = new TreeMap<String, Integer>();
        attrs.put("str", 94);
        attrs.put("agi", 111);
        attrs.put("unarmed", 81);
        attrs.put("melee", 125);
        return(Prediction.me(attrs, 30, 30, new String[] {"gfx/invobjs/bronzesword", null},
                             new double[] {40, 0}, levels()));
    }

    static Map<String, Integer> levels() {
        Map<String, Integer> levels = new LinkedHashMap<String, Integer>();
        levels.put("paginae/atk/barrage", 1);
        levels.put("paginae/atk/cleave", 1);
        levels.put("paginae/atk/fullcircle", 1);
        levels.put("paginae/atk/sting", 4);
        levels.put("paginae/atk/takeaim", 3);
        levels.put("paginae/atk/qdodge", 5);
        levels.put("paginae/atk/sidestep", 4);
        levels.put("paginae/atk/jump", 5);
        levels.put("paginae/atk/zigzag", 1);
        return(levels);
    }

    static Prediction.Seen seen(String res, int[] open, double taken) {
        return(new Prediction.Seen(res, open, 0, Double.NaN, taken, null));
    }

    static Prediction.Live advise(Prediction.Me me, Map<String, Integer> bar, int[] mine,
                                  double shp, double mhp, Prediction.Seen... foes) {
        return(Prediction.adviseLive(me, bar, mine, shp, mhp, Arrays.asList(foes), BEAM, HORIZON));
    }

    /* The first of these the pack can simulate, found by asking rather than assumed. */
    static final String[] CANDIDATES = {
        "gfx/kritter/fox/fox", "gfx/kritter/wolf/wolf", "gfx/kritter/boar/boar",
        "gfx/kritter/badger/badger", "gfx/kritter/beaver/beaver", "gfx/kritter/ants/ants",
        "gfx/kritter/bear/bear", "gfx/kritter/lynx/lynx", "gfx/kritter/adder/adder"};

    public static void main(String[] args) {
        Prediction.Me me = me();
        check("our side builds and is usable", (me != null) && me.usable(), true);

        System.out.println("\na creature the pack knows is planned as itself");
        String known = null;
        Prediction.Live k = null;
        for(String c : CANDIDATES) {
            Prediction.Live l = advise(me, null, FRESH, 300, 300, seen(c, new int[] {20, 0, 0, 10}, 0));
            if((l.moveRes != null) && (l.proxied == 0)) {
                known = c;
                k = l;
                break;
            }
        }
        check("one of the common creatures is known to the pack", known != null, true);
        if(known == null) {
            finish();
            return;
        }
        System.out.printf("      %s -> %s (%s, %d ticks, %.1f hp)%n", known, k.moveRes, k.why, k.ticks, k.hpLost);
        check("  it plans one opponent", k.planned, 1);
        check("  through no stand-in", k.proxied, 0);

        System.out.println("\nhow big a creature is planned, by where it stands");
        /* Only a DRAWN kill is a size (creature_sizes.json, corrected 2026-09-22). A batcave bat
         * dies to a drawn blow and is sized from those kills; a bat anywhere else dies to a blow
         * with no number, and is sized where those kills agree once the unseen blow is priced.
         * The version before planned it one blow past its floor - 17 - and James caught it: a bat
         * is stronger than an ant (wiki 50), and the unseen blow is the fight's biggest. */
        haven.combat.data.Pack.Opponent bat = haven.combat.data.Pack.opponentsFromJar().get("bat");
        check("the pack knows the bat", bat != null, true);
        if(bat != null) {
            double cave = bat.medianHpAbove(0, "batcave"), mine = bat.medianHpAbove(0, "gfx/tiles/mine");
            double far = bat.medianHpAbove(0, "gfx/tiles/nosuchtile");
            System.out.printf("      batcave %.0f, mine %.0f, unknown tile %.0f%n", cave, mine, far);
            check("  a batcave bat is sized from its drawn kills", bat.hpByTile.containsKey("batcave") && (cave > 60), true);
            check("  a mine bat from its priced undrawn kills", bat.undrawnByTile.containsKey("mine"), true);
            check("  and is more than an ant, and no more than a batcave bat", (mine > 50) && (mine <= cave), true);
            check("  an unknown tile takes the larger pool - the undrawn kills", Math.abs(far - mine) < 25, true);
            check("  a bat that took more than its size is one blow from dead",
                  bat.medianHpAbove(10000, "mine"), 10001.0);
        }

        System.out.println("\na creature's agility is its own, not a share of whoever fights it");
        /* estimate.agility_consensus -> Pack.Opponent.agilityAgainst (2026-09-22): every wolf reading
         * across characters of agility 58-411 agrees on 249-251. Staged against a character at 200
         * it is 250; against one at 111 the clamp makes it exactly double, 222. */
        haven.combat.data.Pack.Opponent wolf = haven.combat.data.Pack.opponentsFromJar().get("wolf");
        check("  the pack carries a wolf agility consensus", (wolf != null) && (wolf.agiConsN > 0), true);
        if((wolf != null) && (wolf.agiConsN > 0)) {
            double at200 = wolf.agilityAgainst(200), at111 = wolf.agilityAgainst(111);
            System.out.printf("      wolf %.1f-%.1f from %d readings; staged %.1f against 200, %.1f against 111%n",
                              wolf.agiConsLo, wolf.agiConsHi, wolf.agiConsN, at200, at111);
            check("  staged near 250 against a character at 200", Math.abs(at200 - 250) <= 10, true);
            check("  and at the clamp, double us, against one at 111", Math.abs(at111 - 222) <= 1, true);
        }

        System.out.println("\nthe lines players killed it with reach the live search");
        /* player_lines.json -> Pack.Opponent.playerLines -> Prediction.shownLines, which the live
         * search is offered beside its own so it never answers worse than one of them (2026-09-22). */
        Prediction.Staged bst = Prediction.stage(me, null, new int[] {0, 0, 0, 0}, 300, 300,
                                                 Arrays.asList(seen("gfx/kritter/bat/bat", new int[] {0, 0, 0, 0}, 0)));
        List<List<haven.combat.Move>> shown = (bst.refused == null)
            ? Prediction.shownLines("gfx/kritter/bat/bat", bst.deck) : null;
        check("  a bat has lines players killed it with, on this bar", (shown != null) && !shown.isEmpty(), true);
        if((shown != null) && !shown.isEmpty()) {
            List<haven.combat.Move> first = shown.get(0);
            System.out.printf("      %d line(s); most common: %s%n", shown.size(), first.toString());
            check("  and the most common one ends on its heaviest card, as players throw it",
                  first.get(first.size() - 1).res, "paginae/atk/fullcircle");
        }

        System.out.println("\na card the weapon in hand cannot throw is never advised");
        /* A boar spear is not edged (weapon_classes.json, from the wiki's Giant Needle page), so
         * Sideswipe - "Any edged weapon" - is refused by the server while Quick Barrage - "Any
         * melee weapon" - is not. Both cost no initiative, so nothing but the weapon decides.
         * The sword is the control: it throws both. */
        Map<String, Integer> edgeOrPoint = new LinkedHashMap<String, Integer>();
        edgeOrPoint.put("paginae/atk/sideswipe", 1);
        edgeOrPoint.put("paginae/atk/barrage", 1);
        Map<String, Integer> edgeOnly = new LinkedHashMap<String, Integer>();
        edgeOnly.put("paginae/atk/sideswipe", 1);
        SortedMap<String, Integer> attrs = new TreeMap<String, Integer>();
        attrs.put("str", 94);
        attrs.put("agi", 111);
        attrs.put("unarmed", 81);
        attrs.put("melee", 125);
        Prediction.Me spear = Prediction.me(attrs, 30, 30, new String[] {"gfx/invobjs/boarspear", null},
                                            new double[] {40, 0}, levels());
        check("  the spear is a weapon", spear.weapon() != null, true);
        Prediction.Live sp1 = advise(spear, edgeOrPoint, FRESH, 300, 300, seen(known, new int[] {20, 30, 0, 10}, 0));
        check("  holding a spear, a bar of Sideswipe and Quick Barrage advises Quick Barrage",
              sp1.moveRes, "paginae/atk/barrage");
        Prediction.Live sp2 = advise(spear, edgeOnly, FRESH, 300, 300, seen(known, new int[] {20, 30, 0, 10}, 0));
        check("    and a bar of Sideswipe alone advises nothing", sp2.moveRes, null);
        Prediction.Live swordCleave = advise(me, edgeOnly, FRESH, 300, 300, seen(known, new int[] {20, 30, 0, 10}, 0));
        check("  holding a sword, the same Sideswipe is advised", swordCleave.moveRes, "paginae/atk/sideswipe");

        System.out.println("\na creature the pack has never seen still gets an answer, and says how");
        Prediction.Live u = advise(me, null, FRESH, 300, 300,
                                   seen("gfx/kritter/nosuchbeast/nosuchbeast", new int[] {20, 0, 0, 10}, 0));
        check("it throws a card", u.moveRes != null, true);
        check("  planned through one stand-in", u.proxied, 1);
        check("  and the reason names it", (u.why != null) && u.why.contains("planned as"), true);
        System.out.printf("      unknown -> %s (%s)%n", u.moveRes, u.why);

        System.out.println("\na player whose cards were never seen is planned as someone like us");
        Prediction.Live p = advise(me, null, FRESH, 300, 300, seen("gfx/borka/body", FRESH, 0));
        check("it throws a card", p.moveRes != null, true);
        check("  counted as a player", p.players, 1);
        check("  and not as a stand-in creature", p.proxied, 0);

        System.out.println("\na crowd of all three is planned as a crowd");
        Prediction.Live crowd = advise(me, null, FRESH, 300, 300,
                                       seen(known, new int[] {20, 0, 0, 10}, 0),
                                       seen("gfx/kritter/nosuchbeast/nosuchbeast", FRESH, 0),
                                       seen("gfx/borka/body", FRESH, 0));
        check("every opponent is in the plan", crowd.planned, 3);
        check("  one of them through a stand-in", crowd.proxied, 1);
        check("  one of them a player", crowd.players, 1);
        check("  and it still throws a card", crowd.moveRes != null, true);

        System.out.println("\nthe deck is whatever is on the bar");
        Map<String, Integer> bar = new LinkedHashMap<String, Integer>();
        bar.put("paginae/atk/notacard", 1);
        bar.put("paginae/atk/shield", 1);
        bar.put("paginae/atk/barrage", 0);
        bar.put("paginae/atk/qdodge", 5);
        Prediction.Live b = advise(me, bar, FRESH, 300, 300, seen(known, new int[] {20, 0, 0, 10}, 0));
        check("the card thrown is one of the two it can plan with",
              "paginae/atk/barrage".equals(b.moveRes) || "paginae/atk/qdodge".equals(b.moveRes), true);
        Map<String, Integer> junk = new LinkedHashMap<String, Integer>();
        junk.put("paginae/atk/notacard", 1);
        junk.put("paginae/atk/shield", 1);
        Prediction.Live none = advise(me, junk, FRESH, 300, 300, seen(known, FRESH, 0));
        check("a bar of unknown cards and a stance plans nothing", none.moveRes, null);
        check("  and says why", (none.why != null) && none.why.contains("bar"), true);

        System.out.println("\nour hitpoints are in the question");
        int[] wide = {60, 60, 60, 60};
        int[] foeOpen = {30, 0, 0, 30};
        Prediction.Live full = advise(me, null, wide, 300, 300, seen(known, foeOpen, 0));
        Prediction.Live low = advise(me, null, wide, 40, 300, seen(known, foeOpen, 0));
        System.out.printf("      full health: %s, %.1f hp over %d ticks (budget %.0f)%n",
                          full.moveRes, full.hpLost, full.ticks, full.budget);
        System.out.printf("      low health:  %s, %.1f hp over %d ticks (budget %.0f)%n",
                          low.moveRes, low.hpLost, low.ticks, low.budget);
        check("the budget is what we have above the reserve", low.budget, 40 - (Prediction.RESERVE_PVE * 300));
        check("against creatures the reserve is the PvE one", low.reserve, Prediction.RESERVE_PVE);
        Prediction.Live pvpReserve = advise(me, null, wide, 300, 300, seen(known, foeOpen, 0),
                                            seen("gfx/borka/body", FRESH, 0));
        check("  and once a person is in the fight, the PvP one", pvpReserve.reserve, Prediction.RESERVE_PVP);
        check("short of health, the plan costs no more than the one with health to spare",
              !(low.hpLost > full.hpLost + Prediction.NEGLIGIBLE_HP + 1e-9), true);
        /* AN ORDINARY FIGHT IS NOT DEFENDED. Against the fox with our guard shut the safest plan
         * saves a fraction of a hitpoint, so even with no budget left it is the fastest kill. */
        Prediction.Live lowCheap = advise(me, null, FRESH, 40, 300, seen(known, foeOpen, 0));
        System.out.printf("      low health, guard shut: %s (%s), defending would save %.1f hp%n",
                          lowCheap.moveRes, lowCheap.why, lowCheap.trade);
        check("where defending saves under the negligible amount", lowCheap.trade <= Prediction.NEGLIGIBLE_HP, true);
        check("  low health still plays the fastest kill", lowCheap.why, "fastest kill");
        Prediction.Live blind = advise(me, null, wide, Double.NaN, Double.NaN, seen(known, foeOpen, 0));
        check("with our hitpoints unknown it is the fastest kill", blind.why, "fastest kill");

        /* THE CASE THE RESERVE EXISTS FOR, found rather than assumed: a creature that hurts
         * enough that the fastest kill at full health costs more than we can spare at 120 of
         * 300. The weak creatures above never get there - their fastest kill is also their
         * cheapest - so this sweeps the hard ones and requires at least one to change the pick,
         * and every pick it changes to must be a restoration that costs less. */
        System.out.println("\nagainst something that hurts, low health throws a restoration");
        List<String> restorations = Arrays.asList("paginae/atk/qdodge", "paginae/atk/sidestep",
                                                  "paginae/atk/jump", "paginae/atk/zigzag");
        /* SWEPT RATHER THAN NAMED, over creatures and over where we stand open. Four named
         * creatures at one state stopped holding the case once each animal's blow was read per
         * species on its card's own colours (2026-09-16): the cave angler that had carried it
         * attacks green, blue, yellow and red with different cards, and at {30, 0, 30, 0} its
         * cheapest line saved nothing. Whether some hard fight changes the pick is the question,
         * not which one. */
        String[] hard = {"gfx/kritter/caveangler/caveangler", "gfx/kritter/horse/horse",
                         "gfx/kritter/badger/badger", "gfx/kritter/wolverine/wolverine",
                         "gfx/kritter/vulturebee/vulturebee", "gfx/kritter/bear/bear",
                         "gfx/kritter/moose/moose", "gfx/kritter/boar/boar", "gfx/kritter/wolf/wolf",
                         "gfx/kritter/lynx/lynx"};
        int[][] states = {{30, 0, 30, 0}, {0, 30, 0, 30}, {30, 30, 30, 30}, {50, 50, 0, 0}};
        int changed = 0, restored = 0, cheaper = 0;
        for(int[] standing : states)
        for(String h : hard) {
            Prediction.Live hf = advise(me, null, standing, 300, 300, seen(h, new int[] {20, 0, 0, 20}, 0));
            Prediction.Live hl = advise(me, null, standing, 120, 300, seen(h, new int[] {20, 0, 0, 20}, 0));
            if((hf.proxied > 0) || (hf.moveRes == null) || (hl.moveRes == null))
                continue;
            System.out.printf("      %-36s full: %-22s %6.1f hp%s | at 120: %-22s %6.1f hp%s (%s, trade %.1f)%n", h,
                              hf.moveRes, hf.hpLost, hf.killed ? "" : " no kill", hl.moveRes, hl.hpLost,
                              hl.killed ? "" : " no kill", hl.why, hl.trade);
            if(hf.moveRes.equals(hl.moveRes))
                continue;
            /* A line that loses all 120 is a death whatever is thrown: every plan dies, the pick
             * among them is not a reserve decision, and it is not this case. */
            if(hl.hpLost >= 120 - 1e-9) {
                System.out.printf("        standing %s: no line survives at 120, not judged%n",
                                  java.util.Arrays.toString(standing));
                continue;
            }
            System.out.printf("        standing %s: %s -> %s%n", java.util.Arrays.toString(standing),
                              hf.moveRes, hl.moveRes);
            changed++;
            if(restorations.contains(hl.moveRes))
                restored++;
            if(hl.hpLost < hf.hpLost)
                cheaper++;
        }
        check("at least one hard creature changes the pick at low health", changed > 0, true);
        check("  every changed pick is a restoration", restored, changed);
        check("  and every one costs fewer hitpoints", cheaper, changed);

        /* THE CARD ON SCREEN IS HELD across re-plans unless another is clearly better - replayed
         * party fights changed the pick 20-29 times each, a third of them straight back. Swept
         * over the same hard creatures and states, three of each at once - a lone creature almost
         * always has one clearly best card, and the ties are in crowds: holding the card just
         * picked changes nothing, a card that is not on the bar is not held, and somewhere a
         * different card is held. */
        System.out.println("\na re-plan keeps the card on screen unless another is clearly better");
        int holdSame = 0, sameOf = 0, heldOther = 0, offBar = 0;
        /* THE HOLD RULE ON ITS OWN. With the players' lines offered (Prediction.shownLines) every
         * crowd in this sweep has one clearly best line and nothing is close enough to hold, which
         * says nothing about the rule - so it is swept with the library off, and switched back. */
        Prediction.offerShownLines = false;
        /* Few, because a crowd of three is planned once per target and once per held card. */
        /* Wolves and boars alone used to find their ties in the sizes' low tail - kills that were
         * really partial intakes (creature_sizes, corrected 2026-09-22). At their real size each
         * has one clearly best card, so the sweep reaches for more crowds until one ties. */
        sweep:
        for(int[] standing : new int[][] {{30, 0, 30, 0}, {0, 30, 0, 30}, {0, 0, 0, 0}, {50, 50, 0, 0}})
        for(String h : new String[] {"gfx/kritter/wolf/wolf", "gfx/kritter/boar/boar",
                                     "gfx/kritter/badger/badger", "gfx/kritter/fox/fox",
                                     "gfx/kritter/bat/bat", "gfx/kritter/bear/bear",
                                     "gfx/kritter/lynx/lynx", "gfx/kritter/moose/moose",
                                     "gfx/kritter/reddeer/reddeer", "gfx/kritter/adder/adder",
                                     "gfx/kritter/wolverine/wolverine", "gfx/kritter/greyseal/greyseal"}) {
            if(heldOther > 0)
                break sweep;
            List<Prediction.Seen> pack = Arrays.asList(seen(h, new int[] {20, 0, 0, 20}, 0),
                                                       seen(h, new int[] {0, 0, 0, 30}, 0),
                                                       seen(h, new int[] {10, 0, 10, 0}, 0));
            Prediction.Live free = Prediction.adviseLive(me, null, standing, 300, 300, pack, BEAM, HORIZON);
            if((free.proxied > 0) || (free.moveRes == null))
                continue;
            sameOf++;
            Prediction.Live again = Prediction.adviseLive(me, null, standing, 300, 300, pack,
                                                          BEAM, HORIZON, 0, null, free.moveRes);
            if(free.moveRes.equals(again.moveRes))
                holdSame++;
            Prediction.Live ghost = Prediction.adviseLive(me, null, standing, 300, 300, pack,
                                                          BEAM, HORIZON, 0, null, "paginae/atk/nosuchcard");
            if(free.moveRes.equals(ghost.moveRes))
                offBar++;
            for(String c : BAR) {
                if(c.equals(free.moveRes))
                    continue;
                Prediction.Live h2 = Prediction.adviseLive(me, null, standing, 300, 300, pack,
                                                           BEAM, HORIZON, 0, null, c);
                if(c.equals(h2.moveRes) && (h2.why != null) && h2.why.contains("held:")) {
                    heldOther++;
                    break;
                }
            }
        }
        Prediction.offerShownLines = true;
        System.out.printf("      %d situations, %d held a different card%n", sameOf, heldOther);
        check("  holding the card just picked keeps it", holdSame, sameOf);
        check("  a card not on the bar is not held", offBar, sameOf);
        check("  and some near-tie holds a different card, and says so", heldOther > 0, true);

        System.out.println("\ndamage already dealt comes off the opponent");
        Prediction.Live fresh = advise(me, null, FRESH, 300, 300, seen(known, foeOpen, 0));
        Prediction.Live hurt = advise(me, null, FRESH, 300, 300, seen(known, foeOpen, 1e6));
        System.out.printf("      fresh: %d ticks, nearly dead: %d ticks%n", fresh.ticks, hurt.ticks);
        check("a nearly dead opponent dies no later than a fresh one", hurt.ticks <= fresh.ticks, true);
        check("  and dies", hurt.killed, true);

        /* WHO TO HIT. Against a crowd the plan is searched with each target first. A second
         * copy of the target is no reason to leave it; a nearly dead heavy hitter beside a
         * fresh weak one is, because every tick it stays up it swings. And never one we have
         * offered peace. */
        System.out.println("\nwho to hit: another target only when it is clearly better");
        String heavy = null;
        for(String h : hard) {
            Prediction.Live l = advise(me, null, FRESH, 300, 300, seen(h, new int[] {20, 0, 0, 20}, 0));
            if((l.moveRes != null) && (l.proxied == 0)) {
                heavy = h;
                break;
            }
        }
        check("one of the hard creatures is known to the pack", heavy != null, true);
        Prediction.Seen cur = new Prediction.Seen(1, known, foeOpen, 0, 0, 10, 0, null, true);
        Prediction.Seen twin = new Prediction.Seen(2, known, foeOpen, 0, 0, 10, 0, null, true);
        Prediction.Live same = advise(me, null, FRESH, 300, 300, cur, twin);
        check("an identical second opponent keeps the current target", same.target, 0);
        /* NOT A NEARLY DEAD ONE. That was the first version of this case and the model is right to
         * refuse it: a creature that low has already fled (FoeModel.fleesBelow) and swings at
         * nothing, so no order saves a hitpoint. The case that pays is a FRESH heavy hitter beside
         * the fox - every tick it stays up it swings - and dropping it first costs fewer. */
        /* WHICH heavy hitter is MEASURED, not assumed. This named the cave angler and then went red
         * when the pack was regenerated: against today's reading of it, killing the fox first really
         * is cheaper (1268 ticks / 105 hp against 1372 / 136), so the case's premise had died while
         * the code was fine. So the creature is chosen by the property the case needs - killing it
         * first is clearly quicker AND cheaper, measured by planning each order on its own - and the
         * switch is asserted there. */
        String first = null;
        for(String h : hard) {
            Prediction.Seen heavyOnly = new Prediction.Seen(2, h, foeOpen, 0, 0, 10, 0, null, true);
            Prediction.Seen heavyPassed = new Prediction.Seen(2, h, foeOpen, 0, 0, 10, 0, null, false);
            Prediction.Seen foxPassed = new Prediction.Seen(1, known, foeOpen, 0, 0, 10, 0, null, false);
            Prediction.Live foxFirst = advise(me, null, FRESH, 300, 300, cur, heavyPassed);
            Prediction.Live heavyFirst = advise(me, null, FRESH, 300, 300, heavyOnly, foxPassed);
            if((foxFirst.moveRes == null) || (heavyFirst.moveRes == null) || (foxFirst.proxied > 0))
                continue;
            /* CLEARLY better, by the advice's own margin (Prediction.SWITCH_TICKS) - "better on
             * both" picked a horse at 272 ticks against 312 once creatures' clocks rode our agility
             * (2026-09-23), a real but small gain the advice is right not to chase. */
            boolean better = (heavyFirst.ticks <= Prediction.SWITCH_TICKS * foxFirst.ticks)
                && (heavyFirst.hpLost < foxFirst.hpLost);
            System.out.printf("      %-36s fox first %5d t %6.1f hp | it first %5d t %6.1f hp%s%n",
                              h, foxFirst.ticks, foxFirst.hpLost, heavyFirst.ticks, heavyFirst.hpLost,
                              better ? "  <- killing it first is clearly quicker and cheaper" : "");
            if(better && (first == null))
                first = h;
        }
        check("some opponent is worth killing before the one we are on", first != null, true);
        if(first != null) {
            Prediction.Seen hitter = new Prediction.Seen(2, first, foeOpen, 0, 0, 10, 0, null, true);
            Prediction.Seen hitterPeaced = new Prediction.Seen(2, first, foeOpen, 0, 0, 10, 0, null, false);
            Prediction.Live sw = advise(me, null, FRESH, 300, 300, cur, hitter);
            System.out.printf("      %s beside a fresh %s -> target %d (%s)%n",
                              known, first, sw.target, sw.why);
            check("  and that one is the target the advice picks", sw.target, 1);
            check("  and the reason says to switch", sw.why.startsWith("switch to"), true);
            Prediction.Live pe = advise(me, null, FRESH, 300, 300, cur, hitterPeaced);
            check("but never one we offered peace", pe.target, 0);
        }

        /* THE NEXT BLOW. A total over a fight cannot see one swing, so the worst card each
         * opponent could throw next is priced against our openings - at the top of its damage
         * once it holds initiative - and past the cap, where defending saves more than a
         * negligible amount, the advice must restore or say that nothing on the bar answers it.
         * Where defending saves nothing worth having it must stay quiet. It never backs off. */
        System.out.println("\nthe next blow is watched");
        Map<String, Integer> noRestore = new LinkedHashMap<String, Integer>();
        noRestore.put("paginae/atk/barrage", 1);
        noRestore.put("paginae/atk/cleave", 1);
        noRestore.put("paginae/atk/fullcircle", 1);
        noRestore.put("paginae/atk/sting", 4);
        int past = 0, answered = 0, quiet = 0, quietOk = 0, restores = 0;
        /* The hard creatures, and the two where a sweep of the pack found the guard acting:
         * a vulture bee against one colour wide open, an adder against every colour open. */
        List<String> guarded = new java.util.ArrayList<String>(Arrays.asList(hard));
        guarded.add("gfx/kritter/vulturebee/vulturebee");
        guarded.add("gfx/kritter/adder/adder");
        for(String h : guarded) {
            Prediction.Seen noIp = new Prediction.Seen(1, h, new int[] {20, 0, 0, 20}, 0, 0, 10, 0, null, true);
            Prediction.Seen armed = new Prediction.Seen(1, h, new int[] {20, 0, 0, 20}, 0, 5, 10, 0, null, true);
            Prediction.Live shut = advise(me, null, FRESH, 300, 300, noIp);
            Prediction.Live open = advise(me, null, wide, 300, 300, noIp);
            Prediction.Live openIp = advise(me, null, wide, 300, 300, armed);
            if(open.proxied > 0)
                continue;
            System.out.printf("      %-36s worst blow: shut %5.1f, open %5.1f, open+ip %5.1f (cap %.0f, trade %.1f) -> %s%n",
                              h, shut.danger, open.danger, openIp.danger, open.dangerCap, openIp.trade,
                              (openIp.moveRes == null) ? "-" : openIp.moveRes);
            check("  " + Prediction.shortName(h) + ": an open guard is hit harder than a shut one",
                  open.danger >= shut.danger, true);
            check("  " + Prediction.shortName(h) + ": initiative never makes the blow smaller",
                  openIp.danger >= open.danger - 1e-9, true);
            /* And one colour wide open, which a single restoration can actually close. */
            int[] greenOnly = {80, 0, 0, 0};
            for(Prediction.Live l : new Prediction.Live[] {openIp,
                     advise(me, noRestore, wide, 300, 300, armed),
                     advise(me, null, greenOnly, 300, 300, armed)}) {
                if(!(l.danger > l.dangerCap))
                    continue;
                boolean restoring = (l.moveRes != null) && l.why.contains(" first");
                boolean nothing = l.why.contains("nothing on the bar answers it");
                if(!(l.trade > Prediction.NEGLIGIBLE_HP)) {
                    quiet++;
                    if(!restoring && !nothing)
                        quietOk++;
                    continue;
                }
                past++;
                if(restoring || nothing)
                    answered++;
                if(restoring)
                    restores++;
            }
        }
        System.out.printf("      %d case(s) past the cap worth defending: %d restored; %d past it with nothing to save%n",
                          past, restores, quiet);
        check("some hard creature threatens a blow past the cap", (past + quiet) > 0, true);
        check("  every one worth defending is answered or said to be unanswerable", answered, past);
        check("  and where defending saves nothing, the guard stays quiet", quietOk, quiet);
        check("  and somewhere worth defending, a restoration is thrown into the blow", restores > 0, true);

        /* A BIG BAR IN A LONG FIGHT. The beam loses the winning line once Full Circle is on a bar
         * against one heavy creature; distill() chooses the cards per matchup. The plan it makes
         * must be no slower than the whole bar's, and against at least one of these it must be a
         * real subset that is strictly quicker - the case the selection exists for. */
        System.out.println("\na big bar plans with the cards the matchup wants");
        Map<String, Integer> bigBar = new LinkedHashMap<String, Integer>();
        bigBar.put("paginae/atk/shield", 1);
        bigBar.put("paginae/atk/sideswipe", 3);
        bigBar.put("paginae/atk/uppercut", 3);
        bigBar.put("paginae/atk/fullcircle", 2);
        bigBar.put("paginae/atk/barrage", 5);
        bigBar.put("paginae/atk/cleave", 1);
        bigBar.put("paginae/atk/knockteeth", 2);
        bigBar.put("paginae/atk/oppknock", 2);
        bigBar.put("paginae/atk/punchboth", 5);
        bigBar.put("paginae/atk/qdodge", 5);
        int noSlower = 0, tried = 0, quicker = 0;
        for(String big : new String[] {"gfx/kritter/bear/bear", "gfx/kritter/moose/moose",
                                        "gfx/kritter/wolf/wolf", "gfx/kritter/caveangler/caveangler"}) {
            List<Prediction.Seen> one = Arrays.asList(seen(big, new int[] {10, 0, 0, 10}, 0));
            Prediction.Live whole = Prediction.adviseLive(me, bigBar, FRESH, Double.NaN, Double.NaN, one, BEAM, HORIZON);
            if((whole.moveRes == null) || (whole.proxied > 0)) {
                /* SAID, NOT SKIPPED: a creature that drops out of this list is the failure the
                 * count below reports, and a count cannot say which one or why. */
                System.out.printf("      %-36s not planned as itself: %s%n", big,
                                  (whole.proxied > 0) ? ("a stand-in was used (" + whole.why + ")")
                                                      : ("no card (" + whole.why + ")"));
                continue;
            }
            tried++;
            java.util.Set<String> cards = Prediction.distill(me, bigBar, FRESH, Double.NaN, Double.NaN, one, BEAM, HORIZON);
            Prediction.Live sub = Prediction.adviseLive(me, bigBar, FRESH, Double.NaN, Double.NaN, one, BEAM, HORIZON, 0, cards);
            System.out.printf("      %-36s whole bar %4d ticks%s | chosen %s -> %4d ticks%s%n", big, whole.ticks,
                              whole.killed ? "" : " (no kill)", cards, sub.ticks, sub.killed ? "" : " (no kill)");
            /* RANKED AS THE PLANNER RANKS: a kill before no kill, then fewer ticks. Compared on
             * ticks alone this called the cave angler's chosen cards slower, at 1218 against 1157,
             * when the whole bar's 1157 was a line that never killed it (2026-09-16, once the
             * angler was planned with the cards it throws in reach rather than half restoring). */
            boolean better = (sub.killed && !whole.killed)
                || ((sub.killed == whole.killed) && (sub.ticks < whole.ticks));
            if(better || ((sub.killed == whole.killed) && (sub.ticks == whole.ticks)))
                noSlower++;
            if((cards != null) && better)
                quicker++;
        }
        check("the heavy creatures are all planned as themselves", tried, 4);
        check("  the chosen cards never plan worse than the whole bar", noSlower, tried);
        check("  and against at least one they plan strictly better", quicker > 0, true);

        targets(me);
        System.out.println("agility from the client's bracket:");
        check("  (0, 0.579) of 100 is staged inside it, at the clamp's floor side",
              Math.round(Prediction.agilityFrom(100, 0, 0.579)), 54L);
        check("  (0, 2) is the client's unknown, and stages nothing",
              Double.isNaN(Prediction.agilityFrom(100, 0, 2)), true);
        check("  a crossed bracket stages nothing", Double.isNaN(Prediction.agilityFrom(100, 1.2, 0.8)), true);
        System.out.println("the creature's next action:");
        check("  one that has not acted yet acts at once (0.05 of a 40-tick period)",
              Math.round(Prediction.firstAct(40, -1) * 10), 20L);
        /* A second is 16.7 ticks of 0.06 s. This asserted 20 - the 50 ms tick firstAct had, which
         * planned a creature's next swing a sixth of the elapsed time early (seams audit, 2026-09-23). */
        check("  one that acted a second ago acts 23 ticks later on a 40-tick period",
              Math.round(Prediction.firstAct(40, 1.0)), 23L);
        check("  one overdue acts now, never in the past", Prediction.firstAct(40, 5.0), 0.0);
        check("  unknown stays a full period (NaN)", Double.isNaN(Prediction.firstAct(40, Double.NaN)), true);
        finish();
    }

    static Prediction.Seen at(long gob, String res, double dist, double taken) {
        return(new Prediction.Seen(gob, res, new int[] {10, 0, 0, 10}, 0, 0, dist, taken, null, true));
    }

    /**
     * WHO TO HIT, IN A CROWD (COMBAT.md §3.8 D6, 2026-09-21). Three things James saw or asked for:
     * the advice held on to whatever the server had just made current, a switch never paid for
     * the walk past the others, and a denmother is to be killed first because it brings more bats.
     */
    static void targets(Prediction.Me me) {
        System.out.println("\nwho to hit, in a crowd");
        String wolf = "gfx/kritter/wolf/wolf";
        /* Small enough that two of them die inside the horizon, so the walk decides and a tie does not. */
        String fox = "gfx/kritter/fox/fox";

        /* The server made a fresh wolf current; ours is the other, and the two are identical. */
        List<Prediction.Seen> two = Arrays.asList(at(2, wolf, 20, 0), at(1, wolf, 20, 0));
        Prediction.Live asCurrent = Prediction.adviseLive(me, null, FRESH, Double.NaN, Double.NaN,
                                                          two, BEAM, HORIZON, 0, null, null, 0);
        Prediction.Live asChosen = Prediction.adviseLive(me, null, FRESH, Double.NaN, Double.NaN,
                                                         two, BEAM, HORIZON, 0, null, null, 1);
        System.out.printf("      protecting the current one: target %d; protecting ours: target %d%n",
                          asCurrent.target, asChosen.target);
        check("  with nothing to choose between them, the one we chose is kept", asChosen.target, 1);
        check("    where protecting the current relation kept the newcomer", asCurrent.target, 0);

        /* THE WALK. Ours is the second wolf; the server has put the first in front of us. Beside
         * us, ours is kept. Far off, reaching it is dead time with the one in front still
         * swinging, and that has to cost enough to give it up - James: switching "past other
         * aggroed enemies" lets them "swing on us for free". */
        Prediction.Live near = Prediction.adviseLive(me, null, FRESH, Double.NaN, Double.NaN,
            Arrays.asList(at(2, fox, 20, 0), at(1, fox, 20, 0)), BEAM, HORIZON, 0, null, null, 1);
        Prediction.Live far = Prediction.adviseLive(me, null, FRESH, Double.NaN, Double.NaN,
            Arrays.asList(at(2, fox, 20, 0), at(1, fox, 1500, 0)), BEAM, HORIZON, 0, null, null, 1);
        System.out.printf("      ours beside us:  target %d (%s)%n", near.target, near.why);
        System.out.printf("      ours 1500 away:  target %d (%s)%n", far.target, far.why);
        check("  our target beside us is kept", near.target, 1);
        check("  our target far behind another is given up for the walk", far.target, 0);

        /* A denmother present is the target, whatever else is on us. */
        Prediction.Live den = Prediction.adviseLive(me, null, FRESH, Double.NaN, Double.NaN,
            Arrays.asList(at(1, "gfx/kritter/bat/bat", 20, 0), at(2, "gfx/kritter/denmother/denmother", 60, 0)),
            BEAM, HORIZON);
        System.out.printf("      bat in front, denmother behind: target %d (%s)%n", den.target, den.why);
        check("  a denmother is always the target", den.target, 1);
    }

    static void finish() {
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED"
                           : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
