#!/usr/bin/env python3
"""Read combat telemetry logs and report what they measured.

    python tools/combat/analyze_log.py bin/CombatLogs/*.jsonl

Stdlib only, like every other tool here. This reads logs; it never writes to the
data pack, and it never fills in a value the log did not record. Anything it
could not determine is printed as "?" rather than guessed.

Schema 1 logs (captures made before the begin/gear/end events existed) are read
too: they have no header, and their state field is "hp" rather than "hpf".
"""

import json
import sys
import glob
import os
from collections import defaultdict

# A move and the damage it caused arrive as separate messages a few milliseconds
# apart, in either order, so the pairing window is symmetric.
PAIR_MS = 150
# Openings settle within a client tick; a sample much later than this has had
# time to decay, which would understate the opening the move actually inflicted.
AFTER_MS = 600
# Armour is a flat subtraction, but a fraction of a hit always gets through. Points
# below this share of raw damage are treated as held up by that floor, not by the fit.
FLOOR = 0.30

COLOURS = ("green", "blue", "yellow", "red")


def load(path):
    rows, bad = [], 0
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except ValueError:
                bad += 1
    return rows, bad


def states(rows):
    return [r for r in rows if r.get("ev") == "state"]


def near_before(sts, t):
    best = None
    for s in sts:
        if s["t"] <= t:
            best = s
        else:
            break
    return best


def near_after(sts, t, window):
    for s in sts:
        if t < s["t"] <= t + window:
            return s
    return None


def header(rows):
    for r in rows:
        if r.get("ev") == "begin":
            return r
    return None


# The server sends every attribute the character has, farming and cooking included.
# These are the ones combat is a function of; the rest are counted, not listed.
ATTR_COMBAT = ("str", "agi", "con", "dex", "unarmed", "melee", "ranged", "hp", "hhp")


def report_header(rows, path):
    print("=" * 78)
    print(os.path.basename(path))
    print("=" * 78)
    h = header(rows)
    if h is None:
        print("  header       (none - schema 1 log, predates the begin event)")
    else:
        print("  schema       %s" % h.get("schema"))
        print("  character    %s  (gob %s)" % (h.get("char"), h.get("megob")))
        print("  opponent     %s  (gob %s)" % (h.get("foeres"), h.get("foegob")))
        if h.get("hard") == -1:
            print("  armour       ? (no equipment widget when the fight started)")
        else:
            print("  armour       %s hard + %s soft" % (h.get("hard"), h.get("soft")))
        attr = h.get("attr") or {}
        if attr:
            keys = [k for k in ATTR_COMBAT if k in attr]
            print("  attributes   " + ", ".join("%s=%s" % (k, attr[k]) for k in keys)
                  + "   (+%d others)" % (len(attr) - len(keys)))
    for g in [r for r in rows if r.get("ev") == "gear"]:
        mark = "  BROKEN" if g.get("broken") else ""
        print("  gear[%-2s]     %-40s q%-8.2f %s/%s%s"
              % (g.get("slot"), g.get("res"), g.get("ql") or 0,
                 g.get("hard"), g.get("soft"), mark))

    sts = states(rows)
    span = (rows[-1]["t"] - rows[0]["t"]) / 1000.0 if rows else 0
    end = [r for r in rows if r.get("ev") == "end"]
    print("  duration     %.1f s over %d events" % (span, len(rows)))
    print("  ended        %s" % (end[0].get("reason") if end
                                 else "? (no end event - log was cut off)"))
    if sts:
        print("  distance     %.1f -> %.1f (closest %.1f)"
              % (sts[0]["dist"], sts[-1]["dist"], min(s["dist"] for s in sts)))


def report_moves(rows):
    """Per move: how often, what the server said the cooldown was, and what the
    gap between consecutive uses actually was. The second is the check on the
    first, and on the wiki's ticks * 0.06 conversion."""
    moves = [r for r in rows if r.get("ev") == "move"]
    if not moves:
        return
    by = defaultdict(list)
    for m in moves:
        by[(m["actor"], m["move"], m.get("name"))].append(m)

    print("\n  moves")
    print("    %-6s %-28s %-24s %5s %11s %11s" %
          ("actor", "resource", "name", "n", "cd(ticks)", "gap(s)"))
    for (actor, res, name), ms in sorted(by.items(), key=lambda kv: -len(kv[1])):
        cds = sorted(set(m["cd"] for m in ms if m["cd"] >= 0))
        cdtxt = "?" if not cds else ",".join("%g" % c for c in cds)
        gaps = [(ms[i + 1]["t"] - ms[i]["t"]) / 1000.0 for i in range(len(ms) - 1)]
        gaptxt = "-" if not gaps else "%.2f-%.2f" % (min(gaps), max(gaps))
        print("    %-6s %-28s %-24s %5d %11s %11s"
              % (actor, res, name or "?", len(ms), cdtxt, gaptxt))

    # A reported cooldown is in server ticks of 0.06 s. If the shortest observed
    # gap between two uses of the same move matches it, the conversion holds.
    print("\n    cooldown check (shortest observed gap vs reported ticks * 0.06)")
    any_row = False
    for (actor, res, name), ms in sorted(by.items()):
        gaps = [(ms[i + 1]["t"] - ms[i]["t"]) / 1000.0 for i in range(len(ms) - 1)]
        cds = [m["cd"] for m in ms if m["cd"] >= 0]
        if not gaps or not cds:
            continue
        any_row = True
        predicted = min(cds) * 0.06
        observed = min(gaps)
        verdict = "ok" if observed >= predicted - 0.06 else "SHORTER THAN COOLDOWN"
        print("      %-24s predicted >= %.2f s, observed %.2f s   %s"
              % (name or res, predicted, observed, verdict))
    if not any_row:
        print("      (no move used twice with a reported cooldown)")

    # A move whose reported cooldown is not constant is measuring something the
    # move alone does not determine - worth knowing before fitting anything.
    varying = [(a, n or r, sorted(set(m["cd"] for m in ms if m["cd"] >= 0)))
               for (a, r, n), ms in sorted(by.items())]
    varying = [v for v in varying if len(v[2]) > 1]
    if varying:
        print("\n    moves whose reported cooldown varied between uses")
        for actor, name, cds in varying:
            print("      %-6s %-24s %s" % (actor, name, cds))


def report_effects(rows):
    """For every move, the opening it inflicted and the damage it did.

    This is the raw material the formulas are checked against: opening growth
    for a known attacker/defender pair, and damage dealt at a known opening."""
    sts = states(rows)
    if not sts:
        return
    dmgs = [r for r in rows if r.get("ev") == "dmg"]
    moves = [r for r in rows if r.get("ev") == "move"]
    if not moves:
        return

    print("\n  move effects  (openings g,b,y,r; the opened side is the foe for")
    print("                my moves and me for the foe's)")
    print("    %8s %-6s %-22s %-16s %-16s %-14s %s"
          % ("t(ms)", "actor", "move", "before", "after", "damage", "delta"))
    for m in moves:
        t = m["t"]
        b = near_before(sts, t)
        a = near_after(sts, t, AFTER_MS)
        if b is None:
            continue
        key = "foe" if m["actor"] == "me" else "mine"
        bv = b.get(key)
        av = a.get(key) if a else None
        delta = ""
        if bv and av:
            d = [av[i] - bv[i] for i in range(4)]
            delta = " ".join("%s%+d" % (COLOURS[i][0], d[i])
                             for i in range(4) if d[i])
        paired = [x for x in dmgs if abs(x["t"] - t) <= PAIR_MS]
        # Damage from my move lands on the foe and vice versa; the state event
        # names the foe's gob, so any other gob is me.
        foegob = b.get("gob")
        want_foe = (m["actor"] == "me")
        hits = [x for x in paired if (x["gob"] == foegob) == want_foe]
        dtxt = " ".join("%s%d" % (x["ch"], x["v"]) for x in hits) or "-"
        print("    %8d %-6s %-22s %-16s %-16s %-14s %s"
              % (t, m["actor"], (m.get("name") or m["move"].split("/")[-1])[:22],
                 ",".join(map(str, bv)) if bv else "?",
                 ",".join(map(str, av)) if av else "?",
                 dtxt, delta))


def combined(op):
    """Multiple colours combine as 1 - product(1 - o_i), per the wiki. With a single
    colour open this is just that colour."""
    p = 1.0
    for v in op:
        p *= (1.0 - v / 100.0)
    return 1.0 - p


def fit(pts):
    """Least squares of dealt against opening squared. Returns (C, A, R2)."""
    xs = [o * o for o, _ in pts]
    ys = [float(d) for _, d in pts]
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None
    c = sum((xs[i] - mx) * (ys[i] - my) for i in range(n)) / sxx
    b = my - c * mx
    res = sum((ys[i] - (c * xs[i] + b)) ** 2 for i in range(n))
    tot = sum((y - my) ** 2 for y in ys)
    return (c, -b, (1 - res / tot) if tot else float("nan"))


def fit_clamped(pts):
    """Fit C and A while dropping hits the armour floor was holding up.

    A flat subtraction cannot take a hit to zero - a fraction always gets through -
    so at low openings the observed damage stops following the line and the fit is
    dragged badly wrong. On a fight where 13 armour was known to be worn, fitting
    every point gave A = 6.1; dropping the two floor-bound ones gave 13.4.

    Returns (C, A, R2, dropped) where dropped are the excluded points."""
    keep, dropped = list(pts), []
    for _ in range(len(pts)):
        if len(keep) < 4:
            break
        f = fit(keep)
        if f is None:
            break
        c, a, _r2 = f
        # A point is floor-bound when the line would have put it at or below the
        # fraction that always gets through.
        out = [(o, d) for (o, d) in keep if (c * o * o - a) < FLOOR * c * o * o]
        if not out:
            break
        keep = [q for q in keep if q not in out]
        dropped += out
    f = fit(keep) if len(keep) >= 3 else None
    return (f, keep, dropped)


def report_damage_model(rows):
    """Fit dealt = C * o^2 - A over one fight, per move.

    The wiki's damage term is proportional to the square of the combined opening, and
    everything else in it - base damage, weapon quality, strength, mu - is constant
    within a fight, so it collapses into a single C for that matchup. Flat armour
    shows up as the intercept. Recovering A from damage alone is the armour estimator;
    recovering C is what a simulator needs in order to predict a hit."""
    sts = states(rows)
    h = header(rows)
    if not sts or not h:
        return
    foe = h.get("foegob")
    dmgs = [r for r in rows if r.get("ev") == "dmg"]
    pts = defaultdict(list)
    for m in [r for r in rows if r.get("ev") == "move" and r["actor"] == "me"]:
        b = near_before(sts, m["t"])
        if b is None or not b.get("foe"):
            continue
        hits = [x for x in dmgs if abs(x["t"] - m["t"]) <= PAIR_MS
                and x["gob"] == foe and x["ch"] in ("SHP", "HHP", "ARM")]
        if not hits:
            continue
        pts[m.get("name") or m["move"]].append(
            (combined(b["foe"]), sum(x["v"] for x in hits)))

    shown = False
    for name in sorted(pts):
        p = sorted(pts[name])
        if len(p) < 4:
            continue
        if not shown:
            print("")
            print("  damage model  dealt = C * opening^2 - A   (A is the defender's armour)")
            shown = True
        f, keep, dropped = fit_clamped(p)
        if f is None:
            continue
        c, a, r2 = f
        print("    %-22s n=%-3d C=%6.1f  A=%5.1f  R2=%.4f" % (name, len(keep), c, a, r2))
        print("      %s" % "  ".join("%.2f:%d" % (o, d) for o, d in p))
        if dropped:
            frac = ["%.0f%%" % (100.0 * d / (c * o * o)) for o, d in sorted(dropped)
                    if c * o * o > 0]
            print("      %d hit(s) excluded as floor-bound: %s; they let through %s of raw"
                  % (len(dropped), " ".join("%.2f:%d" % q for q in sorted(dropped)),
                     ", ".join(frac)))
    return shown


def report_damage(rows):
    sts = states(rows)
    dmgs = [r for r in rows if r.get("ev") == "dmg"]
    if not dmgs:
        return
    foegob = sts[0].get("gob") if sts else None
    tot = defaultdict(lambda: defaultdict(int))
    for d in dmgs:
        side = "foe" if d["gob"] == foegob else "me"
        tot[side][d["ch"]] += d["v"]
    print("\n  damage totals")
    for side in ("foe", "me"):
        if tot[side]:
            print("    dealt to %-4s %s" % (side, "  ".join(
                "%s=%d" % (c, v) for c, v in sorted(tot[side].items()))))


def report_anomalies(rows, bad):
    notes = []
    if bad:
        notes.append("%d unparseable line(s)" % bad)
    if header(rows) is None:
        notes.append("no begin event (schema 1 log): no character, opponent, "
                     "stats or gear recorded")
    if not any(r.get("ev") == "end" for r in rows):
        notes.append("no end event - treat this fight as incomplete")
    unknown = sorted(set(r["ch"] for r in rows if r.get("ev") == "dmg"
                         and (r["ch"].startswith("#") or r["ch"].startswith("C"))))
    for u in unknown:
        hits = [r for r in rows if r.get("ev") == "dmg" and r["ch"] == u]
        gloss = ""
        # The channel code is a packed RGBA4444 colour. White fires once per kill on
        # the killer's own gob, and scales with the prey - almost certainly combat
        # experience, but that is an inference, so it is said here and not in the log.
        if u in ("#ffff", "C65535"):
            gloss = " - opaque white; fires on a kill, likely combat experience"
        notes.append("undocumented damage channel %s: %d hit(s), values %s, at t=%s%s"
                     % (u, len(hits), sorted(set(h["v"] for h in hits)),
                        [h["t"] for h in hits], gloss))
    nameless = sorted(set(r["move"] for r in rows
                          if r.get("ev") == "move" and not r.get("name")))
    if nameless:
        notes.append("%d move resource(s) logged with no tooltip name: %s"
                     % (len(nameless), ", ".join(nameless)))
    print("\n  notes")
    if not notes:
        print("    none")
    for n in notes:
        print("    - " + n)


def collect(rows):
    """The per-fight facts the corpus view aggregates: reported cooldowns and, for
    every move, the opening it inflicted against the opening already standing."""
    h = header(rows)
    foe = (h or {}).get("foeres", "?")
    sts = states(rows)
    out = {"foe": foe, "cd": [], "open": []}
    for m in [r for r in rows if r.get("ev") == "move"]:
        if m["actor"] == "me" and m.get("cd", -1) >= 0:
            out["cd"].append((m.get("name") or m["move"], m["cd"]))
        b = near_before(sts, m["t"])
        a = near_after(sts, m["t"], AFTER_MS)
        if b is None or a is None:
            continue
        key = "foe" if m["actor"] == "me" else "mine"
        bv, av = b.get(key), a.get(key)
        if not bv or not av:
            continue
        for i in range(4):
            d = av[i] - bv[i]
            if d > 0:
                out["open"].append((m["actor"], m.get("name") or m["move"],
                                    COLOURS[i], bv[i], d))
    return out


def report_corpus(fights):
    """Across every log given at once. One fight cannot separate a move's own
    constants from the matchup it was measured in; several against different
    opponents can begin to."""
    print("=" * 78)
    print("CORPUS  (%d fight%s)" % (len(fights), "" if len(fights) == 1 else "s"))
    print("=" * 78)

    # Reported cooldown per move per opponent. The character sheet lists a base
    # cooldown; if what the server reports moves with the opponent, the difference
    # is a matchup term and not a property of the move.
    cd = defaultdict(lambda: defaultdict(set))
    for f in fights:
        for name, v in f["cd"]:
            cd[name][f["foe"].split("/")[-1]].add(v)
    if cd:
        print("")
        print("  reported cooldown (ticks) for my moves, by opponent")
        for name in sorted(cd):
            per = cd[name]
            allv = sorted(set().union(*per.values()))
            flag = "   VARIES" if len(allv) > 1 else ""
            print("    %-24s %s%s" % (name, "  ".join(
                "%s=%s" % (o, ",".join("%g" % x for x in sorted(v)))
                for o, v in sorted(per.items())), flag))

    # Opening growth against the opening already standing. dO should fall as the
    # standing opening rises; the shape of that fall is the (1 - Oc) term.
    op = defaultdict(list)
    for f in fights:
        for actor, name, colour, before, delta in f["open"]:
            op[(actor, name, colour, f["foe"].split("/")[-1])].append((before, delta))
    if op:
        print("")
        print("  opening growth: standing opening -> gain, per move and opponent")
        for k in sorted(op):
            actor, name, colour, foe = k
            pts = sorted(op[k])
            if len(pts) < 2:
                continue
            shown = "  ".join("%d:+%d" % p for p in pts[:12])
            more = "  (+%d more)" % (len(pts) - 12) if len(pts) > 12 else ""
            print("    %-4s %-20s %-6s vs %-12s %s%s"
                  % (actor, name[:20], colour, foe[:12], shown, more))
    print()


def main(argv):
    paths = []
    for a in argv:
        hits = sorted(glob.glob(a))
        paths.extend(hits if hits else [a])
    if not paths:
        print(__doc__)
        return 2
    seen = 0
    fights = []
    for p in paths:
        if not os.path.exists(p):
            print("missing: %s" % p)
            continue
        rows, bad = load(p)
        if not rows:
            print("%s: empty" % p)
            continue
        seen += 1
        fights.append(collect(rows))
        report_header(rows, p)
        report_moves(rows)
        report_effects(rows)
        report_damage_model(rows)
        report_damage(rows)
        report_anomalies(rows, bad)
        print()
    if len(fights) > 1:
        report_corpus(fights)
    return 0 if seen else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
