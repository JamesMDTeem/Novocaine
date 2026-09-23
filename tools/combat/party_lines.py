#!/usr/bin/env python3
"""Party kills of one creature (Nv1), joined across our characters' logs, as StrategyVsPlayer jobs.

    python tools/combat/party_lines.py [--out FILE]

Party fights are most of the corpus once they are counted properly (COMBAT.md §3.10): 3,027 Nv1
logs against 4,105 solo, and 2,031 creatures logged by two or more of our characters. Each log
holds one member's own cards and the damage that member took, and every log in the fight sees
the creature's damage floats - so a party fight can be put back together from its members' logs
and both the logged lines and PartyPlanner's lines stepped against the same creature.

A creature is kept when
  - it died, and two or more recording characters (ours or anyone whose log is in the pool)
    logged the fight against it alone;
  - nobody outside those logs threw a card during it - an unlogged ally's damage would be credited
    to us, which is exactly what made the "solo" wolf packs read five times too strong;
  - no log lost lines.

Per member: their line from their own log, after their free ranged phase (as in solo_lines.py),
and the tick they joined relative to the first attacking card anyone threw. The creature's
hitpoints are the largest damage total any member's log saw on it; FRONT is whoever took its
first blow - the creature swings at whoever is in front (James), and PartyPlanner models exactly
that.
"""

import argparse
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402
import fightlog  # noqa: E402
from crowd_lines import KILL_CARD_MS, party_fought  # noqa: E402
from solo_lines import OURS, ROOT, TICK_MS, attacking, deck_for, held_buffs  # noqa: E402


def read_member(path, sheet, attacks):
    """One member's view of a single-creature party fight, or None."""
    try:
        log = fightlog.read(path)
    except Exception:
        return None
    h = log.header or {}
    # ANY RECORDING CHARACTER, not only our four: the pool carries other players' logs too, and a
    # member who recorded is a member whose cards can be replayed rather than a stranger whose
    # damage would be credited to us.
    if not h.get("char") or log.me is None or not party_fought(log):
        return None
    if any(e.lines_lost for e in log.engagements):
        return None
    foes = set()
    for r in log.rows:
        if r.get("ev") == "foes":
            foes.update(x[0] for x in (r.get("o") or []))
    if len(foes) != 1:
        return None
    gob = next(iter(foes))
    res = None
    for e in log.engagements:
        if e.gob == gob and e.res:
            res = e.res
    if not res or "kritter" not in res:
        return None
    wall = h.get("wall") or 0
    killed = any(e.gob == gob and e.outcome == "killed" for e in log.engagements)
    on_foe = [r for r in log.rows if r.get("ev") == "dmg" and r.get("gob") == gob and r.get("ch") == "SHP"]
    ours = [r for r in log.rows if r.get("ev") == "move" and r.get("actor") == "me" and r.get("move")]
    first_foe = min([r["t"] for r in log.rows if r.get("ev") == "move" and r.get("actor") == "foe"]
                    or [float("inf")])
    pre = 0
    while pre < len(ours) and ours[pre]["t"] < first_foe and not attacks.get(ours[pre]["move"], False):
        pre += 1
    ours = ours[pre:]
    hits_on_me = [r for r in log.rows if r.get("ev") == "dmg" and r.get("gob") == log.me
                  and r.get("ch") in ("SHP", "HHP", "ARM")]
    # PEOPLE throwing cards, not every body with a fight overlay: the creature's own cards draw one
    # too, and counting it set aside most of the party fights as "someone outside threw cards".
    bodies = set(o.get("gob") for o in (log.overlays or [])
                 if str(o.get("res", "")).startswith("gfx/fx/fight/") and o.get("gob") != log.me
                 and o.get("gob") != gob and ("borka" in str(o.get("gobres") or "borka")))
    deck = deck_for(log, h, sheet)
    hand, ql = [None, None], [0.0, 0.0]
    for g in (log.gear or []):
        s = g.get("slot")
        if s in (6, 7) and (g.get("t") or 0) == 0:
            hand[s - 6] = g.get("res")
            ql[s - 6] = g.get("ql") or 0.0
    states = [r for r in log.rows if r.get("ev") == "state"]
    return {
        "path": os.path.relpath(path, ROOT).replace("\\", "/"),
        "char": h.get("char"), "me": log.me, "gob": gob, "res": res, "killed": killed,
        "dealt": sum(r.get("v") or 0 for r in on_foe),
        "last_hit": (wall + max(r["t"] for r in on_foe)) if on_foe else None,
        "cards": [(wall + r["t"], r["move"]) for r in ours],
        "first_blow": (wall + min(r["t"] for r in hits_on_me)) if hits_on_me else None,
        "hits": [(wall + r["t"], r["ch"], r.get("v") or 0) for r in hits_on_me],
        "bodies": bodies, "deck": deck,
        "attr": h.get("attr") or {}, "hard": h.get("hard") or 0, "soft": h.get("soft") or 0,
        "hand": hand, "ql": ql, "myip": (states[0].get("myip") or 0) if states else 0,
        "buffs": held_buffs(log.rows, ours[0]["t"] if ours else 0),
    }


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.environ.get("TEMP", "."), "party_lines.jsonl"))
    a = ap.parse_args(argv)
    sheet = estimate.load_moves()
    attacks = attacking(sheet)
    pool = os.path.join(ROOT, "data", "combat", "pool")
    by_gob = defaultdict(list)
    for path in fightlog.pool_logs(pool):
        m = read_member(path, sheet, attacks)
        if m:
            by_gob[m["gob"]].append(m)
    n, why, by = 0, Counter(), Counter()
    with open(a.out, "w", encoding="utf-8") as f:
        for gob, ms in by_gob.items():
            one = {}
            for m in ms:                      # one log per character: the one that saw the most
                if m["char"] not in one or len(m["cards"]) > len(one[m["char"]]["cards"]):
                    one[m["char"]] = m
            ms = [m for m in one.values() if m["cards"] and m["deck"]]
            if len(ms) < 2:
                why["fewer than two of our logs with cards"] += 1
                continue
            if not any(m["killed"] for m in ms):
                why["not killed"] += 1
                continue
            ours = set(m["me"] for m in ms)
            if any(m["bodies"] - ours for m in ms):
                why["someone outside our logs threw cards"] += 1
                continue
            if not any(m["last_hit"] for m in ms):
                why["no damage on it in any log"] += 1
                continue
            t_kill = max(m["last_hit"] for m in ms if m["last_hit"])
            t0 = min(m["cards"][0][0] for m in ms)
            if t_kill <= t0:
                why["no fight after the first card"] += 1
                continue
            blows = [(m["first_blow"], i) for i, m in enumerate(ms) if m["first_blow"]]
            front = min(blows)[1] if blows else 0
            members, tot = [], Counter()
            for m in ms:
                line = [mv for t, mv in m["cards"] if t <= t_kill + KILL_CARD_MS]
                ch = Counter()
                for t, c, v in m["hits"]:
                    if t0 - 2000 <= t <= t_kill + 200:
                        ch[c] += v
                tot.update(ch)
                members.append({
                    "char": m["char"], "attr": m["attr"], "hard": m["hard"], "soft": m["soft"],
                    "hand": m["hand"], "ql": m["ql"], "deck": m["deck"], "myip": m["myip"],
                    "buffs": m["buffs"],
                    "line": line, "start": max(0, round((m["cards"][0][0] - t0) / TICK_MS)),
                    "logged": {"soft": ch["SHP"], "hard": ch["HHP"], "soaked": ch["ARM"], "cards": len(line)},
                })
            j = {
                "shape": "Nv1", "path": ms[0]["path"], "gob": gob, "res": ms[0]["res"],
                "species": ms[0]["res"].rsplit("/", 1)[-1],
                "char": "party of %d" % len(members), "front": front,
                "hp": max(m["dealt"] for m in ms),
                "members": members,
                "logged": {"ticks": round((t_kill - t0) / TICK_MS), "dealt": max(m["dealt"] for m in ms),
                           "soft": tot["SHP"], "hard": tot["HHP"], "soaked": tot["ARM"],
                           "cards": sum(len(x["line"]) for x in members)},
            }
            f.write(json.dumps(j) + "\n")
            n += 1
            by[(len(members), j["species"])] += 1
    print("%d party kills of one creature -> %s" % (n, a.out))
    for k, v in why.most_common():
        print("  set aside: %-45s %d" % (k, v))
    for (k, s), v in by.most_common(15):
        print("  party of %d  %-14s %d" % (k, s, v))
    return 0


if __name__ == "__main__":
    sys.exit(main())
