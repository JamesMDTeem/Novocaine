/*
 * The recorder's per-card advice must not cost the frame, and must still land where it did.
 *
 * Reported 2026-09-25 as a hitch at the start of combat. Every card we throw with Record Combat
 * Telemetry on (the default) makes CombatRecorder.predict write the prediction and then the
 * advice - a beam search over up to four opponents - and that ran on the UI thread: 50-150 ms per
 * card, 120-360 ms on the first. This drives predict() itself against a real writer and a real
 * character and checks that it returns inside a frame, that the advice row still follows the
 * prediction row in the file, and that it says what the search says.
 *
 * NOT part of the client build. Run from the repo root (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;lib\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\advcheck tools\CombatAdviceTimingCheck.java
 *   java -cp "$env:TEMP\advcheck;$CP" haven.automated.combat.CombatAdviceTimingCheck
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven.automated.combat;

import haven.combat.log.CombatLogWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

public class CombatAdviceTimingCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    static void set(String field, Object v) throws Exception {
        Field f = CombatRecorder.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, v);
    }

    static Object get(String field) throws Exception {
        Field f = CombatRecorder.class.getDeclaredField(field);
        f.setAccessible(true);
        return(f.get(null));
    }

    static Prediction.Me me() {
        SortedMap<String, Integer> attrs = new TreeMap<String, Integer>();
        attrs.put("str", 94);
        attrs.put("agi", 111);
        attrs.put("unarmed", 81);
        attrs.put("melee", 125);
        Map<String, Integer> lv = new LinkedHashMap<String, Integer>();
        for(String c : new String[] {"barrage", "cleave", "fullcircle", "jump", "qdodge", "shield",
                                     "sidestep", "sting", "takeaim", "zigzag"})
            lv.put("paginae/atk/" + c, 3);
        return(Prediction.me(attrs, 30, 30, new String[] {"gfx/invobjs/bronzesword", null},
                             new double[] {40, 0}, lv));
    }

    public static void main(String[] args) throws Exception {
        /* A fox, because the logged prediction declines any opponent whose skill the corpus has
         * not recovered, and the fox is one it has - so both rows are written. */
        String foe = (args.length > 0) ? args[0] : "gfx/kritter/fox/fox";
        long gob = 4242;
        int[] open = {5, 0, 10, 0};
        Prediction.Me m = me();
        check(m.usable(), "the staged character is usable");
        boolean predicts = Prediction.of(me(), foe, "paginae/atk/sting", open, 0) != null;
        Prediction.Advised direct = Prediction.advise(me(), foe, open, 0, 60, 2500);
        check(direct != null, "the search has an answer against " + foe);

        Method predict = CombatRecorder.class.getDeclaredMethod("predict", String.class, String.class, long.class);
        predict.setAccessible(true);
        Path dir = Files.createTempDirectory("advcheck");
        double worst = 0;
        int cards = 6;
        Path f = dir.resolve("fight.jsonl");
        CombatLogWriter w = new CombatLogWriter(f, 4096);
        set("writer", w);
        set("t0", System.currentTimeMillis());
        set("me", m);
        set("foeRes", foe);
        set("lastFoeOpen", open);
        set("lastMyIp", 0);
        set("lastFoeGob", gob);
        for(int i = 0; i < cards; i++) {
            long t0 = System.nanoTime();
            predict.invoke(null, "me", "paginae/atk/sting", gob);
            double ms = (System.nanoTime() - t0) / 1e6;
            worst = Math.max(worst, ms);
            w.offer("{\"ev\":\"marker\",\"i\":" + i + "}");
        }
        set("writer", null);
        w.close();
        System.out.printf("  %d cards: worst predict() on the calling thread %.1f ms%n", cards, worst);
        check(worst < 16.0, "predict() returns inside one 60 fps frame (" + String.format("%.1f", worst) + " ms)");

        List<String> lines = Files.readAllLines(f);
        List<String> evs = new ArrayList<String>();
        for(String l : lines) {
            int a = l.indexOf("\"ev\":\"") + 6;
            evs.add(l.substring(a, l.indexOf('"', a)));
        }
        List<String> want = new ArrayList<String>();
        for(int i = 0; i < cards; i++) {
            if(predicts)
                want.add("predict");
            want.addAll(Arrays.asList("advice", "marker"));
        }
        check(evs.equals(want), "every card writes " + (predicts ? "predict, then " : "")
              + "advice, then what came after it: " + evs);
        if(System.getProperty("dump") != null)
            Files.write(Paths.get(System.getProperty("dump")), lines);
        String adv = null;
        for(String l : lines)
            if(l.contains("\"ev\":\"advice\"")) {
                adv = l;
                break;
            }
        check((adv != null) && adv.contains("\"move\":\"" + direct.moveRes + "\""),
              "the advice row names the card the search picks (" + direct.moveRes + ")");
        check(w.dropped() == 0, "nothing dropped (" + w.dropped() + ")");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
