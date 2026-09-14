"""Test whether recorded HHP (ch 0xfc0f) tracks the damage dealt or is a fixed figure per attacker.

The logs carry the HHP channel but nothing in the pipeline consumes it. `dmg` rows name the
entity that took the damage, and HHP rows sit on the logged character -- the hard-hitpoint
damage the enemy deals us. Two shapes are possible:

    HHP constant per (creature, move), independent of dealt   -> a fixed basis (e.g. max HP)
    HHP rises with the soft damage at the same tick           -> a share of damage dealt

The "percentage of MAX HP" reading was attributed to a 2017 dev note with no source in this
repo, and "300 * 0.3 = 90" does not say max HP in any case -- 300 could be the hit. So this
tests the shape, not a quotation. No MHP value is needed: the within-group spread of HHP
answers it, and a permutation null (dealt shuffled) calibrates the slope.

WHAT IT CAN AND CANNOT SAY. It measures animal attacks on US; Sim.strike prices OUR weapons'
grievous. The mechanic is plausibly the same, but that is an extrapolation. The pct itself is
poorly recovered: HHP is a whole number with a floor of 1, so on small hits the ratio reads
high. Hence only files with a single opponent are used (an HHP tick is attributed to the last
foe move before it, which in a crowd could be anyone's), and the ratio is also reported on
hits big enough for the floor not to dominate.

Run: python tools/combat/grievous_basis.py
"""
import collections
import json
import os
import random
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate
import fightlog

CREATURES = os.path.join(estimate.ROOT, "data", "combat", "creatures.json")


def _wiki_mhp():
    try:
        with open(CREATURES, "r", encoding="utf8") as f:
            rows = json.load(f)
    except (OSError, ValueError):
        return {}
    out = {}
    for r in rows if isinstance(rows, list) else []:
        hp = (r.get("hp") or {}).get("value")
        if r.get("name") and hp:
            out[str(r["name"]).lower()] = float(hp)
    return out


def _slope(xs, ys):
    n = len(xs)
    if n < 3:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    den = sum((x - mx) ** 2 for x in xs)
    if den <= 0:
        return None
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den


def measure():
    """Each HHP tick on the player as (species, foe_move, hhp, dealt_shp_at_same_tick).

    Single-opponent files only; returns (rows, files skipped for having several foes)."""
    rows = []
    crowded = 0
    paths, _dirs = fightlog.default_logs(estimate.ROOT)
    for p in sorted(paths):
        try:
            log = fightlog.read(p, None)
        except Exception:
            continue
        me = log.me
        if me is None:
            continue
        foe_of = {}
        for r in log.rows:
            if r.get("ev") == "foe" and r.get("res"):
                foe_of[r["gob"]] = r["res"].split("/")[-1]
        moves = [(r["t"], r.get("actor"), r.get("name") or r.get("move"), r.get("gob"))
                 for r in log.rows if r.get("ev") == "move"]
        dmg = [r for r in log.rows if r.get("ev") == "dmg"]
        if not any((r.get("ch") == "HHP") and (r.get("gob") == me) for r in dmg):
            continue
        if len(set(gob for _t, actor, _n, gob in moves if actor == "foe")) != 1:
            crowded += 1
            continue
        for r in dmg:
            if r.get("ch") != "HHP" or r.get("gob") != me:
                continue
            t, v = r["t"], (r.get("v") or 0)
            if v <= 0:
                continue
            dealt = sum((x.get("v") or 0) for x in dmg
                        if x.get("ch") == "SHP" and x.get("gob") == me and x["t"] == t)
            sp = mv = None
            for mt, actor, name, gob in moves:
                if mt <= t:
                    if actor == "foe":
                        sp, mv = foe_of.get(gob, "?"), name
                else:
                    break
            rows.append((sp, mv, float(v), float(dealt)))
    return rows, crowded


def main():
    rows, crowded = measure()
    print("grievous basis: %d HHP ticks on the logged character, single-opponent files only"
          " (%d crowded file(s) with HHP skipped)" % (len(rows), crowded))
    if not rows:
        print("  no HHP rows; nothing to read")
        return 0
    groups = collections.defaultdict(list)
    for sp, mv, v, dealt in rows:
        groups[(sp, mv)].append((v, dealt))
    thick = {k: g for k, g in groups.items() if len(g) >= 3}
    print("  %d (species, move) groups, %d with n>=3" % (len(groups), len(thick)))
    print("\nwithin-group: is HHP constant (fixed basis) or a slope on dealt (dealt basis)?")
    print("  %-28s %-16s %4s %7s %7s %8s %8s %7s" %
          ("species", "move", "n", "med_hhp", "med_dealt", "spread", "slope", "range"))
    rng = random.Random(1077)
    sharp = 0
    for (sp, mv) in sorted(thick, key=lambda k: -len(thick[k]))[:24]:
        g = thick[(sp, mv)]
        v = [a for a, _b in g]
        d = [b for _a, b in g]
        const = max(v) - min(v)
        s = _slope(d, v)
        spread = (max(v) - min(v)) / (statistics.median(v) or 1.0)
        if const == 0:
            sharp += 1
        print("  %-28s %-16s %4d %7.1f %7.2f %8.2f %8s %7.2f" %
              (str(sp)[:28], str(mv)[:16], len(g), statistics.median(v),
               statistics.median(d), spread,
               ("%.3f" % s) if s is not None else "-", const))
    print("\n  groups where HHP never varies: %d/%d" % (sharp, len(thick)))
    xs = [b for _s, _m, _v, b in rows]
    ys = [v for _s, _m, v, _b in rows]
    obs = _slope(xs, ys)
    nulls = []
    for _ in range(200):
        sh = list(xs)
        rng.shuffle(sh)
        ns = _slope(sh, ys)
        if ns is not None:
            nulls.append(ns)
    if obs is not None and nulls:
        p95 = statistics.quantiles(nulls, n=20)[18] if len(nulls) >= 20 else None
        print("  pooled slope of HHP on dealt = %.4f (null p95 = %s)"
              % (obs, ("%.4f" % p95) if p95 is not None else "-"))
    mhp = _wiki_mhp()
    print("  species with a wiki MHP represented: %d" % len(set(
        sp for sp, _m, _v, _b in rows if sp and sp.lower() in mhp)))
    ratios = [v / d for _s, _m, v, d in rows if d > 0]
    big = [v / d for _s, _m, v, d in rows if d >= 10]
    print("  hhp/dealt when dealt>0: median %.3f, n=%d  (small hits read high: HHP floors at 1)"
          % (statistics.median(ratios) if ratios else -1.0, len(ratios)))
    print("  hhp/dealt when dealt>=10: median %.3f, n=%d  (the floor no longer dominates)"
          % (statistics.median(big) if big else -1.0, len(big)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
