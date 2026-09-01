package haven.automated.combat;

import haven.CharWnd;
import haven.Client;
import haven.Config;
import haven.FightWnd;
import haven.GameUI;
import haven.ItemInfo;
import haven.OptWnd;
import haven.Resource;
import haven.combat.log.JsonObj;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Writes what the Martial Arts and Combat Schools sheet knows about every move the character
 * has learned.
 *
 * The wiki-derived data pack in data/combat is a transcription of a third-party page. This is
 * the server's own numbers for this character: the base cooldown before any matchup modifier,
 * the initiative cost, the opening a move inflicts, the damage and grievous fractions, and -
 * the part no external source has at all - how many levels of each move this character has
 * bought, and how many of them are in the deck right now. Without the levels, mu is not
 * recoverable, and mu is a factor in every attack weight.
 *
 * Best-effort and silent. It runs beside the telemetry logger, under the same toggle, and a
 * failure here must never cost a fight recording.
 */
public final class CombatDeckDump {
    /* The last payload written, so switching decks between fights writes a second file but
     * fighting five times with one deck does not write five identical ones. */
    private static volatile String last = null;

    private CombatDeckDump() {}

    /* The sheet ticks many times a second; rebuilding the payload on each one to compare it
     * would be wasteful, so probes are spaced out. Fight-start dumps are not throttled. */
    private static final long PROBE_MS = 3000;
    private static volatile long lastProbe = 0;

    /**
     * Called from the sheet's own tick. The sheet lists every move the character has learned,
     * including ones not in the current deck and ones never taken into a fight, so probing here
     * captures the full set rather than whatever happened to be equipped when a fight started.
     */
    public static void probe(FightWnd fw) {
        long now = System.currentTimeMillis();
        if((now - lastProbe) < PROBE_MS)
            return;
        lastProbe = now;
        write(fw);
    }

    public static void dump(GameUI gui) {
        try {
            if(gui == null)
                return;
            CharWnd cw = gui.chrwdg;
            if(cw == null)
                return;
            write(cw.fight);
        } catch(Exception e) {
        }
    }

    private static void write(FightWnd fw) {
        if(!OptWnd.combatTelemetryCheckBox.a)
            return;
        try {
            if((fw == null) || (Client.gameDir == null))
                return;
            String json = build(fw);
            if((json == null) || json.equals(last))
                return;
            String safe = (Config.playername == null ? "unknown"
                           : Config.playername.replaceAll("[^A-Za-z0-9_-]", "_"));
            Path dir = Paths.get(Client.gameDir, "CombatLogs");
            Files.createDirectories(dir);
            Path p = dir.resolve("deck-" + safe + "-" + System.currentTimeMillis() + ".json");
            Files.write(p, json.getBytes(StandardCharsets.UTF_8));
            last = json;
        } catch(Exception e) {
            /* never disturb the client, and never cost the caller its fight log */
        }
    }

    private static String build(FightWnd fw) {
        StringBuilder acts = new StringBuilder("[");
        boolean first = true;
        /* A copy: ALL is mutated from the message loop as the server sends actions. */
        List<FightWnd.Action> all = new ArrayList<FightWnd.Action>(fw.ALL);
        for(FightWnd.Action a : all) {
            String one = action(a);
            if(one == null)
                continue;
            if(!first)
                acts.append(',');
            first = false;
            acts.append(one);
        }
        acts.append(']');
        return(new JsonObj()
               .put("kind", "combat-deck")
               .put("schema", 1)
               .put("wall", System.currentTimeMillis())
               .put("char", Config.playername)
               /* The deck's own limits, which bound every optimiser search: how many points
                * may be spent, and how many saved decks exist. */
               .put("maxpoints", fw.maxact)
               .put("nsave", fw.nsave)
               .put("usesave", fw.usesave)
               .raw("moves", acts.toString())
               .end());
    }

    private static String action(FightWnd.Action a) {
        try {
            Resource res = a.res.get();
            Resource.Tooltip tt = res.layer(Resource.tooltip);
            return(new JsonObj()
                   .put("res", res.name)
                   .put("name", (tt == null) ? null : tt.t)
                   /* `a` is the highest level bought, `u` the level in the current deck.
                    * Both are needed: the first bounds what a deck could contain, the
                    * second is what this character actually fought with. */
                   .put("maxlevel", a.a)
                   .put("decklevel", a.u)
                   .raw("info", info(a))
                   .end());
        } catch(Exception e) {
            /* a still-loading action costs that action, not the dump */
            return(null);
        }
    }

    /**
     * Every ItemInfo the server attached to the move, by class name and public fields.
     *
     * Reflection rather than a switch over known types on purpose: the interesting classes
     * (Damage, Coolmod, Armpen and whatever else a move carries) are generated from resources
     * and change with the game, and a switch would silently drop the ones it had not been
     * taught. This records whatever is there and leaves interpretation to the offline tools.
     */
    private static String info(FightWnd.Action a) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        List<ItemInfo> infos;
        try {
            infos = a.info();
        } catch(Exception e) {
            return("[]");
        }
        for(ItemInfo inf : infos) {
            if(inf == null)
                continue;
            JsonObj o = new JsonObj().put("class", inf.getClass().getSimpleName());
            List<Field> fields = Arrays.asList(inf.getClass().getFields());
            List<String> names = new ArrayList<String>();
            for(Field f : fields)
                names.add(f.getName());
            Collections.sort(names);
            for(String nm : names) {
                for(Field f : fields) {
                    if(!f.getName().equals(nm))
                        continue;
                    if(Modifier.isStatic(f.getModifiers()))
                        continue;
                    try {
                        put(o, nm, f.get(inf));
                    } catch(Exception e) {
                        /* an unreadable field is skipped, not fatal */
                    }
                    break;
                }
            }
            if(!first)
                out.append(',');
            first = false;
            out.append(o.end());
        }
        out.append(']');
        return(out.toString());
    }

    /* Only values that survive a round trip through JSON without inventing structure.
     * An Owner or a Resource reference is skipped rather than stringified into something
     * that looks like data. */
    private static void put(JsonObj o, String name, Object v) {
        if(v == null)
            o.put(name, (String)null);
        else if(v instanceof Boolean)
            o.put(name, ((Boolean)v).booleanValue());
        else if((v instanceof Double) || (v instanceof Float))
            o.put(name, ((Number)v).doubleValue());
        else if(v instanceof Number)
            o.put(name, ((Number)v).longValue());
        else if(v instanceof String)
            o.put(name, (String)v);
    }
}
