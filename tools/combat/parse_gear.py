# Parses Category:Weapons and Category:Armor fixtures into data/combat/{weapons,armor}.json.
# Both use {{infobox metaobj}}, so they share one field reader.
#
#   python tools/combat/parse_gear.py
#
# A field the wiki omits becomes null, never 0 -- armorpen is genuinely absent on 4 of 26
# weapons, and inventing a zero there would let the estimator fit against fabricated data.

import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki

OUT = wiki.DATA


def _titles(category):
    index = json.loads(wiki.load_fixture("index.json"))
    return index[category]


def _optnum(f, key):
    return wiki.num(f[key]) if key in f else None


def _parse(category, build):
    records, unparsed = [], []
    for title in _titles(category):
        try:
            text = wiki.load_fixture(wiki.safe_filename(title))
        except FileNotFoundError:
            unparsed.append(title + " (missing fixture)")
            continue
        except OSError as e:
            unparsed.append("%s (%s)" % (title, e))
            continue
        try:
            block = wiki.extract_template(text, "infobox metaobj")
        except ValueError as e:
            unparsed.append("%s (malformed: %s)" % (title, e))
            continue
        if block is None:
            unparsed.append(title + " (no infobox metaobj)")
            continue
        fields = wiki.fields(block)
        if not fields:
            unparsed.append(title + " (empty infobox)")
            continue
        records.append(build(title, fields))
    records.sort(key=lambda r: r["name"])
    return records, unparsed


def parse_weapons():
    return _parse("Weapons", lambda t, f: {
        "name": t,
        "basedmg": _optnum(f, "basedmg"),
        "armorpen": _optnum(f, "armorpen"),
        "range": _optnum(f, "range"),
        "slot": f.get("slot"),
    })


def parse_armor():
    return _parse("Armor", lambda t, f: {
        "name": t,
        "ahp": _optnum(f, "ahp"),
        "hard": _optnum(f, "absorbX"),
        "soft": _optnum(f, "absorbY"),
        "slot": f.get("slot"),
        "statAGI": _optnum(f, "statAGI"),
    })


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in (("weapons", parse_weapons), ("armor", parse_armor)):
        records, unparsed = fn()
        if unparsed:
            print("UNPARSED %s: %s" % (name, unparsed))
            sys.exit(1)
        (OUT / (name + ".json")).write_text(
            json.dumps(records, indent=2, sort_keys=True), encoding="utf8")
        print("%-8s %3d records -> %s" % (name, len(records), OUT / (name + ".json")))


if __name__ == "__main__":
    main()
