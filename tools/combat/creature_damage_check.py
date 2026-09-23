#!/usr/bin/env python3
"""Creature blows on us, held out: does the pack's damage coefficient predict what landed?

    python tools/combat/creature_damage_check.py

replay.py's damage half controls OUR blows and owes nothing to a fitted quantity. A creature's blow
on us had no control at all, and it was wrong: the pack's coefficients were the median of
swing / combined^2 over blows that did damage, and blows into small openings round to 0 or 1 - the
zeros dropped, the ones inflated. Over the corpus the old coefficients predicted 1.24 times the
damage that landed (COMBAT.md §3.12).

This scores estimate.ratio_coef the way it has to be scored, on creatures it was not fitted on:
for each (species, card) with enough blows, the creatures are split in half by gob, the
coefficient fitted on one half and the other half's total damage predicted. Every clean
engagement's creature blows on us count, those that did nothing included.

Passes when the total prediction is within PASS_BAND of what landed.
"""

import os
import random
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402
import fightlog  # noqa: E402
from solo_lines import ROOT  # noqa: E402

PASS_BAND = (0.85, 1.15)
MIN_SIDE = 10


def blows():
    """(species, card, combined opening in the card's colours, swing, gob) for every clean blow on us."""
    # THE COLOURS THE PACK READS EACH CARD ON (animal_moves_measured.json), which is what the
    # model multiplies; the wiki's where the pack names none.
    import json
    meas = json.load(open(os.path.join(ROOT, "data", "combat", "animal_moves_measured.json"), encoding="utf-8"))
    rows = meas if isinstance(meas, list) else (meas.get("moves") or [v for v in meas.values() if isinstance(v, list)][0])
    cidx = {"g": 0, "b": 1, "y": 2, "r": 3}
    wiki = {}
    for r in estimate.animal_move_rows():
        cs = [estimate.ATTACK_TYPE_COLOUR[t] for t in (r.get("attack_types") or [])
              if t in estimate.ATTACK_TYPE_COLOUR]
        if cs:
            wiki[r["name"]] = cs
    for r in rows:
        cs = ((r.get("damage") or {}).get("colours")) if isinstance(r, dict) else None
        if cs:
            wiki[r["name"]] = [cidx[c] for c in cs]
    out = []
    pool = os.path.join(ROOT, "data", "combat", "pool")
    for name in sorted(os.listdir(pool)):
        if not name.endswith(".jsonl"):
            continue
        try:
            log = fightlog.read(os.path.join(pool, name))
        except Exception:
            continue
        for eng in log.engagements:
            if not eng.defence_ok or not eng.res or "kritter" not in eng.res:
                continue
            sp = eng.res.rsplit("/", 1)[-1]
            for h in fightlog.hits(eng, log.me):
                if h["actor"] == "me" or not h.get("move"):
                    continue
                o = [min(x, 100) / 100.0 for x in h["openings"]]
                idx = wiki.get(h["move"]) or (0, 1, 2, 3)
                p = 1.0
                for i in idx:
                    p *= (1.0 - o[i])
                out.append((sp, h["move"], 1.0 - p, (h["shp"] or 0) + (h["soaked"] or 0), eng.gob))
    return out


def main():
    rnd = random.Random(1)
    by = defaultdict(list)
    for b in blows():
        by[(b[0], b[1])].append(b)
    obs = pred = 0.0
    scored = 0
    for key in sorted(by):
        bs = by[key]
        gobs = sorted(set(b[4] for b in bs))
        rnd.shuffle(gobs)
        train = set(gobs[: len(gobs) // 2])
        tr = [(b[3], b[2]) for b in bs if b[4] in train and b[2] >= 0.05]
        te = [b for b in bs if b[4] not in train]
        if len(tr) < MIN_SIDE or len(te) < MIN_SIDE:
            continue
        k = estimate.ratio_coef(tr)
        if k is None:
            continue
        obs += sum(b[3] for b in te)
        pred += sum(k * b[2] ** 2 for b in te)
        scored += 1
    ratio = (pred / obs) if obs > 0 else float("nan")
    ok = PASS_BAND[0] <= ratio <= PASS_BAND[1]
    print("creature blows on us, held out by creature")
    print("  %-58s %-20s %s" % ("(species, card) pairs scored", scored, "ok" if scored >= 20 else "WANT >= 20"))
    print("  %-58s %-20s %s" % ("predicted / landed", "%.3f" % ratio,
                               "ok" if ok else "WANT %.2f-%.2f" % PASS_BAND))
    if ok and scored >= 20:
        print("\nALL CHECKS PASSED")
        return 0
    print("\n1 CHECK(S) FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
