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
                                2, 3, 875, 0.5, 0.9, 12.25),
              "{\"ev\":\"state\",\"t\":1000,\"mine\":[5,0,0,0],\"foe\":[0,0,0,9],"
              + "\"myip\":2,\"foeip\":3,\"hp\":875,\"stam\":0.5000,\"energy\":0.9000,\"dist\":12.2500}");
        check("move",
              CombatEvent.move(1001L, "me", "paginae/atk/cleave", 80.0),
              "{\"ev\":\"move\",\"t\":1001,\"actor\":\"me\",\"move\":\"paginae/atk/cleave\",\"cd\":80.0000}");
        check("damage",
              CombatEvent.damage(1002L, 55L, "ARM", 37),
              "{\"ev\":\"dmg\",\"t\":1002,\"gob\":55,\"ch\":\"ARM\",\"v\":37}");
    }
}
