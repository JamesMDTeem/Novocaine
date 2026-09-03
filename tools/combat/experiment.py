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
    ("sqrt", estimate.mu_curve),
    ("linear", estimate.mu_linear),
    ("invsqrt", _mu_invsqrt),
    ("ratio", _mu_ratio),
)

EXCLUDED = {
    "linear": "level 2 measures 1.143-1.177; this wants 1.125",
    "invsqrt": "caps at 1.276 for level 5, against reduction floors of 1.49 and up",
    "ratio": "caps at 1.333 for level 5, against the same floors",
}

LIVE = tuple(n for n, _f in HYPOTHESES if n not in EXCLUDED)


def pins_mu(observed_ticks, ip):
    """The interval one observed cooldown pins mu to, at this initiative.

    The whole point of a falsification test rather than a discrimination one. With a
    single surviving curve there is nothing left to separate it FROM, so an experiment is
    worth running to the extent it could prove the survivor wrong - and that is exactly
    how narrowly one integer reading constrains mu. A reading that admits 1.28 to 1.33
    can refute a curve claiming 1.25; one that admits 1.0 to 1.5 can refute nothing.

    An observed N means the unrounded value was in [N - 0.5, N + 0.5), same as
    estimate.mu_from_takeaim, which this deliberately mirrors rather than reimplements.
    """
    return estimate.mu_from_takeaim(observed_ticks, ip)


def takeaim_raw(mu, ip):
    """The unrounded cooldown Take Aim would report at this mu and initiative.

    Take Aim is a maneuver, so no agility term enters - which is the whole reason it is
    the instrument for mu. An attack's cooldown carries a factor that depends on the
    opponent, and that would have to be known before mu could be read out of it."""
    if not mu or mu <= 0:
        return None
    return (estimate.TAKE_AIM_BASE / mu) * (1.0 + (estimate.TAKE_AIM_IP_SCALE * ip))


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
        splits[model._round_half_up(r)].append(name)

    # Distance to the nearest rounding boundary, which is where a prediction is fragile.
    robust = min(abs((r % 1.0) - 0.5) for _mu, r in raw.values())

    margin = None
    for a, (_ma, ra) in raw.items():
        for b, (_mb, rb) in raw.items():
            if a >= b:
                continue
            if model._round_half_up(ra) == model._round_half_up(rb):
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
        if model._round_half_up(ra) != model._round_half_up(rb):
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

    # Everything the newest deck holds, so a card owned and never thrown still appears.
    owned = estimate.DECKS[-1][1] if estimate.DECKS else {}
    for nm in owned:
        used[nm]  # touch, so a zero-use card gets a row
    return used, owned


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
            n0 = model._round_half_up(takeaim_raw(mu, 0))
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


def report_discrimination():
    print("=" * 78)
    print("WHICH FIGHT WOULD SETTLE SOMETHING")
    print("=" * 78)
    print("  Ranked by what the outcome would DECIDE, not by where the model is wrong.")
    print("  A residual says something is off across ten parameters at once; two")
    print("  hypotheses predicting different integers say which one is dead.\n")
    print("  Take Aim's cooldown divides by mu and carries no agility term, so it reads")
    print("  mu directly. The server sends whole ticks, so candidates are separable")
    print("  exactly when they round apart - one observation settles it, or none do.\n")

    print("  %-7s %-11s %-11s %-11s %-11s %s"
          % ("level", "sqrt", "linear", "invsqrt", "ratio", "at 0 IP"))
    for lvl in range(1, estimate.MU_LEVELS + 1):
        d = discriminate(lvl, 0)
        if d is None:
            continue
        cells = []
        for name, _fn in HYPOTHESES:
            if name in d["raw"]:
                mu, r = d["raw"][name]
                cells.append("%.3f/%d" % (mu, model._round_half_up(r)))
            else:
                cells.append("-")
        verdict = ("SPLITS %s" % sorted(d["splits"])) if d["decisive"] else "all agree"
        print("  %-7s %-11s %-11s %-11s %-11s %s"
              % (lvl, cells[0], cells[1], cells[2], cells[3], verdict))

    print("\n  Level 1 is the control. Every candidate is pinned to mu = 1.0 there, so no")
    print("  observation at level 1 can separate them - and if this ever reports level 1")
    print("  as decisive, the instrument is broken rather than the hypotheses.\n")

    measured = sorted(estimate.MU_MEASURED)
    print("\n  Already measured: %s" % (", ".join("level %d" % l for l in measured)
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
    for lvl in range(1, estimate.MU_LEVELS + 1):
        if lvl in estimate.MU_MEASURED:
            continue
        found = False
        for i, a in enumerate(live):
            for b in live[i + 1:]:
                ip = separable(lvl, a, b)
                if ip is None:
                    print("  level %d: %s and %s are INSEPARABLE - the curves meet here,"
                          % (lvl, a, b))
                    print("           so no initiative splits them and no fight will.")
                else:
                    print("  level %d: %s vs %s splits from %d IP" % (lvl, a, b, ip))
                found = True
        if not found:
            print("  level %d: nothing to separate." % lvl)
    print()


def report_falsification(live):
    """One curve is standing, so the job is to try to kill it.

    A different question with a different answer, and conflating the two is how a model
    ends up confirming itself. With rivals in the field an experiment is good when it
    splits them. With one survivor there is nothing to split, and the experiment is good
    when a single reading pins mu TIGHTLY enough that the survivor could have been wrong
    and shown to be - which is what the interval width below measures. A test that admits
    1.0 to 1.5 cannot refute anything and is not worth the walk.
    """
    sole = live[0]
    fn = dict(HYPOTHESES)[sole]
    print("  Only %s survives, so there is nothing left to separate. The job is now to" % sole)
    print("  try to FALSIFY it, and an experiment is worth running exactly to the extent")
    print("  that one reading could have come back refuting it.\n")
    print("  %-7s %-8s %-6s %-8s %-16s %s"
          % ("level", "predicts", "IP", "reads", "which pins mu to", "width"))
    for lvl in range(1, estimate.MU_LEVELS + 1):
        if lvl in estimate.MU_MEASURED:
            continue
        mu = fn(lvl)
        if not mu:
            continue
        best = None
        for ip in range(0, 11):
            r = takeaim_raw(mu, ip)
            n = model._round_half_up(r)
            lo, hi = pins_mu(n, ip)
            row = (hi - lo, ip, n, lo, hi, abs((r % 1.0) - 0.5))
            if (best is None) or (row[0] < best[0]):
                best = row
            if ip == 0:
                print("  %-7d %-8.3f %-6d %-8d %-16s %.3f"
                      % (lvl, mu, ip, n, "%.3f - %.3f" % (lo, hi), hi - lo))
        if best and best[1] != 0:
            w, ip, n, lo, hi, rob = best
            print("  %-7s %-8s %-6d %-8d %-16s %.3f   <- tightest"
                  % ("", "", ip, n, "%.3f - %.3f" % (lo, hi), w))
            if rob < ROBUST:
                print("          FRAGILE: that prediction sits %.2f ticks from a rounding"
                      % rob)
                print("          boundary, so which integer comes back turns on the base")
                print("          cooldown having been transcribed exactly right.")
    print()
    # Whether the recommended experiment can be run TODAY, which is the difference
    # between a ranked list and an instruction.
    owned = estimate.DECKS[-1][1] if estimate.DECKS else {}
    have = owned.get("Take Aim")
    if have:
        reach = [l for l in range(1, min(have, estimate.MU_LEVELS) + 1)
                 if l not in estimate.MU_MEASURED]
        print("  Take Aim stands at level %d in the current deck." % have)
        if reach:
            print("  So level%s %s %s reachable now - the card is already there, and the"
                  % ("" if len(reach) == 1 else "s",
                     ", ".join(str(l) for l in reach),
                     "is" if len(reach) == 1 else "are"))
            print("  measurement is one use of it at initiative.")
        else:
            print("  Every level it can reach is already measured; the rest needs the")
            print("  card levelled further.")
        print()
    print("  Initiative tightens every one of these, because the cooldown scales by")
    print("  (1 + 0.2 * ip) while the half-tick of rounding slop does not. Building")
    print("  initiative before the measurement is worth more than repeating it at zero -")
    print("  which is the same conclusion the optimizer reached about opening a fight,")
    print("  arrived at from a different direction.\n")


def report_coverage(paths=None):
    counts, owned = coverage(paths)
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
    report_coverage(argv[1:] or None)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
