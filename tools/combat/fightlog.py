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
GREEN, BLUE, YELLOW, RED = 0, 1, 2, 3

BOW_RES = frozenset({"huntersbow", "rangersbow"})

# Archery cards, by move name as logged. EMPTY: moves_sheet.json names no
# archery attack, and no true ranged log has been seen yet - logs -30 and -58
# hold a Hunter's bow but fight melee cards throughout (Quick Barrage x9,
# Full Circle, Take Aim; openings rose to 31 and 15), so a held bow is NOT a
# ranged fight. Populate from the first real archer log (Japeck/Pikapolonica);
# the BOW_RES pin in fightlog_check will demand it the moment pool logs arrive.
RANGED_MOVES = frozenset()


def is_ranged(rows):
    """Whether this log fought with ranged attacks.

    True when any of OUR move events names a card in RANGED_MOVES. Keyed on
    moves used, not the weapon held: a bow in the hands changes nothing about
    melee cards (logs -30/-58 prove it), while the damage channel is
    weapon-independent either way - damage taken from the foe and damage dealt
    both still show up and stay usable. Only the openings-gain inversions need
    a real ranged fight excluded, and only a ranged move marks one.
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

# A state event fires two to six milliseconds AFTER the move it follows and already
# carries that move's effect. So the state at or before a move's timestamp is the state
# that move read, and the next one is the state it produced. This slack absorbs the
# handful of milliseconds either way; it is not a settle window.
SLACK_MS = 60

# How far from a state showing an opening rise one of our moves may be and still be a
# candidate for having caused it. Generous on purpose: the point of the window is only to
# gather candidates, and it is the COLOUR test that decides between them.
ATTRIB_MS = 900


class Engagement(object):
    """One contiguous run of a log during which the same opponent was being sampled."""

    def __init__(self, gob):
        self.gob = gob
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

    def __repr__(self):
        return "<Engagement %s gob=%s %d states %d moves%s>" % (
            self.name, self.gob, len(self.states), len(self.moves),
            "" if self.clean else " PROBLEMS")


class Log(object):
    def __init__(self, path):
        self.path = path
        self.rows = []
        self.unparseable = 0
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

    for r in log.rows:
        ev = r.get("ev")
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
    for r in log.rows:
        ev = r.get("ev")
        if ev == "state":
            g = r.get("gob")
            if cur is None or cur.gob != g:
                cur = Engagement(g)
                log.engagements.append(cur)
            cur.states.append(r)
            cur.seq.append(r)
        elif ev == "predict":
            if cur is not None:
                cur.predictions.append(r)
        elif ev == "overlay":
            if cur is not None:
                cur.overlays.append(r)
        elif ev in ("move", "dmg"):
            if cur is None:
                # Events before the first state sample. Rare, but they belong to the
                # opponent the header names.
                cur = Engagement((log.header or {}).get("foegob"))
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

    if len(log.engagements) > 1:
        for eng in log.engagements:
            eng.multi_opponent = True
            eng.notes.append("the log retargets: %d opponents sampled in one file"
                             % len(log.engagements))

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


def opening_gains(eng):
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
        before, after = eng.brackets(m)
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
        before, after = eng.brackets(m)
        if before is None or after is None:
            continue
        bv, av = before.get(key), after.get(key)
        if not bv or not av:
            continue
        rose = [i for i in range(4) if av[i] > bv[i]]
        if not rose:
            continue
        if [i for i in rose if i not in can]:
            continue

        # Damage on the OPPONENT inside this window. Only meaningful for our own moves:
        # a foe's move damages us, and our own hitpoints are not drawn per hit.
        if mine and (me_gob is not None):
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
            lo, hi = before["t"], after["t"]
            actor = me_gob if mine else eng.gob
            inwin = [o for o in ols if lo <= o["t"] <= hi]
            if [o for o in inwin if o.get("gob") != actor]:
                continue
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
        for i in rose:
            out.append((m.get("actor"), name, COLOURS[i], bv[i], av[i] - bv[i]))
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
        near = [d for d in eng.damage
                if abs(d["t"] - m["t"]) <= PAIR_MS and d.get("gob") == target]
        chans = {}
        for d in near:
            chans[d["ch"]] = chans.get(d["ch"], 0) + d["v"]
        # The opening the attack READ, by file position - the same rule opening_gains
        # uses, and for the same reason. This used to take the last state stamped within
        # SLACK_MS AFTER the move, which lets the state that already contains the move's
        # own opening be read as the one it swung against. The damage term squares the
        # opening, so a one-step overshoot is not a small error: a badger's Quick Barrage
        # at a true 14% red was predicted against 28% and came out at 3.9 points where
        # the log recorded 1.
        before, _after = eng.brackets(m)
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
    pool_files = sorted(glob.glob(os.path.join(pool_dir, "*.jsonl")))
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
