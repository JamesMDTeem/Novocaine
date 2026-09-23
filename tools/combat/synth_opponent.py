#!/usr/bin/env python3
"""Derive a pack whose extra creature is a creature we HAVE met, resized.

A pack entry is an answer the estimator produced from fights. A creature nobody in the
corpus has fought has no such entry and cannot be given one honestly - so this does not
invent one. It copies an existing creature's entry whole, replaces the two numbers the
player actually knows (hitpoints and armour), renames it, and writes the result to a
derived pack directory that the tools read with -pack.

Everything else in that entry - the cards it throws and in what mix, its attack period,
its pressure by colour, its damage coefficient, its skill and agility bands - still
belongs to the creature it was copied from, and the run says so out loud rather than
letting a borrowed number read as a measured one.

    python tools/combat/synth_opponent.py cachalot --from narwhal --hp 50000 --armour 150
    python tools/combat/synth_opponent.py cachalot --from orca    --hp 50000 --armour 150

Armour is written the way the corpus writes an unidentified one - a total, with the
hard/soft split left null - because that is how narwhal, orca, walrus, mammoth and troll
are all carried. Pack.build() then charges the whole total as hard soak, which is the
pessimistic reading and the one those creatures already get.

The other pack files are not copied. They are linked, or copied where the filesystem
refuses a link, so a derived pack costs one rewritten opponents.json and not 6000 logs.
"""

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

SIBLINGS = ("animal_moves_measured.json", "moves_sheet.json", "moves_ingame.json",
            "individuals.json", "characters.json", "constants.json", "weapons.json",
            "weapons_seen.json", "weapon_classes.json", "armor.json", "creatures.json",
            "moves.json", "animal_moves.json", "creature-notes.json", "spar-tests.json",
            "golden-vectors.json")


def resize(entry, name, hp, armour):
    """The copied entry with its identity, hitpoints and armour replaced."""
    e = json.loads(json.dumps(entry))          # a real copy; entries share nothing
    borrowed = e["name"]
    e["name"] = name
    if e.get("res"):
        e["res"] = "gfx/kritter/%s/%s" % (name, name)
    e["borrowed_from"] = borrowed

    # One value, not a band: the player states it, so both ends of the plan are the same
    # fight and the envelope the tools print collapses to skill and agility - which is
    # exactly the part that is still borrowed.
    e["hitpoints"] = {
        "lo": hp, "hi": hp, "observed_lo": hp, "observed_hi": hp,
        "pinned_lo": None, "pinned_hi": None, "pinned_n": 0, "floor_kills": 0,
        "wiki": hp, "from": "stated by the player, not measured",
        "verdict": "stated as %g; nothing in the corpus has fought this creature" % hp,
    }
    e["armour"] = {
        "total_lo": armour, "total_hi": armour,
        # Unidentified on purpose - see the module docstring.
        "identified": False, "hard": None, "soft": None,
        "n": 0, "wiki": armour,
    }
    return e


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("name", help="what to call the new creature")
    ap.add_argument("--from", dest="template", required=True,
                    help="the creature whose entry is copied")
    ap.add_argument("--hp", type=float, required=True)
    ap.add_argument("--armour", type=float, required=True)
    ap.add_argument("--pack", default=os.path.join("data", "combat"),
                    help="the pack to derive from (default data/combat)")
    ap.add_argument("--out", required=True, help="the derived pack directory to write")
    a = ap.parse_args(argv)

    src = Path(a.pack)
    out = Path(a.out)
    doc = json.loads((src / "opponents.json").read_text(encoding="utf-8"))
    by = {o["name"]: o for o in doc["opponents"]}
    if a.template not in by:
        print("no creature %r in %s" % (a.template, src / "opponents.json"), file=sys.stderr)
        return 2
    if a.name in by:
        print("%r is already in the pack - name the synthetic one something else"
              % a.name, file=sys.stderr)
        return 2

    doc["opponents"].append(resize(by[a.template], a.name, a.hp, a.armour))
    doc["synthetic"] = {
        a.name: {"copied_from": a.template, "hitpoints": a.hp, "armour": a.armour,
                 "note": "cards, period, pressure, damage, skill and agility are %s's"
                         % a.template},
    }

    out.mkdir(parents=True, exist_ok=True)
    (out / "opponents.json").write_text(json.dumps(doc, indent=1, sort_keys=True),
                                        encoding="utf-8")
    for f in SIBLINGS:
        s, d = src / f, out / f
        if not s.exists() or d.exists():
            continue
        try:
            os.link(s, d)
        except OSError:
            shutil.copyfile(s, d)
    pool, dpool = src / "pool", out / "pool"
    if pool.is_dir() and not dpool.exists():
        try:
            os.symlink(pool.resolve(), dpool, target_is_directory=True)
        except OSError:
            # Windows without developer mode refuses a symlink; a junction is allowed.
            os.system('cmd /c mklink /J "%s" "%s" >nul' % (dpool, pool.resolve()))

    print("%s: %s with %g hitpoints and %g armour -> %s"
          % (a.name, a.template, a.hp, a.armour, out / "opponents.json"))
    print("  borrowed from %s: cards and mix, attack period, pressure by colour, damage"
          % a.template)
    print("  coefficient, skill and agility. Only hitpoints and armour are yours.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
