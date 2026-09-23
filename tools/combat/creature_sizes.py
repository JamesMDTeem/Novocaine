#!/usr/bin/env python3
"""How big and how quick each creature really is, by where it was fought - data/combat/creature_sizes.json.

    python tools/combat/creature_sizes.py [--out FILE]

WHY THIS EXISTS (COMBAT.md §3.13). The live advice sized every creature from the pack's measured
individuals, and those were the wrong population: every bat in individuals.json was a batcave bat,
and no small bat ever became an "individual" because a two-card kill leaves no agility or defence
reading to make one from.

CORRECTED 2026-09-22, THE SAME DAY. The first version read a tile split everywhere - mine bat 16
against batcave 119, wolf mountain snow 226 against rock dungeon 565, moose x4, boar x3 - and nearly
all of it was the reading, not the creatures. It counted as a kill any engagement with a fight-end
award inside it, which in a crowd is another creature's award, and it summed only the damage that
landed while the creature was the SAMPLED one (a third short for fatbats). Counting only this
creature's drawn kills, over its whole file's damage, wolves are 504-575 on every tile, boar ~500,
moose ~850, cave angler ~1,550. What survives is the bat, and for a different reason: batcave bats die
to a drawn blow every time (178 of 178) and bats anywhere else to a blow with NO number every time
(995 of 995), so outside the batcave the log holds only a floor. Adders, stoats, swans, cranes, eagles
and the wood scorpion are the same. Those are published apart - and a floor is NOT a size: the unseen
blow is the fight's biggest, so each such kill is priced (price_undrawn) and sized where most agree.

Agility had the matching fault. For 23 species the pack's agility is only a ceiling - "at most X",
set by our own agility when it was read - and the planner staged the ceiling, while the client's
own bracket said the creature was slower than 0.6 of us in 80-100% of those fights.

So, per species:
  hp_by_tile    the damage that killed it (drawn kills only, fightlog.kill_kind), by the tile we
                stood on, as quantiles (every 5%) where a tile has HP_MIN_KILLS kills or more, and
                "all" over every tile. One figure per creature: its damage over the whole file
                (Log.damage_on), summed over one character's files and folded to the fullest
                witness (a party member's log sees the same fight; summing them double counts).
  undrawn_by_tile  for undrawn kills: {lo, hi, agree, n, floor_max} - the hitpoints most such kills
                agree on, each kill bracketing its creature as (floor, floor + priced blow]
                (consensus). Pack.Opponent.medianHpAbove sizes such a creature at the middle.
  agi_ratio     its agility as a share of ours, from the client's own bracket (Fightview.Relation
                minAgi/maxAgi) at the end of each fight: the geometric middle clipped to [0.5, 2],
                median over fights with a narrowed bracket, where AGI_MIN_FIGHTS or more.
"""

import argparse
import json
import math
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fightlog  # noqa: E402
from solo_lines import ROOT  # noqa: E402
import estimate  # noqa: E402
import model  # noqa: E402
import replay  # noqa: E402

HP_MIN_KILLS = 8
AGI_MIN_FIGHTS = 10
QUANTILES = [i / 20.0 for i in range(21)]


def quantiles(v):
    v = sorted(v)
    return [v[int(round(q * (len(v) - 1)))] for q in QUANTILES]


def price_undrawn(log, eng, moves, weapons):
    """What the undrawn killing blow could have done: its raw damage, or None if unpriceable.

    THE UNSEEN BLOW IS THE BIG ONE. A mine bat reads 6, 19, 34 as the openings build, and then
    the Full Circle that kills it lands with no number; one died to a single Full Circle with
    nothing drawn at all. So an undrawn kill's total is a floor that can sit anywhere from 0 to
    just under the creature's size, and "one blow past the floor" (the first version of this)
    planned a mine bat at 17. The blow is priced exactly the way replay prices every drawn one -
    the card we threw at the kill, our weapon and strength, and the VICTIM's own openings from
    the last `foes` row before the award (it is often not the sampled creature) - so the kill
    brackets the creature's hitpoints as (floor, floor + blow]. Raw damage, before armour, which
    keeps it a ceiling on an armoured creature too.
    """
    attrs = (log.header or {}).get("attr") or {}
    strength = attrs.get("str")
    if (not strength) or (not log.records("foes")):
        return None
    idx = getattr(log, "_kill_index", None)
    if idx is None:
        return None
    dels = idx[1].get(eng.gob, ())
    awards = [d["t"] for d in idx[0] if (d.get("gob") != eng.gob)
              and any(abs(t - d["t"]) <= fightlog.UNDRAWN_KILL_MS for t in dels)]
    if not awards:
        return None
    ta = awards[-1]
    # The card: ours nearest the award. A move row can trail its own blow by 50-100 ms
    # (crowd_lines.KILL_CARD_MS), and the killing card was thrown at most a cooldown before.
    cand = [r for r in log.rows if r.get("ev") == "move" and r.get("actor") == "me"
            and (ta - 2500) <= (r.get("t") or 0) <= (ta + 150)]
    if not cand:
        return None
    mv = min(cand, key=lambda r: abs(r["t"] - ta))
    m = moves.get(mv.get("name"))
    if not m:
        return None
    op = None
    for r in log.foes:
        if (r.get("t") or 0) >= ta:
            break
        for o in (r.get("o") or ()):
            if o and o[0] == eng.gob and len(o) >= 5:
                op = o[1:5]
    if op is None:
        return None
    ci = dict((c, i) for i, c in enumerate(fightlog.COLOURS))
    own = [(op[ci[t.get("colour")]] + 0.5) / 100.0 if op[ci[t.get("colour")]] > 0 else 0.0
           for t in (m.get("attack_types") or []) if t.get("colour") in ci]
    if not own:
        return None
    share, flat = m.get("damage_share"), m.get("damage_flat")
    if share:
        wep = replay.weapon_at(log, weapons, ta)
        if not wep or not wep[1]:
            return None
        return model.raw_damage(wep[0], share, wep[1], strength, model.combined(own))
    if flat:
        blow = model.raw_damage(flat, 1.0, strength, strength, model.combined(own))
        g = replay.gloves_at(log, weapons, ta)
        if g:
            blow += model.raw_damage(g[0], 1.0, g[1], strength, model.combined(own))
        return blow
    return None


def consensus(intervals):
    """The hitpoints the most undrawn kills agree on: (lo, hi, agreeing), from (floor, ceil] pairs.

    The largest agreeing subset, as estimate.ok_boost_by_level reads a card's multiplier - a
    handful of blows are mispriced (a stale opening, a card thrown at another creature) and an
    intersection over all of them is empty. The control is the wiki where it is trustworthy:
    adders come out 70-73 (wiki 70), swans 146-154 (wiki 150).
    """
    iv = [(f, c) for f, c in intervals if c > f]
    if not iv:
        return None
    cands = sorted(set([f + 0.5 for f, _c in iv] + [c for _f, c in iv]))
    count = lambda h: sum(1 for f, c in iv if f < h <= c)
    best = max(count(h) for h in cands)
    good = [h for h in cands if count(h) == best]
    return (min(good), max(good), best)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "data", "combat", "creature_sizes.json"))
    a = ap.parse_args(argv)
    pool = os.path.join(ROOT, "data", "combat", "pool")
    # Per creature and witness: damage summed over that character's files (two sittings both
    # happened), and the tiles it was fought on. Folded to the fullest witness below.
    taken = defaultdict(lambda: defaultdict(int))   # (species, gob) -> char -> damage
    where = defaultdict(Counter)                     # (species, gob) -> tile counts
    drawn = set()                                    # (species, gob) that died to a drawn blow
    undrawn = set()                                  # ... to a blow with no number: a floor
    blows = defaultdict(dict)                        # (species, gob) -> char -> priced undrawn blow
    ratios = defaultdict(list)          # species -> agility ratio per fight
    moves = estimate.load_moves()
    weapons = replay.load_weapons()
    # THE WHOLE POOL. This read os.listdir(pool) and so only the top level - 9,855 of 12,849
    # logs on 2026-09-22 - while ~3,000 sit in per-character folders.
    for path in fightlog.pool_logs(pool):
        try:
            log = fightlog.read(path)
        except Exception:
            continue
        char = (log.header or {}).get("char")
        tiles = defaultdict(Counter)
        whole = Counter()
        brackets = {}
        for r in log.rows:
            ev = r.get("ev")
            if ev == "state" and r.get("tile"):
                tiles[r.get("gob")][r["tile"]] += 1
                whole[r["tile"]] += 1
            elif ev == "agi":
                brackets[r.get("gob")] = (r.get("min") or 0.0, r.get("max") or 2.0)
        done = set()
        for e in log.engagements:
            if not e.res or "kritter" not in e.res:
                continue
            sp = e.res.rsplit("/", 1)[-1]
            key = (sp, e.gob)
            # A DRAWN kill of THIS creature (fightlog.kill_kind), not "an award somewhere in the
            # engagement": that read every bat sampled while another died as killed, at its partial
            # intake. An undrawn kill's total is a floor and is not a size either.
            if e.kill == "drawn":
                drawn.add(key)
            elif e.kill == "undrawn":
                undrawn.add(key)
                b = price_undrawn(log, e, moves, weapons)
                if b is not None:
                    blows[key][char] = b
            if e.gob in done:
                continue
            done.add(e.gob)
            # The creature's damage over the whole file (Log.damage_on) - the engagement holds only
            # what landed while it was the sampled one, a third short for a fatbat in a crowd.
            taken[key][char] += log.taken(e.gob)
            where[key].update(tiles[e.gob] or whole)
            # Once per fight: this appended once per ENGAGEMENT, so a creature re-sampled five
            # times in a crowd put the same bracket in five times.
            lo, hi = brackets.get(e.gob, (0.0, 2.0))
            if (lo > 0 or hi < 2) and lo <= hi:
                lo, hi = max(0.5, lo), min(2.0, hi)
                if lo <= hi:
                    ratios[sp].append(math.sqrt(lo * hi))
    dealt = defaultdict(dict)          # species -> gob -> (damage, tile), fullest witness
    for key in drawn:
        d = max(taken[key].values()) if taken[key] else 0
        t = where[key].most_common(1)
        tile = t[0][0].replace("gfx/tiles/", "") if t else None
        if d > 0:
            dealt[key[0]][key[1]] = (d, tile)
    # Undrawn kills: (floor, floor + priced blow] from the fullest witness that priced the blow.
    brackets_hp = defaultdict(list)    # species -> [(floor, ceil, tile)]
    for key in undrawn - drawn:
        priced = [(taken[key].get(c, 0), b) for c, b in blows[key].items()]
        if not priced:
            continue
        floor, blow = max(priced)
        t = where[key].most_common(1)
        tile = t[0][0].replace("gfx/tiles/", "") if t else None
        brackets_hp[key[0]].append((floor, floor + blow, tile))
    out = {"format": 1,
           "note": "creature_sizes.py - kill hitpoints by tile, and agility as a share of ours from "
                   "the client's bracket. See COMBAT.md 3.13.",
           "species": {}}
    for sp in sorted(set(dealt) | set(brackets_hp) | set(ratios)):
        row = {}
        kills = list(dealt[sp].values())
        if len(kills) >= HP_MIN_KILLS:
            by = defaultdict(list)
            for d, t in kills:
                by[t].append(d)
            row["hp_by_tile"] = {"all": {"n": len(kills), "q": quantiles([d for d, _ in kills])}}
            for t, v in by.items():
                if t and len(v) >= HP_MIN_KILLS:
                    row["hp_by_tile"][t] = {"n": len(v), "q": quantiles(v)}
        bk = brackets_hp[sp]
        if len(bk) >= HP_MIN_KILLS:
            by = defaultdict(list)
            for f, c, t in bk:
                by[t].append((f, c))
            groups = [("all", [(f, c) for f, c, _t in bk])] + [
                (t, v) for t, v in by.items() if t and len(v) >= HP_MIN_KILLS]
            for t, v in groups:
                got = consensus(v)
                if got:
                    row.setdefault("undrawn_by_tile", {})[t] = {
                        "n": len(v), "agree": got[2], "lo": round(got[0], 1), "hi": round(got[1], 1),
                        "floor_max": max(f for f, _c in v)}
        r = sorted(ratios[sp])
        if len(r) >= AGI_MIN_FIGHTS:
            row["agi_ratio"] = {"median": round(r[len(r) // 2], 3), "n": len(r)}
        if row:
            out["species"][sp] = row
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, sort_keys=True)
        f.write("\n")
    print("wrote %s  (%d species)" % (a.out, len(out["species"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
