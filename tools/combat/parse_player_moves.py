# Parses the Combat moves fixture into data/combat/moves.json.
#
#   python tools/combat/parse_player_moves.py
#
# Four tables with DIFFERENT column layouts, so each section gets its own field map. Every table
# interleaves real rows with anchor rows marked style="visibility:collapse" that exist only to
# host heading anchors; those are skipped. Row counts are asserted (4/10/8/20) because a silently
# short move table would be invisible downstream and would corrupt every later estimate.
#
# openings_target: two Restorations rows (Flex, Yield Ground) prefix their Openings cell with
# "On opponent:" or "On you:". That prefix says WHO gets opened -- Flex opens the opponent (a
# benefit of the move), Yield Ground opens the user (a cost). Folding both into a bare `openings`
# list would make Yield Ground look like it opens the target, inverting a drawback into an
# advantage -- the same class of mistake as conflating `openings` and `reduces`. So every record
# also carries `openings_target`: "self", "opponent", or None when the cell carries no such
# prefix (true for every Moves/Maneuvers/Attacks row, and most Restorations rows). Never guessed:
# an unprefixed Attacks-table opening is conventionally on the target, but the wiki doesn't say
# so in the cell text, so it stays None rather than being defaulted to "opponent".

import sys, os, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki

OUT = wiki.FIXTURES.parents[1] / "data" / "combat"

SCHOOLS = ("striking", "backhanded", "sweeping", "oppressive")
ROBCOLOR_RE = re.compile(r"\{\{RoB color\|\s*([a-zA-Z]+)\s*\|", re.I)

COLUMNS = {
    "moves":        ["move", "learned", "ip", "effect", "cooldown", "distance", "notes"],
    "restorations": ["move", "learned", "ip", "weight", "openings", "reduces",
                     "cooldown", "distance", "notes"],
    "maneuvers":    ["move", "weight", "cooldown", "notes", "learned"],
    "attacks":      ["move", "learned", "ip", "weight", "attack_type", "openings",
                     "damage", "grievous", "cooldown", "special", "notes"],
}


def _sections(text):
    parts = re.split(r"\n==\s*'''([^']+)'''\s*==\n", text)
    return {parts[i].strip().lower(): parts[i + 1] for i in range(1, len(parts), 2)}


def _table(body):
    m = re.search(r"\{\|class=\"wikitable.*?\n(.*?)\n\|\}", body, re.S)
    return m.group(1) if m else ""


def _rows(table):
    """Split on row separators, dropping the header and the visibility:collapse anchors.

    The captured table body starts AT the sticky header's own "|- class=\"sticky\"" line (the
    outer regex already consumed the newline before it), so splitting on "\\n|-" leaves that
    literal "|- class=\"sticky\"" text sitting at the front of the header chunk. The obvious
    `chunk.lstrip().startswith("!")` therefore never fires -- lstrip only eats whitespace, and
    the chunk starts with "|", not "!" -- so the header silently falls through _cells() into one
    garbage merged cell and becomes a bogus record with a non-empty (garbage) name. Checking
    every line of the chunk for a "!"-prefix, instead of just the chunk's own start, is what
    actually catches it. Confirmed against the fixture: the naive check yields 5/11/9/21 records
    instead of 4/10/8/20, all four extras being one bogus header row per table.
    """
    out = []
    for chunk in re.split(r"\n\|-", table):
        if 'visibility:collapse' in chunk:
            continue
        if any(line.startswith("!") for line in chunk.split("\n")):
            continue
        if not chunk.strip():
            continue
        out.append(chunk)
    return out


def _cells(chunk):
    """Cells begin with a line-leading '|'. Values may span multiple lines."""
    cells, cur, started = [], [], False
    for line in chunk.split("\n"):
        if line.startswith("|"):
            if started:
                cells.append("\n".join(cur).strip())
            cur = [line[1:]]
            started = True
        elif started:
            cur.append(line)
    if started:
        cells.append("\n".join(cur).strip())
    return cells


def _name(cell):
    """The move cell is '[[File:icon_x.png|64px]]<br>Name', sometimes italicised."""
    tail = cell.split("<br>")[-1]
    return tail.replace("''", "").strip()


def _schools(cell):
    out = []
    for s in ROBCOLOR_RE.findall(cell or ""):
        s = s.lower()
        if s in SCHOOLS and s not in out:
            out.append(s)
    return out


def _openings_target(cell):
    """Who the Openings cell's schools land on. Only two rows in the fixture (Restorations:
    Flex, Yield Ground) say so explicitly; everything else is None, not guessed."""
    cell = cell or ""
    if "On you:" in cell:
        return "self"
    if "On opponent:" in cell:
        return "opponent"
    return None


def parse():
    text = wiki.load_fixture("Combat moves.wikitext")
    secs = _sections(text)
    result, unparsed = {}, []
    for key, cols in COLUMNS.items():
        body = secs.get(key)
        if body is None:
            unparsed.append("missing section: %s" % key)
            result[key] = []
            continue
        rows = []
        for chunk in _rows(_table(body)):
            cells = _cells(chunk)
            if not cells:
                continue
            f = dict(zip(cols, cells))
            name = _name(f.get("move", ""))
            if not name:
                unparsed.append("%s: nameless row" % key)
                continue
            rec = {
                "name": name,
                "section": key,
                "ip": wiki.num(f.get("ip", "")) if "ip" in f else None,
                "weight": (f.get("weight") or "").strip() or None,
                "cooldown": wiki.num(f.get("cooldown", "")),
                "attack_types": _schools(f.get("attack_type", "")),
                "openings": _schools(f.get("openings", "")),
                "openings_target": _openings_target(f.get("openings")),
                "reduces": _schools(f.get("reduces", "")),
                "damage": wiki.num(f.get("damage", "")) if "damage" in f else None,
                "grievous": wiki.num(f.get("grievous", "")) if "grievous" in f else None,
                "distance": (f.get("distance") or "").strip() or None,
                "notes": (f.get("notes") or "").strip() or None,
            }
            rows.append(rec)
        rows.sort(key=lambda r: r["name"])
        result[key] = rows
    return result, unparsed


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    secs, unparsed = parse()
    if unparsed:
        print("UNPARSED (%d): %s" % (len(unparsed), unparsed))
        sys.exit(1)
    expected = {"moves": 4, "restorations": 10, "maneuvers": 8, "attacks": 20}
    for k, n in expected.items():
        got = len(secs[k])
        print("%-13s %2d (expected %2d) %s" % (k, got, n, "ok" if got == n else "MISMATCH"))
        if got != n:
            print("  The wiki's %s table changed shape. Update the expected counts in this "
                  "script and in datapack_check.py DELIBERATELY, after reviewing the diff." % k)
            sys.exit(1)
    (OUT / "moves.json").write_text(
        json.dumps(secs, indent=2, sort_keys=True), encoding="utf8")
    print("wrote %s" % (OUT / "moves.json"))


if __name__ == "__main__":
    main()
