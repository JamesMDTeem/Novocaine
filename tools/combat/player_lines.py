#!/usr/bin/env python3
"""The lines our characters actually threw to kill each creature - data/combat/player_lines.json.

    python tools/combat/player_lines.py [--out FILE] [--per N]

WHY (James, 2026-09-22): the planner "should never have a worse outcome than a player displayed
one". A beam search cannot promise that: it prunes by damage done so far, and against the
characters' own fights it lost to the player in 2% of solo and 16% of party matchups - every time a
line the beam cut early (Quick Barrage six times, then Full Circle into the openings they built).
Optimizer.search now takes lines to offer the frontier beside its own; this is the library the live
advice offers, per species, so any line a player has shown against that creature is in the running
from whatever state the fight is in.

A LINE is our cards at one creature, in order, from our first card at it to its death (plus
crowd_lines.KILL_CARD_MS, since a move row can trail its own blow), over every kill fightlog calls a
kill (drawn or undrawn) in every log, solo or party. Only lines of 2+ cards. Per species, the --per
most common distinct lines are kept, each with how many kills it was seen in.
"""

import argparse
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fightlog  # noqa: E402
from crowd_lines import KILL_CARD_MS  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAX_LEN = 40


def lines_of(path):
    try:
        log = fightlog.read(path)
    except Exception:
        return []
    if fightlog.is_ranged(log):
        return []
    out, done = [], set()
    ours = [r for r in log.rows if r.get("ev") == "move" and r.get("actor") == "me" and r.get("move")]
    for e in log.engagements:
        if (not e.kill) or (e.gob in done) or ("kritter" not in (e.res or "")):
            continue
        done.add(e.gob)
        # The kill, not the last number - see Log.kill_time.
        t_end = log.kill_time(e)
        if t_end is None:
            continue
        line = [m["move"] for m in ours if m.get("gob") == e.gob and m["t"] <= t_end + KILL_CARD_MS]
        if 2 <= len(line) <= MAX_LEN:
            out.append((e.res.rsplit("/", 1)[-1], tuple(line)))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "data", "combat", "player_lines.json"))
    ap.add_argument("--per", type=int, default=12)
    a = ap.parse_args(argv)
    counts = defaultdict(Counter)
    for path in fightlog.pool_logs(os.path.join(ROOT, "data", "combat", "pool")):
        for sp, line in lines_of(path):
            counts[sp][line] += 1
    out = {"format": 1,
           "note": "player_lines.py - the lines our characters threw to kill each creature, most "
                   "common first; offered to the live search beside its own (Optimizer.search).",
           "species": {}}
    for sp in sorted(counts):
        top = counts[sp].most_common(a.per)
        out["species"][sp] = [{"n": n, "line": list(line)} for line, n in top]
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, sort_keys=True)
        f.write("\n")
    print("wrote %s  (%d species, %d kills)" % (a.out, len(out["species"]),
                                                sum(sum(c.values()) for c in counts.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
