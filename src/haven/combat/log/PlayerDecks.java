package haven.combat.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The cards each player has been SEEN to throw, remembered across fights.
 *
 * A player's deck is not something the client is ever told. What it is told is every card an
 * opponent throws, as it throws it - so a deck can be learned the same way a person learns it:
 * by watching. Each card seen is added to that player's deck, and the next fight against them
 * starts from everything seen before rather than from nothing.
 *
 * KEYED BY KIN NAME, and only a kinned player is remembered between sessions. The client names
 * a player only when we have memorised them; anyone else is a gob id, which is issued per login
 * (one character in the corpus has 32 of them over 13 days), so it is kept for the rest of the
 * session and never written down. A deck under a gob id that outlived its session would be
 * attributed to whoever was issued that number next. A player named part-way through a fight
 * keeps the cards seen before the name arrived - see {@link #adopt}.
 *
 * Pure, like the rest of this package: no client types, so the check harness can hold it.
 */
public final class PlayerDecks {
    private final Map<String, Map<String, Integer>> decks =
        new TreeMap<String, Map<String, Integer>>();

    /** The key a player is remembered under: their kin name where known, else their gob. */
    public static String keyFor(String kinName, long gob) {
        if((kinName != null) && !kinName.trim().isEmpty())
            return("kin:" + clean(kinName.trim()));
        return("gob:" + gob);
    }

    /** Whether a key outlives the session - a named player does, a bare gob does not. */
    public static boolean durable(String key) {
        return((key != null) && key.startsWith("kin:"));
    }

    /** One card seen thrown by one player. */
    public synchronized void observe(String key, String cardRes) {
        if((key == null) || (cardRes == null) || cardRes.isEmpty())
            return;
        Map<String, Integer> d = decks.get(key);
        if(d == null) {
            d = new TreeMap<String, Integer>();
            decks.put(key, d);
        }
        String card = clean(cardRes);
        Integer n = d.get(card);
        d.put(card, (n == null) ? 1 : (n + 1));
    }

    /**
     * Moves everything seen under one key onto another, summing where both saw a card.
     *
     * The case: a player is fought before the client can name them, so their first cards are
     * filed under their gob, and a name arrives a few frames later. Adopting the gob's cards
     * under the name is what keeps those first cards from being lost at logout.
     */
    public synchronized void adopt(String from, String to) {
        if((from == null) || (to == null) || from.equals(to))
            return;
        Map<String, Integer> d = decks.remove(from);
        if(d == null)
            return;
        Map<String, Integer> t = decks.get(to);
        if(t == null) {
            t = new TreeMap<String, Integer>();
            decks.put(to, t);
        }
        for(Map.Entry<String, Integer> e : d.entrySet()) {
            Integer n = t.get(e.getKey());
            t.put(e.getKey(), ((n == null) ? 0 : n) + e.getValue());
        }
    }

    /** Every card seen from this player and how often, or an empty map. */
    public synchronized Map<String, Integer> deck(String key) {
        Map<String, Integer> d = decks.get(key);
        return((d == null) ? Collections.<String, Integer>emptyMap()
               : Collections.unmodifiableMap(new TreeMap<String, Integer>(d)));
    }

    /** The players remembered, durable or not. */
    public synchronized List<String> players() {
        return(new ArrayList<String>(decks.keySet()));
    }

    /**
     * Reads a saved file: one `player TAB card TAB count` line each. A missing file is an empty
     * memory, and a malformed line is skipped rather than failing the whole read - this runs in
     * the client, where a damaged file must cost its bad lines and nothing else.
     */
    public static PlayerDecks load(Path file) {
        PlayerDecks out = new PlayerDecks();
        if((file == null) || !Files.isRegularFile(file))
            return(out);
        try {
            for(String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if(f.length != 3)
                    continue;
                int n;
                try {
                    n = Integer.parseInt(f[2].trim());
                } catch(NumberFormatException e) {
                    continue;
                }
                if(!durable(f[0]) || f[1].isEmpty() || (n <= 0))
                    continue;
                Map<String, Integer> d = out.decks.get(f[0]);
                if(d == null) {
                    d = new TreeMap<String, Integer>();
                    out.decks.put(f[0], d);
                }
                d.put(f[1], n);
            }
        } catch(IOException e) {
            /* unreadable is the same as absent */
        }
        return(out);
    }

    /**
     * Writes the durable decks, sorted, through a temporary file and a move, so a crash during
     * the write leaves the previous file rather than half of a new one. Gob-keyed decks are
     * never written - see the class comment.
     */
    public synchronized void save(Path file) throws IOException {
        StringBuilder b = new StringBuilder();
        for(Map.Entry<String, Map<String, Integer>> p : decks.entrySet()) {
            if(!durable(p.getKey()))
                continue;
            for(Map.Entry<String, Integer> c : p.getValue().entrySet())
                b.append(p.getKey()).append('\t').append(c.getKey()).append('\t')
                    .append(c.getValue()).append('\n');
        }
        Path dir = file.toAbsolutePath().getParent();
        if(dir != null)
            Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, b.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /* A tab or line break inside a name would split a record, so they become spaces. */
    private static String clean(String s) {
        return(s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
    }
}
