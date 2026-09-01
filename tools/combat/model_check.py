#!/usr/bin/env python3
"""Holds the Python model to the golden vectors generated from the Java one.

    python tools/combat/model_check.py

ADR-0002: Java is authoritative, Python follows. This is the check that keeps the
follower honest. Regenerate the vectors with tools/CombatVectorGen.java after any
change to Formulas.java, then run this; a failure here means the two have drifted,
and the Java is right by construction.

Exits 0 when every vector matches, 1 otherwise.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
VECTORS = os.path.join(ROOT, "data", "combat", "golden-vectors.json")

# Doubles that took the same route through both languages should agree to the last
# few bits. Anything looser would hide a real formula difference.
TOL = 1e-9

DISPATCH = {
    "rawDamage": lambda a: model.raw_damage(a[0], a[1], a[2], a[3], a[4]),
    "dealtDamage": lambda a: model.dealt_damage(a[0], a[1], a[2], a[3]),
    "openingGain": lambda a: model.opening_gain(a[0], a[1], a[2], a[3]),
    "defenceWeight": lambda a: model.defence_weight(a[0], a[1], a[2], a[3]),
    "combined": lambda a: model.combined(a),
    "agilityCooldownFactor": lambda a: model.agility_cooldown_factor(a[0], a[1]),
    "cooldownTicks": lambda a: model.cooldown_ticks(a[0], bool(a[1]), a[2], a[3],
                                                    int(a[4]), bool(a[5]), a[6], a[7]),
    "muFromCooldown": lambda a: model.mu_from_cooldown(a[0], a[1], a[2], int(a[3])),
    "ticksToSeconds": lambda a: model.ticks_to_seconds(a[0]),
}


def main():
    if not os.path.exists(VECTORS):
        print("no golden vectors at %s" % os.path.relpath(VECTORS, ROOT))
        print("generate them with tools/CombatVectorGen.java first")
        return 2
    with open(VECTORS, "r", encoding="utf8") as f:
        doc = json.load(f)
    vectors = doc.get("vectors") or []
    if doc.get("count") != len(vectors):
        print("vector file says %s vectors and carries %d" % (doc.get("count"), len(vectors)))
        return 1

    # Every function the Java side emits must be covered here. A vector file naming a
    # function this script does not know is a new formula with no follower, which must
    # fail rather than be skipped.
    seen = set(v["fn"] for v in vectors)
    missing = sorted(seen - set(DISPATCH))
    if missing:
        print("no Python implementation for: %s" % ", ".join(missing))
        return 1
    untested = sorted(set(DISPATCH) - seen)

    failures, worst, worst_at = 0, 0.0, None
    for v in vectors:
        got = DISPATCH[v["fn"]](v["args"])
        want = v["want"]
        if want is None:
            ok = got is None
            d = 0.0
        else:
            d = abs(float(got) - float(want))
            ok = d <= TOL
        if d > worst:
            worst, worst_at = d, v
        if not ok:
            failures += 1
            if failures <= 12:
                print("  MISMATCH %-24s args=%s got=%r want=%r"
                      % (v["fn"], v["args"], got, want))

    by_fn = {}
    for v in vectors:
        by_fn[v["fn"]] = by_fn.get(v["fn"], 0) + 1
    for fn in sorted(by_fn):
        print("  %-24s %5d vector(s)" % (fn, by_fn[fn]))
    if untested:
        print("  (no vectors cover: %s)" % ", ".join(untested))
    print("\n  worst absolute difference %.3e%s"
          % (worst, "" if worst_at is None else " on %s%s" % (worst_at["fn"], worst_at["args"])))

    if failures:
        print("\n%d of %d vector(s) FAILED" % (failures, len(vectors)))
        return 1
    print("\nALL %d VECTORS MATCH" % len(vectors))
    return 0


if __name__ == "__main__":
    sys.exit(main())
