#!/usr/bin/env python3
"""Checks for fightlog, the layer that decides what a log is allowed to measure.

    python tools/combat/fightlog_check.py

Every estimate in this project passes through fightlog, so a mistake here does not
produce an error - it produces a number that looks fine and is wrong. Three such
mistakes were found by hand on the day it was written: gains that carried no actor, so
an opponent's own attacks were read as evidence about its defence; state pairing by
timestamp, which silently dropped most of the corpus; and treating openings an opponent
arrived with as though another player had caused them.

The fixtures below are written out event by event rather than read from a captured log,
because a captured log cannot express the case that has not happened yet, and because
the real ones live outside the repository. Each is small enough to check by eye.

Exits 0 when every check passes, 1 otherwise.
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fightlog  # noqa: E402

failures = []


def check(what, got, want):
    ok = got == want
    print("  %-56s %-22s %s" % (what, repr(got)[:22], "ok" if ok else "WANT %r" % (want,)))
    if not ok:
        failures.append(what)


ME = 100
FOE = 200
OTHER = 300


def begin(**kw):
    row = {"ev": "begin", "t": 0, "wall": 0, "schema": 3, "char": "c", "megob": ME,
           "foegob": FOE, "foeres": "gfx/kritter/badger/badger", "attrb": {}, "attr": {},
           "hard": 0, "soft": 0}
    row.update(kw)
    return row


def state(t, foe=(0, 0, 0, 0), mine=(0, 0, 0, 0), gob=FOE, myip=0, foeip=0):
    return {"ev": "state", "t": t, "gob": gob, "mine": list(mine), "foe": list(foe),
            "myip": myip, "foeip": foeip, "hpf": 10000, "stam": 1.0, "energy": 1.0,
            "dist": 5.0}


def move(t, actor="me", name="Quick Barrage", gob=FOE, cd=18.0):
    return {"ev": "move", "t": t, "actor": actor, "gob": gob,
            "move": "paginae/atk/barrage", "name": name, "cd": cd}


def dmg(t, gob, ch, v):
    return {"ev": "dmg", "t": t, "gob": gob, "ch": ch, "v": v}


def end(t=9999):
    return {"ev": "end", "t": t, "reason": "ended"}


def write(rows):
    fd, path = tempfile.mkstemp(suffix=".jsonl")
    with os.fdopen(fd, "w", encoding="utf8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")
    return path


def load(rows):
    path = write(rows)
    try:
        return fightlog.read(path)
    finally:
        os.unlink(path)


def segmentation():
    print("segmentation")
    # One opponent throughout.
    log = load([begin(), state(10), move(20), state(30, foe=(0, 0, 0, 10)), end()])
    check("a single-opponent log is one engagement", len(log.engagements), 1)
    check("named from the header", log.engagements[0].name, "badger")

    # The sampled gob changes mid-file: two creatures, one file.
    log = load([begin(), state(10), move(20), state(30, foe=(0, 0, 0, 10)),
                {"ev": "foe", "t": 35, "gob": OTHER, "res": "gfx/kritter/lynx/lynx",
                 "how": "current"},
                state(40, gob=OTHER, foe=(0, 0, 0, 70)), end()])
    check("a retarget splits the log", len(log.engagements), 2)
    check("each engagement keeps its own opponent",
          [e.name for e in log.engagements], ["badger", "lynx"])
    # The second one's opening is 70 where the first left off at 10. Nothing "jumped".
    check("the second opponent's openings are its own",
          log.engagements[1].states[0]["foe"][3], 70)
    check("retargeting does not block measuring our offence",
          log.engagements[0].offence_ok, True)
    check("it does block measuring our defence",
          log.engagements[0].defence_ok, False)

    # A foe event names an opponent whose resource had not loaded when it arrived.
    log = load([begin(foeres=None), state(10),
                {"ev": "foe", "t": 12, "gob": FOE, "res": "gfx/kritter/boar/boar",
                 "how": "name"}, end()])
    check("a late name event identifies the opponent", log.engagements[0].name, "boar")


def contamination():
    print("\nthird parties")
    # Someone else's damage numbers are drawn over gobs we are not fighting.
    log = load([begin(), state(10), move(20), state(30, foe=(0, 0, 0, 10)),
                dmg(31, OTHER, "SHP", 40), end()])
    check("a stranger's damage blocks both directions",
          (log.engagements[0].offence_ok, log.engagements[0].defence_ok), (False, False))

    # An opening rise after our first move that no move of ours explains.
    log = load([begin(), state(10), move(20), state(30, foe=(0, 0, 0, 10)),
                state(2000, foe=(0, 0, 0, 60)), end()])
    check("an unexplained rise after our first move blocks offence",
          log.engagements[0].offence_ok, False)
    check("it does not block defence", log.engagements[0].defence_ok, True)

    # The same rise BEFORE our first move is the opponent arriving already opened, which
    # auto-reaggro produces every time it splits an engagement across two files.
    log = load([begin(), state(10), state(20, foe=(0, 0, 0, 60)), move(2000),
                state(2010, foe=(0, 0, 0, 64)), end()])
    check("a rise before our first move is not contamination",
          log.engagements[0].offence_ok, True)
    check("and is reported as carried in",
          fightlog.carried_in(log.engagements[0]), [("red", 60)])

    # An engagement in which we never attacked cannot be contaminated by definition.
    log = load([begin(), state(10), state(20, foe=(0, 0, 0, 60)), end()])
    check("no attacks of ours means nothing to misattribute",
          log.engagements[0].offence_ok, True)


def pairing():
    print("\npairing moves with the states around them")
    # The state event lands AFTER the move and already carries its effect. Pairing by
    # timestamp with any slack at all reads that state as the one the move read, which
    # reports a gain of zero and loses the observation.
    rows = [begin(), state(1000, foe=(0, 0, 0, 20)), move(2000),
            state(2004, foe=(0, 0, 0, 28)), move(3000),
            state(3005, foe=(0, 0, 0, 35)), end()]
    log = load(rows)
    gains = fightlog.opening_gains(log.engagements[0])
    check("both attacks yield a gain", len(gains), 2)
    check("the first reads the opening standing before it",
          (gains[0][2], gains[0][3], gains[0][4]), ("red", 20, 8))
    check("and so does the second",
          (gains[1][2], gains[1][3], gains[1][4]), ("red", 28, 7))
    check("every gain says who caused it", gains[0][0], "me")

    # A gain the opponent caused is about OUR defence, and must be labelled so.
    log = load([begin(), state(1000), move(2000, actor="foe", name="Badgering"),
                state(2004, mine=(0, 0, 0, 12)), end()])
    gains = fightlog.opening_gains(log.engagements[0])
    check("an opponent's gain is attributed to the opponent", gains[0][0], "foe")
    check("and is read off our own openings", (gains[0][1], gains[0][4]),
          ("Badgering", 12))

    # Two moves with no state between them cannot be separated, so neither is credited.
    log = load([begin(), state(1000), move(2000), move(2100),
                state(2200, foe=(0, 0, 0, 30)), end()])
    gains = fightlog.opening_gains(log.engagements[0])
    check("two moves with no state between them credit only the later", len(gains), 1)


def damage():
    print("\npairing moves with their damage")
    # ARM is the share the armour absorbed, so ARM + SHP is the damage before armour -
    # which is the figure the model predicts, and the reason armoured opponents are the
    # best evidence rather than the worst.
    log = load([begin(), state(1000, foe=(0, 0, 0, 46)),
                dmg(1998, FOE, "ARM", 15), dmg(1999, FOE, "SHP", 3), move(2000), end()])
    h = fightlog.hits(log.engagements[0], ME)[0]
    check("soaked comes from the ARM channel", h["soaked"], 15)
    check("through comes from SHP", h["shp"], 3)
    check("raw is their sum", h["raw"], 18)
    check("read at the opening standing before the move", h["openings"][3], 46)

    # Damage on somebody else in view is not ours and must not be paired.
    log = load([begin(), state(1000, foe=(0, 0, 0, 46)),
                dmg(1999, OTHER, "SHP", 99), move(2000), end()])
    h = fightlog.hits(log.engagements[0], ME)[0]
    check("a stranger's damage is not credited to our move", h["raw"], 0)


def completeness():
    print("\nincomplete logs")
    log = load([begin(), state(10), move(20), state(30, foe=(0, 0, 0, 10))])
    check("a log with no end event is flagged", log.complete, False)
    check("but its measurements still stand", log.engagements[0].offence_ok, True)

    log = load([{"ev": "state", "t": 1, "gob": FOE, "mine": [0, 0, 0, 0],
                 "foe": [0, 0, 0, 0], "myip": 0, "foeip": 0, "hpf": 1, "stam": 1,
                 "energy": 1, "dist": 1}])
    check("a headerless schema 1 log still segments", len(log.engagements), 1)
    check("and reports schema 1", log.schema, 1)


def main():
    segmentation()
    contamination()
    pairing()
    damage()
    completeness()
    if failures:
        print("\n%d CHECK(S) FAILED" % len(failures))
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
