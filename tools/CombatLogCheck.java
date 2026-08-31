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
}
