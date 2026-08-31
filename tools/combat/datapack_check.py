# Verification harness for the combat data pack. Mirrors the tools/*Check.java convention:
# a single file with a main(), run on demand, exits 0 when every check passes and 1 otherwise.
#
#   python tools/combat/datapack_check.py
#
# Parsing checks run against the checked-in fixtures in tools/combat-fixtures/, so this never
# touches the network and a wiki edit cannot silently change what it verifies.

import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki
import parse_gear
import parse_creatures

failures = 0

def check(what, got, want):
    global failures
    ok = got == want
    print("  %-52s %-28s %s" % (what, repr(got)[:28], "ok" if ok else "WANT " + repr(want)[:40]))
    if not ok:
        failures += 1

def primitives():
    print("wiki primitives")
    # Brace-depth: a nested {{#expr:}} must not terminate extraction early.
    text = "{{infobox creature\n| xobst = {{#expr:(4/11)-(-4/11)round6}}\n| hp = 850\n| armor = 65\n}}"
    block = wiki.extract_template(text, "infobox creature")
    check("extract spans nested braces", block is not None and block.endswith("}}"), True)
    f = wiki.fields(block)
    check("field after nested template", f.get("hp"), "850")
    check("second field after nested", f.get("armor"), "65")
    check("nested value kept whole", f.get("xobst"), "{{#expr:(4/11)-(-4/11)round6}}")
    # A pipe inside a wikilink is not a field separator.
    f2 = wiki.fields("{{infobox metaobj\n| loot = [[Fresh Bear Hide|hide]] x2\n| basedmg = 150\n}}")
    check("pipe inside wikilink ignored", f2.get("basedmg"), "150")
    check("missing template returns None", wiki.extract_template("no box here", "infobox creature"), None)
    # Found-but-never-closed must not be mistaken for "not present" -- it has to raise,
    # not return None, or a later consumer treating None as "no infobox" would silently
    # absorb a genuine parse failure into that same bucket.
    try:
        wiki.extract_template("{{infobox creature\n| hp = 850", "infobox creature")
        outcome = "no raise"
    except ValueError:
        outcome = "ValueError"
    check("unbalanced block raises ValueError", outcome, "ValueError")
    # Tolerant numerics: the wiki is not always numeric.
    check("plain int", wiki.num("850"), {"raw": "850", "value": 850})
    check("approx value", wiki.num("~500"), {"raw": "~500", "value": 500})
    check("unparseable kept raw", wiki.num("varies"), {"raw": "varies", "value": None})
    check("empty is null", wiki.num(""), {"raw": "", "value": None})

def gear():
    print("\nweapons + armour")
    weapons, wbad = parse_gear.parse_weapons()
    armor, abad = parse_gear.parse_armor()
    check("weapon count", len(weapons), 26)
    check("no unparsed weapons", wbad, [])
    check("armour count", len(armor), 37)
    check("no unparsed armour", abad, [])
    b12 = [w for w in weapons if w["name"] == "Battleaxe of the Twelfth Bay"][0]
    check("b12 basedmg", b12["basedmg"]["value"], 150)
    check("b12 armorpen", b12["armorpen"]["value"], 10)
    bp = [a for a in armor if a["name"] == "Bronze Plate"][0]
    check("bronze plate hard", bp["hard"]["value"], 20)
    check("bronze plate soft", bp["soft"]["value"], 15)
    check("bronze plate ahp", bp["ahp"]["value"], 450)
    # Missing fields must be null, never a fabricated zero.
    missing_pen = [w for w in weapons if w["armorpen"] is None]
    check("weapons missing armorpen are null", len(missing_pen), 4)
    check("every weapon has basedmg",
          all(w["basedmg"]["value"] is not None for w in weapons), True)


def creatures():
    print("\ncreatures")
    recs, noinfo, malformed = parse_creatures.parse()
    check("creature records", len(recs), 81)
    check("pages without infobox", len(noinfo), 34)
    check("malformed infobox pages", len(malformed), 0)
    bear = [c for c in recs if c["name"] == "Bear"][0]
    check("bear hp", bear["hp"]["value"], 850)
    check("bear armor", bear["armor"]["value"], 65)
    check("bear fhp approx parsed", bear["fhp"]["value"], 500)
    check("bear fhp raw kept", bear["fhp"]["raw"], "~500")
    check("bear deadly", bear["deadly"], True)
    check("bear moves include Bear Hug", "Bear Hug" in bear["moves"], True)
    # Hidden stats are the estimator's job, not the wiki's.
    check("hidden stats null", (bear["ua"], bear["mc"], bear["str"], bear["agi"]),
          (None, None, None, None))
    # Only 20 of 81 creature infoboxes carry armour; the rest must be null, not 0.
    with_armor = [c for c in recs if c["armor"] is not None]
    check("creatures with armor field", len(with_armor), 20)
    # Some pages transclude a move in lowercase (Lynx: {{:bristle}}); MediaWiki resolves that
    # to the same page as "Bristle" since only the first title character is case-insensitive.
    # Without first-character normalisation this count is 42, one too many, because "bristle"
    # and "Bristle" would be counted as distinct moves -- and this would silently break an
    # exact-string join against the canonical Animal Moves catalogue (41 entries) later.
    unique_moves = {m for c in recs for m in c["moves"]}
    check("unique move names across all creatures", len(unique_moves), 41)
    lynx = [c for c in recs if c["name"] == "Lynx"][0]
    check("lynx move normalised to Bristle", "Bristle" in lynx["moves"], True)
    check("lynx move not left lowercase", "bristle" in lynx["moves"], False)


def main():
    primitives()
    gear()
    creatures()
    print("\nALL CHECKS PASSED" if failures == 0 else "\n%d CHECK(S) FAILED" % failures)
    sys.exit(0 if failures == 0 else 1)

if __name__ == "__main__":
    main()
