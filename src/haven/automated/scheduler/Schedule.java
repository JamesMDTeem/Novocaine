package haven.automated.scheduler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One named schedule: an ordered list of steps the scheduler runs.
 *
 * A step either runs one of the registered bots for a number of minutes, or does nothing
 * for a number of minutes. Durations are whole minutes because the point of a schedule is
 * "run the dragonfly bot, wait fifteen minutes, run it again" - fine-grained timing belongs
 * inside the bots, not here.
 *
 * One JSON object per schedule, so a hand-edited or corrupt entry can be skipped by the
 * loader without losing the rest.
 */
public class Schedule {
    public String name;
    public boolean repeat;
    public final List<Step> steps = new ArrayList<>();

    public static class Step {
        /** Step kinds. */
        public static final String BOT = "bot";
        public static final String WAIT = "wait";

        public final String kind;
        /** The bot's registry id, when {@link #kind} is {@link #BOT}. */
        public final String bot;
        public final int minutes;

        public Step(String kind, String bot, int minutes) {
            this.kind = kind;
            this.bot = bot;
            this.minutes = Math.max(1, minutes);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            if (bot != null)
                o.put("bot", bot);
            o.put("minutes", minutes);
            return o;
        }

        public static Step fromJson(JSONObject o) {
            String kind = o.optString("kind", BOT);
            String bot = o.optString("bot", null);
            int minutes = o.optInt("minutes", 5);
            return new Step(kind, bot, minutes);
        }

        public String label() {
            if (WAIT.equals(kind))
                return "Wait " + minutes + " min";
            return "Run " + bot + " for " + minutes + " min";
        }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("repeat", repeat);
        JSONArray steps = new JSONArray();
        for (Step s : this.steps)
            steps.put(s.toJson());
        o.put("steps", steps);
        return o;
    }

    public static Schedule fromJson(JSONObject o) {
        Schedule s = new Schedule();
        s.name = o.optString("name", "");
        s.repeat = o.optBoolean("repeat", false);
        JSONArray steps = o.optJSONArray("steps");
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                Object raw = steps.opt(i);
                if (raw instanceof JSONObject)
                    s.steps.add(Step.fromJson((JSONObject) raw));
            }
        }
        return s;
    }
}
