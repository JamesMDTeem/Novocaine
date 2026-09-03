#!/usr/bin/env python3
"""Replay every logged fight through the model and report where it disagrees.

    python tools/combat/replay.py [bin/CombatLogs/*.jsonl]

WHY THIS EXISTS. Every other check in this project asserts numbers that were
transcribed out of the logs by hand - a bee swarm's 24/19/14/11, Take Aim's
30/36/42/48/54/60. Those are real evidence and they caught real defects, but they only
cover the fights somebody sat down and read. Adding a fight adds no coverage until
somebody edits a check, so the corpus grows and the tested surface does not.

This drives the model from the log files themselves. Every clean engagement is replayed
move by move: for each of our attacks the model is asked what opening it should raise and
what damage it should deal, against the state the log says was standing at that moment,
and the answer is compared with what the log says happened next. A new fight is new
coverage the moment it is written.

WHAT IT DOES NOT DO. It is not a simulator test in the sense of running a fight forward
from its first tick - openings decay by a rule this corpus cannot see (see the spec), so
a forward run drifts for reasons that are not the model's fault. Each move is replayed
against the OBSERVED state before it, which tests the step and not the accumulation.

Predictions are made with the opponent's measured defence weight, which is an interval,
so a prediction is an interval too. A move "agrees" when the observed gain falls inside
it. That is a weaker claim than a point match and it is the honest one: the inputs are
intervals, so the outputs are.

Stdlib only.
"""

import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402
import fightlog  # noqa: E402
import model  # noqa: E402

# An observed integer gain carries the display's truncation on both the gain and the
# standing opening it came from. estimate.GAIN_SLOP is the same allowance, for the same
# reason, and sharing the constant keeps the two from drifting apart.
SLOP = estimate.GAIN_SLOP


def opponent_bounds(name, pack):
    """(lo, hi) combat SKILL for a species, or None when it cannot be predicted from.

    Was the defence weight, which is the naive inversion of an opening gain and only equals
    anything real when the two skills sit outside the equalization band. Predicting from it
    fails exactly where equalization bites: both of this harness's misses were badgers,
    each off by a tenth of a point, because the badger is inside the band and its
    "defence weight" is our own attack weight reflected back.

    An equalized or disputed entry returns None. There is nothing to predict from - the
    corpus bounded the skill and declined to name it, and a midpoint would be a number
    nobody measured."""
    rec = pack.get(name)
    if not rec:
        return None
    sk = rec.get("skill")
    if not sk or sk.get("equalized") or sk.get("disputed"):
        return None
    lo, hi = sk.get("lo"), sk.get("hi")
    if lo is None or hi is None or lo <= 0:
        return None
    return (lo, hi)


def load_pack():
    path = os.path.join(estimate.ROOT, "data", "combat", "opponents.json")
    try:
        with open(path, "r", encoding="utf8") as f:
            doc = json.load(f)
    except (OSError, ValueError):
        return {}
    rows = doc.get("opponents") if isinstance(doc, dict) else doc
    if isinstance(rows, dict):
        rows = list(rows.values())
    return dict((r["name"], r) for r in rows or [] if r.get("name"))


WEAPONS = os.path.join(estimate.ROOT, "data", "combat", "weapons.json")

# Resource basename -> the wiki's weapon name. The log records what the client equips;
# the data pack is keyed on the page title, and nothing derives one from the other.
WEAPON_RES = {
    "bronzesword": "Bronze Sword",
    "cutblade": "Cutblade",
    "hirdswordsman": "Hirdsman's Sword",
    "fyrdswordsman": "Fyrdsman's Sword",
    "battleaxe": "Battleaxe of the Twelfth Bay",
    "boarspear": "Boar Spear",
}


def load_weapons():
    try:
        with open(WEAPONS, "r", encoding="utf8") as f:
            doc = json.load(f)
    except (OSError, ValueError):
        return {}
    rows = doc if isinstance(doc, list) else sum(
        (v for v in doc.values() if isinstance(v, list)), [])
    out = {}
    for r in rows:
        if not isinstance(r, dict) or not r.get("name"):
            continue
        v = r.get("basedmg")
        if isinstance(v, dict):
            v = v.get("value")
        out[r["name"]] = v
    return out


def weapon_of(log, weapons):
    """(base damage, quality) of the weapon held in this fight, or None.

    Both halves come from the log itself - the gear events carry the resource and its
    quality - and the base damage from the wiki pack. Three independent sources, which is
    what makes the damage replay below a real test rather than a restatement.
    """
    for g in log.gear:
        res = (g.get("res") or "").split("/")[-1]
        name = WEAPON_RES.get(res)
        if name and weapons.get(name):
            return (weapons[name], g.get("ql"))
    return None


def replay_damage(log, eng, moves, weapons):
    """Predicted against observed raw damage for every attack we landed.

    THIS IS THE INDEPENDENT HALF. The opening replay below is circular by construction -
    it predicts gains using a defence weight that was fitted from those same gains - but
    damage owes nothing to any fitted quantity:

        raw = basedmg * share * sqrt(sqrt(ql * str) / 10) * opening^2

    Every input is observed or stated. The weapon's base damage is the wiki's, its quality
    and our strength are the log's own header and gear, the share is printed on the
    character sheet, and the opening is the one the log recorded standing at that moment.
    The observed value is ARM + SHP, which is the damage before armour, so the armour
    never enters and neither does anything this project fitted.

    Unarmed moves substitute strength for weapon quality, which collapses the quality term
    to sqrt(str/10) - handled by passing str for both, exactly as Combatant.damageQuality
    does on the Java side.
    """
    out = []
    attrs = (log.header or {}).get("attr") or {}
    strength = attrs.get("str")
    if not strength:
        return out
    wep = weapon_of(log, weapons)
    for h in fightlog.hits(eng, log.me):
        if h.get("actor") != "me":
            continue
        observed = (h.get("shp") or 0) + (h.get("soaked") or 0)
        if observed <= 0:
            continue
        m = moves.get(h.get("move"))
        if m is None:
            continue
        share, flat = m.get("damage_share"), m.get("damage_flat")
        if share:
            if not wep or not wep[1]:
                continue
            base, ql = wep[0], wep[1]
        elif flat:
            base, share, ql = flat, 1.0, strength
        else:
            continue
        # The opening the attack reads is the combined one over ITS OWN attack types.
        cols = [t.get("colour") for t in m.get("attack_types") or []]
        idx = dict((c, i) for i, c in enumerate(fightlog.COLOURS))
        own = [h["openings"][idx[c]] / 100.0 for c in cols if c in idx]
        if not own:
            continue
        pred = model.raw_damage(base, share, ql, strength, model.combined(own))
        out.append((h.get("move"), pred, observed))
    return out


def replay(paths):
    moves = estimate.load_moves()
    opens = estimate.opens_map(moves)
    pack = load_pack()

    weapons = load_weapons()
    stats = defaultdict(lambda: {"agree": 0, "miss": 0, "worst": 0.0, "n": 0})
    dmg = defaultdict(lambda: {"n": 0, "err": 0.0, "worst": 0.0})
    misses = []
    skipped = defaultdict(int)

    for p in sorted(paths):
        try:
            log = fightlog.read(p, opens)
        except Exception:
            skipped["unreadable"] += 1
            continue
        if not log.rows:
            continue
        attrs = (log.header or {}).get("attr") or {}
        lv = estimate.levels_at((log.header or {}).get("wall"))
        for eng in log.engagements:
            name = estimate.bucket(eng)
            if not eng.offence_ok:
                skipped["contaminated"] += 1
                continue
            # Damage needs no opponent stats, so it covers fights the opening replay has
            # to skip for want of a pinned defence weight - but it still needs a clean
            # fight. hits() pairs damage numbers to a move by time, and in a group fight
            # the client draws somebody else's numbers over the same target, so an
            # ungated damage replay reads their hits as ours. It showed as an rms of 9.3
            # points against a model that fits clean fights to under one.
            for mv, pred, obs in replay_damage(log, eng, moves, weapons):
                d = dmg[name]
                d["n"] += 1
                # Observed damage is a whole number the client rounded, so a residual
                # under a point is the display and not the model.
                e = abs(pred - obs)
                d["err"] += e * e
                d["worst"] = max(d["worst"], e)

            bounds = opponent_bounds(name, pack)
            if bounds is None:
                skipped["opponent not pinned"] += 1
                continue
            foe_lo, foe_hi = bounds
            for actor, mv, colour, standing, gain in fightlog.attributed_gains(
                    eng, opens, log.me):
                if actor != "me":
                    continue
                m = moves.get(mv)
                if m is None:
                    continue
                ob = None
                for o in m.get("openings") or []:
                    if o.get("colour") == colour:
                        ob = o.get("pct")
                if not ob:
                    continue
                wa = estimate.attack_weight_bounds(m, attrs, lv.get(mv))
                if not wa:
                    continue
                wa_lo, wa_hi = wa
                # The SKILL and the multipliers go in separately, because only the skills
                # equalize. Our skill is the attack weight with the move's own multiplier
                # divided back out.
                mult = m.get("weight_mult") or 1.0
                oc = standing / 100.0
                # Widest prediction the inputs allow: our biggest weight against the
                # weakest opponent, and the reverse.
                hi = model.opening_gain_eq(wa_hi / mult, mult, foe_lo, 1.0, ob, oc) + SLOP
                lo = model.opening_gain_eq(wa_lo / mult, mult, foe_hi, 1.0, ob, oc) - SLOP
                s = stats[name]
                s["n"] += 1
                if lo <= gain <= hi:
                    s["agree"] += 1
                else:
                    s["miss"] += 1
                    off = (lo - gain) if gain < lo else (gain - hi)
                    s["worst"] = max(s["worst"], off)
                    misses.append((off, name, mv, colour, standing, gain, lo, hi,
                                   os.path.basename(p)))
    return stats, dmg, misses, skipped


def logged_predictions(paths, opens=None):
    """Predictions the CLIENT wrote at the time, against what actually followed.

    Everything else in this file asks today's model what it would have said about an old
    fight. That is useful and it is not the same measurement, in a way that hides itself:
    the answer moves whenever the data pack changes, so the "before" number in any
    before-and-after comparison moves too, and a fix can never be shown to have helped.

    A prediction written into the log at the moment the move was thrown does not move. It
    is a record of what the model believed on the day, and the residual against it is a
    fact about that day.

    Returns (rows, missing) where a row is
    (species, move, colour, predicted, observed, file) and `missing` counts logs that
    carry no predictions at all - which is every log written before schema 8, and every
    fight against an opponent the pack cannot predict.
    """
    if opens is None:
        opens = estimate.opens_map(estimate.load_moves())
    rows, missing = [], 0
    for pth in sorted(paths):
        try:
            log = fightlog.read(pth, opens)
        except (OSError, ValueError):
            continue
        if not log.rows:
            continue
        seen = False
        for eng in log.engagements:
            if not eng.predictions:
                continue
            seen = True
            name = estimate.bucket(eng)
            for pr in eng.predictions:
                # The move this prediction belongs to is the one at the same instant. The
                # client writes them back to back, so an exact timestamp match is right and
                # a window would risk pairing with the NEXT move.
                mv = None
                for m in eng.moves:
                    if (m.get("t") == pr.get("t")) and (m.get("actor") == "me"):
                        mv = m
                        break
                if mv is None:
                    continue
                before, after = eng.brackets(mv)
                if (before is None) or (after is None):
                    continue
                opened = pr.get("opened") or []
                for c, colour in enumerate(("green", "blue", "yellow", "red")):
                    if c >= len(opened):
                        continue
                    if opened[c] <= 0:
                        continue
                    obs = after["foe"][c] - before["foe"][c]
                    rows.append((name, mv.get("name") or mv.get("move"), colour,
                                 opened[c], obs, os.path.basename(log.path)))
        if not seen:
            missing += 1
    return (rows, missing)


def report_logged_predictions(paths, opens=None):
    rows, missing = logged_predictions(paths, opens)
    print()
    print("=" * 78)
    print("PREDICTIONS THE CLIENT WROTE DOWN")
    print("=" * 78)
    if not rows:
        print("  none yet - %d log(s) carry no prediction." % missing)
        print()
        print("  Expected until a fight is logged on a schema 8 client. Every existing log")
        print("  predates it, and a prediction cannot be added to an old fight: the whole")
        print("  point is that it records what the model believed at the time.")
        print()
        return True

    err = [abs(p - o) for _s, _m, _c, p, o in [(r[0], r[1], r[2], r[3], r[4]) for r in rows]]
    rms = (sum(e * e for e in err) / len(err)) ** 0.5
    print("  %d prediction(s) across %d log(s) without one." % (len(rows), missing))
    print("  rms %.2f opening points\n" % rms)
    worst = sorted(rows, key=lambda r: -abs(r[3] - r[4]))[:8]
    print("  %-12s %-20s %-7s %-10s %-10s %s"
          % ("species", "move", "colour", "predicted", "observed", "file"))
    for name, mv, colour, pred, obs, f in worst:
        print("  %-12s %-20s %-7s %-10.1f %-10.1f %s"
              % (name[:12], (mv or "?")[:20], colour, pred, obs, f))
    print()
    return True


def main(argv):
    paths = []
    for a in argv:
        import glob
        paths.extend(sorted(glob.glob(a)))
    if not paths:
        paths, dirs = fightlog.default_logs(estimate.ROOT)
        for d in dirs:
            print("  %d log(s)  %s" % (len(list(f for f in paths if f.startswith(d))), d))
    print("\nreplaying %d log(s) through the model\n" % len(paths))

    stats, dmg, misses, skipped = replay(paths)
    if not stats:
        print("nothing replayable - no engagement had both a clean read and a pinned "
              "opponent")
        return 0

    if dmg:
        import math
        print("DAMAGE - the independent half. No fitted quantity enters this: the weapon's")
        print("base damage is the wiki's, its quality and our strength are the log header's,")
        print("the share is the character sheet's, and the observed figure is ARM + SHP, so")
        print("armour never enters either.\n")
        print("%-16s %-6s %-10s %s" % ("opponent", "hits", "rms error", "worst"))
        tn = 0
        te = 0.0
        for name in sorted(dmg):
            d = dmg[name]
            tn += d["n"]
            te += d["err"]
            print("%-16s %-6d %-10.2f %.2f"
                  % (name, d["n"], math.sqrt(d["err"] / d["n"]), d["worst"]))
        print("\n%d hits, rms %.2f points of damage overall"
              % (tn, math.sqrt(te / tn) if tn else 0.0))
        print()

    print("OPENINGS - circular, and worth saying so. The opponent's skill each prediction")
    print("uses was recovered from these same gains, so agreement confirms the arithmetic")
    print("round-trips and nothing more. What it DOES test is the equalization branch: the")
    print("recovery and the prediction take different paths through it, and a species whose")
    print("skill is only bounded is skipped rather than predicted from a midpoint.\n")
    print("%-16s %-6s %-7s %-7s %s" % ("opponent", "n", "agree", "miss", "worst miss"))
    tot_n = tot_agree = 0
    for name in sorted(stats):
        s = stats[name]
        tot_n += s["n"]
        tot_agree += s["agree"]
        print("%-16s %-6d %-7d %-7d %s"
              % (name, s["n"], s["agree"], s["miss"],
                 ("%.1f points" % s["worst"]) if s["miss"] else "-"))
    print("\n%d of %d predicted openings contain the observed gain (%.1f%%)"
          % (tot_agree, tot_n, (100.0 * tot_agree / tot_n) if tot_n else 0.0))

    if misses:
        print("\nthe %d that do not, worst first - each is a real disagreement between "
              "the\nmodel and a logged fight, not a rounding complaint:" % len(misses))
        for off, name, mv, colour, standing, gain, lo, hi, f in sorted(
                misses, reverse=True)[:12]:
            print("  %-12s %-20s %-7s standing %-4d observed %-5.0f predicted %.1f-%.1f"
                  "   off by %.1f   %s"
                  % (name, mv[:20], colour, standing, gain, lo, hi, off, f))
    if skipped:
        print("\nnot replayed: %s"
              % ", ".join("%d %s" % (v, k) for k, v in sorted(skipped.items())))

    # --- the thresholds, and why they are where they are.
    #
    # These are REGRESSION bounds, not a claim that the model is finished. They sit just
    # above what the corpus does today so that a change which makes the fit worse fails
    # here, while the known gaps below stay honestly visible rather than being asserted
    # away.
    import math
    ok = True
    animals = dict((k, v) for k, v in dmg.items() if not k.startswith(("body#", "?#")))
    an_n = sum(v["n"] for v in animals.values())
    an_e = sum(v["err"] for v in animals.values())
    an_rms = math.sqrt(an_e / an_n) if an_n else 0.0
    print("\nANIMALS ONLY: %d hits, rms %.2f" % (an_n, an_rms))
    if an_rms > 2.0:
        print("  FAIL - animal damage rms above 2.0 points")
        ok = False
    # Misses split into two kinds and only one of them is a finding.
    #
    # A miss under a point is the interval's edge. The prediction band is built from the
    # skill's spread across moves, and where a species sits near an equalization boundary
    # one end of that band lands on the wrong side of it - the fox misses by 0.1, the
    # beaver by 0.4. Failing on those would be failing on arithmetic that is right.
    #
    # A miss of tens of points is a real disagreement. There is exactly one: an ant swarm
    # taking 47 points of Cornered from a single Quick Barrage listed at 10%, which needs
    # an attack weight a hundred times the target's. It is the same observation that first
    # made the ant bucket contradictory, and the likeliest explanation is that a swarm is
    # not one creature - its strength should fall as it is killed, and nothing in this
    # model has a term for that.
    GROSS = 1.0
    gross = [m for m in misses if m[0] >= GROSS]
    edge = len(misses) - len(gross)
    if edge:
        print("  %d miss(es) under a point - the prediction interval's edge, not a finding"
              % edge)
    if len(gross) > 1:
        print("  FAIL - %d gross miss(es), where the corpus has one known outlier"
              % len(gross))
        ok = False
    elif gross:
        print("  1 gross miss, the known ant-swarm outlier - see the source")
    # Players fit far worse than animals - three of them carry the overall figure from
    # under 1.5 to 3.67 - and nothing here explains why yet. Bounded so it cannot quietly
    # get worse, and left visible because it is a real open question rather than noise.
    players = dict((k, v) for k, v in dmg.items() if k.startswith("body#"))
    if players:
        pl_n = sum(v["n"] for v in players.values())
        pl_rms = math.sqrt(sum(v["err"] for v in players.values()) / pl_n)
        print("PLAYERS ONLY: %d hits, rms %.2f   <- the standing gap, see the spec"
              % (pl_n, pl_rms))
        if pl_rms > 10.0:
            print("  FAIL - player damage rms above 10.0 points")
            ok = False
    if not report_logged_predictions(paths):
        ok = False
    print("\n" + ("ALL CHECKS PASSED" if ok else "CHECKS FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
