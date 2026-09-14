"""Fit when an animal gives up: a flat hitpoint fraction, a damage-parity accumulator, or both.

The wiki publishes a Fleeing-HitPoints value per creature and the pack publishes
`flees_below` (the share of hitpoints remaining at flight). Both are FLAT thresholds: flee
once your health drops below a constant. The live game does not read like one. A creature
that is winning stays; a creature that takes a beating and has hurt you little leaves. That
is a PARITY register -- it rises when the creature deals damage, falls when it takes damage --
not a health line.

This measures which statistic is actually concentrated at the moment of flight, per
individual:

    flat     damage taken                     (a health threshold)
    parity   damage taken - damage dealt       (the accumulator)
    share    taken / (taken + dealt)          (a normalized parity)
    fraction taken / its eventual total       (what `flees_below` already reports)

A threshold that predicts flight makes the statistic tight across individuals; one that does
not leaves it as wide as the whole fight. The relative interquartile width is the reading, and
the same statistics are computed at a placebo tick (the first blow landed on the creature) as
the control: no statistic should be concentrated there.

Run: python tools/combat/aggression.py
"""
import collections
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate
import fightlog


def _iqr_width(v):
    v = sorted(v)
    if len(v) < 4:
        return None
    med = statistics.median(v)
    if med == 0:
        return None
    q1 = v[len(v) // 4]
    q3 = v[(3 * len(v)) // 4]
    return (q3 - q1) / float(med)


def collect():
    """Every single-opponent flight as a tuple.

    (species, taken, dealt, total, taken_half, dealt_half) where _half is read at the tick
    the creature had taken half of what it had taken by flight -- a real placebo tick with
    both blows and our damage present, unlike the first blow where `dealt` is zero by
    construction.
    """
    rows = []
    paths, _dirs = fightlog.default_logs(estimate.ROOT)
    for p in sorted(paths):
        try:
            log = fightlog.read(p, None)
        except Exception:
            continue
        me = log.me
        if me is None or not log.engagements:
            continue
        for eng in log.engagements:
            if eng.multi_opponent or eng.others_present:
                continue
            dmg = [d for d in log.rows if d.get("ev") == "dmg"]
            flee = None
            for st in (getattr(eng, "states", None) or ()):
                if (st.get("gst") or 0) & 2:
                    flee = st.get("t")
                    break
            if flee is None:
                continue
            taken = [d for d in dmg
                     if d.get("gob") == eng.gob and d.get("ch") in ("SHP", "ARM")]
            if not taken:
                continue
            total = sum((d.get("v") or 0) for d in taken)
            if total <= 0:
                continue
            t_flee = min(flee, max(d["t"] for d in taken))
            at = sum((d.get("v") or 0) for d in taken if d["t"] <= t_flee)
            if at <= 0 or at >= total:
                continue
            t_half = None
            run = 0.0
            for d in sorted(taken, key=lambda x: x["t"]):
                run += d.get("v") or 0
                if run >= at / 2.0:
                    t_half = d["t"]
                    break
            if t_half is None:
                continue
            half = sum((d.get("v") or 0) for d in taken if d["t"] <= t_half)
            dealt = sum((d.get("v") or 0) for d in dmg
                        if d.get("gob") == me and d.get("ch") in ("SHP", "ARM")
                        and d["t"] <= t_flee)
            dealt_half = sum((d.get("v") or 0) for d in dmg
                             if d.get("gob") == me and d.get("ch") in ("SHP", "ARM")
                             and d["t"] <= t_half)
            rows.append(((eng.res or "?").split("/")[-1], at, dealt, total,
                         half, dealt_half))
    return rows


def _stat(sp, at, dealt, total, first):
    return {"flat": float(at), "parity": float(at - dealt),
            "share": at / float(at + dealt) if (at + dealt) else 0.0,
            "fraction": at / float(total)}


def _fit(rows, which):
    """Least squares taken = a*dealt + b over the flights; a~0 is flat, a~1 is parity."""
    xs = [float(r[2]) for r in rows]
    ys = [float(r[1]) for r in rows]
    n = len(xs)
    if n < 4:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    den = sum((x - mx) ** 2 for x in xs)
    if den <= 0:
        return None
    a = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den
    b = my - a * mx
    res = [y - (a * x + b) for x, y in zip(xs, ys)]
    mad = statistics.median(abs(r) for r in res)
    return a, b, mad


def main():
    rows = collect()
    print("aggression fit: %d single-opponent flights" % len(rows))
    if len(rows) < 4:
        print("  too few flights; nothing to read")
        return 0
    stats = collections.defaultdict(list)
    placebo = collections.defaultdict(list)
    for sp, at, dealt, total, half, dealt_half in rows:
        for k, v in _stat(sp, at, dealt, total, half).items():
            stats[k].append(v)
        for k, v in _stat(sp, half, dealt_half, total, half).items():
            placebo[k].append(v)
    print("\n  statistic   rel-IQR at flight   rel-IQR at placebo (half-way to flight)")
    for k in ("flat", "parity", "share", "fraction"):
        w = _iqr_width(stats[k])
        pw = _iqr_width(placebo[k])
        print("  %-10s  %-18s  %s" %
              (k, ("%.3f" % w) if w is not None else "-",
               ("%.3f" % pw) if pw is not None else "-"))
    print("\n  median at flight: flat=%.1f parity=%.1f share=%.3f fraction=%.3f"
          % (statistics.median(stats["flat"]), statistics.median(stats["parity"]),
             statistics.median(stats["share"]), statistics.median(stats["fraction"])))
    print("  median at placebo: flat=%.1f parity=%.1f share=%.3f fraction=%.3f"
          % (statistics.median(placebo["flat"]), statistics.median(placebo["parity"]),
             statistics.median(placebo["share"]), statistics.median(placebo["fraction"])))
    # POOLED ACROSS SPECIES THE COMPARISON IS DECIDED BEFORE ANY DATA IS READ. `flat` and
    # `parity` are in hitpoints, which run from 60 to over 1,200 across these species, and
    # `fraction` divides that out - so pooled, `fraction` is tight and the other two are wide
    # by construction. The fair reading is WITHIN a species: each statistic's spread among
    # individuals of one species, weighted by how many flights that species has.
    def _width(v):
        v = sorted(v)
        if len(v) < 4:
            return None
        med = statistics.median(v)
        return None if med == 0 else (v[(3 * len(v)) // 4] - v[len(v) // 4]) / abs(med)
    groups = collections.defaultdict(list)
    for r in rows:
        groups[r[0]].append(r)
    within = collections.defaultdict(lambda: [0.0, 0, 0.0, 0])
    for sp, g in groups.items():
        if len(g) < 5:
            continue
        for k in ("flat", "parity", "fraction"):
            w = _width([_stat(r[0], r[1], r[2], r[3], r[4])[k] for r in g])
            pw = _width([_stat(r[0], r[4], r[5], r[3], r[4])[k] for r in g])
            if w is not None:
                within[k][0] += w * len(g)
                within[k][1] += len(g)
            if pw is not None:
                within[k][2] += pw * len(g)
                within[k][3] += len(g)
    print("\n  WITHIN species (>=5 flights), count-weighted rel-IQR:")
    print("  statistic   at flight           at placebo")
    for k in ("flat", "parity", "fraction"):
        s, n, ps, pn = within[k]
        print("  %-10s  %-18s  %s   over %d flight(s)"
              % (k, ("%.3f" % (s / n)) if n else "-", ("%.3f" % (ps / pn)) if pn else "-", n))
    pooled = _fit(rows, "all")
    if pooled:
        a, b, mad = pooled
        flat_med = statistics.median([float(r[1]) for r in rows])
        flat_mad = statistics.median(abs(float(r[1]) - flat_med) for r in rows)
        print("\n  pooled fit: taken = %.3f*dealt + %.1f  (residual MAD %.1f)"
              % (a, b, mad))
        print("  flat model residual MAD = %.1f  (parity better if %.1f < %.1f)"
              % (flat_mad, mad, flat_mad))
        print("  reading: slope a~0 -> flat health threshold; a~1 -> damage-parity")
        import random
        rng = random.Random(1155)
        xs = [float(r[2]) for r in rows]
        ys = [float(r[1]) for r in rows]
        nul = []
        for _ in range(400):
            sh = list(xs)
            rng.shuffle(sh)
            mx, my = sum(sh) / len(sh), sum(ys) / len(ys)
            den = sum((x - mx) ** 2 for x in sh)
            if den > 0:
                nul.append(sum((x - mx) * (y - my) for x, y in zip(sh, ys)) / den)
        if nul:
            nul.sort()
            print("  permuted slope: p2.5=%.3f p50=%.3f p97.5=%.3f (observed %.3f)"
                  % (nul[len(nul) // 40], nul[len(nul) // 2],
                     nul[-max(1, len(nul) // 40)], a))
    bysp = collections.defaultdict(list)
    for r in rows:
        bysp[r[0]].append(r)
    print("\n  per species with >=5 flights: slope on dealt")
    for sp in sorted(bysp, key=lambda k: -len(bysp[k])):
        g = bysp[sp]
        if len(g) < 5:
            continue
        f = _fit(g, sp)
        if f:
            print("    %-14s n=%-3d slope=%6.3f intercept=%7.1f"
                  % (sp[:14], len(g), f[0], f[1]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
