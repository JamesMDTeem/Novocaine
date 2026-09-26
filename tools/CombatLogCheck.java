/*
 * Checks for the pure haven.combat.log package.
 *
 * NOT part of the client build - build.xml compiles src/ only, and this file lives in tools/ so it
 * can never be pulled into a release jar. The fork has no test framework and this does not add one;
 * it is a single file with a main(), run on demand:
 *
 *   javac -d %TEMP%\combatcheck src\haven\combat\log\*.java tools\CombatLogCheck.java
 *   java -cp %TEMP%\combatcheck CombatLogCheck
 *
 * That works because haven.combat.log deliberately imports nothing from haven. If this file ever
 * stops compiling on its own, a UI type has leaked into the logger and the seam is gone.
 *
 * Exits 0 when every check passes, 1 otherwise.
 */

import haven.combat.log.JsonObj;
import haven.combat.log.Openings;
import haven.combat.log.CombatEvent;
import haven.combat.log.CombatLogWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public class CombatLogCheck {
    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = (got == null) ? want == null : got.equals(want);
        System.out.printf("  %-46s %-40s %s%n", what, got, ok ? "ok" : "WANT " + want);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        jsonBasics();
        openings();
        events();
        writer();
        playerDecks();
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void jsonBasics() {
        System.out.println("JsonObj");
        check("empty", new JsonObj().end(), "{}");
        check("string", new JsonObj().put("a", "b").end(), "{\"a\":\"b\"}");
        check("long", new JsonObj().put("n", 42L).end(), "{\"n\":42}");
        check("bool", new JsonObj().put("t", true).end(), "{\"t\":true}");
        check("null string", new JsonObj().put("a", (String) null).end(), "{\"a\":null}");
        check("two keys", new JsonObj().put("a", 1L).put("b", 2L).end(), "{\"a\":1,\"b\":2}");
        check("raw array", new JsonObj().raw("o", "[1,2,3]").end(), "{\"o\":[1,2,3]}");
        check("quote escaped", JsonObj.esc("he said \"hi\""), "he said \\\"hi\\\"");
        check("backslash escaped", JsonObj.esc("a\\b"), "a\\\\b");
        check("newline escaped", JsonObj.esc("a\nb"), "a\\nb");
        check("control escaped", JsonObj.esc("a\u0001b"), "a\\u0001b");
        // Locale safety: a comma decimal separator would produce invalid JSON.
        check("double is locale-safe", new JsonObj().put("d", 1.5).end(), "{\"d\":1.5000}");
        check("NaN becomes null", new JsonObj().put("d", Double.NaN).end(), "{\"d\":null}");
        // Full-precision form, used by the golden vectors rather than by logs.
        check("num round-trips", Double.parseDouble(JsonObj.num(1.0 / 3.0)), 1.0 / 3.0);
        check("num keeps what put() would round away",
              JsonObj.num(0.00001).equals(new JsonObj().put("d", 0.00001).end()), false);
        check("num handles NaN", JsonObj.num(Double.NaN), "null");
        check("num handles infinity", JsonObj.num(Double.POSITIVE_INFINITY), "null");
    }

    static void openings() {
        System.out.println("\nOpenings");
        check("zero", Openings.ZERO.toJson(), "[0,0,0,0]");
        check("order is g,b,y,r", new Openings(1, 2, 3, 4).toJson(), "[1,2,3,4]");
    }

    static void events() {
        System.out.println("\nCombatEvent");
        check("state",
              CombatEvent.state(1000L, new Openings(5, 0, 0, 0), new Openings(0, 0, 0, 9),
                                2, 3, 875, 0.5, 0.9, 12.25, 42L, 18.5, 9.25, 0, "gfx/tiles/field"),
              "{\"ev\":\"state\",\"t\":1000,\"gob\":42,\"mine\":[5,0,0,0],\"foe\":[0,0,0,9],"
              + "\"myip\":2,\"foeip\":3,\"hpf\":875,\"stam\":0.5000,\"energy\":0.9000,\"dist\":12.2500,\"myspd\":18.5000,\"foespd\":9.2500,\"gst\":0,\"tile\":\"gfx/tiles/field\"}");
        check("state (different foe gob)",
              CombatEvent.state(1004L, new Openings(0, 3, 0, 0), new Openings(1, 0, 2, 0),
                                4, 1, 620, 0.75, 0.2, 6.5, 99L, 0.0, 0.0, 2, null),
              "{\"ev\":\"state\",\"t\":1004,\"gob\":99,\"mine\":[0,3,0,0],\"foe\":[1,0,2,0],"
              + "\"myip\":4,\"foeip\":1,\"hpf\":620,\"stam\":0.7500,\"energy\":0.2000,\"dist\":6.5000,\"myspd\":0.0000,\"foespd\":0.0000,\"gst\":2,\"tile\":null}");
        check("move (own, target unknown)",
              CombatEvent.move(1001L, "me", "paginae/atk/cleave", "Cleave", 80.0, -1L),
              "{\"ev\":\"move\",\"t\":1001,\"actor\":\"me\",\"gob\":-1,\"move\":\"paginae/atk/cleave\","
              + "\"name\":\"Cleave\",\"cd\":80.0000}");
        check("move (foe, with gob)",
              CombatEvent.move(1003L, "foe", "paginae/atk/bite", "Bite", 45.5, 77L),
              "{\"ev\":\"move\",\"t\":1003,\"actor\":\"foe\",\"gob\":77,\"move\":\"paginae/atk/bite\","
              + "\"name\":\"Bite\",\"cd\":45.5000}");
        check("damage",
              CombatEvent.damage(1002L, 55L, "ARM", 37),
              "{\"ev\":\"dmg\",\"t\":1002,\"gob\":55,\"ch\":\"ARM\",\"v\":37}");

        check("move (own, with target gob)",
              CombatEvent.move(1005L, "me", "paginae/atk/barrage", "Barrage", 18.0, 42L),
              "{\"ev\":\"move\",\"t\":1005,\"actor\":\"me\",\"gob\":42,\"move\":\"paginae/atk/barrage\","
              + "\"name\":\"Barrage\",\"cd\":18.0000}");
        check("move (no tooltip layer)",
              CombatEvent.move(1007L, "foe", "paginae/atk/fscratch", null, -1, 67527879L),
              "{\"ev\":\"move\",\"t\":1007,\"actor\":\"foe\",\"gob\":67527879,"
              + "\"move\":\"paginae/atk/fscratch\",\"name\":null,\"cd\":-1.0000}");
        check("damage (unknown channel keeps its code)",
              CombatEvent.damage(1006L, 55L, "C65535", 229),
              "{\"ev\":\"dmg\",\"t\":1006,\"gob\":55,\"ch\":\"C65535\",\"v\":229}");

        java.util.SortedMap<String, Integer> base = new java.util.TreeMap<String, Integer>();
        base.put("str", 40);
        base.put("agi", 33);
        java.util.SortedMap<String, Integer> comp = new java.util.TreeMap<String, Integer>();
        comp.put("str", 55);
        comp.put("agi", 33);
        java.util.Map<String, Integer> deck = new java.util.LinkedHashMap<String, Integer>();
        deck.put("paginae/atk/barrage", 5);
        check("begin",
              CombatEvent.begin(0L, 1788215351180L, 2, "ZzxcuV3", 191070665L, 67527879L,
                                "gfx/kritter/fox/fox", base, comp, 12, 7, deck),
              "{\"ev\":\"begin\",\"t\":0,\"wall\":1788215351180,\"schema\":2,\"char\":\"ZzxcuV3\","
              + "\"megob\":191070665,\"foegob\":67527879,\"foeres\":\"gfx/kritter/fox/fox\","
              + "\"attrb\":{\"agi\":33,\"str\":40},\"attr\":{\"agi\":33,\"str\":55},"
              + "\"hard\":12,\"soft\":7,\"deck\":{\"paginae/atk/barrage\":5}}");
        // Attributes must be sorted, or two logs from the same character will not diff.
        check("begin attrs are sorted",
              CombatEvent.begin(0L, 1L, 2, "c", 1L, 2L, null, comp, comp, 0, 0, null)
                         .contains("{\"agi\":33,\"str\":55}"), true);
        /* THE DECK IS NULL WHEN IT COULD NOT BE READ, never an empty object. An empty deck
         * is a claim - that we fought holding no cards - and a reader must be able to tell
         * it from "the character window was not open". */
        check("begin tolerates a null foe res and a deck it could not read",
              CombatEvent.begin(0L, 1L, 2, "c", 1L, 2L, null, null, null, 0, 0, null),
              "{\"ev\":\"begin\",\"t\":0,\"wall\":1,\"schema\":2,\"char\":\"c\",\"megob\":1,"
              + "\"foegob\":2,\"foeres\":null,\"attrb\":{},\"attr\":{},\"hard\":0,\"soft\":0,"
              + "\"deck\":null}");
        /* 4 adds the "foes" event: every opponent's openings, not only the sampled one's.
         * A reader must treat its absence as "this log predates the event", never as "no
         * other opponents were open" - schema 1 logs have no header at all and schema 3
         * logs have no foes lines, and both are still valid evidence for everything else
         * they do carry. */
        /* 5 adds the "buffs" event: what each side is holding, stance included. An
         * opponent's defence weight is skill x block multiplier x mu, and the multiplier
         * comes from the stance - the difference between Bloodlust's 75% of Unarmed and
         * Shield Up's 250% of Melee. */
        /* 6 adds speed to the state sample - Gob.gobSpeed for both sides, the same figure
         * the client draws in white under anything that moves. It replaces inferring a
         * speed from how fast the distance changed, which could not tell a fast creature
         * from one we never withdrew from. */
        /* 7 adds the aggression state and the tile underfoot. gst bit 2 is the opponent's
         * olive branch - what an animal extends when it starts to run - and a fleeing
         * animal stops fighting back. The tile matters because terrain gates OUR speed,
         * so a logged speed cannot be compared against another without it. */
        /* 8 adds the prediction: what the model expected a move to do, written by the
         * client at the moment the move was thrown. The point is that it is written DOWN.
         * A residual can always be recomputed later by running today's model over an old
         * log, and that is a different and worse thing - every change to the data pack
         * silently rewrites the history, so a fix can never be shown to have helped
         * because the "before" number moves with it. */
        /* 16 adds each relation's range to the foes event, as a third parallel array.
         * The state event has measured a distance since the beginning and measures it for
         * ONE opponent, so a fight against several has never recorded who was within
         * reach - which is why nothing downstream models range, while three cards in the
         * sheet hit "all other opponents in range". */
        /* 17 adds two rows carrying what the client had in hand and threw away: the
         * opponent's decision state at the instant it acted (foeact - its openings, IP,
         * reach and the observed gap since it last threw that card), and the per-relation
         * defence timers (mvfx - lastActCleave/lastActDefence/lastDefenceDuration). Both
         * are new events, so an old reader ignores them and an old log stays readable;
         * no existing key moved. 18 adds `oo` to the foeact row - the other side's openings at the same instant, null on rows written before it existed. */
        /* 19 replaces foeact's `since`/`rd` with `gap`/`prev`. A combatant has ONE cooldown and
         * the card it threw sets how long, so the gap to the next action - any card - keyed by
         * the card thrown before it is the observable; the same-card gap `since` carried was a
         * rotation. No pooled log carried a foeact row when the meaning changed. */
        /* 20 adds each relation's initiative, both sides, to the foes event as two more
         * parallel arrays. The game keeps initiative per relation, and only the sampled
         * relation's pair was ever written. Older foes forms are byte-identical. */
        /* 21 names a PLAYER opponent by kin name on a foe row, where the client knows it.
         * A player's resource is the same for everybody and their gob changes per login,
         * so without the name no two fights against one person can be joined. */
        /* 22 adds the advin row: what the LIVE advice was planning from - armed or not, which
         * weapon resolved, the bar, and the cards chosen to plan with. A fight where the advice
         * looked wrong could not be diagnosed from its own log, and one that was spent throwing
         * a card that attacked colours the creature was not open in took a session of guessing
         * to trace to an advisor that believed it was bare-handed. */
        /* 23 adds the charge row: a non-opening buff's meter. Bloodlust raises our attack weight
         * by four times its charge, and without the charge a gain made under it cannot be priced. */
        /* 24 adds durability to the gear row (wd of wm), so armour wear per point soaked can be
         * measured rather than assumed (COMBAT.md §3.8 D1). */
        check("schema constant", CombatEvent.SCHEMA, 24);
        check("gear carries durability when the piece has any",
              CombatEvent.gear(40L, 2, "gfx/invobjs/cuirass", 10.0, 8, 3, false, 120, 2000),
              "{\"ev\":\"gear\",\"t\":40,\"slot\":2,\"res\":\"gfx/invobjs/cuirass\","
              + "\"ql\":10.0000,\"hard\":8,\"soft\":3,\"broken\":false,\"wd\":120,\"wm\":2000}");
        check("  and without durability the row is the old one",
              CombatEvent.gear(40L, 2, "gfx/invobjs/cuirass", 10.0, 8, 3, false, -1, -1),
              CombatEvent.gear(40L, 2, "gfx/invobjs/cuirass", 10.0, 8, 3, false));
        check("charge carries the meter of a held buff",
              CombatEvent.charge(12L, -1L, "me", "paginae/atk/bloodlust", 0.25),
              "{\"ev\":\"charge\",\"t\":12,\"gob\":-1,\"who\":\"me\","
              + "\"res\":\"paginae/atk/bloodlust\",\"v\":0.2500}");
        check("foe names a player by kin name",
              CombatEvent.foe(9L, 55L, "gfx/borka/body", "kin", "Some \"One\""),
              "{\"ev\":\"foe\",\"t\":9,\"gob\":55,\"res\":\"gfx/borka/body\","
              + "\"how\":\"kin\",\"kin\":\"Some \\\"One\\\"\"}");
        check("  and without a name the row is the old one",
              CombatEvent.foe(9L, 55L, "gfx/borka/body", "new", null),
              CombatEvent.foe(9L, 55L, "gfx/borka/body", "new"));
        check("foes carries each relation's initiative, ours and theirs",
              CombatEvent.foes(7L, new long[] {11L, 1, 2, 3, 4, 22L, 5, 6, 7, 8},
                               new int[] {0, 2}, new int[] {12, 30},
                               new int[] {6, 0}, new int[] {1, 3}),
              "{\"ev\":\"foes\",\"t\":7,\"o\":[[11,1,2,3,4],[22,5,6,7,8]],"
              + "\"g\":[0,2],\"d\":[12,30],\"ip\":[6,0],\"oip\":[1,3]}");
        /* THE ROW THE SCHEMA BUMP ADDED HAD NO CASE HERE, and it shipped broken. advin was the
         * one factory that finished with toString() rather than end(), and JsonObj does not
         * override toString(), so every advin row written into a live fight was the builder's
         * object identity - "haven.combat.log.JsonObj@12b06f5f". Seventeen of them across eight
         * of James's fights, unparseable to every reader, which is why the row added to answer
         * "what was the advice planning from" answered nothing and pool_check.py failed on
         * files with unparseable lines (2026-09-15). An exact-string case cannot miss that: an
         * object identity fails it on the first character. Every factory needs one.
         */
        check("advin is JSON, not the builder's identity",
              CombatEvent.advin(5L, true, "gfx/invobjs/small/bronzesword", 236.0,
                                java.util.Arrays.asList("paginae/atk/uppercut",
                                                        "paginae/atk/sideswipe"),
                                java.util.Arrays.asList("paginae/atk/sideswipe")),
              "{\"ev\":\"advin\",\"t\":5,\"armed\":true,"
              + "\"weapon\":\"gfx/invobjs/small/bronzesword\",\"wdmg\":236.0000,"
              + "\"bar\":[\"paginae/atk/uppercut\",\"paginae/atk/sideswipe\"],"
              + "\"chosen\":[\"paginae/atk/sideswipe\"]}");
        check("  and an unresolved weapon writes nulls, not an empty bar",
              CombatEvent.advin(5L, false, null, 0.0, null, null),
              "{\"ev\":\"advin\",\"t\":5,\"armed\":false,\"weapon\":null,\"wdmg\":0.0000,"
              + "\"bar\":null,\"chosen\":null}");

        check("foeact",
              CombatEvent.foeact(100, 77L, "paginae/atk/bite", "Bite",
                                 new Openings(0, 0, 0, 9), new Openings(4, 0, 0, 0), 3, 2, 12.0, 0, 4.5,
                                 "paginae/atk/growl"),
              "{\"ev\":\"foeact\",\"t\":100,\"gob\":77,\"move\":\"paginae/atk/bite\","
              + "\"name\":\"Bite\",\"o\":[0,0,0,9],\"oo\":[4,0,0,0],\"ip\":3,\"oip\":2,\"dist\":12.0000,"
              + "\"gst\":0,\"gap\":4.5000,\"prev\":\"paginae/atk/growl\"}");

        check("mvfx",
              CombatEvent.mvfx(105, 77L, "paginae/atk/shieldup", "Shield Up", 1200, 900, 4300),
              "{\"ev\":\"mvfx\",\"t\":105,\"gob\":77,\"move\":\"paginae/atk/shieldup\","
              + "\"name\":\"Shield Up\",\"cleave\":1200,\"def\":900,\"dur\":4300}");

        /* Advice, schema 15: what the model would have thrown, logged and not acted on.
         * The frontier count is part of it because one plan is not a choice. */
        check("advice",
              CombatEvent.advice(310, 7, "paginae/atk/barrage", "36m/35f/26w", 240,
                                 18.5, true, 6),
              "{\"ev\":\"advice\",\"t\":310,\"gob\":7,"
              + "\"move\":\"paginae/atk/barrage\",\"pack\":\"36m/35f/26w\","
              + "\"ticks\":240,\"hp\":18.5000,\"killed\":true,\"frontier\":6}");

        /* The card's own sheet, schema 13. The offline analysis had only the wiki's table
         * for an opponent's cards, and that table is incomplete and in places wrong; the
         * resource a used card resolves to carries the game's own text. A null pagina is a
         * fact about the card, not a failure, and must survive as null. */
        check("card event",
              CombatEvent.card(7, "paginae/atk/antspit", "Ant Spit",
                               "Openings: +15% $col[128,192,255]{Dizzy}\n"),
              "{\"ev\":\"card\",\"t\":7,\"res\":\"paginae/atk/antspit\","
              + "\"name\":\"Ant Spit\","
              + "\"pagina\":\"Openings: +15% $col[128,192,255]{Dizzy}\\n\"}");
        check("  a card with no sheet writes null, not an empty string",
              CombatEvent.card(1, "paginae/atk/x", null, null),
              "{\"ev\":\"card\",\"t\":1,\"res\":\"paginae/atk/x\","
              + "\"name\":null,\"pagina\":null}");

        check("a prediction",
              CombatEvent.predict(120, 7, "paginae/atk/knockteeth", "36m/35f/26w",
                                  new double[] {0, 0, 0, 14.0}, 7.7, 1.9, 34),
              "{\"ev\":\"predict\",\"t\":120,\"gob\":7,"
              + "\"move\":\"paginae/atk/knockteeth\",\"pack\":\"36m/35f/26w\","
              + "\"opened\":[0.0,0.0,0.0,14.0],\"dealt\":7.7000,\"grievous\":1.9000,"
              + "\"cd\":34}");
        /* gst bit 2 is the OPPONENT's olive branch, which an animal extends when it has
         * taken enough and starts to run - and a fleeing animal stops fighting back, so
         * everything bought after it is bought for nothing. A null tile is an unloaded
         * grid, which is a real answer and not a failure. */
        check("state carries the aggression state and the tile underfoot",
              CombatEvent.state(1L, Openings.ZERO, Openings.ZERO, 0, 0, 10000, 1.0, 1.0,
                                5.0, 9L, 0.0, 0.0, 2, "gfx/tiles/forest")
                         .contains("\"gst\":2,\"tile\":\"gfx/tiles/forest\""), true);
        check("state carries both speeds",
              CombatEvent.state(1L, Openings.ZERO, Openings.ZERO, 0, 0, 10000, 1.0, 1.0,
                                5.0, 9L, 18.5, 9.25, 0, null)
                         .contains("\"myspd\":18.5000,\"foespd\":9.2500"), true);
        check("buffs lists what a combatant is holding",
              CombatEvent.buffs(3L, 42L, "foe",
                                new String[] {"paginae/atk/shieldup", "paginae/atk/cornered"}),
              "{\"ev\":\"buffs\",\"t\":3,\"gob\":42,\"who\":\"foe\","
              + "\"res\":[\"paginae/atk/shieldup\",\"paginae/atk/cornered\"]}");
        /* 11 adds each relation's aggression state to the foes event, as a PARALLEL
         * array, so every reader of the five-wide `o` rows keeps working unchanged. The
         * state event carries gst for the sampled opponent only, which is wrong for the
         * case it matters in: a pack aggroes together and gives up one at a time. */
        check("foes packs gob and four openings per relation, and each one's gst",
              CombatEvent.foes(7L, new long[] {11L, 1, 2, 3, 4, 22L, 5, 6, 7, 8},
                               new int[] {0, 2}),
              "{\"ev\":\"foes\",\"t\":7,\"o\":[[11,1,2,3,4],[22,5,6,7,8]],"
              + "\"g\":[0,2],\"d\":[]}");
        check("foes with one relation is still an array of arrays",
              CombatEvent.foes(1L, new long[] {9L, 0, 0, 0, 0}, new int[] {0}),
              "{\"ev\":\"foes\",\"t\":1,\"o\":[[9,0,0,0,0]],\"g\":[0],\"d\":[]}");
        /* 16 adds each relation's RANGE, as a third parallel array. The state event has
         * carried a distance from the beginning and carries it for the sampled opponent
         * only, so a fight with five animals in it recorded the range to one of them -
         * which is why nothing downstream can say which of a crowd a card could reach,
         * and three cards in the sheet hit "all other opponents in range". */
        check("foes carries how far away each relation is",
              CombatEvent.foes(7L, new long[] {11L, 1, 2, 3, 4, 22L, 5, 6, 7, 8},
                               new int[] {0, 2}, new int[] {9, 34}),
              "{\"ev\":\"foes\",\"t\":7,\"o\":[[11,1,2,3,4],[22,5,6,7,8]],"
              + "\"g\":[0,2],\"d\":[9,34]}");
        /* A relation whose gob has not arrived yet has no distance, and -1 says so rather
         * than a zero that reads as standing on top of us. */
        check("  and says so when it cannot tell",
              CombatEvent.foes(1L, new long[] {9L, 0, 0, 0, 0}, new int[] {0},
                               new int[] {-1}).contains("\"d\":[-1]"),
              true);
        /* The reason the field exists, so it gets its own row rather than being left
         * implicit above: one of a pack disengaging while the rest stay on us. */
        check("one of a pack can disengage while the rest do not",
              CombatEvent.foes(9L, new long[] {1L, 0, 0, 0, 0, 2L, 0, 0, 0, 0,
                                              3L, 0, 0, 0, 0},
                               new int[] {2, 0, 0}).contains("\"g\":[2,0,0]"),
              true);
        // The opponent events, added in schema 3. Without them a fight against more than one
        // opponent reads as one opponent whose openings jump for no reason.
        check("foe (appears)",
              CombatEvent.foe(17L, 1649181853L, "gfx/kritter/lynx/lynx", "new"),
              "{\"ev\":\"foe\",\"t\":17,\"gob\":1649181853,"
              + "\"res\":\"gfx/kritter/lynx/lynx\",\"how\":\"new\"}");
        check("foe (becomes the sampled one)",
              CombatEvent.foe(27539L, 675939166L, "gfx/kritter/boar/boar", "current"),
              "{\"ev\":\"foe\",\"t\":27539,\"gob\":675939166,"
              + "\"res\":\"gfx/kritter/boar/boar\",\"how\":\"current\"}");
        // A relation can arrive before its gob does, and the res then reads null. That is a fact
        // about the fight, not a failure - the "name" event carries the answer when it arrives.
        check("foe (resource not loaded yet)",
              CombatEvent.foe(0L, 5L, null, "new"),
              "{\"ev\":\"foe\",\"t\":0,\"gob\":5,\"res\":null,\"how\":\"new\"}");
        check("foe (no relation is current)",
              CombatEvent.foe(1L, -1L, null, "current"),
              "{\"ev\":\"foe\",\"t\":1,\"gob\":-1,\"res\":null,\"how\":\"current\"}");
        check("gear",
              CombatEvent.gear(0L, 6, "gfx/invobjs/cutthroatknuckles", 34.5, 0, 0, false),
              "{\"ev\":\"gear\",\"t\":0,\"slot\":6,\"res\":\"gfx/invobjs/cutthroatknuckles\","
              + "\"ql\":34.5000,\"hard\":0,\"soft\":0,\"broken\":false}");
        check("gear (broken)",
              CombatEvent.gear(0L, 2, "gfx/invobjs/cuirass", 10.0, 8, 3, true),
              "{\"ev\":\"gear\",\"t\":0,\"slot\":2,\"res\":\"gfx/invobjs/cuirass\","
              + "\"ql\":10.0000,\"hard\":8,\"soft\":3,\"broken\":true}");
        check("end",
              CombatEvent.end(26999L, "ended", 3, false),
              "{\"ev\":\"end\",\"t\":26999,\"reason\":\"ended\",\"dropped\":3,\"failed\":false}");
        /* Quarters, not a fraction. The server sends a uint8 the client divides by
         * four, so 0 to 4 is the whole resolution - and a log that said 0.75 would
         * invite a reader to believe three-quarters had been measured. */
        check("health",
              CombatEvent.health(4100L, 91L, 3),
              "{\"ev\":\"hp\",\"t\":4100,\"gob\":91,\"q\":3}");
        /* The weapon's own figures, which retire a wiki join that misses silently.
         * Penetration and grievous arrive already divided by a hundred, so what is
         * pinned here is the 0..1 fraction Formulas takes - not a percentage. */
        java.util.Map<String, Double> wv = new java.util.LinkedHashMap<String, Double>();
        wv.put("damage", 12.0);
        wv.put("armpen", 0.125);
        check("weapon",
              CombatEvent.weapon(0, 7, "gfx/invobjs/bronzesword", wv),
              "{\"ev\":\"wpn\",\"t\":0,\"slot\":7,"
              + "\"res\":\"gfx/invobjs/bronzesword\","
              + "\"v\":{\"damage\":12.0000,\"armpen\":0.1250}}");
        /* AND THE HAND EMPTYING, which for two months wrote nothing at all. A slot that
         * goes from a weapon to a shield or to nothing has no weapon figures to write, so
         * the old writer skipped it and the file went on naming the weapon that had left.
         * A null res with an empty `v` is the removal, the same convention the gear row
         * uses, and it is pinned here because it is the only row whose whole content is
         * an absence. */
        check("weapon, hand emptied",
              CombatEvent.weapon(4200, 6, null, null),
              "{\"ev\":\"wpn\",\"t\":4200,\"slot\":6,"
              + "\"res\":null,\"v\":{}}");
    }

    /* A player's deck is learned by watching and remembered by kin name. The known answers:
     * cards accumulate per player, a gob-keyed player is never written down, the file round-
     * trips, and a damaged line costs that line and nothing else. */
    static void playerDecks() {
        System.out.println("\nPlayerDecks");
        try {
            Path dir = Files.createTempDirectory("playerdecks");
            Path f = dir.resolve("player-decks.tsv");
            haven.combat.log.PlayerDecks d = new haven.combat.log.PlayerDecks();
            String named = haven.combat.log.PlayerDecks.keyFor("Some Body", 11L);
            String bare = haven.combat.log.PlayerDecks.keyFor(null, 22L);
            check("a named player is keyed by name", named, "kin:Some Body");
            check("  and an unnamed one by gob", bare, "gob:22");
            d.observe(named, "paginae/atk/cleave");
            d.observe(named, "paginae/atk/cleave");
            d.observe(named, "paginae/atk/sting");
            d.observe(bare, "paginae/atk/punch");
            check("cards accumulate per player", d.deck(named).get("paginae/atk/cleave"), 2);
            haven.combat.log.PlayerDecks later = new haven.combat.log.PlayerDecks();
            String gobKey = haven.combat.log.PlayerDecks.keyFor(null, 77L);
            String kinKey = haven.combat.log.PlayerDecks.keyFor("Named Later", 77L);
            later.observe(gobKey, "paginae/atk/cleave");
            later.observe(kinKey, "paginae/atk/cleave");
            later.adopt(gobKey, kinKey);
            check("a player named after their first cards keeps those cards",
                  later.deck(kinKey).get("paginae/atk/cleave"), 2);
            check("  and the gob key is emptied", later.deck(gobKey).isEmpty(), true);
            check("  and the deck holds every card seen", d.deck(named).size(), 2);
            d.save(f);
            haven.combat.log.PlayerDecks back = haven.combat.log.PlayerDecks.load(f);
            check("a named deck survives a save and a load", back.deck(named), d.deck(named));
            check("  and a gob-keyed one is never written", back.deck(bare).isEmpty(), true);
            Files.write(f, java.util.Arrays.asList("kin:A\tpaginae/atk/x\t3", "broken line",
                                                   "kin:A\tpaginae/atk/y\tnot-a-number"));
            haven.combat.log.PlayerDecks damaged = haven.combat.log.PlayerDecks.load(f);
            check("a damaged line costs that line and nothing else",
                  damaged.deck("kin:A").toString(), "{paginae/atk/x=3}");
            check("  and a missing file is an empty memory",
                  haven.combat.log.PlayerDecks.load(dir.resolve("absent.tsv")).players().isEmpty(), true);
        } catch(IOException e) {
            check("player decks raised no IOException", e.toString(), "none");
        }
    }

    static void writer() {
        System.out.println("\nCombatLogWriter");
        try {
            Path dir = Files.createTempDirectory("combatlog");
            Path f = dir.resolve("a.jsonl");

            CombatLogWriter w = new CombatLogWriter(f, 64);
            w.offer("{\"a\":1}");
            w.offer("{\"a\":2}");
            w.close();
            List<String> lines = Files.readAllLines(f);
            check("line count", lines.size(), 2);
            check("first line", lines.get(0), "{\"a\":1}");
            check("second line", lines.get(1), "{\"a\":2}");
            check("nothing dropped", w.dropped(), 0);

            // close is idempotent - the recorder may close on both combat end and logout.
            w.close();
            check("double close survives", Files.readAllLines(f).size(), 2);

            // A full queue must drop, never block and never throw.
            Path g = dir.resolve("b.jsonl");
            CombatLogWriter w2 = new CombatLogWriter(g, 1);
            for(int i = 0; i < 20000; i++)
                w2.offer("{\"i\":" + i + "}");
            w2.close();
            check("overflow dropped some", w2.dropped() > 0, true);
            check("overflow wrote something", Files.readAllLines(g).size() > 0, true);

            // offer after close is a no-op, not a crash.
            w2.offer("{\"late\":1}");
            check("offer after close is safe", true, true);

            // A healthy writer reports alive.
            CombatLogWriter w3 = new CombatLogWriter(dir.resolve("c.jsonl"), 8);
            check("alive for healthy writer", w3.alive(), true);
            w3.close();

            // An unopenable path must fail loudly from the constructor, not silently on the
            // background thread. A regular file where a directory is required does it portably.
            Path blocker = Files.createTempFile("combatlog-blocker", "");
            Path bad = blocker.resolve("nested").resolve("a.jsonl");
            boolean threw = false;
            try {
                new CombatLogWriter(bad, 8);
            } catch(IOException e) {
                threw = true;
            }
            check("bad path throws from constructor", threw, true);

            // offerLater: a line still being computed keeps the place it was offered in, however
            // long it takes - the recorder's advice row, searched off the UI thread (2026-09-25).
            java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
            Path h = dir.resolve("d.jsonl");
            CombatLogWriter w4 = new CombatLogWriter(h, 64);
            w4.offer("{\"n\":1}");
            w4.offerLater(ex.submit(() -> {Thread.sleep(300); return("{\"n\":2}");}));
            w4.offer("{\"n\":3}");
            w4.offerLater(ex.submit(() -> null));
            w4.offerLater(ex.submit(() -> {throw(new RuntimeException("search failed"));}));
            w4.offer("{\"n\":4}");
            w4.close();
            ex.shutdown();
            check("later line keeps its place", Files.readAllLines(h),
                  java.util.Arrays.asList("{\"n\":1}", "{\"n\":2}", "{\"n\":3}", "{\"n\":4}"));
            check("a failed later line counts as dropped", w4.dropped(), 1);

            // A null answer writes nothing and loses nothing, as a caller with no advice would.
            java.util.concurrent.ExecutorService ex2 = java.util.concurrent.Executors.newSingleThreadExecutor();
            Path k = dir.resolve("e.jsonl");
            CombatLogWriter w5 = new CombatLogWriter(k, 8);
            w5.offerLater(ex2.submit(() -> null));
            w5.offer("{\"n\":1}");
            w5.close();
            ex2.shutdown();
            check("null later line writes nothing", Files.readAllLines(k), java.util.Arrays.asList("{\"n\":1}"));
            check("and counts nothing dropped", w5.dropped(), 0);
        } catch(Exception e) {
            System.out.println("  writer check threw: " + e);
            failures++;
        }
    }
}
