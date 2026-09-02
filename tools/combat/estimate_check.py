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


def hitpoints():
    print("hitpoints")
    # The bug this exists for. A fight interrupted by auto-reaggro continues in a fresh
    # log with the creature's health where the last one left it, so the file containing
    # the kill holds only the last instalment. The fox really did read 42 this way, when
    # its two files together come to 126.
    hp = estimate.summarise_hp({7: 84 + 42}, {7})
    check("a kill sums every file the gob appears in", hp["lo"], 126)
    hp = estimate.summarise_hp({7: 42}, {7})
    check("one file alone would have said 42", hp["lo"], 42)

    # Two of the same species are two observations, not one range over one creature.
    hp = estimate.summarise_hp({1: 210, 2: 342}, {1, 2})
    check("two kills give a range", (hp["lo"], hp["hi"]), (210, 342))

    # A survivor bounds from below only, and must not be averaged in with a kill.
    hp = estimate.summarise_hp({1: 500}, set())
    check("a survivor has no upper bound", hp["hi"], None)
    check("and reports the largest seen", hp["lo"], 500)
    hp = estimate.summarise_hp({1: 100, 2: 50}, {1})
    check("a kill is preferred over a survivor", (hp["lo"], hp["hi"]), (100, 100))

    check("nothing observed is not zero", estimate.summarise_hp({}, set()), None)
    check("zero damage is not an observation", estimate.summarise_hp({1: 0}, {1}), None)


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
    armour()
    buckets()
    if failures:
        print("\n%d CHECK(S) FAILED" % len(failures))
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
