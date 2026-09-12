"""Who threw the hit: expected-versus-observed fitting across a joined encounter.

THE PROBLEM IS STRUCTURAL AND THE ANSWER IS INFERENCE. `Gob.processDmg` decodes a damage
float for `this.id` only - victim, channel, value - so no damage row in any log carries an
attacker. In a duel that costs nothing, because there is only one candidate. In a party
fight it is the whole question, and `fightlog.hits` answered it by assuming the nearest of
our own moves did it, which credited an ally's hit to our card until the R1 veto started
dropping those pairings instead.

Dropping is not attributing. This module enumerates instead: every player who could have
thrown something into the window is a CANDIDATE, each candidate's expected raw damage is
computed from that character's own strength, weapon and quality through the Java formula
chain, and the observed integer decides between them. Where it cannot decide, the answer is
SHARED or UNKNOWN and no winner is named.

WHAT IT IS NOT. It is not a second forward model: every expectation goes through `model.py`,
which is the verified follower of `Formulas.java`, and ADR-0002 keeps Java authoritative. It
is not a pack writer and not a consumer of one: `estimate.collect`, the pack writers and
`estimate_check`'s golden assertions are untouched, so nothing here can move a shipped
number. It writes one JSONL of attributed rows, and only where it is told to.

THE THREE THINGS THAT MAKE IT WORK, each of which was a defect in the single-file readers:

  1. The cross-file join. An ally's strength is in the ally's own file. See encounter.py.
  2. The corrected clock. Two clients' timestamps differ by up to two seconds. Same place.
  3. The announcement-anchored bracket. A `state` row can already carry the move's own
     opening, so bracketing on the `move` row reads the opening the move just created. On
     the ant hit in Shade\\0740-1788545411918-Shade-36.jsonl that is the difference between
     an expectation of 22.96 and one of 8.27 against an observed 8.

CONFIDENCE IS THE OUTPUT, not a single name. Over the audit's 40-encounter sample, damage
fitting decided 84 of 112 competing clusters; ordering by corrected wall clock resolved 19
of the remaining 35; a core of ambiguous and floored hits stays SHARED or UNKNOWN, and
saying so is the point.
"""

import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import encounter  # noqa: E402
import fightlog  # noqa: E402

ROOT = encounter.ROOT

# How long before a hit a candidate action may have been thrown.
#
# The same 150 ms `fightlog` pairs damage over, kept deliberately identical: this module
# has to be comparable with the primitive it is replacing, and widening the window here
# would make it look better by admitting candidates the old reader never saw.
PAIR_MS = fightlog.PAIR_MS

# How long before its own `move` row a card's announcement overlay may sit.
#
# The announcement and the move row are two client messages for one server action:
# measured across 651 files the gap is p50 3 ms, p90 57 ms, max 60 ms - one server tick.
# Anything older than a tick is a different action and must not be used as this one's
# anchor.
ANNOUNCE_MS = 60

# How close two damage floats on one victim must be to be one hit.
#
# One millisecond, the same clustering `fightlog.soak_pairs` uses, because it is the same
# phenomenon: ARM and SHP for one hit are emitted together. It also merges two attackers
# whose floats land in the same millisecond, and that merge is irreversible - 18.2% of
# adjacent rows in a file already share a `t`. A merged hit is one observation and the
# honest answer for it is SHARED.
CLUSTER_MS = 1

# What counts as a close fit, and what counts as a decisive margin, in raw damage points.
#
# The channels are rounded independently by the client, so `round(ARM) + round(SHP)` can
# differ from `round(raw)` by a whole point in each direction: the Zzxcu Full Circle hit
# computes 109.488 and is logged as ARM 50 + SHP 60 = 110. One point per channel is
# therefore the floor under "agrees", not zero, and a margin has to clear several times the
# rounding before it is worth a name.
TOL = 1.0
HIGH_MARGIN = 3.0
MEDIUM_MARGIN = 1.0
SHARED_COST = 1.5

# Below this expected raw, no fit separates anything.
#
# A hit of one or two points is dominated by rounding: every candidate's expectation is
# near zero and so is the difference between them. Roughly 7% of clustered hits in the
# audit's sample were here. They are reported FLOORED rather than scored.
FLOOR_RAW = 2.0

HIGH, MEDIUM, LOW, SHARED, UNKNOWN = "HIGH", "MEDIUM", "LOW", "SHARED", "UNKNOWN"


class Hit(object):
    """One observed damage cluster on one victim, on the reference clock."""

    def __init__(self, victim, wall, arm, shp, hhp, witness):
        self.victim = victim
        self.wall = wall
        self.arm = arm
        self.shp = shp
        self.hhp = hhp
        self.witness = witness

    @property
    def raw(self):
        """ARM + SHP + HHP, which is the damage before armour - the quantity `raw` is.

        Summing the channels back up is what makes this comparable with an expectation
        without knowing the victim's armour, and animal armour is exactly what the pack
        does not know: `opponents.json` carries wolf's hard and soft as null with
        `identified` false. The per-channel split is a better test where it is available
        and is simply not available here.
        """
        return self.arm + self.shp + self.hhp

    def __repr__(self):
        return "Hit(victim=%s raw=%g)" % (self.victim, self.raw)


class Candidate(object):
    """One player's action that could have produced a hit, priced."""

    def __init__(self, actor, card, wall, victim, source):
        self.actor = actor            # Fighter
        self.card = card
        self.wall = wall              # reference clock, the anchor
        self.victim = victim          # gob, or None when the log did not say
        self.source = source          # "move" (our own row) or "overlay" (announcement)
        self.opening = None           # the combined opening it read, as a fraction
        self.colours = None           # the victim's four logged colours at the anchor
        self.expected = None          # (lo, hi) raw damage, or None when unpriceable
        self.cost = None
        self.stale = False            # another action landed after the opening was sampled
        self.why = []

    @property
    def mid(self):
        """The middle of the expected interval, for reporting only. Never for deciding."""
        return None if self.expected is None else (self.expected[0] + self.expected[1]) / 2.0

    def cost_against(self, observed):
        """How far `observed` falls outside this candidate's expected interval, in points.

        Zero inside the interval. The interval is what the inputs permit - the opening's
        print truncation, above all - so an observation inside it is as good a fit as the
        evidence can distinguish, and ranking two such candidates by their distance from
        some midpoint would be ranking on arithmetic rather than on evidence.
        """
        lo, hi = self.expected
        if observed < lo:
            return (lo - observed) / TOL
        if observed > hi:
            return (observed - hi) / TOL
        return 0.0

    @property
    def name(self):
        return self.actor.name

    def __repr__(self):
        return "Candidate(%s %s exp=%s)" % (self.name, self.card, self.expected)


class Attributed(object):
    """One hit, its ranked candidates and the confidence that ranking earns."""

    def __init__(self, hit, candidates, confidence, reasons):
        self.hit = hit
        self.candidates = candidates
        self.confidence = confidence
        self.reasons = reasons

    @property
    def best(self):
        return self.candidates[0] if self.candidates else None

    @property
    def margin(self):
        scored = [c for c in self.candidates if c.cost is not None]
        if len(scored) < 2:
            return None
        return scored[1].cost - scored[0].cost

    def row(self):
        """The record written to the output file.

        `expected_*` are MODEL values and are named so. Nothing downstream may read one as
        an observation: the whole reason attribution is possible is that the observations
        are integers the client drew, and the expectations are this project's arithmetic
        about them.
        """
        return {
            "victim": self.hit.victim,
            "wall": self.hit.wall,
            "observed_arm": self.hit.arm,
            "observed_shp": self.hit.shp,
            "observed_hhp": self.hit.hhp,
            "observed_raw": self.hit.raw,
            "confidence": self.confidence,
            "reasons": self.reasons,
            "witness": os.path.basename(self.hit.witness),
            "candidates": [
                {"attacker": c.name, "gob": c.actor.gob, "card": c.card,
                 "source": c.source, "wall": c.wall,
                 "model_expected_raw_lo": None if c.expected is None else c.expected[0],
                 "model_expected_raw_hi": None if c.expected is None else c.expected[1],
                 "cost": c.cost, "why": c.why}
                for c in self.candidates],
        }


def victim_openings(enc):
    """{victim gob: [(wall, [green, blue, yellow, red]), ...]} over the whole encounter.

    EVERY WITNESS, NOT JUST THE ONE WE ARE READING. A player samples the opponent they are
    currently targeting, so a victim three of the four are ignoring is sampled by one file
    and nobody else; and `foes` rows carry every relation's openings, including creatures
    this client never targeted. Pooling them on the corrected clock is what gives each hit
    an opening to be scored against at all.

    Sorted by corrected wall. Several clients sampling the same victim in the same
    millisecond simply contribute several entries; the reader takes the last one strictly
    before its anchor, so a duplicate costs nothing and a disagreement is resolved in
    favour of the most recent sample, which is the freshest thing anyone saw.
    """
    out = defaultdict(list)
    for g in enc.logs:
        for eng in g.engagements:
            for st in eng.states:
                foe = st.get("foe")
                if foe and (eng.gob is not None):
                    out[eng.gob].append((enc.wall_of(g, st.get("t") or 0), list(foe)))
        for row in (g.foes or []):
            w = enc.wall_of(g, row.get("t") or 0)
            for rel in (row.get("o") or []):
                if len(rel) >= 5:
                    out[rel[0]].append((w, [rel[1], rel[2], rel[3], rel[4]]))
    for k in out:
        out[k].sort(key=lambda r: r[0])
    return dict(out)


def openings_before(timeline, victim, wall):
    """The victim's four colours as last sampled strictly before `wall`, or None.

    STRICTLY BEFORE, and before the ANNOUNCEMENT rather than the move row. A `state` row
    two to six milliseconds after a move already carries that move's own effect, so a
    bracket that admits it reads the opening the attack created instead of the one it
    struck. That is not a small error: on the ant hit it turns an expectation of 8.27 into
    22.96 against an observed 8.
    """
    rows = timeline.get(victim)
    if not rows:
        return None
    best = None
    for w, cols in rows:
        if w < wall:
            best = cols
        else:
            break
    return best


def hits_of(enc):
    """Every observed damage cluster in the encounter, deduped across witnesses.

    ONE WITNESS PER VICTIM, the fullest whose clock is known. Several clients draw the
    same float, so pooling every file's damage rows would count one hit as many; and
    picking the reference log alone would lose every victim the reference was not near. So
    the rule is per victim - but a MEASURED clock outranks a fuller file, because a file
    whose offset could not be recovered is carried at zero and a zero that should have been
    1,682 ms puts every hit in it nearly two seconds from where it happened. A fuller file
    with an unknown clock is not better evidence; it is the same evidence in the wrong
    place. The reference log counts as measured, its offset being zero by construction.

    THE CORRECTION IS CROSS-CHECKED, and this is the strongest evidence in the module that
    the clocks are right. In the wolverine encounter four files independently record the
    wolverine's damage floats, and after correction - which moves one of them by 1,682 ms -
    they agree on each float to within a few milliseconds. Three carry all five; BonkiDonki
    joined late and carries four of them:

        float   BonkiDonki   Pikapolonica   Santa Samus   Shade (reference)
        SHP 74  -            ...138900      ...138901     ...138902
        SHP 2   ...140946    ...140944      ...140946     ...140945
        SHP 6   ...141731    ...141729      ...141730     ...141730
        SHP 9   ...142023    ...142021      ...142023     ...142023
        SHP 67  ...142923    ...142922      ...142928     ...142923

    Four clients do not agree to a few milliseconds on an offset that is wrong. The same
    encounter supplies the negative half: a sixth float lands after those files end and is
    recorded by three later ones, none of which shares enough announcements for an offset
    to be measured - and carried at zero they place it 129 ms apart. See
    attribution_check.the_clocks_are_cross_checked.
    """
    per = defaultdict(lambda: defaultdict(list))
    for g in enc.logs:
        for eng in g.engagements:
            for d in eng.damage:
                ch = d.get("ch")
                if ch not in ("ARM", "SHP", "HHP"):
                    continue
                gob = d.get("gob")
                v = d.get("v")
                if (gob is None) or not isinstance(v, (int, float)):
                    continue
                per[gob][g.path].append((enc.wall_of(g, d.get("t") or 0), ch, v))
    measured = set(p for p, n in enc.matches.items() if (n is None) or n)
    out = []
    for victim, bywit in per.items():
        witness = max(bywit, key=lambda p: (p in measured, len(bywit[p])))
        rows = sorted(bywit[witness])
        cur = None
        for w, ch, v in rows:
            if (cur is None) or ((w - cur["hi"]) > CLUSTER_MS):
                cur = {"w": w, "hi": w, "ARM": 0.0, "SHP": 0.0, "HHP": 0.0}
                out.append(Hit(victim, w, 0, 0, 0, witness))
                out[-1]._acc = cur
            cur["hi"] = max(cur["hi"], w)
            cur[ch] += v
    for h in out:
        h.arm, h.shp, h.hhp = h._acc["ARM"], h._acc["SHP"], h._acc["HHP"]
        del h._acc
    out.sort(key=lambda h: h.wall)
    return out


def actions_of(enc):
    """Every player action in the encounter, on the reference clock, deduped.

    TWO SOURCES, AND THEY OVERLAP. A character's own log records what that character threw
    as a `move` row with `actor: me` - the card named outright and the target named by
    `move.gob`, which is the strongest form this evidence takes. Every client near enough
    ALSO writes an `overlay` row on the actor's gob announcing the same card, which is the
    only trace another player's action leaves in a log that is not theirs.

    So the same server action can arrive up to four times. They are deduped on (actor,
    card, time within a server tick): the surviving row prefers the `move` form, because
    only that one names the victim.

    An announcement on a gob with no Fighter - a stranger, or a party member who was not
    logging - is deliberately NOT dropped. It becomes a candidate with no expectation,
    which is what turns a hit that used to be silently credited to our card into an honest
    UNKNOWN.
    """
    raw = []
    for g in enc.logs:
        me = (g.header or {}).get("megob")
        f = enc.fighters.get(me)
        ann = defaultdict(list)
        for o in (g.overlays or []):
            card = fightlog.overlay_move(o.get("res") or "")
            if card:
                ann[o.get("gob")].append((o.get("t") or 0, card))
        for k in ann:
            ann[k].sort()
        if f is not None:
            for eng in g.engagements:
                for m in eng.moves:
                    if m.get("actor") != "me":
                        continue
                    card = m.get("name")
                    if not card:
                        continue
                    t = m.get("t") or 0
                    anchor = t
                    for at, ac in ann.get(me, ()):
                        if (ac == card) and (0 <= (t - at) <= ANNOUNCE_MS):
                            anchor = min(anchor, at)
                    raw.append(Candidate(f, card, enc.wall_of(g, anchor),
                                         m.get("gob"), "move"))
        for gob, rows in ann.items():
            if gob == me:
                continue           # our own announcement; the move row above is better
            other = enc.fighters.get(gob)
            for t, card in rows:
                raw.append(Candidate(other or _Stranger(gob), card,
                                     enc.wall_of(g, t), None, "overlay"))
    return _dedupe(raw, enc)


class _Stranger(object):
    """A body that acted and has no log of its own.

    It cannot be priced - no strength, no weapon, no quality - so it can never win a
    comparison. It exists so that its presence is recorded: a hit with a stranger in the
    window is UNKNOWN, not our card's, and the old reader could not tell the difference.
    """

    def __init__(self, gob):
        self.gob = gob
        self.name = "unknown#%s" % gob
        self.str = None

    def raw(self, card, opening, wall):
        return None

    def combined_opening(self, card, colours):
        return None

    def opening_bounds(self, card, colours):
        return None

    def raw_bounds(self, card, colours, wall):
        return None


def _dedupe(cands, enc):
    """One row per server action, preferring the form that names a victim."""
    best = {}
    for c in sorted(cands, key=lambda x: (x.wall, x.source != "move")):
        key = None
        for k in list(best):
            gob, card = k[0], k[1]
            if (gob == c.actor.gob) and (card == c.card) and (abs(best[k].wall - c.wall) <= 60):
                key = k
                break
        if key is None:
            best[(c.actor.gob, c.card, c.wall)] = c
        elif (best[key].source != "move") and (c.source == "move"):
            best[key] = c
    return sorted(best.values(), key=lambda x: x.wall)


def price(cand, timeline, enc, victim):
    """Fill in the candidate's opening and expected raw damage, or say why not.

    `victim` is passed rather than read off the candidate because an ANNOUNCEMENT names no
    target: all a third client's overlay says is that this player threw this card at this
    moment. Scoring it against the hit's victim is a hypothesis about who it was aimed at,
    and it is recorded as one in `why` rather than promoted to a reading.

    The anchor stays on the reference clock all the way in; `Fighter` converts it, because
    only the Fighter knows which of that character's files was running at that moment. A
    person writes a new log every time their target changes, so one brawl is several files
    per person, and pricing an action against the wrong one reads both the gear and the
    clock off a file that had not started yet.
    """
    cols = openings_before(timeline, victim, cand.wall)
    if cols is None:
        cand.why.append("victim never sampled before this action")
        return
    cand.colours = cols
    op = cand.actor.combined_opening(cand.card, cols)
    if op is None:
        cand.why.append("card declares no attack type")
        return
    cand.opening = op
    exp = cand.actor.raw_bounds(cand.card, cols, cand.wall)
    if exp is None:
        cand.why.append("attacker's weapon or strength unresolved")
        return
    cand.expected = exp


def _mark_stale(cand, timeline, siblings):
    """Flag a candidate whose opening was sampled before somebody else already acted.

    THE OPENING HAS TO BE AS OF THIS ACTION, NOT AS OF THE WINDOW. When two attacks land
    close on one victim, the first changes the victim's openings; if no state row separates
    them, the second is priced against an opening that the first has already spent. The
    expectation is then too high, and it is too high in a way that looks like a bad fit
    rather than like a missing sample.

    THE CROSS-FILE JOIN IS MOST OF THE FIX, which is worth stating because it was not the
    intended one. Every witness samples the victim, so pooling their states leaves far less
    room between samples than any single file has. Measured over 120 encounters:

                            actions   stale    opening sample age
        joined              1,811     1.4%     p50 28 ms, p90 286 ms
        one file only       1,810     7.8%     p50 91 ms, p90 473 ms

    What is left is the 1.4% where even the pooled timeline has no sample between two
    actions. Those cannot be stepped forward - nothing recorded the intermediate state, and
    computing one would be substituting the model for the observation the whole method
    rests on - so they are marked and their confidence is capped. A fit that might be
    reading a spent opening is not a HIGH.
    """
    rows = timeline.get(cand.victim) or []
    sample = None
    for w, _cols in rows:
        if w < cand.wall:
            sample = w
        else:
            break
    if sample is None:
        return
    for other in siblings:
        if (other.card == cand.card) and (other.wall == cand.wall):
            continue
        if sample < other.wall < cand.wall:
            cand.stale = True
            cand.why.append("another action landed after this opening was sampled")
            return


def _for_hit(c, hit):
    """A fresh candidate for one hit, so that pricing never leaks between hits.

    The action objects are shared - one throw can sit in the window of several hits - and
    filling `expected` in place would let the first hit's victim and opening decide what
    every later hit sees. Each hit gets its own copy, priced against its own victim.
    """
    out = Candidate(c.actor, c.card, c.wall, hit.victim, c.source)
    if c.victim is None:
        out.why.append("announcement names no target; assumed at this victim")
    return out


def attribute(enc):
    """Every hit in the encounter, attributed as far as the bytes allow."""
    timeline = victim_openings(enc)
    actions = actions_of(enc)
    byvictim = defaultdict(list)
    for c in actions:
        if c.victim is not None:
            byvictim[c.victim].append(c)
    for v in byvictim:
        byvictim[v].sort(key=lambda c: c.wall)
    out = []
    for hit in hits_of(enc):
        window = []
        for c in actions:
            # THE ACTION MAY BE LOGGED AFTER ITS OWN DAMAGE. An action arrives as two
            # client messages - the announcement overlay and the `move` row - and the
            # damage float is a third; they are not ordered. On the ant hit the float is at
            # t=2543 and the move row at 2547. Requiring the anchor to precede the hit
            # therefore threw away most of the corpus's attributable hits: of 150
            # creature-victim hits in the first twelve encounters, 111 had no action in a
            # forward-only window and 74 of those had one within 25 ms the other way.
            # One server tick is the lead allowed, because all effects of one action land
            # on one tick and anything further out is a different action.
            if not (-ANNOUNCE_MS <= (hit.wall - c.wall) <= PAIR_MS):
                continue
            if (c.victim is not None) and (c.victim != hit.victim):
                continue
            k = _for_hit(c, hit)
            price(k, timeline, enc, hit.victim)
            _mark_stale(k, timeline, byvictim.get(hit.victim, ()))
            window.append(k)
        out.append(_decide(hit, window, enc))
    return out


def _decide(hit, window, enc):
    """Rank the candidates for one hit and choose the confidence that ranking earns.

    The order of these tests is the argument. Nothing is scored before the cases that
    cannot be scored are taken out, because a cost computed from a missing input is a
    number that looks like evidence.
    """
    reasons = []
    if not window:
        # NOT EVERY HIT IS A PLAYER'S. An animal attacking one of us produces a damage
        # float on a player exactly like ours produce one on the animal, and this method
        # structurally cannot fit it: an animal's strength, weapon and quality are never
        # logged by anybody. Both cases are UNKNOWN - the enum has no sixth value and
        # inventing one would suggest a distinction the output cannot support - but the
        # reason separates them, so a census can say how much of the UNKNOWN pile is work
        # left undone and how much is out of reach.
        why = ("victim is a player and no player acted: an animal's attack, which carries"
               " no attacker stats anywhere in the corpus"
               if hit.victim in enc.fighters else "no player action in the window")
        return Attributed(hit, [], UNKNOWN, [why])
    priced = [c for c in window if c.expected is not None]
    unpriced = [c for c in window if c.expected is None]
    if not priced:
        return Attributed(hit, window, UNKNOWN,
                          ["no candidate could be priced"] + sorted(
                              set(w for c in window for w in c.why)))
    for c in priced:
        c.cost = c.cost_against(hit.raw)
    priced.sort(key=lambda c: c.cost)
    ranked = priced + unpriced

    if max(c.expected[1] for c in priced) < FLOOR_RAW:
        return Attributed(hit, ranked, LOW,
                          ["every expectation is under %g raw: rounding dominates" % FLOOR_RAW])
    if unpriced:
        reasons.append("%d candidate(s) in the window could not be priced" % len(unpriced))

    close = [c for c in priced if c.cost <= SHARED_COST]
    margin = (priced[1].cost - priced[0].cost) if len(priced) > 1 else None

    # ORDERING IS A PARTITION, NOT A LIKELIHOOD. It may narrow a candidate set and it may
    # never outrank the damage score, never produce HIGH on its own, and never separate two
    # candidates closer together than the encounter's own measured resolution.
    if (len(priced) > 1) and (margin is not None) and (margin < MEDIUM_MARGIN):
        spread = priced[-1].wall - priced[0].wall
        res = enc.resolution
        if abs(spread) <= res:
            reasons.append("candidates within the %d ms clock resolution" % res)
            return Attributed(hit, ranked, SHARED, reasons)

    if len(close) > 1:
        reasons.append("%d candidates fit within %g raw" % (len(close), SHARED_COST))
        return Attributed(hit, ranked, SHARED, reasons)
    if (margin is None) and (priced[0].cost <= TOL) and not unpriced:
        if priced[0].stale:
            reasons.append("one candidate and it fits, but the opening may already be spent")
            return Attributed(hit, ranked, MEDIUM, reasons)
        reasons.append("one candidate, and it fits")
        return Attributed(hit, ranked, HIGH, reasons)
    if margin is None:
        reasons.append("one candidate, fitting to %.2f raw" % priced[0].cost)
        return Attributed(hit, ranked, LOW if priced[0].cost > 2.0 else MEDIUM, reasons)
    if (priced[0].cost <= TOL) and (margin >= HIGH_MARGIN) and not unpriced:
        if priced[0].stale:
            reasons.append("margin %.2f, but the opening may already be spent" % margin)
            return Attributed(hit, ranked, MEDIUM, reasons)
        reasons.append("margin %.2f over the runner-up" % margin)
        return Attributed(hit, ranked, HIGH, reasons)
    if (priced[0].cost <= 2.0) and (margin >= MEDIUM_MARGIN):
        reasons.append("margin %.2f over the runner-up" % margin)
        return Attributed(hit, ranked, MEDIUM, reasons)
    reasons.append("best fit %.2f, margin %.2f" % (priced[0].cost, margin))
    return Attributed(hit, ranked, LOW, reasons)


def run(paths=None, out_path=None, limit=None):
    """Attribute the whole corpus (or `limit` encounters) and return the census.

    `out_path` is written only when given, and a caller that gives one is expected to hand
    a scratch path: this never writes into `data/combat`, because an attributed row is an
    inference and the pool is observation.
    """
    encs = encounter.find(paths, limit=limit)
    census = defaultdict(int)
    rows = []
    for enc in encs:
        for a in attribute(enc):
            census[a.confidence] += 1
            census["hits"] += 1
            if a.confidence == UNKNOWN:
                for r in a.reasons:
                    census["why: " + r] += 1
            rows.append(a.row())
    census["encounters"] = len(encs)
    if out_path:
        with open(out_path, "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r) + "\n")
    return (census, rows)


def main(argv):
    out = None
    limit = None
    args = list(argv)
    while args:
        a = args.pop(0)
        if a == "-o":
            out = args.pop(0)
        elif a == "-n":
            limit = int(args.pop(0))
    census, _rows = run(out_path=out, limit=limit)
    print("encounters %d, hits %d" % (census["encounters"], census["hits"]))
    for k in (HIGH, MEDIUM, LOW, SHARED, UNKNOWN):
        n = census.get(k, 0)
        pct = (100.0 * n / census["hits"]) if census["hits"] else 0.0
        print("  %-8s %5d  %5.1f%%" % (k, n, pct))
    for k in sorted(k for k in census if k.startswith("why: ")):
        print("      %5d  %s" % (census[k], k[5:]))
    if out:
        print("wrote %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
