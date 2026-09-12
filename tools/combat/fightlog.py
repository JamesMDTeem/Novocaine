"""Read a combat telemetry log and say what part of it can be trusted.

A log file is not one fight. The header names a single opponent, but the client samples
whichever relation is current, so a file can describe several creatures in sequence -
and the damage numbers in it belong to whoever happened to be on screen, which in a
group fight is other players. Nothing downstream can fit an opponent's stats correctly
without separating those cases first, and the failure mode is silent: an opening that
another player caused, attributed to our own last move, yields a defence weight that is
confidently wrong rather than obviously missing.

This module does that separation and nothing else. It fits nothing and infers nothing.

    import fightlog
    log = fightlog.read(path)
    for eng in log.engagements:
        if eng.clean:
            ...

Stdlib only (this module). The opening-decay fitter tools/combat/decay_fit.py is the one exception that requires scipy/numpy (see tools/combat/requirements.txt) for O(t)=O0*exp(-t/tau) fitting.
"""

import glob
import json
import os

COLOURS = ("green", "blue", "yellow", "red")

# One server tick, in milliseconds. "One tick is 0.06 seconds" (Sim.java). It is the
# outside limit on how far a card's announcement can sit from its own `move` row - every
# effect of one action lands on one tick - and measuring the two across 651 files gives
# p50 3 ms, p90 57 ms, max 60 ms, which is that limit exactly.
TICK_MS = 60
GREEN, BLUE, YELLOW, RED = 0, 1, 2, 3

BOW_RES = frozenset({"huntersbow", "rangersbow"})

# Archery cards, by move name as logged. EMPTY: moves_sheet.json names no
# archery attack, and no true ranged log has been seen yet - logs -30 and -58
# hold a Hunter's bow but fight melee cards throughout (Quick Barrage x9,
# Full Circle, Take Aim; openings rose to 31 and 15), so a held bow is NOT a
# ranged fight. Populate from the first real archer log (Japeck/Pikapolonica);
# the BOW_RES pin in fightlog_check will demand it the moment pool logs arrive.
# EMPTY, AND CORRECTLY SO. There is no ranged attack CARD in this game - shooting is its
# own action and never enters the fightview as a move - so no move name can mark a fight
# as ranged and this set has nothing to hold. Dodges and the rest of the defensive cards
# are thrown with a bow in hand like any other, which is why a bow in the gear list is not
# evidence of a ranged fight either.
#
# What a bow in the gear DOES mean is that the weapon was swapped during the fight, since
# the melee cards in the same log cannot be thrown with it. That costs those hits rather
# than corrupting them: gear is snapshotted at t=0, replay.weapon_of finds no melee weapon
# and skips every weapon-share move in the log. 333 logs hold a bow and they carry about
# twenty melee moves between them.
RANGED_MOVES = frozenset()


def is_ranged(rows):
    """Whether this log fought with ranged attacks. Always False - see RANGED_MOVES.

    Kept as the gate rather than deleted because the callers read better for it and
    because the question is a real one; the answer is simply that the game gives no move
    that could make it true. Keyed on moves used, not the weapon held: a bow in the hands
    changes nothing about melee cards (logs -30/-58 prove it), while the damage channel is
    weapon-independent either way - damage taken from the foe and damage dealt both still
    show up and stay usable.
    """
    # Accept a Log object (has .rows) or an iterable of row dicts.
    raw = getattr(rows, "rows", rows)
    for r in raw or []:
        if not isinstance(r, dict):
            continue
        if r.get("ev") != "move" or r.get("actor") != "me":
            continue
        if (r.get("name") or r.get("move")) in RANGED_MOVES:
            return True
    return False

# A move and the damage it caused arrive as separate messages a few milliseconds apart,
# in either order, so the pairing window is symmetric.
PAIR_MS = 150

# A state event usually fires two to six milliseconds AFTER the move it follows and already
# carries that move's effect. So the state at or before a move's timestamp is normally the
# state that move read, and the next one is the state it produced. THIS IS TRUE FOR THE
# ATTACKS - median 6 ms across the corpus - AND IT IS NOT A CLAIM ABOUT EVERY CARD. Dash is
# the counterexample: its "completely removes your slightest opening" clear lands about
# 364 ms after the move, not six. Of 270 throws that opened with a standing colour, 260
# record the lowest positive colour falling to 0 at a median gap of 364 ms, the histogram
# concentrated at 350-399 for 256 of them. So for Dash the state at or before the move does
# NOT yet carry the effect, and the "recording defect" this comment used to imply for it was
# a false alarm - the clear is simply slower than the bracket rule assumes. This slack only
# absorbs the handful of milliseconds either way; it is not a settle window.
SLACK_MS = 60

# How far from a state showing an opening rise one of our moves may be and still be a
# candidate for having caused it. Generous on purpose: the point of the window is only to
# gather candidates, and it is the COLOUR test that decides between them.
ATTRIB_MS = 900


class Engagement(object):
    """One contiguous run of a log during which the same opponent was being sampled."""

    def __init__(self, gob):
        self.gob = gob
        # Whether this file has already had an engagement with this same opponent. It makes
        # openings we find standing OURS rather than a stranger's - see attributed_gains.
        self.rejoined = False
        self.advice = []
        self.res = None
        self.states = []
        self.moves = []
        self.damage = []
        # Overlays on player bodies that fall inside this engagement. Time-scoped here
        # rather than read off the log, because attribution asks whether somebody else
        # acted inside ONE move's bracket, and a whole file's worth would reject
        # everything in any fight a player was ever seen in.
        self.overlays = []
        # Schema 8: what the model expected, written by the client at the moment the move
        # was thrown. Kept apart from `moves` because it is not something the game did - it
        # is what we believed the game was about to do, and conflating the two would let a
        # prediction be read as an observation.
        self.predictions = []
        # States and moves in the order the client wrote them, plus the position of each
        # move within it. See brackets() for why file order and not timestamps.
        self.seq = []
        self.order = {}
        # Facts that block a measurement, and facts merely worth knowing. Both are short
        # human-readable strings. Which measurements a problem blocks is decided by the
        # two properties below, because the two directions fail for different reasons:
        # our attacks on the opponent are spoiled by anyone else attacking it, and its
        # attacks on us are spoiled by there being another opponent attacking us.
        self.problems = []
        self.notes = []
        self.others_present = False
        self.third_party_rises = 0
        self.multi_opponent = False
        # Set when the writer shed lines or its drain thread died. Unlike the other three
        # this spoils BOTH directions at once, because what went missing is unknown: a
        # lost move breaks attribution of our gains, a lost damage number breaks what the
        # opponent did to us, and nothing in the file says which it was.
        self.lines_lost = False
        # Explicit fight outcome inferred from died() award + gst + HP trail, players
        # excluded. Surfaced as a field, not a silent gate change - see inferred_outcome().
        # Values: "killed", "fled", "player", "unknown". The gate truth tables do not read
        # this; it is reported alongside problems so a later reader can decide.
        self.outcome = "unknown"
        self.outcome_detail = ""

    @property
    def clean(self):
        return not self.problems

    @property
    def offence_ok(self):
        """Whether what WE did to this opponent can be measured from it."""
        return ((not self.lines_lost) and (not self.others_present)
                and (self.third_party_rises == 0))

    @property
    def defence_ok(self):
        """Whether what this opponent did to US can be measured from it."""
        return ((not self.lines_lost) and (not self.others_present)
                and (not self.multi_opponent))

    @property
    def t0(self):
        return self.states[0]["t"] if self.states else None

    @property
    def t1(self):
        return self.states[-1]["t"] if self.states else None

    @property
    def name(self):
        return (self.res or "?").split("/")[-1]

    def brackets(self, move):
        """The states immediately before and after a move, by position in the file.

        Not by timestamp. A state event usually lands two to six milliseconds after the
        move it follows, but not always - the ordering jitters either way by a few
        milliseconds, and any slack wide enough to absorb that is also wide enough to
        mistake the move's own result for the state it read. Both events are written by
        the same thread in the order they happen, so their order in the file is exact
        where their timestamps are merely close.

        Pairing on timestamps cost most of the corpus: nine consecutive attacks in one
        badger log yielded four usable gains instead of nine, and the four were the ones
        whose timing happened to fall the right side of the window.
        """
        i = self.order.get(id(move))
        if i is None:
            return (None, None)
        before = after = None
        for j in range(i - 1, -1, -1):
            ev = self.seq[j].get("ev")
            if ev == "state":
                before = self.seq[j]
                break
            if ev == "move":
                # Another move landed and no state was sampled between the two, so the
                # older state describes the world before BOTH of them. Taking it would
                # credit this move with the previous one's work.
                #
                # The forward search has always stopped here; the backward one did not,
                # and the asymmetry was invisible while whole engagements were being
                # discarded for contamination. It affects 144 of 2400 brackets, and it
                # inflates a gain rather than shrinking it - which is how an ant ended up
                # with a defence weight of 1 from a single 47-point Quick Barrage that a
                # listed 10% opening cannot produce.
                return (None, None)
        for j in range(i + 1, len(self.seq)):
            ev = self.seq[j].get("ev")
            if ev == "state":
                after = self.seq[j]
                break
            if ev == "move":
                # Another move landed first; this one's result is no longer separable.
                break
        return (before, after)

    def announced_before(self, move, me_gob=None):
        """The state this move ACTUALLY read, when its own announcement beat its `move` row.

        An action reaches the log as two client messages - the `gfx/fx/fight/` overlay that
        announces the card, and the `move` row that books it - a median of 3 ms apart and up
        to one server tick. A `state` row can land between them, and that state already
        carries the action's own effect. brackets() pairs on the `move` row, so when that
        happens it reads the world the move CREATED as the world the move read.

        It is rare and it is not small. Three of the corpus's 98 bracketed Opportunity
        Knocks uses move when the anchor does, and two of them are the difference between a
        card that did nothing and a card that did what its text says:

            0346-1788466272144-BonkiDonki-5        100 -> 100   becomes   73 -> 100
            BonkiDonki-1788646023805-BonkiDonki-147 100 ->  99   becomes   71 ->  99
            BonkiDonki-1789063767114-BonkiDonki-29   52 ->  52   becomes   55 ->  52

        The first two are held at an unknown card level, so they feed the pooled interval
        and not the per-level ones. The third is the corpus's only use of the card against
        a PERSON, and correcting its anchor does not rescue it: the opening was 55 and fell
        to 52 across the throw. That reading is an open question, not a measurement of the
        card - see estimate_check.opportunity_knocks.

        Returns brackets()'s `before` unchanged whenever there is no announcement, none
        inside a tick, or no state between the two - so a caller can use it everywhere and
        only the affected reads move. THE INTERVENING-MOVE STOP IS KEPT: walking back past
        another `move` row would credit this card with the previous one's work, which is the
        defect brackets() itself was fixed for, so the walk stops there and returns None.
        None therefore means "un-separable, do not use this read", exactly as it does from
        brackets(), and a caller must drop the observation rather than fall back to the
        state on the far side of the announcement - which is the contaminated one.

        Across the corpus 3,318 of 28,444 of our own bracketed moves anchor earlier than
        brackets() puts them, 937 of those change an opening colour by a point or more and
        180 by twenty or more; 77 are dropped as un-separable.

        MOST OF THE MOVED ANCHORS CHANGE NOTHING, and that is the recorder's restate()
        rather than luck. onMove calls restate(), which re-emits the last known state
        values with a FRESH timestamp, so the row sitting between an announcement and
        its move row is very often that re-emission: correct values, lying clock.
        Walking back past it lands on the genuine sample it was copied from, which is
        why 2,381 of the 3,318 read identically either way. The 937 that do differ are
        the ones where a real sample landed in the gap, and those are the whole point.
        """
        i = self.order.get(id(move))
        if i is None:
            return None
        before, _after = self.brackets(move)
        if before is None:
            return None
        t = move.get("t") or 0
        card = move.get("name")
        # WHOSE GOB THE ANNOUNCEMENT IS ON. Our own moves announce on us; a creature's
        # announce on the creature, and its `move` row carries the actor in `gob`. Getting
        # this backwards would look for our card on the opponent and simply never match,
        # which is a silent no-op rather than a visible failure.
        actor = me_gob if (move.get("actor") == "me") else (move.get("gob") or self.gob)
        if actor is None:
            return before
        anchor = None
        for o in self.overlays:
            if (o.get("gob") != actor) or (overlay_move(o.get("res") or "") != card):
                continue
            ot = o.get("t") or 0
            if 0 <= (t - ot) <= TICK_MS:
                anchor = ot if anchor is None else min(anchor, ot)
        if (anchor is None) or ((before.get("t") or 0) < anchor):
            return before
        for j in range(i - 1, -1, -1):
            ev = self.seq[j].get("ev")
            if ev == "move":
                return None
            if (ev == "state") and ((self.seq[j].get("t") or 0) < anchor):
                return self.seq[j]
        return None

    def __repr__(self):
        return "<Engagement %s gob=%s %d states %d moves%s>" % (
            self.name, self.gob, len(self.states), len(self.moves),
            "" if self.clean else " PROBLEMS")


class Log(object):
    def __init__(self, path):
        self.path = path
        self.rows = []
        self.unparseable = 0
        # Event names the reader has no branch for. Not an error - a log from a newer
        # client is still evidence for everything else it carries - but it is the one
        # symptom of a schema the reader has not caught up with, and it was invisible.
        self.unknown_events = {}
        self.header = None
        self.gear = []
        self.end = None
        # Schema 9: the writer's health as the fight closed. None on older logs,
        # which is unknown rather than clean - they predate the accounting.
        self.end_dropped = None
        self.end_failed = None
        self.engagements = []
        # gob -> resource name, from every source in the file
        self.names = {}
        # Schema 10 signals the client already had and never recorded: the party we
        # fought with, the agility bracket Fightsess narrows from attack cooldowns, the
        # weapon's own figures, and three server resources nothing consumes.
        # Overlays on player bodies - the only trace a log carries of another PLAYER's
        # move, since their moves never enter our fightview. Read here so attribution can
        # consult them; a rise beside one of these is not safely ours.
        self.overlays = []
        self.party = []
        self.agility = []
        self.weapons = []
        self.atkres = []
        # Schema 10 "hp" samples: a combatant's health as the SERVER states it, in
        # quarters. Everything else this file knows about hitpoints is accumulated from
        # damage numbers, which needs a kill to close and leaves survivors unbounded.
        self.health = []
        # Schema 4 "foes" samples: every relation's openings at a moment, not only the
        # sampled opponent's. Empty for every log written before that existed.
        self.foes = []
        # Schema 5 "buffs" samples: what each side is holding, stance included.
        self.buffs = []

    @property
    def me(self):
        return (self.header or {}).get("megob")

    @property
    def schema(self):
        return (self.header or {}).get("schema", 1)

    @property
    def complete(self):
        return self.end is not None

    def state_before(self, t):
        """The state a move at time t read - see SLACK_MS on why this is not the state
        immediately after it."""
        best = None
        for eng in self.engagements:
            for s in eng.states:
                if s["t"] <= t:
                    best = s
                else:
                    return best
        return best


def read(path, opens=None):
    """Parse one log file into a Log. Never raises on bad content.

    `opens` maps a move name to the set of colour indices it opens, from the move
    sheet. Supplying it makes contamination detection exact - see unattributed_rises.
    """
    log = Log(path)
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except ValueError:
                log.unparseable += 1
                continue
            if not isinstance(obj, dict):
                log.unparseable += 1
                continue
            log.rows.append(obj)

    known = ("begin", "gear", "end", "foe", "hp", "overlay", "party", "agi", "wpn",
             "atkres", "buffs", "foes", "state", "predict", "advice", "move", "dmg",
             "card")
    for r in log.rows:
        ev = r.get("ev")
        if ev not in known:
            log.unknown_events[ev] = log.unknown_events.get(ev, 0) + 1
        if ev == "begin":
            log.header = r
            if r.get("foegob") is not None and r.get("foeres"):
                log.names[r["foegob"]] = r["foeres"]
        elif ev == "gear":
            log.gear.append(r)
        elif ev == "end":
            log.end = r
            log.end_dropped = r.get("dropped")
            log.end_failed = r.get("failed")
        elif ev == "foe" and r.get("res"):
            log.names[r["gob"]] = r["res"]
        elif ev == "hp":
            log.health.append(r)
        elif ev == "overlay":
            log.overlays.append(r)
        elif ev == "party":
            log.party.append(r)
        elif ev == "agi":
            log.agility.append(r)
        elif ev == "wpn":
            log.weapons.append(r)
        elif ev == "atkres":
            log.atkres.append(r)
        elif ev == "buffs":
            # Schema 5. The buff resources standing on a combatant, which is where a
            # STANCE lives - the missing term in an opponent's defence weight.
            log.buffs.append(r)
        elif ev == "foes":
            # Schema 4. Every opponent's openings, including ones we never targeted - the
            # only evidence a log carries about another player's attacks, since their moves
            # never enter our fightview. Kept on the Log rather than an Engagement because
            # it spans them: a rise on a creature we are not fighting is what says a gain on
            # the one we ARE fighting may not be ours.
            log.foes.append(r)

    _segment(log)
    _diagnose(log, opens)
    return log


def _segment(log):
    """Split into engagements at every change of the sampled opponent.

    The sampled gob is carried on the state event itself, so this works on schema 2 logs
    that predate the foe event - those just cannot say what the other opponents were.
    """
    cur = None
    # Gobs we have already had an engagement with in this file. A fight that opens with the
    # opponent already carrying openings is suspect - somebody put them there and may still
    # be swinging - UNLESS that somebody was us, which is exactly what a target we have
    # fought before in the same log means. See the PRIOR test in attributed_gains.
    seen = set()
    for r in log.rows:
        ev = r.get("ev")
        if ev == "state":
            g = r.get("gob")
            if cur is None or cur.gob != g:
                cur = Engagement(g)
                cur.rejoined = g in seen
                seen.add(g)
                log.engagements.append(cur)
            cur.states.append(r)
            cur.seq.append(r)
        elif ev == "predict":
            if cur is not None:
                cur.predictions.append(r)
        elif ev == "advice":
            # What the model would have thrown, logged and not acted on - see
            # CombatEvent.advice. Kept apart from predictions: a prediction is about the
            # card a person chose and this is about the card they did not.
            if cur is not None:
                cur.advice.append(r)
        elif ev == "overlay":
            if cur is not None:
                cur.overlays.append(r)
        elif ev in ("move", "dmg"):
            if cur is None:
                # Events before the first state sample. Rare, but they belong to the
                # opponent the header names.
                cur = Engagement((log.header or {}).get("foegob"))
                cur.rejoined = cur.gob in seen
                seen.add(cur.gob)
                log.engagements.append(cur)
            (cur.moves if ev == "move" else cur.damage).append(r)
            if ev == "move":
                cur.order[id(r)] = len(cur.seq)
                cur.seq.append(r)
    for eng in log.engagements:
        eng.res = log.names.get(eng.gob)


def _diagnose(log, opens=None):
    """Record what each engagement can and cannot be used to measure.

    The two directions fail independently. Measuring what we did to an opponent is
    spoiled by anyone else attacking that opponent, because their openings and its
    damage are then not all ours. Measuring what it did to us is spoiled by there being
    a second opponent, because the openings on us then have more than one source.
    """
    me = log.me
    for eng in log.engagements:
        known = {me, eng.gob}
        strangers = {}
        for d in eng.damage:
            g = d.get("gob")
            if g not in known:
                strangers[g] = strangers.get(g, 0) + 1
        if strangers:
            eng.others_present = True
            eng.problems.append(
                "%d damage number(s) belong to %d gob(s) that are neither us nor this "
                "opponent - someone else was fighting here"
                % (sum(strangers.values()), len(strangers)))

        rises = unattributed_rises(eng, opens)
        if rises:
            eng.third_party_rises = len(rises)
            eng.problems.append(
                "%d opening rise(s) totalling %d points that no move of ours explains, "
                "after our own first move" % (len(rises), sum(r[2] for r in rises)))

        carried = carried_in(eng)
        if carried:
            eng.notes.append(
                "arrived already opened: %s - a previous engagement's work, not ours"
                % ", ".join("%s %d" % (c, v) for c, v in carried))

        if not [m for m in eng.moves if m.get("actor") == "me"]:
            eng.notes.append("we never attacked in this engagement")

        if eng.res is None:
            eng.notes.append("opponent never identified (no resource in the log)")

    # SIMULTANEOUS OPPONENTS, NOT TARGET SWITCHES. This used to fire only when the file
    # sampled more than one gob, so a crowd fought through a single sampled target - the
    # normal shape, and the one the defence gate most needs to catch - read as a duel.
    # The Shade wolf log is the case: a foes row carries six relations, the file samples
    # one engagement throughout, and both gates reported usable. Corpus-wide that is 51
    # files, 21 of them with foe moves from two or more gobs. The direct evidence is a
    # foes row carrying two or more relations; two distinct gobs throwing a foe move is
    # the fallback for logs that predate the event. Either sets it for every engagement
    # in the file, and target switching still counts, because it is the same defect seen
    # from another angle. Conservative on purpose: the loss is a measurement, the miss
    # is a wrong defence weight.
    foe_move_gobs = set()
    for eng in log.engagements:
        for m in eng.moves:
            if (m.get("actor") == "foe") and (m.get("gob") is not None):
                foe_move_gobs.add(m["gob"])
    crowd_rows = [r for r in log.foes if len(r.get("o") or []) >= 2]
    if crowd_rows or (len(foe_move_gobs) >= 2) or (len(log.engagements) > 1):
        why = []
        if len(log.engagements) > 1:
            why.append("%d opponents sampled in one file" % len(log.engagements))
        if crowd_rows:
            why.append("a foes row carries %d relations at once"
                       % max(len(r.get("o") or []) for r in crowd_rows))
        if len(foe_move_gobs) >= 2:
            why.append("%d distinct gobs threw a move" % len(foe_move_gobs))
        for eng in log.engagements:
            eng.multi_opponent = True
            eng.notes.append("more than one opponent: " + "; ".join(why))

    if not log.complete:
        for eng in log.engagements:
            eng.notes.append("no end event - the fight was cut off")

    # Lines the writer shed on a full queue. This is a PROBLEM and not a note, because a
    # dropped line is invisible in a way the other faults are not: a missing damage number
    # or state widens a bracket, and a missing MOVE lets its gain merge into a neighbour's
    # with nothing left behind to notice. Recording the count in the end event without
    # gating on it here would leave the file honest and the analysis unchanged, which is
    # the half-fix this exists to close.
    if log.end_dropped:
        for eng in log.engagements:
            eng.lines_lost = True
            eng.problems.append(
                "the writer shed %d line(s) on a full queue - an unseen move merges its "
                "gain into a neighbour's, so nothing in this file is safe to measure"
                % log.end_dropped)
    if log.end_failed:
        for eng in log.engagements:
            eng.lines_lost = True
            eng.problems.append(
                "the log writer's drain thread had already died - lines stopped reaching "
                "disk at an unknown point before the end")
    # Outcome inference and its explicit per-engagement field. This does NOT change any
    # gate verdict - offence_ok/defence_ok truth tables are untouched. It is reported
    # alongside problems so a reader can see "killed" vs "fled" vs "unknown" without
    # guessing, and because a later analysis that does gate on outcome can be judged
    # on this reading rather than on a silent redefinition.
    for eng in log.engagements:
        eng.outcome, eng.outcome_detail = _infer_outcome(eng, me, log.health)
        # Where lines were shed, every signal that would have arrived as a line is
        # suspect - damage, state, overlay, and by the same token the sfx outcome
        # sounds that say whether a swing connected. A file that lost lines and still
        # reports hit 2 / miss 1 is reporting a count that was truncated in the same
        # shed, so it is flagged here rather than left to read as a clean measurement.
        if eng.lines_lost:
            eng.notes.append(
                "sfx outcomes and outcome inference may have shed with the %d dropped "
                "line(s) - hit/miss counts and killed/fled reading are not trusted"
                % (log.end_dropped or 0))
        # Surface outcome as an explicit field consumed by problems/gates reporting,
        # without silently changing gate verdicts. The note is always emitted so the
        # field is visible even when it fails to decide.
        eng.notes.append("outcome: %s%s" % (
            eng.outcome, (" (%s)" % eng.outcome_detail) if eng.outcome_detail else ""))


def carried_in(eng):
    """Openings already standing on the opponent before we first attacked it.

    Not contamination. Auto-reaggro splits one engagement across two files, and the
    second file opens with everything the first one built - the fox log that starts at
    28% Off Balance and the badger log that starts at 60% Cornered are both this. The
    model reads whatever opening is standing, so these engagements stay measurable; the
    value simply did not come from us.
    """
    if not eng.states:
        return []
    first = [m["t"] for m in eng.moves if m.get("actor") == "me"]
    upto = first[0] if first else eng.states[-1]["t"]
    best = [0, 0, 0, 0]
    for s in eng.states:
        if s["t"] > upto:
            break
        for i in range(4):
            best[i] = max(best[i], s["foe"][i])
    return [(COLOURS[i], best[i]) for i in range(4) if best[i] > 0]


def unattributed_rises(eng, opens=None):
    """Opening rises on the opponent, after our own first move, that no move of ours
    can account for.

    In a solo fight this is empty. When it is not, another player is attacking the same
    target, and every gain in the engagement is suspect - not only these, because a rise
    that happens to coincide with one of our own moves is then indistinguishable from
    ours.

    Rises before our first move are excluded deliberately: those are openings the
    opponent walked in with, which is a different fact and is reported by carried_in().

    Neither position nor timing alone decides this, and both were tried. A move's effect
    can arrive a state late - one fox log shows the move, then a state with the opening
    still at zero, then the opening - so requiring the move to sit strictly between the
    two states rejects a perfectly good hit. And the move message can arrive AFTER its
    own effect, five milliseconds later in one boar log, so requiring it to precede the
    rise rejects another. Meanwhile a pure time window is useless in the case that
    matters: in a group fight another player's hits land within a few hundred
    milliseconds of ours anyway.

    What does decide it is colour. Our deck opens the colours it opens; a rise in any
    other colour cannot be ours, whoever it happened next to. `opens` supplies that -
    move name to the set of colour indices it opens, from the move sheet. Without it this
    falls back to proximity alone, which catches a fight we sat out entirely but not much
    else, and says so by flagging nothing it cannot prove.
    """
    mine = [m for m in eng.moves if m.get("actor") == "me"]
    if not mine:
        # Openings rose on an opponent we never touched, so whoever did it, it was not
        # us. The very first transition is exempt: an engagement's opening sample is
        # zeroes and the next one carries whatever the opponent walked in with, which is
        # every auto-reaggro fragment in the corpus and not a third party.
        return [(b["t"], COLOURS[c], b["foe"][c] - a["foe"][c])
                for a, b in zip(eng.states[1:], eng.states[2:])
                for c in range(4) if b["foe"][c] > a["foe"][c]]
    first = mine[0]["t"]
    out = []
    for a, b in zip(eng.states, eng.states[1:]):
        if b["t"] < first:
            continue
        near = [m for m in mine if abs(m["t"] - b["t"]) <= ATTRIB_MS]
        for c in range(4):
            d = b["foe"][c] - a["foe"][c]
            if d <= 0:
                continue
            if not near:
                out.append((b["t"], COLOURS[c], d))
            elif opens is not None and not any(
                    c in opens.get(m.get("name") or m.get("move"), set()) for m in near):
                out.append((b["t"], COLOURS[c], d))
    return out


def opening_gains(eng, me_gob=None):
    """Every (actor, move, colour, standing, gain) this engagement supports.

    The actor is first and is not optional. A gain our move caused measures the
    opponent's defence against our attack weight; a gain their move caused measures our
    defence against an attack weight we do not know. Returning the two without a label
    invites exactly one mistake, and it was made here: the opponent's own attacks were
    read back as evidence about the opponent's defence, which turned self-consistent
    fights into contradictory ones.

    Attribution is by the state pair that brackets the move. Only rises in the window a
    move actually spans are returned; a rise nothing explains is dropped rather than
    handed to the nearest move, which is the whole point of this module.
    """
    out = []
    for m in eng.moves:
        key = "foe" if m.get("actor") == "me" else "mine"
        # ANCHORED ON THE ANNOUNCEMENT. See Engagement.announced_before: a state landing
        # between a card's announcement and its `move` row already carries that card's
        # effect, so brackets()' `before` can be the world the move CREATED. It affects
        # 3,318 of 28,444 of our bracketed moves, and understates the gain every time.
        _b, after = eng.brackets(m)
        before = eng.announced_before(m, me_gob)
        if before is None or after is None:
            continue
        bv, av = before.get(key), after.get(key)
        if not bv or not av:
            continue
        for i in range(4):
            d = av[i] - bv[i]
            if d > 0:
                out.append((m.get("actor"), m.get("name") or m.get("move"),
                            COLOURS[i], bv[i], d))
    return out


# The farthest we have ever been from an opponent at the start of a move that then
# demonstrably landed a hit on it - 57.7 units over 1065 landed attacks, against a median
# of 12.7 and a 99th percentile of 34.3.
#
# The spread is not measurement noise: `dist` is read from the state BEFORE the move, and
# an attack closes the gap before it strikes, so a move begun far away still lands. That
# is why this is set at the observed maximum with room over it rather than at anything
# tighter - the question it answers is "could we possibly have caused this", not "were we
# in range at the moment it happened", and only the first is answerable from a log.
OUT_OF_REACH = 70.0


# Which card an overlay announces, DERIVED rather than guessed.
#
# A combat move shows a brief icon over whoever used it. Matching every overlay on our own
# body against the card we had just played gives a one-to-one map with no cross-talk at
# all: barrage follows Quick Barrage 153 times out of 153, fullcircle follows Full Circle
# 26 of 26, flex follows Take Aim 24 of 24, slide follows Quick Dodge 8 of 8. Not one
# resource follows two different cards.
#
# THE POINT OF HAVING THIS IS THE OTHER SIDE OF THE FIGHT. The recorder used to keep
# overlays only on player bodies, which meant an animal's announcement was discarded at
# the door - and an animal's announcement is what says WHICH of five ants acted, in the
# fights that have left twenty-one species with no measurement at all. From schema 12 the
# filter is on the overlay resource instead, so every combatant's is kept.
#
# Animals may use cards with no icon of their own, or share one. That is a question for
# the first corpus recorded after the change, not something to assume either way here.
OVERLAY_MOVE = {
    "gfx/fx/fight/barrage": "Quick Barrage",
    "gfx/fx/fight/fullcircle": "Full Circle",
    "gfx/fx/fight/cleave": "Cleave",
    "gfx/fx/fight/sting": "Sting",
    "gfx/fx/fight/oppknock": "Opportunity Knocks",
    "gfx/fx/fight/flex": "Take Aim",
    "gfx/fx/fight/slide": "Quick Dodge",
    "gfx/fx/fight/dash": "Dash",
    "gfx/fx/fight/jump": "Jump",
    "gfx/fx/fight/zigzag": "Zig-Zag Ruse",
}

# Outcome sounds, which arrive by the same path and are NOT move announcements. hit1 and
# miss say whether a swing connected, which nothing else in a log does; ip says a point of
# initiative was taken. They follow many different cards, which is how they were told
# apart from the icons above.
OVERLAY_OUTCOME = {
    "sfx/fight/hit1": "hit",
    "sfx/fight/miss": "miss",
    "sfx/fight/ip": "initiative",
}


def overlay_move(res):
    """The card an overlay resource announces, or None if it announces no card."""
    return OVERLAY_MOVE.get(res)


def overlay_outcome(res):
    """Whether an overlay is an outcome sound rather than a move announcement."""
    return OVERLAY_OUTCOME.get(res)


# Announcements (gfx/fx/fight/<slug>) name a card; outcome signals (sfx/fight/hit1,
# miss, ip) say whether a swing connected, which nothing else in a log records. The
# two are distinguished explicitly here - never decode an outcome signal as a card -
# so a new sfx consumer cannot reintroduce self-veto by treating a hit sound as a move.
PLAYER_RES = "borka"


def _infer_outcome(eng, me_gob, health):
    """Explicit fight-outcome inference, players excluded.

    Signals, in priority order:
      - died signal: #ffff (or C65535) award on a gob that is NOT the victim. The award
        draws on whoever won, so it sits on the winner rather than the dead creature.
        One such award landed on the OPPONENT's own gob in a fight we lost, so the
        non-victim test is not cosmetic. Players are excluded outright - beating one is
        a knockout, not a kill.
      - gst: foe flight olive branch (bit 2) plus a damage trail. Bit 2 alone is not a
        flight - moose etc. set it at zero damage because they never wanted the fight -
        so the trail is the second condition.
      - HP trail: quarters from the server (log.health), if any creature ever sends one.
        Currently none do - kept as the third signal so the field is future-proof.
    Returns (outcome, detail) where outcome is killed/fled/player/unknown. Detail is a
    short human-readable reason for the reading, not a verdict.
    """
    res = eng.res or ""
    if PLAYER_RES in res:
        return ("player", "opponent is a player - knockout not death")
    # Killed has to be checked before fled: a creature that dies also sets gst and the
    # award is the decisive signal, not the branch.
    has_award = any(d.get("ch") in ("#ffff", "C65535") and d.get("gob") != eng.gob
                    for d in eng.damage)
    if has_award:
        return ("killed", "#ffff on non-victim")
    # Flight needs both the bit and the trail.
    if any((s.get("gst") or 0) & 2 for s in eng.states):
        dmg = sum(d.get("v", 0) for d in eng.damage
                  if d.get("gob") == eng.gob and d.get("ch") == "SHP")
        if dmg > 0:
            return ("fled", "gst bit 2 + damage trail")
        return ("unknown", "gst bit 2 but no damage trail - not a flight")
    # HP quarters, if any - filtered to this opponent's gob.
    if health:
        qs = [h.get("q") for h in health if h.get("gob") == eng.gob and h.get("q") is not None]
        if qs and qs[-1] == 0:
            return ("killed", "HP quarters 0")
    return ("unknown", "no award, no flight, players excluded")


def _bracket_sfx(eng, move):
    """Outcome sounds inside one move's bracket, never confused with announcements.

    Returns dict with counts and which-swings-connected - counts of hit1/miss/ip
    whose timestamps fall within the bracket's state pair, plus a boolean for whether
    this swing produced an explicit hit or miss at all. An announcement is gfx/fx/fight/*
    and an outcome is sfx/fight/* - the two namespaces are disjoint and this function
    only counts the second.
    """
    before, after = eng.brackets(move)
    if before is None or after is None:
        return {"hits": 0, "misses": 0, "ips": 0, "connected": None}
    lo, hi = before["t"], after["t"]
    hits = sum(1 for o in eng.overlays if lo <= o["t"] <= hi and o.get("res") == "sfx/fight/hit1")
    misses = sum(1 for o in eng.overlays if lo <= o["t"] <= hi and o.get("res") == "sfx/fight/miss")
    ips = sum(1 for o in eng.overlays if lo <= o["t"] <= hi and o.get("res") == "sfx/fight/ip")
    connected = None
    if hits and not misses:
        connected = True
    elif misses and not hits:
        connected = False
    elif hits and misses:
        connected = None  # ambiguous - two sounds in one bracket, report counts not a boolean
    return {"hits": hits, "misses": misses, "ips": ips, "connected": connected}


def engagement_sfx(eng):
    """Every bracket in this engagement paired with its outcome sounds.

    Returns a list of (move_name, actor, bracket_t, counts_dict) plus aggregate counts.
    The per-bracket hit/miss facts are the only place a log records which swings
    connected - damage alone does not, because a miss produces no SHP and no ARM and so
    leaves no channel at all. An announcement overlay is NEVER counted here - see the
    namespace guard in _bracket_sfx.
    """
    rows = []
    agg = {"hits": 0, "misses": 0, "ips": 0, "brackets_with_hit": 0, "brackets_with_miss": 0}
    for m in eng.moves:
        c = _bracket_sfx(eng, m)
        agg["hits"] += c["hits"]
        agg["misses"] += c["misses"]
        agg["ips"] += c["ips"]
        if c["hits"]:
            agg["brackets_with_hit"] += 1
        if c["misses"]:
            agg["brackets_with_miss"] += 1
        rows.append((m.get("name") or m.get("move"), m.get("actor"), m["t"], c))
    return rows, agg


def sfx_coverage(logs):
    """Corpus coverage of the sfx outcome signals.

    Returns dict with engagements, with_sfx, total hit/miss/ip counts, and a short
    human-readable summary. Outcome sounds and announcements are counted separately - an
    sfx hit is not an announcement and an announcement is not a hit.
    """
    total = with_sfx = hits = misses = ips = 0
    with_hit = with_miss = 0
    per_eng = []
    for log in logs:
        for eng in log.engagements:
            total += 1
            h = sum(1 for o in eng.overlays if o.get("res") == "sfx/fight/hit1")
            mi = sum(1 for o in eng.overlays if o.get("res") == "sfx/fight/miss")
            ip = sum(1 for o in eng.overlays if o.get("res") == "sfx/fight/ip")
            if h or mi or ip:
                with_sfx += 1
            hits += h
            misses += mi
            ips += ip
            if h:
                with_hit += 1
            if mi:
                with_miss += 1
            per_eng.append((h, mi, ip))
    return {"engagements": total, "with_sfx": with_sfx,
            "hits": hits, "misses": misses, "ips": ips,
            "with_hit": with_hit, "with_miss": with_miss}


def foe_aggression(row):
    """Each relation's aggression state from a schema-11 `foes` row, as {gob: gst}.

    Empty for anything earlier, which is not the same as "everyone was aggressive": those
    logs recorded gst for the sampled opponent only, and the whole reason this exists is
    that a pack does not give up all at once.

    Bit 1 is our olive branch and bit 2 is theirs. Bit 2 alone does NOT mean the animal
    fled - moose, red deer and walrus in this corpus set it after taking zero damage,
    because they never wanted the fight - so a flight reading needs the damage trail too.
    """
    gobs = [r[0] for r in (row.get("o") or [])]
    gst = row.get("g") or []
    return dict(zip(gobs, gst))


def foe_range(row):
    """How far away each relation was, from a schema-16 `foes` row, as {gob: units}.

    Empty for anything earlier, and that emptiness is the finding rather than a gap to
    paper over: every log before 16 measured the distance to the SAMPLED opponent only, so
    a fight with five animals in it recorded the range to one of them. Three cards in the
    sheet hit "all other opponents in range" and no earlier log can say how many that was.

    -1 where the client could not tell, which is a relation whose gob had not arrived yet.
    Whole units, quantised at the recorder because the event is written behind a change
    gate and a raw distance moves every frame.
    """
    gobs = [r[0] for r in (row.get("o") or [])]
    dist = row.get("d") or []
    return {g: d for g, d in zip(gobs, dist) if d >= 0}


# Hand slots. The recorder writes a `gear` row for every equipment slot and a `wpn` row
# for slots 6 and 7 only - the two hands - so these are the slots a weapon can be in.
HAND_SLOTS = (6, 7)


def coolmod_hands(log):
    """Every hand resource in this fight that carries a weapon cooldown modifier.

    The `wpn` row is the item's own tooltip, read off the server, and one of its figures
    is Coolmod - the weapon's attack-cooldown modifier. It is written for both hands at
    the start of a fight and again whenever a hand changes, and until now NOTHING READ IT.

    Returns {resource: coolmod} for the resources whose modifier is not 1.0, empty when
    the fight held no such weapon. Corpus-wide that is 19 of 5,599 files and one item, the
    pickaxe at 1.15.
    """
    out = {}
    for w in (log.weapons or []):
        v = w.get("v") or {}
        cm = v.get("coolmod")
        res = w.get("res")
        if res and (cm is not None) and (abs(cm - 1.0) > 1e-9):
            out[res] = cm
    return out


def held_coolmod(log, t, mods=None):
    """Whether a cooldown-modifying weapon was in hand at `t`, as the largest modifier.

    None when no hand held one, which is the ordinary case.

    READ FROM `gear`, NOT FROM `wpn`. A `wpn` row is written only for an item that has
    weapon tooltips, so a hand that changes FROM a pickaxe TO a shield writes a `wpn` row
    for the other hand and nothing at all for this one - the same removal-blindness the
    `gear` writer was fixed for. `gear` carries every slot change including a removal (a
    null res), so the hands are reconstructed from it and the modifier is looked up by
    resource. Demonstrated in pool/Santa_Samus-1789072636788-Santa_Samus-62.jsonl: slot 6
    goes pickaxe -> roundshield at t=9265 with no `wpn` row of its own.

    WHAT THIS IS FOR, and what it is not. It says a modifier was held. It does not say
    whether the reported cooldown already carries it, and no caller may multiply or divide
    by it. THE MECHANIC IS NOT IN DOUBT - the owner states that a pickaxe lengthens the
    cooldown and that the sword is the ordinary one, and the item's own tooltip says 1.15 -
    but whether the number in the `move` row has it applied is, and the corpus says both
    things:

    FOR. In pool/Santa_Samus-1789072636788-Santa_Samus-62.jsonl the reported Quick Barrage
    cooldown falls 23 -> 20 against ONE greenooze gob at one card level and zero initiative,
    exactly when the hands go from two pickaxes to sword-and-shield. 23/20 is 1.15 to the
    digit. A gob's agility does not change mid-fight, so nothing else can produce it. This
    is also the only fight in the corpus that contains a mid-fight swap off a modifying
    weapon, which is the only configuration that can show the modifier at all.

    AGAINST. Over all 108 observations taken with a pickaxe in hand, the reported cooldown
    divided by the card's base gives 0.90, 0.95, 1.00, 1.025, 1.05, 1.075 and 1.15 - every
    one an ordinary agility factor except the three 1.15s, which are that one gob. Divide
    instead by 1.15 and they become 0.783, 0.826, 0.870, 0.891, 0.913, 0.935 and 1.00, of
    which 104 of 108 fall below the 0.9 floor that the other 21,081 observations in the
    corpus respect exactly. So in 105 observations the modifier is NOT in the number, and
    in 3 it is.

    Two things it is not: staleness in the `cd` field, which was tested - the first reading
    of a slice differs from a unanimous tail in 6 of 2,275 slices, and five of those six are
    Take Aim, whose cooldown really does climb - and a fast opponent, which the within-gob
    swap rules out.

    So the one honest use is to EXCLUDE an observation taken with one of these in hand.
    See estimate.agility_band, whose whole reading is that a slice at one card, level and
    initiative isolates the opponent's agility and nothing else. Settling it needs a
    deliberate test rather than more corpus: one opponent, one card, swapped weapons, which
    is what that greenooze fight accidentally was.
    """
    if mods is None:
        mods = coolmod_hands(log)
    if not mods:
        return None
    hands = {}
    for g in (log.gear or []):
        slot = g.get("slot")
        if slot not in HAND_SLOTS:
            continue
        if (g.get("t") or 0) > t:
            break
        hands[slot] = g.get("res")
    held = [mods[r] for r in hands.values() if r in mods]
    return max(held) if held else None


# How long after the state row that closes a bracket a further rise in the same
# colour is still the same rise finishing rather than a second cause. Only 13 of the
# corpus's 1667 late rises arrive this promptly; the rest are hundreds of milliseconds
# out and are somebody else's.
SETTLE_MS = 20


def attributed_gains(eng, opens, me_gob=None):
    """Gains that survive attribution PER OBSERVATION rather than per engagement.

    opening_gains() returns every gain a move brackets and leaves the caller to decide
    whether the engagement as a whole is trustworthy. That all-or-nothing gate throws away
    most of a busy world: sixteen of thirty-four boar engagements, twenty of twenty-two
    beelarva, and every bear fight in the corpus were discarded because SOMETHING else was
    happening in them - in several cases another player fighting a different animal
    nearby, which cannot affect what our sword did to our boar.

    A move's bracket is already exclusive of every move the log records, ours and the
    opponent's alike, because brackets() stops at one. What it cannot see is another
    PLAYER, whose moves are not in our fightview and so never reach the log at all. Two
    independent tests catch that, and an observation must pass both.

    COLOUR. Our deck opens the colours it opens. If any colour rose inside the bracket
    that this move does not open, something else acted in that window, and the rise in the
    colour it DOES open is no longer separable from that something. Reject the whole
    observation, not just the stray colour.

    DAMAGE. The client draws floating numbers over a creature for damage from any source.
    One move lands one hit, so two distinct hits inside one bracket means two attackers -
    and a hit inside the bracket of a move that deals no damage at all means the hit was
    not ours.

    OVERLAY. A player's combat move shows as a brief icon over their body, and that icon
    is the only trace a log carries of what somebody else did. One inside the bracket
    means another player acted in this window, whatever the colours and damage say.
    Adding this test is not free and it is not optional: without it, wolf and walrus both
    picked up defence weights out of fights another player was swinging in.

    REACH. An opponent we could not have touched is one we did not open. This is the only
    one of the four that does not require the third party to leave a trace: the other
    three all ask whether something VISIBLE happened - a stray colour, a damage number, an
    icon - and a hit that is fully soaked, thrown from off-screen, is none of those. Being
    out of reach is evidence about us, so it holds whether or not anyone else was seen.

    It rejects nothing in the corpus as it stands - 0 of 1126 attributed gains sit beyond
    OUT_OF_REACH - and that is stated rather than hidden. It is a guard against a case
    this corpus has not got yet, not a filter doing present work, and the number above is
    what a later reader needs in order to tell whether it has started to matter.

    THIS IS NOT TURN-BASED, and the bracket does not pretend otherwise. Both sides run
    their own clocks and cooldowns differ fourfold between Quick Barrage and Cleave, so
    cards land simultaneously often enough to matter: of 34537 consecutive move pairs the
    median gap is 1013 ms, but 8.6% of ours-then-theirs pairs and 6.3% of theirs-then-ours
    land within 20 ms of each other, against 1.5% for two cards from the same side.

    Most of those are already caught. Of 1477 cards of ours thrown within 20 ms of one of
    theirs, 910 are vetoed - by the overlay test, since their announcement falls inside our
    bracket, or by there being no state row between the two at all. The 567 that survive
    read 5.1% above 1.2 times the model against 2.5% for cards thrown clear of theirs, so a
    residual doubling remains. It is reported rather than gated: gating it would cost 567
    observations to remove perhaps fourteen bad ones, which is the trade this file has
    already refused twice.

    What survives is still not proof. A third party opening the SAME colour inside the
    same bracket, with no damage number and no overlay, is invisible to all three tests.
    The bias that leaves has a known direction, which is worth more than a false sense of
    safety: it can only ADD to a gain, so it makes an opponent's defence weight read LOW.
    An estimate that disagrees with a duel by reading lower is therefore suspect in a way
    one reading higher is not.

    VALIDATED AGAINST THE STRICT GATE. This used to run only on engagements that passed
    offence_ok, which threw away every observation in a fight that had anything else going
    on anywhere in it - and for twelve species the clean engagements turned out to be
    precisely the ones where we never attacked, so "fought plenty, measured nothing" was
    the corpus reporting a selection effect rather than a fact about the creatures.
    Running it everywhere is checked by the species that have BOTH: ants 10.3 against
    10.3, beeswarm 31.2 against 30.5, redants 22.0 against 20.8, warriorant 38.6 against
    36.2, sentinelbee 125.0 against 103.8, fox 61.0 against 72.3. Six of seven agree
    within 25%; the seventh is horse, at two observations each side.
    """
    out = []
    # HISTORY WE DID NOT SEE - UNLESS THE HISTORY WAS OURS. An engagement whose FIRST state
    # row already shows an opening on the opponent was under way before we started watching,
    # and whoever put those points there may still be swinging.
    #
    # Not when we had already fought this same gob earlier in the file. Then the standing
    # openings are ours, and the readings say so: gains from those engagements sit at 2.9%
    # above 1.2 times the model against 3.0% for fights that started at zero, while a first
    # sight of an already-opened opponent sits at 6.5%. Dropping the rejoins as well cost
    # 242 gains and bought nothing, and a wolf pack is nothing but rejoins - target
    # switching back and forth is how those fights are fought. None of the four tests below can see them: they
    # all ask what happened inside a bracket, and this is about what happened before any
    # bracket existed.
    #
    # It is the strongest single contamination signal in the corpus. Gains from such
    # engagements read more than 1.2 times what the model expects in 6.9% of 274 cases,
    # against 1.6% of 3042 from engagements that started at zero, and they are wider in
    # both directions - a tenth to ninetieth percentile of 0.77 to 1.14 against 0.89 to
    # 1.06.
    sts = getattr(eng, "states", None) or ()
    if sts and any(sts[0].get("foe") or ()) and not getattr(eng, "rejoined", False):
        return out
    # Move announcements only, and only somebody else's - see the OVERLAY test below.
    ols = [o for o in getattr(eng, "overlays", [])
           if overlay_outcome(o.get("res")) is None]
    for m in eng.moves:
        name = m.get("name") or m.get("move")
        can = opens.get(name)
        if can is None:
            # A move whose openings we do not know cannot attribute anything. Silence
            # here is the point: guessing would put an unmeasured move's rise on the
            # nearest known one.
            continue
        mine = (m.get("actor") == "me")
        key = "foe" if mine else "mine"
        # See opening_gains: the announcement, not the `move` row, is where this card's
        # own effect begins.
        _b, after = eng.brackets(m)
        before = eng.announced_before(m, me_gob)
        if before is None or after is None:
            continue
        bv, av = before.get(key), after.get(key)
        if not bv or not av:
            continue
        # A RISE STILL IN FLIGHT, and only that. One Flex against ants reads 23 three
        # milliseconds after the card and 51 eight milliseconds later with no event of any
        # kind between them, so the state row that closes a bracket can catch a value on
        # its way up. Where the very next state row lands within SETTLE_MS and reads
        # higher, it is the same rise finishing rather than a second cause.
        #
        # The window is what makes this safe. Taking the highest value reached before the
        # next MOVE instead - which sounds more principled, since decay only pulls an
        # opening down - takes the gross misses from 5 to 35: those later rises are mostly
        # separate events, arriving a median of 350 ms after the card.
        si = None
        for k2, st2 in enumerate(sts):
            if st2 is after:
                si = k2
                break
        if si is not None and (si + 1) < len(sts):
            nxt = sts[si + 1]
            gap = (nxt.get("t") or 0) - (after.get("t") or 0)
            nv = nxt.get(key)
            if nv and 0 <= gap <= SETTLE_MS and av and any(
                    nv[i2] > av[i2] for i2 in range(4)):
                inter = False
                idx0 = eng.order.get(id(m))
                if idx0 is not None:
                    for j2 in range(idx0 + 1, len(eng.seq)):
                        t2 = eng.seq[j2].get("t") or 0
                        if t2 > (nxt.get("t") or 0):
                            break
                        if eng.seq[j2].get("ev") == "move":
                            inter = True
                            break
                if not inter:
                    av = [max(av[i2], nv[i2]) for i2 in range(4)]
        rose = [i for i in range(4) if av[i] > bv[i]]
        if not rose:
            continue
        if [i for i in rose if i not in can]:
            continue

        # Damage on the OPPONENT inside this window. Only meaningful for our own moves:
        # a foe's move damages us, and our own hitpoints are not drawn per hit.
        if mine and (me_gob is not None):
            # AND THE GAP BEFORE IT. A hit on the target between the last bracket's close
            # and this one's open belongs to nobody we can name: our previous card had
            # already been scored by then, and ours has not landed yet. One ant fight has
            # such a hit at 3067 with our own Quick Barrage written at 3160 and the
            # bracket opening at 3140 - the gain read 35 against a model saying 17 to 23.
            #
            # Only the GAP, not the whole span back to the previous card. Widening the
            # window itself puts our own previous hit inside it and rejects one bracket in
            # five, 10423 attributed gains down to 8203.
            #
            # Two SHP rows sharing a bucket are still ONE hit for the count below. They
            # are not an AOE - 311 of 325 carry different values and 96% show no other gob
            # hit in the same instant - but which of two attackers they are cannot be told
            # from a log, and they occur about as often when nobody else is visible.
            idx = eng.order.get(id(m))
            gapfrom = None
            if idx is not None:
                for j in range(idx - 1, -1, -1):
                    if eng.seq[j].get("ev") == "move":
                        _pb, pa = eng.brackets(eng.seq[j])
                        gapfrom = pa["t"] if pa is not None else None
                        break
            if gapfrom is not None and gapfrom < before["t"]:
                if [d for d in eng.damage
                    if d.get("gob") == eng.gob
                    and gapfrom < d["t"] < before["t"]
                    and d.get("ch") in ("SHP", "HHP", "ARM")]:
                    continue
            lo, hi = before["t"], after["t"]
            groups = set()
            for d in eng.damage:
                if d.get("gob") != eng.gob:
                    continue
                if lo <= d["t"] <= hi and d.get("ch") in ("SHP", "HHP", "ARM"):
                    groups.add(d["t"] // 2)
            if len(groups) > 1:
                continue

        # SOMEBODY ELSE'S move announcement inside the bracket. Unlike the colour and
        # damage tests this catches a third party whose blow did no damage and whose
        # colour happens to match ours, which is the case the other two are blind to.
        #
        # "Somebody else's" is doing the work, and it used to say "any". An announcement
        # plays over WHOEVER USED THE MOVE, so our own Quick Barrage puts an icon on our
        # own body every time we throw one - and vetoing on that vetoed our own move using
        # its own announcement as the evidence against it. It threw away 450 brackets
        # against 69 genuine third-party ones.
        #
        # Outcome sounds are excluded further up for the same reason in reverse: hit1,
        # miss and ip say what happened to a swing, not that somebody swung, so they are
        # not evidence of a third party at all.
        if ols:
            # THE WINDOW OPENS AT THE PREVIOUS MOVE, NOT AT THE STATE ROW. An announcement
            # is a card being PLAYED; the opening it raises arrives afterwards. So a third
            # party who announced between the last card and ours has an effect still in
            # flight when our bracket opens, and testing only from `before` misses them by
            # the few milliseconds between the two.
            #
            # A group ant fight is the case. Two bodies both announce barrage at 1135, our
            # own Quick Barrage is written at 1144, and the bracket runs from a state row
            # after 1135 to one at 1153 - so the other player's announcement sits just
            # outside it while their red lands just inside. The gain read 49 where the
            # model says 27 to 40, and it is two players' Quick Barrage.
            idx = eng.order.get(id(m))
            lo = before["t"]
            if idx is not None:
                for j in range(idx - 1, -1, -1):
                    if eng.seq[j].get("ev") == "move":
                        lo = min(lo, eng.seq[j].get("t", lo))
                        break
            # Only the third-party test gets the wider window. The count test below is
            # about OUR OWN announcements, and ours from the previous card are supposed
            # to be back there - widening its window makes every second card of ours
            # look like two, which cost 382 observations to buy one miss.
            hi = after["t"]
            actor = me_gob if mine else eng.gob
            if [o for o in ols if lo <= o["t"] <= hi and o.get("gob") != actor]:
                continue
            inwin = [o for o in ols if before["t"] <= o["t"] <= hi]
            # OUR OWN announcement is this move's own, but only one of them can be.
            # Two means a second move of ours landed inside the bracket and the gain
            # belongs to both - which is what the blanket veto used to catch by
            # accident, and what dropping it entirely gave away.
            if len(inwin) > 1:
                continue
            named = [overlay_move(o.get("res")) for o in inwin]
            if [n for n in named if (n is not None) and (n != name)]:
                continue

        # Out of reach for the whole bracket. Only for our own moves: how far away we
        # stood says nothing about whether the opponent reached US.
        if mine:
            d0, d1 = before.get("dist"), after.get("dist")
            if (d0 is not None) and (d1 is not None) \
               and (min(d0, d1) > OUT_OF_REACH):
                continue
        # A CARD OF OURS FIRING BECAUSE WE WERE HIT. Parry's sheet reads "When attacked:
        # Openings: +10% Dizzy", and it is a block-weight card - it answers the opponent's
        # swing, so no move row is ever written for it and nothing in a log says whether
        # it was in the deck at all. Its rise lands in whatever bracket happens to be open.
        #
        # It is visible in aggregate, and only once our own gains are divided out. Taking
        # for each colour how often it rises in a state step where the opponent's blow
        # landed on us against how often it rises in a quiet step, over 855107 steps:
        # green 3x, yellow 0x, red 3x - and blue 80x. Comparing blue against the other
        # colours WITHOUT that normalisation says nothing, because our own Quick Barrage
        # opens red constantly; that comparison is what made this look like nothing.
        #
        # So when the opponent's blow resolved inside this bracket - our own openings are
        # higher at the far end than the near one - a rise on the opponent may be Parry's
        # and not this card's, and the two are not separable.
        if bv and av:
            ours = "mine" if mine else "foe"
            ob_, oa_ = before.get(ours), after.get(ours)
            if ob_ and oa_ and any(oa_[i] > ob_[i] for i in range(4)):
                continue
        for i in rose:
            out.append((m.get("actor"), name, COLOURS[i], bv[i], av[i] - bv[i]))
    return out


def _announcement_by_other(eng, me_gob, move):
    """A move announcement on a gob that is neither us nor this opponent, near the move.

    Damage is victim-keyed: the client draws a floating number over the creature hit and
    never records the attacker, so when somebody else's card announcement falls inside the
    same pairing window as our move, their numbers over the same target cannot be told
    from ours. The announcement is the only signal that says so, and it is decisive - the
    pairing is dropped, never reassigned. This is the damage half's per-observation test,
    the same one attributed_gains runs for openings.

    AN ANNOUNCEMENT IS A CARD ICON (gfx/fx/...), NOT AN OUTCOME SOUND. The namespaces are
    disjoint and overlay_outcome() is the existing classifier for the second, but it names
    only hit1/miss/ip and there are others (sfx/fight/antspit among them), so the gfx/fx/
    prefix is the guard that actually keeps every outcome sound out of the veto.
    """
    t = move.get("t")
    if t is None:
        return None
    for o in eng.overlays:
        ot = o.get("t")
        if (ot is None) or (abs(ot - t) > PAIR_MS):
            continue
        if o.get("gob") in (me_gob, eng.gob):
            continue
        res = o.get("res") or ""
        if overlay_outcome(res) is not None:
            continue
        if not res.startswith("gfx/fx/"):
            continue
        return o
    return None


def _cluster(rows):
    """Damage rows grouped into hits: one hit is a run of rows within a millisecond.

    ARM and SHP for one blow are emitted together on the same millisecond, which is
    what makes them safe to group without going near the move list - the same rule
    soak_pairs uses. Two attackers whose floats land in the same millisecond are one
    group and cannot be separated; that is a real limit, not this function's choice.
    """
    out = []
    cur = None
    for d in sorted(rows, key=lambda r: r["t"]):
        if (cur is None) or ((d["t"] - cur[-1]["t"]) > 1):
            cur = [d]
            out.append(cur)
        else:
            cur.append(d)
    return out


def hits(eng, me_gob):
    """Every attack in this engagement paired with the damage it did.

    Returns dicts with the move, who threw it, the opening it read in each colour, and
    the SHP/HHP/ARM numbers that landed with it. `soaked` is the ARM channel, which the
    client draws as the armour's share - so ARM + SHP is the damage before armour, which
    is the figure the model predicts.
    """
    out = []
    for m in eng.moves:
        target = eng.gob if m.get("actor") == "me" else me_gob
        # SOMEBODY ELSE ANNOUNCED INSIDE THIS WINDOW. Their damage over the same target
        # is indistinguishable from ours, so the pair is not made at all - see
        # _announcement_by_other. Dropping it loses an observation; keeping it would
        # credit a stranger's hit to our card, which is the "numbers weirdly" report.
        if _announcement_by_other(eng, me_gob, m) is not None:
            near = []
        else:
            near = [d for d in eng.damage
                    if abs(d["t"] - m["t"]) <= PAIR_MS and d.get("gob") == target]
        # ONE HIT, NOT EVERY FLOAT IN THE WINDOW. This summed every damage row on the
        # target within 150 ms into a single number and compared it with a single
        # expectation. A hit is a cluster of channels on one millisecond - the same
        # grouping soak_pairs uses, and for the same reason - and a window can hold
        # more than one: 893 of 18,523 paired moves do, median 71 ms apart.
        #
        # They are not one card striking twice. 741 of the 893 have no second `move`
        # row in the window at all, and the rate is flat across cards - 5.8% for Quick
        # Barrage, 4.6% for Full Circle, 2.3% for Fell Scratch - where a multi-strike
        # card would be near 100%. They are somebody else's hits on the same victim,
        # the ones _announcement_by_other cannot see because no announcement of theirs
        # reached this log.
        #
        # So the pairing takes the cluster nearest the move and leaves the rest. Over
        # the 753 such windows where a prediction is computable, the root-mean-square
        # error of predicted against observed damage is 66.0 summing the window and
        # 26.1 taking the nearest cluster. `clusters` carries how many there were, so
        # a consumer can tell a clean pairing from one that had company.
        #
        # Isolated over the whole corpus by swapping only this grouping: summing the
        # window hands cards 552,201 points of raw damage where the nearest cluster
        # hands them 520,838 - 6.0% of all attributed damage belonged to somebody
        # else - and credits 50 blows against us to foe cards that did not land them.
        groups = _cluster(near)
        chosen = min(groups, key=lambda g: abs(g[0]["t"] - m["t"])) if groups else []
        chans = {}
        for d in chosen:
            chans[d["ch"]] = chans.get(d["ch"], 0) + d["v"]
        # The opening the attack READ, by file position - the same rule opening_gains
        # uses, and for the same reason. This used to take the last state stamped within
        # SLACK_MS AFTER the move, which lets the state that already contains the move's
        # own opening be read as the one it swung against. The damage term squares the
        # opening, so a one-step overshoot is not a small error: a badger's Quick Barrage
        # at a true 14% red was predicted against 28% and came out at 3.9 points where
        # the log recorded 1.
        # AND BY ITS OWN ANNOUNCEMENT, not its `move` row. The two are separate
        # client messages a median of 3 ms apart, and a state landing between them
        # already carries the card's effect - so the move row's `before` can be the
        # world the move made. That is the same one-step overshoot described just
        # above, arriving by a different route, and it matters here for the same
        # reason: the damage term SQUARES the opening. The audit's worked case is an
        # ant in pool/Shade/0740-1788545411918-Shade-36.jsonl, where the move row
        # reads red 55 and the announcement reads 33 - an expectation of 22.96
        # against 8.27, on an observed 8.
        #
        # 937 of 46,665 bracketed moves read a different opening for it, and 896 of
        # those read LOWER - median 7 points, down to 34 - which is the inflation
        # coming out. replay.py is the control, because its damage half owes nothing
        # to any fitted quantity: over its clean gated set the root-mean-square error
        # of predicted against observed damage falls from 6.788 to 5.416 points, at a
        # cost of 8 of 3,997 observations dropped as un-separable.
        before = eng.announced_before(m, me_gob)
        if before is None:
            continue
        key = "foe" if m.get("actor") == "me" else "mine"
        out.append({
            "t": m["t"],
            "actor": m.get("actor"),
            "move": m.get("name") or m.get("move"),
            "openings": list(before[key]),
            "shp": chans.get("SHP", 0),
            "hhp": chans.get("HHP", 0),
            "soaked": chans.get("ARM", 0),
            "raw": chans.get("ARM", 0) + chans.get("SHP", 0),
            "clusters": len(groups),
            "ip_before": before.get("myip" if m.get("actor") == "me" else "foeip"),
        })
    return out


def soak_pairs(eng):
    """Every hit this opponent took, as (absorbed, through), whoever threw it.

    The client draws its floating numbers over a creature for damage from ANY source, not
    only ours - the bear log carries thirty of them for a fight this character sat out
    entirely. So armour can be measured in a group fight exactly as well as in a duel:
    the ratio of absorbed to through is a property of the armour and says nothing about
    the attacker.

    ARM and SHP for one hit are emitted together, on the same millisecond, which is what
    makes them safe to pair without going near the move list. Pairing armour observations
    through our own moves instead - as the first version did - both threw away every
    group fight and risked matching our ARM against somebody else's SHP.

    A hit with no SHP was absorbed entirely, and its ARM is the raw damage rather than
    the armour's capacity. Those are kept, because they are the only hits that land
    inside the soft-soak ramp and so the only ones that can ever separate hard from soft.
    """
    # Collect per-gob ARM/SHP rows sorted by timestamp, then cluster by gap <= 1 ms.
    # The previous t//2 bucketing is equivalent for even/odd pairs but splits an
    # ARM at 98933 and SHP at 98934 (different buckets) while joining 98932+98933.
    # Clustering by sorted gap is boundary-independent and matches the stated 1 ms slack.
    rows = []
    for d in eng.damage:
        if d.get("gob") != eng.gob:
            continue
        ch = d.get("ch")
        if ch not in ("ARM", "SHP"):
            continue
        t = d.get("t")
        if not isinstance(t, int):
            try:
                t = int(t)
            except (TypeError, ValueError):
                continue
        v = d.get("v")
        if not isinstance(v, (int, float)):
            continue
        rows.append((t, ch, v))
    rows.sort(key=lambda r: r[0])
    clusters = []
    cur = None
    for t, ch, v in rows:
        if cur is None or t - cur["hi"] > 1:
            cur = {"t": t, "hi": t, "ARM": 0, "SHP": 0}
            clusters.append(cur)
        cur["hi"] = max(cur["hi"], t)
        cur[ch] += v
    out = []
    for c in clusters:
        if (c["ARM"] + c["SHP"]) > 0:
            out.append({"t": c["t"], "soaked": c["ARM"], "shp": c["SHP"],
                        "raw": c["ARM"] + c["SHP"]})
    return out


def find_log_dirs(root=None):
    """Every directory on this machine that a client writes combat logs into.

    There is more than one, and finding that out the hard way costs a session: fights
    recorded through the Steam Workshop copy land under
    steamapps/workshop/content/<app>/<item>/CombatLogs, nowhere near the checkout, and a
    tool pointed only at bin/CombatLogs reports a corpus that has quietly stopped
    growing. Two mornings of fights sat unnoticed in the Steam directory for exactly this
    reason.

    Returns existing directories only, most recently written first, so the newest corpus
    leads.
    """
    if root is None:
        root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                            "..", ".."))
    out = [os.path.join(root, "bin", "CombatLogs"),
           os.path.join(root, "Release", "CombatLogs")]

    libs = []
    try:
        import winreg
        for hive, key, name in (
                (winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam", "SteamPath"),
                (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam",
                 "InstallPath")):
            try:
                with winreg.OpenKey(hive, key) as k:
                    libs.append(winreg.QueryValueEx(k, name)[0])
            except OSError:
                pass
    except ImportError:
        pass
    libs.append(r"C:\Program Files (x86)\Steam")

    for lib in libs:
        vdf = os.path.join(lib, "steamapps", "libraryfolders.vdf")
        roots = [lib]
        if os.path.exists(vdf):
            try:
                with open(vdf, "r", encoding="utf8", errors="replace") as f:
                    for line in f:
                        if '"path"' in line:
                            parts = line.split('"')
                            if len(parts) >= 4:
                                roots.append(parts[3].replace("\\\\", os.sep))
            except (OSError, ValueError):
                pass
        for r in roots:
            out.extend(glob.glob(os.path.join(r, "steamapps", "workshop", "content",
                                              "*", "*", "CombatLogs")))

    seen, dirs = set(), []
    for d in out:
        real = os.path.normcase(os.path.abspath(d))
        if real in seen or not os.path.isdir(d):
            continue
        seen.add(real)
        dirs.append(d)

    def newest(d):
        try:
            files = glob.glob(os.path.join(d, "*.jsonl"))
        except OSError:
            return 0
        try:
            return max((os.path.getmtime(f) for f in files), default=0)
        except OSError:
            return 0

    dirs.sort(key=newest, reverse=True)
    return dirs


def default_logs(root=None):
    """Every combat log this machine has, from every install. (paths, dirs)."""
    dirs = find_log_dirs(root)
    paths = []
    for d in dirs:
        paths.extend(sorted(glob.glob(os.path.join(d, "*.jsonl"))))
    # Pooled corpus pulled from the team server by tools/combat/sync_pool.py.
    # Missing dir = no files, no error, so fresh checkouts are unaffected.
    if root is None:
        root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                            "..", ".."))
    pool_dir = os.path.join(root, "data", "combat", "pool")
    # Recursive on purpose. sync_pool.py writes the pool flat, but a pool that has been
    # reorganised by hand - one directory per character is the obvious way to do it, and
    # it has happened - would otherwise go completely unseen: a single-level glob returns
    # nothing, default_logs silently falls back to the local logs only, and every
    # estimator downstream reports numbers from a fraction of the corpus without saying
    # so. Reading both layouts costs nothing and removes a silent-wrong-answer mode.
    pool_files = sorted(glob.glob(os.path.join(pool_dir, "**", "*.jsonl"), recursive=True))
    if pool_files:
        # Dedup against local files by fightId stem. Pool filenames carry a
        # characterId- prefix (sanitized to [A-Za-z0-9_-]), so a pool copy and
        # its local original must count ONCE. Compare suffix after hyphen boundary:
        # pool stem "char-fightId" endswith "-localStem" means same fightId.
        local_stems = set(os.path.splitext(os.path.basename(p))[0] for p in paths)
        deduped = []
        for pf in pool_files:
            stem = os.path.splitext(os.path.basename(pf))[0]
            # pool stem equals local stem, or ends with "-localStem"
            is_dup = False
            if stem in local_stems:
                is_dup = True
            else:
                for ls in local_stems:
                    if stem == ls or stem.endswith("-" + ls):
                        is_dup = True
                        break
            if not is_dup:
                deduped.append(pf)
        # Sorted already; extend after dedup to keep overall sorted order per source
        paths.extend(deduped)
    return paths, dirs


def read_all(paths, opens=None):
    out = []
    for p in paths:
        if os.path.exists(p):
            out.append(read(p, opens))
    return out
