#!/usr/bin/env python3
"""Checks for the experiment chooser in experiment.py.

    python tools/combat/experiment_check.py

This file ranks fights by what their outcome would settle, which is a claim that can be
confidently wrong in a way no error message would show: an instrument that reports every
experiment as decisive is useless in exactly the same way as one that reports none, and
both run cleanly. The controls below are what separate the two.

The sharpest of them is the level-1 control. Every candidate curve is pinned to mu = 1.0
at level 1 by construction, so NO observation there can separate them. If this ever
reports level 1 as decisive, the fault is in the instrument and not in the hypotheses -
and that is the one failure mode which would otherwise look like progress.

That failure has already happened once, in the other direction. The cooldown was read as
a single round-half-up when the game floors twice, and every one of this file's checks
passed while the instrument was wrong - because they tested the tool against itself. The
checks that pin a VERDICT now name the readings behind it.

Exits 0 when every check passes, 1 otherwise.
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402
import experiment  # noqa: E402
import model  # noqa: E402

failures = []


def check(what, got, want):
    ok = got == want
    print("  %-62s %-16s %s" % (what, repr(got)[:16], "ok" if ok else "WANT %r" % (want,)))
    if not ok:
        failures.append(what)


def near(what, got, want, tol):
    ok = abs(got - want) <= tol
    print("  %-62s %-16s %s"
          % (what, "%.3f" % got, "ok" if ok else "WANT %.3f +/- %.3f" % (want, tol)))
    if not ok:
        failures.append(what)


def controls():
    """The two ways this instrument could be broken and still run."""
    print("\ncontrols - what must NOT be decisive")

    # Level 1 pins every curve to 1.0. Nothing measured there can separate anything, at
    # any initiative. A decisive verdict here means the instrument is reading noise.
    dec = [ip for ip in range(0, 21) if experiment.discriminate(1, ip)["decisive"]]
    check("level 1 is decisive at no initiative", dec, [])

    # A hypothesis against itself. If this ever separates, the comparison is not
    # comparing what it says it is.
    same = (("a", estimate.mu_curve), ("b", estimate.mu_curve))
    self_sep = [l for l in range(1, estimate.MU_LEVELS + 1)
                if experiment.separable(l, "a", "b", hyps=same) is not None]
    check("a curve is never separable from itself", self_sep, [])

    # And the opposite failure: an instrument that separates nothing is equally useless.
    # Level 3 must separate the leading curve from the linear one, or there is no
    # experiment left to recommend anywhere.
    check("level 3 separates linear from sqrt at 0 IP",
          experiment.separable(3, "linear", "sqrt"), 0)


def curves_that_meet():
    """Where two candidates coincide, no fight can tell them apart - at any initiative."""
    print("\ncurves that meet - inseparable however hard you try")

    # sqrt and linear both reach 1.5 at level 5 by construction. A report that counted
    # only how many integers the field splits into would call level 5 decisive - it does
    # split three ways - while settling nothing between the two curves that matter.
    check("linear and sqrt cannot be split at level 5",
          experiment.separable(5, "linear", "sqrt"), None)
    check("but they can at level 3", experiment.separable(3, "linear", "sqrt"), 0)

    # The live question, and the one that decides what to do in game. Linear and the
    # wiki's stated 1.4 differ by 1.8% at level 4, which Take Aim's base of 30 cannot
    # resolve - both land on 21 ticks - while Dash's base of 80 gives 58 and 57.
    check("Take Aim cannot separate linear from the wiki figure at level 4",
          experiment.separable_with(4, "linear", "wiki", "Take Aim"), None)
    check("  but Dash can, with no initiative at all",
          experiment.separable_with(4, "linear", "wiki", "Dash"), 0)
    check("  and nothing separates them at level 5, where both say 1.5",
          experiment.separable_with(5, "linear", "wiki", "Dash"), None)


def the_reversal():
    """The measured intervals, and which curves they admit.

    This is the check that would have caught the error, and did not exist. It names the
    readings rather than the verdict: 30 ticks at level 1, 26 at level 2, 24 at level 3,
    all at zero initiative, all straight out of the corpus. Whatever the inversion is
    doing, those three integers have to come back out of it.
    """
    print("\nthe measured intervals, from three readings")
    for level, cd, want_in, want_out in ((1, 30, 1.0, None),
                                         (2, 26, 1.125, 1.1676),
                                         (3, 24, 1.25, 1.2961)):
        lo, hi = experiment.pins_mu(cd, 0)
        check("level %d reads %d ticks -> linear's %.4f fits"
              % (level, cd, want_in), lo < want_in <= hi, True)
        if want_out is not None:
            check("  and the square-root curve's %.4f does not" % want_out,
                  lo < want_out <= hi, False)


def initiative_does_not_sharpen():
    """Initiative adds NOTHING to a deck-weighting measurement, and it used to look as if
    it added a great deal.

    Under the old single-round model the whole cooldown scaled with initiative while the
    slop stayed half a tick, so a reading at ten initiative looked three times tighter than
    one at zero, and the advice that came out of it was to build initiative before
    measuring. That advice was wrong.

    The weighting enters only through the card's own integer cooldown, C = floor(base/mu).
    Initiative scales C. So a reading at any initiative names the same C and therefore the
    same interval of mu, and eighteen readings up a ladder are one measurement confirmed
    eighteen times rather than eighteen measurements.

    They were still worth having - eighteen consistent readings are what proved the rule,
    since round-half-up cannot produce them - but they did not narrow the answer by one
    part in a thousand.

    What DOES narrow it is a bigger base. See INSTRUMENTS.
    """
    print("\ninitiative does not sharpen the reading")
    w0 = experiment.pins_mu(int(math.floor(experiment.takeaim_raw(estimate.mu_linear(3), 0))), 0)
    w10 = experiment.pins_mu(int(math.floor(experiment.takeaim_raw(estimate.mu_linear(3), 10))), 10)
    near("level 3 at 10 IP reads exactly as wide as at 0",
         w10[1] - w10[0], w0[1] - w0[0], 1e-9)
    check("  and to the same interval, not merely the same width",
          (abs(w10[0] - w0[0]) < 1e-9) and (abs(w10[1] - w0[1]) < 1e-9), True)

    # A bigger base is what actually resolves more finely.
    dash = experiment.pins_mu(
        int(math.floor(experiment.raw_cooldown(80.0, estimate.mu_linear(3), 0.0, 0))),
        0, base=80.0, ip_scale=0.0)
    check("Dash's base of 80 reads level 3 more tightly than Take Aim's 30",
          (dash[1] - dash[0]) < (w0[1] - w0[0]), True)
    print("      Take Aim %.4f-%.4f   Dash %.4f-%.4f"
          % (w0[0], w0[1], dash[0], dash[1]))


def fragility():
    """A separation can be real on paper and turn on a transcribed constant."""
    print("\nfragility - decisive is not the same as trustworthy")

    # invsqrt at level 5 predicts 23.503 ticks, which is three thousandths of a tick from
    # rounding the other way. It reports an integer, that integer differs from its rivals,
    # and the whole difference rests on the base cooldown being exactly 30.
    d = experiment.discriminate(5, 0)
    check("level 5 at 0 IP is decisive", d["decisive"], True)
    check("but is flagged fragile", d["robust"] < experiment.ROBUST, True)

    # Level 3 at 0 IP is decisive AND fragile, which is not a contradiction and is worth
    # seeing: linear predicts exactly 24.000 ticks, so a hair less than 1.25 would floor to
    # 23. The reading came back 24 and the curve is confirmed - but the margin is nothing,
    # and a report that called it comfortable would be overstating what one integer can
    # carry.
    d3 = experiment.discriminate(3, 0)
    check("level 3 at 0 IP is decisive", d3["decisive"], True)
    check("and is flagged fragile - linear lands exactly on the boundary",
          d3["robust"] < experiment.ROBUST, True)


def coverage():
    """The corpus's blind spots, and the join that hides them when it breaks."""
    print("\ncoverage - what has never been tried")

    used, owned, stances = experiment.coverage()
    if not owned:
        print("  (no deck dump on this machine - coverage not checked)")
        return

    thrown = [m for m, v in used.items() if sum(v.values()) > 0]
    never = [m for m, v in used.items() if sum(v.values()) == 0]

    # The regression guard. A log names a move by its resource path and a deck dump names
    # it by its display name; joining on either alone produces two disjoint lists that
    # both look complete, and the first run of this reported ALL 36 owned cards as never
    # thrown while separately listing ten resource paths with a thousand uses between
    # them. Either list being empty means the join has broken again.
    check("some owned card has been thrown", len(thrown) > 0, True)
    check("and the thrown cards carry deck names, not resource paths",
          any("/" in m for m in thrown), False)

    # And the finding itself, which is the reason the report exists: the corpus is
    # already collapsed onto a handful of cards. That is not the optimizer's doing - it
    # predates the optimizer - but it is exactly the shape a self-feeding loop would
    # produce, and it is why the loop needs this counterweight.
    check("most of the deck has never been thrown", len(never) > len(thrown), True)

    # A stance is not a card you throw - one sits on the bar and is on continuously - so
    # it must not appear in either list. Counting it as never used is miscounting, and it
    # is the kind of miscount that reads as a finding.
    check("the stances are identified", sorted(stances),
          ["Combat Meditation", "Shield Up"])
    check("  and none of them is counted as an unthrown card",
          [m for m in never if m in stances], [])


def both_report_branches():
    """The branch that is unreachable today still has to run.

    report_separation takes the live field as an argument rather than reading the module
    global, so it can be exercised here with a chosen field instead of only whichever
    curves happen to be standing today.
    """
    print("\nboth report branches run")
    import io as _io
    from contextlib import redirect_stdout
    for name, fn, live in (("separation", experiment.report_separation,
                            ("linear", "wiki")),):
        buf = _io.StringIO()
        try:
            with redirect_stdout(buf):
                fn(live)
            ok = len(buf.getvalue()) > 0
        except Exception as e:  # noqa: BLE001 - the point is that it must not raise
            ok = False
            print("  %s raised %r" % (name, e))
        check("report_%s produces a report" % name, ok, True)


def what_to_do():
    """The blocker diagnosis, which is a list someone acts on.

    Worth checking precisely because the cost of being wrong is not a wrong number on a
    page - it is a wasted evening. The matchup report once blamed equalization for every
    opponent it could not plan against, which would have sent someone off to retrain a
    skill when what was needed was to catch one ant on its own.
    """
    print("\nwhat to go and do")
    b = experiment.blockers()
    if not b:
        print("  (no pack on this machine - skipped)")
        return

    everyone = [n for v in b.values() for n in v]
    check("every opponent lands in exactly one bucket",
          len(everyone), len(set(everyone)))
    check("  and something is actually planned against", len(b.get("planned") or ()) > 0, True)

    # The distinction the whole report turns on. These three all read as "no skill" and
    # they need three different things: a solo fight, more fights, and a look at the
    # estimator. Collapsing them is the error this replaced.
    named = lambda k: set(n.split(" (")[0] for n in (b.get(k) or ()))
    check("a creature fought often with nothing measured is its own case",
          len(named("no gains")) > 0, True)
    check("  apart from one barely fought at all",
          named("no gains") & named("barely fought"), set())
    check("  and apart from one whose measurements contradict each other",
          named("no gains") & named("contradictory"), set())

    # BOAR'S CONTRADICTION IS GONE, and what removed it is worth recording because the
    # contradiction was carried as an open question from 2026-09-02 to 2026-09-03.
    #
    # It was 35 engagements and 7 defence-weight observations that would not intersect.
    # The cause was the OVERLAY test in attributed_gains vetoing on any move announcement
    # in a bracket - including our own, which plays over our own body every time we throw
    # a card. It threw away 450 brackets against 69 genuine third-party ones, and the
    # survivors were a biased remnant that did not agree with itself.
    #
    # Boar is now bounded by equalization instead: its skill is within a factor of two of
    # ours, which no further boar will fix either, but it is a different fact and it wants
    # a different answer.
    check("boar is no longer contradicting itself", "boar" in named("contradictory"),
          False)
    check("  it is bounded by equalization, which is a different problem",
          "boar" in named("equalized"), True)


def main():
    controls()
    the_reversal()
    initiative_does_not_sharpen()
    both_report_branches()
    curves_that_meet()
    fragility()
    coverage()
    what_to_do()
    if failures:
        print("\n%d CHECK(S) FAILED" % len(failures))
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
