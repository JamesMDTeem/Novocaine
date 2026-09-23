#!/usr/bin/env python3
"""Clean 1vN clears - one of us against several creatures - as jobs for StrategyVsPlayer.

    python tools/combat/crowd_lines.py [--out FILE] [--min-foes N]

The crowd counterpart of solo_lines.py (COMBAT.md §3.10). The planner has planned crowds for a
while - several opponents, sweeps, per-relation initiative, and since 2026-09-21 the whole room -
but no crowd prediction had ever been held against a crowd fight. A fight is kept when

  - it is ours, against creatures only, and no party member threw a card in it;
  - no log lines were lost;
  - at least --min-foes creatures (default 2) were in it, and EVERY one of them died - a clear,
    so "time to clear" means the same thing in the log and in the model;
  - it started with the first creature fresh;
  - every creature took at least half its hits beside one of our cards (OUR_HIT_SHARE) - below
    that, something we did not log was damaging it.

Each creature carries the tick it arrived (relative to the fight engaging), the damage that
killed it (its hitpoints, staged as-is), and its place in the order they died: the model hits
the first one standing, so the creatures are ordered as they were killed. The line starts after
the free ranged phase, as in solo_lines.py.
"""

import argparse
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402
import fightlog  # noqa: E402
from solo_lines import OURS, ROOT, TICK_MS, attacking, deck_for, held_buffs, restoring  # noqa: E402


def party_fought(log):
    """Whether a party member threw a card while this log ran.

    NOT fightlog's per-engagement flag, which cannot see it in a crowd: that check runs only while
    nothing else has marked the engagement, and in a fight with several creatures the damage on
    the others marks it first ("damage numbers ... neither us nor this opponent"). A "solo" wolf
    pack clear read 21 hits on the wolves for 25 of our cards, 9 of them near a card of ours, and
    80 fight overlays from 5 other bodies - the party was in it (2026-09-21).
    """
    mates = set()
    for p in (log.party or []):
        mates.update(p.get("gobs") or ())
    mates.discard(log.me)
    return any((o.get("gob") in mates) and str(o.get("res", "")).startswith("gfx/fx/fight/")
               for o in (log.overlays or []))


_REACH = None


def reach_of(species):
    """The distance a species swings from, from the pack (policy.reach), or None."""
    global _REACH
    if _REACH is None:
        doc = json.load(open(os.path.join(ROOT, "data", "combat", "opponents.json"), encoding="utf-8"))
        _REACH = {o["name"]: (o.get("policy") or {}).get("reach") for o in doc["opponents"]}
    return _REACH.get(species)


# A hit is ours when it lands within this of one of our cards: in clean solo kills the damage row
# sits a median 1 ms from the card that caused it (p10 -12 ms), and the tail past a second is
# hits whose own card was not logged.
OUR_HIT_MS = 60
# THE KILLING CARD'S ROW CAN TRAIL ITS DAMAGE by up to ~100 ms (14% of kills sit 50-100 ms before
# the next card of ours, then nothing until 400 ms). A 50 ms cut dropped the kill card from the line
# and read the model as giving BonkiDonki's early fights a third of their damage (2026-09-21).
KILL_CARD_MS = 150
# The share of a creature's hits that must be ours. Clean solo kills put a median 100% of hits beside
# a card of ours and their tenth percentile at 50%; a crowd creature below that took damage from
# something we did not log - two 163 hp foxes killed with 5 of our cards, 830 hp of cattle with 6.
OUR_HIT_SHARE = 0.5


def ours_share(rows, gob):
    ours = [r["t"] for r in rows if r.get("ev") == "move" and r.get("actor") == "me"]
    hits = sorted(set(r["t"] for r in rows if r.get("ev") == "dmg" and r.get("gob") == gob
                      and r.get("ch") == "SHP"))
    if not hits:
        return None
    return sum(1 for t in hits if any(abs(t - m) <= OUR_HIT_MS for m in ours)) / float(len(hits))


def job(path, sheet, rest, attacks, min_foes):
    try:
        log = fightlog.read(path)
    except Exception:
        return None
    h = log.header or {}
    if h.get("char") not in OURS or log.me is None:
        return None
    me = log.me
    if any("borka" in (e.res or "") for e in log.engagements):
        return None
    if any(e.lines_lost for e in log.engagements):
        return None
    if party_fought(log):
        return None
    killed = set(e.gob for e in log.engagements if e.outcome == "killed")
    arrive, res, near = {}, {}, {}
    for r in log.rows:
        if r.get("ev") == "foe" and r.get("res"):
            res[r["gob"]] = r["res"]
    for r in log.rows:
        if r.get("ev") == "foes":
            d = r.get("d") or []
            for i, row in enumerate(r.get("o") or []):
                arrive.setdefault(row[0], r["t"])
                # IN REACH, NOT MERELY AGGROED. A relation appears when a creature takes an
                # interest, and it may still be running in; staged from then, it swings at us
                # for the whole run. The `foes` row carries each one's distance, so it joins
                # the model when it first came inside its own species' reach.
                rch = reach_of((res.get(row[0]) or "").rsplit("/", 1)[-1])
                if (row[0] not in near) and (i < len(d)) and (rch is not None) and (d[i] <= rch):
                    near[row[0]] = r["t"]
    for e in log.engagements:
        if e.res:
            res.setdefault(e.gob, e.res)
    foes = [g for g in arrive if "kritter" in (res.get(g) or "")]
    if len(foes) < min_foes or any(g not in killed for g in foes):
        return None
    dealt, died = Counter(), {}
    for r in log.rows:
        if r.get("ev") == "dmg" and r.get("ch") == "SHP" and r.get("gob") in arrive:
            dealt[r["gob"]] += r.get("v") or 0
            died[r["gob"]] = r["t"]
    if any(dealt[g] <= 0 for g in foes):
        return None
    if any((ours_share(log.rows, g) or 0) < OUR_HIT_SHARE for g in foes):
        return None
    ours = [r for r in log.rows if r.get("ev") == "move" and r.get("actor") == "me" and r.get("move")]
    first_foe = min([r["t"] for r in log.rows if r.get("ev") == "move" and r.get("actor") == "foe"]
                    or [float("inf")])
    pre = 0
    while pre < len(ours) and ours[pre]["t"] < first_foe and not attacks.get(ours[pre]["move"], False):
        pre += 1
    free, ours = ours[:pre], ours[pre:]
    if len(ours) < 3:
        return None
    t0 = ours[0]["t"]
    t1 = max(died[g] for g in foes)
    order = sorted(foes, key=lambda g: died[g])
    states = [r for r in log.rows if r.get("ev") == "state" and r["t"] <= t0]
    st = states[-1] if states else {}
    ch = Counter()
    for r in log.rows:
        if r.get("ev") == "dmg" and r.get("gob") == me and r.get("ch") in ("SHP", "HHP", "ARM") \
                and t0 - 2000 <= r["t"] <= t1 + 200:
            ch[r["ch"]] += r.get("v") or 0
    deck = deck_for(log, h, sheet)
    if not deck:
        return None
    hand, ql = [None, None], [0.0, 0.0]
    for g in (log.gear or []):
        s = g.get("slot")
        if s in (6, 7) and (g.get("t") or 0) == 0:
            hand[s - 6] = g.get("res")
            ql[s - 6] = g.get("ql") or 0.0
    kept = [r for r in ours if r["t"] <= t1 + KILL_CARD_MS]
    line = [r["move"] for r in kept]
    # The creature each card was thrown at, as its place in the kill order (-1 when the move row
    # names none of them): replayed without it, a card that hit the first creature is moved onto
    # the next one standing, which is not what happened.
    targets = [order.index(r["gob"]) if r.get("gob") in order else -1 for r in kept]
    # Each creature's agility bracket, as solo_lines records it - see solo_lines.jobs_from.
    brackets = {}
    for r in log.rows:
        if r.get("ev") == "agi" and r.get("gob") in foes:
            brackets[r["gob"]] = [r.get("min") or 0.0, r.get("max") or 2.0]
    return {
        "path": os.path.relpath(path, ROOT).replace("\\", "/"),
        "char": h.get("char"),
        "shape": "1vN",
        "buffs": held_buffs(log.rows, t0),
        "foes": [{"gob": g, "res": res[g], "species": res[g].rsplit("/", 1)[-1],
                  "hp": dealt[g], "arrive": max(0, round((near.get(g, arrive[g]) - t0) / TICK_MS)),
                  "aggro": max(0, round((arrive[g] - t0) / TICK_MS)),
                  "agi": brackets.get(g)} for g in order],
        "species": "+".join(sorted(set(res[g].rsplit("/", 1)[-1] for g in foes))),
        "attr": h.get("attr") or {}, "hard": h.get("hard") or 0, "soft": h.get("soft") or 0,
        "hand": hand, "ql": ql, "deck": deck,
        "mine": list(st.get("mine") or [0, 0, 0, 0]), "myip": st.get("myip") or 0,
        "free": [r["move"] for r in free],
        "line": line,
        "targets": targets,
        "logged": {
            "ticks": round((t1 - t0) / TICK_MS),
            "dealt": sum(dealt[g] for g in foes),
            "soft": ch["SHP"], "hard": ch["HHP"], "soaked": ch["ARM"],
            "cards": len(line), "foes": len(foes),
            "restored": round(sum(1 for r in line if r in rest) / float(len(line)), 3),
        },
    }


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.environ.get("TEMP", "."), "crowd_lines.jsonl"))
    ap.add_argument("--min-foes", type=int, default=2)
    a = ap.parse_args(argv)
    sheet = estimate.load_moves()
    rest = restoring(sheet)
    attacks = attacking(sheet)
    pool = os.path.join(ROOT, "data", "combat", "pool")
    n, by = 0, Counter()
    with open(a.out, "w", encoding="utf-8") as f:
        for path in fightlog.pool_logs(pool):
            j = job(path, sheet, rest, attacks, a.min_foes)
            if j:
                f.write(json.dumps(j) + "\n")
                n += 1
                by[(j["char"], j["species"], len(j["foes"]))] += 1
    print("%d clean 1vN clears -> %s" % (n, a.out))
    for (c, s, k), m in sorted(by.items(), key=lambda kv: -kv[1])[:20]:
        print("  %-12s %-28s %2d foes  %d" % (c, s, k, m))
    return 0


if __name__ == "__main__":
    sys.exit(main())
