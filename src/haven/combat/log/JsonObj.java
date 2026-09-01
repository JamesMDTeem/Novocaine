package haven.combat.log;

import java.util.Locale;

/**
 * Minimal JSON object writer for the combat log.
 *
 * Imports nothing from haven, deliberately - see tools/CombatLogCheck.java. All number
 * formatting is Locale.ROOT: a comma decimal separator produces invalid JSON.
 */
public final class JsonObj {
    private final StringBuilder sb = new StringBuilder(160);
    private boolean first = true;

    public JsonObj() {
        sb.append('{');
    }

    private void key(String k) {
        if(!first)
            sb.append(',');
        first = false;
        sb.append('"').append(esc(k)).append("\":");
    }

    public JsonObj put(String k, String v) {
        key(k);
        if(v == null)
            sb.append("null");
        else
            sb.append('"').append(esc(v)).append('"');
        return(this);
    }

    public JsonObj put(String k, long v) {
        key(k);
        sb.append(v);
        return(this);
    }

    public JsonObj put(String k, boolean v) {
        key(k);
        sb.append(v);
        return(this);
    }

    public JsonObj put(String k, double v) {
        key(k);
        if(Double.isNaN(v) || Double.isInfinite(v))
            sb.append("null");
        else
            sb.append(String.format(Locale.ROOT, "%.4f", v));
        return(this);
    }

    /**
     * A double at full precision, for callers that need the value back exactly.
     *
     * {@link #put(String, double)} rounds to four places, which is right for a log - the
     * numbers there are measurements, and four places is past their resolution. It is wrong
     * for golden vectors, where a rounded expectation would let the Python evaluator drift
     * by up to half a unit in the fourth place without any check noticing.
     */
    public static String num(double v) {
        if(Double.isNaN(v) || Double.isInfinite(v))
            return("null");
        return(Double.toString(v));
    }

    /** Inserts already-serialised JSON (a nested object or array) verbatim. */
    public JsonObj raw(String k, String json) {
        key(k);
        sb.append(json == null ? "null" : json);
        return(this);
    }

    public String end() {
        return(sb.toString() + "}");
    }

    public static String esc(String s) {
        StringBuilder o = new StringBuilder(s.length() + 8);
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch(c) {
            case '"':  o.append("\\\""); break;
            case '\\': o.append("\\\\"); break;
            case '\n': o.append("\\n");  break;
            case '\r': o.append("\\r");  break;
            case '\t': o.append("\\t");  break;
            default:
                if(c < 0x20)
                    o.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                else
                    o.append(c);
            }
        }
        return(o.toString());
    }
}
