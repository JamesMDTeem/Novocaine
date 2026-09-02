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

Stdlib only, like everything else here.
"""

import json
import os

COLOURS = ("green", "blue", "yellow", "red")
GREEN, BLUE, YELLOW, RED = 0, 1, 2, 3

# A move and the damage it caused arrive as separate messages a few milliseconds apart,
# in either order, so the pairing window is symmetric.
PAIR_MS = 150

# A state event fires two to six milliseconds AFTER the move it follows and already
# carries that move's effect. So the state at or before a move's timestamp is the state
# that move read, and the next one is the state it produced. This slack absorbs the
# handful of milliseconds either way; it is not a settle window.
SLACK_MS = 60


class Engagement(object):
    """One contiguous run of a log during which the same opponent was being sampled."""

    def __init__(self, gob):
        self.gob = gob
        self.res = None
        self.states = []
        self.moves = []
        self.damage = []
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

    @property
    def clean(self):
        return not self.problems

    @property
    def offence_ok(self):
        """Whether what WE did to this opponent can be measured from it."""
        return (not self.others_present) and (self.third_party_rises == 0)

    @property
    def defence_ok(self):
        """Whether what this opponent did to US can be measured from it."""
        return (not self.others_present) and (not self.multi_opponent)

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
            if self.seq[j].get("ev") == "state":
                before = self.seq[j]
                break
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
        self.engagements = []
        # gob -> resource name, from every source in the file
        self.names = {}

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


def read(path):
    """Parse one log file into a Log. Never raises on bad content."""
    log = Log(path)
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                log.rows.append(json.loads(line))
            except ValueError:
                log.unparseable += 1

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
        elif ev == "foe" and r.get("res"):
            log.names[r["gob"]] = r["res"]

    _segment(log)
    _diagnose(log)
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


def _diagnose(log):
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

        rises = unattributed_rises(eng)
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


def unattributed_rises(eng):
    """Opening rises on the opponent, after our own first move, that no move of ours
    can account for.

    In a solo fight this is empty. When it is not, another player is attacking the same
    target, and every gain in the engagement is suspect - not only these, because a rise
    that happens to coincide with one of our own moves is then indistinguishable from
    ours.

    Rises before our first move are excluded deliberately: those are openings the
    opponent walked in with, which is a different fact and is reported by carried_in().
    """
    mine = sorted(m["t"] for m in eng.moves if m.get("actor") == "me")
    if not mine:
        return []
    out = []
    for a, b in zip(eng.states, eng.states[1:]):
        if b["t"] < mine[0]:
            continue
        for i in range(4):
            d = b["foe"][i] - a["foe"][i]
            if d <= 0:
                continue
            if not any(a["t"] - SLACK_MS <= t <= b["t"] + SLACK_MS for t in mine):
                out.append((b["t"], COLOURS[i], d))
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
        before = None
        for s in eng.states:
            if s["t"] <= m["t"] + SLACK_MS:
                before = s
            else:
                break
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


def read_all(paths):
    out = []
    for p in paths:
        if os.path.exists(p):
            out.append(read(p))
    return out
