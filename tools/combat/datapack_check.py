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

# Tolerance on the recovered base against the wiki's stated one. The two weapons the
# corpus holds sit at 0.2% and 0.5%, so 1% passes today with room and still fails the
# factor-of-two error this pair of checks exists to catch.
RECOVER_TOL = 0.01


def _span(name, rec):
    """The reading, named: how many sightings, over what quality range, recovering what."""
    b = rec.get("recovered_base") or {}
    q = rec.get("quality") or []
    return ("%s n=%s ql %s-%s -> base %s-%s"
            % (name, rec.get("n"), q[0] if q else "?", q[-1] if q else "?",
               b.get("lo"), b.get("hi")))


def _agrees(rec, wiki_base):
    """True when EVERY recovered base in the corpus is within RECOVER_TOL of the wiki's.

    Both ends, not the lowest: a scaling law that drifts with quality would still put one
    end on the wiki figure, and that is exactly the failure mode worth catching.
    """
    b = rec.get("recovered_base") or {}
    lo, hi = b.get("lo"), b.get("hi")
    if (lo is None) or (hi is None) or not wiki_base:
        return False
    return (abs(lo - wiki_base) <= RECOVER_TOL * wiki_base
            and abs(hi - wiki_base) <= RECOVER_TOL * wiki_base)


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
    # Live WeaponInfo vs wiki table - the stone-axe factor-2 finding.
    #
    # The check NAMES the readings so a regression has to move a reading, not just flip a
    # verdict - but it names the RANGE the corpus has seen, not one arbitrary member of it.
    # It used to pin damage[0] and quality[0] to the literals 176.0 and 38.0613, which is
    # an index into a sorted list of every sighting: the pool grew by one lower-quality
    # bronze sword and four checks went red while nothing about the model had moved.
    #
    # What carries the meaning is that dividing the quality scaling back out of a live
    # tooltip returns the wiki's stated base. Asserting that over the whole span is a
    # stricter test than the old one as well as a stable one - the sword now spans ql
    # 30.4 to 68.7 and the axe 56.3 to 158.1, so agreeing at both ends is agreeing across
    # a factor of two in quality rather than at a single point.
    try:
        import estimate as _est
        seen = _est.weapons_seen()
        # Bronze sword: tooltip 176 at ql 38.0613 -> recovered 90.21 vs wiki 90
        bs = seen.get("bronzesword") or seen.get("bronze_sword") or {}
        # Stone axe: tooltip 71 at ql 56.2835 -> recovered 29.93 vs wiki 30, pen 20% vs 10%
        sa = seen.get("stoneaxe") or {}
        wiki_byname = {w["name"]: w for w in weapons}
        bs_wiki = wiki_byname.get("Bronze Sword", {})
        sa_wiki = wiki_byname.get("Stone Axe", {})
        # Name the readings
        bs_wiki_base = (bs_wiki.get("basedmg") or {}).get("value")
        bs_wiki_pen = (bs_wiki.get("armorpen") or {}).get("value")
        bs_live_pen = (bs.get("armpen") or [None])[0]
        check("bronze sword seen at all", (bs.get("n") or 0) > 0, True)
        check(_span("bronze sword", bs), _agrees(bs, bs_wiki_base), True)
        check("bronze sword wiki base 90", bs_wiki_base, 90)
        check("bronze sword wiki pen 12.5 vs live 0.125 agrees", bs_wiki_pen, 12.5)
        check("bronze sword live pen 0.125", bs_live_pen, 0.125)
        sa_wiki_base = (sa_wiki.get("basedmg") or {}).get("value")
        sa_live_pen = (sa.get("armpen") or [None])[0]
        check("stone axe seen at all", (sa.get("n") or 0) > 0, True)
        check(_span("stone axe", sa), _agrees(sa, sa_wiki_base), True)
        check("stone axe wiki base 30", sa_wiki_base, 30)
        # The corrected offline file: data/combat/weapons.json Stone Axe pen 20 (was wiki 10).
        # Parse fixtures still say 10 - the correction is in the built data file, not the scrape.
        import json as _json2, os as _os2
        try:
            with open(_os2.path.join(_est.ROOT, "data", "combat", "weapons.json"), encoding="utf8") as _f:
                _built = {w.get("name"): w for w in _json2.load(_f)}
            _built_pen = (_built.get("Stone Axe", {}).get("armorpen") or {}).get("value")
        except Exception:
            _built_pen = None
        check("stone axe built weapons.json pen corrected to 20", _built_pen, 20)
        check("stone axe live pen 0.20 vs built 20 agrees post-fix", sa_live_pen, 0.2)
        # Offline join prefers live pen/base where present, wiki fallback documented in estimate.py
        join = _est.weapon_offline_join()
        check("offline join stoneaxe pen prefers live 0.20", join.get("stoneaxe", {}).get("armorpen"), 0.2)
        check("offline join bronzesword pen prefers live 0.125", join.get("bronzesword", {}).get("armorpen"), 0.125)
    except Exception as e:
        check("weapon live vs wiki naming", str(e)[:28], "ok")


def creatures():
    print("\ncreatures")
    recs, noinfo, malformed = parse_creatures.parse()
    check("creature records", len(recs), 82)
    check("pages without infobox", len(noinfo), 33)
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
    # Only 20 of 82 creature infoboxes carry armour; the rest must be null, not 0.
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
    # Was "disputed" while the wiki's worked example and its prose disagreed. The corpus
    # settled it - Knock Its Teeth Out's +24/+19/+14/+11 ladder from a listed +20% - so this
    # now pins the resolved value. A check that pins a stale status keeps a settled question
    # looking open, which is its own quiet failure.
    check("opening exponent settled at a cube root", consts["opening_exponent"]["value"], 3)
    check("  and flagged confirmed", consts["opening_exponent"]["status"], "confirmed")
    # mu is an input, not a measurement: the curve above level 1 stays null on purpose, and
    # the model carries the stated range as an interval rather than guessing a shape.
    check("mu curve still unmeasured", consts["mu_curve"]["value"], None)
    check("mu range is the devs' stated 1.0 to 1.5",
          (consts["mu_min"]["value"], consts["mu_max"]["value"]), (1.0, 1.5))
    check("damage exponent", consts["opening_damage_exponent"]["value"], 2)
    # The damage term reads the attack's OWN colours, never all four - the mistake that made
    # the damage coefficient appear to rise with Melee Combat.
    check("damage reads the attack's own colours",
          consts["damage_opening_scope"]["value"], "own attack types only")
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


def every_key_is_read():
    """Is there anything in the shipped data that nothing reads?

    THIS IS THE SHAPE OF THE LAST TWO BUGS. Shield Up's "block_requires" was parsed out
    of the card text, written into the sheet, shipped in the pack, and read by nothing -
    250% of the block weight holding a shield against 50% without, a factor of five on
    the one number that stance exists to set, and every model quietly used the headline
    figure. The character's armour was the same story from the other end: the client had
    been writing hard and soft soak on every gear row for the whole corpus and the duel
    fought naked.

    Neither was visible in the code, because the code looked finished. What was missing
    was a consumer, and a missing consumer leaves no trace anywhere except in the answers.

    So this goes key by key through what is actually shipped and asks whether any reader
    names it. A key nobody names is either dead weight in the file or a mechanic that is
    not being applied, and the two are worth telling apart by hand - which is why this
    prints them rather than guessing.
    """
    import json as _json3
    import estimate as _est3
    root = _est3.ROOT
    print("")
    print("is there anything in the data that nothing reads")
    readers = []
    for rel in ("src/haven/combat/data/Pack.java",
                "tools/combat/estimate.py",
                "tools/combat/replay.py",
                "tools/combat/experiment.py",
                "tools/CombatDeckSearch.java",
                "tools/CombatMeta.java",
                "tools/CombatPackCheck.java"):
        try:
            with open(os.path.join(root, rel), "r", encoding="utf-8",
                      errors="replace") as f:
                readers.append(f.read())
        except OSError:
            pass
    blob = chr(10).join(readers)

    # Keys that are documentation rather than instruction: they say where a number came
    # from or how sure of it we are, and nothing downstream is meant to branch on them.
    PROSE = {"source", "note", "from", "wiki", "raw", "name", "verdict", "problems",
             "notes", "members", "self_contradictory_gobs", "outliers", "modes_note",
             # The card text a figure was parsed OUT of, kept so the parse can be argued
             # with. "80 / mu" is provenance for cooldown and cooldown_mu, which are the
             # two things a fight reads.
             "cooldown_raw"}

    # Keys whose CONTENTS are data rather than schema. "owned" maps a card's display name
    # to its level, so its keys are forty card names, not forty fields nobody reads.
    INDEXES = {"owned", "policy", "mix"}

    for rel in ("data/combat/moves_sheet.json", "data/combat/opponents.json",
                "data/combat/characters.json"):
        path = os.path.join(root, rel)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                doc = _json3.load(f)
        except (OSError, ValueError):
            continue
        keys = set()

        # A key that is empty in every record has nothing to consume, so a missing reader
        # says nothing about it. Counted rather than assumed: "when_attacked" is in the
        # sheet forty-one times and populated zero.
        filled = set()

        def walk(node, depth=0):
            if isinstance(node, dict):
                for k, v in node.items():
                    keys.add(k)
                    if v not in (None, 0, 0.0, False, "", [], {}):
                        filled.add(k)
                    if (depth < 2) and (k not in INDEXES):
                        walk(v, depth + 1)
            elif isinstance(node, list):
                for v in node[:40]:
                    walk(v, depth)

        walk(doc)
        unread = sorted(k for k in keys
                        if (k in filled) and (k not in PROSE)
                        and ('"%s"' % k not in blob))
        check("every key in %s is named by a reader" % os.path.basename(rel),
              unread, [])


# What a card's own text says it does, against whether the data has anywhere to put it.
#
# THE AUDIT CANNOT SEE THESE. CombatAudit asks whether every field is read by something,
# which catches a mechanic that was parsed and then ignored - Shield Up's shield, the
# character's armour. It cannot catch a mechanic that was never given a field, because
# there is nothing for it to enumerate. That is a different hole and it is larger.
#
# The text is the game's own: moves_sheet.json is a client deck dump, not a wiki scrape.
# So a note here is what the card says about itself, and "no field" means the simulator
# is playing a different card from the one in the game.
#
# Each entry is (card, field that would hold it or None, what the text says). A None means
# the mechanic is not modelled, and the count of those is the finding.
TEXT_MECHANICS = [
    ("Steal Thunder", None,
     "takes 3 initiative from the target and gains you 2, to the extent it is unblocked"),
    ("Feigned Dodge", None,
     "the opening it takes off you is put ON the opponent at twice the amount, so it "
     "is a reduction and an attack in one and the model has only the reduction"),
    ("Bloodlust", None,
     "charges 25% per mu; your attack weight rises by four times the charge"),
    ("Combat Meditation", None,
     "charges 25% per mu; your cooldown falls by the charge"),
    ("Dash", None, "completely removes your slightest opening"),
    ("Oak Stance", None, "your greatest opening is reduced by 5% per mu"),
    ("Full Circle", None, "attacks your target and every other opponent in range"),
    ("Punch 'em Both", None, "attacks your target and one other"),
    ("Storm of Swords", None,
     "attacks up to five, at 100/125/150/175/200% of the weapon's damage"),
    # RANGE IS A MECHANIC AND NOT A DETAIL, AND THE LOGS ALREADY MEASURE IT. Every state
    # row carries the distance to the opponent, so the reach of each card is the largest
    # distance it was ever seen resolving at. Across the corpus:
    #
    #   reductions       Sidestep 599, Quick Dodge 597, Jump 506, Dash 481,
    #                    Zig-Zag Ruse 468, Artful Evasion 462
    #   ranged utility   Take Aim 148 (p99 140), Think 111
    #   weapon attacks   Sting 57, Full Circle 56, Cleave 56, Flex 55, Opp Knocks 53
    #   unarmed attacks  Knock Its Teeth Out 31, Punch 20
    #
    # The reductions are not "reaching" six hundred units - they act on yourself and have
    # no range requirement at all, so the figure is just wherever the opponent happened to
    # be. That is the point: a reduction can be thrown from anywhere, an attack cannot.
    # Reading it the other way round - melee by default unless the text says otherwise -
    # was wrong, and the text is the weaker source here than the measurement.
    #
    # So the cost of a reduction is not that you must stand in reach. It is the tick it
    # spends. What the reach buys is the in-and-out game: back off, drop a reduction from
    # outside their attack range, come back. Two players move at the same speed, so
    # whoever turns first has the reaction gap - unavailable in a tight den, against
    # something faster, or body blocked, but usually available in PVP.
    #
    # The simulator has no notion of standing apart at all, which is the same hole that
    # leaves four of fourteen learned policy rules unreadable.
    ("Take Aim", None, "reaches about 148 where an attack reaches about 55"),
    ("Steal Thunder", None,
     "text says a small distance; never thrown in the corpus, so unmeasured"),
    ("(attacks generally)", None,
     "bounded at roughly 55 units with a weapon and 20 to 31 unarmed, while a reduction "
     "has no range requirement - none of which the simulator can express"),
    ("Opportunity Knocks", "boost_greatest", "raises the opponent's greatest opening"),
    ("Quick Barrage", "gain_when_above", "gains initiative when the opponent is open"),
    ("Shield Up", "block_mult_without", "half the block weight without a shield"),
    ("Take Aim", "ip_scale", "cooldown rises 20% per point of initiative"),
    ("Combat Meditation", "attack_mult", "a quarter of the normal attack weight"),
    ("Oak Stance", "attack_mult", "half the normal attack weight"),
]


def text_mechanics():
    """How much of what the cards say about themselves the model actually has."""
    print("")
    print("what the card text says, against whether anything can hold it")
    gaps = [(c, w) for c, f, w in TEXT_MECHANICS if f is None]
    print("  %d of %d documented mechanics have no field at all:"
          % (len(gaps), len(TEXT_MECHANICS)))
    for c, w in gaps:
        print("    %-20s %s" % (c, w))
    # Not an assertion that the number is zero - it is not, and pretending otherwise
    # would be worse than recording it. The assertion is that the list is maintained:
    # if it ever shrinks to nothing the check should be deleted, and if a mechanic is
    # implemented its entry must gain a field name.
    check("the unmodelled list is still being kept", len(gaps) > 0, True)
    # Feigned Dodge is the one to watch: it is in nearly every deck the optimizer
    # recommends and the half that is missing is the ATTACKING half, so the card is
    # undervalued rather than overvalued. The decks are not wrong to hold it; they are
    # holding it for less than it is worth.
    check("  and Feigned Dodge is on it, being in nearly every recommended deck",
          any(c == "Feigned Dodge" for c, _w in gaps), True)


def how_thin_is_the_damage():
    """How much of the roster's damage figure is actually measured.

    OUR OWN ARMOUR IS WHAT BLINDS THE CORPUS HERE. A creature's damage coefficient is
    fitted to blows that took soft hitpoints, and through 79 points of hard soak most
    creature blows take about one - so the better the gear, the fewer observations there
    are to fit. It is a measurement problem created by success.

    The boreworm is the case worth carrying: one observation, coefficient 13.7, and the
    safest-aim report predicts 33 hitpoints lost where four logged fights cost a median
    of 4 and a worst of 9. The prediction is not merely thin, it is four to eight times
    pessimistic, and nothing in the output said so until it was asked.
    """
    print("")
    print("how much of the damage model is actually measured")
    path = os.path.join(_est3root(), "data", "combat", "opponents.json")
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            doc = _json4.load(f)
    except (OSError, ValueError):
        return
    none_at_all, thin, solid = [], [], []
    for v in doc.get("opponents") or []:
        if v.get("kind") != "creature":
            continue
        th = v.get("threat")
        if not th:
            continue
        n = ((th.get("damage") or {}).get("n"))
        if not n:
            none_at_all.append(v["name"])
        elif n <= 3:
            thin.append(v["name"])
        else:
            solid.append(v["name"])
    tot = len(none_at_all) + len(thin) + len(solid)
    print("  %d creature(s) with a threat model: %d never seen landing a blow, "
          "%d resting on 3 or fewer, %d better than that"
          % (tot, len(none_at_all), len(thin), len(solid)))
    # WAS "thin for most of the roster", AND THE FIX MADE THAT FALSE. The fit used to
    # count only the soft hitpoints a blow took and throw the soaked part away, which
    # through heavy armour is most of the blow - so it measured the residue our own gear
    # let past, and the better the gear the less there was to fit. Counting the whole
    # swing took the corpus from 408 usable observations to 850, and the roster from 13
    # never-seen and 14 thin against 17 measured, to 11 and 4 against 29.
    #
    # The assertion now points the other way, so that losing the ground again fails here
    # rather than quietly returning to guesswork.
    check("most of the roster's damage is measured rather than guessed",
          len(solid) > (len(none_at_all) + len(thin)), True)
    check("  and the deck report says so per opponent",
          "health column is a guess" in _read_tool("tools/CombatDeckSearch.java"), True)


def _est3root():
    import estimate as _e
    return _e.ROOT


def _read_tool(rel):
    try:
        with open(os.path.join(_est3root(), rel), "r", encoding="utf-8",
                  errors="replace") as f:
            return f.read()
    except OSError:
        return ""


import json as _json4  # noqa: E402


def animal_cards():
    """The other side, card by card, instead of one averaged action.

    Our side has always been modelled per card and theirs was a single aggregate - a
    clock, a per-colour pressure, one damage coefficient - because that was all the data
    supported. It is not all it supports now, and every one of these spreads is wide
    enough that an average of it describes a creature that does not exist:

        damage        Shredding Paw 143 against Vampirism 9, sixteen times
        restoration   Swift Evasion 0.30 against Rampant Rage 0.08, nearly four times
        grievous      three cards do any at all; every other reads exactly zero
        armour        most soaked at 0.80 to 0.83, Ant Spit alone at 0.50

    The cooldowns come from creatures acting as soon as they can, so the floor of the gap
    between two uses of one card IS its cooldown. Quick Barrage is the check on that: it
    is the one card here whose base is known, because it is ours, and it reads 18 against
    a listed 20 - the difference being the agility factor.

    What is NOT settled is the scale of the openings. The fit has a gauge freedom, so
    those are ratios; they land near multiples of five, which is suggestive and is not a
    measurement.
    """
    print("")
    print("the other side, card by card")
    path = os.path.join(_est3root(), "data", "combat", "animal_moves_measured.json")
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            doc = _json4.load(f)
    except (OSError, ValueError):
        check("animal_moves_measured.json is present", False, True)
        return
    moves = dict((m["name"], m) for m in doc.get("moves") or [])
    check("cards measured", len(moves) >= 20, True)
    check("  species factors, which the openings are relative to",
          len(doc.get("species_factor") or {}) >= 30, True)

    def field(nm, key, sub):
        m = moves.get(nm) or {}
        return ((m.get(key) or {}).get(sub))

    # The cooldown method, checked against the one card whose real base we know.
    check("Quick Barrage's cooldown reads near its listed 20",
          18 <= field("Quick Barrage", "cooldown", "ticks") <= 21, True)
    check("  and Fell Scratch, the most observed, lands on a round 41",
          40 <= field("Fell Scratch", "cooldown", "ticks") <= 42, True)

    # The spreads, which are the whole reason for going per card.
    check("damage spans more than tenfold across cards",
          field("Shredding Paw", "damage", "coef")
          > (10 * field("Vampirism", "damage", "coef")), True)
    check("restoration spans more than threefold",
          field("Swift Evasion", "restores", "share")
          > (3 * field("Rampant Rage", "restores", "share")), True)

    # Grievous is the sharpest: three cards, and the rest are not merely small, they are
    # zero. A per-creature rate would smear those three across everything.
    hurts = [nm for nm, m in moves.items()
             if (m.get("grievous") or {}).get("per_soft", 0) > 0]
    check("only a few cards leave a lasting wound", sorted(hurts),
          ["Blood & Gore", "Chomp", "Shredding Paw"])

    # And the penetration outlier, matched on swing size so size cannot explain it.
    check("Ant Spit gets through where the others do not",
          field("Ant Spit", "armour", "soaked_share")
          < (0.7 * field("Fell Scratch", "armour", "soaked_share")), True)


def main():
    primitives()
    animal_cards()
    how_thin_is_the_damage()
    every_key_is_read()
    text_mechanics()
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
