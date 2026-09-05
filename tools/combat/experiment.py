#!/usr/bin/env python3
"""Which fight is worth going and having, ranked by what it would settle.

    python tools/combat/experiment.py

WHY THIS EXISTS. The obvious way to close the loop is to fight, compare what the model
predicted against what happened, and treat the difference as new data. That is right in
shape and wrong in two ways that matter.

THE FIRST is that a residual does not say what was wrong. "Predicted 42, took 37" is one
number standing in for ten parameters, and the model has ten. A large residual is a
prompt to investigate, not evidence about any particular term.

THE SECOND is worse, because it looks like success. If the optimizer picks the fights,
the corpus only ever covers the moves the optimizer already likes. A move it never picks
is never measured, so its parameters stay wide; being wide, it loses the frontier; so it
is never picked. The error seals itself in, and the residuals FALL while it happens -
because the model is only ever asked about cases it already gets right. Any loop that
collects data where the model chooses to go has this failure, and it has no symptom.

So this ranks experiments by a different quantity. Not where the model is wrong - where
the surviving HYPOTHESES DISAGREE. A fight every candidate predicts identically teaches
nothing about which is right, however large its residual; a fight they split on is
decisive even when it is dull. That is the difference between collecting data and
designing an experiment, and only the second one converges.

The corpus already contains one of these, found by hand. mu_curve's docstring says: "Take
Aim's own maximum is 3: one further point in it reads level 3 to about +/-0.02, where this
curve says 1.296 and linear says 1.250." That is a designed experiment, identified by a
person reading two formulas against each other. This is the machine that finds them.

WHAT MAKES AN EXPERIMENT DECISIVE HERE. The server reports cooldowns in whole ticks, so
two hypotheses are distinguishable exactly when they round to different integers - a
discrete criterion, not a statistical one. One observation settles it, or no number of
them do. That also means an experiment can be decisive on paper and fragile in fact: if
one candidate predicts 23.48 ticks and another 23.52 they differ as integers, but any
small error in the base cooldown flips the answer. Both are reported, separately.

Stdlib only (this module). The opening-decay fitter tools/combat/decay_fit.py is the one exception that requires scipy/numpy (see tools/combat/requirements.txt) for O(t)=O0*exp(-t/tau) fitting.
"""

import json
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402
import fightlog  # noqa: E402
import model  # noqa: E402


# How far a predicted cooldown must sit from a .5 boundary before its rounding is worth
# trusting. A tenth of a tick is six thousandths of a second, and the base cooldowns are
# transcribed off a sheet rather than measured, so anything tighter is asking the
# transcription to be exact.
ROBUST = 0.10


def _mu_invsqrt(level):
    """1.5 - 0.5/sqrt(L). Fits level 2 and is EXCLUDED at level 5, where it caps at 1.276
    against reduction floors of 1.49 and up."""
    if not level or level < 1:
        return None
    return 1.5 - (0.5 / (min(level, estimate.MU_LEVELS) ** 0.5))


def _mu_ratio(level):
    """1 + 0.5(L-1)/(L+1). Also fits level 2, also EXCLUDED at level 5, capping at 1.333."""
    if not level or level < 1:
        return None
    L = min(level, estimate.MU_LEVELS)
    return 1.0 + (0.5 * (L - 1.0) / (L + 1.0))


# The field, with what killed each one. The dead are kept in deliberately: an experiment
# that cannot separate a surviving candidate from a dead one is not measuring what it
# thinks it is, and the corpse of a known-wrong curve is the cheapest available check on
# that. EXCLUDED is what makes the difference between the two questions this file asks -
# with more than one survivor the job is to separate them, and with exactly one the job
# is to try to kill it.
HYPOTHESES = (
    ("linear", estimate.mu_linear),
    ("wiki", lambda L: {4: 1.4, 5: 1.5}.get(L)),
    ("sqrt", estimate.mu_curve),
    ("invsqrt", _mu_invsqrt),
    ("ratio", _mu_ratio),
)

EXCLUDED = {
    "sqrt": "levels 2 and 3 read 1.111-1.154 and 1.200-1.250; this wants 1.168 and 1.296",
    "invsqrt": "caps at 1.276 for level 5, against reduction floors of 1.49 and up",
    "ratio": "caps at 1.333 for level 5, against the same floors",
}

LIVE = tuple(n for n, _f in HYPOTHESES if n not in EXCLUDED)


# The instruments. A card can measure the deck weighting only if its COOLDOWN divides by
# it, and exactly two do.
#
# WHICH ONE TO USE IS A QUESTION OF RESOLUTION, not of any ceiling. The reading is an
# integer number of ticks, so a card resolves a difference in the weighting only when that
# difference moves the integer - and how far it moves scales with the card's base. At
# level 4 the two surviving curves differ by 1.8%: Take Aim's base of 30 gives 21.8 and
# 21.4 ticks, which both floor to 21 and settle nothing, while Dash's 80 gives 58.2 and
# 57.1, which floor apart. A bigger base is a finer instrument.
#
# Take Aim earns its place for the levels it can reach because it carries an initiative
# term, and a ladder of readings at rising initiative is many independent measurements of
# one number rather than one repeated. Dash has no such term: one reading is every reading.
#
# Neither entry claims a maximum level. The deck dump's maxlevel is how far the character
# has levelled a card, not how far it can go - twenty-one cards report 1, Quick Barrage
# among them - so nothing available here says whether Take Aim can pass 3.
# Opportunity Knocks' own multiplier, read from the sheet rather than restated, so this
# cannot drift from what the parser found.
def _boost():
    try:
        for m in estimate.load_moves().values():
            if m.get("boost_greatest"):
                return float(m["boost_greatest"])
    except Exception:
        pass
    return 0.4


BOOST = _boost()

INSTRUMENTS = {
    "Take Aim": {"base": 30.0, "ip_scale": 0.20, "max_level": estimate.MU_LEVELS},
    "Dash": {"base": 80.0, "ip_scale": 0.0, "max_level": estimate.MU_LEVELS},
}


def pins_mu(observed_ticks, ip, base=None, ip_scale=None):
    """The interval one observed cooldown pins the deck weighting to.

    The whole point of a falsification test rather than a discrimination one. With a single
    surviving curve there is nothing left to separate it FROM, so an experiment is worth
    running to the extent it could prove the survivor wrong - and that is exactly how
    narrowly one integer reading constrains mu. A reading that admits 1.20 to 1.25 can
    refute a curve claiming 1.296; one that admits 1.0 to 1.5 can refute nothing.

    It inverts the DOUBLE floor. The card's own cooldown is an integer C = floor(base/mu),
    and what is observed is floor(C * f). So the reading names a set of possible C, and
    each C names an interval of mu. Inverting a single round-half-up instead is what
    excluded the linear curve for a week.
    """
    if base is None:
        base = estimate.TAKE_AIM_BASE
    if ip_scale is None:
        ip_scale = estimate.TAKE_AIM_IP_SCALE
    f = 1.0 + (ip_scale * ip)
    cands = [c for c in range(1, int(base) + 1)
             if int(math.floor(c * f)) == int(observed_ticks)]
    if not cands:
        return None
    # floor(base / mu) == C  <=>  mu in (base / (C + 1), base / C]
    return (base / (max(cands) + 1.0), base / float(min(cands)))


def raw_cooldown(base, mu, ip_scale, ip):
    """The cooldown a card would report, before the second floor.

    The card's own cooldown is an INTEGER - base over the deck weighting, floored - and
    initiative scales that integer. Both floors are load-bearing: reading this as a single
    round-half-up is what excluded the linear curve for a week and sent the whole project
    after a square-root one.
    """
    if not mu or mu <= 0:
        return None
    return math.floor(base / mu) * (1.0 + (ip_scale * ip))


def takeaim_raw(mu, ip):
    """The cooldown Take Aim would report at this deck weighting and initiative.

    Take Aim is a maneuver, so no agility term enters - which is the whole reason it is an
    instrument for the deck weighting at all. An attack's cooldown carries a factor that
    depends on the opponent, and that would have to be known before mu could be read out
    of it."""
    return(raw_cooldown(estimate.TAKE_AIM_BASE, mu, estimate.TAKE_AIM_IP_SCALE, ip))


def discriminate(level, ip, hyps=HYPOTHESES):
    """What one Take Aim at this level and initiative would settle.

    Returns a dict, or None when no hypothesis has an opinion at this level.

        splits     the distinct integer cooldowns, each with the candidates predicting it
        decisive   whether more than one integer is predicted
        margin     the closest two raw predictions that land on DIFFERENT integers, in
                   ticks - how much room the separation actually has
        robust     the smallest distance from any raw prediction to a .5 boundary. Below
                   ROBUST, the integer that gets reported turns on the base cooldown
                   having been transcribed exactly right.
    """
    raw = {}
    for name, fn in hyps:
        mu = fn(level)
        r = takeaim_raw(mu, ip)
        if r is not None:
            raw[name] = (mu, r)
    if not raw:
        return None

    splits = defaultdict(list)
    for name, (_mu, r) in raw.items():
        splits[int(math.floor(r))].append(name)

    # Distance to the nearest rounding boundary, which is where a prediction is fragile.
    # Distance to the nearest boundary, which for a floor is the fractional part.
    robust = min(min(r % 1.0, 1.0 - (r % 1.0)) for _mu, r in raw.values())

    margin = None
    for a, (_ma, ra) in raw.items():
        for b, (_mb, rb) in raw.items():
            if a >= b:
                continue
            if int(math.floor(ra)) == int(math.floor(rb)):
                continue
            d = abs(ra - rb)
            if (margin is None) or (d < margin):
                margin = d

    return {"level": level, "ip": ip, "raw": raw,
            "splits": dict((k, sorted(v)) for k, v in splits.items()),
            "decisive": len(splits) > 1,
            "margin": margin, "robust": robust}


def separable(level, a, b, ips=range(0, 21), hyps=HYPOTHESES):
    """Whether ANY initiative separates these two candidates at this level.

    The distinction that matters most, and the one a count of splits hides. An experiment
    can split the field three ways and still leave the two LIVE candidates sitting on the
    same integer - which is exactly what happens at level 5, where the leading curve and
    the linear one both reach 1.5 by construction and are identical there forever. A
    report that said "splits [20, 23, 24]" and stopped would be describing a decisive
    experiment for a question nobody is asking.

    Returns the lowest initiative that separates them, or None when nothing does.
    """
    fns = dict(hyps)
    if (a not in fns) or (b not in fns):
        return None
    for ip in ips:
        ra = takeaim_raw(fns[a](level), ip)
        rb = takeaim_raw(fns[b](level), ip)
        if (ra is None) or (rb is None):
            continue
        if int(math.floor(ra)) != int(math.floor(rb)):
            return ip
    return None


def coverage(paths=None):
    """How often we have USED each card we own, and against how many species.

    The counterweight to everything above. Discrimination and falsification both rank
    questions the corpus is in a position to answer; this is where there is no question
    yet because there is no observation - a card used twice has no measured behaviour to
    be right or wrong about, and no residual it produces means anything.

    It is the direct guard against the failure in this file's header. A card with no
    observations cannot be argued out of the deck by a model that has never seen it, and
    a report that never surfaced it would BE that collapse, quietly.

    Counted off the move rows rather than off a successful estimate, and deliberately.
    The cards worth surfacing are exactly the ones that produced nothing measurable, so
    counting from the estimator's own output would drop them - the thing being looked for
    would be filtered out by the instrument looking for it.
    """
    if paths is None:
        paths, _dirs = fightlog.default_logs(estimate.ROOT)

    # The log names a move by its resource path and the deck dump names it by its display
    # name, and joining them on either one alone silently produces two disjoint lists that
    # both look complete - which is what the first run of this did, reporting all 36 owned
    # cards as never thrown while listing ten resource paths with thousands of uses.
    byres = dict((m["res"], nm) for nm, m in estimate.load_moves().items() if m.get("res"))

    used = defaultdict(lambda: defaultdict(int))
    for p in sorted(paths):
        try:
            log = fightlog.read(p, None)
        except (OSError, ValueError):
            continue
        if not log.rows:
            continue
        for eng in log.engagements:
            sp = estimate.bucket(eng)
            for mv in eng.moves:
                if mv.get("actor") == "foe":
                    continue
                nm = mv.get("move")
                if nm:
                    used[byres.get(nm, nm)][sp] += 1

    # Everything the newest deck holds, so a card owned and never thrown still appears -
    # except a STANCE, which is not thrown at all. One sits on the bar at a time and is on
    # continuously, so it has no uses to count and listing it as never used is miscounting
    # rather than finding something. Reported separately instead.
    owned = estimate.DECKS[-1][1] if estimate.DECKS else {}
    stances = set(nm for nm, m in estimate.load_moves().items() if m.get("stance"))
    for nm in owned:
        if nm not in stances:
            used[nm]  # touch, so a zero-use card gets a row
    return used, owned, stances


PACK = os.path.join(estimate.ROOT, "data", "combat", "opponents.json")


def blockers(path=PACK):
    """What stands between each opponent and a planned fight, counted by cause.

    The list of things worth doing in-game was written by hand once, into the spec, and a
    hand-written list is true on the day it is written. This computes it, so it stays true
    as the corpus grows and so it cannot quietly describe a state the pack left behind.

    Four causes, and they are not interchangeable - they have different fixes and only one
    of them is answered by fighting the creature more:

        planned      nothing is missing
        no skill     no opening gain of ours against it was ever attributable. NOT a thin
                     measurement, no measurement. These are the swarming species, where
                     every logged fight has a third party in it.
        equalized    its combat skill is within a factor of two of ours, so every gain we
                     logged was pinned and returned our own weight back. Equalization
                     compares SKILLS, so no change of card escapes it - only a different
                     school, or a trained one.
        no ceiling   it survived everything we ever did, so its hitpoints have a floor and
                     no cap.

    Getting this wrong is cheap to do and expensive to act on: the matchup report once
    blamed equalization for all of them, which would have sent someone off to retrain a
    skill when what was needed was to catch one ant on its own.
    """
    try:
        with open(path, "r", encoding="utf8") as f:
            doc = json.load(f)
    except (OSError, ValueError):
        return {}
    out = defaultdict(list)
    for o in doc.get("opponents") or ():
        sk, hp = o.get("skill"), o.get("hitpoints")
        dw, eng = o.get("defence_weight"), o.get("engagements") or 0
        name = "%s (%d)" % (o.get("name"), eng)
        if not sk or sk.get("value") is None:
            # Three different problems, and lumping them sends someone off to do the
            # wrong thing. A creature we have never had a clean gain against needs a
            # solo fight; one whose gains CONTRADICT each other has been measured and
            # the measurements disagree, which more of the same will not settle.
            if dw and dw.get("contradictory"):
                out["contradictory"].append(name)
            elif eng < 5:
                out["barely fought"].append(name)
            else:
                out["no gains"].append(name)
        elif sk.get("equalized") or sk.get("disputed"):
            out["equalized"].append(name)
        elif not hp or hp.get("hi") is None:
            out["no ceiling"].append(name)
        else:
            out["planned"].append(name)
    return out


def report_todo():
    """Everything the corpus cannot answer without going and doing something, ranked."""
    b = blockers()
    if not b:
        return
    print("=" * 78)
    print("WHAT TO GO AND DO")
    print("=" * 78)
    print("  Computed from the pack, not written down, so it stays true as the corpus")
    print("  grows. Ranked by how many species each action unblocks.\n")

    jobs = []
    if b.get("no gains"):
        jobs.append((len(b["no gains"]),
                     "Fight ONE of these, alone (engagements in brackets)",
                     ["Fought plenty and measured nothing: no opening gain of ours against",
                      "them was ever attributable. That is not a thin measurement, it is no",
                      "measurement, and it is what a corpus of crowded fights looks like -",
                      "the gate that keeps openings attributable needs us alone with one of",
                      "them. More fights of the same shape add nothing; one clean solo",
                      "engagement is the first skill measurement the species has ever had."],
                     b["no gains"]))
    if b.get("contradictory"):
        jobs.append((len(b["contradictory"]),
                     "Work out why these disagree with themselves",
                     ["Measured, and the measurements do not intersect. Unlike everything",
                      "else here this is not answered by fighting them more - either the",
                      "creature varies in a way the model has no term for, or a move of ours",
                      "is being read wrong against it. It is a question for the estimator."],
                     b["contradictory"]))
    if b.get("barely fought"):
        jobs.append((len(b["barely fought"]),
                     "Simply fight these more",
                     ["Under five engagements each. Nothing subtle is wrong; there is just",
                      "not enough yet."],
                     b["barely fought"]))
    if b.get("no ceiling"):
        jobs.append((len(b["no ceiling"]),
                     "Kill one of these",
                     ["They survived everything we ever did, so the corpus has a floor on",
                      "their hitpoints and no cap. One kill settles it."],
                     b["no ceiling"]))
    if b.get("equalized"):
        jobs.append((len(b["equalized"]),
                     "Fight these from a different school",
                     ["Their combat skill is within a factor of two of ours, so every gain",
                      "we logged equalized and handed our own weight back. More fights will",
                      "not fix it. Equalization compares SKILLS, so no change of card",
                      "escapes it either - only the other school, or a trained one."],
                     b["equalized"]))

    # The mu experiment, which is not about any opponent and so is not in the pack.
    sole = LIVE[0] if LIVE else None
    if sole:
        fn_ = dict(HYPOTHESES)[sole]
        owned = estimate.DECKS[-1][1] if estimate.DECKS else {}
        have = owned.get("Take Aim")
        reach = [l for l in range(1, min(have or 0, estimate.MU_LEVELS) + 1)
                 if l not in estimate.MU_MEASURED] if have else []
        if reach:
            lvl = reach[0]
            mu = fn_(lvl)
            n0 = int(math.floor(takeaim_raw(mu, 0)))
            lo, hi = pins_mu(n0, 0)
            jobs.append((1,
                         "Use Take Aim at level %d, holding initiative" % lvl,
                         ["The card is already at level %d in the deck. One use pins mu to"
                          % have,
                          "%.3f-%.3f at zero initiative and tighter with more, which is the"
                          % (lo, hi),
                          "only live test of the surviving curve. It predicts %.3f." % mu],
                         []))

    for n, title, why, who in sorted(jobs, reverse=True):
        print("  [%d] %s" % (n, title))
        for line in why:
            print("      %s" % line)
        if who:
            print("      %s" % ", ".join(sorted(who)[:12]))
            if len(who) > 12:
                print("      ... and %d more" % (len(who) - 12))
        print()

    done = len(b.get("planned") or ())
    total = sum(len(v) for v in b.values())
    print("  %d of %d opponents can be planned against end to end.\n" % (done, total))


def separable_with(level, a, b, instrument, ips=range(0, 21), hyps=HYPOTHESES):
    """The lowest initiative at which this instrument splits these two, or None.

    Instrument-aware, and that is the whole point rather than a detail. At level 4 the two
    surviving curves differ by 1.8%, and Take Aim's base of 30 cannot resolve that - both
    land on 21 ticks. Dash's base of 80 gives 58 and 57. A report that asked only the card
    already in the deck would call the question unanswerable when another card answers it.
    """
    fns = dict(hyps)
    if (a not in fns) or (b not in fns):
        return None
    spec = INSTRUMENTS[instrument]
    if level > spec["max_level"]:
        return None
    for ip in ips:
        ra = raw_cooldown(spec["base"], fns[a](level), spec["ip_scale"], ip)
        rb = raw_cooldown(spec["base"], fns[b](level), spec["ip_scale"], ip)
        if (ra is None) or (rb is None):
            continue
        if int(math.floor(ra)) != int(math.floor(rb)):
            return ip
        if spec["ip_scale"] == 0:
            # No initiative term, so one reading is every reading.
            return None
    return None


def report_discrimination():
    print("=" * 78)
    print("WHICH FIGHT WOULD SETTLE SOMETHING")
    print("=" * 78)
    print("  Ranked by what the outcome would DECIDE, not by where the model is wrong.")
    print("  A residual says something is off across ten parameters at once; two")
    print("  hypotheses predicting different integers say which one is dead.\n")
    print("  A card can measure the deck weighting only if its COOLDOWN divides by it.")
    print("  Exactly two do, and a bigger base is a finer instrument:\n")
    owned = estimate.DECKS[-1][1] if estimate.DECKS else {}
    for nm, spec in INSTRUMENTS.items():
        print("      %-10s base %-5.0f initiative scale %-5.2f (deck level: %d)"
              % (nm, spec["base"], spec["ip_scale"], owned.get(nm, 0)))
    print()
    print("  The cooldown FLOORS, and it floors twice: the card's own cooldown is")
    print("  base over the weighting, floored to the integer the card displays, and")
    print("  initiative then scales that integer and floors again. Reading it as one")
    print("  round-half-up is what excluded the linear curve for a week.\n")

    names = [n for n, _f in HYPOTHESES]
    print("  what each card would report at 0 initiative, by level")
    for nm, spec in INSTRUMENTS.items():
        print("\n  %s (base %.0f)" % (nm, spec["base"]))
        print("      %-7s %s" % ("level", "  ".join("%-12s" % n for n in names)))
        for lvl in range(1, estimate.MU_LEVELS + 1):
            if lvl > spec["max_level"]:
                continue
            cells, ints = [], set()
            for n, fn in HYPOTHESES:
                mu = fn(lvl)
                r = raw_cooldown(spec["base"], mu, spec["ip_scale"], 0)
                if r is None:
                    cells.append("%-12s" % "-")
                else:
                    cells.append("%-12s" % ("%.3f/%d" % (mu, int(math.floor(r)))))
                    ints.add(int(math.floor(r)))
            note = ("SPLITS %s" % sorted(ints)) if len(ints) > 1 else "all agree"
            print("      %-7d %s  %s" % (lvl, "  ".join(cells), note))

    print("\n  Level 1 is the control. Every candidate is pinned to 1.0 there, so no")
    print("  observation at level 1 can separate anything - and if this ever reports")
    print("  level 1 as decisive, the instrument is broken and not the hypotheses.\n")

    measured = sorted(estimate.MU_MEASURED)
    print("  Already measured: %s" % (", ".join("level %d" % l for l in measured)
                                      if measured else "nothing"))
    print("  Still live: %s" % ", ".join(LIVE))
    for name in (n for n, _f in HYPOTHESES if n in EXCLUDED):
        print("      %-9s dead - %s" % (name, EXCLUDED[name]))
    print()

    if len(LIVE) > 1:
        report_separation(LIVE)
    else:
        report_falsification(LIVE)


def report_separation(live):
    """More than one curve is standing, so the job is to split them."""
    print("  %d candidates survive, so the job is to SEPARATE them.\n" % len(live))
    owned = estimate.DECKS[-1][1] if estimate.DECKS else {}
    found = False
    for lvl in range(1, estimate.MU_LEVELS + 1):
        if lvl in estimate.MU_MEASURED:
            continue
        for i, a in enumerate(live):
            for b in live[i + 1:]:
                hits = []
                for nm in INSTRUMENTS:
                    ip = separable_with(lvl, a, b, nm)
                    if ip is not None:
                        hits.append((nm, ip))
                if not hits:
                    print("  level %d: nothing in the deck separates %s from %s."
                          % (lvl, a, b))
                    continue
                found = True
                for nm, ip in hits:
                    spec = INSTRUMENTS[nm]
                    ra = raw_cooldown(spec["base"], dict(HYPOTHESES)[a](lvl),
                                      spec["ip_scale"], ip)
                    rb = raw_cooldown(spec["base"], dict(HYPOTHESES)[b](lvl),
                                      spec["ip_scale"], ip)
                    print("  level %d: %s vs %s - use %s%s. %s reads %d ticks, %s reads %d."
                          % (lvl, a, b, nm,
                             "" if spec["ip_scale"] == 0 else " at %d initiative" % ip,
                             a, int(math.floor(ra)), b, int(math.floor(rb))))
                    have = owned.get(nm, 0)
                    if have >= lvl:
                        print("           %s is already at level %d in the deck - one use"
                              " settles it." % (nm, have))
                    else:
                        print("           %s is at level %d and must be levelled to %d"
                              " first." % (nm, have, lvl))
    if not found:
        print("  Nothing left that any card in the deck can settle.")
    print()


def boost_experiment(levels=None):
    """Whether the deck weighting scales Opportunity Knocks, and what would show it.

    A DIFFERENT SHAPE of question from the cooldown ladder above, and worth keeping
    separate. There, two curves predict different integers for the same card and the game
    reports one of them. Here there is one curve and two places it might apply: Sim
    multiplies the boost by mu (Sim.java, `points * m.boostGreatest * m.mu`) and no
    reading supports that. The card multiplies the greatest standing opening and takes
    neither the cube root nor the falloff, so nothing else in the sheet constrains it.

    ANSWERED ON 2026-09-03, AND THE ANSWER IS YES. This was written when the card had
    never been thrown; fourteen uses later it has, all at level 2, and eleven of them
    uncensored by the ceiling. The multiplier on the standing opening comes out in
    [1.4394, 1.4516], which contains 0.4 * 1.125 = 1.45 and excludes 1.40 - so mu scales
    it, and by a margin far outside the display's rounding.

    One of those uses is worth more than the other thirteen: the card was thrown with
    NOTHING standing and opened nothing. Under the ordinary rule an empty opening is the
    easiest one to open and the gain would be at its largest. Only a share of what is
    already there gives zero, so the shape is settled too, not just the constant.

    The table below is kept because it is the record of what discriminated, and because
    the level-1 control has still never been run - both hypotheses predict the same
    number there, so a disagreement would say the card is not understood at all rather
    than anything about mu. That is now a cheap confirmation rather than the experiment.

    Returns a list of (level, standing, with_mu, without_mu) rows.
    """
    if levels is None:
        levels = (1, 2)
    out = []
    for level in levels:
        mu = estimate.mu_linear(level)
        for standing in (30, 40, 50, 60):
            out.append((level, standing, standing * BOOST * mu, standing * BOOST))
    return out


def report_boost():
    print("=" * 78)
    print("DOES THE DECK WEIGHTING SCALE OPPORTUNITY KNOCKS?")
    print("=" * 78)
    print("  SETTLED: it does. The card multiplies the greatest standing opening by %d%%,"
          % int(BOOST * 100))
    print("  taking neither the cube root nor the falloff, and mu scales that share.")
    try:
        uses, lo, hi = estimate.ok_boost()
    except Exception:
        uses, lo, hi = (), None, None
    if lo is not None:
        n = sum(1 for b, a, _l in uses if (b > 0) and (a < 100))
        print()
        print("  %d use(s), %d uncensored, giving a multiplier in [%.4f, %.4f]:"
              % (len(uses), n, lo, hi))
        print("      0.4 * mu at the linear curve's mu(2) = 1.125   %.4f   admitted"
              % 1.45)
        print("      0.4 flat, with mu not scaling it              %.4f   EXCLUDED"
              % 1.40)
        zeros = [a for b, a, _l in uses if b == 0]
        if zeros:
            print("  and one use against NOTHING standing, which opened %d - a share of what"
                  % zeros[0])
            print("  is there, not a number of points, which is the other half of the rule.")
    print()
    print("  %-7s %-9s %-12s %-12s %s"
          % ("level", "standing", "if mu scales", "if it does not", "gap"))
    for level, standing, a, b in boost_experiment():
        gap = a - b
        note = "  <- CONTROL: must agree" if abs(gap) < 1e-9 else ""
        print("  %-7d %-9d %-12.1f %-12.1f %+.1f%s" % (level, standing, a, b, gap, note))
    print()
    print("  The level-1 control has still never been run, and it is now cheap rather than")
    print("  necessary: both hypotheses predict the same number there, so a disagreement")
    print("  would say the card is not understood at all rather than anything about mu.")
    print()


def report_coverage(paths=None):
    counts, owned, stances = coverage(paths)
    if not counts:
        return
    print("=" * 78)
    print("WHAT HAS NEVER BEEN TRIED")
    print("=" * 78)
    print("  The counterweight. Above ranks questions the corpus can answer; this is")
    print("  where there is no question yet because there is no observation - and it is")
    print("  the guard against a loop that only ever fights the fights it already wins.\n")
    tot = sorted((sum(v.values()), mv, len(v)) for mv, v in counts.items())
    print("  %-28s %-6s %-6s %-8s %s" % ("card", "level", "uses", "species", ""))
    for n, mv, sp in tot:
        flag = "  <- NEVER USED" if n == 0 else ("  <- thin" if n < 5 else "")
        lvl = owned.get(mv)
        print("  %-28s %-6s %-6d %-8d%s"
              % (mv[:28], lvl if lvl is not None else "-", n, sp, flag))
    held = sorted(nm for nm in owned if nm in stances)
    if held:
        print()
        print("  Stances held, not thrown: %s." % ", ".join(held))
        print("  A stance is a passive - one on the bar, on continuously - so it has no")
        print("  uses to count, and a row saying otherwise would be miscounting.")
    blind = [mv for n, mv, _sp in tot if n == 0]
    if blind:
        print()
        print("  %d card%s in the deck %s never been thrown. Nothing the model says about"
              % (len(blind), "" if len(blind) == 1 else "s",
                 "has" if len(blind) == 1 else "have"))
        print("  %s rests on an observation, and a frontier that ranks %s last is"
              % ("it" if len(blind) == 1 else "them", "it" if len(blind) == 1 else "them"))
        print("  reporting the absence of data, not a finding about the card.")
    print()


def main(argv):
    report_todo()
    report_discrimination()
    report_boost()
    report_coverage(argv[1:] or None)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
