package haven.automated.survey;

import haven.Area;
import haven.Coord;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.SharedFile;
import haven.automated.nbots.world.WorkClaims;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The one plan a crew shares, and who has taken which survey.
 *
 * A crew runs several clients out of one install, so the plan cannot live in a field: each
 * character has to see the same list and see what the others have already started. It goes through
 * {@link SharedFile} for the same reason {@code botplaces.json} does - the file is locked and
 * replaced atomically rather than written in place, so two clients saving at once cannot leave a
 * half-written document behind.
 *
 * <p>Claims go through {@link WorkClaims} and are exclusive per survey. That is correctness, not
 * tidiness: {@code NSurveyBot} records that two characters manning ONE survey do not halve the work
 * but corrupt it, each draining soil the other is still counting. Different surveys at the same
 * time is the intended case and the whole point of planning sixteen of them.
 *
 * <p>{@link #toJson} and {@link #fromJson} are separate from the file handling so the round trip
 * can be checked with no filesystem involved - see {@link SurveyPlannerCheck}.
 */
public class SurveyPlanStore {
    private static final String FILE = "surveyplan.json";

    /**
     * Claim keys are namespaced by index alone, not by coordinates.
     *
     * One plan is live at a time, so the index identifies a survey unambiguously; keying on the
     * rectangle would mean a replanned grid inherits stale claims from surveys that no longer
     * exist in the same shape.
     */
    private static String key(int index) {
        return "surveyplan-" + index;
    }

    // ------------------------------------------------------------------ serialisation

    public static String toJson(SurveyPlan plan) {
        JSONObject root = new JSONObject();
        root.put("ul", coord(plan.ul));
        root.put("targetZ", plan.targetZ);

        JSONArray surveys = new JSONArray();
        for (SurveyPlan.SurveySpec s : plan.surveys) {
            JSONObject o = new JSONObject();
            o.put("index", s.index);
            /* Area has no JSON form of its own, so it goes as its two corners and comes back
             * through Area.corn - the same constructor the planner built it with. */
            o.put("ul", coord(s.tiles.ul));
            o.put("br", coord(s.tiles.br));
            o.put("net", s.net);
            surveys.put(o);
        }
        root.put("surveys", surveys);

        JSONArray transfers = new JSONArray();
        for (SurveyPlan.Transfer t : plan.transfers) {
            JSONObject o = new JSONObject();
            o.put("from", t.from);
            o.put("to", t.to);
            o.put("amount", t.amount);
            o.put("stockpile", coord(t.stockpile));
            transfers.put(o);
        }
        root.put("transfers", transfers);
        return root.toString(2);
    }

    public static SurveyPlan fromJson(String json) {
        JSONObject root = new JSONObject(json);
        List<SurveyPlan.SurveySpec> surveys = new ArrayList<>();
        JSONArray sa = root.getJSONArray("surveys");
        for (int i = 0; i < sa.length(); i++) {
            JSONObject o = sa.getJSONObject(i);
            surveys.add(new SurveyPlan.SurveySpec(o.getInt("index"),
                Area.corn(coord(o.getJSONObject("ul")), coord(o.getJSONObject("br"))),
                o.getDouble("net")));
        }
        List<SurveyPlan.Transfer> transfers = new ArrayList<>();
        JSONArray ta = root.getJSONArray("transfers");
        for (int i = 0; i < ta.length(); i++) {
            JSONObject o = ta.getJSONObject(i);
            transfers.add(new SurveyPlan.Transfer(o.getInt("from"), o.getInt("to"),
                o.getDouble("amount"), coord(o.getJSONObject("stockpile"))));
        }
        return new SurveyPlan(coord(root.getJSONObject("ul")), root.getDouble("targetZ"),
            surveys, transfers);
    }

    private static JSONObject coord(Coord c) {
        return new JSONObject().put("x", c.x).put("y", c.y);
    }

    private static Coord coord(JSONObject o) {
        return Coord.of(o.getInt("x"), o.getInt("y"));
    }

    // ------------------------------------------------------------------ the shared file

    private static Path file() {
        return Paths.get(System.getProperty("novocaine.surveyplanfile", FILE));
    }

    public static void save(SurveyPlan plan) {
        try {
            SharedFile.writeAtomic(file(), toJson(plan).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            NLog.crash("saving " + FILE, e);
        }
    }

    /** The plan on disk, or null when there is none or it will not parse. */
    public static SurveyPlan load() {
        Path p = file();
        if (!Files.exists(p))
            return null;
        try {
            return fromJson(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            /* A plan is cheap to recompute and the terrain has not moved, so a corrupt file is
             * better reported and ignored than allowed to stop the window opening. */
            NLog.crash("loading " + FILE, e);
            return null;
        }
    }

    // ------------------------------------------------------------------ claims

    /** Takes a survey for this client. False when another character already holds it. */
    public static boolean claim(int index) {
        return WorkClaims.claim(key(index));
    }

    /** Extends a claim this client holds; claims lapse after {@link WorkClaims#TTL_MS}. */
    public static void renew(int index) {
        WorkClaims.renew(key(index));
    }

    public static void release(int index) {
        WorkClaims.release(key(index));
    }

    /** Whether another character currently holds this survey. */
    public static boolean taken(int index) {
        return WorkClaims.taken(key(index));
    }
}
