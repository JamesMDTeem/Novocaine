#!/usr/bin/env python3
"""Clean solo kills, written out as jobs for tools/StrategyVsPlayer.java.

    python tools/combat/solo_lines.py [--out FILE] [--chars "A,B"] [--min-cards N]

WHY. The planner's lines have only ever been scored against the model. The fights the
characters actually won are the other half of the question: did the searched line beat
what a person threw, on what, and what did the person do that the search does not? This
picks the fights where that comparison is fair and writes each one's starting state, the
line that was thrown, and what the log says it cost, so the Java side can step both lines
through the same model (Optimizer.follow and Optimizer.search) from the same start.

A FIGHT IS KEPT WHEN
  - the opponent is a creature and the engagement ended in its death (fightlog outcome
    "killed", read off the kill award);
  - it is clean both ways - offence_ok and defence_ok: nothing else in the fight, no lost
    lines, no second opponent - so every blow is ours or its;
  - it started fresh: the creature stood at zero in every colour on the first state row,
    so the planner is not handed a fight half won;
  - we threw at least --min-cards cards (default 3);
  - at least half the hits on it sit beside one of our cards (crowd_lines.OUR_HIT_SHARE).

WHAT IS MEASURED, from the log alone:
  ticks      first card we threw to the last blow on it, in 0.06 s ticks
  dealt      soft damage on the creature
  soft/hard  our soft and hard hitpoints lost
  soaked     what our armour stopped (the ARM channel) - the part that wears it
  restored   the share of our cards that close our own openings
"""

import argparse
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402
import fightlog  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OURS = ("Shade", "BonkiDonki", "ZzxcuV3", "Santa Samus")
TICK_MS = 60.0


def attacking(sheet):
    """{card res: whether it can hit} - it declares an attack type or deals damage. Only these end a
    fight's free phase. One copy for every extractor (it was typed out in four)."""
    return {m.get("res"): bool(m.get("attack_types") or m.get("damage") or m.get("damage_share"))
            for m in sheet.values() if m.get("res")}


def restoring(sheet):
    out = set()
    for m in sheet.values():
        if m.get("reduces"):
            out.add(m.get("res"))
    return out


def held_buffs(rows, t0):
    """Our buffs as the fight engaged - the last `buffs` row for us at or before t0, else the first.

    The stance held into a fight (Parry, Shield Up...) is one of these, and nothing else in the log
    names it: a Parry held from the last fight is never thrown in this one. None when no row says.
    """
    mine = [r for r in rows if r.get("ev") == "buffs" and r.get("who") == "me"]
    if not mine:
        return None
    before = [r for r in mine if (r.get("t") or 0) <= t0]
    return list((before[-1] if before else mine[0]).get("res") or [])


def deck_for(log, h, sheet):
    """The deck the fight was fought with, card resource -> level.

    Newer logs carry it in the begin row. Older ones do not, and for those the deck dumps are
    read the way the estimator reads them (estimate.levels_for_log), which also refuses a dump
    the fight contradicts. {} when neither can say - such a fight cannot be staged fairly.
    """
    d = h.get("deck") or {}
    if d:
        return d
    by_name = estimate.levels_for_log(log) or {}
    out = {}
    for name, lvl in by_name.items():
        m = sheet.get(name)
        if m and m.get("res") and lvl:
            out[m["res"]] = lvl
    return out


def jobs_from(path, sheet, sheet_restores, min_cards, chars, attacks):
    try:
        log = fightlog.read(path)
    except Exception:
        return []
    h = log.header or {}
    if h.get("char") not in chars:
        return []
    me = log.me
    deck = deck_for(log, h, sheet)
    if not deck:
        return []
    hand = [None, None]
    ql = [0.0, 0.0]
    for g in (log.gear or []):
        s = g.get("slot")
        if s in (6, 7) and (g.get("t") or 0) == 0:
            hand[s - 6] = g.get("res")
            ql[s - 6] = g.get("ql") or 0.0
    out = []
    for eng in log.engagements:
        res = eng.res or ""
        if "kritter" not in res or eng.outcome != "killed":
            continue
        if not (eng.offence_ok and eng.defence_ok):
            continue
        if not eng.states:
            continue
        first = eng.states[0]
        if any(first.get("foe") or [1]):
            continue
        ours = [m for m in eng.moves if m.get("actor") == "me" and m.get("move")]
        # THE FREE PHASE. Take Aim and its kin are thrown at range while the creature is still
        # coming in: measured over the opening Take Aims in these fights, at 78-106 units against
        # reaches of 14-47, with no blow on us and not one action of the creature before our first
        # attack. The model stages every fight in reach, so it charged that phase time, hitpoints
        # and armour that the fight never cost - and read the planner as crushing lines that were
        # only opened well. Leading non-attacking cards thrown before the creature's first action
        # are set aside, and both lines start where the fight did: at the first card after them,
        # holding the initiative they banked.
        first_foe = min([m["t"] for m in eng.moves if m.get("actor") == "foe"] or [float("inf")])
        pre = 0
        while (pre < len(ours) and ours[pre]["t"] < first_foe
               and not attacks.get(ours[pre]["move"], False)):
            pre += 1
        free = ours[:pre]
        ours = ours[pre:]
        if len(ours) < min_cards:
            continue
        t0 = ours[0]["t"]
        engaged = [st for st in eng.states if st.get("t") is not None and st["t"] <= t0] or [first]
        # THE WHOLE FILE'S NUMBERS ON IT, and the fight ending at its death - Log.damage_on and
        # Log.kill_time, which every other extractor reads (seams audit, 2026-09-23). This read the
        # engagement's own rows and ended at the last number, so an undrawn kill's line stopped a
        # card short of the blow that killed it.
        on_foe = [d for d in log.damage_on(eng.gob) if d.get("ch") == "SHP" and d.get("t") is not None]
        if not on_foe:
            continue
        # SOMETHING WE DID NOT LOG was hitting it when fewer than half its hits sit beside one of our
        # cards - a bow, a pet, a passer-by. offence_ok cannot see a source with no card overlay, and
        # the crowd work found two 163 hp foxes killed with five of our cards. See crowd_lines.
        from crowd_lines import ours_share, OUR_HIT_SHARE, KILL_CARD_MS
        if (ours_share(log.rows, eng.gob) or 0) < OUR_HIT_SHARE:
            continue
        t1 = log.kill_time(eng) or max(d["t"] for d in on_foe)
        ch = Counter()
        for d in eng.damage:
            if d.get("gob") == me and d.get("ch") in ("SHP", "HHP", "ARM") and t0 - 2000 <= d["t"] <= t1 + 200:
                ch[d["ch"]] += d.get("v") or 0
        kept = [m for m in ours if m["t"] <= t1 + KILL_CARD_MS]
        line = [m["move"] for m in kept]
        # PER CARD, WHAT THE LOG SAW: the creature's openings standing before it (the last state row
        # at least 20 ms earlier - a card's own gain lands a few ms either side of its row) and the
        # soft hitpoints it took off (hits within KILL_CARD_MS either side). For tools/OpeningDrift,
        # which walks the model forward over the same line and sets the two side by side.
        states = [st for st in eng.states if st.get("t") is not None]
        trace = []
        for m in kept:
            before = [st for st in states if st["t"] <= m["t"] - 20]
            fo = list(before[-1].get("foe") or [0, 0, 0, 0]) if before else [0, 0, 0, 0]
            hit = sum(d.get("v") or 0 for d in on_foe if abs(d["t"] - m["t"]) <= KILL_CARD_MS)
            mo = list(before[-1].get("mine") or [0, 0, 0, 0]) if before else [0, 0, 0, 0]
            trace.append({"open": fo, "mine": mo, "shp": hit})
        # THE CLIENT'S AGILITY BRACKET for this creature, as a ratio of its agility to ours: the last
        # (tightest) row. The model otherwise stages the species' agility cap, and bats, foxes and
        # swans that were slower than us (bracket (0, 0.58), every card at 0.9 of its base) ran the
        # modelled clock 1.22 times slow. None where the client never narrowed it, or it crossed.
        agi = [r for r in log.rows if r.get("ev") == "agi" and r.get("gob") == eng.gob]
        bracket = [agi[-1].get("min") or 0.0, agi[-1].get("max") or 2.0] if agi else None
        if bracket and bracket[0] > bracket[1]:
            bracket = None
        out.append({
            "path": os.path.relpath(path, ROOT).replace("\\", "/"),
            "char": h.get("char"),
            "species": res.rsplit("/", 1)[-1],
            "res": res,
            "gob": eng.gob,
            "attr": h.get("attr") or {},
            "hard": h.get("hard") or 0,
            "soft": h.get("soft") or 0,
            "hand": hand,
            "ql": ql,
            "deck": deck,
            "mine": list(engaged[-1].get("mine") or [0, 0, 0, 0]),
            "myip": engaged[-1].get("myip") or 0,
            "free": [m["move"] for m in free],
            "line": line,
            "agi": bracket,
            "buffs": held_buffs(log.rows, t0),
            "trace": trace,
            # The creature's own actions over the same span as the line, for tools/OpeningDrift's
            # pace check: every foe move row on it from the first card of the line to the kill.
            "foe_acts": sum(1 for m in eng.moves if m.get("actor") == "foe"
                            and t0 <= m["t"] <= t1 + KILL_CARD_MS),
            "logged": {
                "ticks": round((t1 - t0) / TICK_MS),
                "dealt": sum(d.get("v") or 0 for d in on_foe),
                "soft": ch["SHP"], "hard": ch["HHP"], "soaked": ch["ARM"],
                "cards": len(line),
                "restored": round(sum(1 for r in line if r in sheet_restores) / float(len(line)), 3),
            },
        })
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.environ.get("TEMP", "."), "solo_lines.jsonl"))
    ap.add_argument("--chars", default=",".join(OURS))
    ap.add_argument("--min-cards", type=int, default=3)
    a = ap.parse_args(argv)
    chars = set(c.strip() for c in a.chars.split(","))
    sheet = estimate.load_moves()
    rest = restoring(sheet)
    attacks = attacking(sheet)
    paths = fightlog.pool_logs(os.path.join(ROOT, "data", "combat", "pool"))
    n = 0
    by = Counter()
    with open(a.out, "w", encoding="utf-8") as f:
        for p in paths:
            for j in jobs_from(p, sheet, rest, a.min_cards, chars, attacks):
                f.write(json.dumps(j) + "\n")
                n += 1
                by[(j["char"], j["species"])] += 1
    print("%d clean solo kills -> %s" % (n, a.out))
    for (c, s), k in sorted(by.items(), key=lambda kv: -kv[1])[:25]:
        print("  %-12s %-14s %d" % (c, s, k))
    return 0


if __name__ == "__main__":
    sys.exit(main())
