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
# The wiki-derived pack. Its stated hitpoints and armour are the baseline our own
# measurements bound rather than replace - see summarise_hp.
CREATURES = os.path.join(ROOT, "data", "combat", "creatures.json")
# Curated facts the wiki dump does not carry - chiefly which creatures scale with mine
# depth, whose single stated figure is one depth rather than a nominal individual.
NOTES = os.path.join(ROOT, "data", "combat", "creature-notes.json")

# The deck weighting, applied to a move's ATTACK WEIGHT.
#
# mu is not one global multiplier. It scales whatever the move's headline quantity is,
# and the sheet says which per move - an attack's attack weight, a maneuver's opening, a
# defensive card's block weight, or Take Aim's cooldown, which it divides. See mu_ratio
# for why that distinction is not cosmetic: it makes the correction linear for one kind
# of move and cubed for another.
#
# This constant is the attack-weight one, and it is 1.0 here as a measurement rather than
# an assumption - see MU_MIN below.
MU = 1.0

# The deck weighting runs 1.0 to 1.5, rising with the points put into a card. That is the
# devs' own statement quoted on the wiki - "the actual value of mu ranges from 1 to 1.5,
# depending on your weighting" - and carries the same weight as Jorb's armour notes, which
# the damage model already treats as authoritative. Two useful things follow.
#
# First, MU = 1.0 above is not a blind assumption for this corpus: the character's deck
# dump shows Quick Barrage, Full Circle, Cleave and Knock Its Teeth Out all at level 1,
# and Take Aim - also level 1, and the one move whose cooldown divides by mu - reporting
# its listed 30 exactly. So mu is 1.0 at level 1, measured, and every defence weight
# recovered from those four moves is a true figure rather than a proportional one.
#
# Second, it bounds the disagreement between two moves. Since Wd_true = mu * Wd_measured,
# two moves against one opponent must satisfy mu_b/mu_a = Wd_a/Wd_b, and that ratio
# cannot leave 1/1.5 to 1.5. A wider gap is not a deck-level difference and needs another
# explanation.
MU_MIN, MU_MAX = 1.0 / 1.5, 1.5

# The card cap is 5 for everything except stances. A linear curve across those five
# levels - 1.0 to 1.5 - is the obvious shape, and mu_at_level() below computes it, but
# NOTHING in the estimator depends on it: mu enters through mu_bounds() as the stated
# range, so a levelled card widens its own answer instead of leaning on a guessed curve.
#
# Attempting to measure the curve from fights was a mistake worth recording. mu is ours,
# not the opponent's, so a fight has nothing to say about it - and the attempt behaved
# exactly like the second-order inference it was, moving by a fifth depending only on
# where a noise threshold sat.
#
# Note the deck dump's "maxlevel" is how far the character has LEARNED a move, not the
# game's ceiling - a move showing a max of 1 has been picked up once.
MU_LEVELS = 5


def mu_at_level(level):
    """The deck weighting a card at this level would have, on the linear hypothesis."""
    if not level or level < 1:
        return None
    return 1.0 + (MU_MAX - 1.0) * (min(level, MU_LEVELS) - 1) / (MU_LEVELS - 1)


def mu_bounds(level):
    """What a card's deck weighting can be, as an interval. This is an INPUT.

    mu is ours. It is a property of a card at a level, the game states its range, and
    nothing about an opponent changes it - so there is nothing here for a fight to
    reveal. The one unknown in the opening formula is the opponent's Wd, and every fight
    is solving for that.

    Which is why this returns an interval rather than a number. At level 1 the interval
    is a point, because Take Aim measures mu = 1.0 there exactly. Above level 1 the curve
    is unknown, so the honest input is the whole stated range from 1.0 up, and a card
    that has been levelled simply yields a WIDER Wd - not a wrong one, and not one that
    needs mu estimated first.

    Trying to derive mu from fights instead was a second-order inference on top of the
    quantity actually being measured, and it behaved like one: the estimate moved by a
    fifth depending only on where a noise threshold was set.
    """
    if not level or level < 1:
        return (MU, MU)
    if level == 1:
        return (1.0, 1.0)
    # Level 2 cannot be below level 1, and nothing can exceed the stated ceiling. A
    # measured curve would narrow this; until there is one, the range is the input.
    return (1.0, MU_MAX)


def mu_ratio(wd_a, wd_b):
    """How much bigger move b's deck weighting is than move a's. ATTACKS ONLY.

    mu does not do one thing. It multiplies whatever the move's headline quantity is, and
    the sheet says which per move:

        attack             the ATTACK WEIGHT     "According to weapon * mu"
        defensive maneuver the REDUCTION         "Reduces: 20% * mu Striking"
        block card         the BLOCK WEIGHT      "Block weight: * 250% * mu"
        Take Aim           divides the COOLDOWN  "Cooldown: 30 / mu"

    The openings an attack inflicts are flat - a plain "+10% Cornered", never scaled. So
    for the moves this estimator uses, mu enters only through Wa, and

        Wd_true = mu * Wd_measured

    which is linear, and the ratio below falls straight out of it.

    Were a move ever to carry mu on the OPENING instead, the measured k would be inflated
    by mu and the correction would be Wd_true = mu**3 * Wd_measured - cubed, and applying
    the linear form to it would be 125% wrong at mu 1.5. Nothing in the sheet does that
    today; collect() refuses such a gain rather than leaving it as a silent assumption.

    No cube root and no reciprocal: the first version had both, which compressed every
    difference towards 1 and so read as healthy exactly when it was hiding the most - a
    true ratio of 1.5 printed as 0.87.
    """
    return wd_a / wd_b if wd_b > 0 else 0.0


def deck_history():
    """Every deck the client has dumped, as (wall time, {move: level}), oldest first.

    A deck changes, and a measurement has to be read against the deck it was made with.
    Using today's instead is not a small error: Quick Barrage sat at level 1 through most
    of this corpus and has since been dropped, so judging those fights by the current deck
    marks all of them level 0 and quietly excludes them from every mu comparison - which
    made three badgers look inconsistent when they were measured with a move the filter
    had thrown away.

    The dumps carry their own millisecond timestamp in the filename, and the client writes
    one when a fight starts, so the deck in force for a log is the newest dump at or
    before it.
    """
    out = []
    for d in fightlog.find_log_dirs(ROOT):
        for p in glob.glob(os.path.join(d, "deck-*.json")):
            stamp = os.path.basename(p).rsplit("-", 1)[-1].split(".")[0]
            try:
                when = int(stamp)
            except ValueError:
                continue
            try:
                with open(p, "r", encoding="utf8") as f:
                    doc = json.load(f)
            except (OSError, ValueError):
                continue
            body = doc.get("body", doc)
            moves = body.get("moves") or []
            # A probe fired while the sheet was still loading has the text but no levels.
            levels = dict((m.get("name"), m.get("decklevel")) for m in moves
                          if m.get("name") and m.get("decklevel") is not None)
            if levels and any(v > 0 for v in levels.values()):
                out.append((when, levels))
    out.sort()
    return out


DECKS = deck_history()


def levels_at(when):
    """The deck in force at a wall time - the newest dump at or before it.

    Falls back to the earliest known deck for a fight that predates every dump, which is
    better than reporting no levels at all: the deck it was fought with is more likely to
    resemble the oldest one on record than today's.
    """
    if not DECKS:
        return {}
    best = DECKS[0][1]
    for stamp, levels in DECKS:
        if when is not None and stamp > when:
            break
        best = levels
    return best


LEVELS = levels_at(None) if not DECKS else DECKS[-1][1]


def load_moves():
    if not os.path.exists(SHEET):
        return {}
    with open(SHEET, "r", encoding="utf8") as f:
        doc = json.load(f)
    return dict((m["name"], m) for m in doc.get("moves") or [] if m.get("name"))


def attack_weight(move, attrs, level=None):
    """Wa for one of our moves: the skill its icon names, its own multiplier, and mu.

    "According to weapon" moves name no skill, and the sheet's closing note resolves
    those to Melee Combat.

    mu comes from the card's level at the time of the fight. Leaving it at 1.0 for a
    levelled card is not neutral - it understates that move's attack weight and so
    understates every defence weight recovered from it. Punch at level 5 was reading a
    badger 20 points lighter than the same badger measured with an unlevelled move,
    purely from the missing factor.

    The level-to-mu curve is a hypothesis (see MU_LEVELS), so every number downstream
    inherits it for LEVELLED moves only. Level 1 is measured at exactly 1.0, and almost
    everything in this corpus is level 1, so most of it is unaffected either way.
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
    mu = mu_at_level(level) if level else MU
    return base * mult * (mu if mu else MU)


def attack_weight_bounds(move, attrs, level=None):
    """Wa as the interval the card's level allows. See mu_bounds - mu is an input.

    At level 1 this is a point, because mu is measured at exactly 1.0 there. Above it the
    interval carries the whole stated range, so a levelled card yields a wider defence
    weight rather than a wrong one.
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
    lo, hi = mu_bounds(level)
    return (base * mult * lo, base * mult * hi)


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
    # The search runs over the TOTAL soak and how it splits, bounded by the largest
    # amount ever seen absorbed. Absorption saturates at hard + soft once a hit is big
    # enough, so no total above that is possible - and bounding it this way is what
    # keeps the grid cheap enough to cover a mammoth's stated 125.
    #
    # A fixed 0..60 grid was the first version, and it silently could not reach the
    # bear's 65: the true fit sits at 65 + 0 with no residual at all, and the search
    # returned 60 + 5 with an error of 1.56 because that was the best it could see.
    # Absorption only saturates at hard + soft once a hit is big enough to get through.
    # If nothing ever did, the largest absorption seen is just our largest HIT, and says
    # only that the armour is at least that much - a cachalot's 150 looks like 20 if all
    # we ever landed on it were twenties. Reporting a fitted total there would be a
    # confident number for a quantity the data does not contain.
    penetrated = any(shp > 0 for _raw, shp in pts)
    biggest = int(max(h["soaked"] for h in hits))
    if not penetrated:
        return {"hard": None, "soft": None, "n": len(pts), "rms": 0.0,
                "total": (biggest, None), "identified": False, "penetrated": False}
    top = biggest + 2
    scored = []
    for total in range(0, top + 1):
        for soft in range(0, total + 1):
            hard = total - soft
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
    # Ties are judged against a quarter of a squared point per observation, because the
    # numbers are integers and a half-point residual is indistinguishable from rounding.
    # A tighter tolerance manufactures certainty: the lynx's 33+2 beats 35+0 by half a
    # squared point across 28 hits, on the strength of one raw-35 hit that reads 1 in one
    # instance and 0 in another, and reporting that as an identified split would be
    # reading the rounding.
    tol = err + (0.25 * len(pts))
    tied = [(h, s) for e, h, s in scored if e <= tol]
    totals = set(h + s for h, s in tied)
    return {"hard": hard, "soft": soft, "n": len(pts),
            "rms": math.sqrt(err / len(pts)),
            "total": (min(totals), max(totals)),
            "identified": len(tied) == 1, "penetrated": True}


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

# The smallest opening gain worth reading a POINT estimate from.
#
# k = gain / (Ob * (1 - Oc)) and Wd = Wa / k**3, so half a point of rounding on an
# integer gain becomes a third-power error on the defence weight. It is not a rounding
# nuisance, it is the dominant term at small gains:
#
#     gain 20   ->  Wd within about  8%
#     gain 13   ->  Wd within about 12%
#     gain 10   ->  Wd within about 17%
#     gain  5   ->  Wd within  -37% .. +25%
#     gain  3   ->  Wd within  -73% .. +37%
#
# Weighting a gain of 3 the same as a gain of 20 is what made every mu comparison
# unreadable. Two cards known to be at the same level - and so certain to have the same
# mu - read anywhere from 0.44 to 2.25 of each other with everything pooled, and 0.93 to
# 1.08 once only gains of 13 or more are counted. The model was never the problem.
#
# Interval arithmetic already handles this correctly, since a small gain simply yields a
# wide band. This threshold is for the places a single number is wanted.
MIN_GAIN = 10


def gain_interval(wa, gain, ob, standing, wa_hi=None):
    """The defence weight an observed gain allows, as an interval.

    Because the weight enters through a cube root, the slop above becomes a wide band at
    small gains and a narrow one at large: a +4 into an opening already at 52% constrains
    almost nothing, while a +24 into a fresh one constrains tightly. Reporting a midpoint
    alone hides which of those two an estimate is.
    """
    oc = standing / 100.0
    # Wd = Wa / k**3, so the low end pairs the smallest attack weight with the largest
    # gain and the high end does the opposite. Passing an interval for Wa is how a card
    # whose level leaves mu uncertain widens the answer instead of biasing it.
    lo = model.defence_weight(wa, gain + GAIN_SLOP, ob, oc)
    hi = model.defence_weight(wa_hi if wa_hi else wa, max(0.1, gain - GAIN_SLOP), ob, oc)
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
    wiki = wiki_creatures()
    opens = opens_map(moves)
    per = defaultdict(lambda: {
        "engagements": 0, "skipped": [], "wd": [], "cd": defaultdict(set),
        "hits": [], "their_moves": defaultdict(set), "agi_me": set(), "took": [],
        "res": None, "hp": None, "wd_by_gob": {},
        "last_hit": {}, "partial": set(), "soak": [],
        "mu_scaled_openings": set(), "wd_by_gob_move": {}, "deck_at": {},
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
        # The deck as it stood for THIS fight, so a card's mu is the one it was used at.
        lv = levels_at((log.header or {}).get("wall"))
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
            hits = [d for d in eng.damage
                    if d.get("ch") == "SHP" and d.get("gob") == eng.gob]
            rec["dealt"][eng.gob] += sum(d["v"] for d in hits)
            # Armour reads off every hit the creature took, whoever threw it: the ratio
            # of absorbed to through is a property of the armour, not of the attacker.
            rec["soak"].extend(fightlog.soak_pairs(eng))
            # The killing blow, for the overkill bound - it is the last damage this
            # opponent took, and however much of it exceeded the opponent's remaining
            # health is not evidence of anything.
            if hits:
                rec["last_hit"][eng.gob] = hits[-1]["v"]
            # Deliberately NOT gated on whether anyone else was attacking. The client
            # draws a floating number over a creature for damage from any source, so our
            # total is the creature's total intake while it was in view - which is what
            # the ceiling below needs. What can still be missed is a fight that started
            # before we could see it, and no flag in a log detects that.
            if died(eng, log):
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
                ob, ob_scales_with_mu = None, False
                for o in m.get("openings") or []:
                    if o.get("colour") == colour:
                        ob = o.get("pct")
                        ob_scales_with_mu = bool(o.get("mu"))
                if not ob:
                    continue
                if ob_scales_with_mu:
                    # This move's OPENING carries the deck weighting, not its attack
                    # weight - "Openings: 20% * mu Off Balance". The correction is then
                    # cubed rather than linear (see mu_ratio), so mixing one of these in
                    # with the attacks would be wrong by 125% at mu 1.5. Every move that
                    # does this today is a maneuver with no attack weight at all, so the
                    # case does not arise; refusing it here is what keeps that from being
                    # a silent assumption.
                    rec["mu_scaled_openings"].add(name)
                    continue
                bounds = attack_weight_bounds(m, attrs, lv.get(name))
                if not bounds or not bounds[0]:
                    continue
                wa, wa_hi = bounds
                wd = model.defence_weight(wa, gain, ob, standing / 100.0)
                if wd > 0:
                    lo, hi = gain_interval(wa, gain, ob, standing, wa_hi)
                    rec["wd"].append((name, colour, standing, gain, wa, wd, lo, hi))
                    rec["wd_by_gob"].setdefault(eng.gob, []).append((lo, hi, wd))
                    # Per individual AND per move. mu can only be read between two moves
                    # thrown at the same creature - see report_mu.
                    rec["wd_by_gob_move"].setdefault(eng.gob, {}).setdefault(
                        name, []).append((wd, lo, hi, gain))
                    rec["deck_at"][eng.gob] = lv

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
        rec["wiki"] = wiki_for(wiki, rec["res"])
        rec["hp"] = summarise_hp(rec["dealt"], rec["killed"], rec["last_hit"],
                                 wiki_for(wiki, rec["res"]))
    return per, moves


# Players all share this resource, and a fight against one ends in a knockout rather
# than a death.
PLAYER = "borka"


def died(eng, log):
    """Whether this engagement ended with the opponent dead.

    The client draws an opaque-white number when a fight ends, on the gob of whoever won
    it. Every white in the corpus lands in the last moments of its log, which is what
    made it look like a kill marker - but one lands on the OPPONENT'S gob, in a spar this
    character lost, where nothing died at all. So the signal is the award, not the death,
    and reading it correctly needs two conditions.

    The award must be on someone other than the opponent, which excludes the fight we
    lost. And the opponent must not be a player, because beating one of those is a
    knockout. What is deliberately NOT required is that the award be ours: two boars and
    a bear in this corpus were finished by other people, and their hitpoints count just
    the same.
    """
    if PLAYER in (eng.res or ""):
        return False
    return any(d.get("ch") in ("#ffff", "C65535") and d.get("gob") != eng.gob
               for d in eng.damage)


def norm(name):
    """A creature key stripped to letters, so "Wild Bees" and "wildbees" agree."""
    if not name:
        return None
    return name.lower().replace(" ", "").replace("'", "").replace("-", "")


def wiki_for(wiki, res):
    """The wiki entry for a resource path, which does not always end in the creature.

    "gfx/kritter/wildbees/beeswarm" is the swarm the wiki calls Wild Bees, and its name
    is the directory rather than the file. Trying every segment costs nothing and is the
    difference between having a baseline for that creature and not.
    """
    if not res:
        return None
    for part in reversed(res.split("/")):
        e = wiki.get(norm(part))
        if e is not None:
            return e
    return None


def depth_scaled():
    """Creature keys whose stats scale with the mine floor they were found on."""
    if not os.path.exists(NOTES):
        return set()
    with open(NOTES, "r", encoding="utf8") as f:
        doc = json.load(f)
    return set(norm(n) for n in
               (doc.get("depth_scaled") or {}).get("creatures") or [])


def wiki_creatures():
    """The wiki's stated stats, keyed the same way as our own buckets."""
    if not os.path.exists(CREATURES):
        return {}
    with open(CREATURES, "r", encoding="utf8") as f:
        doc = json.load(f)
    out = {}
    for e in doc:
        out[norm(e.get("name"))] = e
    return out


def wiki_value(entry, field):
    if not entry:
        return None
    v = entry.get(field)
    if isinstance(v, dict):
        return v.get("value")
    return v


def summarise_hp(dealt, killed, last_hit, wiki_entry):
    """Hitpoints, as the range a fresh one of these could have.

    The wiki's stated figure is the baseline and it is a good one. Its boar died three
    times here at 453, 483 and 499 against a stated 450; its stated armour of 15 for the
    boar, 65 for the bear and 35 for the lynx are each exactly what our own hits
    recovered. Discarding that in favour of a handful of fights would be worse, not more
    rigorous.

    But the wiki also lists a BASE QUALITY beside every creature - 30 for a badger, 40
    for a boar - so its hitpoints are the figure for a nominal individual, and real ones
    vary around it. That settles how to combine several fights: not by intersecting them,
    which assumes every badger is the same badger, but by taking the envelope. Two
    badgers here come to 190-210 and 171-342; a third could be either, or outside both.

    What one fight bounds is the individual in it, and the two directions are not
    symmetric:

    A creature that SURVIVED taking D had more than D. A creature that DIED having taken
    D had at most D - but possibly far less, since the killing blow overshoots by however
    much it overshoots, so a kill at D says only (D - last hit, D].

    Both hold in a group fight. The client draws a floating number over a creature for
    damage from any source - the bear log carries thirty for a fight this character sat
    out entirely - so D is the creature's whole intake while in view, not our share. What
    is still invisible is a fight that began before we could see it, which would make a
    creature look smaller than it is.
    """
    stated = wiki_value(wiki_entry, "hp")
    per, lo, hi, sur = [], None, None, None

    for gob, d in sorted(dealt.items()):
        if d <= 0:
            continue
        if gob in killed:
            floor, ceil = d - last_hit.get(gob, 0), d
            per.append("died at %d with a last hit of %d, so that one had %d to %d"
                       % (d, last_hit.get(gob, 0), floor, ceil))
            lo = floor if lo is None else min(lo, floor)
            hi = ceil if hi is None else max(hi, ceil)
        else:
            # A survivor proves some individual was AT LEAST this big, which raises the
            # top of the range and says nothing about the bottom. Letting it lower the
            # floor as well put the boar's range at 64 to 499, on the strength of one
            # boar that walked away from 63 damage and may well have had 450.
            per.append("survived taking %d, so that one had more than that" % d)
            sur = d + 1 if sur is None else max(sur, d + 1)

    # The envelope of every individual seen, widened to include the nominal one. A fresh
    # opponent is drawn from this range, not pinned to any point in it.
    #
    # Kills and survivors enter differently. A kill brackets its individual, so it can
    # both lower the floor and raise the ceiling. A survivor only ever proves one was
    # BIGGER than something - it never bounds it from above and never says anything about
    # how small the species goes. Letting a survivor lower the floor put the boar at 64,
    # on the strength of one that walked away from 63 damage and probably had 450.
    use_lo, use_hi = lo, hi
    if sur is not None:
        # It had at least this much. With no kill to cap it, the top stays open rather
        # than collapsing onto the floor - "445 or more" is the finding, and printing
        # "445" would turn a lower bound into a point estimate.
        use_lo = sur if use_lo is None else use_lo
        if use_hi is not None:
            use_hi = max(use_hi, sur)
    if stated is not None:
        use_lo = stated if use_lo is None else min(use_lo, stated)
        use_hi = stated if use_hi is None else max(use_hi, stated)

    verdict = None
    if stated is not None and per:
        below = [g for g, d in dealt.items() if g in killed and d > 0 and d < stated]
        # Above the stated figure two ways: something walked away from more than it, or
        # something needed more than it just to reach its last hit. Counting only the
        # first missed the cave angler entirely - it DIED at 1545 with a last hit of 90,
        # so it held at least 1455 against a stated 1200, and was reported as
        # "consistent" because it was not a survivor.
        above = [g for g, d in dealt.items()
                 if d > 0 and ((g not in killed and d + 1 > stated)
                               or (g in killed and (d - last_hit.get(g, 0)) > stated))]
        if below and not above:
            verdict = ("%d of these died before taking the wiki's %d, so they were below "
                       "its base quality (or already hurt when we met them)"
                       % (len(below), stated))
        elif above and not below:
            verdict = ("%d of these held out past the wiki's %d, so they were above its "
                       "base quality" % (len(above), stated))
        elif below and above:
            verdict = "individuals seen on both sides of the wiki's %d" % stated
        else:
            verdict = "every individual seen is consistent with the wiki's %d" % stated

    if use_lo is None and use_hi is None:
        return None
    return {"lo": use_lo, "hi": use_hi, "wiki": stated, "verdict": verdict,
            "observed_lo": lo, "observed_hi": hi, "from": "; ".join(per) or "wiki only"}


DEPTH = depth_scaled()

def report_mu(per):
    """How the deck weighting rises with a card's level.

    Every comparison is between two moves thrown at ONE creature. mu is a property of our
    own deck, not of the opponent, so each individual gives an independent estimate of
    the same quantity and they pool - but only after the opponent has been divided out,
    which needs both moves used on the same one. Comparing across individuals measures
    the individuals.

    Only gains of MIN_GAIN or more are counted, and that is not a detail. Two cards known
    to be at the same level - and so certain to share a mu - read anywhere from 0.44 to
    2.25 of each other with every gain pooled, and 0.93 to 1.08 once the small ones are
    dropped. Equal-weighting an integer gain of 3 with one of 20 is what made this
    unreadable, since the gain enters cubed.

    Same-level pairs are reported alongside as the control, because they must read exactly
    1.00 and whatever they actually read is this method's remaining noise.
    """
    rows = []
    for name in sorted(per):
        for gob, allmoves in sorted(per[name]["wd_by_gob_move"].items()):
            bymove = dict((mv, [o for o in obs if o[3] >= MIN_GAIN])
                          for mv, obs in allmoves.items())
            bymove = dict((mv, obs) for mv, obs in bymove.items() if obs)
            if len(bymove) < 2:
                continue
            # The deck as it stood when this creature was fought, not as it stands now.
            lv = per[name]["deck_at"].get(gob, LEVELS)
            ref = None
            for mv in sorted(bymove):
                if lv.get(mv) == 1 and (ref is None
                                        or len(bymove[mv]) > len(bymove[ref])):
                    ref = mv
            if ref is None:
                continue

            def band(mv):
                obs = bymove[mv]
                return (max(o[1] for o in obs), min(o[2] for o in obs))

            def med(mv):
                v = sorted(o[0] for o in bymove[mv])
                return v[len(v) // 2]

            rb = band(ref)
            for mv in sorted(bymove):
                lvl = lv.get(mv)
                if mv == ref or not lvl or med(mv) <= 0:
                    continue
                nb = band(mv)
                lo = rb[0] / nb[1] if nb[1] > 0 else 0.0
                hi = rb[1] / nb[0] if nb[0] > 0 else 0.0
                rows.append((lvl, mu_ratio(med(ref), med(mv)), lo, hi,
                             len(bymove[mv]), name, mv, ref))
    if not rows:
        return

    print("=" * 78)
    print("DECK WEIGHTING BY LEVEL")
    print("=" * 78)
    print("  mu is 1.0 at level 1, measured: Take Aim's cooldown divides by it and came")
    print("  back at its listed 30. Everything below is one move against another thrown")
    print("  at the SAME creature, since only that divides the opponent out.\n")
    print("  %-6s %-6s %-16s %-5s %-11s %s"
          % ("level", "mu", "interval", "n", "if linear", "measured on"))
    for lvl, mu, lo, hi, n, name, mv, ref in sorted(rows):
        pred = mu_at_level(lvl)
        print("  %-6s %-6.2f %-16s %-5d %-11s %s vs %s, %s"
              % (lvl, mu, "%.2f - %.2f" % (lo, hi), n,
                 "%.3f" % pred if pred else "?", mv[:16], ref[:16], name[:12]))

    # The level-1 rows are the control: every one of them must read exactly 1.00, so what
    # they actually read is this method's noise. A higher-level reading is only evidence
    # to the extent it sits outside that spread, which is a sharper test than asking
    # whether it is near a predicted value.
    ctrl = sorted(r[1] for r in rows if r[0] == 1)
    if ctrl:
        mid = ctrl[len(ctrl) // 2]
        print("\n  The %d level-1 rows are the control - each must read exactly 1.00."
              % len(ctrl))
        print("  They read %.2f to %.2f, median %.2f. That spread is the method's noise."
              % (ctrl[0], ctrl[-1], mid))
        for lvl, mu, _lo, _hi, n, _sp, mv, _ref in sorted(rows):
            if lvl == 1:
                continue
            over = sum(1 for c in ctrl if mu > c)
            print("    level %s (%s, n=%d) reads %.2f - above %d of the %d controls"
                  % (lvl, mv[:18], n, mu, over, len(ctrl)))
        print("\n  These are a CHECK, not a measurement. mu is ours - a card at a level")
        print("  has the weighting the game gives it, and no fight reveals it. It enters")
        print("  the estimate as an input, and above level 1 it enters as the stated")
        print("  1.0 to 1.5, which simply widens that card's Wd rather than biasing it.")
        print("  A levelled card reading high here is that width, not a discovery.")
    print()



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
                # Individuals of a species are taken to share a defence weight until
                # something says otherwise, so an empty intersection is first of all a
                # measurement problem. Report the size of the miss instead of a bare
                # verdict: four badgers missing by one point on integer gains is not the
                # same finding as two disagreeing by half, and calling both
                # "contradictory" hides which one you have.
                gap = ilo - ihi
                span = max(1.0, ihi if ihi > 0 else ilo)
                print("\n  defence weight   %.0f - %.0f, missing by %.0f (%.0f%%)   from %d"
                      " observation(s)" % (ihi, ilo, gap, 100.0 * gap / span, len(vals)))
                print("                   %s"
                      % ("within measurement error - integer gains carry about this much"
                         " slop" if gap <= 0.1 * span else
                         "too far apart to be one creature - something else is wrong"))
            print("                   (mu is an INPUT, from the card's level: exactly 1.0"
                  " at level 1, and the")
            print("                   stated 1.0-1.5 above it, which widens that card's"
                  " band rather than biasing it)")
            if rec["mu_scaled_openings"]:
                print("                   excluded, because their OPENING carries the deck"
                      " weighting rather than")
                print("                   their attack weight, which makes the correction"
                      " cubed: %s"
                      % ", ".join(sorted(rec["mu_scaled_openings"])))

            # Per individual, because a species bucket assumes every one of them is the
            # same creature and that is not free. Hitpoints say otherwise outright - two
            # badgers here differ by a factor of 1.6 - so the same could be true of
            # defence, and pooling would hide it in a wider interval rather than reporting
            # it. It happens not to be: those two badgers give 74-132 and 74-97, which
            # overlap. That is a finding, and it only exists because it was checked.
            if len(rec["wd_by_gob"]) > 1:
                print("                   per individual - a species bucket assumes these"
                      " agree:")
                for gob, rows in sorted(rec["wd_by_gob"].items()):
                    glo, ghi = max(r[0] for r in rows), min(r[1] for r in rows)
                    print("      gob %-14s %2d obs   %s"
                          % (gob, len(rows),
                             ("%.0f - %.0f" % (glo, ghi)) if glo <= ghi
                             else "CONTRADICTORY on its own"))

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
            # mu is deliberately NOT compared here, though it used to be. It can only be
            # read between two moves thrown at the SAME creature, and a species bucket
            # holds several - so comparing a move used on one badger against a move used
            # on another measures the difference between the badgers instead. Doing
            # exactly that made Cleave and Full Circle read 0.59 and 0.55, outside the
            # possible range, purely because a fourth badger arrived and the reference
            # move moved onto it. report_mu() works per individual.
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
        arm = fit_armour(rec["soak"])
        wiki_arm = wiki_value(rec.get("wiki"), "armor")
        if arm and not arm.get("penetrated", True):
            print("\n  armour           at least %d, no ceiling   (%d hit(s), none got "
                  "through)" % (arm["total"][0], arm["n"]))
            print("                   absorption only saturates on a hit that penetrates,"
                  " so this is our biggest hit and not its armour")
            if wiki_arm is not None:
                print("                   the wiki says %s" % wiki_arm)
        elif arm:
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
            if wiki_arm is not None:
                mark = "agrees" if wiki_arm == tlo == thi else "DIFFERS"
                print("                   the wiki says %s - %s" % (wiki_arm, mark))
            for h in [x for x in rec["soak"] if x["soaked"] > 0][:6]:
                print("      raw %-5d soaked %-5d through %-5d" % (h["raw"], h["soaked"],
                                                                   h["shp"]))
        else:
            hit = [h for h in rec["soak"] if h["raw"] > 0]
            if hit and not any(h["soaked"] for h in hit):
                print("\n  armour           none observed - %d hit(s), no ARM channel on any"
                      % len(hit))
            elif wiki_arm is not None:
                print("\n  armour           %s total, from the wiki - our own hits could not "
                      "measure it" % wiki_arm)
            else:
                print("\n  armour           ? (too few soaked hits, and the wiki does not "
                      "list it)")

        # --- what it does back
        if norm(rec.get("res")) in DEPTH or (rec.get("wiki")
                                            and norm(rec["wiki"].get("name")) in DEPTH):
            print("\n  NOTE             this creature scales with mine depth (floors 1-9),"
                  " so the range below")
            print("                   spans DEPTHS, not individual variation - and nothing"
                  " in a log records")
            print("                   which floor a fight happened on")

        hp = rec["hp"]
        if hp:
            span = ("%s" % hp["lo"]) if hp["lo"] == hp["hi"] else \
                   ("%s - %s" % (hp["lo"], hp["hi"] if hp["hi"] is not None else "?"))
            print("\n  hitpoints        %s" % span)
            if hp.get("verdict"):
                print("                   %s" % hp["verdict"])
            for line in (hp.get("from") or "").split("; "):
                if line and line != "wiki only":
                    print("                   - %s" % line)
        else:
            print("\n  hitpoints        ? (never damaged it, and the wiki does not list it)")

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

        arm = fit_armour(rec["soak"])
        wiki_arm = wiki_value(rec.get("wiki"), "armor")
        if arm:
            tlo, thi = arm["total"]
            entry["armour"] = {"total_lo": tlo, "total_hi": thi,
                               "hard": arm["hard"] if arm["identified"] else None,
                               "soft": arm["soft"] if arm["identified"] else None,
                               "identified": arm["identified"], "n": arm["n"],
                               "wiki": wiki_arm}
        elif rec["soak"] and not any(h["soaked"] for h in rec["soak"]):
            # Not the same as unknown: it was hit and nothing was absorbed.
            entry["armour"] = {"total_lo": 0, "total_hi": 0, "hard": 0, "soft": 0,
                               "identified": True, "n": len(rec["soak"]), "wiki": wiki_arm}
        elif wiki_arm is not None:
            entry["armour"] = {"total_lo": wiki_arm, "total_hi": wiki_arm, "hard": None,
                               "soft": None, "identified": False, "n": 0,
                               "wiki": wiki_arm, "source": "wiki"}
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
        # Every install on this machine, not just the checkout. See
        # fightlog.find_log_dirs - the Steam Workshop copy keeps its own directory, and
        # a tool that only looked in bin/CombatLogs reported a corpus that had silently
        # stopped growing while two mornings of fights sat elsewhere.
        paths, dirs = fightlog.default_logs()
        for d in dirs:
            n = len([x for x in paths if os.path.dirname(x) == d])
            print("  %3d log(s)  %s" % (n, d))
        print()
    if not paths:
        print(__doc__)
        return 2
    per, moves = collect(paths)
    if not moves:
        print("no %s - run tools/combat/parse_deck.py first"
              % os.path.relpath(SHEET, ROOT))
        return 2
    report(per, moves)
    report_mu(per)
    if write:
        write_pack(per, moves)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
