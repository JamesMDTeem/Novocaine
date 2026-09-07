package haven.automated.survey;

import haven.Area;
import haven.Coord;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.SharedFile;
import haven.automated.nbots.world.WorkClaims;
import haven.automated.nbots.world.WorldAnchor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        root.put("region", new JSONObject()
            .put("ul", coord(plan.region.ul))
            .put("br", coord(plan.region.br)));
        root.put("targetZ", plan.targetZ);
        /* The anchor goes as two separate strings, the way botplaces.json keeps them, rather than
         * through WorldAnchor.store(): the combined form is six fields and older parsers reject it
         * outright, which would turn "this client is behind" into "this client silently has no
         * plan". Split, the worst an old build does is ignore keys it does not know. */
        if (plan.anchor != null) {
            root.put("anchorSeg", plan.anchor.segpart());
            root.put("anchorGrid", plan.anchor.gridpart());
            root.put("anchorOff", coord(plan.anchorOff));
        }

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
        WorldAnchor anchor = root.has("anchorSeg")
            ? WorldAnchor.parse(root.getString("anchorSeg"), root.optString("anchorGrid", ""))
            : null;
        Coord off = root.has("anchorOff") ? coord(root.getJSONObject("anchorOff")) : Coord.z;
        return new SurveyPlan(region(root, surveys), root.getDouble("targetZ"),
            surveys, transfers, anchor, off);
    }

    /**
     * The plan's region, reconstructed for a document written before plans carried one.
     *
     * Older files recorded only the north-west corner, because a plan was always exactly one grid
     * and the size was implied. The surveys tile the region exactly, so their bounding box is the
     * region - no guessing, and no need to make anybody replan to get their highlights.
     */
    private static Area region(JSONObject root, List<SurveyPlan.SurveySpec> surveys) {
        if (root.has("region")) {
            JSONObject r = root.getJSONObject("region");
            return Area.corn(coord(r.getJSONObject("ul")), coord(r.getJSONObject("br")));
        }
        if (surveys.isEmpty())
            return Area.sized(coord(root.getJSONObject("ul")), Coord.of(1, 1));
        Area a = surveys.get(0).tiles;
        for (SurveyPlan.SurveySpec s : surveys)
            a = Area.corn(a.ul.min(s.tiles.ul), a.br.max(s.tiles.br));
        return a;
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
        /* A new plan has new rectangles, so nothing carried over from the old one is true of it.
         * The stamp would hide the stale marks anyway; writing them away keeps the file honest
         * about what a crew is looking at. */
        writeDone(plan, new HashSet<>());
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

    // ------------------------------------------------------------------ finished surveys

    /**
     * Which surveys are FINISHED, which is a different question from who is working one.
     *
     * A claim answers "is somebody on this right now" and lapses on its own inside half a minute,
     * which is exactly right for coordination and exactly wrong for progress: a survey levelled
     * yesterday is still levelled today. So done-marks are their own file, permanent, and
     * explicitly set by whoever finished the work - nothing here infers completion from the
     * terrain, because a survey can read as flat while its stockpile is still sitting in it.
     *
     * <p>It lives beside the plan rather than inside it so that marking one finished never
     * rewrites the plan document. A plan is expensive to compute and cheap to lose; a done-mark is
     * the opposite.
     */
    private static final String DONE_FILE = "surveydone.json";

    /** How long a read of the done file stands before it is fetched again. */
    private static final long DONE_TTL_MS = 1000;

    private static Set<Integer> doneCache = new HashSet<>();
    private static String doneStamp = "";
    private static long doneRead = 0;

    private static Path doneFile() {
        return Paths.get(System.getProperty("novocaine.surveydonefile", DONE_FILE));
    }

    /**
     * What identifies the plan a set of done-marks belongs to.
     *
     * Claims get away with keying on the index alone because they expire; a done-mark does not,
     * and inheriting one across a replan would tell a crew that work nobody has done is finished -
     * the one failure that makes a progress display worse than having none. Any replan that moves
     * a rectangle or changes how many there are produces a different stamp, and the marks start
     * empty again.
     *
     * <p>Deliberately free of absolute coordinates. Those are relative to the session's floating
     * map origin, so a stamp built from them changes on every relog and takes a crew's whole
     * progress record with it - the plan would come back and report nothing done. What goes in
     * instead is the shape: the region's size, the survey count, and each rectangle's offset from
     * the region's own corner, none of which a rebase touches. The anchor's grid half joins them
     * so that two regions of identical shape in different places are still told apart; the segment
     * half is left out on purpose, because segment ids are private to the client that invented
     * them and would break the stamp across a crew.
     */
    private static String stamp(SurveyPlan plan) {
        Coord sz = plan.region.sz();
        int h = 0;
        for (SurveyPlan.SurveySpec s : plan.surveys) {
            Coord r = s.tiles.ul.sub(plan.region.ul);
            Coord rs = s.tiles.sz();
            h = (((h * 31 + r.x) * 31 + r.y) * 31 + rs.x) * 31 + rs.y;
        }
        String a = (plan.anchor == null) ? "" : plan.anchor.gridpart();
        return a + "@" + plan.anchorOff.x + "," + plan.anchorOff.y
             + "/" + sz.x + "x" + sz.y + "/" + plan.surveys.size()
             + "/" + Integer.toHexString(h);
    }

    /** The indices listed in a done document. The stamp is the caller's to check. */
    private static Set<Integer> parseDone(JSONObject root) {
        Set<Integer> out = new HashSet<>();
        JSONArray a = root.optJSONArray("done");
        for (int i = 0; (a != null) && (i < a.length()); i++)
            out.add(a.getInt(i));
        return out;
    }

    /** The done document as it stands on disk, or null when there is none or it will not parse. */
    private static JSONObject readDone(Path p) {
        if (!Files.exists(p))
            return null;
        try {
            return new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // A corrupt done file must not stop the window: nothing reads as marked, and the next
            // mark rewrites the file wholesale.
            NLog.crash("loading " + DONE_FILE, e);
            return null;
        }
    }

    /**
     * The surveys of this plan somebody has marked finished. Never null.
     *
     * Cached for a second, because the overlay asks twice a second and every caller wants the same
     * answer. A crewmate's mark therefore shows up within a second rather than instantly, which is
     * far inside the time it takes anyone to notice.
     */
    public static synchronized Set<Integer> done(SurveyPlan plan) {
        if (plan == null)
            return new HashSet<>();
        long now = System.currentTimeMillis();
        if (now - doneRead >= DONE_TTL_MS) {
            doneRead = now;
            JSONObject root = readDone(doneFile());
            doneCache = (root == null) ? new HashSet<>() : parseDone(root);
            doneStamp = (root == null) ? "" : root.optString("stamp", "");
        }
        if (!stamp(plan).equals(doneStamp))
            return new HashSet<>();
        return new HashSet<>(doneCache);
    }

    /** Marks a survey finished, or takes the mark off again. Shared, so a crew sees it too. */
    public static void setDone(SurveyPlan plan, int index, boolean done) {
        if (plan == null)
            return;
        Path p = doneFile();
        try (SharedFile.Held held = SharedFile.lock(p)) {
            /* Failing to get the lock means a crewmate is mid-write. Abandoning the mark is right
             * - forcing it would drop whatever they were recording - and the button can simply be
             * pressed again. */
            if (held == null)
                return;
            String want = stamp(plan);
            /* Re-read INSIDE the lock. This is a read-modify-write across processes, and skipping
             * the re-read is exactly how a crewmate's marks get dropped by the next person to
             * press the button. */
            JSONObject root = readDone(p);
            Set<Integer> cur = ((root != null) && want.equals(root.optString("stamp", "")))
                ? parseDone(root) : new HashSet<>();
            if (done)
                cur.add(index);
            else
                cur.remove(index);
            publish(p, want, cur);
        } catch (IOException | RuntimeException e) {
            NLog.crash("saving " + DONE_FILE, e);
        }
    }

    /** Replaces the whole done set, for a plan that has just been recomputed. */
    private static void writeDone(SurveyPlan plan, Set<Integer> set) {
        if (plan == null)
            return;
        Path p = doneFile();
        try (SharedFile.Held held = SharedFile.lock(p)) {
            if (held == null)
                return;
            publish(p, stamp(plan), set);
        } catch (IOException | RuntimeException e) {
            NLog.crash("saving " + DONE_FILE, e);
        }
    }

    /** Writes the done document and brings the cache in line with it. Call under the lock. */
    private static void publish(Path p, String stamp, Set<Integer> set) throws IOException {
        /* Built element by element, and sorted. This tree's org.json has no Collection
         * constructor, so `new JSONArray(set)` binds to the single-Object one and throws at
         * runtime rather than failing to compile. Sorting is free here and makes the file
         * diffable, which matters for something several clients rewrite. */
        JSONArray a = new JSONArray();
        for (int i : new java.util.TreeSet<>(set))
            a.put(i);
        JSONObject root = new JSONObject();
        root.put("stamp", stamp);
        root.put("done", a);
        SharedFile.writeAtomic(p, root.toString(2).getBytes(StandardCharsets.UTF_8));
        synchronized (SurveyPlanStore.class) {
            doneCache = new HashSet<>(set);
            doneStamp = stamp;
            doneRead = System.currentTimeMillis();
        }
    }
}
