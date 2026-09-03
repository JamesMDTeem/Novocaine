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
# The floor on mu ITSELF, as opposed to MU_MIN above, which floors a RATIO of two of
# them. Conflating the two would let a measurement clip to 0.67.
MU_MIN_STATED = 1.0

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


def mu_linear(level):
    """The deck weighting: 1.0 to 1.5, evenly, across the five card levels.

    THE LEADING CURVE, and it was written off for a week by an error in the instrument
    rather than in the hypothesis.

    Take Aim's cooldown was read as round-half-up of a single division, which put level 2
    at 1.143-1.177 and excluded this curve's 1.125. The game floors, and it floors TWICE:
    the card's own cooldown is base/mu floored to an integer, and initiative then scales
    that integer and floors again. Under the correct rule level 2 reads 1.111-1.154 and
    level 3 reads 1.200-1.250, and this curve's 1.125 and 1.250 sit inside both.

    Twenty-eight readings across three card levels, including a ladder of eighteen
    consecutive Take Aims from 0 to 17 initiative. Linear matches all twenty-eight. The
    square-root curve that replaced it matches eight.

    Levels 4 and 5 remain unmeasured, and Take Aim caps at level 3 so it can never reach
    them. See mu_curve for what is still open there.
    """
    if not level or level < 1:
        return None
    return 1.0 + (MU_MAX - 1.0) * (min(level, MU_LEVELS) - 1) / (MU_LEVELS - 1)


def mu_at_level(level):
    """A single mu for a card at this level, for the places that cannot carry an interval.

    The midpoint of mu_bounds, which is measured where Take Aim has been logged at that
    level and the devs' stated range otherwise. It used to be the linear curve, which the
    corpus has since excluded - and a point estimate off an excluded curve is exactly the
    kind of confident wrong number this project keeps finding.

    Prefer mu_bounds and attack_weight_bounds. A point here throws away the fact that an
    unmeasured level is known only to within 1.0-1.5, and that width is real.
    """
    if not level or level < 1:
        return None
    lo, hi = mu_bounds(level)
    return (lo + hi) / 2.0


# Take Aim's base cooldown and its initiative scaling, from the sheet. Kept here rather
# than read from the move sheet because measure_mu() must work on a corpus whose deck
# dump is not the one loaded, and because a wrong base here would silently rescale every
# mu it reports - a literal is easier to check than a lookup.
TAKE_AIM_BASE, TAKE_AIM_IP_SCALE = 30.0, 0.20


def mu_from_takeaim(cooldown, ip):
    """The deck weighting implied by one Take Aim cooldown, as an interval.

    The card's own cooldown is an INTEGER - base divided by mu and floored, which is the
    number the card displays. Initiative then scales that integer and the result is floored
    again. So an observed N at initiative i means the card's integer C satisfies
    floor(C * f) == N, and mu is then bounded by floor(base / mu) == C.

    This used to invert a single round-half-up, and getting it wrong was not a small error.
    It put level 2 at 1.143-1.177 where the truth is exactly 1.125, which EXCLUDED the
    linear curve and sent the whole project after a square-root one for a week. A ladder of
    eighteen consecutive Take Aims settled it: twenty-eight readings across three levels,
    all twenty-eight matched by linear under the double floor, eighteen by round-half-up.
    """
    if not cooldown or cooldown <= 0:
        return None
    f = 1.0 + (TAKE_AIM_IP_SCALE * ip)
    # The integers C for which floor(C * f) == cooldown. Usually one; two when f is small.
    cands = [c for c in range(1, int(TAKE_AIM_BASE) + 1)
             if int(math.floor(c * f)) == int(cooldown)]
    if not cands:
        return None
    # floor(base / mu) == C  <=>  mu in (base / (C + 1), base / C]
    lo = TAKE_AIM_BASE / (max(cands) + 1.0)
    hi = TAKE_AIM_BASE / float(min(cands))
    return (lo, hi)


def _mu_from_takeaim_old(cooldown, ip):
    """Kept only so the correction above can be shown against what it replaced."""
    if not cooldown or cooldown <= 0:
        return None
    f = 1.0 + (TAKE_AIM_IP_SCALE * ip)
    lo = TAKE_AIM_BASE / ((cooldown + 0.5) / f)
    hi = TAKE_AIM_BASE / ((cooldown - 0.5) / f)
    return (lo, hi)


def measure_mu(logs=None):
    """mu per card level, read off Take Aim's reported cooldown. {level: (lo, hi)}.

    THIS IS THE ONE PLACE mu IS MEASURED RATHER THAN ASSUMED, and it works only because
    Take Aim's sheet writes "Cooldown: 30 / mu" - the server hands us a number that mu
    divides, so nothing about an opponent enters. Every other card carries mu into a
    quantity that is multiplied by an unknown defence weight, where mu and Wd cannot be
    separated; this one does not.

    Two things have to be handled or the answer is wrong rather than merely wide.

    The initiative the cooldown scales by is the one held GOING IN, and the state sample
    that records it can land either side of the move message. So a use is read against the
    ip in the state before it, and an observation whose bracketing states disagree in a
    way the move itself cannot explain - Take Aim grants a point, so an unchanged ip means
    a sample was dropped - is not trusted to a single ip and is reported separately rather
    than averaged in. One such observation here reads 31 ticks against an apparent 2 IP,
    which would be mu 1.36; its own file also holds a clean 26 at 0 IP, and 26 at 0 IP
    predicts exactly 31 at 1 IP, so the ip sample is stale rather than the model wrong.

    The level is the level in force AT THE TIME, from levels_at(). A fight whose deck is
    unknown is skipped, never credited to today's deck - doing that credited the corpus's
    oldest fight, which predates every dump, to level 2 and made its textbook 30/36/42
    ladder read as mu 1.18.
    """
    if logs is None:
        logs, _dirs = fightlog.default_logs(ROOT)
    per = defaultdict(list)
    suspect = defaultdict(list)
    for path in logs:
        try:
            with open(path, "r", encoding="utf-8") as f:
                recs = [json.loads(ln) for ln in f if ln.strip()]
        except (OSError, ValueError):
            continue
        wall = next((r.get("wall") for r in recs if r.get("ev") == "begin"), None)
        level = levels_at(wall).get("Take Aim")
        if not level:
            continue
        before = None
        for i, r in enumerate(recs):
            if r.get("ev") == "state":
                before = r
                continue
            if r.get("ev") != "move" or r.get("actor") != "me":
                continue
            if not str(r.get("move") or "").endswith("takeaim"):
                continue
            if before is None:
                continue
            after = next((x for x in recs[i + 1:] if x.get("ev") == "state"), None)
            ip = before.get("myip")
            if ip is None:
                continue
            band = mu_from_takeaim(r.get("cd"), ip)
            if band is None:
                continue
            # Take Aim grants a point. If the state after it shows the same ip, a sample
            # was dropped and this ip cannot be trusted.
            stale = (after is not None) and (after.get("myip") == ip)
            (suspect if stale else per)[level].append(band)
    out = {}
    for level, bands in per.items():
        lo = max(b[0] for b in bands)
        hi = min(b[1] for b in bands)
        # Clipped to the devs' stated 1.0-1.5. That is not cosmetic at level 1: the
        # measurement there is (0.9931, 1.0070], and the part below 1.0 is rounding on a
        # whole-tick cooldown rather than a real possibility, since level 1 is a card with
        # no extra points in it and 1.0 is the floor by definition. Clipping keeps the
        # floor without pretending the measurement is tighter than it is.
        lo, hi = max(lo, MU_MIN_STATED), min(hi, MU_MAX)
        if lo <= hi:
            out[level] = (lo, hi, len(bands), len(suspect.get(level, ())))
    return out


# Measured at import, so every defence weight this tool reports is corrected by a mu that
# was read rather than assumed. What it currently finds:
#
#   level 1  ->  exactly 1.0        63 observations, the whole 30/36/42/48/54/60/66/72
#                                   ladder at 0 through 7 initiative, without exception
#   level 2  ->  1.143 to 1.177     six agreeing observations - five of "26 at 0 IP" and
#                                   one of "31 at 1 IP", which are the same mu
#
# What that EXCLUDES matters as much as what it says. The obvious curve, linear from 1.0
# to 1.5 across the five card levels, predicts 1.125 at level 2 and is ruled out. So is
# linear across a card's own maximum, which predicts 1.25. Shapes that still fit include
# 1.5 - 0.5/sqrt(L) (1.146) and 1 + 0.5*(L-1)/(L+1) (1.167), and one level cannot separate
# them. Take Aim's own maximum is 3: one further point in it, and a handful of uses logged
# at 0 initiative, would discriminate between every surviving candidate.


# What the wiki's own worked example states, as opposed to what this corpus measured.
#
# "A player who has 50 uac, 25% green opening and is in lvl 4 Chin Up defense mode is
# attacked by a player with 100 uac by level 5 punch. Defense weight will be 50*1.4 and
# attack weight will be 100*0.8*1.5."
#
# Chin Up's block multiplier is 1.0, so 50*1.4 puts mu at 1.4 for level 4; Punch's is 0.8,
# so 100*0.8*1.5 puts it at 1.5 for level 5. Wiki prose rather than a dev quote, so it is
# kept apart from MU_MEASURED and used to judge candidate curves rather than to compute.
MU_WIKI_EXAMPLE = {4: 1.4, 5: 1.5}


def mu_curve(level):
    """The square-root curve: 1 + 0.5*(sqrt(L)-1)/(sqrt(5)-1). EXCLUDED.

    KEPT ONLY TO BE CONTRADICTED, which is the exact wording this docstring used to carry
    about mu_linear. The reversal is worth stating plainly rather than quietly deleting.

    This curve was adopted because Take Aim's cooldown appeared to measure 1.143-1.177 at
    level 2, which excluded linear's 1.125 and admitted this curve's 1.168. That reading
    came from inverting a round-half-up of a single division. The game floors twice, and
    under the correct rule level 2 reads 1.111-1.154 and level 3 reads 1.200-1.250 - both
    of which EXCLUDE this curve, at 1.168 and 1.296.

    The lesson is not that the hypothesis was badly chosen. Three independent lines picked
    it out and each was argued carefully; every one of them ran through the same broken
    inversion, so agreeing with each other told us nothing. A shared instrument is a shared
    assumption, and three results from one instrument are one result.

    One thing it got right survives: the wiki's worked example states mu(4) = 1.4, which
    is neither this curve's 1.405 nor linear's 1.375. Level 4 is genuinely open, and Take
    Aim caps at 3 so it cannot settle it. Dash divides by mu, has no initiative term, and
    at level 4 gives 58 ticks for linear, 57 for the wiki figure and 56 for this curve -
    three distinct integers, one use.
    """
    if not level or level < 1:
        return None
    L = min(level, MU_LEVELS)
    return 1.0 + (MU_MAX - 1.0) * ((L ** 0.5) - 1.0) / ((MU_LEVELS ** 0.5) - 1.0)


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
    # Measured, wherever Take Aim has been logged at that level - see MU_MEASURED. A
    # measurement beats a stated range: at level 2 it narrows 1.0-1.5 to 1.14-1.18, which
    # is the difference between a defence weight known to 50% and one known to 3%.
    if level in MU_MEASURED:
        return MU_MEASURED[level]
    if level == 1:
        return (1.0, 1.0)
    # An unmeasured level keeps the devs' stated range, and is NOT interpolated. The
    # corpus has already excluded the linear curve, so interpolating between measured
    # levels would be guessing a shape the data contradicts.
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
    # A probe that fires while the sheet is still loading carries a handful of cards
    # rather than the deck. One here holds 8 where every dump either side holds 33, and
    # left in place it reports every card it is missing as absent for the eight minutes
    # it covers. Judge it on card COUNT, not on levels: a genuine deck change moves
    # levels around and a genuine new card raises the count by one, while a partial dump
    # is short by twenty. Nothing here treats a level going DOWN as suspicious, because
    # it legitimately does - this corpus contains a six-minute stretch where Sting and
    # Quick Barrage were swapped out for Punch and Knock Its Teeth Out and then swapped
    # back, which is an experiment rather than an artefact.
    if out:
        full = max(len(l) for _w, l in out)
        out = [(w, l) for w, l in out if len(l) >= (full * 0.75)]
    return out


DECKS = deck_history()


def levels_at(when):
    """The deck in force at a wall time - the newest dump at or before it, or {} if none.

    An unknown deck is reported as unknown. It used to fall back: a fight with no wall
    stamp walked the whole list and came back with TODAY'S deck, and a fight older than
    every dump came back with the earliest one. Both are guesses wearing a measurement's
    clothes, and the second one is the exact error this project has already paid for once
    - Take Aim's first logged fight, which predates every dump, was credited to level 2
    and its perfect 30/36/42 ladder read as mu 1.18 instead of the 1.0 it plainly is.

    Returning {} costs nothing that matters: a level-keyed measurement skips the fight,
    which is the correct treatment for a fight whose deck is not known.
    """
    if not DECKS or when is None or when < DECKS[0][0]:
        return {}
    best = {}
    for stamp, levels in DECKS:
        if stamp > when:
            break
        best = levels
    return best


LEVELS = DECKS[-1][1] if DECKS else {}

MU_MEASURED = dict((lvl, (lo, hi)) for lvl, (lo, hi, _n, _s) in measure_mu().items())


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


# How far a fit's root-mean-square residual may sit before it stops being a measurement.
#
# This is the trust signal, and the corpus calibrates it rather than taste. Every armour
# that lands exactly on the wiki's figure fits with an rms of 0.22 or less - red deer 0.00,
# boar 0.00 on clean hits, lynx 0.15, cave angler 0.22. Every fit that CONTRADICTS the
# wiki has an rms above 2 - bear 2.42 against a stated 65, moose 4.16 against the same.
#
# The boar settles which side is wrong, because it is the one species with both: 222 hits
# from group fights fit 15-16 with rms 2.33, and the 7 hits from the fight nobody else
# joined fit 15 exactly with rms 0.00. The armour did not change between them. So a high
# residual means the hits are mixed, not that the wiki is wrong, and a fit above this
# threshold may not be used to contradict a stated figure.
ARM_RMS_TRUST = 1.0


def fit_armour(hits, clean=None):
    """Hard and soft soak, from attacks whose ARM channel recorded what was absorbed.

    `clean` is the subset thrown in fights nobody else was in. When there are enough of
    them they are used ALONE, because a fit is only as good as its assumption that every
    hit came from the same attacker with the same penetration.

    ARM + SHP is the damage before armour and SHP is what got through, so each hit is a
    direct (raw, dealt) pair and no damage model is needed to produce one. The grid is
    coarse because the numbers are integers; a finer one would be inventing precision.
    """
    def usable(rows):
        return [(h["raw"], h["shp"]) for h in rows or ()
                if h["soaked"] > 0 and h["raw"] > 0]

    pts, source = usable(clean), "clean"
    if len(pts) < 4:
        pts, source, hits = usable(hits), "mixed", hits
    else:
        hits = clean
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
        return {"hard": None, "soft": None, "n": len(pts), "rms": 0.0, "source": source,
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
    return {"hard": hard, "soft": soft, "n": len(pts), "source": source,
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
    # The STANDING opening is quantised too, and used not to be treated as such. The client
    # renders it as floor(fraction * 100) and the log records the same integer, so a logged
    # 52 means somewhere in [52, 53) - and (1 - Oc) is a divisor of k, so that carries
    # through to Wd the same way the gain does. Using the integer for both ends made every
    # interval a few percent narrower than the data supports, which is the same species of
    # error as reporting a point estimate: an honest-looking number that is not earned.
    oc_lo = standing / 100.0
    oc_hi = min(1.0, (standing + 1) / 100.0)
    # Wd = Wa / k**3. The low end maximises k - largest gain, smallest (1 - Oc) - and pairs
    # it with the smallest attack weight; the high end does the opposite. Passing an
    # interval for Wa is how a card whose level leaves mu uncertain widens the answer
    # instead of biasing it.
    lo = model.defence_weight(wa, gain + GAIN_SLOP, ob, oc_hi)
    hi = model.defence_weight(wa_hi if wa_hi else wa, max(0.1, gain - GAIN_SLOP), ob, oc_lo)
    return (lo, hi)


def stance_of(log, gob):
    """The stance resource a combatant was holding, from the schema-5 buffs samples.

    Returns the LAST one seen, or None. A stance can be swapped mid-fight and this does not
    try to track that - it is a starting point for reading the corpus, not a timeline.
    """
    best = None
    for r in log.buffs:
        if (gob is not None) and (r.get("gob") != gob):
            continue
        for res in r.get("res") or []:
            nm = (res or "").rsplit("/", 1)[-1]
            if nm in STANCE_RES:
                best = STANCE_RES[nm]
    return best


# Move name by the resource basename its stance buff uses. Only the cards whose sheet
# carries a Block weight line are stances; an opening pagina is not one.
STANCE_RES = {
    "bloodlust": "Bloodlust", "chinup": "Chin Up", "combmed": "Combat Meditation",
    "parry": "Parry", "shieldup": "Shield Up", "toarms": "To Arms",
    "oakstance": "Oak Stance", "dorf": "Death or Glory",
}


def block_weight(moves, stance, attrs, gear, level):
    """A combatant's defence weight from the stance they are holding.

    The wiki states this outright in its own worked example: "a player who ... is in lvl 4
    Chin Up defense mode ... defense weight will be 50 * 1.4" - the skill the card names,
    times its block multiplier, times the card's mu at their level. Chin Up's multiplier is
    1.0, so that example is 50 unarmed at mu 1.4, and it is where the mu curve's level-4
    value of 1.4 comes from.

    This is the same arithmetic own_defence_weight does for us, factored out so it can be
    pointed at an opponent. The difference is what is known: for us the level is in our own
    deck dump and mu with it, and for THEM the level is not visible at all - so their mu is
    the whole stated 1.0 to 1.5 and the answer is an interval a third wide.

    Returns (lo, hi, description) or None.
    """
    m = moves.get(stance)
    if (m is None) or not m.get("block_weight"):
        return None
    skill = m.get("block_skill") or "melee"
    base = attrs.get(skill)
    if not base:
        return None
    mult = m.get("block_mult") or 1.0
    why = ""
    need = m.get("block_requires")
    if need:
        held = (gear is None) or any(need in (g.get("res") or "") for g in gear)
        if not held:
            mult = m.get("block_mult_without") or mult
            why = " (no %s equipped)" % need
    lo, hi = mu_bounds(level) if level else (MU_MIN_STATED, MU_MAX)
    return (base * mult * lo, base * mult * hi,
            "%s: %s %g x %g x mu %.3f-%.3f%s" % (stance, skill, base, mult, lo, hi, why))


def own_defence_weight(moves, attrs, gear, levels):
    """OUR defence weight - the one term in the opening formula we do not have to infer.

    Returns (wd, description) or (None, why not).

    An opponent's Wd has to be recovered from how fast our attacks open it. Ours does not:
    it is the block weight of whichever stance we are holding, and the sheet states that
    outright as a skill, a multiplier and mu. Holding Shield Up with Melee Combat at 125
    and the card at level 1, ours is 125 x 250% x 1.0 = 312.5, known rather than fitted.

    That makes the reverse direction measurable for the first time. A gain the OPPONENT
    puts on US is cbrt(Wa_foe / Wd_us) * Ob * (1 - Oc), and with Wd_us known the only
    thing left in it is the creature's own attack weight times the move's listed opening.

    Two conditions have to be respected or the number is wrong by a lot.

    The card must actually be IN THE DECK. A stance at deck level 0 cannot have been held,
    which is what rules out Bloodlust here - it would otherwise be the obvious explanation
    for anything odd about our attack weight, since it raises it by four times its charge.

    And a conditional multiplier has to be checked against the gear. Shield Up reads
    "If Shield Up is used without a shield equipped, its block weight will be 50% instead
    of 250%" - a factor of five, and the difference between a defence weight of 312 and
    one of 62.
    """
    best = None
    for name, m in moves.items():
        if not m.get("block_weight"):
            continue
        lvl = levels.get(name) or 0
        if lvl < 1:
            continue
        skill = m.get("block_skill") or "melee"
        base = attrs.get(skill)
        if not base:
            continue
        mult = m.get("block_mult") or 1.0
        why = ""
        need = m.get("block_requires")
        if need:
            held = any(need in (g.get("res") or "") for g in gear)
            if not held:
                mult = m.get("block_mult_without") or mult
                why = " (no %s equipped, so the reduced multiplier)" % need
            else:
                why = " (%s equipped)" % need
        lo, hi = mu_bounds(lvl)
        wd = base * mult * ((lo + hi) / 2.0)
        desc = "%s: %s %g x %g x mu %.3f%s" % (name, skill, base, mult, (lo + hi) / 2.0, why)
        # More than one stance in the deck is possible; the log does not say which was
        # held, so the strongest is reported and flagged rather than guessed at.
        if best is None or wd > best[0]:
            best = (wd, desc)
    if best is None:
        return (None, "no stance card in the deck at this time")
    return best


# The four attack schools, and the opening colour each one inflicts. The wiki's animal
# table names schools where our own sheet names colours; they are the same four things.
SCHOOL_COLOUR = {"striking": "green", "backhanded": "blue",
                 "sweeping": "yellow", "oppressive": "red"}

ANIMALS = os.path.join(ROOT, "data", "combat", "animal_moves.json")


def animal_opens():
    """Animal move name -> the colours it opens, from the wiki's table.

    Needed for the same reason our own deck's map is: attribution turns on colour. Without
    it every gain an ANIMAL puts on us is unattributable, because there is nothing to test
    a stray colour against - which is why the pressure figures could only ever come from
    fights with a single opponent.

    The table gives the schools and not the percentages, which is the whole reason
    opening pressure is reported as a product - see collect().
    """
    try:
        with open(ANIMALS, "r", encoding="utf8") as f:
            doc = json.load(f)
    except (OSError, ValueError):
        return {}
    rows = doc if isinstance(doc, list) else sum(
        (v for v in doc.values() if isinstance(v, list)), [])
    idx = dict((c, i) for i, c in enumerate(fightlog.COLOURS))
    out = {}
    for r in rows:
        if not isinstance(r, dict) or not r.get("name"):
            continue
        cols = set()
        for o in r.get("openings") or []:
            c = SCHOOL_COLOUR.get(str(o).lower())
            if c in idx:
                cols.add(idx[c])
        out[r["name"]] = cols
    return out


def opens_map(moves):
    """Move name -> the set of colour indices it opens, for fightlog.

    This is what lets contamination detection be exact rather than temporal: a rise in a
    colour none of our deck opens cannot be ours, however close in time it landed. Animal
    moves are folded in from the wiki table so the same test works in both directions;
    ours win a name collision, since our sheet is the game's own text.
    """
    idx = dict((c, i) for i, c in enumerate(fightlog.COLOURS))
    out = animal_opens()
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
        "last_hit": {}, "partial": set(), "soak": [], "soak_clean": [],
        "mu_scaled_openings": set(), "wd_by_gob_move": {}, "deck_at": {},
        # What the opponent's moves do to US, against our own KNOWN defence weight.
        # move -> list of (pressure, our Wd, n) - see own_defence_weight.
        "pressure": defaultdict(list), "my_wd": set(),
        # (move, the opponent's own initiative before it, whether we were alone) - the raw
        # material for foe_policy.
        "foe_moves": [],
        # Rate of change of the distance between us, per second - the fallback for logs
        # written before schema 6. See relative_speed.
        "sep": [],
        # Schema 6: the speed the client itself reports for each side, units per second.
        "myspd": [], "foespd": [],
        # (gob, wall time, our IP at the engagement's start, at its end) - see reaggro_cost.
        "ip_edges": [],
        # Milliseconds between consecutive actions of ITS, within one engagement. The
        # opponent's clock, which is the one term the optimizer cannot do without: a
        # frontier is a trade between damage taken and time spent, and damage taken is
        # pressure times how often it gets to apply it. See threat().
        "foe_gaps": [],
        # Damage per opponent GOB, accumulated across every file that gob appears in -
        # see summarise_hp for why this cannot be done per file.
        "dealt": defaultdict(int), "killed": set(),
    })
    for p in sorted(paths):
        log = fightlog.read(p, opens)
        if not log.rows:
            continue
        # A creature's own clock, measured per GOB across the whole file.
        #
        # Not per engagement, and not gated on defence_ok, which is where this started and
        # where it was wrong in two ways at once. Engagements split on the SAMPLED
        # opponent, so in a crowded fight one creature's actions are scattered across
        # several of them and the gaps between them are lost at every boundary. And
        # defence_ok excludes exactly the crowded fights - which for a swarming species is
        # nearly all of them, so ants read 8 gaps out of 144 engagements.
        #
        # Neither confound applies to timing. A move row carries the gob that ACTED (see
        # Fightview.Relation.use), so filtering on it gives one creature's own actions,
        # and how often it swings does not depend on who else is in the fight. The gate
        # stays where it belongs - on pressure and damage, which genuinely cannot be
        # attributed when a second opponent is opening us at the same time.
        bygob = defaultdict(list)
        for r in log.rows:
            if (r.get("ev") == "move") and (r.get("actor") == "foe") and r.get("gob"):
                bygob[r["gob"]].append(r["t"])
        gaps_for = {}
        for g, ts in bygob.items():
            ts.sort()
            out = []
            for a, b in zip(ts, ts[1:]):
                d = b - a
                # Under a tick is the same action arriving twice; over thirty seconds is a
                # lull that is not a cooldown - it withdrew, or we did.
                if 60 <= d <= 30000:
                    out.append(d)
            gaps_for[g] = out

        attrs = (log.header or {}).get("attr") or {}
        agi_me = attrs.get("agi")
        # The deck as it stood for THIS fight, so a card's mu is the one it was used at.
        lv = levels_at((log.header or {}).get("wall"))
        my_wd, my_wd_why = own_defence_weight(moves, attrs, log.gear, lv)
        for eng in log.engagements:
            rec = per[bucket(eng)]
            rec["engagements"] += 1
            # Once per gob per file, not once per engagement - an engagement is a slice of
            # one creature's fight and adding its gaps again at every slice would count the
            # same milliseconds several times over.
            if eng.gob in gaps_for:
                rec["foe_gaps"].extend(gaps_for.pop(eng.gob))
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
            pairs = fightlog.soak_pairs(eng)
            rec["soak"].extend(pairs)
            # Hits from a fight nobody else was in. soak_pairs deliberately takes hits
            # from every attacker, on the argument that the absorbed/through split is a
            # property of the armour - but that argument has a hole. Penetration bypasses
            # armour entirely, so the split depends on the ATTACKER's penetration too, and
            # the fit assumes zero. Worse, two hits landing inside the same two-millisecond
            # bucket merge into one synthetic hit with both their ARM and both their SHP.
            # Neither can happen when we are the only one swinging.
            if not eng.others_present:
                rec["soak_clean"].extend(pairs)
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

            # What the opponent does to US, taken BEFORE the offence gate and under the
            # DEFENCE one - the two fail for different reasons. Someone else hitting the
            # boar spoils what we can learn about the boar's defence and says nothing
            # about what the boar did to us; another opponent attacking US is what spoils
            # this direction, because then a rise on us may be someone else's move.
            #
            # This is measurable at all only because our own defence weight is known
            # rather than inferred - see own_defence_weight. With Wd_us known,
            #
            #     gain = cbrt(Wa_foe / Wd_us) * Ob * (1 - Oc)
            #     P    = gain / (1 - Oc)  =  cbrt(Wa_foe / Wd_us) * Ob
            #
            # P is how many points the move opens on a fresh colour, and it is fully
            # measured. Splitting it into the creature's attack weight and the move's own
            # listed opening is NOT possible here: the wiki's animal-move table records
            # WHICH colours a move opens and never by how much. So the product is what
            # gets reported, because the product is what was measured - and it is the
            # useful half anyway, since it falls as the cube root of our own defence
            # weight and so says directly what a heavier stance would buy.
            # ATTRIBUTION IS PER OBSERVATION, NOT PER ENGAGEMENT.
            #
            # This used to skip a whole engagement the moment anything else was happening
            # in it, and "anything else" included another player fighting a DIFFERENT
            # animal a few paces away - which cannot touch what our sword did to our boar.
            # It cost most of a busy world: sixteen of thirty-four boar engagements,
            # twenty of twenty-two beelarva, and every bear fight in the corpus.
            #
            # A move's bracket already excludes every move the log records. What it cannot
            # see is another PLAYER, whose moves never enter our fightview. attributed_
            # gains() tests each bracket for that directly - a stray colour, or a second
            # hit landing on the target inside the window - and keeps the observations
            # that pass. The engagement-level problems are still reported, because they
            # remain the honest description of the fight; they no longer decide on their
            # own what may be measured.
            # PER-BRACKET ATTRIBUTION IS BUILT BUT NOT YET TRUSTED, and the corpus is
            # why. fightlog.attributed_gains() tests each bracket on its own - a stray
            # colour, or a second hit landing inside the window - instead of discarding a
            # whole engagement because something else was happening somewhere in it. That
            # is the right idea and it multiplies the corpus six-fold. It also breaks a
            # measurement that was previously solid: the fox goes from a consistent 57-71
            # across 17 observations to a contradictory 38-57 across 24, and DOWNWARD,
            # which is the direction an unseen extra opening pushes a defence weight.
            #
            # Both tests are blind to the one case that matters - another player opening
            # the SAME colour inside the same bracket - and no amount of tightening the
            # engagement gate fixes that, because the evidence simply is not in the log.
            #
            # It IS in the game. Openings are drawn over every opponent's head, not only
            # the one we have targeted, and the client only samples the target's. With
            # every relation's openings recorded, a third party's work shows up as a rise
            # on a creature we never touched, and this becomes decidable rather than
            # hopeful. That is the next step; until the logs carry it, the engagement gate
            # stands.
            if eng.states:
                rec["ip_edges"].append((eng.gob, (log.header or {}).get("wall") or 0,
                                        eng.states[0].get("myip"),
                                        eng.states[-1].get("myip")))

            for st in eng.states:
                if st.get("foespd") is not None:
                    rec["foespd"].append(st["foespd"])
                if st.get("myspd") is not None:
                    rec["myspd"].append(st["myspd"])

            for a, b in zip(eng.states, eng.states[1:]):
                da, db = a.get("dist"), b.get("dist")
                dt = (b["t"] - a["t"]) / 1000.0
                # A sample gap under a twentieth of a second divides by almost nothing and
                # a gap over two seconds has any amount of movement hidden inside it.
                if (da is None) or (db is None) or not (0.05 < dt < 2.0):
                    continue
                rec["sep"].append((db - da) / dt)

            for fm in eng.moves:
                if fm.get("actor") != "foe":
                    continue
                fb, _fa = eng.brackets(fm)
                rec["foe_moves"].append((fm.get("name") or fm.get("move"),
                                         fb.get("foeip") if fb else None,
                                         eng.defence_ok and not eng.others_present))

            attributed = (fightlog.attributed_gains(eng, opens, log.me)
                          if eng.offence_ok else [])
            if eng.problems:
                rec["skipped"].append((os.path.basename(p), eng.problems,
                                       len(attributed)))

            if my_wd:
                for actor, name, colour, standing, gain in attributed:
                    if actor == "me":
                        continue
                    oc = min(standing, 99) / 100.0
                    if (1.0 - oc) <= 0.02:
                        continue
                    rec["pressure"][(name, colour)].append(gain / (1.0 - oc))
                    rec["my_wd"].add(round(my_wd, 1))

            for actor, name, colour, standing, gain in attributed:
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

def mu_from_reductions(logs=None):
    """mu per card level, read off what a defensive card takes off our own openings.

    The SECOND independent way to measure mu, and the only one that reaches the levels
    Take Aim cannot - Take Aim's own maximum is 3, and this deck holds Quick Dodge at 5.

    A "Reduces: 20% - mu" line removes a SHARE of what is standing, and the share scales
    linearly with mu (see Move.reduces). Nothing about the opponent enters, so

        after = floor(before * (1 - share * mu))

    inverts to an interval on mu, widened by the display truncating BOTH numbers. Small
    standing values are dropped: at a standing 4 the truncation is worth more than the
    reduction.

    IT READS LOW, and the corpus says so rather than theory. The level-1 control - Zig-Zag
    Ruse, where mu must be exactly 1.0 - comes back at 0.98, and three uses of Quick Dodge
    at level 5 reduced NOTHING at all, which a reduction cannot do. Both are explained the
    same way: the opponent is attacking while we defend, and a gain it puts on the same
    colour inside the same bracket nets against the reduction. That can only ever make a
    reduction look smaller, so these medians are floors on mu and not estimates of it.

    Which is what makes them useful. Take Aim measures mu(2) tightly at 1.143 to 1.177,
    and these say mu(5) is at least 1.49 - and the only curve satisfying both is the one
    that reaches the stated ceiling of 1.5 at level 5.
    """
    if logs is None:
        logs, _dirs = fightlog.default_logs(ROOT)
    moves = load_moves()
    opens = opens_map(moves)
    red = dict((n, [(t["colour"], t["pct"]) for t in (m.get("reduces") or [])])
               for n, m in moves.items() if m.get("reduces"))
    out = defaultdict(list)
    inert = defaultdict(int)
    for path in sorted(logs):
        try:
            log = fightlog.read(path, opens)
        except Exception:
            continue
        lv = levels_at((log.header or {}).get("wall"))
        for eng in log.engagements:
            for m in eng.moves:
                nm = m.get("name")
                if (m.get("actor") != "me") or (nm not in red):
                    continue
                level = lv.get(nm)
                if not level:
                    continue
                before_s, after_s = eng.brackets(m)
                if (before_s is None) or (after_s is None):
                    continue
                bv, av = before_s.get("mine"), after_s.get("mine")
                if not bv or not av:
                    continue
                for colour, pct in red[nm]:
                    i = fightlog.COLOURS.index(colour)
                    share = pct / 100.0
                    before, after = bv[i], av[i]
                    # Below 8 points the display's truncation is worth more than the
                    # reduction itself, and the interval covers everything.
                    if (before < 8) or (share <= 0):
                        continue
                    if after >= before:
                        inert[(level, nm)] += 1
                        continue
                    lo = (1.0 - ((after + 1.0) / (before + 1.0))) / share
                    hi = (1.0 - (after / before)) / share
                    out[(level, nm)].append((lo + hi) / 2.0)
    return out, inert


def report_mu_reductions():
    rows, inert = mu_from_reductions()
    if not rows:
        return
    print("=" * 78)
    print("DECK WEIGHTING, MEASURED A SECOND WAY")
    print("=" * 78)
    print("  A defensive card removes a SHARE of a standing opening and mu scales that")
    print("  share linearly, so what it takes off measures mu with no opponent in it. This")
    print("  reaches level 5, where Take Aim stops at its own maximum of 3.")
    print("  It reads LOW: the level-1 control must be exactly 1.0 and comes back under it,")
    print("  and some uses reduce nothing at all - the opponent is attacking the same")
    print("  colour while we defend, and that can only ever mask a reduction. Read these")
    print("  as FLOORS on mu.\n")
    print("  %-6s %-15s %-5s %-8s %-8s %s"
          % ("level", "card", "n", "median", "Take Aim", "uses that did nothing"))
    for (level, nm), vals in sorted(rows.items()):
        vals = sorted(vals)
        med = vals[len(vals) // 2]
        direct = MU_MEASURED.get(level)
        print("  %-6s %-15s %-5d %-8.3f %-8s %d"
              % (level, nm[:15], len(vals), med,
                 ("%.3f-%.3f" % direct) if direct else "-", inert.get((level, nm), 0)))
    print()
    print("  Against the curves still standing after Take Aim's level-2 measurement:\n")
    print("  %-6s %-9s %-11s %-11s %-11s %s"
          % ("level", "measured", "linear", "1.5-.5/rtL", "(L-1)/(L+1)", "sqrt-norm"))
    for (level, nm), vals in sorted(rows.items()):
        vals = sorted(vals)
        med = vals[len(vals) // 2]
        print("  %-6s %-9.3f %-11.3f %-11.3f %-11.3f %.3f"
              % (level, med,
                 1.0 + 0.5 * (level - 1) / 4.0,
                 1.5 - 0.5 / (level ** 0.5),
                 1.0 + 0.5 * (level - 1) / (level + 1.0),
                 1.0 + 0.5 * ((level ** 0.5) - 1) / ((5 ** 0.5) - 1)))
    print()
    print("  The linear curve tracks these medians almost exactly - and Take Aim EXCLUDES")
    print("  it, measuring 1.143-1.177 at level 2 where linear wants 1.125. Since these")
    print("  read low and Take Aim does not, the reconciliation is that the true curve sits")
    print("  above the medians. Only one candidate does that and still reaches the devs'")
    print("  stated 1.5 at level 5: mu = 1 + 0.5*(sqrt(L)-1)/(sqrt(5)-1), which puts level")
    print("  2 at 1.168 - inside Take Aim's interval - and level 5 at exactly 1.5.")
    print("  Leading candidate, not a settled one. One more point in Take Aim measures")
    print("  level 3 directly and separates it from everything else.\n")


ANIMAL_MOVES = os.path.join(ROOT, "data", "combat", "animal_moves.json")


def animal_move_kinds():
    """Animal move name -> True when it is an attack, from the wiki's own table."""
    try:
        with open(ANIMAL_MOVES, "r", encoding="utf8") as f:
            doc = json.load(f)
    except (OSError, ValueError):
        return {}
    rows = doc if isinstance(doc, list) else sum(
        (v for v in doc.values() if isinstance(v, list)), [])
    return dict((r["name"], bool(r.get("attack_types")))
                for r in rows if isinstance(r, dict) and r.get("name"))


# A hearthling on foot does not exceed this, so a faster reading is the distance jumping
# rather than anything moving - a gob being replaced, a target switching, a position
# correction. The corpus has separations of 2400 units per second in it, which is not a
# bee swarm.
MAX_REAL_SPEED = 60.0


def reaggro_cost(per):
    """What dropping and re-taking aggro costs, measured across the corpus.

    Auto-reaggro is a real tactic, not a logging accident: a creature that flees is peaced
    and re-aggroed to turn it round and make it fight again. It is also what fragments a
    single fight across several log files, which is why hitpoints are summed per gob rather
    than per file.

    The question it raises is what the re-aggro costs, and the corpus answers it flatly.
    Across every boundary where one engagement with a gob ended while we still held
    initiative and another began, the initiative was gone 25 times out of 26.

    That is the whole basis of the tactical rule. Against something we can outrun, staying
    in combat and keeping the range as it flees preserves the pool - and Take Aim spends
    thirty ticks a point to build one. Against something faster there is no choice, because
    following it is not an option, so the pool is forfeit either way and the only question
    is how quickly it can be rebuilt.

    Returns (kept, lost, examples).
    """
    seen = defaultdict(list)
    for name, rec in per.items():
        for gob, when, first, last in rec.get("ip_edges") or ():
            seen[gob].append((when, name, first, last))
    kept = lost = 0
    ex = []
    for gob, v in seen.items():
        v.sort()
        for (_w1, sp, _f1, l1), (_w2, _sp2, f2, _l2) in zip(v, v[1:]):
            if (l1 is None) or (f2 is None) or (l1 <= 0):
                continue
            if f2 == l1:
                kept += 1
            else:
                lost += 1
                if len(ex) < 6:
                    ex.append((sp, l1, f2))
    return (kept, lost, ex)


def relative_speed(rec):
    """How much faster we are than this species, from how fast the gap opens.

    Measured, not looked up. Each state sample carries the distance to the opponent, so the
    change between consecutive samples is the rate the gap is closing or opening. When we
    withdraw, that rate is OUR speed minus THEIRS, and the high end of it over a whole
    corpus is the cleanest estimate of the difference available - we spend a lot of a fight
    backing away.

    It checks out against creatures whose speed is common knowledge: a moose barely lets us
    pull away at 3.1 units per second while ants shed us at 19.5. If those two had come out
    the same way round the measurement would be meaningless.

    WHY IT MATTERS, and it is not academic. A creature that flees faster than we can chase
    cannot be finished by following it, so the only way to keep fighting is to drop aggro
    and re-take it - which costs the whole initiative pool (see ip_lost_on_reaggro). A
    creature slower than us can simply be kept in range while it runs, and the initiative
    stays. That is the difference between a plan that opens with Take Aim and one that
    cannot afford to.

    Returns {p95, p50, n} in world units per second, or None. Roughly eleven units to a
    tile.
    """
    # MEASURED, where the log carries it. Schema 6 records Gob.gobSpeed for both sides -
    # the Moving attribute's own velocity, the white figure the client draws under anything
    # that moves. That is the creature's speed outright, so none of the inference below is
    # needed and none of its confound applies: a fox that we never backed away from still
    # reports its own speed the moment it moves.
    #
    # Reported as a RANGE. Speed is randomised per individual within a species band, so a
    # single figure would be averaging over animals that genuinely differ - the same shape
    # as the hitpoint spread already reported per gob.
    spd = sorted(x for x in (rec.get("foespd") or ()) if 0 < x <= MAX_REAL_SPEED)
    if len(spd) >= 10:
        mine = sorted(x for x in (rec.get("myspd") or ()) if 0 < x <= MAX_REAL_SPEED)
        out = {"measured": True, "n": len(spd),
               "lo": round(spd[int(0.05 * len(spd))], 1),
               "median": round(spd[len(spd) // 2], 1),
               "hi": round(spd[int(0.95 * len(spd))], 1)}
        if len(mine) >= 10:
            ours = mine[int(0.95 * len(mine))]
            out["our_top"] = round(ours, 1)
            # Whether we can hold range while it runs, which is what decides between
            # keeping the fight and re-aggroing away the initiative pool.
            out["we_outrun_it"] = ours > out["hi"]
        return out

    v = sorted(r for r in (rec.get("sep") or ()) if abs(r) <= MAX_REAL_SPEED)
    if len(v) < 40:
        return None
    # WHETHER WE EVER TRIED. The rate only measures a speed difference during a withdrawal,
    # and a p95 near zero has two readings that look identical: the creature kept up with
    # us, or we never backed away from it and there was nothing to keep up with. A fox
    # comes out at 0.0 across 81 samples, and foxes are not slow - we simply stood and
    # fought them. Without this the table would have said a fox outruns us.
    moved = sum(1 for r in v if r > 2.0) / float(len(v))
    return {"measured": False,
            "p95": round(v[int(0.95 * len(v))], 1),
            "median": round(v[len(v) // 2], 1),
            "n": len(v),
            "withdrew": round(moved, 3),
            # Below a tenth the sample is mostly a standing fight and the p95 says more
            # about our own habits than about the creature.
            "informative": moved >= 0.10}


def foe_policy(rec):
    """What this species actually does, as something a simulator can act on.

    NOT a general learned policy, and the corpus is why. 1065 logged opponent moves across
    thirty species is one species over a hundred and most under fifty - enough to measure a
    move mix and to test a named hypothesis, nowhere near enough to fit a behaviour model
    over openings, initiative, distance and hitpoints at once. What follows is therefore a
    mix plus the ONE conditioning the data supports, with the evidence attached.

    Three hypotheses were tested and only one survived.

    IT DOES NOT TURTLE WHEN HURT. Attack share against its own worst opening runs 89%, 89%,
    87% across bands of 0-15, 16-40 and over 40. Flat. Openings we put on a creature do not
    slow the kill down by making it defend, which is worth knowing precisely because it is
    the intuitive expectation.

    IT DOES NOT TARGET OUR WEAKEST COLOUR - and this one took two separate controls to put
    down, because it survived the first. Pooled, 74.8% of animal attacks land on whichever
    colour we were most open in against a 59.5% shuffled null. Per species that vanished
    (ants 100% against a 99% null), which is Simpson's paradox: ants are a fifth of the
    sample and Ant Spit opens green and blue, so "our most open colour" is the one the ants
    themselves made.

    The second control is the one that makes the negative result stand. A species with only
    one attack cannot target anything, so "does it choose well" is not even a question for
    it - and a species that throws the same move 95% of the time has no choice to detect
    either, whatever its theoretical repertoire. Restricted to the eight species that
    genuinely vary - second-commonest move used at least a fifth of the time - not one
    exceeds the 95th percentile of its own shuffled null. Royal guard ants 79% against 71%,
    badgers 68% against 57%, moose 78% against 78%, boars 42% against 53%.

    IT DOES CHANGE WITH ITS OWN INITIATIVE, for some species. Attack share at 0 initiative
    against 1 or more: cattle 92% to 67%, boar 89% to 62%, bear 76% to 45% - and moose 76%
    to 74%, warrior ants 83% to 89%, vulture bees 91% to 100%. Five fall, two rise, one
    flat. So it is real and it is not a law, which means it belongs in a per-species record
    with its sample size rather than in the model as a rule.

    AND A WITHDRAWN ONE. Attack share appeared to fall to 62% beyond eight tiles, matching
    the guide's note that animals defend when out of attack range. It does not survive the
    obvious control: an animal has one target at a time, so when WE are far away it may
    simply be fighting somebody else, and its moves are still logged in our engagement.
    Restricted to fights nobody else was in, the whole distance sample is 66 observations
    and the beyond-eight-tiles band holds exactly one. There is no distance finding here,
    only a multi-party artefact - which is why `solo_n` below travels with every mix.
    """
    kinds = animal_move_kinds()
    mix, at0, at1, solo = defaultdict(int), [0, 0], [0, 0], 0
    for mv, ip, alone in rec.get("foe_moves") or ():
        mix[mv] += 1
        if alone:
            solo += 1
        known = kinds.get(mv)
        if (known is None) or (ip is None):
            continue
        slot = at0 if (ip == 0) else at1
        slot[0 if known else 1] += 1
    if not mix:
        return None
    total = float(sum(mix.values()))
    # A LIST of pairs, not a dict: the pack is written with sorted keys, which would put
    # this in alphabetical order and quietly destroy the one thing the mix is for.
    out = {"n": int(total),
           # How much of this came from a fight nobody else was in. An animal holds one
           # target at a time, so a mix drawn from group fights partly describes what it was
           # doing to somebody else. Low solo_n does not invalidate the mix - it is what
           # this creature throws - but it does forbid reading anything positional out of
           # it, which is exactly the mistake the withdrawn distance finding was.
           "solo_n": solo,
           "mix": [[k, round(v / total, 3)] for k, v in
                   sorted(mix.items(), key=lambda kv: (-kv[1], kv[0]))]}
    n0, n1 = sum(at0), sum(at1)
    if (n0 >= 10) and (n1 >= 10):
        share0, share1 = at0[0] / float(n0), at1[0] / float(n1)
        out["attack_share_at_0_ip"] = round(share0, 3)
        out["attack_share_above_0_ip"] = round(share1, 3)
        out["ip_n"] = [n0, n1]
        # Ten points of swing on ten-plus observations each way. Below that the two bands
        # are the same number with noise on it, and calling it a behaviour would be reading
        # the noise.
        out["ip_conditioned"] = abs(share0 - share1) >= 0.10
        # And whether that conditioning can be believed. Initiative is RELATIONAL - the
        # guide is explicit that points are held against one opponent and not another - so
        # a creature fighting somebody else can sit at zero initiative against us while
        # acting entirely on them. Cattle's 92%-to-67% swing rests on 91 observations of
        # which ONE came from a fight nobody else was in. The finding is kept because the
        # swing is large and repeats across species, and flagged because it cannot be
        # cleaned up without solo fights that do not exist yet.
        out["ip_group_contaminated"] = (solo < (0.2 * total))
    return out


def foe_skill_entry(rec):
    """What the pack should carry for an opponent's combat skill.

    {value, lo, hi, n, moves, equalized} - value is None when every move equalized, and
    lo/hi then bound it. Disagreement between moves is reported rather than averaged: a
    creature sitting near our own skill is exactly where the branch test is least stable.
    """
    bymove = defaultdict(list)
    for row in rec.get("wd") or ():
        if (row[3] >= MIN_GAIN) and row[4] and (row[5] > 0):
            bymove[row[0]].append((row[4], row[5]))
    ests, lo_b, hi_b, used = [], 0.0, float("inf"), []
    for mv, obs in sorted(bymove.items()):
        if len(obs) < 3:
            continue
        wa = obs[0][0]
        wds = sorted(o[1] for o in obs)
        m = load_moves().get(mv) or {}
        mult = m.get("weight_mult") or 1.0
        our = (wa / mult) if mult else wa
        skill, lo, hi, _branch = foe_skill_from(our, wds[len(wds) // 2])
        used.append(mv)
        if skill is not None:
            ests.append(skill)
        else:
            lo_b, hi_b = max(lo_b, lo), min(hi_b, hi)
    if not used:
        return None
    if ests:
        ests.sort()
        med = ests[len(ests) // 2]
        # Some moves equalized and some did not, and they disagree. That is not an average
        # waiting to be taken: a creature near our own skill is exactly where the branch
        # test is least stable, so the estimate and the bound are both suspect. The badger
        # is the case - four moves bound it to 56-116 while Punch and Sting read 22 and 39.
        disputed = (hi_b < float("inf")) and not (lo_b <= med <= hi_b)
        out = {"value": round(med, 1),
               "lo": round(ests[0], 1), "hi": round(ests[-1], 1),
               "n": len(ests), "moves": used, "equalized": False}
        if disputed:
            out["disputed"] = True
            out["bound_lo"], out["bound_hi"] = round(lo_b, 1), round(hi_b, 1)
        return out
    if hi_b < float("inf"):
        return {"value": None, "lo": round(lo_b, 1), "hi": round(hi_b, 1),
                "n": 0, "moves": used, "equalized": True}
    return None


def foe_skill_from(our_skill, wd_naive):
    """The opponent's combat SKILL, recovered from a naively-inverted defence weight.

    This is what the defence-weight numbers were always trying to be, and could not be
    while equalization was unmodelled.

    Write k for the observed gain over the move's listed opening after falloff, so that
    k**3 = equalize(S, F) * multMe (an animal holds no stance, so its own multiplier is 1).
    The naive inversion reports Wd = Wa/k**3 = S*multMe/k**3. Substituting each branch of
    equalize:

        F < S/2   k**3 = S/(2F) * multMe   ->   Wd = 2F     so F = Wd / 2
        F > 2S    k**3 = 2S/F * multMe     ->   Wd = F / 2   so F = 2 * Wd
        in band   k**3 = multMe            ->   Wd = S       and F is only bounded

    The middle case is the artefact: Wd comes back as our own skill and says nothing. The
    outer two are real measurements, and the proof they are real is that moves reading
    DIFFERENT skills of ours converge on one answer - a player read 482, 589 and 599 from
    three moves across Unarmed 58 and Melee 81; another read 29 and 29; a bee swarm read 16
    and 19. Nothing forces that agreement unless the branch arithmetic is right.

    Returns (skill, lo, hi, branch). skill is None inside the band, where lo/hi bound it.
    """
    if (our_skill <= 0) or (wd_naive <= 0):
        return (None, None, None, "no reading")
    # The branch is decided by where the naive answer sits relative to OUR skill, because
    # that is exactly what the middle case returns.
    if wd_naive < (our_skill * 0.85):
        return (wd_naive / 2.0, None, None, "weaker than us")
    if wd_naive > (our_skill * 1.15):
        return (wd_naive * 2.0, None, None, "stronger than us")
    return (None, our_skill / 2.0, our_skill * 2.0, "equalized - only bounded")


def report_foe_skill(rec):
    """Per-move skill recovery, and what the moves agree on.

    Prints the bound from equalized moves and the estimate from the rest, then intersects
    them. A move that lands outside the intersection is shown rather than dropped: with
    Sting at deck level 3 its mu is an interval and its attack weight with it, so it is the
    one move here whose input is not pinned.
    """
    bymove = defaultdict(list)
    for row in rec.get("wd") or ():
        if (row[3] >= MIN_GAIN) and row[4] and (row[5] > 0):
            bymove[row[0]].append((row[4], row[5]))
    rows = []
    for mv, obs in sorted(bymove.items()):
        if len(obs) < 3:
            continue
        wa = obs[0][0]
        wds = sorted(o[1] for o in obs)
        rows.append((mv, wa, wds[len(wds) // 2], len(obs)))
    if not rows:
        return
    print("\n  combat skill    what the opponent's own UA/MC actually is, per move")
    lo_b, hi_b = 0.0, float("inf")
    ests = []
    for mv, wa, wd, n in rows:
        m = load_moves().get(mv) or {}
        mult = m.get("weight_mult") or 1.0
        our = wa / mult if mult else wa
        skill, lo, hi, branch = foe_skill_from(our, wd)
        if skill is not None:
            ests.append(skill)
            print("      %-20s our %-6.0f %2d obs   -> %-6.0f  %s"
                  % (mv[:20], our, n, skill, branch))
        else:
            lo_b, hi_b = max(lo_b, lo), min(hi_b, hi)
            print("      %-20s our %-6.0f %2d obs   -> %-6s  %s (%.0f - %.0f)"
                  % (mv[:20], our, n, "?", branch, lo, hi))
    if ests:
        ests.sort()
        print("      the moves agree on about %.0f (%.0f to %.0f across %d move(s))"
              % (ests[len(ests) // 2], ests[0], ests[-1], len(ests)))
        if hi_b < float("inf") and not (lo_b <= ests[len(ests) // 2] <= hi_b):
            print("      NB that sits outside the %.0f-%.0f the equalized moves bound it to"
                  % (lo_b, hi_b))
    elif hi_b < float("inf"):
        print("      every move equalized, so the skill is only bounded: %.0f to %.0f"
              % (lo_b, hi_b))


def equalization_verdict(rec):
    """Whether a species' defence weight was measured or merely reflected back at us.

    EQUALIZATION is a dead zone: two combat skills within a factor of two are compared as
    if equal, so the skill term in cbrt(Wa/Wd) is pinned to 1. Inside it, inverting an
    opening gain for Wd cannot work - it returns the attacker's own weight, dressed as the
    defender's.

    The tell is direct and needs no extra data. If a species' per-move answers track OUR
    attack weight for each move, the skills are equalized and nothing was measured. If the
    moves agree on one number regardless of their weights, the skills are far enough apart
    that the ratio is live and the number is real.

        boar        KITO at Wa 58 gave 51-73, Quick Barrage at Wa 111 gave 111-158
                    -> equalized. Recorded as this corpus's one unresolved anomaly for
                       weeks; it was never about the boar.
        bee swarm   three moves at Wa 58, 112 and 125 all gave about 30
                    -> live, and 30 is a measurement.

    Returns (verdict, detail).
    """
    # rec["wd"] rows are (move, colour, standing, gain, wa, wd, lo, hi).
    bymove = defaultdict(list)
    for row in rec.get("wd") or ():
        if row[3] >= MIN_GAIN and row[4] and row[5] > 0:
            bymove[row[0]].append((row[4], row[5]))
    pairs = []
    for mv, rows in bymove.items():
        if len(rows) < 2:
            continue
        wds = sorted(r[1] for r in rows)
        pairs.append((mv, rows[0][0], wds[len(wds) // 2]))
    if len(pairs) < 2:
        return (None, None)
    # Does the answer follow our weight, or ignore it?
    was = [p[1] for p in pairs]
    wds = [p[2] for p in pairs]
    if (max(was) / min(was)) < 1.3:
        return (None, None)   # our weights are too alike to tell the two apart
    follow = sum(1 for _mv, wa, wd in pairs if 0.6 <= (wd / wa) <= 1.7)
    spread_wd = max(wds) / max(min(wds), 1e-9)
    spread_wa = max(was) / min(was)
    if (follow >= (len(pairs) - 1)) and (spread_wd > (spread_wa * 0.6)):
        return ("equalized",
                "every move's answer tracks OUR OWN attack weight for that move, which is "
                "what an equalized comparison returns - the skills are within a factor of "
                "two and no gain here can measure this creature")
    if spread_wd < (spread_wa * 0.5):
        return ("live",
                "the moves agree despite their attack weights differing by %.1fx, so the "
                "skill ratio is outside the equalization band and this is a real figure"
                % spread_wa)
    return (None, None)


def report_reaggro(per):
    kept, lost, ex = reaggro_cost(per)
    if (kept + lost) == 0:
        return
    print("=" * 78)
    print("WHAT RE-AGGRO COSTS")
    print("=" * 78)
    print("  Peacing a fleeing creature and re-aggroing it turns it round to fight again,")
    print("  which is the standard way to finish something that runs. It also ends the")
    print("  combat relation, and the initiative goes with it.\n")
    print("  Across every boundary where a fight with one creature ended while we still")
    print("  held initiative and another began:\n")
    print("      kept it: %d        lost it: %d" % (kept, lost))
    for sp, before, after in ex:
        print("      %-14s ended on %d, resumed on %d" % (sp, before, after))
    print()
    print("  So re-aggro forfeits the pool, and Take Aim spends thirty ticks a point to")
    print("  build one. Against a creature we can outrun, staying in combat and holding")
    print("  the range as it flees is worth what the pool is worth. Against a faster one")
    print("  there is nothing to weigh - following it is not on the table.\n")


def report_shared_moves(per):
    """One animal move seen against several species - the first cross-species comparison.

    Opening pressure is P = cbrt(Wa_foe / Wd_us) * Ob, and Ob belongs to the MOVE. So for
    one move thrown by two different creatures at a known Wd_us, Ob cancels in the ratio
    and what is left is cbrt of the ratio of their attack weights. Nothing else in this
    project can compare two species directly; every other measurement is a property of one
    creature recovered from our attacks on it.

    It exists only because our own defence weight is known rather than inferred. Before the
    stance was pinned, Wd_us was an unknown sitting inside every one of these numbers.

    Read the ratio cubed and read it carefully: P varies as the CUBE ROOT of attack weight,
    so a 12% spread in P is a 40% spread in Wa, and the observed spreads are wide enough
    to overlap. What the corpus supports today is the ordering, not the magnitudes.
    """
    shared = defaultdict(list)
    for name, rec in per.items():
        for (mv, colour), vals in rec.get("pressure", {}).items():
            if len(vals) >= 2:
                shared[(mv, colour)].append((name, sorted(vals)))
    shared = dict((k, v) for k, v in shared.items() if len(v) > 1)
    if not shared:
        return
    print("=" * 78)
    print("ONE MOVE, SEVERAL CREATURES")
    print("=" * 78)
    print("  Opening pressure is cbrt(its attack weight / OURS) x the move's own listed")
    print("  opening. For one move the listed opening is the same whoever throws it, so a")
    print("  ratio between two creatures is cbrt of the ratio of their attack weights -")
    print("  and cubing it is how to read it. Our own defence weight is known, which is")
    print("  the only reason any of this is a measurement rather than two unknowns.\n")
    for (mv, colour), rows in sorted(shared.items()):
        rows.sort(key=lambda r: -(sum(r[1]) / len(r[1])))
        top = sum(rows[0][1]) / len(rows[0][1])
        print("  %s (%s)" % (mv, colour))
        for name, vals in rows:
            mean = sum(vals) / len(vals)
            print("      %-14s n=%-4d %5.1f  (%.1f to %.1f)   attack weight x%.2f of the "
                  "strongest here" % (name, len(vals), mean, vals[0], vals[-1],
                                      (mean / top) ** 3))
        print()


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
    print("  mu is measured directly off Take Aim, whose cooldown divides by it - see the")
    print("  'Take Aim says' column, which owes nothing to any fight. The rows themselves")
    print("  are one move against another thrown at the SAME creature, since only that")
    print("  divides the opponent out, and they are the weaker evidence of the two.")
    print("  The linear curve is shown only because the corpus EXCLUDES it: it wants")
    print("  1.125 at level 2 where Take Aim measures 1.143 to 1.177.\n")
    print("  %-6s %-6s %-16s %-5s %-13s %-11s %s"
          % ("level", "mu", "interval", "n", "Take Aim says", "linear said", "measured on"))
    for lvl, mu, lo, hi, n, name, mv, ref in sorted(rows):
        pred = mu_linear(lvl)
        direct = MU_MEASURED.get(lvl)
        print("  %-6s %-6.2f %-16s %-5d %-13s %-11s %s vs %s, %s"
              % (lvl, mu, "%.2f - %.2f" % (lo, hi), n,
                 ("%.3f-%.3f" % direct) if direct else "-",
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
            kept = sum(n for _f, _p, n in rec["skipped"])
            print("  %d engagement(s) with something else going on - %d observation(s)"
                  " survived per-bracket attribution anyway:"
                  % (len(rec["skipped"]), kept))
            for f, probs, _n in rec["skipped"][:4]:
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
                # An intersection that survives by a hair is a near-miss, not a precise
                # answer, and it prints identically to a real one. Four badgers whose own
                # bands are 71-132, 71-97, 73-122 and 45-73 intersect at exactly 73-73:
                # that reads as a measurement good to the point when it is really the
                # observations only just failing to contradict each other. Say so - the
                # next observation is as likely to empty the intersection as confirm it.
                widths = [w[7] - w[6] for w in rec["wd"]]
                tightest = min(widths) if widths else 0.0
                if (ihi - ilo) < (0.25 * tightest):
                    print("                   NB the intersection (%.0f wide) is far tighter"
                          " than any single" % (ihi - ilo))
                    print("                   observation (narrowest %.0f) - these barely"
                          " overlap, so read it as a" % tightest)
                    print("                   near-miss rather than a figure known to the"
                          " point")
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
                print("                   per move. Read the Wd/Wa column, not the Wd one:")
                print("                     ratios all near 1.0  -> EQUALIZED. The skills")
                print("                       are within a factor of two, the skill term is")
                print("                       pinned to 1, and inverting a gain returns OUR")
                print("                       OWN weight. Nothing here measures the target.")
                print("                     Wd agreeing while Wa differs -> a real figure.")
            for mv in sorted(bymove):
                rows = bymove[mv]
                mlo, mhi = max(r[2] for r in rows), min(r[3] for r in rows)
                span = ("%.0f - %.0f" % (mlo, mhi)) if mlo <= mhi else "no overlap"
                wa = rows[0][0]
                med = sorted(r[1] for r in rows)[len(rows) // 2]
                print("      %-20s Wa %-6.1f %2d obs   Wd %-14s midpoints %.0f - %.0f"
                      "   Wd/Wa %.2f"
                      % (mv[:20], wa, len(rows), span,
                         min(r[1] for r in rows), max(r[1] for r in rows),
                         (med / wa) if wa else 0.0))
            verdict, detail = equalization_verdict(rec)
            if verdict:
                print("                   -> %s" % detail)
            report_foe_skill(rec)
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
        arm = fit_armour(rec["soak"], rec.get("soak_clean"))
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
            if arm.get("source") == "mixed":
                print("                   fitted from hits by EVERY attacker, ours and "
                      "other people's - the")
                print("                   split depends on the attacker's penetration, "
                      "and two hits inside one")
                print("                   two-millisecond bucket merge, so this is looser "
                      "than its width suggests")
            if wiki_arm is not None:
                # A fit may only contradict a stated figure when it is clean enough to be
                # believed. Every armour that lands exactly on the wiki fits with an rms
                # under 0.25; every one that contradicts it sits above 2. The boar settles
                # it - 15 exactly from the fight nobody else joined, 15-16 with rms 2.33
                # from 222 mixed hits of the same creature.
                trusted = (arm["rms"] <= ARM_RMS_TRUST) or (arm.get("source") == "clean")
                if wiki_arm == tlo == thi:
                    mark = "agrees"
                elif tlo <= wiki_arm <= thi:
                    mark = "contains it"
                elif not trusted:
                    mark = ("outside this fit, but the fit's residual of %.2f is too high "
                            "to contradict it" % arm["rms"])
                else:
                    mark = "DIFFERS"
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
        if rec["pressure"]:
            wds = sorted(rec["my_wd"])
            print("\n  how fast it opens us   against OUR defence weight of %s"
                  % (("%g" % wds[0]) if len(wds) == 1
                     else "%g to %g" % (wds[0], wds[-1])))
            print("                   points opened on a fresh colour, per use. This is")
            print("                   cbrt(its attack weight / ours) x the move's own")
            print("                   listed opening - the two cannot be separated, because")
            print("                   the wiki's animal table says WHICH colours a move")
            print("                   opens and never by how much.")
            rows = []
            for (mv, colour), vals in rec["pressure"].items():
                vals = sorted(vals)
                rows.append((sum(vals) / len(vals), mv, colour, len(vals),
                             vals[0], vals[-1]))
            for mean, mv, colour, n, lo, hi in sorted(rows, reverse=True):
                print("      %-22s %-7s n=%-4d %5.1f  (%.1f to %.1f)"
                      % (mv[:22], colour, n, mean, lo, hi))
        print()


def period_of(gaps_ms):
    """How often this creature acts, in ticks, from the gaps between its own actions.

    The MEAN of the gaps, with lulls trimmed, and the arithmetic settles it: over a fight
    of T ticks with N actions, T is the sum of the gaps, so actions per tick is exactly
    1 / mean(gap). The optimizer wants the long-run rate at which it gets hit, and that is
    the mean and nothing else. A median is a different quantity that happens to look
    similar, and on a two-card creature it is not even close.

    Two things this deliberately does NOT do, both of which were tried here first.

    IT DOES NOT FOLD DOUBLES. A gap at twice the period is one action that was never
    logged, and folding it back looked like free evidence. The corpus says the doubles are
    rare - one in sixty-one for ants - and that folding costs far more than it buys,
    because a genuine second cooldown near a 2:1 ratio gets swallowed by the same rule.
    Cattle acts on two clocks, 22 ticks and 38, and the fold turned every 38 into a 19 and
    reported the creature as twice as dangerous as it is. The tell that 38 is real and not
    a double is that NOTHING sits at 44, where a double of 22 would have to be.

    IT DOES NOT COLLAPSE THE MODES. Multi-modality is the normal case, not the exception:
    boar has clusters at 30, 38 and 49, caveangler at 30 and 41. The mean is still the
    right single number for a rate, and the modes are reported beside it so that a number
    which is a blend of two cards is visibly a blend rather than silently one.

    Lulls - it withdrew, we withdrew, something walked between us - are cut at three times
    the median, and the count of what was cut is carried out, because a filter whose
    removals nobody counts is doing more than it says.

    Returns None when there is nothing to measure.
    """
    ticks = sorted((g / 60.0) for g in gaps_ms)
    if not ticks:
        return None
    med = ticks[len(ticks) // 2]
    if med <= 0:
        return None
    cut = 3.0 * med
    kept = [t for t in ticks if t <= cut]
    if not kept:
        return None

    mean = sum(kept) / len(kept)
    hist = defaultdict(int)
    for t in kept:
        hist[int(round(t))] += 1

    # Peaks: a rounded value holding a real share of the mass, with nothing bigger within
    # a quarter of it. Reported in order of weight, so the first is the dominant clock.
    floor = max(2, int(0.08 * len(kept)))
    peaks = []
    for t, n in sorted(hist.items(), key=lambda kv: (-kv[1], kv[0])):
        if n < floor:
            continue
        if any(abs(t - q) <= (0.25 * max(t, q)) for q in peaks):
            continue
        # A peak sitting on a whole multiple of one already found is the missed-action
        # double, and listing it as a second clock would claim the creature has a card it
        # does not have. This is the one place the doubles argument holds: it decides how
        # a peak is LABELLED, and never what the mean is computed from.
        # Doubles only, and tightly. A triple is rare enough not to matter, and the window
        # that catches one is wide enough to swallow a real second clock: a player acting
        # every 19 ticks also has a peak at 50, which is 12% off three times 19 and is not
        # a missed action.
        if any(abs(t - (2 * q)) <= (0.10 * t) for q in peaks):
            continue
        peaks.append(t)
        if len(peaks) == 3:
            break

    return {"ticks": round(mean, 1), "n": len(kept), "dropped": len(ticks) - len(kept),
            "median": round(med, 1), "lo": round(kept[0], 1), "hi": round(kept[-1], 1),
            "modes": peaks}


COLOURS = ("red", "green", "blue", "yellow")


def threat(rec):
    """What this opponent does to US, in the form the optimizer plans against.

    Everything above in this file measures OUR attacks on THEM, because that is the side
    a log can attribute. This is the other half, and the optimizer is useless without it:
    a frontier trades damage taken against time spent, and neither term exists until the
    opponent swings back.

    Four quantities, each measured or explicitly absent.

        period      the median gap between its own actions, in ticks. Taken only from
                    engagements clean for defence - a fight with a third party in it has
                    gaps that are the other player's timing as much as the creature's.

        pressure    points it opens on a fresh colour per action, per colour. Built from
                    the per-move pressures already measured, weighted by how often it
                    actually throws each move: an ant that spits nineteen times in twenty
                    should read as an ant that spits, not as the average of its two cards.
                    Pressure is gain / (1 - Oc), so the falloff is already divided out and
                    what remains is fully observed - it needs nothing about the creature's
                    own attack weight, which is exactly the quantity equalization hides.

        damage      SHP through our armour per unit of SQUARED combined opening, because
                    their damage follows the same shape ours does. One coefficient stands
                    in for their strength and their weapon, neither of which a log records.

        flees       the share of its hitpoints below which it stops fighting. Needs the
                    aggression state that schema 7 logs and no fight in this corpus
                    carries yet, so it is null here rather than guessed.

    Returns None when the corpus has not seen this creature act on us at all. A creature
    we have only ever hit is not a creature we know how to fight.
    """
    period = period_of(rec.get("foe_gaps") or ())

    # How often it throws each move, from every engagement - the weights.
    freq = defaultdict(int)
    for name, _ip, _alone in (rec.get("foe_moves") or ()):
        if name:
            freq[name] += 1

    pressure, pn = dict((c, 0.0) for c in COLOURS), 0
    bymove = defaultdict(dict)
    for (mv, colour), vals in (rec.get("pressure") or {}).items():
        if vals and (colour in pressure):
            bymove[mv][colour] = sum(vals) / float(len(vals))
            pn += len(vals)
    if bymove:
        # Weighted by use. A move we have measured but never counted still gets a vote of
        # one, so a card seen twice does not vanish and does not dominate either.
        total = 0.0
        for mv, cols in bymove.items():
            w = float(freq.get(mv) or 1)
            total += w
            for c, v in cols.items():
                pressure[c] += w * v
        for c in COLOURS:
            pressure[c] = round(pressure[c] / total, 2) if total > 0 else 0.0

    wds = sorted(rec.get("my_wd") or ())
    # The block weight it was measured against, so the figure can be rescaled when ours
    # changes. Without this the pressure is a number with no denominator: it was measured
    # against SOMETHING of ours, and a pack that did not say what would be inviting the
    # simulator to apply it at any defence at all.
    against = (sum(wds) / len(wds)) if wds else None

    coefs = []
    for h in (rec.get("took") or ()):
        o = [min(x, 100) / 100.0 for x in (h.get("openings") or [])]
        if (len(o) != 4) or (h.get("shp", 0) <= 0):
            continue
        c = model.combined(o)
        # An attack that landed against nothing standing is not evidence about the
        # coefficient - it is evidence that the opening term is not the whole story, and
        # dividing by a combined opening near zero would turn that into a huge number.
        if c < 0.05:
            continue
        coefs.append(h["shp"] / (c * c))
    coefs.sort()
    damage = ({"coef": round(coefs[len(coefs) // 2], 1), "n": len(coefs),
               "lo": round(coefs[0], 1), "hi": round(coefs[-1], 1)}
              if coefs else None)

    if (period is None) and (pn == 0) and (damage is None):
        return None
    return {"period": period, "pressure": pressure, "pressure_against": against,
            "pressure_n": pn, "damage": damage,
            # Measurable the moment a fight is logged on a schema 7 client: the second
            # bit of the aggression state is its olive branch. Null, not zero - zero
            # would mean "fights to the death", which is a claim.
            "flees_below": None}


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

        # The SKILL, which is what the simulator now wants and what the corpus can actually
        # recover. defence_weight above is kept because it is what the corpus literally
        # observed - the naive inversion - but it is only equal to the opponent's block
        # weight when the skills happen to sit outside the equalization band, and inside it
        # the number is our own attack weight handed back. See foe_skill_from.
        #
        # A pack entry therefore says which of the three cases it is, and an equalized one
        # carries a BOUND rather than a value. A simulator given a bound can run the fight
        # at both ends and report an interval; given a fabricated point it would report a
        # confident answer to a question the corpus never answered.
        entry["skill"] = foe_skill_entry(rec)
        entry["policy"] = foe_policy(rec)
        entry["relative_speed"] = relative_speed(rec)
        # What it does to us. Everything else in this entry is our attacks on it.
        entry["threat"] = threat(rec)

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

        arm = fit_armour(rec["soak"], rec.get("soak_clean"))
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
    report_shared_moves(per)
    report_mu_reductions()
    report_reaggro(per)
    if write:
        write_pack(per, moves)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
