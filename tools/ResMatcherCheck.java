/*
 * ResMatcher answers exactly what Arrays.stream(list).anyMatch(name::matches) answered.
 *
 * Gob's object highlights (critter auras, container fullness, workstation progress) used that stream,
 * compiling every pattern of the list again on every call; ResMatcher compiles the list once as one
 * alternation and remembers each name's answer (2026-09-26). This holds the two against each other for
 * every list it replaced, over names built from the lists themselves (each entry, with things before
 * and after it, cut short) and, when given a file of real resource names one per line, over those too.
 *
 * NOT part of the client build. Run from the repo root (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\rmcheck tools\ResMatcherCheck.java
 *   java -cp "$env:TEMP\rmcheck;$CP" haven.ResMatcherCheck [names.txt]
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven;

import java.nio.file.*;
import java.util.*;

public class ResMatcherCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    static void compare(String what, String[] list, ResMatcher rm, Collection<String> names) {
        int n = 0, bad = 0, yes = 0;
        String first = null;
        for(String name : names) {
            boolean old = Arrays.stream(list).anyMatch(name::matches);
            boolean now = rm.matches(name);
            boolean again = rm.matches(name);
            n++;
            if(old) yes++;
            if((old != now) || (now != again)) {
                bad++;
                if(first == null)
                    first = name + " (old " + old + ", new " + now + ")";
            }
        }
        check(bad == 0, what + ": " + n + " names, " + yes + " match, " + bad + " disagree" + ((first == null) ? "" : ", e.g. " + first));
    }

    public static void main(String[] args) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        for(String[] list : new String[][] {Config.critterResPaths, Config.containersResPaths, Config.workstationsResPaths}) {
            for(String p : list) {
                names.add(p);
                names.add(p + "-x");
                names.add("x/" + p);
                names.add(p.substring(0, Math.max(1, p.length() - 1)));
                names.add(p.replace("gfx/", "gfx/x/"));
            }
        }
        names.addAll(Arrays.asList("gfx/kritter/rabbit/rabbit", "gfx/kritter/bunny", "gfx/kritter/woodscorpion/woodscorpion",
                                   "gfx/kritter/rabbitx", "", "gfx/borka/body"));
        if(args.length > 0)
            names.addAll(Files.readAllLines(Paths.get(args[0])));
        System.out.println(names.size() + " names" + ((args.length > 0) ? " (with the real resource names)" : ""));
        compare("critters", Config.critterResPaths, Config.critterRes, names);
        compare("containers", Config.containersResPaths, Config.containersRes, names);
        compare("workstations", Config.workstationsResPaths, Config.workstationsRes, names);
        compare("rabbits", new String[] {".*(rabbit|bunny)$"}, Config.rabbitRes, names);
        compare("wood scorpions", new String[] {".*(woodscorpion)$"}, Config.woodscorpionRes, names);
        check(!new ResMatcher().matches("anything"), "an empty list matches nothing");
        check(!Config.critterRes.matches(null), "a null name matches nothing");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
