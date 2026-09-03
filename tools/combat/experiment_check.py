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

Exits 0 when every check passes, 1 otherwise.
"""

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
    check("level 3 separates sqrt from linear at 0 IP",
          experiment.separable(3, "sqrt", "linear"), 0)


def curves_that_meet():
    """Where two candidates coincide, no fight can tell them apart - at any initiative."""
    print("\ncurves that meet - inseparable however hard you try")

    # sqrt and linear both reach 1.5 at level 5 by construction. A report that counted
    # only how many integers the field splits into would call level 5 decisive - it does
    # split three ways - while settling nothing between the two curves that matter.
    check("sqrt and linear cannot be split at level 5",
          experiment.separable(5, "sqrt", "linear"), None)
    check("but they can at level 3", experiment.separable(3, "sqrt", "linear"), 0)
    check("and at level 4", experiment.separable(4, "sqrt", "linear"), 0)


def initiative_sharpens():
    """More initiative is a better measurement, not just a slower one."""
    print("\ninitiative sharpens the reading")

    w0 = experiment.pins_mu(model._round_half_up(
        experiment.takeaim_raw(estimate.mu_curve(3), 0)), 0)
    w10 = experiment.pins_mu(model._round_half_up(
        experiment.takeaim_raw(estimate.mu_curve(3), 10)), 10)
    check("level 3 reads tighter at 10 IP than at 0",
          (w10[1] - w10[0]) < (w0[1] - w0[0]), True)

    # The whole cooldown scales by (1 + 0.2 * ip) and the rounding slop does not, so the
    # width should fall roughly as 1/(1 + 0.2 * ip) - a factor of three from 0 to 10.
    near("and by about the factor the scaling predicts",
         (w0[1] - w0[0]) / (w10[1] - w10[0]), 3.0, 0.35)

    # Against the hand arithmetic. mu_curve's own docstring says one further Take Aim
    # reading at level 3 pins mu "to about +/-0.02", worked out by a person from the two
    # formulas. This tool must land on the same place, or one of them is wrong.
    near("level 3 at 0 IP pins mu to about +/-0.02, as the docstring says",
         (w0[1] - w0[0]) / 2.0, 0.028, 0.005)


def fragility():
    """A separation can be real on paper and turn on a transcribed constant."""
    print("\nfragility - decisive is not the same as trustworthy")

    # invsqrt at level 5 predicts 23.503 ticks, which is three thousandths of a tick from
    # rounding the other way. It reports an integer, that integer differs from its rivals,
    # and the whole difference rests on the base cooldown being exactly 30.
    d = experiment.discriminate(5, 0)
    check("level 5 at 0 IP is decisive", d["decisive"], True)
    check("but is flagged fragile", d["robust"] < experiment.ROBUST, True)

    # Level 3 at 0 IP is the counter-example: decisive AND comfortable.
    d3 = experiment.discriminate(3, 0)
    check("level 3 at 0 IP is decisive", d3["decisive"], True)
    check("and is not fragile", d3["robust"] >= experiment.ROBUST, True)


def coverage():
    """The corpus's blind spots, and the join that hides them when it breaks."""
    print("\ncoverage - what has never been tried")

    used, owned = experiment.coverage()
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


def both_report_branches():
    """The branch that is unreachable today still has to run.

    With one candidate standing, report_separation is never taken - and an untested
    branch waiting for a new hypothesis to be added is precisely the code that has rotted
    by the time it is needed. Both take the live field as an argument so both can be
    exercised, here, with a field that has two names in it.
    """
    print("\nboth report branches run")
    import io as _io
    from contextlib import redirect_stdout
    for name, fn, live in (("separation", experiment.report_separation, ("sqrt", "linear")),
                           ("falsification", experiment.report_falsification, ("sqrt",))):
        buf = _io.StringIO()
        try:
            with redirect_stdout(buf):
                fn(live)
            ok = len(buf.getvalue()) > 0
        except Exception as e:  # noqa: BLE001 - the point is that it must not raise
            ok = False
            print("  %s raised %r" % (name, e))
        check("report_%s produces a report" % name, ok, True)


def main():
    controls()
    both_report_branches()
    curves_that_meet()
    initiative_sharpens()
    fragility()
    coverage()
    if failures:
        print("\n%d CHECK(S) FAILED" % len(failures))
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
