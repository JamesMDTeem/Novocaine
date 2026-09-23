#!/usr/bin/env python3
"""Party clears of several creatures (NvN), joined across the members' logs, as StrategyVsPlayer jobs.

    python tools/combat/nvn_lines.py [--out FILE]

The party-against-crowd counterpart of party_lines.py (COMBAT.md §3.12), for haven.combat.PartyCrowd.
A fight is the set of logs that share a creature, joined the way party_lines joins one creature, and
it is kept when

  - two or more recording characters logged it, and no log lost lines;
  - every creature in it died - a clear, so time to clear means the same in the log and the model;
  - nobody outside those logs threw a card during it (party_lines' bodies check: an unlogged ally's
    damage would be credited to us).

Per member: their cards after their free ranged phase (as solo_lines), each with the creature its
move row names; the tick they joined, relative to the first attacking card anyone threw; their
deck, gear, attributes, banked initiative and held stance. Per creature: its hitpoints (the largest
damage total any log saw on it), the tick it arrived (first seen in any log, relative to the same
start, never before 0), the member it went for first (the first member hit within KILL_CARD_MS of
one of its cards), and the tick it died. Creatures are ordered by death.
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
from solo_lines import ROOT, TICK_MS, attacking, deck_for, held_buffs  # noqa: E402


def read_member(path, sheet, attacks):
    """One member's view of a party fight against two or more creatures, or None."""
    try:
        log = fightlog.read(path)
    except Exception:
        return None
    h = log.header or {}
    if not h.get("char") or log.me is None or not party_fought(log):
        return None
    if any(e.lines_lost for e in log.engagements):
        return None
    wall = h.get("wall") or 0
    res, seen, killed = {}, {}, set()
    for e in log.engagements:
        if e.res and "kritter" in e.res:
            res[e.gob] = e.res
            if e.outcome == "killed":
                killed.add(e.gob)
    for r in log.rows:
        if r.get("ev") == "foes":
            for row in (r.get("o") or []):
                if row[0] in res:
                    seen.setdefault(row[0], wall + r["t"])
    if len(res) < 2:
        return None
    ours = [r for r in log.rows if r.get("ev") == "move" and r.get("actor") == "me" and r.get("move")]
    foe_moves = [r for r in log.rows if r.get("ev") == "move" and r.get("actor") == "foe" and r.get("gob") in res]
    first_foe = min([r["t"] for r in foe_moves] or [float("inf")])
    pre = 0
    while pre < len(ours) and ours[pre]["t"] < first_foe and not attacks.get(ours[pre]["move"], False):
        pre += 1
    ours = ours[pre:]
    hits = [r for r in log.rows if r.get("ev") == "dmg" and r.get("gob") == log.me
            and r.get("ch") in ("SHP", "HHP", "ARM")]
    # Who each creature went for: this member, where a hit landed on us just after its card.
    went = {}
    for r in foe_moves:
        if r["gob"] not in went and any(0 <= d["t"] - r["t"] <= KILL_CARD_MS for d in hits):
            went[r["gob"]] = wall + r["t"]
    dealt, died = Counter(), {}
    for r in log.rows:
        if r.get("ev") == "dmg" and r.get("ch") == "SHP" and r.get("gob") in res:
            dealt[r["gob"]] += r.get("v") or 0
            died[r["gob"]] = wall + r["t"]
    bodies = set(o.get("gob") for o in (log.overlays or [])
                 if str(o.get("res", "")).startswith("gfx/fx/fight/") and o.get("gob") != log.me
                 and o.get("gob") not in res and ("borka" in str(o.get("gobres") or "borka")))
    hand, ql = [None, None], [0.0, 0.0]
    for g in (log.gear or []):
        s = g.get("slot")
        if s in (6, 7) and (g.get("t") or 0) == 0:
            hand[s - 6] = g.get("res")
            ql[s - 6] = g.get("ql") or 0.0
    states = [r for r in log.rows if r.get("ev") == "state"]
    return {
        "path": os.path.relpath(path, ROOT).replace("\\", "/"),
        "char": h.get("char"), "me": log.me, "res": res, "killed": killed, "seen": seen,
        "went": went, "dealt": dealt, "died": died, "bodies": bodies,
        "cards": [(wall + r["t"], r["move"], r.get("gob")) for r in ours],
        "hits": [(wall + r["t"], r["ch"], r.get("v") or 0) for r in hits],
        "deck": deck_for(log, h, sheet), "attr": h.get("attr") or {},
        "hard": h.get("hard") or 0, "soft": h.get("soft") or 0, "hand": hand, "ql": ql,
        "myip": (states[0].get("myip") or 0) if states else 0,
        "buffs": held_buffs(log.rows, ours[0]["t"] if ours else 0),
    }


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.environ.get("TEMP", "."), "nvn_lines.jsonl"))
    a = ap.parse_args(argv)
    sheet = estimate.load_moves()
    attacks = attacking(sheet)
    pool = os.path.join(ROOT, "data", "combat", "pool")
    logs = []
    for path in fightlog.pool_logs(pool):
        m = read_member(path, sheet, attacks)
        if m:
            logs.append(m)
    # One fight = the logs linked by a shared creature.
    parent = list(range(len(logs)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    by_gob = defaultdict(list)
    for i, m in enumerate(logs):
        for g in m["res"]:
            by_gob[g].append(i)
    for ix in by_gob.values():
        for b in ix[1:]:
            parent[find(b)] = find(ix[0])
    groups = defaultdict(list)
    for i in range(len(logs)):
        groups[find(i)].append(logs[i])
    n, why, by = 0, Counter(), Counter()
    with open(a.out, "w", encoding="utf-8") as f:
        for ms in groups.values():
            one = {}
            for m in ms:                      # one log per character: the one with the most cards
                if m["char"] not in one or len(m["cards"]) > len(one[m["char"]]["cards"]):
                    one[m["char"]] = m
            ms = [m for m in one.values() if m["cards"] and m["deck"]]
            if len(ms) < 2:
                why["fewer than two logs with cards"] += 1
                continue
            res = {}
            for m in ms:
                res.update(m["res"])
            killed = set().union(*(m["killed"] for m in ms))
            if any(g not in killed for g in res):
                why["not every creature died"] += 1
                continue
            mes = set(m["me"] for m in ms)
            if any(m["bodies"] - mes for m in ms):
                why["someone outside our logs threw cards"] += 1
                continue
            hp = {g: max(m["dealt"].get(g, 0) for m in ms) for g in res}
            if any(v <= 0 for v in hp.values()):
                why["a creature with no damage on it"] += 1
                continue
            died = {g: max(m["died"][g] for m in ms if g in m["died"]) for g in res}
            t0 = min(m["cards"][0][0] for m in ms)
            t_clear = max(died.values())
            if t_clear <= t0:
                why["no fight after the first card"] += 1
                continue
            order = sorted(res, key=lambda g: died[g])
            idx = dict((g, i) for i, g in enumerate(order))
            members, tot = [], Counter()
            for m in ms:
                kept = [c for c in m["cards"] if c[0] <= t_clear + KILL_CARD_MS]
                ch = Counter()
                for t, c, v in m["hits"]:
                    if t0 - 2000 <= t <= t_clear + 200:
                        ch[c] += v
                tot.update(ch)
                members.append({
                    "char": m["char"], "attr": m["attr"], "hard": m["hard"], "soft": m["soft"],
                    "hand": m["hand"], "ql": m["ql"], "deck": m["deck"], "myip": m["myip"],
                    "buffs": m["buffs"],
                    "line": [c[1] for c in kept], "at": [idx.get(c[2], -1) for c in kept],
                    "start": max(0, round((m["cards"][0][0] - t0) / TICK_MS)),
                    "logged": {"soft": ch["SHP"], "hard": ch["HHP"], "soaked": ch["ARM"], "cards": len(kept)},
                })
            foes = []
            for g in order:
                seen = min([m["seen"][g] for m in ms if g in m["seen"]] or [t0])
                went = [(m["went"][g], i) for i, m in enumerate(ms) if g in m["went"]]
                # Where no blow of its could be matched to one of us, the member who went for it
                # first: defaulting to member 0 stacked whole wolf packs on one person, and damage
                # goes as the square of what they open.
                first_at = [(c[0], i) for i, m in enumerate(ms) for c in m["cards"] if c[2] == g]
                aggro = min(went)[1] if went else (min(first_at)[1] if first_at else 0)
                foes.append({"gob": g, "res": res[g], "species": res[g].rsplit("/", 1)[-1], "hp": hp[g],
                             "arrive": max(0, round((seen - t0) / TICK_MS)),
                             "aggro": aggro, "aggro_from": "hit" if went else ("card" if first_at else "default"),
                             "died": round((died[g] - t0) / TICK_MS)})
            species = "+".join(sorted(set(x["species"] for x in foes)))
            j = {"shape": "NvN", "path": ms[0]["path"], "char": "party of %d" % len(members),
                 "species": species, "members": members, "foes": foes,
                 "logged": {"ticks": round((t_clear - t0) / TICK_MS), "dealt": sum(hp.values()),
                            "soft": tot["SHP"], "hard": tot["HHP"], "soaked": tot["ARM"],
                            "cards": sum(len(x["line"]) for x in members), "foes": len(foes)}}
            f.write(json.dumps(j) + "\n")
            n += 1
            by[(len(members), species)] += 1
    print("%d party clears of two or more creatures -> %s" % (n, a.out))
    for k, v in why.most_common():
        print("  set aside: %-45s %d" % (k, v))
    for (k, s), v in by.most_common(15):
        print("  party of %d  %-40s %d" % (k, s, v))
    return 0


if __name__ == "__main__":
    sys.exit(main())
