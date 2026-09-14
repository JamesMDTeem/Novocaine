"""Per-species table of what the wiki states against what the pack measured: HP and armour.

The 2026-09-13 audit published this table by hand, generated before the queen-ant join fix,
so it went stale the moment the pack was regenerated. This rebuilds it from the pack alone -
no network - so it can be re-run after every `estimate.py --write-pack`.

Flags:
    HP_WIKI_OUTSIDE_OBSERVED   the wiki figure is outside the envelope of every bracket seen
    HP_PINNED_ABOVE_WIKI       every individual a kill PINNED sat above the wiki figure
    HP_PINNED_BELOW_WIKI       every pinned individual sat below it
    ARMOUR_DIFFERS             the wiki's armour is outside the measured total

The pinned flags exist because the envelope straddles almost any figure: the warrior ant is
pinned at 74-125 against the "Ants 50" row it inherits, and its envelope (0-335) said nothing.
A flag says where to look - a base-quality gap, a depth-scaled species or the wrong wiki row -
not which of those it is. The wiki is a baseline here, never a control.

Run: python tools/combat/wiki_reconcile.py [--out PATH]
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402


def _rows():
    path = os.path.join(estimate.ROOT, "data", "combat", "opponents.json")
    with open(path, "r", encoding="utf-8") as f:
        doc = json.load(f)
    rows = doc.get("opponents") if isinstance(doc, dict) else doc
    if isinstance(rows, dict):
        rows = list(rows.values())
    return [r for r in rows or () if (r.get("kind") or "creature") == "creature"]


def _fmt(v):
    return "-" if v is None else str(v)


def table():
    out = ["| species | hp wiki | observed lo..hi | pinned lo..hi | arm wiki | hard | soft "
           "| total lo..hi | flags |",
           "|---|---|---|---|---|---|---|---|---|"]
    for r in sorted(_rows(), key=lambda x: x.get("name") or ""):
        hp = r.get("hitpoints") or {}
        arm = r.get("armour") or {}
        flags = []
        w = hp.get("wiki")
        olo, ohi = hp.get("observed_lo"), hp.get("observed_hi")
        if (w is not None) and (olo is not None) and (ohi is not None) and not (olo <= w <= ohi):
            flags.append("HP_WIKI_OUTSIDE_OBSERVED")
        pvw = hp.get("pinned_vs_wiki")
        if pvw == "above":
            flags.append("HP_PINNED_ABOVE_WIKI")
        elif pvw == "below":
            flags.append("HP_PINNED_BELOW_WIKI")
        aw, tlo, thi = arm.get("wiki"), arm.get("total_lo"), arm.get("total_hi")
        if (aw is not None) and (tlo is not None) and not (
                tlo <= aw <= (thi if thi is not None else float("inf"))):
            flags.append("ARMOUR_DIFFERS")
        out.append("| %s | %s | %s..%s | %s..%s | %s | %s | %s | %s..%s | %s |" % (
            r.get("name"), _fmt(w), _fmt(olo), _fmt(ohi), _fmt(hp.get("pinned_lo")),
            _fmt(hp.get("pinned_hi")), _fmt(aw), _fmt(arm.get("hard")), _fmt(arm.get("soft")),
            _fmt(tlo), _fmt(thi), " ".join(flags)))
    return "\n".join(out) + "\n"


def main(argv):
    text = table()
    if "--out" in argv:
        path = argv[argv.index("--out") + 1]
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("wrote %s" % path)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
