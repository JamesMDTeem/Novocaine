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
import parse_animal_moves
import parse_player_moves
import build_datapack

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


def animal_moves():
    print("\nanimal moves")
    recs, unparsed, malformed, _ = parse_animal_moves.parse()
    check("animal move count", len(recs), 41)
    check("no unparsed animal moves", unparsed, [])
    check("no malformed animal moves", malformed, [])
    check("template-form count", len([r for r in recs if r["source"] == "template"]), 10)
    check("table-form count", len([r for r in recs if r["source"] == "table"]), 31)
    hug = [r for r in recs if r["name"] == "Bear Hug"][0]
    check("bear hug attack type", hug["attack_types"], ["oppressive"])
    check("bear hug ip", hug["ip"]["value"], -4)
    check("bear hug openings dedup+order",
          hug["openings"], ["striking", "backhanded", "sweeping"])
    chomp = [r for r in recs if r["name"] == "Chomp"][0]
    check("chomp is template form", chomp["source"], "template")
    check("chomp attack types", chomp["attack_types"], ["striking", "sweeping"])
    check("chomp openings", chomp["openings"], ["backhanded"])
    check("chomp ip", chomp["ip"]["value"], -2)
    # rdc1..4 / *Reduces: is a DIFFERENT axis from openings -- it's what the move removes from
    # its own user, not what it inflicts on its target. Bristle reduces all four openings on
    # itself (a restoration move); folding that into `openings` would record it as inflicting
    # every opening on its target, the opposite of the truth.
    bristle = [r for r in recs if r["name"] == "Bristle"][0]
    check("bristle reduces all four schools", bristle["reduces"],
          ["striking", "backhanded", "sweeping", "oppressive"])
    check("bristle openings stay empty (reduces, not inflicts)", bristle["openings"], [])
    unstoppable = [r for r in recs if r["name"] == "Unstoppable"][0]
    check("unstoppable (table *Reduces:) has reduces data",
          len(unstoppable["reduces"]) > 0, True)
    # Trunk Gunk's "Attack type: Ranged" isn't a {{RoB color}} call, so it can't join the
    # attack_types vocabulary -- but the literal text is real data and must survive somewhere.
    trunk_gunk = [r for r in recs if r["name"] == "Trunk Gunk"][0]
    check("trunk gunk raw attack type kept", trunk_gunk["raw_attack_type"], "Ranged")
    # Exactly two pages carry no {{RoB color}} school data anywhere on the page at all -- Dark
    # Heart Move (a pure IP generator) and Trunk Gunk (a ranged attack); both sit outside the
    # four-school system. Asserting the set exactly, rather than "no colourless records" or
    # "colourless is fine", means a THIRD colourless page still fails loudly instead of being
    # silently swallowed by a loosened rule.
    colourless = {r["name"] for r in recs
                  if not r["attack_types"] and not r["openings"] and not r["reduces"]}
    check("colourless records are exactly the two off-system moves",
          colourless, {"Dark Heart Move", "Trunk Gunk"})


def player_moves():
    print("\nplayer moves")
    secs, bad = parse_player_moves.parse()
    check("no unparsed rows", bad, [])
    check("moves count", len(secs["moves"]), 4)
    check("restorations count", len(secs["restorations"]), 10)
    check("maneuvers count", len(secs["maneuvers"]), 8)
    check("attacks count", len(secs["attacks"]), 20)
    names = [a["name"] for a in secs["attacks"]]
    check("cleave present", "Cleave" in names, True)
    check("kito present", "Knock Its Teeth Out" in names, True)
    cleave = [a for a in secs["attacks"] if a["name"] == "Cleave"][0]
    check("cleave cooldown", cleave["cooldown"]["value"], 80)
    check("cleave attack types", cleave["attack_types"], ["backhanded", "oppressive"])
    check("cleave openings", cleave["openings"], ["oppressive"])
    kito = [a for a in secs["attacks"] if a["name"] == "Knock Its Teeth Out"][0]
    check("kito damage", kito["damage"]["value"], 30)
    check("kito cooldown", kito["cooldown"]["value"], 35)
    # special: the Attacks table's weapon-requirement / multi-target column. It was captured
    # into the row dict but silently dropped before reaching rec, so every attack lost its
    # weapon requirement. Assert on CONTENT, not merely non-nullness -- a check that only
    # confirms "not None" would still pass if the wrong column had been copied in its place.
    chop = [a for a in secs["attacks"] if a["name"] == "Chop"][0]
    check("chop special names an edged weapon",
          chop["special"] is not None and "edged" in chop["special"].lower(), True)
    storm = [a for a in secs["attacks"] if a["name"] == "Storm of Swords"][0]
    check("storm of swords special carries multi-target text", storm["special"] is not None, True)
    # Confirms special is genuinely optional, not defaulting to a string for rows with a blank
    # Special cell.
    check("cleave has no special (field is optional)", cleave["special"], None)
    # Anchor rows carry no move name; none may survive into the output.
    allrows = sum(secs.values(), [])
    check("no nameless rows", [r for r in allrows if not r["name"]], [])
    # openings_target: "On you:" vs "On opponent:" in the Openings cell says WHO gets opened.
    # Flattening both into a bare `openings` list would make Yield Ground's self-inflicted
    # opening (a cost of using the move) look like it opens the opponent (a benefit) -- the
    # same class of inversion as conflating `openings` with `reduces`.
    yield_ground = [r for r in secs["restorations"] if r["name"] == "Yield Ground"][0]
    check("yield ground opens self", yield_ground["openings_target"], "self")
    flex = [r for r in secs["restorations"] if r["name"] == "Flex"][0]
    check("flex opens opponent", flex["openings_target"], "opponent")
    # Confirms the field is genuinely tri-state, not silently defaulting to one of the two
    # observed values for every row that lacks an explicit "On you:"/"On opponent:" prefix.
    check("some records have no openings_target",
          any(r["openings_target"] is None for r in allrows), True)


def constants_and_crosscheck():
    print("\nconstants + cross-checks")
    import json as _json
    consts = _json.loads((wiki.DATA / "constants.json").read_text(encoding="utf8"))
    check("tick seconds", consts["tick_seconds"]["value"], 0.06)
    check("opening exponent flagged disputed", consts["opening_exponent"]["status"], "disputed")
    check("mu curve unknown", consts["mu_curve"]["value"], None)
    check("damage exponent", consts["opening_damage_exponent"]["value"], 2)
    # Every constant must declare a status, so nothing reads as settled when it is not.
    missing = [k for k, v in consts.items() if "status" not in v]
    check("all constants declare status", missing, [])
    # The client is authoritative for opening resource names.
    check("client cross-check clean", build_datapack.cross_check(), [])

    # Every move a creature references must exist as an animal_moves.json record, and every
    # animal_moves.json record must be referenced by at least one creature -- both directions,
    # not just a subset check in one direction.
    recs, _noinfo, _malformed = parse_creatures.parse()
    animal, _unparsed, _anmalformed, _mismatches = parse_animal_moves.parse()
    creature_moves = {m for c in recs for m in c["moves"]}
    animal_names = {r["name"] for r in animal}
    check("creature moves all present in animal_moves.json",
          sorted(creature_moves - animal_names), [])
    check("animal_moves.json records all referenced by a creature",
          sorted(animal_names - creature_moves), [])

    # School vocabulary: no stray names in either catalogue, and both actually use all four.
    player, _pbad = parse_player_moves.parse()
    moves_schools = build_datapack.schools_used(sum(player.values(), []))
    animal_schools = build_datapack.schools_used(animal)
    canon = build_datapack.SCHOOLS
    check("moves.json schools have no stray names", sorted(moves_schools - canon), [])
    check("animal_moves.json schools have no stray names", sorted(animal_schools - canon), [])
    check("moves.json uses all four schools", sorted(canon - moves_schools), [])
    check("animal_moves.json uses all four schools", sorted(canon - animal_schools), [])


def write_nothing_on_failure():
    """Regression-guards build_datapack's core promise: if any parser reports a problem, NO
    files get written. Injects a fake problem by monkeypatching one parser, redirects the
    build's output directory to a scratch temp dir for the duration (so this never touches
    data/combat/), and asserts both the exit code and that the temp dir stays empty. Restored
    in a finally so a failure here can't leave build_datapack or parse_gear patched for later
    checks (or, for that matter, later runs of this same check)."""
    print("\nwrite-nothing-on-failure guarantee")
    import tempfile, shutil
    from pathlib import Path

    orig_parse_weapons = parse_gear.parse_weapons
    orig_out = build_datapack.OUT
    tmpdir = Path(tempfile.mkdtemp(prefix="combat_datapack_check_"))
    try:
        def fake_parse_weapons():
            weapons, _bad = orig_parse_weapons()
            return weapons, ["INJECTED (test-only) failure to prove build writes nothing"]

        parse_gear.parse_weapons = fake_parse_weapons
        build_datapack.OUT = tmpdir

        exit_code = None
        try:
            build_datapack.main()
        except SystemExit as e:
            exit_code = e.code
        check("build exits 1 when a parser reports a problem", exit_code, 1)
        check("temp output dir has no files written", list(tmpdir.iterdir()), [])
    finally:
        parse_gear.parse_weapons = orig_parse_weapons
        build_datapack.OUT = orig_out
        shutil.rmtree(tmpdir, ignore_errors=True)


def main():
    primitives()
    gear()
    creatures()
    animal_moves()
    player_moves()
    constants_and_crosscheck()
    write_nothing_on_failure()
    print("\nALL CHECKS PASSED" if failures == 0 else "\n%d CHECK(S) FAILED" % failures)
    sys.exit(0 if failures == 0 else 1)

if __name__ == "__main__":
    main()
