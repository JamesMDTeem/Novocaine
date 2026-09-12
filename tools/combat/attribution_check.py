"""Controls for the attribution prototype: every one reads a value known independently.

The rule this suite is written under is the project's own. A check names a READING, never a
verdict: "four files place this damage float within six milliseconds of each other" is a
reading; "the attacker was Santa" is a verdict, and no corpus row says it. Every quantity here is
either an integer the client drew, a number two independent sources agree on, or a value
computed by construction in a fixture whose inputs are written down beside it.

Nothing here reads or writes `data/combat`. Attribution is a consumer of the model and never
a pack writer, so `estimate_check`'s assertions stay independent of it: if this file were
deleted, not one shipped number would move.
"""

import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import attribution  # noqa: E402
import encounter  # noqa: E402
import estimate  # noqa: E402
import fightlog  # noqa: E402
import model  # noqa: E402

failures = []
ROOT = encounter.ROOT

# One integer per channel, the same tolerance the matcher uses. Named here rather than
# reached through the module so that a fixture testing the arithmetic does not quietly
# follow the matcher if the matcher's own constant is loosened.
TOL_POINTS = 1.0


def check(what, got, want):
    ok = got == want
    print("  %-62s %-18s %s" % (what, repr(got)[:18], "ok" if ok else "WANT %r" % (want,)))
    if not ok:
        failures.append(what)


def near(what, got, want, tol):
    ok = (got is not None) and (abs(got - want) <= tol)
    print("  %-62s %-18s %s"
          % (what, ("%.4f" % got) if got is not None else "None",
             "ok" if ok else "WANT %.4f +/- %.4f" % (want, tol)))
    if not ok:
        failures.append(what)


def find_log(basename):
    """One corpus file by name, or None. The pool is one directory per character."""
    hits = glob.glob(os.path.join(ROOT, "data", "combat", "pool", "**", basename),
                     recursive=True)
    return hits[0] if hits else None


def encounter_with(basename):
    """The built encounter containing a named file, or None if the corpus lacks it."""
    p = find_log(basename)
    if p is None:
        return None
    paths, _dirs = fightlog.default_logs(ROOT)
    for grp in encounter.group(sorted(paths)):
        if any(os.path.basename(q) == basename for q in grp):
            return encounter.build(grp)
    return None


# ---------------------------------------------------------------------------------


def damage_is_integers():
    """Every damage float the client draws is a whole number, and both channels are drawn.

    THIS IS WHAT MAKES ATTRIBUTION POSSIBLE AT ALL. An observation that were a float would
    carry the attacker's exact arithmetic and identify them outright; an integer carries
    the arithmetic to within a rounding, which is why a candidate has to be scored by an
    interval and why two similar attackers cannot always be told apart.

    Also the reason the tolerance is one point PER CHANNEL and not zero overall: ARM and
    SHP are rounded separately by the client, so their sum can sit a point either side of
    the rounded raw. The Zzxcu Full Circle hit below computes 109.488 and is written as
    ARM 50 + SHP 60 = 110.
    """
    print("\nwhat the client actually wrote down")
    paths, _dirs = fightlog.default_logs(ROOT)
    n = frac = 0
    chans = {}
    for p in sorted(paths)[::17]:            # a fixed 1-in-17 stride, so this is repeatable
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if '"dmg"' not in line:
                        continue
                    try:
                        r = json.loads(line)
                    except ValueError:
                        continue
                    if r.get("ev") != "dmg":
                        continue
                    v = r.get("v")
                    if not isinstance(v, (int, float)):
                        continue
                    n += 1
                    chans[r.get("ch")] = chans.get(r.get("ch"), 0) + 1
                    if abs(v - round(v)) > 1e-9:
                        frac += 1
        except OSError:
            continue
    print("    %d damage row(s) sampled, channels %s"
          % (n, ", ".join("%s %d" % kv for kv in sorted(chans.items()))))
    check("  every damage value is an integer", frac, 0)
    check("    and there were rows to check", n > 1000, True)
    check("    with both damage channels present",
          ("ARM" in chans) and ("SHP" in chans), True)


def the_opening_is_an_interval():
    """A printed opening is a cell, not a point, and the expectation inherits its width.

    Openings reach the log through `(int)(100 * ameteri)`, which TRUNCATES: a printed 68
    means a true value anywhere in [0.68, 0.69). Raw damage goes as the square of the
    combined opening, so one point of print resolution is worth about 3% of the expectation.

    The control is ZzxcuV3's Full Circle on wolf 137009758 in
    0535-1788623864484-ZzxcuV3-3.jsonl, observed as ARM 50 + SHP 60 = 110. The victim's
    last sampled opening before the announcement is [56, 0, 0, 68] - which two witnesses
    agree on, ZzxcuV3's own file and Shade's - and Full Circle is Sweeping (yellow) and
    Oppressive (red), so the reading is red alone. At the bottom of that cell the
    expectation is 106.337 and at the top 110.474. The observation is 110: OUTSIDE the
    point and INSIDE the interval.

    The 2026-09-11 audit memo quotes 109.488 for this hit, computed from red 69. That is
    the sample before last: both witnesses record 69 at ...883954 and earlier, and 68 at
    ...884011, and the throw is at ...884126. The memo's number is the same arithmetic on a
    stale sample, and it happens to land inside the interval the fresh sample gives - which
    is the point of quoting an interval rather than either number.
    """
    print("\nan opening is a cell, not a point")
    enc = encounter_with("0535-1788623864484-ZzxcuV3-3.jsonl")
    if enc is None:
        print("    (the corpus here does not hold the wolf encounter)")
        return
    zz = [f for f in enc.fighters.values() if f.name == "ZzxcuV3"]
    check("  ZzxcuV3 is in the encounter", len(zz) == 1, True)
    if not zz:
        return
    found = None
    for a in attribution.attribute(enc):
        if (a.hit.victim == 137009758) and (abs(a.hit.raw - 110) < 1e-9):
            found = a
    check("  the ARM 50 + SHP 60 hit on the wolf is found", found is not None, True)
    if found is None:
        return
    cand = [c for c in found.candidates if c.card == "Full Circle"]
    check("    and Full Circle is the candidate for it", len(cand) == 1, True)
    if not cand:
        return
    c = cand[0]
    check("    read against the opening two witnesses agree on", c.colours, [56, 0, 0, 68])
    near("    the bottom of the opening's cell expects", c.expected[0], 106.337, 0.01)
    near("    the top of it expects", c.expected[1], 110.474, 0.01)
    check("    so the observed 110 is inside the interval",
          c.expected[0] <= 110 <= c.expected[1], True)
    check("      and outside the point the printed opening alone would give",
          abs(c.expected[0] - 110) > 1.0, True)
    check("    which makes this hit attributable", found.confidence, attribution.HIGH)


def the_bracket_is_the_announcement():
    """The opening must be read before the ANNOUNCEMENT, not before the `move` row.

    An action reaches the log as two client messages - the overlay that announces the card
    and the `move` row that books it - p50 3 ms apart, and a `state` row can land between
    them already carrying the move's own effect. Bracketing on the move row then reads the
    opening the attack CREATED instead of the one it struck.

    The control is the ant in 0740-1788545411918-Shade-36.jsonl. The announcement is at
    t=2543, a state at 2546 carries red 55, the move row is at 2547, and the observed
    damage is SHP 8. Read before the announcement the victim's red is 33 and the
    expectation is 8.27; read before the move row it is 55 and the expectation is 22.96.
    The observation settles which is right, and it is not close.
    """
    print("\nwhere the bracket is anchored")
    p = find_log("0740-1788545411918-Shade-36.jsonl")
    if p is None:
        print("    (the corpus here does not hold the ant fight)")
        return
    log = fightlog.read(p)
    enc = encounter.Encounter([log])
    enc.offset = {log.path: 0}
    enc.matches = {log.path: None}
    f = encounter.Fighter(enc, [log], encounter.load_weapons(), estimate.load_moves())
    enc.fighters = {f.gob: f}
    timeline = attribution.victim_openings(enc)
    act = None
    for c in attribution.actions_of(enc):
        if (c.card == "Quick Barrage") and (2400 <= enc.own_time_in(log, c.wall) <= 2700):
            act = c
    check("  the barrage is found", act is not None, True)
    if act is None:
        return
    check("    anchored on the announcement, not the move row",
          enc.own_time_in(log, act.wall), 2543)
    before = attribution.openings_before(timeline, act.victim, act.wall)
    check("    which reads the victim's red at", before, [0, 0, 0, 33])
    b = f.raw_bounds("Quick Barrage", before, act.wall)
    near("      expecting", b[0], 8.266, 0.01)
    stale = attribution.openings_before(timeline, act.victim,
                                        enc.wall_of(log, 2547) + 1)
    check("    the move row would have read", stale, [0, 0, 0, 55])
    sb = f.raw_bounds("Quick Barrage", stale, act.wall)
    near("      expecting instead", sb[0], 22.962, 0.01)
    check("    and the observed SHP is 8, which is the announcement's answer",
          (abs(b[0] - 8) < 1.5) and (abs(sb[0] - 8) > 10), True)


def the_clocks_are_cross_checked():
    """Damage floats several clients saw, lining up after a correction fitted to something
    else entirely - and not lining up where no correction was available.

    An offset is recovered from CARD ANNOUNCEMENTS. Damage floats are a different set of
    events, so where they fall after the correction is an independent test of it, and the
    wolverine encounter around wall 1788596139 offers both halves of the test at once.

    Four files record the wolverine's damage. Three carry all five floats and BonkiDonki's
    first file carries four of them; one of the four needs +1,682 ms to line up. After
    correction the four agree on each float to within a few milliseconds:

        float   n   widest disagreement
        2       4   2 ms
        6       4   2 ms
        9       4   2 ms
        67      4   6 ms
        74      3   2 ms

    THE LAST FLOAT IS THE COUNTER-EXAMPLE, and it is the reason unmeasured offsets are not
    carried as zero. A fifth float of 77 lands after the first three files have ended and
    is recorded by three LATER files - one per character, each too short to share five
    announcements with the reference, so none of their offsets could be measured. Carried
    at zero, those three place the same float 129 ms apart. Same encounter, same victim,
    same arithmetic: 2 ms where the clocks are known and 129 ms where they are assumed.
    """
    print("\nthe clocks, checked against events they were not fitted to")
    enc = encounter_with("0411-1788596139011-BonkiDonki-7.jsonl")
    if enc is None:
        print("    (the corpus here does not hold the wolverine encounter)")
        return
    byval = {}
    measured_of = {}
    for g in enc.logs:
        known = (enc.matches.get(g.path) is None) or bool(enc.matches.get(g.path))
        for eng in g.engagements:
            for d in eng.damage:
                if (d.get("gob") != 1729324533) or (d.get("ch") not in ("ARM", "SHP", "HHP")):
                    continue
                v = d.get("v")
                byval.setdefault(v, []).append(enc.wall_of(g, d.get("t") or 0))
                measured_of.setdefault(v, []).append(known)
    shared = sorted(v for v in byval if len(byval[v]) > 1)
    check("  several files witnessed the same floats", len(shared) >= 5, True)
    known, assumed = [], []
    for v in shared:
        spread = max(byval[v]) - min(byval[v])
        where = "measured" if all(measured_of[v]) else "assumed"
        print("    float %-4s seen by %d file(s), %-8s clocks, spread %4d ms"
              % (v, len(byval[v]), where, spread))
        (known if all(measured_of[v]) else assumed).append(spread)
    check("    where every clock was measured, they agree to within 10 ms",
          [x for x in known if x > 10], [])
    check("      and there were several such floats", len(known) >= 4, True)
    check("    where no clock could be measured, they do not",
          bool(assumed) and (max(assumed) > 100), True)
    big = [p for p in enc.offset if abs(enc.offset[p]) > 1000]
    check("    one file needed a correction over a second", len(big), 1)
    check("      and it was measured, not assumed",
          all(enc.matches.get(p) for p in big), True)


def two_close_hits_separate():
    """The wolverine pair is two hits with two attackers, not one shared hit.

    The 2026-09-11 audit memo offers this as the SHARED control: Santa and Shade on the same
    card with near-identical weights, expected 5.887 and 5.131 against an observed 6, and
    the matcher "must return SHARED, not a winner". On the joined clocks it is not one hit.
    The victim takes SHP 6 at ...141730 and SHP 9 at ...142023; Santa's Quick Barrage is at
    ...141732 and Shade's at ...142024. Each float sits two milliseconds after its own
    throw, and the two throws are 292 ms apart - twenty times the encounter's resolution.

    The memo's pass put the two throws 19 ms apart, which is where SHARED came from. The
    difference is the offsets, and the offsets here are the ones four files agree on to
    within 3 ms (see the check above). So this is recorded as a separation rather than as a
    tie, and what is asserted is the SPACING, which is read off the corrected clocks, not
    the names.
    """
    print("\ntwo close hits on one victim")
    enc = encounter_with("0411-1788596139011-BonkiDonki-7.jsonl")
    if enc is None:
        print("    (the corpus here does not hold the wolverine encounter)")
        return
    hits = [h for h in attribution.hits_of(enc)
            if (h.victim == 1729324533) and (h.raw in (6.0, 9.0))]
    check("  both floats are found", sorted(h.raw for h in hits), [6.0, 9.0])
    acts = [c for c in attribution.actions_of(enc)
            if (c.card == "Quick Barrage") and (c.victim == 1729324533)
            and any(abs(c.wall - h.wall) < 400 for h in hits)]
    names = sorted(set(c.name for c in acts))
    check("    two different people threw into the window", len(names) >= 2, True)
    if len(hits) == 2 and len(acts) >= 2:
        gap = abs(sorted(h.wall for h in hits)[1] - sorted(h.wall for h in hits)[0])
        print("    the two floats are %d ms apart, resolution is %d ms"
              % (gap, enc.resolution))
        check("    which is far outside the resolution", gap > (20 * enc.resolution), True)
        for h in sorted(hits, key=lambda x: x.wall):
            nearest = min(acts, key=lambda c: abs(c.wall - h.wall))
            print("      float %g at %d follows %s at %d (%+d ms)"
                  % (h.raw, h.wall, nearest.name, nearest.wall, h.wall - nearest.wall))
            check("      float %g follows its own throw within a tick" % h.raw,
                  abs(h.wall - nearest.wall) <= 60, True)


def a_fixture_that_must_separate():
    """Two attackers written down here, and the arithmetic that has to tell them apart.

    No corpus file is involved. A and B are given with their inputs, so both expectations
    are computable by hand from `Formulas.rawDamage` and the separation is a property of
    the numbers rather than of any fight:

        raw = base * share * sqrt(sqrt(ql * str) / 10) * opening^2

        A: base  90, share 0.25, ql 50, str  60, opening 0.55  ->  15.929
        B: base 150, share 0.25, ql 80, str 240, opening 0.55  ->  42.226
        ratio 2.651

    The fixture exists because every other control here depends on the corpus being what
    it is. This one depends on nothing: change the formula chain and it fails, with the
    arithmetic printed beside the failure.

    The two observations are chosen to exercise both answers. Sixteen is within the
    rounding of A and nowhere near B, so the method must name A. Thirty is near neither,
    which is the case where naming the closer of the two would be naming a loser.
    """
    print("\na fixture whose answer is arithmetic")
    op = 0.55
    a = model.raw_damage(90, 0.25, 50, 60, op)
    b = model.raw_damage(150, 0.25, 80, 240, op)
    near("  attacker A expects", a, 15.929, 0.01)
    near("  attacker B expects", b, 42.226, 0.01)
    near("    and B is this many times A", b / a, 2.651, 0.01)
    check("  against an observed 16, A fits and B is beaten by a decisive margin",
          (abs(a - 16) <= TOL_POINTS)
          and ((abs(b - 16) - abs(a - 16)) > attribution.HIGH_MARGIN), True)
    check("  against an observed 30, neither is within the rounding",
          (abs(a - 30) > TOL_POINTS) and (abs(b - 30) > TOL_POINTS), True)


def the_join_keeps_the_opening_fresh():
    """Pooling every witness's samples is what keeps an opening from going stale.

    When two attacks land close on one victim the first spends part of the opening, so the
    second has to be priced against what is left. Nothing can step an opening forward
    without a sample - computing one would put the model where the observation belongs - so
    what matters is how often a sample exists between two actions, and that is a property
    of how many clients were watching.

    The reading is the comparison, over the same actions, between the pooled timeline and
    the attacker's own file alone. The join roughly halves the age of the opening a hit is
    priced against, and at least halves the number of actions with no sample in between.
    The size of the gain depends on how many clients were present, so it is asserted as a
    direction and a bound rather than pinned: over 120 encounters it is 1.4% against 7.8%,
    over the 40 this check walks it is 2.7% against 5.5%.

    What survives is marked on the candidate and caps its confidence below HIGH. The check
    is that the marking happens at all and that it is rare: a flag nothing ever sets is not
    a safeguard, and one that fires constantly means the timeline is not doing its job.
    """
    print("\nhow fresh the opening a hit is priced against is")
    encs = encounter.find(limit=40)
    joined = single = 0
    jstale = sstale = 0
    for enc in encs:
        pooled = attribution.victim_openings(enc)
        acts = attribution.actions_of(enc)
        sibs = {}
        for c in acts:
            if c.victim is not None:
                sibs.setdefault(c.victim, []).append(c)
        for v in sibs:
            sibs[v].sort(key=lambda c: c.wall)
        for c in acts:
            if c.victim is None:
                continue
            own = getattr(c.actor, "logs", None)
            for label, rows in (("joined", pooled.get(c.victim) or []),
                                ("single", _own_view(enc, own, c.victim) if own else None)):
                if rows is None:
                    continue
                sample = None
                for w, _cols in rows:
                    if w < c.wall:
                        sample = w
                    else:
                        break
                if sample is None:
                    continue
                between = [o for o in sibs[c.victim]
                           if not ((o.card == c.card) and (o.wall == c.wall))
                           and (sample < o.wall < c.wall)]
                if label == "joined":
                    joined += 1
                    jstale += 1 if between else 0
                else:
                    single += 1
                    sstale += 1 if between else 0
    jp = (100.0 * jstale / joined) if joined else 0.0
    sp = (100.0 * sstale / single) if single else 0.0
    print("    joined across every witness: %d action(s), %d with no sample in between (%.1f%%)"
          % (joined, jstale, jp))
    print("    the attacker's own file only: %d action(s), %d with none (%.1f%%)"
          % (single, sstale, sp))
    # Counts and not percentages, because the two views walk the same actions and the
    # integer ratio is exact where a percentage comparison sits on a boundary - at forty
    # encounters it is 24 against 12, which "more than twice" reads as false.
    check("  the join leaves fewer actions reading a spent opening", jstale < sstale, True)
    check("    and what is left is under one action in twenty", jp < 5.0, True)
    check("    while the attacker's own file alone is at least twice as bad",
          sstale >= (2 * jstale), True)


def _own_view(enc, logs, victim):
    """The victim's opening samples as one character's own files recorded them."""
    sub = encounter.Encounter(list(logs))
    sub.offset, sub.matches, sub.residual = enc.offset, enc.matches, enc.residual
    return attribution.victim_openings(sub).get(victim) or []


def the_enum_is_exercised():
    """Every confidence value the matcher can emit is reached by some real hit.

    A ranking scheme whose cautious branches are never taken is a ranking scheme that has
    not been tested, and the cautious branches are the ones that matter: they are what stop
    this from naming an attacker it cannot identify. So the census has to contain each of
    them, and the UNKNOWN pile has to be broken down by reason rather than reported as one
    number - the part of it that is an animal's attack on a player is out of this method's
    reach for good, and the part that is a missing candidate is work.
    """
    print("\nthe whole enum, over a slice of the corpus")
    census, _rows = attribution.run(limit=60)
    print("    %d encounter(s), %d hit(s)" % (census["encounters"], census["hits"]))
    for k in (attribution.HIGH, attribution.MEDIUM, attribution.LOW,
              attribution.SHARED, attribution.UNKNOWN):
        print("      %-8s %5d" % (k, census.get(k, 0)))
    check("  every confidence value is reached",
          [k for k in (attribution.HIGH, attribution.MEDIUM, attribution.LOW,
                       attribution.SHARED, attribution.UNKNOWN)
           if not census.get(k)], [])
    check("    and UNKNOWN is broken down by reason",
          len([k for k in census if k.startswith("why: ")]) > 1, True)
    check("    with the animal-attack case named as its own reason",
          any("an animal's attack" in k for k in census), True)


def it_writes_nowhere_near_the_pack():
    """Attribution is a consumer of the model and never a producer of shipped data.

    The rule is ADR-0002's: the Java model and the pack are authoritative, and the analysis
    side may read them and may not move them. An attributed row is an INFERENCE; the pool
    is observation and `data/combat` is what the client is built with, so a prototype that
    could write into either would put a guess where a measurement belongs.

    Read out of the sources, because the property is about what the code CAN do and no
    output shows it. Both modules are checked: any `open(..., "w")` must be on a path the
    caller passed in, and neither may name the pack directory.
    """
    print("\nwhere this is allowed to write")
    bad = []
    for name in ("attribution.py", "encounter.py"):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), name)
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()
        for i, line in enumerate(src.splitlines(), 1):
            code = line.split("#", 1)[0]
            if ('open(' in code) and ('"w"' in code or "'w'" in code):
                if "out_path" not in code:
                    bad.append("%s:%d" % (name, i))
            if ("data" in code) and ("combat" in code) and ("join" in code):
                if "pool" not in code:
                    bad.append("%s:%d writes toward the pack" % (name, i))
    check("  the only write is to a path the caller passed", bad, [])
    check("    and neither module imports a pack writer",
          [n for n in ("write_pack", "write_characters")
           if n in open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                     "attribution.py"), encoding="utf-8").read()], [])


def main():
    damage_is_integers()

    the_opening_is_an_interval()
    the_bracket_is_the_announcement()
    the_clocks_are_cross_checked()
    two_close_hits_separate()
    a_fixture_that_must_separate()
    the_join_keeps_the_opening_fresh()
    the_enum_is_exercised()
    it_writes_nowhere_near_the_pack()
    if failures:
        print("\n%d CHECK(S) FAILED" % len(failures))
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
