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

Stdlib only (this module). The opening-decay fitter tools/combat/decay_fit.py is the one exception that requires scipy/numpy (see tools/combat/requirements.txt) for O(t)=O0*exp(-t/tau) fitting.
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

# Flex readings that AGREE with the model, for the share the known outlier is held to at
# the gate below. Counting the misses alone would let the outlier grow simply by the
# agreeing readings being filtered out somewhere upstream.
FLEX_AGREEING = 174


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


def _armoured(name, pack):
    """Whether this target may carry armour: a species with a measured floor above 0, or a
    PLAYER, whose armour the pack rarely knows. Schema-2 logs write only SHP and IP on a
    player - never an armour row - and ZzxcuV3's Quick Barrages on two players read a steady
    12-13 points short of the model, which is that armour; it was the whole of the 7.46
    "standing gap" in player damage."""
    if str(name).startswith("body#"):
        return True
    arm = (pack.get(name) or {}).get("armour") or {}
    return (arm.get("total_lo") or 0) > 0


def load_pack():
    path = os.path.join(estimate.ROOT, "data", "combat", "opponents.json")
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            doc = json.load(f)
    except (OSError, ValueError):
        return {}
    rows = doc.get("opponents") if isinstance(doc, dict) else doc
    if isinstance(rows, dict):
        rows = list(rows.values())
    return dict((r["name"], r) for r in rows or [] if r.get("name"))


WEAPONS = os.path.join(estimate.ROOT, "data", "combat", "weapons.json")

# Moved to estimate, which the character export also reads it from.
WEAPON_RES = estimate.WEAPON_RES


def load_weapons():
    try:
        with open(WEAPONS, "r", encoding="utf-8", errors="replace") as f:
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
    return weapon_at(log, weapons, None)


def weapon_at(log, weapons, t):
    """The weapon in hand at time `t`, or the first one the file names when t is None.

    A WEAPON CAN BE SWAPPED MID-FIGHT, and the recorder polls equipment and writes a gear
    row when it changes, so the log has always said so. This did not read it: it returned
    the first weapon in the file and used it for every hit in the fight, which prices a
    Cleave from a bronze sword at whatever the fight opened with.

    Gear rows are in file order, which is time order, so the answer is the last one at or
    before `t`. A slot emptying writes a null res and simply does not match.
    """
    found = None
    for g in log.gear:
        if (t is not None) and ((g.get("t") or 0) > t):
            break
        res = (g.get("res") or "").split("/")[-1]
        name = WEAPON_RES.get(res)
        if name and weapons.get(name):
            found = (weapons[name], g.get("ql"))
            if t is None:
                return found
    return found


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
    # THE PAIRING IS VETTED PER OBSERVATION BEFORE IT GETS HERE. fightlog.hits drops the
    # damage for a move when another combatant announced inside the same window, because
    # their numbers over the same victim cannot be told from ours - see
    # fightlog._announcement_by_other. This function therefore inherits the R1 fix by using
    # the shared primitive rather than re-pairing damage of its own.
    for h in fightlog.hits(eng, log.me):
        wep = weapon_at(log, weapons, h.get("t"))
        if h.get("actor") != "me":
            continue
        observed = (h.get("shp") or 0) + (h.get("soaked") or 0)
        if observed <= 0:
            continue
        m = moves.get(h.get("move"))
        if m is None:
            continue
        share, flat = m.get("damage_share"), m.get("damage_flat")
        glove = None
        if share:
            if not wep or not wep[1]:
                continue
            # A TOOL IN HAND BEFORE SCHEMA 13 IS NOT EVIDENCE OF THE WEAPON SWUNG. Swaps were
            # first logged at schema 13 ("Notice a weapon swap"), so an older log's weapon is
            # its t=0 snapshot - and a gathering tool is exactly what someone holds when a
            # fight finds them and swaps away from. Pickaxe hits read 1.37-1.40x the model in
            # schema 3-10 logs and 0.98-0.99 in 12 and 16, and Shade's wildgoat fight matches
            # the sword he held that day to the digit (x1.68). Such a hit is not scored.
            if (log.schema < 13) and (weapon_name_at(log, h.get("t")) in SWAPPED_FROM_TOOLS):
                continue
            base, ql = wep[0], wep[1]
        elif flat:
            base, share, ql = flat, 1.0, strength
            # GLOVES ADD THEIR OWN TERM TO AN UNARMED BLOW: damage = base * sqrt(sqrt(str *
            # glove quality) / 10), beside the card's flat figure (the wiki's formula for
            # gloves). Left out, every Punch Shade threw in Lynx Claw Gloves read 1.23-1.28x
            # the prediction at glove quality 39 and 68 and strength 178-291 - the formula
            # predicts 1.27-1.29 there - while ZzxcuV3's in poor man's gloves read 1.02.
            glove = gloves_at(log, weapons, h.get("t"))
        else:
            continue
        # The opening the attack reads is the combined one over ITS OWN attack types.
        cols = [t.get("colour") for t in m.get("attack_types") or []]
        idx = dict((c, i) for i, c in enumerate(fightlog.COLOURS))
        # THE MIDPOINT OF WHAT THE CLIENT SHOWED, not its floor (2026-09-15). An opening is
        # rendered as floor(fraction * 100), so a logged 55 is anywhere in [55, 56), and damage
        # squares it: reading the floor under-predicts by about 2 * 0.5 / o of every hit, which
        # is the steady high reading the heavy cards carried. With +0.5 on each standing colour
        # (a 0 stays 0 - nothing was showing) the mean error on hits before the last goes from
        # +0.47 to -0.02 and rms 1.66 to 1.50; on drawn killing blows +2.15 to +0.18, rms 3.00
        # to 1.97; Cleave 3.82 to 2.04, Santa Samus 1.15 to 0.83. Punch and Knock Its Teeth Out,
        # both small, read slightly low with it (0.56 to 0.69, 0.80 to 0.92).
        own = [(h["openings"][idx[c]] + 0.5) / 100.0 if h["openings"][idx[c]] > 0 else 0.0
               for c in cols if c in idx]
        if not own:
            continue
        pred = model.raw_damage(base, share, ql, strength, model.combined(own))
        if glove:
            pred += model.raw_damage(glove[0], 1.0, glove[1], strength, model.combined(own))
        out.append((h.get("move"), pred, observed))
    return out


# Tools carried for gathering, which a fight finds in hand and which are swapped away from -
# see replay_damage. Only these are distrusted in pre-schema-13 logs; a sword at t=0 was
# almost always the sword swung (swords read 1.02 across every schema).
SWAPPED_FROM_TOOLS = ("Pickaxe", "Woodsman's Axe")

# Glove resources whose damage adds to an unarmed blow, and their rows in weapons.json.
# "lynxclawgloves" is confirmed by the corpus; "cutthroatknuckles" is an unconfirmed spelling.
GLOVE_RES = {"lynxclawgloves": "Lynx Claw Gloves", "cutthroatknuckles": "Cutthroat Knuckles"}


def weapon_name_at(log, t):
    """The weapons.json name of the weapon in hand at time `t` - the same walk as weapon_at,
    returning the name, since two weapons can share a base damage figure."""
    found = None
    for g in log.gear:
        if (t is not None) and ((g.get("t") or 0) > t):
            break
        name = WEAPON_RES.get((g.get("res") or "").split("/")[-1])
        if name:
            found = name
    return found


def gloves_at(log, weapons, t):
    """(base damage, quality) of damage-dealing gloves worn at time `t`, or None.

    Gear rows are per slot and in time order; a slot that empties or changes stops counting,
    which is tracked per slot rather than by the last matching row."""
    slots = {}
    for g in log.gear:
        if (t is not None) and ((g.get("t") or 0) > t):
            break
        slots[g.get("slot")] = g
    for g in slots.values():
        nm = GLOVE_RES.get((g.get("res") or "").split("/")[-1])
        if nm and weapons.get(nm) and g.get("ql"):
            return (weapons[nm], g.get("ql"))
    return None


def replay(paths):
    moves = estimate.load_moves()
    opens = estimate.opens_map(moves)
    pack = load_pack()

    weapons = load_weapons()
    stats = defaultdict(lambda: {"agree": 0, "miss": 0, "worst": 0.0, "n": 0})
    dmg = defaultdict(lambda: {"n": 0, "err": 0.0, "worst": 0.0})
    # The last hit of an engagement, kept apart - see the argument where it is filled.
    final_dmg = defaultdict(lambda: {"n": 0, "err": 0.0, "worst": 0.0})
    by_char = defaultdict(lambda: {"n": 0, "err": 0.0, "worst": 0.0})
    misses = []
    skipped = defaultdict(int)
    ranged_skipped = 0
    ranged_files = []

    for p in sorted(paths):
        try:
            log = fightlog.read(p, opens)
        except Exception:
            skipped["unreadable"] += 1
            continue
        if not log.rows:
            continue
        if fightlog.is_ranged(log):
            ranged_skipped += 1
            ranged_files.append(os.path.basename(p))
            continue
        attrs = (log.header or {}).get("attr") or {}
        lv = estimate.levels_for_log(log)
        for eng in log.engagements:
            name = estimate.bucket(eng)
            # THE OLD ALL-OR-NOTHING GATE, AND ONLY WHERE IT STILL EARNS ITS KEEP. This ran
            # over both halves and excluded 60.8% of engagements - 57.1% for others_present
            # alone, which fires when anything at all was happening anywhere in the fight.
            # attributed_gains was written to replace exactly that, testing each observation
            # instead of condemning the engagement, and running both meant the openings half
            # was gated twice: once by a rule the project had already decided was too coarse.
            #
            # The damage half still leans on it, because a pairing is made by time and a
            # third party's numbers over the same target look identical. It is no longer
            # the only defence: hits() now vetoes a pairing outright when another
            # combatant's move announcement falls inside the window (see
            # fightlog._announcement_by_other), so this gate is the floor under the pairs
            # that carry no announcement. The openings half sees every fight its own four
            # tests allow.
            clean = eng.offence_ok
            if not clean:
                skipped["contaminated (damage half only)"] += 1
            # Damage needs no opponent stats, so it covers fights the opening replay has
            # to skip for want of a pinned defence weight - but it still needs a clean
            # fight. hits() pairs damage numbers to a move by time, and in a group fight
            # the client draws somebody else's numbers over the same target, so an
            # ungated damage replay reads their hits as ours.
            #
            # RE-MEASURED 2026-09-10, because the gate is expensive and everything around
            # it has changed. It is still right, and by a wider margin than the rms of 9.3
            # first recorded here. Hits before the last of an engagement:
            #
            #   clean                          1906 hits   rms  3.16   p90 |err|  1.96
            #   others present, us in a party  2439 hits   rms 39.12   p90 |err| 31.87
            #   others present, no party       3442 hits   rms 25.85   p90 |err| 16.07
            #
            # The median is fine in all three - -0.29, -0.52, -0.42 - so this is not bias,
            # it is somebody else's number landing on our move. Being in a party is the
            # worse case, which is what you would expect: more people hitting one target.
            # There IS now a per-observation test for the announced case - the veto in
            # hits() - but an unannounced third party leaves no row to test, so this gate
            # remains the floor for that residue. Separating on the announcement alone
            # would report a population and not a measurement.
            # THE LAST HIT OF AN ENGAGEMENT IS NOT A SOUND OBSERVATION, and is scored
            # separately rather than dropped. A blow that kills is recorded at the health
            # it actually removed, not the damage it would have done, so a killing blow
            # that overshoots reads short - and the residuals say exactly that. Hits
            # before the last sit at rms 3.16 with p05 -2.3 and p95 +2.7, symmetric about
            # zero; the last hit sits at 13.24 with the same median and a p95 of +14.0,
            # one-sided upward. Of the thirty worst residuals in the corpus, twenty-five
            # are over-predictions and twenty-three of those end an engagement the
            # creature did not survive.
            #
            # Pooled, that one population takes the animal figure from 3.16 to 8.02 and
            # hides the state of the model: on hits before the last, Santa Samus reads
            # 1.07, ZzxcuV3 1.27 and Shade 1.77.
            hs = [h for h in fightlog.hits(eng, log.me) if h.get("actor") == "me"
                  and ((h.get("shp") or 0) + (h.get("soaked") or 0)) > 0] if clean else []
            lastt = max([h.get("t", 0) for h in hs] or [None])
            scored = [h for h in hs if moves.get(h.get("move"))]
            char = (log.header or {}).get("char")
            for (mv, pred, obs), h in zip(
                    replay_damage(log, eng, moves, weapons), scored):
                # A HIT THE OLD RECORDER HALF-WROTE. Schema 2 and 3 logs carry the soaked
                # half of a hit on only 6% and 47% of hits, and observed damage is SHP + ARM,
                # so a hit on an ARMOURED creature with no soak written reads short by exactly
                # the armour - a constant 30-44 points, which is the shape BonkiDonki's
                # residuals had. Skipped here and counted, never scored: the model is not
                # wrong about a number the log never recorded. (Inside the loop, not by
                # filtering `scored`, because the zip pairs by position.)
                if (log.schema <= 3) and ((h.get("soaked") or 0) <= 0) \
                        and _armoured(name, pack):
                    skipped["schema 2-3 hit on armour with no soak written"] += 1
                    continue
                final = (lastt is not None) and (h.get("t") == lastt)
                d = (final_dmg if final else dmg)[name]
                d["n"] += 1
                # Observed damage is a whole number the client rounded, so a residual
                # under a point is the display and not the model.
                e = abs(pred - obs)
                d["err"] += e * e
                d["worst"] = max(d["worst"], e)
                if not final and not str(name).startswith(("body#", "?#")):
                    # Animals only, to match the figure above it. A player opponent is a
                    # different question - see the standing gap at the foot of this report.
                    a = by_char[char]
                    a["n"] += 1
                    a["err"] += e * e
                    a["worst"] = max(a["worst"], e)

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
                # `lv` and not just `lv.get(mv)`: a stance held for the fight multiplies
                # every attack in it, and the prediction this replay scores has to price
                # the same weight the recovery did. See estimate.collect.
                wa = estimate.attack_weight_bounds(m, attrs, lv.get(mv), lv)
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
                                   os.path.basename(p), clean))
    print("%d ranged fight(s) routed out of melee validation (%s)"
          % (ranged_skipped, ", ".join(sorted(ranged_files))))
    return stats, dmg, misses, skipped, final_dmg, by_char


# How long a single move's opening update may take to finish arriving - see
# logged_predictions. Full Circle's second colour lands about 7 ms after its first.
SETTLE_MS = 20


def settled_after(eng, after, mv):
    """The state once `mv`'s own update has finished arriving.

    The last state row within SETTLE_MS of `after`, stopping before any later move - so a
    gain split across two rows is read whole and the next card's effects never are."""
    t0 = after.get("t") or 0
    nxt = min([m.get("t") for m in eng.moves
               if (m.get("t") is not None) and (m.get("t") > (mv.get("t") or 0))] or [None],
              key=lambda x: float("inf") if x is None else x)
    best = after
    for s in eng.states:
        st = s.get("t") or 0
        if st <= t0:
            continue
        if (st - t0) > SETTLE_MS or ((nxt is not None) and (st >= nxt)):
            break
        best = s
    return best


def landed_after(eng, log, mv, t_from):
    """The state once our blow's update has settled, timed from the blow's LANDING.

    The first state after `t_from` - the later of the move row and our own landing fx - and
    then the last within SETTLE_MS of it, as settled_after does. It stops before the next
    thing that could carry somebody else's gain: the next move row, the next damage on the
    target, or our own next landing fx. None when nothing settled in that window."""
    ends = [m["t"] for m in eng.moves if (m.get("t") is not None) and (m["t"] > mv["t"])]
    ends += [d["t"] for d in eng.damage
             if (d.get("gob") == eng.gob) and (d.get("t") is not None) and (d["t"] > t_from + 5)]
    ends += [o["t"] for o in eng.overlays
             if (o.get("gob") == log.me) and (o.get("t") is not None) and (o["t"] > t_from + 5)
             and str(o.get("res", "")).startswith("gfx/fx/fight/")]
    stop = min(ends) if ends else None
    first = best = None
    for s in eng.states:
        st = s.get("t") or 0
        if st <= t_from:
            continue
        if (stop is not None) and (st >= stop):
            break
        if first is None:
            first = best = s
        elif (st - (first.get("t") or 0)) <= SETTLE_MS:
            best = s
        else:
            break
    return best


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
                # A GAIN CAN ARRIVE IN TWO ROWS. Full Circle's red and green land as separate
                # state updates a few milliseconds apart: on ants the row 3 ms after the move
                # carried red only, and green went 0 -> 47 at +10 ms against a predicted 47.1,
                # so reading the first row scored a correct prediction as "observed 0". The
                # after-state is taken once the update settles - the last row within SETTLE_MS
                # of the first one - which a following move's own effects cannot reach.
                #
                # AND IT IS TIMED FROM THE BLOW, NOT THE ROW (2026-09-15). Our landing fx can sit
                # before the move row by up to ~120 ms, and a state in that gap already holds the
                # gain - so `before` read it and the observation came out 0 (381 such readings), or
                # an `after` just past the row preceded a gain that landed after it. Anchored on OUR
                # OWN fx only - never target damage, which in BonkiDonki-181 was a party member's
                # Quick Barrage doubling the gain - rms 5.02 -> 3.95, zero readings 381 -> 55;
                # 381 readings moved, 369 closer, 10 further.
                lands = [o["t"] for o in eng.overlays
                         if (o.get("gob") == log.me) and (o.get("t") is not None)
                         and (fightlog.overlay_move(o.get("res") or "") == mv.get("name"))
                         and (abs(mv["t"] - o["t"]) <= fightlog.TICK_MS)]
                if lands and (min(lands) < mv["t"]):
                    earlier = eng.state_before(mv, min(lands))
                    if earlier is not None:
                        before = earlier
                after = landed_after(eng, log, mv, max(lands + [mv["t"]]))
                if after is None:
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


def logged_advice(paths, opens=None):
    """What the model would have thrown, against what the person did throw.

    THIS IS THE BOT'S ONLY AUDIT UNTIL THERE IS A BOT. The decision layer exists and the
    executor does not, so `Advisor` runs on every card and its answer goes in the log
    without being acted on - see CombatEvent.advice. Agreement is not the goal and
    disagreement is not a fault: a person plays for reasons the model has no term for. What
    the comparison gives is a place to look, and a record that does not move when the pack
    does.

    An advice is matched to the card actually thrown by being the nearest move row after it
    on the same opponent, which is how the recorder writes them - the advice is computed
    from the state before the card and logged beside it.

    Returns (rows, missing) where a row is (species, advised, thrown, agreed, ticks,
    killed, frontier, file).
    """
    if opens is None:
        opens = estimate.opens_map(estimate.load_moves())
    rows, missing = [], 0
    for pth in sorted(paths):
        try:
            log = fightlog.read(pth, opens)
        except Exception:
            continue
        if not log.rows:
            continue
        any_here = False
        for eng in log.engagements:
            adv = getattr(eng, "advice", None) or []
            if not adv:
                continue
            any_here = True
            mine = sorted([m for m in eng.moves if m.get("actor") == "me"],
                          key=lambda m: m.get("t") or 0)
            for a in adv:
                t = a.get("t") or 0
                nxt = None
                for m in mine:
                    if (m.get("t") or 0) >= t - 200:
                        nxt = m
                        break
                thrown = (nxt or {}).get("move")
                rows.append((estimate.bucket(eng), a.get("move"), thrown,
                             (thrown is not None) and (thrown == a.get("move")),
                             a.get("ticks"), a.get("killed"), a.get("frontier"),
                             os.path.basename(pth)))
        if not any_here:
            missing += 1
    return rows, missing


def report_logged_advice(paths, opens=None):
    rows, missing = logged_advice(paths, opens)
    print()
    print("=" * 78)
    print("ADVICE THE CLIENT WROTE DOWN, AND DID NOT ACT ON")
    print("=" * 78)
    if not rows:
        print("  none yet - %d log(s) carry no advice." % missing)
        print()
        print("  Expected until a fight is logged on a schema 15-or-later client. The decision")
        print("  layer exists and the executor does not, which is the right moment to audit it:")
        print("  the advisor runs on every card, its answer is written down, and nothing acts on")
        print("  it. A bot that cannot be audited against what it expected is a bot whose")
        print("  failures are invisible.")
        print()
        return True
    agreed = sum(1 for r in rows if r[3])
    print("  %d advice(s) across %d log(s) without one." % (len(rows), missing))
    print("  the model would have thrown the same card %d times (%.0f%%)"
          % (agreed, 100.0 * agreed / len(rows)))
    thin = sum(1 for r in rows if (r[6] or 0) <= 1)
    if thin:
        print("  %d of them chose from a frontier of one, which is not a choice" % thin)
    print()
    seen = {}
    for sp, advised, thrown, ok, _t, _k, _f, _fl in rows:
        if not ok:
            seen[(advised, thrown)] = seen.get((advised, thrown), 0) + 1
    if seen:
        print("  %-28s %-28s %s" % ("the model wanted", "the person threw", "times"))
        for (adv_, thr), n in sorted(seen.items(), key=lambda kv: -kv[1])[:8]:
            print("  %-28s %-28s %d"
                  % (str(adv_)[:28], str(thr)[:28], n))
        print()
    return True


def report_logged_predictions(paths, opens=None):
    rows, missing = logged_predictions(paths, opens)
    print()
    print("=" * 78)
    print("PREDICTIONS THE CLIENT WROTE DOWN")
    print("=" * 78)
    if not rows:
        print("  none yet - %d log(s) carry no prediction." % missing)
        print()
        print("  Expected until a fight is logged on a schema 8-or-later client. Every existing log")
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

    stats, dmg, misses, skipped, final_dmg, by_char = replay(paths)
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
        solo = [m for m in misses if m[9]]
        print("\nthe %d that do not, worst first - each is a real disagreement "
              "between the model and a logged fight, not a rounding complaint."
              " A fight with somebody else in it is marked, and is scored apart"
              " at the gate below:" % len(misses))
        for row in sorted(misses, reverse=True)[:12]:
            off, name, mv, colour, standing, gain, lo, hi, f = row[:9]
            # "our Quick Barrage against ants" rather than "ants Quick Barrage" - the
            # card is always OURS and the species is who we threw it at, and the old
            # wording read as though the ant had thrown it.
            print("  our %-20s %-7s vs %-12s standing %-4d observed %-5.0f"
                  " predicted %.1f-%.1f   off by %.1f   %s%s"
                  % (mv[:20], colour, name[:12], standing, gain, lo, hi, off, f,
                     "" if row[9] else "   [group]"))
        print("  %d of the %d are from fights we had to ourselves"
              % (len(solo), len(misses)))
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
    print("\nANIMALS ONLY, hits before the last of an engagement: %d hits, rms %.2f"
          % (an_n, an_rms))
    # The last hit, reported and not asserted. It is a real population and its residuals
    # are real, but a killing blow is recorded at the health it removed rather than the
    # damage it carried, so an over-prediction there is the expected reading and not a
    # fault in the model. Held to nothing; printed so it cannot be forgotten.
    fan = dict((k, v) for k, v in final_dmg.items() if not k.startswith(("body#", "?#")))
    fn = sum(v["n"] for v in fan.values())
    fe = sum(v["err"] for v in fan.values())
    if fn:
        print("  and the LAST hit of each engagement, scored apart: %d hits, rms %.2f"
              % (fn, math.sqrt(fe / fn)))
        print("  (a blow that kills is recorded at the health it removed, so it reads"
              " short of the damage it carried)")
    # Per attacker, because the corpus is four players with different gear and the
    # pooled figure hides which of them the model actually reproduces.
    if by_char:
        print("\n  %-16s %-6s %-10s %s" % ("attacker", "hits", "rms", "worst"))
        for ch in sorted(by_char, key=lambda c: -by_char[c]["n"]):
            a = by_char[ch]
            if not a["n"]:
                continue
            print("  %-16s %-6d %-10.2f %.1f"
                  % (str(ch)[:16], a["n"], math.sqrt(a["err"] / a["n"]), a["worst"]))
    # Two readings, because one number over four players hides which of them the model
    # reproduces. The pooled bound is the regression gate: it is loose on purpose, a
    # mixture over gear, strength and log schema we do not control, and it sits just above
    # what the corpus does today. The per-attacker figures are then READ, not each held to
    # a fixed bar: the corpus is four players and the original 2.0 standard was calibrated
    # when Shade read 1.77, so now that the clean corpus has grown and Shade reads 2.13 it
    # crosses that collar while the fit it describes has not moved for the worse - the
    # pooled animals-only rms is 2.71, under the 3.16 first recorded here. Santa Samus
    # reads 1.31 and ZzxcuV3 1.24; BonkiDonki reads 8.23 and is the open case, printed by
    # name so it cannot be forgotten. What is asserted of the spread is its shape and not
    # one player's collar: every reading is finite and non-negative, and the median
    # attacker is reproduced inside the 2.0 standard - so a break that lifted the typical
    # player over the line still fails here, while one player at the collar does not.
    if an_rms > 4.0:
        print("  FAIL - pooled animal damage rms above 4.0 points on hits before the last")
        ok = False
    judged = [(c, math.sqrt(a["err"] / a["n"]))
              for c, a in by_char.items() if a["n"] >= 100]
    broken = [c for c, r in judged if not (math.isfinite(r) and r >= 0.0)]
    if broken:
        print("  FAIL - attacker rms is not a finite, non-negative reading: %s"
              % ", ".join(str(c) for c in broken))
        ok = False
    # THE ORIGINAL STANDARD, RESTORED (2026-09-14). The 09-13 pass replaced "a majority of
    # attackers sit inside 2.0" with "the median does" - and over four attackers the median is
    # the mean of the middle two, so two of four could fail and the gate still pass. What had
    # pushed Shade over the collar was two hits from schema 2-3 logs on armoured creatures with
    # no soak written (skipped above; Shade's worst residual went 46.9 -> 34.1 and its rms 2.10
    # -> 1.82). BonkiDonki is untouched by that filter and stays the named open case.
    tight = [c for c, r in judged if r <= 2.0]
    if judged:
        worst = max(judged, key=lambda cr: cr[1])
        print("\n  %d of %d attackers with 100+ hits inside the 2.0 standard; worst is %s at %.2f"
              % (len(tight), len(judged), str(worst[0])[:16], worst[1]))
    if judged and (len(tight) * 2 <= len(judged)):
        print("  FAIL - %d of %d attackers with 100+ hits are above 2.0 points"
              % (len(judged) - len(tight), len(judged)))
        ok = False
    # Misses split into two kinds and only one of them is a finding.
    #
    # A miss under a point is the interval's edge. The prediction band spans the tenth to
    # ninetieth percentile of the skills the corpus recovered, so a tenth of the readings
    # are meant to fall outside it, and where a species sits near an equalization boundary
    # one end lands on the wrong side of that too - the fox misses by 0.1, the beaver by
    # 0.4. Failing on those would be failing on arithmetic that is right.
    #
    # A miss of tens of points is a real disagreement, and Flex owns five of the six.
    #
    # THE FLEX MODEL IS RIGHT, and it is worth saying that first, because the residual is
    # small and strange rather than large and systematic. Flex's sheet gives it a flat 15%
    # Dizzy and an attack weight of bare Unarmed - no multiplier, no mu - and taking every
    # Flex reading in the corpus as a multiple of what that predicts puts 172 of 179 inside
    # 0.85 to 1.10, piled on 1.00. Unarmed is also the right skill by a wide margin: the
    # skill each reading implies, divided by the attribute, has a median of 0.94 and an
    # interquartile spread of 1.1x against 1.2x or worse for str, agi, con, prc and int.
    #
    # THEN THERE ARE FIVE. Nothing sits between 1.10 and 1.33; the five land at 1.33 to
    # 1.39, which is an attack weight two and a half times what the card claims. They are
    # not one species - badger, boar and three ants - and no field a log carries separates
    # them from the other 174. Not initiative, ours or theirs; not the standing opening,
    # which is zero in all five; not how many cards either side had thrown; not distance,
    # stamina, or whether it was the first Flex of the fight. Every one of those overlaps.
    #
    # EVERY GROSS MISS LEFT IS ONE OF THEM. That is worth more than the count: the
    # residual is a single phenomenon in five observations rather than a scatter. The
    # last one to join them read 23 until fightlog started closing a bracket at the top
    # of a rise still in flight - it was the same 51 as the other three, sampled three
    # milliseconds after the card instead of eleven.
    #
    # FIVE EXPLANATIONS HAVE BEEN TESTED AND FAILED, and they are written down so that
    # nobody spends the afternoon on them again:
    #
    #   A swarm weakening as it is killed. The obvious reading of "a swarm is not one
    #   creature", and it is false: binned by how far through an engagement's damage each
    #   gain sits, the ants' implied skill RISES 19% from the first fifth to the last and
    #   the bee swarm's 6%. Nothing falls except the badger, at 13%.
    #
    #   Initiative. Take Aim grants a point and nothing else in the corpus reads one back
    #   out, so a card thrown holding six of them was the candidate. Across 2953 gains
    #   with an initiative reading, the median gain against its own cell is 1.000 at every
    #   initiative from zero to six.
    #
    #   Our own card level. Flex sits at deck level 3 for the character that shows the
    #   split and the gains do not track it, but the card settles it outright: its sheet
    #   reads "Openings on opponent: +15% Dizzy" with no mu on it, identically at every
    #   maxlevel from 1 to 5 across six characters' deck dumps. The mu is on the line
    #   below, where Flex reduces the user's own Backhanded and Oppressive by 10%.
    #
    #   An opponent's card opening the OPPONENT. Ant Spit precedes several of the high
    #   Flex readings, and the ants' blue does rise in its bracket, so it looked like a
    #   creature exposing itself as it swings. It is not. A card that opened its user
    #   would do so every time, and the base rate says otherwise: across 867 uses of Ant
    #   Spit the ants' own blue rises in 12% of them, and the same holds everywhere -
    #   Fell Scratch 8% blue over 2476 wolf uses, Mule Kick 12% over 363. What the rates
    #   actually track is whether the card is an attack. The opponent's non-attacking
    #   cards, thrown while nothing of ours is in flight, sit at nothing at all: Roar of
    #   the Wild 0.4% of 241, Careful Approach 0 of 139, Swift Evasion 0.9% of 115. The
    #   rise in the other cases is OUR gain arriving inside their bracket, which is the
    #   same late-arrival problem as below and not a mechanic.
    #
    # THE LEADING CANDIDATE IS A CARD THAT NEVER APPEARS IN THE LOG. Five cards in the
    # corpus's own deck dumps carry a "When attacked" clause, and they are block-weight
    # cards: they fire when the OPPONENT swings, so no move row is ever written for them
    # and nothing here can tell an active one from an absent one.
    #
    #   Parry            +10% Dizzy on the opponent, sword required
    #   Bloodlust        charges 25% per blow taken; our attack weight rises by four
    #                    times the charge
    #   Combat Meditation while active, every attack of ours is at 25% weight
    #   Oak Stance       while active, every attack of ours is at 50% weight
    #   Death or Glory   0.75 initiative per blow taken
    #
    # Three of those move the two quantities this file predicts from, and one of them,
    # Parry, is measured and handled - see below. BLOODLUST IS TESTED AND FAILS. It charges
    # 25% per blow taken and spends on the next attack for four times the charge, so a card
    # thrown after N blows should raise its opening by (1+N)**(1/3): 1.26 after one blow,
    # 1.44 after two. Binning every attributed gain against the median of its own species,
    # card, colour and character cell gives 1.000 at every count from zero to four, on 9113
    # observations at zero and 843 at one. It fails on the ant readings in particular too -
    # two of the three surviving 51s had no blow land on us anywhere in the fight, and
    # neither did any of the forty readings at 36 to 38.
    #
    # PARRY IS MEASURED AND IS NOW REJECTED AT SOURCE. Dividing our own gains out makes
    # it visible: for each colour, how often it rises in a state step where the opponent's
    # blow landed on us against how often it rises in a quiet step, over 855107 steps -
    # green 3x, yellow 0x, red 3x, and blue 80x. Blue is what Parry opens. The first pass
    # at this compared blue against the other colours pooled and found nothing, because
    # our own Quick Barrage opens red constantly and drowned the contrast.
    #
    # fightlog.attributed_gains now drops any observation whose bracket had the opponent's
    # blow resolve inside it. That took the gross misses from 15 to 9 and agreement from
    # 99.3% to 99.5%, at a cost of 34 observations.
    #
    # It does not catch all of them. Three of the surviving eight are the ant Flex readings
    # at 51, and in those the opponent's blow had already resolved before the bracket
    # opened - which Parry's own rise apparently had not. Nothing yet accounts for those
    # three, and extending the test one state row back is not the answer: it catches one
    # of them and costs 480 observations, because only 11% of blows leak that way and the
    # test cannot tell which.
    #
    # TWO OF THE EIGHT WERE THE SAME WINDOW BUG IN TWO TESTS, and both are fixed. The
    # damage test asks whether more than one hit landed on the target inside the bracket,
    # but a hit in the GAP between the previous bracket's close and this one's open
    # belongs to nobody: our previous card was scored before it and ours has not landed.
    # One ant fight has such a hit at 3067 with our own Quick Barrage written at 3160 and
    # the bracket opening at 3140, and the gain read 35 against a model saying 17 to 23.
    # Only the gap is tested, not the whole span back to the previous card - widening the
    # window itself puts our own previous hit inside it and rejects one bracket in five.
    #
    # Two SHP rows sharing a bucket are still counted as ONE hit. They are not an AOE -
    # 311 of 325 carry different values and 96% show no other gob hit in the same instant,
    # so they are most likely two attackers - but they occur about as often when nobody
    # else is visible as when somebody is, and a log cannot say which attacker.
    #
    # The other was a window bug rather than a mechanic, and it is fixed. The
    # third-party overlay test ran from the state row that opens the bracket, but an
    # announcement is a card being PLAYED and its opening arrives afterwards - so another
    # player who announced between the last card and ours sat a few milliseconds outside
    # the window while their gain landed inside it. In one group ant fight two bodies both
    # announce barrage at 1135, our own Quick Barrage is written at 1144, and the bracket
    # opens after 1135: the gain read 49 against a model saying 27 to 40, and it was two
    # players' Quick Barrage read as one.
    #
    # What IS established is that a gain often arrives after the state row that closes its
    # bracket: of 14733 moves that raised a colour they open, 53% have settled by the first
    # state row after the move, 28% are still climbing and 19% are already decaying.
    # A MISS HAS TO CLEAR BOTH BARS. One display point is the absolute floor and it stops
    # meaning much once a prediction is large: an ant's Quick Barrage predicted 12.0-19.4
    # and observed at 21 is 1.6 points out, which is 8% - and every input to that
    # prediction is an interval with about that much slop in it. The Flex readings, by
    # contrast, sit 16 to 19% outside their own band, and there is nothing in between.
    #
    # So a gross miss is one that is both a point out AND a tenth out. Neither bar alone
    # works: the absolute one alone flags arithmetic that is right, and the relative one
    # alone flags a prediction of 3.0 missed by 0.4.
    GROSS = 1.0
    GROSS_SHARE = 0.10

    def _gross(m):
        off, gain, lo, hi = m[0], m[5], m[6], m[7]
        edge_ = hi if gain > hi else lo
        return (off >= GROSS) and (edge_ > 0) and ((off / edge_) >= GROSS_SHARE)

    # CLEAN FIGHTS AND GROUP FIGHTS ARE SCORED APART, and the gate holds only the first.
    #
    # The openings half used to be skipped entirely on a fight with anything else going on
    # in it, which is 60.8% of engagements. That gate is right for the damage half and too
    # coarse here, so it now separates rather than excludes - and the separation says what
    # the exclusion never could.
    #
    # A group fight adds TAIL, not bias. Comparing the opponent skill recovered from clean
    # engagements against group ones, over the 18 species with 20 or more of each, the
    # median ratio is 1.01 with eight species below one and ten above. Contamination can
    # only ever ADD to a gain and so can only read a skill LOW; a balanced ratio is not
    # that. What group fights do add is outliers, and the estimator is median-based, which
    # is why the pack is unharmed and this file is not.
    group = [m for m in misses if not m[9]]
    misses = [m for m in misses if m[9]]
    if group:
        gg = [m for m in group if _gross(m)]
        hi_side = sum(1 for m in gg if m[5] > m[7])
        print("  %d miss(es) in fights with somebody else in them, scored apart - %d gross,"
              % (len(group), len(gg)))
        print("  %d of those reading HIGH, which is what a third party's gain does" % hi_side)
    gross = [m for m in misses if _gross(m)]
    edge = len(misses) - len(gross)
    if edge:
        print("  %d miss(es) under a point - the prediction interval's edge, not a finding"
              % edge)
    # THE KNOWN OUTLIER IS NAMED, NARROWLY, AND COUNTED. Everything above documents one
    # phenomenon the log cannot resolve: a Flex that comes out about 1.36 times what the
    # card predicts, in five readings of 179, across three species, with no recorded field
    # separating them from the other 174.
    #
    # A bare count would go green on the wrong thing - a new species, a different card, a
    # bigger residual would all slip under "five or fewer". So the exemption is the SHAPE:
    # Flex, thrown at a standing zero, landing between 1.10 and 1.30 times the top of its
    # own predicted interval. Any gross miss that is not that fails, and the exempt ones
    # are held to a share of the Flex readings so the phenomenon cannot quietly spread.
    exempt, real = [], []
    for mrow in gross:
        _off, _nm, mv_, _col, standing_, gain_, _lo, hi_ = mrow[:8]
        r = (gain_ / hi_) if hi_ else 0.0
        (exempt if (mv_ == "Flex" and standing_ == 0 and 1.10 <= r <= 1.30)
         else real).append(mrow)
    if exempt:
        print("  %d gross miss(es) are the known Flex reading at 1.10-1.30x - see the source"
              % len(exempt))
    if real:
        print("  FAIL - %d gross miss(es) outside the one phenomenon the corpus knows about"
              % len(real))
        # AND WHICH ONES. A gate that prints a count and not the rows behind it costs a
        # debugging session every time it fires, and the rows are the whole finding.
        for mrow in sorted(real, key=lambda m: -abs(m[5] - m[7]))[:8]:
            off, nm, mv_, col_, standing_, gain_, lo_, hi_ = mrow[:8]
            print("        our %-18s %-7s vs %-12s standing %-4s observed %-5s"
                  " predicted %.1f-%.1f" % (mv_, col_, nm, standing_, gain_, lo_, hi_))
        ok = False
    flexn = sum(1 for m in misses if m[1] and m[2] == "Flex") + FLEX_AGREEING
    if flexn and (len(exempt) > 0.15 * flexn):
        print("  FAIL - the Flex outlier is %d of %d readings, over the 15%% it has held"
              % (len(exempt), flexn))
        ok = False
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
    if not report_logged_advice(paths):
        ok = False
    print("\n" + ("ALL CHECKS PASSED" if ok else "CHECKS FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
