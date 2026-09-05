# Parses Category:Creatures fixtures into data/combat/creatures.json.
#
#   python tools/combat/parse_creatures.py
#
# 34 of the 115 pages have no {{infobox creature}} at all (butterflies, chicks, ducklings and
# other non-combat critters). Those are reported and skipped, not treated as failures -- but the
# count is asserted, so a change in that number surfaces rather than passing unnoticed.
#
# A page that HAS an infobox but where it is malformed (unbalanced braces) is a different thing
# entirely: wiki.extract_template raises ValueError for that case rather than returning None, and
# that must land in a separate `malformed` bucket, not `noinfo` -- folding it into "no infobox"
# would silently absorb a genuine parse failure into the same bucket as legitimate non-combat
# pages, which is exactly what the None/ValueError split exists to prevent.
#
# hp/armor/baseq are PRIORS ONLY. The ARM damage channel observes armour absorption directly and
# the min-estimator bounds max HP from observed deaths; where they disagree, observation wins.

import sys, os, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki

OUT = wiki.DATA

# The Combat Moves table on a creature page transcludes each move as {{:Move Name}}.
# Also matches {{:Move Name|param}} form; pipe terminates the name.
MOVE_RE = re.compile(r"\{\{\s*:\s*([^}|]+?)\s*(?:\|[^}]*)?\}\}")


def _norm_move(name):
    """MediaWiki title resolution is case-insensitive on the FIRST character only (the rest
    is case-sensitive). Some pages transclude moves in lowercase (Lynx: {{:bristle}} instead
    of {{:Bristle}}); normalise the captured name the same way MediaWiki resolves it, so an
    exact-string join against the canonical Animal Moves catalogue does not silently miss.
    Deliberately NOT .title() / .capitalize() -- both would mangle the rest of multi-word
    names like "Go for the Jugular" or "Raven's Bite"."""
    name = name.strip()
    return name[:1].upper() + name[1:] if name else name


def _optnum(f, key):
    return wiki.num(f[key]) if key in f else None


def parse():
    index = json.loads(wiki.load_fixture("index.json"))
    records, noinfo, malformed = [], [], []
    for title in index["Creatures"]:
        try:
            text = wiki.load_fixture(wiki.safe_filename(title))
        except FileNotFoundError:
            noinfo.append(title)
            continue
        try:
            block = wiki.extract_template(text, "infobox creature")
        except ValueError:
            malformed.append(title)
            continue
        if block is None:
            noinfo.append(title)
            continue
        f = wiki.fields(block)
        records.append({
            "name": title,
            "hp": _optnum(f, "hp"),
            "fhp": _optnum(f, "fhp"),
            "armor": _optnum(f, "armor"),
            "baseq": _optnum(f, "baseq"),
            "deadly": f.get("deadly", "").strip().lower() == "yes",
            "moves": [_norm_move(m) for m in MOVE_RE.findall(text)],
            "ua": None, "mc": None, "str": None, "agi": None,
        })
    records.sort(key=lambda r: r["name"])
    return records, sorted(noinfo), sorted(malformed)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    records, noinfo, malformed = parse()
    print("creatures %3d records, %d pages without an infobox (skipped):" % (len(records), len(noinfo)))
    for t in noinfo:
        print("    %s" % t)
    if malformed:
        print("MALFORMED %d page(s) with an unbalanced infobox:" % len(malformed))
        for t in malformed:
            print("    %s" % t)
    (OUT / "creatures.json").write_text(
        json.dumps(records, indent=2, sort_keys=True), encoding="utf8")
    print("wrote %s" % (OUT / "creatures.json"))
    if malformed:
        sys.exit(1)


if __name__ == "__main__":
    main()
