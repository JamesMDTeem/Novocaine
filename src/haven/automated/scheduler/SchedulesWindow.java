package haven.automated.scheduler;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.Label;
import haven.OldDropBox;
import haven.Scrollport;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.nbots.BotDef;
import haven.automated.nbots.BotRegistry;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defining schedules, and starting and stopping them.
 *
 * A schedule is a list of steps - run this bot for that many minutes, wait that many
 * minutes - which the player starts by hand. Nothing runs on login; a schedule is
 * something you click to start, because a bot that starts itself is a bot that starts at
 * the worst possible moment.
 *
 * Runners are deliberately NOT stopped when this window closes: the point of a schedule
 * is unattended operation, and closing the editor is not an instruction to stop the work.
 * The Run/Stop buttons in the list are the only controls, and they are the same buttons
 * whether the window is freshly opened or has been open for an hour.
 *
 * The two-pane shape, the measured layout, the one-frame rebuild delay and the status
 * recount all follow {@link haven.automated.nbots.PlacesWindow}, whose javadoc explains
 * why each of them exists.
 */
public class SchedulesWindow extends Window {
    private static final Coord WSZ = UI.scale(700, 520);
    private static final int LISTW = UI.scale(200);
    /** Where the selected schedule is remembered between sessions. */
    private static final String SELPREF = "nbotSchedulesSelected";

    private final GameUI gui;
    private final Scrollport list;
    private final Widget detail;
    private final Label hint;
    private final TextEntry newName;

    /** The schedule whose editor is on the right, by name. */
    private String selected;
    /** Set by a button that wants the panels rebuilt; acted on at the start of the next tick. */
    private boolean rebuild = false;

    /** Live runners by schedule name. */
    private final Map<String, ScheduleRunner> runners = new HashMap<>();
    private final Map<String, Thread> threads = new HashMap<>();

    /** The running-status line, kept so the tick can rewrite it in place. */
    private Label status;

    /** Counts down to the next status refresh, in seconds. */
    private double recount = 0;

    /** The bot last picked in the add-step dropbox, by title. */
    private String pickedBot;

    public SchedulesWindow(GameUI gui) {
        super(WSZ, "Schedules");
        this.gui = gui;

        int y = UI.scale(4);
        add(new Label("Run bots on a timed loop - e.g. dragonfly for ten minutes, wait fifteen, repeat."),
            new Coord(UI.scale(6), y));
        y += UI.scale(18);
        hint = add(new Label(""), new Coord(UI.scale(6), y));
        y += UI.scale(20);

        newName = add(new TextEntry(UI.scale(170), ""), new Coord(UI.scale(6), y));
        Widget addBtn = add(new Button(UI.scale(52), "New") {
            @Override
            public void click() {
                addPending();
            }
        }, new Coord(UI.scale(182), y));
        y = bottom(UI.scale(8), newName, addBtn);

        int paneh = Math.max(UI.scale(120), WSZ.y - y - UI.scale(10));
        list = add(new Scrollport(new Coord(LISTW, paneh)), new Coord(UI.scale(6), y));
        detail = add(new Widget(new Coord(WSZ.x - LISTW - UI.scale(24), paneh)),
            new Coord(LISTW + UI.scale(14), y));
        String last = Utils.getpref(SELPREF, "");
        selected = last.isEmpty() ? null : last;
        refresh();
        pack();
    }

    /** The lowest edge of a row of widgets, plus a gap. Layout by measurement, not by arithmetic. */
    private static int bottom(int gap, Widget... ws) {
        int b = 0;
        for (Widget w : ws) {
            if (w != null)
                b = Math.max(b, w.c.y + w.sz.y);
        }
        return b + gap;
    }

    // ------------------------------------------------------------------ defining

    private void addPending() {
        String name = newName.text().trim();
        if (name.isEmpty()) {
            gui.error("Give the schedule a name first.");
            return;
        }
        newName.settext("");
        if (Schedules.byName(name) != null) {
            selected = name;
            hint.settext("Selected existing schedule \"" + name + "\".");
            hint.setcolor(Color.WHITE);
        } else {
            Schedule s = new Schedule();
            s.name = name;
            Schedules.add(s);
            selected = name;
            hint.settext("Added \"" + name + "\". Add steps on the right, then press Run.");
            hint.setcolor(Color.GREEN);
        }
        rebuild = true;
    }

    private Schedule current() {
        return (selected == null) ? null : Schedules.byName(selected);
    }

    private boolean running(String name) {
        Thread t = threads.get(name);
        return t != null && t.isAlive();
    }

    private void run(String name) {
        Schedule s = Schedules.byName(name);
        if (s == null)
            return;
        if (running(name)) {
            gui.error("\"" + name + "\" is already running.");
            return;
        }
        if (s.steps.isEmpty()) {
            gui.error("\"" + name + "\" has no steps yet.");
            return;
        }
        ScheduleRunner runner = new ScheduleRunner(gui, s);
        Thread t = new Thread(runner, "Schedule-" + s.name);
        t.setDaemon(true);
        runners.put(s.name, runner);
        threads.put(s.name, t);
        t.start();
        hint.settext("Started \"" + s.name + "\".");
        hint.setcolor(Color.GREEN);
        rebuild = true;
    }

    private void stop(String name) {
        ScheduleRunner runner = runners.get(name);
        if (runner != null)
            runner.stop();
        // The runner closes the bot it opened on its way out; give it a moment.
        Thread t = threads.get(name);
        if (t != null) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        runners.remove(name);
        threads.remove(name);
        hint.settext("Stopped \"" + name + "\".");
        hint.setcolor(Color.WHITE);
        rebuild = true;
    }

    // ------------------------------------------------------------------ the list

    private static void clear(Widget parent) {
        for (Widget w = parent.child; w != null; ) {
            Widget next = w.next;
            w.destroy();
            w = next;
        }
    }

    private void refresh() {
        clear(list.cont);
        int y = 0;
        List<Schedule> schedules = Schedules.all();
        if (schedules.isEmpty()) {
            list.cont.add(new Label("No schedules yet."), new Coord(0, y));
        } else {
            if (current() == null)
                selected = schedules.get(0).name;
            for (Schedule s : schedules) {
                final String name = s.name;
                boolean sel = name.equals(selected);
                /* Measured off the scroll CONTENT, not the port, and the Run/Stop control
                 * is on the row itself: seeing several schedules at once is the point, and
                 * running one must not require selecting it first. */
                int roww = Math.max(UI.scale(40), list.cont.sz.x - UI.scale(22) - UI.scale(52));
                Button row = new Button(roww, running(name) ? name + " (running)" : name) {
                    @Override
                    public void click() {
                        selected = name;
                        rebuild = true;
                    }
                };
                if (sel)
                    row.change("> " + name, Color.YELLOW);
                list.cont.add(row, new Coord(0, y));
                list.cont.add(new Button(UI.scale(48), running(name) ? "Stop" : "Run") {
                    @Override
                    public void click() {
                        if (running(name))
                            stop(name);
                        else
                            run(name);
                    }
                }, new Coord(roww + UI.scale(4), y));
                y += UI.scale(22);
            }
        }
        detail();
    }

    // ------------------------------------------------------------------ the editor

    private void detail() {
        clear(detail);
        Schedule s = current();
        if (s == null) {
            detail.add(new Label("Give a schedule a name and press New."), Coord.z);
            return;
        }
        int cw = detail.sz.x;
        int y = 0;

        Label title = new Label(s.name);
        title.setcolor(Color.WHITE);
        detail.add(title, new Coord(0, y));
        status = detail.add(new Label(statusText(s)), new Coord(UI.scale(140), y));
        int delw = UI.scale(52);
        detail.add(new Button(delw, "Delete") {
            @Override
            public void click() {
                if (running(s.name))
                    stop(s.name);
                Schedules.remove(s.name);
                selected = null;
                rebuild = true;
            }
        }, new Coord(Math.max(0, cw - delw), y));
        y += UI.scale(24);

        detail.add(new CheckBox("Repeat until stopped") {
            {
                a = s.repeat;
            }

            public void set(boolean val) {
                s.repeat = val;
                Schedules.add(s);
                a = val;
            }
        }, new Coord(0, y));
        y += UI.scale(22);

        detail.add(new Label("Steps"), new Coord(0, y));
        y += UI.scale(16);

        if (s.steps.isEmpty()) {
            detail.add(new Label("(none yet - add one below)"), new Coord(UI.scale(12), y));
            y += UI.scale(18);
        }
        for (int i = 0; i < s.steps.size(); i++) {
            final int idx = i;
            detail.add(new Label((i + 1) + ". " + s.steps.get(i).label()), new Coord(UI.scale(12), y));
            detail.add(new Button(UI.scale(52), "Remove") {
                @Override
                public void click() {
                    s.steps.remove(idx);
                    Schedules.add(s);
                    rebuild = true;
                }
            }, new Coord(Math.max(0, cw - UI.scale(64)), y));
            y += UI.scale(20);
        }
        y += UI.scale(8);

        detail.add(new Label("Add bot step:"), new Coord(0, y + UI.scale(3)));
        detail.add(new OldDropBox<String>(UI.scale(200), 12, UI.scale(17)) {
            {
                super.change(pickedBot == null ? "(pick a bot)" : pickedBot);
            }

            protected String listitem(int i) {
                return windowedBots().get(i);
            }

            protected int listitems() {
                return windowedBots().size();
            }

            protected void drawitem(GOut g, String item, int i) {
                g.aimage(Text.renderstroked(item).tex(),
                    Coord.of(UI.scale(3), g.sz().y / 2), 0.0, 0.5);
            }

            public void change(String item) {
                pickedBot = item;
            }
        }, new Coord(UI.scale(92), y));
        final TextEntry botMinutes = detail.add(new TextEntry(UI.scale(48), "10"),
            new Coord(UI.scale(300), y));
        detail.add(new Button(UI.scale(40), "Add") {
            @Override
            public void click() {
                addBotStep(pickedBot, minutes(botMinutes));
            }
        }, new Coord(UI.scale(356), y));
        y += UI.scale(24);

        detail.add(new Label("Add wait:"), new Coord(0, y + UI.scale(3)));
        final TextEntry waitMinutes = detail.add(new TextEntry(UI.scale(48), "15"),
            new Coord(UI.scale(92), y));
        detail.add(new Button(UI.scale(40), "Add") {
            @Override
            public void click() {
                addWaitStep(minutes(waitMinutes));
            }
        }, new Coord(UI.scale(148), y));
    }

    private void addBotStep(String title, int minutes) {
        Schedule s = current();
        if (s == null)
            return;
        String id = null;
        for (BotDef d : BotRegistry.defs()) {
            if (d.title.equals(title)) {
                id = d.id;
                break;
            }
        }
        if (id == null) {
            gui.error("Pick a bot from the list first.");
            return;
        }
        s.steps.add(new Schedule.Step(Schedule.Step.BOT, id, minutes));
        Schedules.add(s);
        rebuild = true;
    }

    private void addWaitStep(int minutes) {
        Schedule s = current();
        if (s == null)
            return;
        s.steps.add(new Schedule.Step(Schedule.Step.WAIT, null, minutes));
        Schedules.add(s);
        rebuild = true;
    }

    private static int minutes(TextEntry entry) {
        try {
            return Math.max(1, Integer.parseInt(entry.text().trim()));
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private static List<String> windowedBots() {
        List<String> out = new ArrayList<>();
        for (BotDef d : BotRegistry.defs()) {
            if (!d.isScript())
                out.add(d.title);
        }
        return out;
    }

    private String statusText(Schedule s) {
        Thread t = threads.get(s.name);
        if (t == null || !t.isAlive())
            return "idle";
        ScheduleRunner runner = runners.get(s.name);
        if (runner == null || runner.step() < 0)
            return "starting...";
        if (runner.step() >= s.steps.size())
            return "wrapping up...";
        return "step " + (runner.step() + 1) + "/" + s.steps.size() + ": "
            + s.steps.get(runner.step()).label();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Re-reads the status line every second, and rebuilds the panels a frame after any
     * button asked for it. Both follow PlacesWindow: rewriting ONE label keeps typing
     * intact, and rebuilding from inside a click destroys the widget dispatching it.
     */
    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (rebuild) {
            rebuild = false;
            refresh();
            recount = 1.0;
            return;
        }
        recount -= dt;
        if (recount <= 0) {
            recount = 1.0;
            Schedule s = current();
            if (s != null && status != null)
                status.settext(statusText(s));
            /* A schedule that finishes on its own leaves a stale "(running)" marker in
             * the list; rebuilding here is what turns it back into "Run", and doing it
             * once when the thread actually died is cheap. */
            if (pruneDeadRunners())
                refresh();
        }
    }

    /** Drops runners whose thread has exited; true when anything was dropped. */
    private boolean pruneDeadRunners() {
        boolean changed = false;
        java.util.Iterator<Map.Entry<String, Thread>> it = threads.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Thread> e = it.next();
            if (!e.getValue().isAlive()) {
                it.remove();
                runners.remove(e.getKey());
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && Objects.equals(msg, "close")) {
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }

    @Override
    public void reqdestroy() {
        Utils.setprefc("wndc-nbot-SchedulesWindow", this.c);
        Utils.setpref(SELPREF, (selected == null) ? "" : selected);
        super.reqdestroy();
    }
}
