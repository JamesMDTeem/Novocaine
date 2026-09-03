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
        check("begin",
              CombatEvent.begin(0L, 1788215351180L, 2, "ZzxcuV3", 191070665L, 67527879L,
                                "gfx/kritter/fox/fox", base, comp, 12, 7),
              "{\"ev\":\"begin\",\"t\":0,\"wall\":1788215351180,\"schema\":2,\"char\":\"ZzxcuV3\","
              + "\"megob\":191070665,\"foegob\":67527879,\"foeres\":\"gfx/kritter/fox/fox\","
              + "\"attrb\":{\"agi\":33,\"str\":40},\"attr\":{\"agi\":33,\"str\":55},"
              + "\"hard\":12,\"soft\":7}");
        // Attributes must be sorted, or two logs from the same character will not diff.
        check("begin attrs are sorted",
              CombatEvent.begin(0L, 1L, 2, "c", 1L, 2L, null, comp, comp, 0, 0)
                         .contains("{\"agi\":33,\"str\":55}"), true);
        check("begin tolerates a null foe res",
              CombatEvent.begin(0L, 1L, 2, "c", 1L, 2L, null, null, null, 0, 0),
              "{\"ev\":\"begin\",\"t\":0,\"wall\":1,\"schema\":2,\"char\":\"c\",\"megob\":1,"
              + "\"foegob\":2,\"foeres\":null,\"attrb\":{},\"attr\":{},\"hard\":0,\"soft\":0}");
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
        check("schema constant", CombatEvent.SCHEMA, 10);

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
        check("foes packs gob and four openings per relation",
              CombatEvent.foes(7L, new long[] {11L, 1, 2, 3, 4, 22L, 5, 6, 7, 8}),
              "{\"ev\":\"foes\",\"t\":7,\"o\":[[11,1,2,3,4],[22,5,6,7,8]]}");
        check("foes with one relation is still an array of arrays",
              CombatEvent.foes(1L, new long[] {9L, 0, 0, 0, 0}),
              "{\"ev\":\"foes\",\"t\":1,\"o\":[[9,0,0,0,0]]}");
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
        } catch(Exception e) {
            System.out.println("  writer check threw: " + e);
            failures++;
        }
    }
}
