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
                                2, 3, 875, 0.5, 0.9, 12.25, 42L),
              "{\"ev\":\"state\",\"t\":1000,\"gob\":42,\"mine\":[5,0,0,0],\"foe\":[0,0,0,9],"
              + "\"myip\":2,\"foeip\":3,\"hpf\":875,\"stam\":0.5000,\"energy\":0.9000,\"dist\":12.2500}");
        check("state (different foe gob)",
              CombatEvent.state(1004L, new Openings(0, 3, 0, 0), new Openings(1, 0, 2, 0),
                                4, 1, 620, 0.75, 0.2, 6.5, 99L),
              "{\"ev\":\"state\",\"t\":1004,\"gob\":99,\"mine\":[0,3,0,0],\"foe\":[1,0,2,0],"
              + "\"myip\":4,\"foeip\":1,\"hpf\":620,\"stam\":0.7500,\"energy\":0.2000,\"dist\":6.5000}");
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
        check("schema constant", CombatEvent.SCHEMA, 2);
        check("gear",
              CombatEvent.gear(0L, 6, "gfx/invobjs/cutthroatknuckles", 34.5, 0, 0, false),
              "{\"ev\":\"gear\",\"t\":0,\"slot\":6,\"res\":\"gfx/invobjs/cutthroatknuckles\","
              + "\"ql\":34.5000,\"hard\":0,\"soft\":0,\"broken\":false}");
        check("gear (broken)",
              CombatEvent.gear(0L, 2, "gfx/invobjs/cuirass", 10.0, 8, 3, true),
              "{\"ev\":\"gear\",\"t\":0,\"slot\":2,\"res\":\"gfx/invobjs/cuirass\","
              + "\"ql\":10.0000,\"hard\":8,\"soft\":3,\"broken\":true}");
        check("end",
              CombatEvent.end(26999L, "ended"),
              "{\"ev\":\"end\",\"t\":26999,\"reason\":\"ended\"}");
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
