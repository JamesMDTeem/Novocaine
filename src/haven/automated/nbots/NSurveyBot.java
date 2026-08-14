package haven.automated.nbots;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Gob;
import haven.Label;
import haven.Loading;
import haven.Resource;
import haven.UI;
import haven.Widget;
import haven.Window;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.task.TakeWorkSlot;
import haven.automated.nbots.task.TravelTo;
import haven.automated.nbots.task.Upkeep;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static haven.OCache.posres;

/**
 * Manning land surveys: dig each one out, remove it when it is drained, move to the next.
 *
 * A survey object is worked by opening its "Land survey" window (a right-click) and clicking Dig
 * until the "Units of soil left" label stops moving. Two things about that loop are worth spelling
 * out, because they are the two ways the stock approach goes wrong:
 *
 * <p>The window is not a flower menu. {@link WorkGob} is built around right-clicking an object and
 * choosing a petal from the menu that opens; a survey skips that step and opens a full window
 * instead, with the action behind a Button inside it. So this bot drives {@link #button} and
 * {@link #soil} directly rather than handing the gob to a shared task. The buttons are the vanilla
 * {@code haven.Button}s the server's window resource ships, and their {@code click()} runs whatever
 * the resource wired up - exactly what nurgling's survey supporter does, and the reason the Dig
 * click needs no client-side message of its own.
 *
 * <p>Depletion is detected by the label refusing to change. Each successful Dig reduces "Units of
 * soil left", so the label read before a Dig and the one read after it differ; when they do not,
 * the survey is drained and the window offers a Remove button. The read is a poll rather than a
 * single comparison, because a Dig that is still animating has not updated the label yet and a
 * one-shot compare would declare a healthy survey dead in the middle of its own swing.
 *
 * <p>The other requirement is the worksite claim. A survey is one character's job at a time, and
 * without a reservation two clients finish one and race to the next, both digging it, both reading
 * a moving label, both concluding the other did nothing - the break the stock bot is famous for.
 * {@link TakeWorkSlot} is the shared fix: it claims a spot around the gob through the cross-client
 * claim file, skips a survey somebody else is visibly standing on, and the claim is renewed for as
 * long as the bot stays on it. Draining water and food are the ordinary shift upkeep.
 */
public class NSurveyBot extends NBot {
    private static final String LOG = "nbot-survey.log";

    /** The survey window, by title. */
    private static final String SURVEY_WINDOW = "Land survey";

    /** The label whose value tells us how much digging is left. */
    private static final String SOIL_LEFT = "Units of soil left";

    /** A fallback for windows that phrase it differently; tracked the same way. */
    private static final String SOIL_REQ = "Units of soil req";

    /** How long (in 25ms ticks) to wait for a Dig to show up in the label before calling it drained. */
    private static final int DIG_TICKS = 120;
    /** How long to wait for the survey to vanish after Remove before giving up on it. */
    private static final int REMOVE_TICKS = 120;

    /** Surveys given up on this shift, so one broken object cannot stall the whole run. */
    private final Set<Long> retired = new HashSet<>();

    /** The work area this shift is confined to, or null for "nearest survey anywhere in sight". */
    private Place area;

    public NSurveyBot(GameUI gui) {
        super(gui, "NSurveyBot", "Survey (crew)", LOG, UI.scale(300, 160));
        settings.places("area", "Survey area", PlaceRoles.WORK);
        settings.layout(this, UI.scale(10, 22), 1, UI.scale(150));
        pack();
    }

    @Override
    protected String title() {
        return "Survey";
    }

    @Override
    protected Outcome work() throws InterruptedException {
        Gob me = ctx.player();
        if (me == null)
            return Outcome.blocked("no character");

        retired.clear();
        String pinned = settings.place("area");
        area = (pinned == null || pinned.isEmpty()) ? null : Places.byName(pinned);
        if (area != null) {
            Outcome there = new TravelTo(area).run(ctx);
            if (!there.isOk())
                return there;
        }

        int done = 0;
        int attempts = 0;
        while (running() && attempts < 1000) {
            attempts++;
            Gob survey = findSurvey();
            if (survey == null)
                break;   // nothing left in reach; the shift is over

            if (!upkeep())
                return Outcome.failed(fatalStop);

            TakeWorkSlot slot = new TakeWorkSlot(survey);
            if (!slot.run(ctx).isOk()) {
                /* In sight but every spot is claimed or stood on. Go round: the emptiness test at
                 * the top owns finishing, and it has looked. */
                slot.release();
                continue;
            }

            setStatus("Manning a survey (" + done + " drained)");
            try {
                Outcome o = man(survey, slot);
                if (o.isOk())
                    done++;
                else if (o.isFailed())
                    retired.add(survey.id);
            } finally {
                slot.release();
            }
        }

        report("finished after draining " + done + " survey(s).");
        setStatus("Done: " + done + " drained.");
        return Outcome.ok();
    }

    /** The nearest survey gob not yet given up on, confined to {@link #area} when one is set. */
    private Gob findSurvey() {
        Gob me = ctx.player();
        List<Gob> found = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (retired.contains(g.id))
                    continue;
                String name = resname(g);
                if (name == null || !name.contains("survobj"))
                    continue;
                if (area != null && !area.contains(gui, g.rc))
                    continue;
                found.add(g);
            }
        }
        if (found.isEmpty())
            return null;
        if (me != null)
            found.sort((a, b) -> Double.compare(me.rc.dist(a.rc), me.rc.dist(b.rc)));
        return found.get(0);
    }

    /** Works one survey through its window until it is drained and removed. */
    private Outcome man(Gob survey, TakeWorkSlot slot) throws InterruptedException {
        Window wnd = open(survey);
        if (wnd == null)
            return Outcome.blocked("couldn't open the survey window");

        try {
            while (running() && ctx.gob(survey.id) != null) {
                slot.renew();

                if (ctx.stamina() < Upkeep.DRINK_BELOW || ctx.energy() < Upkeep.EAT_BELOW) {
                    wnd.wdgmsg("close");
                    if (!upkeep())
                        return Outcome.failed(fatalStop);
                    wnd = open(survey);
                    if (wnd == null)
                        return Outcome.blocked("survey window lost after upkeep");
                    continue;
                }

                String before = awaitSoil();
                if (before == null)
                    return Outcome.blocked("couldn't read the survey's soil state");

                Button dig = button("Dig");
                if (dig == null)
                    return Outcome.failed("survey window has no Dig button");
                dig.click();

                if (awaitSoilChange(before) != null)
                    continue;   // the dig registered; keep going

                Button remove = button("Remove");
                if (remove == null)
                    return Outcome.failed("dig isn't progressing and the window has no Remove button");

                remove.click();
                ctx.nav.waitUntil(() -> ctx.gob(survey.id) == null, REMOVE_TICKS);
                return Outcome.ok();   // drained and removed
            }
            return (ctx.gob(survey.id) == null) ? Outcome.ok() : Outcome.blocked("stopped");
        } finally {
            Window cur = gui.getwnd(SURVEY_WINDOW);
            if (cur != null)
                cur.wdgmsg("close");
        }
    }

    /** Right-clicks the survey and waits for its window to appear. */
    private Window open(Gob survey) throws InterruptedException {
        Window stale = gui.getwnd(SURVEY_WINDOW);
        if (stale != null)
            stale.wdgmsg("close");
        ctx.gui.map.wdgmsg("click", Coord.z, survey.rc.floor(posres), 3, 0, 0, (int) survey.id,
            survey.rc.floor(posres), 0, -1);
        return awaitWindow();
    }

    private Window awaitWindow() throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            if (!running())
                throw new InterruptedException();
            Window w = gui.getwnd(SURVEY_WINDOW);
            if (w != null)
                return w;
            ctx.nav.pause(1);
        }
        return null;
    }

    /** Polls for the soil label to become readable (non-"..."). */
    private String awaitSoil() throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            if (!running())
                throw new InterruptedException();
            Window w = gui.getwnd(SURVEY_WINDOW);
            String s = (w == null) ? null : soil(w);
            if (s != null)
                return s;
            ctx.nav.pause(1);
        }
        return null;
    }

    /** Polls for the soil label to move away from {@code before}; null means it never did. */
    private String awaitSoilChange(String before) throws InterruptedException {
        for (int i = 0; i < DIG_TICKS; i++) {
            if (!running())
                throw new InterruptedException();
            Window w = gui.getwnd(SURVEY_WINDOW);
            String now = (w == null) ? null : soil(w);
            if (now != null && !now.equals(before))
                return now;
            ctx.nav.pause(1);
        }
        return null;
    }

    /** The current "Units of soil" label text, or null while it is absent or still loading. */
    private String soil(Window wnd) {
        Label left = label(wnd, SOIL_LEFT);
        if (left != null)
            return clean(left.texts);
        Label req = label(wnd, SOIL_REQ);
        return (req == null) ? null : clean(req.texts);
    }

    private static String clean(String text) {
        return (text == null || text.equals("...")) ? null : text;
    }

    // ------------------------------------------------------------------ widget lookup

    private Button button(String text) {
        Window wnd = gui.getwnd(SURVEY_WINDOW);
        if (wnd == null)
            return null;
        return findButton(wnd, text);
    }

    private static Button findButton(Widget root, String text) {
        for (Widget w = root.child; w != null; w = w.next) {
            if (w instanceof Button) {
                Button b = (Button) w;
                if (b.text != null && text.equals(b.text.text))
                    return b;
            }
            Button deep = findButton(w, text);
            if (deep != null)
                return deep;
        }
        return null;
    }

    private static Label label(Widget root, String substr) {
        for (Widget w = root.child; w != null; w = w.next) {
            if (w instanceof Label) {
                Label l = (Label) w;
                if (l.texts != null && l.texts.contains(substr))
                    return l;
            }
            Label deep = label(w, substr);
            if (deep != null)
                return deep;
        }
        return null;
    }

    private static String resname(Gob gob) {
        try {
            Resource res = gob.getres();
            return (res == null) ? null : res.name;
        } catch (Loading l) {
            return null;
        }
    }
}
