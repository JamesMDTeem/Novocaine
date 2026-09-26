/*
 * Server-sent group numbers past the eight kin colours must not crash the client.
 *
 * A friend crashed on 2026-09-26 in ui/obj/buddy-n's Named.parse: ArrayIndexOutOfBounds, index 11
 * for length 8. Village and realm groups run 0-254 (see BuddyWnd.gcol, 2026-09-23), and the
 * resource classes that colour a name or a map icon by group indexed BuddyWnd.gc directly. This
 * feeds the real parsers and icon code a group past the palette, and a group inside it, and checks
 * each answers with gcol's colour instead of throwing.
 *
 * NOT part of the client build. BuddyWnd loads window art and a toolkit when it initialises, so
 * this needs the game's resource jars and a desktop session (not headless). Run from the repo
 * root (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;bin\*;lib\*;lib\ext\jogl\*;lib\ext\lwjgl\*;lib\ext\steamworks\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\kincheck tools\KinGroupCheck.java
 *   java -cp "$env:TEMP\kincheck;$CP" haven.KinGroupCheck
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven;

import java.awt.Color;

public class KinGroupCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    /* The objdelta a server sends for a named gob: the name, the group, the flags. */
    static Message named(String nm, int group, int fl) {
        MessageBuf m = new MessageBuf();
        m.addstring(nm);
        m.adduint8(group);
        m.adduint8(fl);
        return(new MessageBuf(m.fin()));
    }

    static Color namedColour(int group) throws Exception {
        Gob gob = new Gob(null, Coord2d.z);
        haven.res.ui.obj.buddy_n.Named.parse(gob, named("Somebody", group, 1));
        haven.res.ui.obj.buddy_n.Named n = gob.getattr(haven.res.ui.obj.buddy_n.Named.class);
        return((n == null) ? null : n.col);
    }

    public static void main(String[] args) throws Exception {
        for(int group : new int[] {3, 7, 8, 11, 254}) {
            Color want = BuddyWnd.gcol(group);
            Color got;
            try {
                got = namedColour(group);
            } catch(Throwable t) {
                check(false, "a named gob in group " + group + " parses (threw " + t + ")");
                continue;
            }
            check(want.equals(got), "a named gob in group " + group + " is coloured " + want + " (" + got + ")");
        }
        check(BuddyWnd.gcol(3).equals(BuddyWnd.gc[3]), "groups inside the palette keep their own colour");
        check(BuddyWnd.gcol(11).equals(BuddyWnd.extcol), "groups past it take the neutral colour");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
