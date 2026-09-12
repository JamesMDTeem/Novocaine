"""One fight as several characters saw it: the cross-file join, and the clocks.

THE CORPUS IS NOT ONE LOG PER FIGHT. It is N per-character views of the same fight, and
every consumer until now looped over files in isolation and folded the other witnesses in
as duplicate noise. A four-character wolf encounter at wall ~1788623864 exists in four
files carrying the same party gobs; each file's own `state.gob` is that player's current
target, each file's own header carries that player's attributes and gear, and nothing
joined them.

This module does the join and nothing else. It answers three questions:

    who was in this fight          -> encounters(paths)
    what were they able to do      -> Fighter, one per character, from their own header
    when did each of them act      -> offsets(), in one reference clock

It reads. It does not score, attribute, or write; that is attribution.py, which is built
on this. Neither touches the pack writers or estimate.collect - attribution is a new
consumer of the model, never a second forward model, and never a second pack.

WHY THE CLOCKS NEED WORK. Every row carries `System.currentTimeMillis() - t0`, stamped in
the handler that took the event (CombatRecorder.now), and `t0` is per fight per client. So
two clients' `t` are not comparable, and neither are their `wall + t`: in the wolf
encounter the offsets against the reference log are Santa Samus -452 ms and ZzxcuV3
+157 ms. A 150 ms pairing window applied across that skew pairs the wrong things or
nothing. The offsets are recoverable because the clients SEE EACH OTHER: a card
announcement is an overlay on the actor's gob, and every client near enough writes its own
row for the same server event. Matching those gives the offset, and the spread of the
matched residuals gives the RESOLUTION - how far apart two actions must be before their
order is readable at all. Measured over 40 encounters: residual p50 1 ms, p90 6 ms, p95
10 ms; per-encounter worst pair p90 8 ms, max 25 ms.
"""

import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fightlog  # noqa: E402
import model  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

# How far apart two fights' `begin.wall` may be and still be one encounter.
#
# Eight seconds. The clients do not start their logs together - a fight begins for each
# player when that player is drawn into it - and the observed skew inside one encounter is
# hundreds of milliseconds before any correction. Eight seconds is wide enough for a
# straggler joining a fight already underway and narrow enough that two separate fights
# against the same respawned gob do not merge; the shared-gob test below does the rest of
# the work.
JOIN_MS = 8000

# How close a matched announcement must be to the running offset estimate to count.
#
# A barrage repeats about every 1.3 s, so nearest-matching two clients' announcement
# streams without a window happily pairs one throw with the NEXT one and reports an offset
# out by a repeat period: the loose estimator's residual p90 is 1265 ms, the tight one's
# is 6 ms. 40 ms is a little over half a server tick - wide enough to admit the true
# correspondence after a rough offset, far short of any repeat.
MATCH_MS = 40

# The width of the window the rough offset is taken from, and how many matched
# announcements make an offset a measurement rather than a coincidence.
#
# 250 ms is the width of the window the true correspondences have to fall inside TOGETHER,
# not a bound on the offset: the offset itself runs to nearly two seconds in this corpus,
# but its spread after correction is single-digit milliseconds. 250 ms is therefore far
# wider than that spread and far short of a card's ~1.3 s repeat period, so one window holds
# every true pair and no alias. Five matches is the floor under calling it measured: with
# one or two, a median has no spread to report and hands back a residual of zero, which
# reads as a perfect clock and is the most misleading shape this can take.
ROUGH_BIN_MS = 250
MIN_MATCHES = 5

# The floor under a reported resolution.
#
# `t` is integer milliseconds and 18.2% of adjacent rows in a file share one, so nothing
# finer than a millisecond survives to be read even within a single client. A recovered
# offset whose residuals are all zero does not mean two clients are in perfect step; it
# means the measurement bottomed out.
RESOLUTION_FLOOR_MS = 1


class Fighter(object):
    """One character's ability to do damage, read from that character's own logs.

    THIS IS THE HALF A SINGLE LOG CANNOT HAVE. A damage float is victim-keyed with no
    attacker on it, so the only way to ask "could this have been Santa?" is to know Santa's
    strength, weapon and quality - and those live in Santa's own header and gear rows, in
    Santa's own file. That is the whole reason this module exists.

    ONE CHARACTER, SEVERAL FILES. A log ends when the sampled opponent changes, so one
    person in one brawl writes several of them - the wolverine encounter has four from
    BonkiDonki and three from Shade inside twenty-two seconds. Holding a single log per
    character and letting the last one win prices an action from 14:02 against the gear
    timeline of the file that started at 14:09, and reads its timestamps on that file's
    clock. Every file the character wrote in this encounter is kept, and each question is
    answered from the file that was running when it was asked.

    `str` comes from the header's `attr` and not `attrb`: `attr` is the computed, buffed
    value the server is actually fighting with, which is what Combatant reads on the Java
    side.
    """

    def __init__(self, enc, logs, weapons, moves):
        self.enc = enc
        self.logs = sorted(logs, key=lambda g: (g.header or {}).get("wall") or 0)
        h = self.logs[0].header or {}
        self.name = h.get("char")
        self.gob = h.get("megob")
        self._weapons = weapons
        self._moves = moves
        self.party = set()
        for g in self.logs:
            for row in (g.party or []):
                for x in (row.get("gobs") or []):
                    self.party.add(x)

    @property
    def path(self):
        return self.logs[0].path

    @property
    def str(self):
        """Strength as the most recent header states it. See at().

        One value rather than one per file, because the interesting per-file quantity is
        the gear and the clock; attributes move between sessions, not inside twenty-two
        seconds, and a caller asking for the strength of an action already has the file.
        """
        return ((self.logs[-1].header or {}).get("attr") or {}).get("str")

    def at(self, wall):
        """(log, own-clock time) for a reference-clock instant, or (None, None).

        The file that was running at `wall` if one was, and otherwise the last file that
        started before it - a gap between two of a character's logs is a moment nobody
        recorded, and the freshest thing said about their gear is still the last file's.
        """
        best = None
        for g in self.logs:
            t = self.enc.own_time_in(g, wall)
            if t < 0:
                continue
            end = ((g.end or {}).get("t") or 0)
            if (best is None) or (t <= end) or (best[1] > t):
                best = (g, t)
            if t <= end:
                return (g, t)
        return best if best else (None, None)

    def strength_at(self, wall):
        g, _t = self.at(wall)
        if g is None:
            return None
        return ((g.header or {}).get("attr") or {}).get("str")

    def weapon_at(self, wall):
        """(base damage, quality) in hand at a reference-clock instant, or None.

        Base damage is the WIKI TABLE's, not the `wpn` row's tooltip figure. They are not
        the same number and the difference is a factor of two: BonkiDonki's sword tooltips
        224 where the table base is 90, and Prediction.java records that using the tooltip
        "cost a factor of two". The tooltip row is still read - for armpen, which the table
        does not carry for every weapon - just not for this.
        """
        g, t = self.at(wall)
        if g is None:
            return None
        found = None
        for row in (g.gear or []):
            if (row.get("t") or 0) > t:
                break
            res = (row.get("res") or "").split("/")[-1]
            name = _weapon_res().get(res)
            if name and self._weapons.get(name):
                found = (self._weapons[name], row.get("ql"))
        return found

    def armpen_at(self, wall):
        """The weapon's own armour penetration at `wall`, or None.

        Read off the `wpn` row rather than joined from the wiki: four of the twenty-six
        weapons have no penetration in the table at all, and the item has been carrying the
        figure the whole time. Only the ARM/SHP split depends on it; `raw` does not, which
        is why a missing value is not fatal to an attribution.
        """
        g, t = self.at(wall)
        if g is None:
            return None
        best = None
        for w in (g.weapons or []):
            if (w.get("t") or 0) > t:
                break
            v = w.get("v") or {}
            if v.get("armpen") is not None:
                best = v["armpen"]
        return best

    def raw(self, card, opening, wall):
        """Expected raw damage for this character throwing `card` into `opening`.

        `opening` is the combined opening over the card's OWN attack types, as a fraction.

        None when this character could not have thrown it at all - no such card, no weapon
        resolved for a weapon card, no strength in the header. A None here is what makes a
        candidate UNKNOWN rather than a bad fit; the two must not be confused.

        mu IS DELIBERATELY ABSENT. It appears in Sim.land, never in Sim.strike, so the deck
        level a card was held at does not enter a damage expectation taken from an OBSERVED
        opening. That is what makes this usable at all: card levels are unknown for about
        16% of the corpus and it does not matter here.
        """
        m = self._moves.get(card)
        strength = self.strength_at(wall)
        if (m is None) or not strength:
            return None
        share, flat = m.get("damage_share"), m.get("damage_flat")
        if share:
            wep = self.weapon_at(wall)
            if (not wep) or (not wep[1]):
                return None
            base, ql = wep[0], wep[1]
        elif flat:
            base, share, ql = flat, 1.0, strength
        else:
            return None
        return model.raw_damage(base, share, ql, strength, opening)

    def combined_opening(self, card, colours):
        """The card's own combined opening from a victim's four logged colours, or None.

        `colours` is the raw `state.foe` / `foes` quadruple in whole points. This is the
        LOWER end of what those points mean; see opening_bounds.
        """
        b = self.opening_bounds(card, colours)
        return None if b is None else b[0]

    def opening_bounds(self, card, colours):
        """(lo, hi) for the card's combined opening. An opening is an interval, not a point.

        A colour reaches the log through `(int)(100 * ameteri)`, which TRUNCATES, so a
        printed 68 means a true value anywhere in [0.68, 0.69). Raw damage goes as the
        square of the combined opening, so that one point of print resolution is worth
        about 3% of the expectation - 106.34 to 109.24 on the Zzxcu Full Circle hit whose
        observation is 110. Treating the printed number as exact turns that hit into a
        miss by 3.7 points and the interval turns it into a fit.

        It is not a nicety. The whole method rests on comparing an expectation with an
        integer the client drew, and if the expectation is quoted tighter than its inputs
        allow then every comparison is overconfident in the same direction. A candidate is
        priced as the interval its inputs permit, and the cost is the distance from the
        observation to that interval.

        Both ends come from the same combination: `1 - prod(1 - o_i)` over the card's own
        attack colours, evaluated at the bottom and at the top of each colour's cell. 100
        is its own ceiling and does not step past itself.
        """
        m = self._moves.get(card)
        if m is None:
            return None
        idx = dict((c, i) for i, c in enumerate(fightlog.COLOURS))
        lo, hi = [], []
        for t in (m.get("attack_types") or []):
            i = idx.get(t.get("colour"))
            if (i is not None) and (i < len(colours)):
                c = colours[i]
                lo.append(c / 100.0)
                hi.append(min(100, c + 1) / 100.0)
        if not lo:
            return None
        return (model.combined(lo), model.combined(hi))

    def raw_bounds(self, card, colours, wall):
        """(lo, hi) expected raw damage, or None. See opening_bounds and raw."""
        b = self.opening_bounds(card, colours)
        if b is None:
            return None
        a = self.raw(card, b[0], wall)
        if a is None:
            return None
        return (a, self.raw(card, b[1], wall))

    def __repr__(self):
        return "Fighter(%s gob=%s str=%s files=%d)" % (
            self.name, self.gob, self.str, len(self.logs))


# estimate.py owns the resource-basename-to-weapon-name map, and it is loaded lazily so
# that importing this module does not pull in the whole estimator.
_WEAPON_RES = {}


def _weapon_res():
    global _WEAPON_RES
    if not _WEAPON_RES:
        import estimate
        _WEAPON_RES = dict(estimate.WEAPON_RES)
    return _WEAPON_RES


class Encounter(object):
    """One fight, and every file that witnessed it.

    `ref` is the reference clock: the file with the most rows, because the offsets and the
    ordering are only as good as the witness they are measured against, and a client that
    was present but idle records almost nothing. One log in the wolf encounter carries 125
    `state` rows and zero moves, damage or overlays.
    """

    def __init__(self, logs):
        self.logs = list(logs)
        self.ref = max(self.logs, key=lambda g: len(g.rows))
        self.offset = {}
        self.matches = {}
        self.residual = {}
        self.fighters = {}

    @property
    def chars(self):
        return sorted(set((g.header or {}).get("char") or "?" for g in self.logs))

    def wall_of(self, log, t):
        """`t` in this log, expressed on the reference clock.

        The offset is added, not subtracted: offset[path] is (reference wall - this file's
        wall) for the same server event, so adding it moves this file's timeline onto the
        reference's.
        """
        base = (log.header or {}).get("wall") or 0
        return base + t + self.offset.get(log.path, 0)

    def own_time_in(self, log, wall):
        """A reference-clock instant, back on one file's own clock.

        The inverse of wall_of. Every timeline inside one file - gear, `wpn`, states - is in
        that file's own milliseconds, so anything compared against them has to come back
        into that frame first.
        """
        base = (log.header or {}).get("wall") or 0
        return wall - self.offset.get(log.path, 0) - base

    @property
    def resolution(self):
        """How far apart two actions must be before their order is readable, in ms.

        The worst matched-announcement residual over the file pairs in this encounter, at
        the 90th percentile, floored at one millisecond. Two candidate actions closer than
        this are simultaneous as far as the bytes are concerned and must stay SHARED; the
        matcher may not order them however tempting the difference looks.
        """
        worst = RESOLUTION_FLOOR_MS
        for r in self.residual.values():
            worst = max(worst, r)
        return worst

    def __repr__(self):
        return "Encounter(%s, %d files, res %d ms)" % (
            ",".join(self.chars), len(self.logs), self.resolution)


def headers(paths):
    """(path, header) for each log, reading only the first line of each file.

    The encounter join has to look at every file in the corpus and the corpus is 5,599
    files; parsing them all to group them would cost more than everything downstream. The
    header carries wall, char, megob and foegob, which is the whole join key.
    """
    out = []
    for p in paths:
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                line = f.readline()
        except OSError:
            continue
        try:
            h = json.loads(line)
        except ValueError:
            continue
        if isinstance(h, dict) and (h.get("ev") == "begin"):
            out.append((p, h))
    return out


def group(paths, join_ms=JOIN_MS):
    """Files grouped into encounters, as lists of paths. Solo fights are not returned.

    THE KEY IS A SHARED OPPONENT GOB PLUS PROXIMITY IN WALL TIME. A gob id is unique to one
    object in one session, so two files naming the same `foegob` are looking at the same
    animal; the wall window is what stops two separate fights against a long-lived gob from
    being welded together. Requiring at least two distinct characters is what makes this an
    encounter rather than one player's own consecutive fights.
    """
    by_gob = defaultdict(list)
    for p, h in headers(paths):
        g = h.get("foegob")
        if g is None:
            continue
        by_gob[g].append((h.get("wall") or 0, h.get("char"), p))
    out = []
    seen = set()
    for g, rows in by_gob.items():
        rows.sort()
        i = 0
        while i < len(rows):
            j = i + 1
            while (j < len(rows)) and ((rows[j][0] - rows[j - 1][0]) <= join_ms):
                j += 1
            block = rows[i:j]
            chars = set(c for _w, c, _p in block)
            if len(chars) >= 2:
                key = tuple(sorted(p for _w, _c, p in block))
                if key not in seen:
                    seen.add(key)
                    out.append(list(key))
            i = j
    return out


def _announcements(log):
    """(wall-relative t, gob, res) for every card announcement in a log.

    Announcements only - `gfx/fx/fight/<card>` - and never the outcome sounds. A `miss` or
    a `hit1` follows many different cards and fires on far more gobs, so matching on those
    would pair unlike events and inflate the residual the offset is gated on.
    """
    out = []
    for o in (log.overlays or []):
        res = o.get("res") or ""
        if not fightlog.overlay_move(res):
            continue
        out.append((o.get("t") or 0, o.get("gob"), res))
    return out


def estimate_offset(ref, other, match_ms=MATCH_MS):
    """(offset_ms, n_matched, residual_p90) putting `other`'s clock on `ref`'s.

    Two passes, and the second one is the point.

    THE FIRST PASS IS A DENSITY PEAK, NOT A MEDIAN. Matching two clients' announcement
    streams on `(gob, res)` alone offers several plausible partners for every throw, because
    a barrage repeats about every 1.3 seconds, so the difference list is the true offset
    surrounded by repeat-period aliases. Every true correspondence shares one difference and
    the aliases spread out, which is a peak and not a central tendency; a median over a list
    with more alias entries than true ones lands between clusters rather than on one.

    OFFSETS ARE LARGER THAN THEY LOOK LIKE THEY SHOULD BE, and the largest one in the corpus
    was checked by hand before this was trusted. BonkiDonki-1788685604519-BonkiDonki-7 and
    Santa_Samus-1788685603205-Santa_Samus-2 come back +1,976 ms, which is nearly two seconds
    between two clients on one machine. It is real: the two announcement keys that occur
    exactly once in each file - a Cleave and a Zig-Zag Ruse, which have no repeat to alias
    against - give +1,987 and +1,921 ms independently, and the sixteen-throw barrage streams
    are the same shape with their first events 1,977 ms apart. `wall + t` is when the CLIENT
    processed the event, not when the server resolved it, so a background client that is not
    being rendered can sit whole seconds behind. That is the quantity being corrected.

    The second pass keeps, for each announcement, only the partner nearest the rough offset
    and within `match_ms` of it, and re-medians. Its residual is what a caller gates on: a
    tight residual over enough matches means the two clients really were watching the same
    events; too few matches means there was nothing to measure, and the encounter must fall
    to UNKNOWN rather than be ordered on a fiction. The residual is the resolution: two
    actions closer together than it are simultaneous as far as the bytes can say.

    (0, 0, None) when there is nothing to match on, or too little. That is NOT an offset of
    zero. `Encounter.build` records it as an unmeasured clock, and every caller has to
    check `matches` before ordering anything across that file.
    """
    a = _announcements(ref)
    b = _announcements(other)
    if (not a) or (not b):
        return (0, 0, None)
    wa = (ref.header or {}).get("wall") or 0
    wb = (other.header or {}).get("wall") or 0
    by_key = defaultdict(list)
    for t, gob, res in a:
        by_key[(gob, res)].append(wa + t)
    rough = []
    for t, gob, res in b:
        for ta in by_key.get((gob, res), ()):
            rough.append(ta - (wb + t))
    if not rough:
        return (0, 0, None)
    guess = _densest(rough, ROUGH_BIN_MS)
    diffs = []
    for t, gob, res in b:
        tb = wb + t
        best = None
        for ta in by_key.get((gob, res), ()):
            d = ta - tb
            if abs(d - guess) > match_ms:
                continue
            if (best is None) or (abs(d - guess) < abs(best - guess)):
                best = d
        if best is not None:
            diffs.append(best)
    if len(diffs) < MIN_MATCHES:
        # Not a measurement. An offset from one or two coincidences is a guess with a
        # residual of zero attached to it, which is the most misleading shape this can
        # take, so it is not returned at all.
        return (0, 0, None)
    off = _median(diffs)
    resid = sorted(abs(d - off) for d in diffs)
    p90 = resid[min(len(resid) - 1, int(0.9 * len(resid)))]
    return (int(round(off)), len(diffs), int(round(p90)))


def _densest(xs, width):
    """The centre of the most populated `width`-wide window over `xs`.

    A sliding window rather than fixed bins, so a cluster that straddles a bin edge is not
    split in half and beaten by an alias that happens to sit mid-bin.
    """
    s = sorted(xs)
    best, bestn, j = s[0], 0, 0
    for i in range(len(s)):
        while (j < len(s)) and ((s[j] - s[i]) <= width):
            j += 1
        if (j - i) > bestn:
            bestn = j - i
            best = _median(s[i:j])
    return best


def _median(xs):
    s = sorted(xs)
    n = len(s)
    if not n:
        return 0.0
    if n % 2:
        return float(s[n // 2])
    return (s[(n // 2) - 1] + s[n // 2]) / 2.0


def build(paths, weapons=None, moves=None, opens=None):
    """Read one encounter's files, recover the offsets, and snapshot every fighter.

    An offset that could not be measured is recorded as 0 WITH a None residual, and
    `resolution` is left at the floor for that pair. Callers must consult
    `Encounter.matches` before ordering anything across that file: a missing measurement is
    the case the dossier says must fall to UNKNOWN, and silently treating it as "the clocks
    agree" is exactly the fabrication the offsets exist to prevent.
    """
    if weapons is None:
        weapons = load_weapons()
    if moves is None:
        import estimate
        moves = estimate.load_moves()
    logs = []
    for p in paths:
        try:
            logs.append(fightlog.read(p, opens))
        except Exception:
            continue
    logs = [g for g in logs if g.rows and g.header]
    if len(logs) < 2:
        return None
    enc = Encounter(logs)
    _weapon_res()
    for g in logs:
        if g.path == enc.ref.path:
            enc.offset[g.path] = 0
            enc.matches[g.path] = None
            continue
        off, n, resid = estimate_offset(enc.ref, g)
        enc.offset[g.path] = off
        enc.matches[g.path] = n
        if resid is not None:
            enc.residual[g.path] = resid
    # ONE FIGHTER PER CHARACTER, HOLDING EVERY FILE THEY WROTE. Built after the offsets,
    # because a Fighter answers questions on the reference clock and cannot convert one
    # without them.
    bygob = defaultdict(list)
    for g in logs:
        gob = (g.header or {}).get("megob")
        if gob is not None:
            bygob[gob].append(g)
    for gob, gs in bygob.items():
        enc.fighters[gob] = Fighter(enc, gs, weapons, moves)
    return enc


def load_weapons():
    """Weapon base damage by name, from the wiki pack. See replay.load_weapons."""
    import replay
    return replay.load_weapons()


def find(paths=None, limit=None):
    """Every encounter in the corpus, built. Convenience for the checks and the CLI."""
    if paths is None:
        paths, _dirs = fightlog.default_logs(ROOT)
    import estimate
    moves = estimate.load_moves()
    opens = estimate.opens_map(moves)
    weapons = load_weapons()
    out = []
    for files in group(sorted(paths)):
        enc = build(files, weapons, moves, opens)
        if enc is not None:
            out.append(enc)
        if limit and (len(out) >= limit):
            break
    return out


def main(argv):
    paths, _dirs = fightlog.default_logs(ROOT)
    encs = find(paths, limit=int(argv[0]) if argv else None)
    print("%d encounter(s) in %d file(s)" % (len(encs), len(paths)))
    for e in encs[:20]:
        print("  %-46s res %2d ms" % (",".join(e.chars), e.resolution))
        for g in e.logs:
            print("      %-52s offset %+6d ms  matched %s"
                  % (os.path.basename(g.path), e.offset.get(g.path, 0),
                     e.matches.get(g.path)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
