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
        return(Prediction.me(attrs, 30, 30, new String[] {"gfx/invobjs/bronzesword", null},
                             new double[] {40, 0}, levels));
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
        /* Few, because a crowd of three is planned once per target and once per held card. */
        for(int[] standing : new int[][] {{30, 0, 30, 0}, {0, 30, 0, 30}})
        for(String h : new String[] {"gfx/kritter/wolf/wolf", "gfx/kritter/boar/boar"}) {
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
            boolean better = (heavyFirst.ticks < foxFirst.ticks)
                && (heavyFirst.hpLost < foxFirst.hpLost);
            System.out.printf("      %-36s fox first %5d t %6.1f hp | it first %5d t %6.1f hp%s%n",
                              h, foxFirst.ticks, foxFirst.hpLost, heavyFirst.ticks, heavyFirst.hpLost,
                              better ? "  <- killing it first is better on both" : "");
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
            if((whole.moveRes == null) || (whole.proxied > 0))
                continue;
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

        finish();
    }

    static void finish() {
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED"
                           : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
