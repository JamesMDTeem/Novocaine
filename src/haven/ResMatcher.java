package haven;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Whether a resource name fully matches any of a list of regular expressions, compiled once.
 *
 * The object highlights in Gob asked Arrays.stream(list).anyMatch(name::matches) every time they
 * updated, and String.matches compiles its pattern afresh on each call - every pattern in the
 * list, for every gob, each time: 2.9 GB in a two-minute flight recording (2026-09-26). The list
 * is compiled here into one alternation, (?:p1)|(?:p2)|..., which under matches() accepts exactly
 * when some pi matches the whole name, as anyMatch did; and since the answer depends on the name
 * alone and there are only so many resource names, each is remembered once asked.
 */
public class ResMatcher {
    private final Pattern pat;
    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();

    public ResMatcher(String... regexes) {
	StringBuilder buf = new StringBuilder();
	for(String re : regexes) {
	    if(buf.length() > 0)
		buf.append('|');
	    buf.append("(?:").append(re).append(')');
	}
	/* An empty list matches nothing, as anyMatch over nothing does. */
	this.pat = (regexes.length == 0) ? null : Pattern.compile(buf.toString());
    }

    public boolean matches(String name) {
	if((pat == null) || (name == null))
	    return(false);
	return(seen.computeIfAbsent(name, n -> pat.matcher(n).matches()));
    }
}
