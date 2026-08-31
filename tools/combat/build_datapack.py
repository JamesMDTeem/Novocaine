# Builds the whole combat data pack and cross-checks it against the client and against itself.
#
#   python tools/combat/build_datapack.py
#
# Runs every parser, then runs a set of cross-checks that only make sense once every parser's
# output exists: the four opening resource names against the client's own res/paginae/atk/
# directory, the creature-move vocabulary against the animal-move catalogue (both directions),
# and the school-name vocabulary used across moves.json and animal_moves.json.
#
# Nothing is written unless every parser AND every cross-check comes back clean. A build that
# writes a known-incomplete data pack and only signals the problem via exit code is exactly the
# failure this whole plan exists to prevent -- so a failing build must leave whatever data pack
# was already on disk untouched, and print what went wrong before exiting 1.
#
# Where the wiki and the client disagree, THE CLIENT IS AUTHORITATIVE -- the wiki is a secondary
# source and is known wrong in at least one place.

import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki, parse_gear, parse_creatures, parse_animal_moves, parse_player_moves

ROOT = wiki.ROOT
OUT = wiki.DATA

# The four openings, as the client names them. Verified three separate ways against
# OptWnd.java and Openings.java.
OPENING_RES = {
    "striking": "offbalance",
    "backhanded": "dizzy",
    "sweeping": "reeling",
    "oppressive": "cornered",
}

# The only school names either catalogue is allowed to use.
SCHOOLS = {"striking", "backhanded", "sweeping", "oppressive"}


def cross_check():
    """Compare the data pack's opening vocabulary against the client's res tree."""
    problems = []
    atk = ROOT / "res" / "paginae" / "atk"
    if not atk.is_dir():
        return ["client res dir missing: %s" % atk]
    have = {p.stem for p in atk.glob("*.res")}
    for school, res in OPENING_RES.items():
        if res not in have:
            problems.append("client has no res for %s (%s.res); found %s"
                            % (school, res, sorted(have)))
    return problems


def schools_used(records):
    """Every school name referenced anywhere on the given records, across attack_types,
    openings and reduces -- the three fields that carry school vocabulary."""
    s = set()
    for r in records:
        s.update(r.get("attack_types") or [])
        s.update(r.get("openings") or [])
        s.update(r.get("reduces") or [])
    return s


def move_join_check(creatures, animal_records):
    """Every move a creature references must exist as an animal_moves.json record, AND every
    animal_moves.json record must be referenced by at least one creature. Checking only the
    first direction (a subset check) would silently tolerate orphaned move pages that nothing
    in creatures.json ever points at -- and this join is what makes per-creature policy work
    possible later, so both directions have to hold exactly."""
    problems = []
    creature_moves = {m for c in creatures for m in c["moves"]}
    animal_names = {r["name"] for r in animal_records}
    missing_records = sorted(creature_moves - animal_names)
    orphaned_records = sorted(animal_names - creature_moves)
    if missing_records:
        problems.append("creature moves with no animal_moves.json record: %s" % missing_records)
    if orphaned_records:
        problems.append("animal_moves.json records no creature references: %s" % orphaned_records)
    return problems


def school_vocab_check(player_sections, animal_records):
    """The set of school names appearing anywhere in moves.json and in animal_moves.json must
    be a subset of exactly SCHOOLS (catches a typo'd or stray school name), and both files must
    actually use all four -- a subset check alone would pass on a file that used none at all."""
    problems = []
    moves_schools = schools_used(sum(player_sections.values(), []))
    animal_schools = schools_used(animal_records)
    stray_moves = sorted(moves_schools - SCHOOLS)
    stray_animal = sorted(animal_schools - SCHOOLS)
    if stray_moves:
        problems.append("moves.json uses non-canonical school name(s): %s" % stray_moves)
    if stray_animal:
        problems.append("animal_moves.json uses non-canonical school name(s): %s" % stray_animal)
    unused_moves = sorted(SCHOOLS - moves_schools)
    unused_animal = sorted(SCHOOLS - animal_schools)
    if unused_moves:
        problems.append("moves.json never uses school(s): %s" % unused_moves)
    if unused_animal:
        problems.append("animal_moves.json never uses school(s): %s" % unused_animal)
    return problems


def main():
    problems = []

    weapons, wbad = parse_gear.parse_weapons()
    armor, abad = parse_gear.parse_armor()
    creatures, noinfo, cbad = parse_creatures.parse()
    animal, anbad, anmalformed, _anmismatches = parse_animal_moves.parse()
    player, pbad = parse_player_moves.parse()

    # noinfo (creature pages with no infobox: butterflies, chicks, etc) is EXPECTED and does
    # NOT fail the build. Only genuine parse failures do.
    for label, bad in (
        ("weapons", wbad),
        ("armor", abad),
        ("creatures (malformed infobox)", cbad),
        ("animal moves (unparsed)", anbad),
        ("animal moves (malformed)", anmalformed),
        ("player moves", pbad),
    ):
        if bad:
            problems.append("UNPARSED/MALFORMED %s: %s" % (label, bad))

    problems += move_join_check(creatures, animal)
    problems += school_vocab_check(player, animal)
    problems += cross_check()

    if problems:
        print("BUILD FAILED -- %d problem(s), nothing written:" % len(problems))
        for p in problems:
            print("  - %s" % p)
        sys.exit(1)

    OUT.mkdir(parents=True, exist_ok=True)
    writes = {
        "weapons.json": weapons,
        "armor.json": armor,
        "creatures.json": creatures,
        "animal_moves.json": animal,
        "moves.json": player,
    }
    for name, data in writes.items():
        (OUT / name).write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf8")
        n = len(data) if isinstance(data, list) else sum(len(v) for v in data.values())
        print("%-18s %3d records" % (name, n))
    print("%-18s %3d skipped (no infobox)" % ("creatures", len(noinfo)))
    print("cross-check      client res names agree with the data pack")
    sys.exit(0)


if __name__ == "__main__":
    main()
