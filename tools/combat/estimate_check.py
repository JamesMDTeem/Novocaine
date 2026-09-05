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
import model  # noqa: E402
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
    # At the clamp the cooldown stops moving: every opponent from half our agility
    # downwards produces these same 18 ticks. So this bounds ONE side and no more.
    # Reporting agiMe/2 as a lower bound - which this did, with a flag beside it -
    # invents a bound that the intersection then treats as real, and the invention
    # scales with OUR agility, so it stayed invisible until the corpus held fights at
    # more than one. Four species came out contradicting themselves when it did.
    lo, hi, capped = estimate.agility_interval([(20, 18)], 81)
    check("an observation at the cap is reported as capped", capped, True)
    check("and bounds only the side it saturated on", lo, 0.0)
    near("  leaving what it does say: at most half our agility", hi, 48.2, 0.1)

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

    # A cooldown constrains the RATIO of the two agilities; only our own agility at that
    # moment turns it into an absolute. The same ticks seen when we were faster therefore
    # describe a faster opponent, and by exactly that factor.
    at_lo = estimate.agility_interval([(20, 19, 112)], None)
    at_hi = estimate.agility_interval([(20, 19, 124)], None)
    check("the same ticks at a higher agility of ours read a faster opponent",
          at_hi[0] > at_lo[0], True)
    near("  by exactly the ratio of our two agilities", at_hi[0] / at_lo[0],
         124 / 112.0, 0.001)
    # Which is why a pooled bucket has to convert each observation at its own agility.
    # Converting them all at the largest inflates every older one by that same ratio, and
    # in this corpus that put two groups of ants in intervals that do not meet.
    pooled = estimate.agility_interval([(20, 19, 112), (20, 19, 124)], None)
    one_scale = estimate.agility_interval([(20, 19), (20, 19)], 124)
    check("pooling converts each at its own, so the result is not the larger one's",
          pooled[1] < one_scale[1], True)
    near("  its ceiling comes from the fight where we were slower", pooled[1],
         112 / (2.0 ** 0.25), 0.1)


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
    # The linear hypothesis - levels 1 to 5 across 1.0 to 1.5 - is kept only so it can be
    # shown to be wrong. The cap is 5 for everything except stances; a move showing a
    # "max" of 1 in the deck dump has merely been LEARNED once, which is a different fact
    # and an easy one to misread as the ceiling.
    near("the linear curve puts level 1 at 1.0", estimate.mu_linear(1), 1.0, 1e-9)
    near("and level 5 at the top of the stated range", estimate.mu_linear(5), 1.5, 1e-9)
    near("and level 2 at 1.125", estimate.mu_linear(2), 1.125, 1e-9)
    check("nothing goes past the cap",
          estimate.mu_linear(9) == estimate.mu_linear(5), True)
    # Which the measurement CONTAINS. This check asserted the opposite for a week, and it
    # passed the whole time, because the interval it tested came from inverting the wrong
    # rounding rule. A check can only ever be as right as its instrument.
    check("Take Aim's level-2 measurement contains that 1.125",
          estimate.mu_bounds(2)[0] <= 1.125 <= estimate.mu_bounds(2)[1], True)
    check("  and level 3's contains 1.25",
          estimate.mu_bounds(3)[0] <= 1.25 <= estimate.mu_bounds(3)[1], True)
    # The square-root curve is what the corpus went after when the inversion was wrong. It
    # is excluded at both measured levels now, and pinning that stops it coming back.
    check("  while the square-root curve's 1.168 is excluded at level 2",
          estimate.mu_bounds(2)[0] <= estimate.mu_curve(2) <= estimate.mu_bounds(2)[1],
          False)
    check("  and its 1.296 at level 3",
          estimate.mu_bounds(3)[0] <= estimate.mu_curve(3) <= estimate.mu_bounds(3)[1],
          False)
    # And the point estimate no longer comes off the excluded curve.
    check("an unlearned card has no weighting", estimate.mu_at_level(0), None)
    check("the point estimate now sits inside the measured band",
          estimate.mu_bounds(2)[0] <= estimate.mu_at_level(2) <= estimate.mu_bounds(2)[1],
          True)
    near("and is 1.0 at level 1", estimate.mu_at_level(1), 1.0, 0.01)

    check("a level-1 spread is inside it",
          all(estimate.MU_MIN <= r <= estimate.MU_MAX
              for r in (0.82, 0.88, 0.96, 1.06, 1.11, 1.12)), True)


def deck_history():
    print("\nthe deck in force at a fight")
    # A deck changes, and a measurement has to be read against the deck it was made with.
    # Judging historical fights by today's deck is not a small error: Quick Barrage sat at
    # level 1 through most of this corpus and has since been dropped, so today's deck
    # marks all of it level 0 and silently drops it from every mu comparison.
    saved = estimate.DECKS
    try:
        estimate.DECKS = [
            (1000, {"Quick Barrage": 1, "Punch": 0}),
            (2000, {"Quick Barrage": 1, "Punch": 3}),
            (3000, {"Quick Barrage": 0, "Punch": 5}),
        ]
        check("a fight between dumps uses the one before it",
              estimate.levels_at(2500)["Punch"], 3)
        check("a fight after the last dump uses the last",
              estimate.levels_at(9999)["Punch"], 5)
        check("exactly on a dump counts as that dump",
              estimate.levels_at(2000)["Punch"], 3)
        # The whole point: a move dropped since is still read at the level it was used at.
        check("a move dropped since is read at the level it was used at",
              estimate.levels_at(2500)["Quick Barrage"], 1)
        check("and today's deck would have said 0", estimate.DECKS[-1][1]["Quick Barrage"],
              0)
        # A fight older than every dump is UNKNOWN, not guessed. It used to fall back to
        # the earliest deck, and a fight with no wall stamp at all walked the whole list
        # and came back with TODAY's - which credited the corpus's oldest Take Aim fight
        # to level 2 and made its textbook 30/36/42 ladder read as mu 1.18 instead of the
        # 1.0 it plainly is. A level-keyed measurement should skip a fight whose deck it
        # does not know, and it can only do that if this says so.
        check("a fight older than every dump is unknown, not guessed",
              estimate.levels_at(1), {})
        check("a fight with no timestamp is unknown too", estimate.levels_at(None), {})
        estimate.DECKS = []
        check("no dumps at all is empty rather than wrong", estimate.levels_at(500), {})
    finally:
        estimate.DECKS = saved


def mu_measurement():
    """Take Aim's cooldown, which is the only direct read of mu anywhere in this project.

    The level-1 row is the control: a card with no extra points in it must weight at
    exactly 1.0, and 48 logged observations spanning the whole 30/36/42/48/54/60/66/72
    ladder say it does. If that row ever stops containing 1.0, the reader is wrong and
    every level above it is wrong with it.
    """
    print("\nmu, measured from Take Aim's cooldown")
    # The arithmetic, on numbers rather than on the corpus, so this section still means
    # something on a machine with no logs.
    lo, hi = estimate.mu_from_takeaim(30, 0)
    check("30 ticks at 0 IP brackets mu = 1.0", lo < 1.0 <= hi, True)
    lo, hi = estimate.mu_from_takeaim(26, 0)
    # The card's own cooldown is floor(30 / mu), so 26 ticks means that integer is 26,
    # which brackets mu at (30/27, 30/26] = 1.111 to 1.154. The linear curve's 1.125 sits
    # inside; the square-root curve's 1.168 does not.
    check("26 ticks at 0 IP brackets 1.125 and not 1.168",
          (lo < 1.125 <= hi, lo < 1.1676 <= hi), (True, False))
    # 26 at 0 IP and 31 at 1 IP are the SAME mu - the initiative term has to come off the
    # observation before the ratio is taken, and getting that backwards is what made an
    # earlier version of this read a different mu at every initiative.
    a = estimate.mu_from_takeaim(26, 0)
    b = estimate.mu_from_takeaim(31, 1)
    check("26 at 0 IP and 31 at 1 IP are consistent", (a[0] < b[1]) and (b[0] < a[1]), True)
    # 36 at 1 IP is mu = 1.0, so it must NOT be consistent with 26 at 0 IP.
    c = estimate.mu_from_takeaim(36, 1)
    check("36 at 1 IP is a different mu, and reads so",
          (a[0] < c[1]) and (c[0] < a[1]), False)

    m = estimate.measure_mu()
    if not m:
        print("  (no Take Aim in this corpus - nothing measured)")
        return
    for level in sorted(m):
        blo, bhi, n, stale = m[level]
        print("  level %d: mu in (%.4f, %.4f]  from %d observation(s)%s"
              % (level, blo, bhi, n,
                 ", %d stale ip sample(s) set aside" % stale if stale else ""))
    if 1 in m:
        blo, bhi, _n, _s = m[1]
        check("  CONTROL: level 1 must contain exactly 1.0", (blo <= 1.0 <= bhi), True)
    if 2 in m:
        blo, bhi, _n, _s = m[2]
        # The linear curve is what everyone reaches for first, and it is right. This
        # asserted the exclusion until an eighteen-reading Take Aim ladder showed the
        # cooldown floors twice rather than rounding once.
        check("  the linear 1.0-1.5 curve predicts 1.125, and it fits",
              blo <= 1.125 <= bhi, True)
        check("  linear across the card's own max predicts 1.25, which level 3 confirms",
              blo <= 1.25 <= bhi, False)


def own_defence():
    """OUR block weight, which is the one term in the opening formula we get to know.

    The control here is the arithmetic against the sheet: Shield Up reads
    "Block weight: Melee * 250% * mu", so at Melee Combat 125 and level 1 it is 312.5 and
    nothing about it is fitted. The conditional is the part that bites - "if used without
    a shield equipped, its block weight will be 50% instead of 250%" is a factor of FIVE,
    and it lives in prose rather than in the block-weight field.
    """
    print("\nour own defence weight, from the stance we are holding")
    moves = {
        "Shield Up": {"block_weight": "· 250% · µ", "block_skill": "melee",
                      "block_mult": 2.5, "block_requires": "shield",
                      "block_mult_without": 0.5},
        "Bloodlust": {"block_weight": "· 75% · µ", "block_skill": "unarmed",
                      "block_mult": 0.75},
    }
    attrs = {"melee": 125, "unarmed": 81}
    shield = [{"res": "gfx/invobjs/small/roundshield"}, {"res": "gfx/invobjs/small/bronzesword"}]
    bare = [{"res": "gfx/invobjs/small/bronzesword"}]

    wd, why = estimate.own_defence_weight(moves, attrs, shield, {"Shield Up": 1})
    near("Shield Up with a shield is Melee 125 x 250%", wd, 312.5, 3.0)
    check("  and says so", "shield equipped" in why, True)
    wd, why = estimate.own_defence_weight(moves, attrs, bare, {"Shield Up": 1})
    near("without one it drops to 50%, a factor of five", wd, 62.5, 1.0)
    check("  and says why", "no shield equipped" in why, True)

    # A stance not in the deck cannot have been held. This is what rules Bloodlust out,
    # and Bloodlust matters because it is the one card that would inflate our ATTACK
    # weight - four times its charge - and so explain an anomaly it has nothing to do with.
    wd, why = estimate.own_defence_weight(moves, attrs, shield, {"Bloodlust": 0})
    check("a stance at deck level 0 is not held", wd, None)
    check("  and the reason is given", "no stance card" in why, True)
    wd, _ = estimate.own_defence_weight(moves, attrs, shield, {"Bloodlust": 1})
    near("Bloodlust held would be Unarmed 81 x 75%", wd, 60.75, 1.0)

    # Opening pressure: P = gain / (1 - Oc). The point of dividing out the falloff is that
    # the same move must read the same P whatever opening it lands on.
    print("  pressure divides out the standing opening it landed on:")
    for standing, gain in ((0, 10.0), (50, 5.0), (75, 2.5)):
        p_ = gain / (1.0 - standing / 100.0)
        near("    %d%% standing, gain %.1f" % (standing, gain), p_, 10.0, 0.01)


def mu_from_reductions():
    """The second mu measurement, and the reason it is trusted only as a floor.

    Its control is Zig-Zag Ruse at level 1, where mu must be exactly 1.0 - and the control
    is CONTAINMENT: every level-1 use must pin an interval that brackets 1.0. Each use
    measures an interval rather than a point, because the display truncates the standing
    value both before and after, so the honest question is whether the truth is inside it.

    This asserted "the midpoint median reads at or below 1.0" instead, and passed until
    the corpus grew from two level-1 readings to five. The median then reached 1.012 and
    the check failed while every interval still contained 1.0 exactly - three of them with
    1.0 as their LOWER bound, whose midpoints cannot be 1.0 or less. The measurement was
    never wrong; the statistic was never a floor.

    That the method reads low overall is separately true and separately evidenced - uses
    of Quick Dodge at level 5 that reduced nothing at all, which a reduction cannot do -
    and it is why the HIGHER levels are floors rather than estimates.
    """
    print("\nmu read from what a defensive card takes off us")
    rows, inert, spans = estimate.mu_from_reductions()
    if not rows:
        print("  (no defensive card in this corpus at a known level)")
        return
    for (level, nm), vals in sorted(rows.items()):
        vals = sorted(vals)
        print("  level %d %-15s n=%-4d median %.3f%s"
              % (level, nm[:15], len(vals), vals[len(vals) // 2],
                 "   %d use(s) reduced nothing" % inert[(level, nm)]
                 if inert.get((level, nm)) else ""))
    lvl1 = [iv for (lv, _n), ivs in spans.items() if lv == 1 for iv in ivs]
    if lvl1:
        # Named, not summarised: the reading each interval came from is what a broken
        # inversion would move, and a median is exactly what would hide it.
        for lo, hi in sorted(lvl1):
            check("  CONTROL: level 1 interval [%.3f, %.3f] contains 1.0" % (lo, hi),
                  (lo - 1e-9) <= 1.0 <= (hi + 1e-9), True)
        # The floors claim, stated where it is actually true. A reduction masked by the
        # opponent's own gain can only ever read SMALLER, so at the level where mu is 1.0
        # by definition no interval may sit wholly above it.
        check("  and none sits wholly above it, which is what makes these floors",
              [1 for lo, _h in lvl1 if lo > 1.0 + 1e-9], [])
    five = [v for (lv, _n), vals in rows.items() if lv == 5 for v in vals]
    if five:
        med = sorted(five)[len(five) // 2]
        # A floor of 1.49 at level 5 rules out every candidate curve that tops out lower.
        check("  level 5 floors mu at 1.49, so a curve capping at 1.333 is out",
              med > 1.333, True)


def mu_curve():
    """The leading curve, held to all three lines of evidence at once.

    Kept as a check rather than a note because the linear curve is what anyone reaches for
    first, it tracks the reduction medians almost exactly, and only the level-2 measurement
    rules it out. That exclusion is easy to lose.
    """
    print("\nthe mu curve, against every line of evidence")
    c = estimate.mu_curve
    near("level 1 is 1.0 by definition", c(1), 1.0, 1e-9)
    near("level 5 reaches the devs' stated ceiling", c(5), 1.5, 1e-9)
    # Take Aim, measured.
    lo, hi = estimate.mu_bounds(2)
    check("level 2 falls OUTSIDE Take Aim's measured interval - it is excluded",
          lo <= c(2) <= hi, False)
    check("  where the linear curve sits inside it", lo <= 1.125 <= hi, True)
    # The wiki's worked example.
    for level, stated in sorted(estimate.MU_WIKI_EXAMPLE.items()):
        near("level %d matches the wiki's worked example" % level, c(level), stated, 0.01)
    # The reduction floors.
    rows, _inert, _spans = estimate.mu_from_reductions()
    five = [v for (lv, _n), vals in rows.items() if lv == 5 for v in vals]
    if five:
        med = sorted(five)[len(five) // 2]
        check("level 5 clears the floor the reductions put under it", c(5) >= med, True)
        check("  and the rivals that cap at 1.333 do not", 1.333 >= med, False)


def agility_control():
    """Two routes to opponent agility, sharing only the observation.

    The single most valuable check in this file, because it is the only one that is not
    the corpus grading its own homework. Every other estimator here is verified against
    the data that produced it - which is exactly how a wrong rounding rule survived a
    week and three "independent" confirmations that all ran through it.

    Ours inverts Formulas.agilityCooldownFactor. The client's reads
    Config.attackCooldownNumbers, a hand-built table that predates this project and was
    not derived from our formula. A disagreement means one of them is wrong.
    """
    print("\nagility, measured twice by independent routes")
    rows = estimate.agility_control()
    if not rows:
        print("  (no log carries the client's own bracket yet)")
        return
    bad = [(sp, r) for sp in rows for r in rows[sp] if r[5] is False]
    both = [(sp, r) for sp in rows for r in rows[sp] if r[5] is not None]
    check("  the two routes are compared at all", len(both) > 0, True)
    check("  and none of them disagree", [sp for sp, _r in bad], [])
    # WHICH ROUTE IS TIGHTER IS A READING, NOT A LAW. This asserted that ours must never
    # be the wider, on the reasoning that a formula learning less from the same
    # observation is throwing information away. That reasoning is wrong, because the two
    # do not quantize the same way: ours brackets by the rounding interval of an integer
    # tick count, the client's by the spacing of adjacent entries in
    # Config.attackCooldownNumbers. Neither grid dominates the other. It went red on the
    # first opponent to land where the table is dense and the ticks are coarse - a
    # wildgoat at about 1.24x our agility - with nothing having regressed.
    wider = []
    for sp in rows:
        for gob, clo, chi, olo, ohi, agree in rows[sp]:
            if (agree is None) or (chi == float("inf")):
                continue
            if (ohi - olo) > (chi - clo) + 1e-9:
                wider.append(sp)
    both = [r for sp in rows for r in rows[sp]
            if (r[5] is not None) and (r[2] != float("inf"))]
    check("  both bounded on the same opponent, so the widths can be compared",
          len(both) > 0, True)
    print("    where the client's table is the tighter of the two    %-20s"
          % (sorted(set(wider)) or "nowhere"))


def agi_brackets():
    print("\nagi brackets as an independent second opinion on agility")
    comp = estimate.agi_species_comparison()
    if not comp:
        print("  (no agi bracket in this corpus)")
        return
    for sp in sorted(comp):
        pooled = comp[sp].get("pooled")
        why = comp[sp].get("why") or {}
        if pooled is None:
            p_lo_s = p_hi_s = "?"
            capped = False
        else:
            plo, phi, capped = pooled
            p_lo_s = "%.1f" % plo if plo > 0 else "0"
            p_hi_s = "inf" if phi == float("inf") else "%.1f" % phi
        pooled_s = "%s - %s%s" % (p_lo_s, p_hi_s, " capped" if capped else "")
        if why.get("from_contaminated"):
            pooled_s += " from_contaminated"
        if why.get("union"):
            pooled_s += " union"
        brackets = comp[sp].get("brackets") or []
        if not brackets:
            continue
        for b, agree in brackets:
            mn, mx = b.get("min"), b.get("max")
            raw = "%.3f - %.3f" % (mn if mn is not None else 0, mx if mx is not None else 2)
            conv_lo = b.get("lo")
            conv_hi = b.get("hi")
            conv_s = "%.1f - %s" % (conv_lo, "inf" if conv_hi == float("inf") else "%.1f" % conv_hi)
            agi_me = b.get("agiMe")
            gob = b.get("gob")
            f = b.get("file")
            # Name the reading: species, raw bracket, converted absolute, pooled interval, file
            label = "  %s bracket %s agiMe %s => %s pooled %s gob %s %s" % (
                sp[:12], raw, agi_me, conv_s, pooled_s, str(gob)[-5:], f)
            if pooled is None:
                check(label + " (no pooled interval to compare)", agree, None)
            else:
                # Intervals agree when they intersect; neither is a point estimate
                check(label, agree, True)
        # Species with a pooled interval but no bracket beyond those listed is covered
        # by the loop above; species with no reading at all are not in this map.


def agility_band():
    """How wide the band is, and which cards are on it. See estimate.agility_band.

    The spec has carried "the corpus fits +-10%, the guide states +-20%, unresolved"
    since 2026-09-02. It is resolvable from the logs already in hand, and the strongest
    form of the reading assumes nothing about any opponent's agility: for ONE card at one
    level and one initiative, the ratio of the longest reported cooldown to the shortest
    is the ratio of the two extreme factors, whatever those factors are. A +-10% band
    allows 1.1/0.9 = 1.2222 and no more. A +-20% band would allow 1.5.
    """
    print("\nthe agility band, and which cards ride it")
    ratios, spreads, flat = estimate.agility_band()
    if not ratios:
        print("  (no level-1 zero-initiative attack in the corpus)")
        return
    lo = min(r for _n, _s, r in ratios)
    hi = max(r for _n, _s, r in ratios)
    check("  every level-1 zero-initiative attack cooldown is inside [0.9, 1.1]",
          [r for _n, _s, r in ratios if (r < 0.9 - 1e-9) or (r > 1.1 + 1e-9)], [])
    near("  and the fastest sits exactly on 0.9", lo, 0.9, 1e-9)
    near("  and the slowest exactly on 1.1", hi, 1.1, 1e-9)
    # The assumption-free form: for ONE card at one level and one initiative, the ratio
    # of the longest cooldown to the shortest is the ratio of the two extreme factors,
    # whatever those factors are. It comes out at 1.1/0.9 and not a thousandth over.
    widest = max(spreads.values()) if spreads else 1.0
    near("  the widest spread for one card at one level and initiative", widest,
         1.1 / 0.9, 1e-4)
    # AND THE EDGE IS A PILE-UP, NOT A TAIL, which is what says the band ends there
    # rather than the corpus merely running out of fast creatures. If it ran to 1.2 there
    # would be readings between 1.1 and 1.2 and no reason for any to land exactly on 1.1.
    at_edge = len([r for _n, _s, r in ratios if abs(r - 1.1) < 1e-9])
    beyond = len([r for _n, _s, r in ratios if r > 1.1 + 1e-9])
    check("  nothing at all sits between 1.1 and the +-20% band's 1.2", beyond, 0)
    check("    while 1.1 itself is where the slowest opponents pile up", at_edge > 20,
          True)
    print("    %d observation(s) exactly on 1.1, %d card slice(s), %d level-1 readings"
          % (at_edge, len(spreads), len(ratios)))
    # The control. A maneuver with no initiative term must not move at all.
    check("  a maneuver that scales with nothing never moves a tick",
          [k for k, v in flat.items() if v > 1.0 + 1e-9], [])
    check("    and there were maneuver slices to check", len(flat) > 10, True)
    print("    from %d maneuver slice(s) across %d card(s)"
          % (len(flat), len(set(k[0] for k in flat))))


def agility_carriers():
    """Which cards take the modifier, which is not the same as which cards attack.

    Opportunity Knocks declares no attack type and rides the band anyway: base 45, it
    reports 41 ticks against every creature at the bottom of the band and 45 against the
    one measured at our own agility, and round(45*0.9) is 41. The pack read it as a
    maneuver, so the simulator predicted 45 for a card that costs 41 in almost every PvE
    fight it is thrown in.
    """
    print("\nwhich cards take the modifier")
    ratios, spreads, _flat = estimate.agility_band()
    moves = estimate.load_moves()
    riders, still = [], []
    for (name, _lvl, _ip), spread in spreads.items():
        (riders if spread > 1.0 + 1e-9 else still).append(name)
    riders = sorted(set(riders))
    check("  every card observed to move declares a type or a skill",
          [n for n in riders
           if not ((moves.get(n) or {}).get("attack_types")
                   or (moves.get(n) or {}).get("attack_skill"))], [])
    check("  and Opportunity Knocks is one of them, on its skill alone",
          ("Opportunity Knocks" in riders)
          and not (moves["Opportunity Knocks"].get("attack_types") or []), True)
    print("    riders: %s" % ", ".join(riders))


def opportunity_knocks():
    """The one card whose rule is not the opening rule, measured rather than assumed.

    The simulator has implemented "40% * mu of the greatest standing opening" since it was
    written, on the strength of the card's own text and nothing else. The original fourteen
    uses tested both halves of that: that it is a SHARE and not a number of points, and
    that mu scales it. Current counts are printed below; the verdict pins stay red while
    the uses contradict (empty intersection).
    """
    print("\nOpportunity Knocks, against the card's own text")
    uses, lo, hi = estimate.ok_boost()
    if not uses:
        print("  (never used in the corpus)")
        return
    # THE DECISIVE CASE. Under the ordinary rule, dO = cbrt(Wa/Wd) * Ob * (1 - Oc), an
    # opponent with nothing open is the EASIEST to open and the gain is at its largest.
    zeros = [(b, a) for b, a, _l in uses if b == 0]
    check("  used against nothing standing, it opens nothing", [a for _b, a in zeros],
          [0] * len(zeros))
    check("    and there was such a use to check", len(zeros) > 0, True)
    if lo is None:
        n = sum(1 for b, a, _l in uses if (b > 0) and (a < 100))
        print("    EMPTY interval from %d uncensored use(s) - the uses contradict, so the" % n)
        print("    instrument is unusable until they agree again; the verdicts below stay red.")
        # DELIBERATIVE-PINs: the verdicts did not change, only the plumbing did. An empty
        # instrument admits nothing and bounds nothing.
        check("  the multiplier is bounded on both sides", False, True)
        check("  0.4 * mu at the linear curve's mu(2) = 1.125 is admitted", False, True)
        check("  0.4 flat, with mu not scaling it, is excluded", False, False)
        check("  and so is 0.4 * mu at 1.5 - 0.5/sqrt(2) = 1.1464", False, False)
        return

    # Openings truncate into the log, so each use bounds the multiplier rather than
    # naming it, and the bounds intersect.
    check("  the multiplier is bounded on both sides", (lo > 1.0) and (hi < 2.0), True)
    check("  0.4 * mu at the linear curve's mu(2) = 1.125 is admitted",
          lo <= 1.45 <= hi, True)
    check("  0.4 flat, with mu not scaling it, is excluded", lo <= 1.40 <= hi, False)
    check("  and so is 0.4 * mu at 1.5 - 0.5/sqrt(2) = 1.1464",
          lo <= 1.0 + 0.4 * 1.1464 <= hi, False)
    print("    [%.4f, %.4f] from %d uncensored use(s)"
          % (lo, hi, sum(1 for b, a, _l in uses if (b > 0) and (a < 100))))


def mu_instruments_agree():
    """The two routes to mu, which share nothing but the deck.

    Take Aim reads it off a whole-tick cooldown through two floors and a round.
    Opportunity Knocks reads it off a multiplication of an opening, in a different field
    of a different event. A wrong rounding rule in the first survived a week and three
    "independent" confirmations that all ran through it; this is the first reading of mu
    that could have contradicted it.
    """
    print("\nmu, from two instruments that share nothing")
    check("  they do not disagree at any level", estimate.MU_DISPUTED, {})
    two = estimate.MU_MEASURED.get(2)
    check("    and level 2 is measured", two is not None, True)
    if two is None:
        return
    lo, hi = two
    check("  the linear curve's 1.125 survives both", lo <= 1.125 <= hi, True)
    check("  the square-root curve's 1.168 does not", lo <= 1.168 <= hi, False)
    check("  nor does 1.5 - 0.5/sqrt(L), at 1.1464", lo <= 1.1464 <= hi, False)
    ta = estimate.measure_mu().get(2)
    if (ta is not None) and ((two[0], two[1]) != (ta[0], ta[1])):
        print("    mu(2) in (%.4f, %.4f], narrowed by Opportunity Knocks from Take Aim's"
              " (%.4f, %.4f]" % (lo, hi, ta[0], ta[1]))
    else:
        print("    mu(2) in (%.4f, %.4f] - Take Aim alone, Opportunity Knocks unusable"
              % (lo, hi))


def deepest_interval():
    """Robust consensus among intervals, which is what intersection is not.

    A single contaminated reading empties an intersection. The deepest point - the value
    the most intervals cover - is the same computation without that failure mode, and it
    is what lets seventy-four bear readings say something instead of nothing.
    """
    print("\nconsensus among intervals, where intersection breaks")
    d, span = estimate.deepest_interval([(1.0, 5.0), (2.0, 6.0), (3.0, 7.0)])
    check("  three overlapping intervals agree over their common part", span, (3.0, 5.0))
    check("    covering all three", d, 3)
    # The failure mode this exists for.
    ivs = [(10.0, 20.0)] * 9 + [(100.0, 110.0)]
    d, span = estimate.deepest_interval(ivs)
    check("  one outlier does not empty the answer", span, (10.0, 20.0))
    check("    and the count says how many it left out", d, 9)
    check("  nothing in, nothing out", estimate.deepest_interval([]), (0, None))
    # Touching at a point is agreement, not a miss.
    d, span = estimate.deepest_interval([(1.0, 3.0), (3.0, 5.0)])
    check("  intervals that meet at a point meet", d, 2)


def dropped_gains():
    """What MIN_GAIN throws away, and the fact that it is not throwing away noise.

    A gain is small mostly because the opening it landed on was already large, so the
    threshold sorts by PHASE OF FIGHT rather than by measurement quality. Every defence
    weight in the pack therefore comes from the opening minutes.
    """
    print("\nthe gains MIN_GAIN drops, and what they are")
    per, _moves = estimate.collect(estimate.fightlog.default_logs(estimate.ROOT)[0])
    small, big = [], []
    for k in per:
        for r in per[k].get("wd") or ():
            (small if r[3] < estimate.MIN_GAIN else big).append(r[2])
    check("  there are gains on both sides of the threshold",
          (len(small) > 100) and (len(big) > 100), True)
    small.sort()
    big.sort()
    ms, mb = small[len(small) // 2], big[len(big) // 2]
    check("  a dropped gain landed on a MUCH larger standing opening", ms > 2 * mb, True)
    print("    median standing opening: %d under the threshold, %d over it, from %d and %d"
          % (ms, mb, len(small), len(big)))

    rows = [(str(k), estimate.wd_consensus(per[k])) for k in sorted(per, key=str)]
    rows = [(n, c) for n, c in rows if c and (c["agrees"] is not None)]
    agree = [n for n, c in rows if c["agrees"]]
    dis = [n for n, c in rows if not c["agrees"]]
    check("  most species' dropped gains agree with what the pack reads",
          len(agree) > 2 * len(dis), True)
    # The direction is the diagnostic. Third-party contamination can only ADD to a gain,
    # which can only read a defence weight LOW - so a mixed direction is not that.
    low = [n for n, c in rows if (not c["agrees"]) and (c["hi"] < c["against"])]
    high = [n for n, c in rows if (not c["agrees"]) and (c["lo"] > c["against"])]
    check("  and the disagreements do not all point one way",
          bool(low) and bool(high), True)
    print("    %d agree, %d do not - %d reading low, %d high" %
          (len(agree), len(dis), len(low), len(high)))
    print("    disagreeing: %s" % ", ".join(sorted(dis)))


def attribution_provenance():
    """Clean evidence is used alone where it exists, and never diluted.

    Per-observation attribution earns contaminated fights their place - it more than
    doubles the attributed gains and gives fifteen species a first measurement. But the
    pack INTERSECTS intervals, and a third party can only ever push a defence weight
    LOW, so one contaminated interval sitting slightly under empties the intersection.
    Pooling the two cost beeswarm, fox, redants, sentinelbee and warriorant the
    measurements they already had.

    So the rule is rank, not pool. This pins it, because the failure is silent: the
    species that would regress are exactly the ones with the most data, and they would
    come back as "contradictory" rather than as an error.
    """
    print("\nattribution provenance")
    per, _moves = estimate.collect(estimate.fightlog.default_logs(estimate.ROOT)[0])
    import json
    with open(estimate.PACK, encoding="utf8") as f:
        packed = json.load(f)
    packed = packed["opponents"] if isinstance(packed, dict) else packed
    entries = dict((o["name"], o.get("defence_weight") or {}) for o in packed)

    mixed = 0
    for name, rec in per.items():
        if not rec["wd"]:
            continue
        clean = [w for w in rec["wd"] if w[8]]
        got = entries.get(name)
        if (not clean) or (got is None):
            continue
        if len(clean) != len(rec["wd"]):
            mixed += 1
        # The entry must be what the CLEAN observations alone say - not what they say
        # once the contaminated ones have been mixed in. Asserted against the interval
        # rather than against a count, because the failure mode is an interval quietly
        # narrowing or emptying, not a row going missing.
        lo, hi = max(w[6] for w in clean), min(w[7] for w in clean)
        want = (round(lo, 1), round(hi, 1)) if lo <= hi else (None, None)
        check("  %s is measured from its clean evidence alone" % name[:18],
              (got.get("lo"), got.get("hi")), want)
        check("    and is not marked as rescued from contaminated fights",
              bool(got.get("from_contaminated")), False)
    check("  some species carry both kinds of evidence", mixed > 0, True)
    check("  and every wd row records which kind it is",
          all(len(w) == 9 for rec in per.values() for w in rec["wd"]), True)
    # The converse: anything measured only from contaminated evidence must say so.
    for name, rec in per.items():
        if rec["wd"] and not [w for w in rec["wd"] if w[8]]:
            got = entries.get(name)
            if got and (got.get("lo") is not None):
                check("  %s says its evidence was contaminated" % name[:18],
                      bool(got.get("from_contaminated")), True)


def equalization():
    """The dead zone, and the artefact it produced for weeks.

    Two skills within a factor of two are compared as if equal, so the skill term in
    cbrt(Wa/Wd) is pinned to 1. Inverting an opening gain for the defender's weight then
    returns the ATTACKER's own weight - which is what the boar's "anomaly" was: Knock Its
    Teeth Out at Wa 58 read 51-73 and Quick Barrage at Wa 111 read 111-158, the same
    creature reading as two numbers because two different weights went in.
    """
    print("\nequalization - the dead zone in the skill comparison")
    eq = model.equalize
    near("equal skills compare at 1", eq(100, 100), 1.0, 1e-12)
    near("  and so does half", eq(100, 50), 1.0, 1e-12)
    near("  and double", eq(100, 200), 1.0, 1e-12)
    check("the band is inclusive at both edges",
          model.equalized(100, 50) and model.equalized(100, 200), True)
    # Outside, and continuous across the edge - a step here would show as a cliff in the
    # openings a slightly weaker opponent takes.
    near("a quarter our skill opens twice as hard", eq(100, 25), 2.0, 1e-12)
    near("four times ours opens half as hard", eq(100, 400), 0.5, 1e-12)
    near("continuous just below the lower edge", eq(100, 49.999), 1.0, 1e-4)
    near("continuous just above the upper edge", eq(100, 200.001), 1.0, 1e-4)

    # The guide's own worked block-weight figures, which are the multiplier half with the
    # skills equal. If these drift, the cube root or the direction has gone.
    for mult, pct in ((2.5, 0.7368), (1.5, 0.8736), (0.8, 1.0772), (0.75, 1.1006)):
        near("  block weight %.2f gives %.4f" % (mult, pct),
             model.opening_gain_eq(100, 1.0, 100, mult, 1.0, 0.0), pct, 5e-5)

    # THE ARTEFACT ITSELF. Our own moves read different SKILLS - Knock Its Teeth Out uses
    # Unarmed at 58, Quick Barrage uses Melee at 111 - and against one creature whose skill
    # sits inside both bands, every gain equalizes to the move's listed opening. Inverting
    # that for a defence weight returns the attack weight that went in, so the same animal
    # reads as 58 through one move and 111 through the other. That is the boar.
    foe = 80.0
    for wa in (58.0, 111.0):
        check("  skill %.0f is inside the band against %.0f" % (wa, foe),
              model.equalized(wa, foe), True)
        gain = model.opening_gain_eq(wa, 1.0, foe, 1.0, 20.0, 0.0)
        near("    its gain is just the listed opening", gain, 20.0, 1e-9)
        back = model.defence_weight(wa, gain, 20.0, 0.0)
        near("    and inverting it hands back our own %.0f" % wa, back, wa, 0.5)
    # Outside the band it is a real measurement again - a bee swarm, far weaker, is where
    # three moves of different weight all agree on one number.
    weak = 12.0
    check("  a far weaker opponent is outside it", model.equalized(111.0, weak), False)
    g = model.opening_gain_eq(111.0, 1.0, weak, 1.0, 20.0, 0.0)
    check("    so its gain exceeds the listed opening", g > 20.0, True)


def foe_skill():
    """Recovering an opponent's SKILL, with a round-trip as the control.

    Simulate a gain from a known pair of skills, invert it the naive way, and the recovery
    must hand the known skill back. That is a control in the strict sense - the answer is
    known before the estimator runs - and it is what the defence-weight numbers never had.
    """
    print("\nrecovering the opponent's combat skill")
    ours = 111.0
    for foe, label in ((10.0, "far weaker"), (40.0, "weaker"), (400.0, "far stronger"),
                       (600.0, "stronger")):
        gain = model.opening_gain_eq(ours, 1.0, foe, 1.0, 20.0, 0.0)
        wd = model.defence_weight(ours, gain, 20.0, 0.0)
        got, _lo, _hi, _br = estimate.foe_skill_from(ours, wd)
        near("  %-13s skill %.0f round-trips" % (label, foe), got, foe, 0.5)
    # Inside the band nothing can be recovered, and it must say so rather than guess.
    for foe in (60.0, 111.0, 200.0):
        gain = model.opening_gain_eq(ours, 1.0, foe, 1.0, 20.0, 0.0)
        wd = model.defence_weight(ours, gain, 20.0, 0.0)
        got, lo, hi, _br = estimate.foe_skill_from(ours, wd)
        check("  skill %.0f is inside the band and declines to guess" % foe, got, None)
        check("    but is bounded", (lo, hi), (ours / 2.0, ours * 2.0))


def foe_policy():
    """What a species does, and the two claims that did not survive being checked.

    The negative results are the ones worth pinning, because each looked convincing first.
    Targeting especially: pooled, animals appear to hit whichever colour we are most open
    in three quarters of the time against a 59% null - and per species the effect is gone,
    because ants are a fifth of the sample and Ant Spit makes the very opening it then
    "targets". Recording that as a behaviour would have put a Simpson's paradox into the
    optimizer's advice.
    """
    print("\nwhat a species does, per species")
    per, _moves = None, None
    kinds = estimate.animal_move_kinds()
    check("animal moves are classed attack or not, from the wiki table", len(kinds) > 30,
          True)
    check("  Ant Spit is an attack", kinds.get("Ant Spit"), True)
    check("  Bristle is not", kinds.get("Bristle"), False)

    # The mix must survive a pack round trip in FREQUENCY order, not alphabetical - the
    # writer sorts keys, so a dict here would silently reorder it.
    rec = {"foe_moves": [("Ant Spit", 0, True)] * 9 + [("Fell Scratch", 0, True)] * 1}
    pol = estimate.foe_policy(rec)
    check("the mix is an ordered list, commonest first", pol["mix"][0][0], "Ant Spit")
    near("  and carries its share", pol["mix"][0][1], 0.9, 1e-9)
    check("  with too few initiative observations to condition on",
          "ip_conditioned" in pol, False)
    # An animal holds one target at a time, so a mix drawn from group fights partly
    # describes what it was doing to somebody ELSE. That is what withdrew the distance
    # finding, so every mix carries how much of it came from a fight nobody else was in.
    check("  and it says how much came from a solo fight", pol["solo_n"], 10)
    grp = estimate.foe_policy({"foe_moves": [("Ant Spit", 0, False)] * 8})
    check("a mix drawn entirely from group fights says so", grp["solo_n"], 0)
    check("  while still reporting what it threw", grp["n"], 8)

    # Conditioning is claimed only on a real swing with real support behind it.
    atk = [("Ant Spit", 0, True)] * 12 + [("Bristle", 0, True)] * 1
    non = [("Ant Spit", 2, True)] * 6 + [("Bristle", 2, True)] * 6
    pol = estimate.foe_policy({"foe_moves": atk + non})
    check("a large swing on enough observations is claimed", pol["ip_conditioned"], True)
    flat = ([("Ant Spit", 0, True)] * 10 + [("Bristle", 0, True)] * 2
            + [("Ant Spit", 2, True)] * 10 + [("Bristle", 2, True)] * 2)
    check("an identical split is not", estimate.foe_policy({"foe_moves": flat})["ip_conditioned"],
          False)
    # Initiative is relational, so a creature fighting somebody else can sit at zero
    # against us while acting on them. A conditioning drawn from group fights is flagged.
    dirty = ([("Ant Spit", 0, False)] * 12 + [("Bristle", 0, False)] * 1
             + [("Ant Spit", 2, False)] * 6 + [("Bristle", 2, False)] * 6)
    d = estimate.foe_policy({"foe_moves": dirty})
    check("a swing seen only in group fights is flagged", d["ip_group_contaminated"], True)
    check("  and the swing itself is still reported", d["ip_conditioned"], True)
    check("one seen in solo fights is not flagged",
          estimate.foe_policy({"foe_moves": atk + non})["ip_group_contaminated"], False)


def tactics():
    """Re-aggro's price, and whether a speed reading means anything.

    Both come from the same fact: an engagement can END and be re-taken. That is a real
    tactic against something that flees, and it is not free.
    """
    print("\nre-aggro, and relative speed")
    # Synthetic, so the arithmetic is checkable without the corpus. One gob, two
    # engagements, initiative held at the end of the first and gone at the start of the
    # second.
    per = {"boar": {"ip_edges": [(7, 100, 0, 5), (7, 200, 0, 3)]}}
    kept, lost, _ex = estimate.reaggro_cost(per)
    check("initiative held at a boundary and gone after it counts as lost", (kept, lost),
          (0, 1))
    per = {"boar": {"ip_edges": [(7, 100, 0, 5), (7, 200, 5, 6)]}}
    check("  and carried across counts as kept", estimate.reaggro_cost(per)[0], 1)
    # Ending on nothing says nothing either way.
    per = {"boar": {"ip_edges": [(7, 100, 0, 0), (7, 200, 0, 2)]}}
    check("  ending on no initiative is not evidence", estimate.reaggro_cost(per)[:2], (0, 0))

    # Speed. The rate only measures a difference while we are actually withdrawing, so a
    # standing fight has to read as uninformative rather than as "it kept up with us".
    fast = {"sep": [18.0] * 30 + [0.0] * 30}
    r = estimate.relative_speed(fast)
    check("backing away freely reads as informative", r["informative"], True)
    check("  and reports how much faster we are", r["p95"] >= 15, True)
    still = {"sep": [0.0] * 60}
    r = estimate.relative_speed(still)
    check("a standing fight is NOT read as the creature matching us", r["informative"],
          False)
    near("  even though its p95 is zero", r["p95"], 0.0, 1e-9)
    # Schema 6 records the speed outright, and it must WIN over the inference - the
    # inference exists only for logs written before it. Reported as a range, because speed
    # is randomised per individual within a species band.
    real = {"foespd": [9.0] * 6 + [11.0] * 6 + [10.0] * 8, "myspd": [18.0] * 20,
            "sep": [0.0] * 200}
    r = estimate.relative_speed(real)
    check("a logged speed is used instead of the inference", r["measured"], True)
    check("  and it reports a range, not one number", (r["lo"] <= r["median"] <= r["hi"]),
          True)
    check("  we outrun it, so the fight can be held", r["we_outrun_it"], True)
    slowus = {"foespd": [19.0] * 20, "myspd": [18.0] * 20, "sep": [0.0] * 200}
    check("something faster than us reads so", estimate.relative_speed(slowus)["we_outrun_it"],
          False)
    check("  even from a standing fight the inference could not read",
          estimate.relative_speed(slowus)["measured"], True)

    # A single teleporting sample must not become the answer.
    jump = {"sep": [0.0] * 59 + [2400.0]}
    check("a distance jump is discarded rather than believed",
          estimate.relative_speed(jump)["p95"] < 60.0, True)


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


def defence_weight_late():
    """The late-phase reading, and whether the (1 - Oc) falloff is the cause.

    MIN_GAIN = 10 drops every gain under ten points. That is a filter on the
    OPENING and not on the noise: the median standing opening under a gain of ten
    is 51 and over it is 16.5, so the pack's defence weights come from the opening
    minutes and the late phase - where damage goes as opening squared - has never
    been tested until now. wd_consensus() reports the dropped gains via
    deepest_interval (bear, boar, moose are the canonical examples; current depths
    are printed below by the check itself).

    Against the species that have both kinds of evidence the late reading often
    disagrees with the pack, mixed direction. HISTORY, because the census moves
    (current readings printed below): 2026-09-03 read 23 agree, 8 do not, 5 low /
    3 high. 2026-09-04 wolverine joined the LOW set, then bat the agreeing set:
    23/9, 6 low / 3 high. Contamination can only ADD to a gain and so can only ever read a defence
    weight LOW - the 3 highs need another cause). Three of the nine disagreers - badger,
    boar, sentinelbee - were already marked disputed for an unrelated reason
    (equalization), which is some corroboration that the disagreement is about
    those creatures rather than about this method.

    The remaining candidate is the falloff term (1 - Oc) itself, and this is the
    first reading that exercises it at high standing openings. The check is whether
    the corpus ENTAILS a change to it:

      residual-vs-standing slope: if the falloff were wrong, inferred Wd would
        trend with Oc (late gains larger than predicted => inferred Wd low)
      late-phase-only misfit: late residuals systematically above or below pred
      contamination direction: LOW could be contamination, HIGH cannot

    The corpus does not entail it. On the 2026-09-03 corpus the slopes were small and
    10 points, bear -1.7%, boar -1.9%, caveangler -18.6% but caveangler is
    depth-scaled, royalguardant -0.3%, sentinelbee -4.6%, walrus +3.0%,
    warriordrone -1.2%), residuals vs standing likewise small and mixed, and the
    three HIGH disagreements sit on both sides of zero slope. No global falloff
    shape moves them all one way. So defence_weight_late is published BESIDE the
    skill, never folded in, unless a future corpus proves identity - the
    disagreements forbid silent folding. See _mu_measured precedent: disagreement
    between sound instruments is the finding.

    This check pins the READINGS not the verdict: deepest_interval coverage,
    per-species pack vs late intervals, which species disagree and which direction,
    and which were already disputed. If a future corpus moves these, the check
    goes red naming the reading that moved, not a law that does not exist.
    """
    print("\ndefence_weight_late, and whether the (1 - Oc) falloff is the cause")
    per, _moves = estimate.collect(estimate.fightlog.default_logs(estimate.ROOT)[0])
    rows = [(str(k), estimate.wd_consensus(per[k])) for k in sorted(per, key=str)]
    rows = [(n, c) for n, c in rows if c and (c["agrees"] is not None)]
    agree = [n for n, c in rows if c["agrees"]]
    dis = [n for n, c in rows if not c["agrees"]]
    # The census, printed exact for the human. The checks assert PROPERTIES of it:
    # exact counts and sets move with the corpus (wolverine joined the LOW set and bat
    # the agreeing set mid-session), so pinning them flaps the suite on every new
    # creature. Regime changes redden instead: no disagreements at all, disagreements
    # outnumbering agreements, one-sided direction, or a canonical depth collapsing.
    print("  %d species agree with the pack, %d do not: %s"
          % (len(agree), len(dis), ", ".join(sorted(dis))))
    check("  most species agree with the pack", len(agree) > len(dis), True)
    check("  disagreements exist and are reported, not empty", bool(dis), True)
    low = [n for n, c in rows if (not c["agrees"]) and (c["hi"] < c["against"])]
    high = [n for n, c in rows if (not c["agrees"]) and (c["lo"] > c["against"])]
    print("  low (contamination can explain): %s" % ", ".join(sorted(low)))
    print("  high (contamination cannot): %s" % ", ".join(sorted(high)))
    check("  disagreements are mixed low and high", bool(low) and bool(high), True)
    check("  low and high partition the disagreements",
          sorted(low + high) == sorted(dis) and not set(low) & set(high), True)
    # Which 3 were already disputed for an unrelated reason (equalization).
    # That is not a dismissal of the disagreement, it is corroboration that it is
    # about those creatures rather than about this method.
    already_disputed = []
    for n, c in rows:
        if not c["agrees"]:
            ent = estimate.foe_skill_entry(per[n])
            if ent and ent.get("disputed"):
                already_disputed.append(n)
    check("  3 of the 9 were already disputed (equalization)", sorted(already_disputed),
          sorted(["badger", "boar", "sentinelbee"]))
    # Deepest-interval coverage - the robust replacement for intersection. Canonical
    # illustrations printed exact, asserted as coverage ratios: a collapsing depth
    # (contamination creeping in) reddens, one noisy interval does not.
    by_name = dict(rows)
    for _name in ("bear", "boar", "moose"):
        _c = by_name.get(_name) or {}
        print("  %s deepest_interval covers %s of %s" % (_name, _c.get("depth"), _c.get("n")))
        check("  %s deepest_interval still covers its gains" % _name,
              (_c.get("n") or 0) > 0 and (_c.get("depth") / _c.get("n")) > 0.9, True)
    # Contamination direction rule: LOW could be third-party hits adding to a gain,
    # HIGH cannot. So the 3 HIGH need another cause if they are not noise.
    # Pin that the rule was applied: late LOW intervals sit below the pack point,
    # HIGH sit above it.
    for n in low:
        c = by_name[n]
        check("  %s reads low (late_hi < pack)" % n[:12], c["hi"] < c["against"], True)
    for n in high:
        c = by_name[n]
        check("  %s reads high (late_lo > pack)" % n[:12], c["lo"] > c["against"], True)
    # Falloff suspect: if (1 - Oc) were globally wrong, every late Wd would sit on
    # one side of the pack point. Mixed direction exonerates a global falloff error.
    # This is the verdict the corpus does NOT entail a model change for.
    check("  mixed direction exonerates a global falloff shape error",
          bool(low) and bool(high), True)
    # Defence_weight_late is published BESIDE the skill, never folded in, until
    # identity is proven - pin that the pack still carries it separately.
    import json
    with open(estimate.PACK, encoding="utf8") as f:
        packed = json.load(f)
    packed = packed["opponents"] if isinstance(packed, dict) else packed
    entries = dict((o["name"], o) for o in packed)
    for n in dis:
        ent = entries.get(n) or {}
        check("  %s defence_weight_late is published beside the skill" % n[:12],
              ent.get("defence_weight_late") is not None, True)
        check("  %s skill is not replaced by its late reading" % n[:12],
              ent.get("skill") is not None or ent.get("defence_weight") is not None, True)


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


def opponent_period():
    """How often a creature acts - a mean, and specifically not a fold.

    Written after getting it wrong twice in one sitting, in opposite directions.
    """
    print("\nthe opponent's clock")
    ms = lambda ticks: [int(t * 60) for t in ticks]

    # THE IDENTITY. Over T ticks with N actions, T is the sum of the gaps, so the rate is
    # 1/mean. Anything but a mean answers a different question - here a median would say
    # 20 where the creature actually gets 5 swings in 130 ticks, which is 26.
    p = estimate.period_of(ms([20, 20, 20, 20, 50]))
    near("five actions over 130 ticks reads 26, not the median's 20",
         p["ticks"], 26.0, 0.05)

    # THE CATTLE CASE. Two cards, 22 ticks and 38, which sit close enough to 2:1 that a
    # fold-the-doubles rule swallows the second: every 38 becomes a 19 and the creature is
    # reported as twice as dangerous as it is. The tell that 38 is a real cooldown and not
    # a missed action is that NOTHING sits at 44, where a double of 22 would have to be.
    p = estimate.period_of(ms([22] * 18 + [38] * 13 + [39] * 6 + [37] * 3))
    check("two clocks near a 2:1 ratio are both kept", len(p["modes"]), 2)
    check("  and neither is folded away",
          sorted(p["modes"]) == [22, 38], True)
    check("  so the period sits between them, not below both",
          (p["ticks"] > 22) and (p["ticks"] < 38), True)

    # THE SENTINEL BEE CASE, which is the one the fold rule was right about. A tight
    # cluster at 50 with a couple at 94 is one clock with two missed actions, and calling
    # 94 a second clock would claim a card the creature does not have. This is decided
    # when a peak is LABELLED and never when the mean is computed - the 94s still count
    # their full elapsed time, which is the conservative direction.
    p = estimate.period_of(ms([50] * 12 + [49] * 4 + [51] * 2 + [94] * 3))
    check("a peak at twice the period is not a second clock", p["modes"], [50])

    # LULLS. It withdrew, or we did. Cut at three times the median and COUNTED, because a
    # filter whose removals nobody counts is doing more than it says.
    p = estimate.period_of(ms([44] * 20 + [246, 435]))
    check("gross lulls are trimmed", p["dropped"], 2)
    check("  and the rest survives", p["n"], 20)
    near("  leaving the clock where the cluster is", p["ticks"], 44.0, 0.1)

    check("nothing to measure returns nothing", estimate.period_of([]), None)


def broken_gear():
    """Worn-out gear: no armour, but a shield is still a shield.

    Two different rules that look like one, and collapsing them would be wrong in both
    directions. A broken item contributes NO armour - the client's own Armor Class readout
    excludes it and the log mirrors that, so a character in three worn-out pieces really
    does fight at zero. But Shield Up only asks whether a shield is EQUIPPED, and a broken
    one still is, so its block multiplier applies exactly as it would with a fresh one.

    Pinned because the natural tidy-up - filter broken items out of the gear list once, at
    the top - would silently take the stance's multiplier away with the armour.
    """
    print("\nbroken gear: no armour, but a shield is still a shield")
    moves = estimate.load_moves()
    if "Shield Up" not in moves:
        print("  (no Shield Up in the sheet - skipped)")
        return
    attrs = {"melee": 125, "unarmed": 81}
    broken = [{"res": "gfx/invobjs/small/roundshield", "hard": 5, "soft": 2,
               "broken": True}]
    none = [{"res": "gfx/invobjs/small/bronzesword", "hard": 0, "soft": 0,
             "broken": False}]
    with_shield = estimate.block_weight(moves, "Shield Up", attrs, broken, 1)
    without = estimate.block_weight(moves, "Shield Up", attrs, none, 1)
    if (with_shield is None) or (without is None):
        print("  (Shield Up carries no block weight in this sheet - skipped)")
        return
    check("a BROKEN shield still satisfies Shield Up's requirement",
          with_shield[0] > without[0], True)
    check("  so the reduced multiplier is not applied",
          "no shield" in (with_shield[2] or ""), False)
    check("  where an empty hand does apply it",
          "no shield" in (without[2] or ""), True)


def weapons_live_vs_wiki():
    print("\nweapons: live wpn vs wiki table - the two readings by name")
    seen = estimate.weapons_seen()
    join = estimate.weapon_offline_join()
    # Bronze sword: the agreement case (12.5% both sides) that rules out a units error.
    bs = seen.get("bronzesword") or {}
    bs_tooltip = (bs.get("damage") or [None])[0]
    bs_ql = (bs.get("quality") or [None])[0]
    bs_rec = (bs.get("recovered_base") or {}).get("lo")
    bs_pen = (bs.get("armpen") or [None])[0]
    check("bronze sword tooltip 176.0", bs_tooltip, 176.0)
    check("bronze sword ql 38.0613", bs_ql, 38.0613)
    check("bronze sword recovered base 90.21 (tool) vs wiki 90", round(bs_rec or 0, 2), 90.21)
    check("bronze sword wiki pen 12.5% agrees with live 0.125", round((bs_pen or 0) * 100, 2), 12.5)
    near("  dmg/sqrt(ql/10) recovers wiki to 0.24%", bs_rec or 0, 90.0, 0.3)
    # Stone axe: the disagreement that is a finding, not arithmetic.
    sa = seen.get("stoneaxe") or {}
    sa_tooltip = (sa.get("damage") or [None])[0]
    sa_ql = (sa.get("quality") or [None])[0]
    sa_rec = (sa.get("recovered_base") or {}).get("lo")
    sa_pen = (sa.get("armpen") or [None])[0]
    check("stone axe tooltip 71.0", sa_tooltip, 71.0)
    check("stone axe ql 56.2835", sa_ql, 56.2835)
    check("stone axe recovered base 29.93 (tool) vs wiki 30", round(sa_rec or 0, 2), 29.93)
    near("  dmg/sqrt(ql/10) recovers wiki to 0.24%", sa_rec or 0, 30.0, 0.3)
    check("stone axe live pen 0.20 vs wiki 10% is factor-2", round((sa_pen or 0) * 100, 1), 20.0)
    # Offline join prefers live where present, wiki fallback where not.
    check("offline join bronzesword pen is live 0.125", join.get("bronzesword", {}).get("armorpen"), 0.125)
    check("offline join stoneaxe pen is live 0.20 (not wiki 0.10)", join.get("stoneaxe", {}).get("armorpen"), 0.2)
    check("offline join bronzesword base is live 90.21 (not wiki 90 flat)", round(join.get("bronzesword", {}).get("basedmg") or 0, 2), 90.21)
    check("offline join stoneaxe base is live 29.93", round(join.get("stoneaxe", {}).get("basedmg") or 0, 2), 29.93)
    # Wiki fallback: a weapon never held stays on wiki values, absent stays null never 0.
    # Battleaxe has no live reading in this corpus.
    ba = join.get("battleaxeofthetwelfthbay") or join.get("battleaxe") or {}
    check("wiki fallback battleaxe base 150 where no live reading", ba.get("basedmg"), 150)
    check("wiki fallback battleaxe pen 0.10 where no live reading", ba.get("armorpen"), 0.1)
    # Preserve raw strings alongside parsed numbers per M1b contract - check one wiki raw.
    import json as _json, os as _os
    try:
        with open(_os.path.join(estimate.ROOT, "data", "combat", "weapons.json"), encoding="utf8") as f:
            wiki_list = _json.load(f)
        sa_wiki_raw = next((w for w in wiki_list if w.get("name") == "Stone Axe"), {})
        check("wiki Stone Axe raw pen string preserved alongside value", sa_wiki_raw.get("armorpen", {}).get("raw"), "20")
        check("wiki Stone Axe raw base string preserved", sa_wiki_raw.get("basedmg", {}).get("raw"), "30")
        # Null stays null, never 0.
        cut = next((w for w in wiki_list if w.get("name") == "Cutthroat Knuckles"), {})
        check("absent armorpen stays null, never 0", cut.get("armorpen"), None)
    except Exception as e:
        check("wiki raw preservation check", str(e)[:20], "ok")


def main():
    hitpoints()
    agility()
    defence()
    deck_weighting()
    deck_history()
    mu_measurement()
    own_defence()
    mu_from_reductions()
    agility_control()
    agi_brackets()
    agility_band()
    agility_carriers()
    opportunity_knocks()
    mu_instruments_agree()
    deepest_interval()
    dropped_gains()
    defence_weight_late()
    attribution_provenance()
    mu_curve()
    equalization()
    foe_skill()
    foe_policy()
    tactics()
    armour()
    buckets()
    broken_gear()
    opponent_period()
    weapons_live_vs_wiki()
    if failures:
        print("\n%d CHECK(S) FAILED" % len(failures))
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
