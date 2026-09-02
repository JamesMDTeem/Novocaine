#!/usr/bin/env python3
"""Checks for the estimators in estimate.py.

    python tools/combat/estimate_check.py

Each of these turns observations into a claim about an opponent, and each has a way of
being confidently wrong that no error message would reveal. The cases below are the ones
that actually went wrong, kept as checks so they cannot go wrong again quietly.

Exits 0 when every check passes, 1 otherwise.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402
from estimate import summarise_hp  # noqa: E402

failures = []


def check(what, got, want):
    ok = got == want
    print("  %-58s %-20s %s" % (what, repr(got)[:20], "ok" if ok else "WANT %r" % (want,)))
    if not ok:
        failures.append(what)


def near(what, got, want, tol):
    ok = abs(got - want) <= tol
    print("  %-58s %-20s %s"
          % (what, "%.2f" % got, "ok" if ok else "WANT %.2f +/- %.2f" % (want, tol)))
    if not ok:
        failures.append(what)


WIKI = lambda hp, armor=None: {"hp": {"value": hp}, "armor": {"value": armor}}


def hitpoints():
    print("hitpoints")
    # Auto-reaggro splits an engagement across files and the creature returns with the
    # health the last one left it, so the file holding the kill has only the final
    # instalment. The fox read 42 that way; its two files come to 126.
    hp = summarise_hp({7: 84 + 42}, {7}, {7: 42}, None)
    check("a kill sums every file the gob appears in", hp["observed_hi"], 126)

    # The overkill bound, which is the whole reason a kill is a CEILING and not a floor.
    # A kill at 126 whose last hit was 42 says the creature was somewhere in (84, 126].
    check("a kill puts a ceiling on it", hp["hi"], 126)
    check("and a floor one last hit below", hp["lo"], 84)

    # A survivor proves one was bigger. It never caps anything, and it must never lower
    # the floor - doing so put the boar at 64 on the strength of one that walked away
    # from 63 damage and probably had 450.
    hp = summarise_hp({1: 63}, set(), {}, None)
    check("a survivor has no ceiling", hp["hi"], None)
    check("and reports what it walked away from", hp["lo"], 64)
    hp = summarise_hp({1: 499, 2: 63}, {1}, {1: 256}, None)
    check("a survivor does not drag the floor down", hp["lo"], 243)
    check("the kill still caps it", hp["hi"], 499)

    # Individuals of a species differ - the wiki lists a base quality beside every one -
    # so several fights are an ENVELOPE, not an intersection. Two badgers at 190-210 and
    # 171-342 make a third anywhere in 171-342.
    hp = summarise_hp({1: 210, 2: 342}, {1, 2}, {1: 20, 2: 171}, None)
    check("two individuals give the envelope, not the overlap",
          (hp["lo"], hp["hi"]), (171, 342))

    print("\n  against the wiki baseline")
    # The wiki's figure is kept when our fights are consistent with it, and is included
    # in the range either way.
    hp = summarise_hp({1: 126}, {1}, {1: 42}, WIKI(110))
    check("a consistent wiki value sits inside the range",
          hp["lo"] <= 110 <= hp["hi"], True)
    check("and is reported as consistent", "consistent" in hp["verdict"], True)

    # Dead below stated: that individual was below the wiki's base quality.
    hp = summarise_hp({1: 210}, {1}, {1: 20}, WIKI(250))
    check("dying below the stated figure is flagged", "below its base quality"
          in hp["verdict"], True)
    check("and the stated figure still widens the range", hp["hi"], 250)

    # Alive above stated: that individual was above it.
    hp = summarise_hp({1: 400}, set(), {}, WIKI(250))
    check("surviving past the stated figure is flagged",
          "above its base quality" in hp["verdict"], True)

    # With no fights at all the wiki stands alone rather than the creature being unknown.
    hp = summarise_hp({}, set(), {}, WIKI(4000))
    check("a creature we have never fought still has a baseline",
          (hp["lo"], hp["hi"]), (4000, 4000))
    check("nothing at all is still nothing", summarise_hp({}, set(), {}, None), None)


def agility():
    print("\nagility, from integer cooldowns")
    # Our agility 81; a move of base 20 reported at 18 ticks. The multiplier is somewhere
    # in [17.5/20, 18.5/20], and the interval on the opponent follows.
    lo, hi, capped = estimate.agility_interval([(20, 18)], 81)
    check("an observation at the cap is reported as capped", capped, True)
    near("its lower bound is half our agility", lo, 40.5, 0.1)

    # Equal agility is the neutral point: a base 20 reported at 20.
    lo, hi, capped = estimate.agility_interval([(20, 20)], 81)
    check("a neutral cooldown is not capped", capped, False)
    check("and brackets our own agility", lo <= 81 <= hi, True)

    # Two observations intersect rather than average.
    one = estimate.agility_interval([(20, 19)], 81)
    two = estimate.agility_interval([(20, 19), (35, 33)], 81)
    check("a second observation only narrows", (two[0] >= one[0]) and (two[1] <= one[1]),
          True)

    # Observations from different opponents cannot be satisfied at once, and the empty
    # interval is the signal - this is how pooling every player into one bucket was
    # caught, since gfx/borka/body is the resource for all of them.
    mixed = estimate.agility_interval([(20, 18), (20, 22)], 81)
    check("irreconcilable observations give an empty interval", mixed[0] > mixed[1], True)

    check("no observations, no answer", estimate.agility_interval([], 81), None)


def defence():
    print("\ndefence weight, from an opening gain")
    # Knock Its Teeth Out, listed +20% Cornered, opened a bee swarm by 24 from nothing at
    # an attack weight of 58. The midpoint is 58/1.2^3.
    lo, hi = estimate.gain_interval(58, 24, 20, 0)
    near("the bee swarm's interval contains 33.6", (lo + hi) / 2, 33.6, 6.0)
    check("and brackets it", lo <= 33.6 <= hi, True)

    # A small gain into a nearly-closed opening constrains far less than a big one into a
    # fresh opening, and the interval has to show that rather than hide it.
    tight = estimate.gain_interval(111, 24, 20, 0)
    loose = estimate.gain_interval(111, 4, 10, 52)
    check("a small gain at a high standing opening is the weaker evidence",
          (loose[1] - loose[0]) > (tight[1] - tight[0]) * 5, True)


def deck_weighting():
    print("\ndeck weighting (mu)")
    # Wd_true = mu * Wd_measured, because the measurement divides an attack weight that
    # is itself proportional to mu. Against ONE opponent Wd_true is a single number, so
    # mu_b/mu_a = Wd_a/Wd_b - no cube root, and the opposite way up from how it first
    # went in. The earlier version took the cube root of the reciprocal, which compressed
    # every difference towards 1 and so looked healthiest exactly when it hid the most.
    near("two moves agreeing means equal weighting", estimate.mu_ratio(90.0, 90.0),
         1.0, 1e-9)
    near("the move recovering a SMALLER weight has the LARGER mu",
         estimate.mu_ratio(90.0, 60.0), 1.5, 1e-9)
    check("and the old cube-rooted form would have said 0.87 for that",
          round((60.0 / 90.0) ** (1 / 3.0), 2), 0.87)
    check("an impossible denominator constrains nothing", estimate.mu_ratio(90.0, 0.0),
          0.0)

    # mu is not one global multiplier - it scales whatever the move's headline quantity
    # is. An attack's attack weight, a maneuver's opening, a defensive card's block
    # weight. That decides the arithmetic: with mu in the attack weight the correction is
    # linear, with mu in the opening it is cubed, and confusing the two is 125% wrong at
    # mu 1.5. The sheet is unambiguous about which, so the check is that the two families
    # do not overlap.
    sheet = estimate.load_moves()
    # No move's OPENING is scaled by mu. An attack inflicts a flat "+10% Cornered", and
    # the moves that do scale with mu scale a REDUCTION instead - "Reduces: 20% * mu
    # Striking" on the defensive maneuvers. That is what makes the linear correction
    # right for everything this estimator measures.
    check("no move's openings are scaled by mu",
          any(o.get("mu") for m in sheet.values() for o in (m.get("openings") or [])),
          False)
    reducers = [m["name"] for m in sheet.values()
                if any(o.get("mu") for o in (m.get("reduces") or []))]
    check("the mu-scaled percentages are all reductions", len(reducers) > 0, True)
    check("and no attack is among them",
          any(sheet[n].get("attack_types") for n in reducers), False)
    # An attack carries mu on its attack-weight line instead.
    atk = sheet.get("Quick Barrage") or {}
    check("an attack's weight line carries mu",
          "µ" in (atk.get("attack_weight") or ""), True)

    # The wiki puts mu between 1.0 and 1.5, so a ratio outside 1/1.5 to 1.5 cannot be a
    # deck-level difference at all. That is what rules the boar's disagreement out: its
    # two moves imply 2.72, where every other opponent in the corpus sits within 0.82 to
    # 1.11.
    check("the wiki's range bounds what a level difference can explain",
          (round(estimate.MU_MIN, 3), estimate.MU_MAX), (0.667, 1.5))
    check("a boar-sized gap is outside it",
          estimate.MU_MIN <= 2.72 <= estimate.MU_MAX, False)
    # The linear hypothesis: levels 1 to 5 across 1.0 to 1.5. The cap is 5 for everything
    # except stances - a move showing a "max" of 1 in the deck dump has merely been
    # LEARNED once, which is not the same fact and is easy to misread as the ceiling.
    near("level 1 is 1.0, which Take Aim measures exactly",
         estimate.mu_at_level(1), 1.0, 1e-9)
    near("level 5 is the top of the wiki's range", estimate.mu_at_level(5), 1.5, 1e-9)
    near("level 3 sits midway", estimate.mu_at_level(3), 1.25, 1e-9)
    check("an unlearned card has no weighting", estimate.mu_at_level(0), None)
    check("and nothing goes past the cap",
          estimate.mu_at_level(9) == estimate.mu_at_level(5), True)
    # One anchor at 1.0 and one loose reading at level 3 cannot separate this from the
    # rival curve, and the checks should not pretend otherwise.
    rival = 1.0 + 0.1 * (3 - 1)
    check("the rival curve is still live at level 3",
          abs(estimate.mu_at_level(3) - rival) < 0.1, True)

    check("a level-1 spread is inside it",
          all(estimate.MU_MIN <= r <= estimate.MU_MAX
              for r in (0.82, 0.88, 0.96, 1.06, 1.11, 1.12)), True)


def armour():
    print("\narmour")
    # Every hit past the soft ramp: hard H with soft S subtracts exactly H+S, so the
    # split is not identifiable and must not be reported as though it were. These are the
    # boar's four logged hits.
    hits = [{"raw": 18, "shp": 3, "soaked": 15}, {"raw": 28, "shp": 13, "soaked": 15},
            {"raw": 35, "shp": 20, "soaked": 15}, {"raw": 42, "shp": 27, "soaked": 15}]
    arm = estimate.fit_armour(hits)
    check("the total is recovered exactly", arm["total"], (15, 15))
    check("the split is declined", arm["identified"], False)
    near("and it fits with no residual", arm["rms"], 0.0, 0.01)

    check("one hit is not enough to fit anything",
          estimate.fit_armour(hits[:1]), None)

    # The bear, whose stated armour is 65. The first version of this search ran a fixed
    # 0..60 grid and could not reach it: the true fit sits at 65 + 0 with no residual at
    # all, and it returned 60 + 5 with an error of 1.56 because that was the best it
    # could see. Nothing about the output said the answer had been out of range.
    bear = [{"raw": 1, "shp": 0, "soaked": 1}, {"raw": 40, "shp": 0, "soaked": 40},
            {"raw": 70, "shp": 5, "soaked": 65}, {"raw": 91, "shp": 26, "soaked": 65},
            {"raw": 153, "shp": 88, "soaked": 65}, {"raw": 427, "shp": 362, "soaked": 65}]
    arm = estimate.fit_armour(bear)
    check("a soak above any fixed grid is still found", arm["total"], (65, 65))
    near("and fits exactly", arm["rms"], 0.0, 0.01)

    # Tie tolerance. These numbers are integers, so a half-point residual is rounding and
    # not evidence. Judging ties tightly made the lynx's 33 + 2 beat 35 + 0 by half a
    # squared point across 28 hits - on one raw-35 hit reading 1 in one instance and 0 in
    # another - and reported an identified split that was really the rounding.
    lynx = [{"raw": 35, "shp": 1, "soaked": 34}, {"raw": 35, "shp": 0, "soaked": 35},
            {"raw": 27, "shp": 0, "soaked": 27}, {"raw": 21, "shp": 0, "soaked": 21},
            {"raw": 60, "shp": 25, "soaked": 35}]
    arm = estimate.fit_armour(lynx)
    check("a split within rounding is not claimed", arm["identified"], False)
    check("but the total still lands on the wiki's 35",
          arm["total"][0] <= 35 <= arm["total"][1], True)

    # Armour goes well past anything seen so far - a cachalot's is at least 150 - and the
    # search bound comes from the data rather than a constant, so it follows.
    heavy = [{"raw": 160, "shp": 10, "soaked": 150},
             {"raw": 300, "shp": 150, "soaked": 150},
             {"raw": 40, "shp": 0, "soaked": 40}]
    arm = estimate.fit_armour(heavy)
    check("a 150 soak is found without widening any constant", arm["total"], (150, 150))

    # Nothing got through, so absorption never saturated: the largest number seen is our
    # largest HIT, not the armour. A cachalot's 150 looks like 20 when all we ever landed
    # were twenties, and fitting a total there would be a confident number for a quantity
    # the data does not contain.
    blunt = [{"raw": 20, "shp": 0, "soaked": 20}, {"raw": 12, "shp": 0, "soaked": 12},
             {"raw": 18, "shp": 0, "soaked": 18}]
    arm = estimate.fit_armour(blunt)
    check("nothing penetrating means no ceiling", arm["total"], (20, None))
    check("and it is flagged as unpenetrated", arm["penetrated"], False)
    check("with no split invented", arm["hard"], None)


def buckets():
    print("\nbucketing")

    class E(object):
        def __init__(self, res, gob):
            self.res = res
            self.gob = gob

    check("animals group by species",
          estimate.bucket(E("gfx/kritter/badger/badger", 1)), "badger")
    check("the same species from a different gob is the same bucket",
          estimate.bucket(E("gfx/kritter/badger/badger", 2)), "badger")
    # Every player shares gfx/borka/body, so grouping on the resource would pool them.
    a = estimate.bucket(E("gfx/borka/body", 1))
    b = estimate.bucket(E("gfx/borka/body", 2))
    check("two players are two buckets", a != b, True)
    check("an unidentified opponent is kept apart by gob",
          estimate.bucket(E(None, 9)), "?#9")


def main():
    hitpoints()
    agility()
    defence()
    deck_weighting()
    armour()
    buckets()
    if failures:
        print("\n%d CHECK(S) FAILED" % len(failures))
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
