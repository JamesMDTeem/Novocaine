#!/usr/bin/env python3
"""Recover an opponent's hidden stats from logged fights.

    python tools/combat/estimate.py bin/CombatLogs/*.jsonl

Reads every log, keeps only the engagements that can actually support each measurement
(fightlog decides that, not this), and reports what the corpus knows about each species
it has met. Nothing here is fitted by search where arithmetic will do: the defence
weight is the inverse of the opening-growth formula, and the agility is an interval
implied by integer cooldowns.

Where a quantity cannot be pinned it is reported as a range or as "?", never as a point
estimate with the uncertainty quietly dropped. An interval that says "somewhere below
40" is a useful thing to plan a fight around; a confident 38 that is really "somewhere
below 40" is not.

Stdlib only.
"""

import glob
import json
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fightlog  # noqa: E402
import model  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SHEET = os.path.join(ROOT, "data", "combat", "moves_sheet.json")

# The deck weighting. Every attack weight on the sheet is written "... * mu", and mu is
# only readable from moves whose cooldown divides by it - Take Aim, which reported its
# listed 30 exactly, so mu is 1 there. For the rest it is unknown, and assuming 1 makes
# every defence weight below proportional to the truth rather than equal to it. Since Wd
# is only ever used as the ratio Wa/Wd against the same Wa, predictions are unaffected;
# the printed number is what carries the assumption.
MU = 1.0


def load_moves():
    if not os.path.exists(SHEET):
        return {}
    with open(SHEET, "r", encoding="utf8") as f:
        doc = json.load(f)
    return dict((m["name"], m) for m in doc.get("moves") or [] if m.get("name"))


def attack_weight(move, attrs):
    """Wa for one of our moves: the skill its icon names, times its own multiplier.

    "According to weapon" moves name no skill, and the sheet's closing note resolves
    those to Melee Combat.
    """
    skill = move.get("attack_skill") or "melee"
    base = attrs.get(skill)
    if base is None:
        return None
    mult = 1.0
    raw = move.get("attack_weight") or ""
    for tok in raw.replace("·", " ").split():
        tok = tok.strip()
        if tok.endswith("%"):
            try:
                mult *= float(tok[:-1]) / 100.0
            except ValueError:
                pass
    return base * mult * MU


def agility_interval(observations, agi_me):
    """The opponent's agility, as the interval the reported cooldowns allow.

    A cooldown comes back as a whole number of ticks, so an observation of N against a
    base B says the multiplier lies in [(N-0.5)/B, (N+0.5)/B] and no tighter. The
    multiplier is 1 - 0.1*clamp(log2(agiMe/agiFoe), -1, 1), so each observation is an
    interval on the opponent's agility and several of them intersect.

    Returns (lo, hi, capped) where capped means the multiplier has reached its limit and
    the interval is open on that side - all the observation says is "at least twice" or
    "at most half".
    """
    lo, hi, capped = 0.0, float("inf"), False
    for base, ticks in observations:
        if base <= 0:
            continue
        flo, fhi = (ticks - 0.5) / base, (ticks + 0.5) / base
        # f = 1 - 0.1 L  ->  L = 10 (1 - f), and agiFoe = agiMe / 2^L.
        llo, lhi = 10.0 * (1.0 - fhi), 10.0 * (1.0 - flo)
        if lhi >= 1.0 or llo <= -1.0:
            capped = True
        llo, lhi = max(-1.0, min(1.0, llo)), max(-1.0, min(1.0, lhi))
        a, b = agi_me / (2.0 ** lhi), agi_me / (2.0 ** llo)
        lo, hi = max(lo, min(a, b)), min(hi, max(a, b))
    if hi == float("inf"):
        return None
    return (lo, hi, capped)


def fit_armour(hits):
    """Hard and soft soak, from attacks whose ARM channel recorded what was absorbed.

    ARM + SHP is the damage before armour and SHP is what got through, so each hit is a
    direct (raw, dealt) pair and no damage model is needed to produce one. The grid is
    coarse because the numbers are integers; a finer one would be inventing precision.
    """
    pts = [(h["raw"], h["shp"]) for h in hits if h["soaked"] > 0 and h["raw"] > 0]
    if len(pts) < 2:
        return None
    scored = []
    for hard in range(0, 61):
        for soft in range(0, 61):
            err = 0.0
            for raw, dealt in pts:
                err += (model.dealt_damage(raw, hard, soft, 0.0) - dealt) ** 2
            scored.append((err, hard, soft))
    scored.sort()
    err, hard, soft = scored[0]

    # Soft soak ramps in over an interval of twice its value and is fully applied past
    # that, so once every hit lands above the interval, hard H with soft S is
    # indistinguishable from hard H+S with no soft at all - both subtract H+S. The fit
    # will happily return one of them. Saying which is which needs a hit small enough to
    # land inside the ramp, and reporting a split that the data cannot see is how a
    # confident wrong number gets into a table.
    tol = max(0.5, err * 1.0001)
    tied = [(h, s) for e, h, s in scored if e <= tol]
    totals = set(h + s for h, s in tied)
    return {"hard": hard, "soft": soft, "n": len(pts),
            "rms": math.sqrt(err / len(pts)),
            "total": (min(totals), max(totals)),
            "identified": len(tied) == 1}


def bucket(eng):
    """The key an engagement's measurements accumulate under.

    Every player shares one resource - gfx/borka/body - so grouping on the resource
    alone pools every human opponent into a single creature. That is not a cosmetic
    problem: pooling three sparring partners of different agility produced an agility
    interval of "136 to 48", an empty range, which is the tool proving its own bucket
    wrong. Animals are grouped by species; players are kept apart by gob, which is
    stable within a session and is the only identity a log carries for them.
    """
    res = eng.res or ""
    if "kritter" in res:
        return res.split("/")[-1]
    if not res:
        return "?#%s" % eng.gob
    return "%s#%s" % (res.split("/")[-1], eng.gob)


# How wrong an observed gain can be, in opening points. Half a point is the display
# rounding. The other point is decay: a gain is read from the state sampled after the
# hit, and openings demonstrably lose a point between the two - the same badger log that
# supplies the cleanest growth series in the corpus shows three of them, 20 to 19, 28 to
# 27 and 42 to 41.
#
# Getting this wrong is not conservative in a harmless direction. At the half point
# alone, that badger's nine Quick Barrages produce two disjoint answers - 81 from the
# early hits and 192 from the late ones - and the tool reports a contradiction where
# there is none. At a point and a half they intersect at 98 to 111, which contains the
# 100 that a joint fit of the whole series returns.
GAIN_SLOP = 1.5


def gain_interval(wa, gain, ob, standing):
    """The defence weight an observed gain allows, as an interval.

    Because the weight enters through a cube root, the slop above becomes a wide band at
    small gains and a narrow one at large: a +4 into an opening already at 52% constrains
    almost nothing, while a +24 into a fresh one constrains tightly. Reporting a midpoint
    alone hides which of those two an estimate is.
    """
    oc = standing / 100.0
    lo = model.defence_weight(wa, gain + GAIN_SLOP, ob, oc)
    hi = model.defence_weight(wa, max(0.1, gain - GAIN_SLOP), ob, oc)
    return (lo, hi)


def opens_map(moves):
    """Move name -> the set of colour indices it opens, for fightlog.

    This is what lets contamination detection be exact rather than temporal: a rise in a
    colour none of our deck opens cannot be ours, however close in time it landed.
    """
    idx = dict((c, i) for i, c in enumerate(fightlog.COLOURS))
    out = {}
    for name, m in moves.items():
        out[name] = set(idx[o["colour"]] for o in (m.get("openings") or [])
                        if o.get("colour") in idx)
    return out


def collect(paths):
    moves = load_moves()
    opens = opens_map(moves)
    per = defaultdict(lambda: {
        "engagements": 0, "skipped": [], "wd": [], "cd": defaultdict(set),
        "hits": [], "their_moves": defaultdict(set), "agi_me": set(), "took": [],
        "res": None, "hp": None,
        # Damage per opponent GOB, accumulated across every file that gob appears in -
        # see summarise_hp for why this cannot be done per file.
        "dealt": defaultdict(int), "killed": set(),
    })
    for p in sorted(paths):
        log = fightlog.read(p, opens)
        if not log.rows:
            continue
        attrs = (log.header or {}).get("attr") or {}
        agi_me = attrs.get("agi")
        for eng in log.engagements:
            rec = per[bucket(eng)]
            rec["engagements"] += 1
            if agi_me:
                rec["agi_me"].add(agi_me)

            # Hitpoints are accumulated BEFORE the usability gate, and per gob rather
            # than per engagement. A creature does not care who hurt it or in how many
            # sittings, so a group fight and an interrupted one both still measure it -
            # they are only useless for attributing openings.
            rec["res"] = rec["res"] or eng.res
            rec["dealt"][eng.gob] += sum(
                d["v"] for d in eng.damage
                if d.get("ch") == "SHP" and d.get("gob") == eng.gob)
            if any(d.get("ch") in ("#ffff", "C65535") and d.get("gob") == log.me
                   for d in eng.damage):
                rec["killed"].add(eng.gob)

            if not eng.offence_ok:
                rec["skipped"].append((os.path.basename(p), eng.problems))
                continue

            for actor, name, colour, standing, gain in fightlog.opening_gains(eng):
                # Only our own attacks measure the opponent's defence. Theirs measure
                # ours, against an attack weight the log does not record.
                if actor != "me":
                    continue
                m = moves.get(name)
                if m is None:
                    continue
                ob = None
                for o in m.get("openings") or []:
                    if o.get("colour") == colour:
                        ob = o.get("pct")
                if not ob:
                    continue
                wa = attack_weight(m, attrs)
                if not wa:
                    continue
                wd = model.defence_weight(wa, gain, ob, standing / 100.0)
                if wd > 0:
                    lo, hi = gain_interval(wa, gain, ob, standing)
                    rec["wd"].append((name, colour, standing, gain, wa, wd, lo, hi))

            for h in fightlog.hits(eng, log.me):
                if h["actor"] == "me":
                    rec["hits"].append(h)

            for m in eng.moves:
                if m.get("actor") == "me":
                    name = m.get("name") or m.get("move")
                    if m.get("cd", -1) > 0:
                        rec["cd"][name].add(m["cd"])
                else:
                    rec["their_moves"][m.get("name") or m.get("move")].add(m.get("cd"))

            if eng.defence_ok:
                for h in fightlog.hits(eng, log.me):
                    if h["actor"] != "me":
                        rec["took"].append(h)

    for rec in per.values():
        rec["hp"] = summarise_hp(rec["dealt"], rec["killed"])
    return per, moves


def summarise_hp(dealt, killed):
    """Hitpoints, from the total damage it took to kill one.

    The sum must run across every FILE the same gob appears in, not within one. A fight
    interrupted by auto-reaggro continues in a fresh log with the creature's health where
    the last one left it, and counting only the file that contains the kill measures the
    last instalment rather than the total. That error was not small: the fox came out at
    42 where the true figure across its two files is 126, and one badger at 38 where it
    is 210 - which had also made the two badgers look like wildly different creatures
    (38 and 342) when they are 210 and 342.

    Damage other people dealt counts too, and should: hitpoints are hitpoints, and it is
    the creature's total intake that killed it. What that cannot see is damage dealt out
    of our view, so every figure here is a lower bound on the creature at full health -
    it is exactly its health at the moment we first saw it.

    A kill also gives an upper bound, since the killing blow was the first to take it
    past zero, but the corpus does not record which blow was last cleanly enough to
    subtract it, so only the lower bound is reported.
    """
    kills = [v for g, v in dealt.items() if g in killed and v > 0]
    lived = [v for g, v in dealt.items() if g not in killed and v > 0]
    if kills:
        return {"lo": min(kills), "hi": max(kills),
                "from": "%d kill(s), summed across every file each gob appears in"
                        % len(kills),
                "note": "at least this much - the creature may have been hurt before we "
                        "first saw it, and damage dealt out of our view is not counted"}
    if lived:
        return {"lo": max(lived), "hi": None,
                "from": "%d opponent(s) that survived" % len(lived),
                "note": "lower bound only - none of them died"}
    return None


def report(per, moves):
    for name in sorted(per):
        rec = per[name]
        print("=" * 78)
        print("%s   (%d engagement%s)"
              % (name, rec["engagements"], "" if rec["engagements"] == 1 else "s"))
        print("=" * 78)
        if rec["skipped"]:
            print("  %d engagement(s) not used for offence:" % len(rec["skipped"]))
            for f, probs in rec["skipped"][:4]:
                print("    %s" % f)
                for pr in probs:
                    print("      - %s" % pr)

        # --- defence weight
        if rec["wd"]:
            # Each observation is a constraint, so the answer must satisfy all of them
            # at once - intersect, do not average. An empty intersection means the
            # observations disagree, which is worth saying loudly rather than papering
            # over with a median.
            ilo = max(w[6] for w in rec["wd"])
            ihi = min(w[7] for w in rec["wd"])
            vals = sorted(w[5] for w in rec["wd"])
            if ilo <= ihi:
                print("\n  defence weight   %.0f - %.0f   from %d observation(s), midpoints"
                      " %.0f - %.0f" % (ilo, ihi, len(vals), vals[0], vals[-1]))
            else:
                print("\n  defence weight   CONTRADICTORY - %d observation(s) whose intervals"
                      " do not overlap, midpoints %.0f - %.0f"
                      % (len(vals), vals[0], vals[-1]))
            print("                   (assuming mu = 1; see MU in this file)")

            # Per move, because two moves disagreeing is a different fact from two
            # observations of one move disagreeing. The opponent's defence weight is a
            # property of the opponent, so every move must recover the same one. When
            # they do not, the discrepancy is in OUR numbers - specifically in the mu of
            # each move, which enters its attack weight and is assumed 1 above. Two moves
            # against one opponent therefore measure the RATIO of their mu, which is
            # otherwise only readable from moves whose cooldown divides by it.
            bymove = defaultdict(list)
            for mv, _c, _st, _g, wa, wd, lo, hi in rec["wd"]:
                bymove[mv].append((wa, wd, lo, hi))
            if len(bymove) > 1:
                print("                   per move - these should agree, and a gap between"
                      " them is a ratio of their mu:")
            for mv in sorted(bymove):
                rows = bymove[mv]
                mlo, mhi = max(r[2] for r in rows), min(r[3] for r in rows)
                span = ("%.0f - %.0f" % (mlo, mhi)) if mlo <= mhi else "no overlap"
                print("      %-20s Wa %-6.1f %2d obs   Wd %-14s midpoints %.0f - %.0f"
                      % (mv[:20], rows[0][0], len(rows), span,
                         min(r[1] for r in rows), max(r[1] for r in rows)))
            if len(bymove) > 1:
                items = sorted(bymove.items())
                for i in range(len(items) - 1):
                    (an, ar), (bn, br) = items[i], items[i + 1]
                    amid = sorted(r[1] for r in ar)[len(ar) // 2]
                    bmid = sorted(r[1] for r in br)[len(br) // 2]
                    if amid > 0 and bmid > 0:
                        print("        %s vs %s: mu ratio about %.2f"
                              % (an[:18], bn[:18], (bmid / amid) ** (1.0 / 3.0)))
            if len(rec["wd"]) > 8:
                print("      (+%d more)" % (len(rec["wd"]) - 8))
        else:
            print("\n  defence weight   ? (no clean opening gain against this opponent)")

        # --- agility
        obs, agi_me = [], (sorted(rec["agi_me"])[-1] if rec["agi_me"] else None)
        for mv, ticks in rec["cd"].items():
            m = moves.get(mv)
            if m is None or m.get("cooldown") is None:
                continue
            # Maneuvers take no agility term, so they say nothing about the opponent.
            if not (m.get("attack_types") or []):
                continue
            for t in ticks:
                obs.append((m["cooldown"], t))
        if obs and agi_me:
            iv = agility_interval(obs, agi_me)
            if iv is None:
                print("\n  agility          ?")
            else:
                lo, hi, capped = iv
                if lo > hi:
                    print("\n  agility          CONTRADICTORY (%.0f - %.0f) - the cooldowns "
                          "in this bucket cannot come from one opponent" % (lo, hi))
                    obs = sorted(set(obs))
                else:
                    note = "  (at the cap - bounded, not pinned)" if capped else ""
                    print("\n  agility          %.0f - %.0f%s   from our agility %d and %d "
                          "cooldown observation(s)" % (lo, hi, note, agi_me, len(obs)))
                for base, t in sorted(set(obs)):
                    print("      base %-4d reported %-5g" % (base, t))
        else:
            print("\n  agility          ? (no attack cooldowns reported against this opponent)")

        # --- armour
        arm = fit_armour(rec["hits"])
        if arm:
            tlo, thi = arm["total"]
            if arm["identified"]:
                print("\n  armour           %d hard + %d soft   (%d hit(s), rms %.2f)"
                      % (arm["hard"], arm["soft"], arm["n"], arm["rms"]))
            elif tlo == thi:
                print("\n  armour           %d total, split unidentifiable   (%d hit(s), "
                      "rms %.2f)" % (tlo, arm["n"], arm["rms"]))
                print("                   every hit landed past the soft-soak ramp, where "
                      "H hard + S soft and H+S hard are the same subtraction")
            else:
                print("\n  armour           %d - %d total, split unidentifiable   (%d hit(s), "
                      "rms %.2f)" % (tlo, thi, arm["n"], arm["rms"]))
            for h in rec["hits"][:6]:
                if h["soaked"] > 0:
                    print("      %-22s raw %-4d soaked %-4d through %-4d"
                          % (h["move"][:22], h["raw"], h["soaked"], h["shp"]))
        else:
            hit = [h for h in rec["hits"] if h["raw"] > 0]
            if hit and not any(h["soaked"] for h in hit):
                print("\n  armour           none observed - %d hit(s), no ARM channel on any"
                      % len(hit))
            else:
                print("\n  armour           ? (too few soaked hits to separate hard from soft)")

        # --- what it does back
        if rec["their_moves"]:
            print("\n  its moves        %s" % ", ".join(sorted(rec["their_moves"])))
        if rec["took"]:
            dmg = [h for h in rec["took"] if h["raw"] > 0]
            if dmg:
                worst = max(h["raw"] for h in dmg)
                tot = sum(h["shp"] for h in dmg)
                print("  what it did      %d landed hit(s), %d total through our armour, "
                      "worst raw %d" % (len(dmg), tot, worst))
        print()


PACK = os.path.join(ROOT, "data", "combat", "opponents.json")


def write_pack(per, moves):
    """Write what the corpus knows, in the form the simulator can load.

    Every quantity is an interval or null. There is no field here that can hold a point
    estimate, deliberately: the simulator's job is to answer "can I win this", and the
    honest answer to that is often a range that straddles yes and no. A pack that
    flattened 74-97 to 85 would let it print a confident answer it has not got.
    """
    out = []
    for name in sorted(per):
        rec = per[name]
        entry = {"name": name, "engagements": rec["engagements"],
                 "res": rec.get("res"), "moves": sorted(rec["their_moves"])}

        if rec["wd"]:
            lo = max(w[6] for w in rec["wd"])
            hi = min(w[7] for w in rec["wd"])
            entry["defence_weight"] = ({"lo": round(lo, 1), "hi": round(hi, 1),
                                        "n": len(rec["wd"])}
                                       if lo <= hi else
                                       {"lo": None, "hi": None, "n": len(rec["wd"]),
                                        "contradictory": True})
        else:
            entry["defence_weight"] = None

        obs, agi_me = [], (sorted(rec["agi_me"])[-1] if rec["agi_me"] else None)
        for mv, ticks in rec["cd"].items():
            m = moves.get(mv)
            if m is None or m.get("cooldown") is None or not (m.get("attack_types") or []):
                continue
            for t in ticks:
                obs.append((m["cooldown"], t))
        iv = agility_interval(obs, agi_me) if (obs and agi_me) else None
        entry["agility"] = (None if iv is None or iv[0] > iv[1] else
                            {"lo": round(iv[0], 1), "hi": round(iv[1], 1),
                             "capped": iv[2], "our_agility": agi_me})

        arm = fit_armour(rec["hits"])
        if arm:
            tlo, thi = arm["total"]
            entry["armour"] = {"total_lo": tlo, "total_hi": thi,
                               "hard": arm["hard"] if arm["identified"] else None,
                               "soft": arm["soft"] if arm["identified"] else None,
                               "identified": arm["identified"], "n": arm["n"]}
        elif rec["hits"] and not any(h["soaked"] for h in rec["hits"]):
            # Not the same as unknown: we hit it and nothing was absorbed.
            entry["armour"] = {"total_lo": 0, "total_hi": 0, "hard": 0, "soft": 0,
                               "identified": True, "n": len(rec["hits"])}
        else:
            entry["armour"] = None

        entry["hitpoints"] = rec["hp"]
        out.append(entry)

    doc = {"source": "tools/combat/estimate.py over the logged corpus",
           "note": "Every value is an interval or null. Nothing here is a point estimate.",
           "opponents": out}
    with open(PACK, "w", encoding="utf8") as f:
        json.dump(doc, f, indent=1, sort_keys=True)
        f.write("\n")
    print("wrote %s  (%d opponent(s))" % (os.path.relpath(PACK, ROOT), len(out)))


def main(argv):
    argv = list(argv)
    write = "--write-pack" in argv
    if write:
        argv.remove("--write-pack")
    paths = []
    for a in argv:
        hits_ = sorted(glob.glob(a))
        paths.extend(hits_ if hits_ else [a])
    paths = [p for p in paths if os.path.exists(p)]
    if not paths:
        print(__doc__)
        return 2
    per, moves = collect(paths)
    if not moves:
        print("no %s - run tools/combat/parse_deck.py first"
              % os.path.relpath(SHEET, ROOT))
        return 2
    report(per, moves)
    if write:
        write_pack(per, moves)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
