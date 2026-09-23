#!/usr/bin/env python3
"""Read tools/StrategyVsPlayer.java's results: is the model right, and does the planner win?

    python tools/combat/strategy_report.py RESULTS.jsonl [--pick live|fast|safe] [--min N]

THREE QUESTIONS, IN THE ORDER THEY DEPEND ON EACH OTHER.

1. CALIBRATION - the player's own cards, stepped through the model against the creature that
   was actually fought. `credit` is the share of that creature's real hitpoints the model gives
   those cards: 1.0 is exact, 0.9 means the model under-credits them by a tenth. "Killed or not"
   was the first test and it was the wrong one - a line that leaves a 31-point bat on 2.5 read
   as a total failure. `1 card` is the share of fights the model leaves within 1.5 of the line's
   own cards of the kill, which is the fair test for small creatures (see within_a_card); a
   matchup is calibrated on either. `time m/l` is the model's time for the same cards over the log's.

2. THE PLANNER AGAINST THE PLAYER, BOTH IN THE MODEL AND CALIBRATED PER FIGHT, on the three
   metrics James named: ticks to kill, soft hitpoints lost, armour soaked. The creature is staged
   at exactly the damage the player's line did to it in the model (StrategyVsPlayer's `cal` pass),
   so that line kills on its last card as it did in the log and the planner faces the same
   creature in the same units. Two earlier forms of this table were wrong in opposite directions:
   against the LOGGED fight the model's slow clocks counted against the planner (Santa's bats), and
   scaling the player's line by its credit let the player pay the model's damage deficit as a
   fraction while the planner paid it in whole cards. Per fight: DOMINATES (no worse on all three,
   better on one), LOSES (the reverse), TRADES, TIES.

3. WHAT THE PLAYER DID where the logged fight beats the planner's line.
"""

import argparse
import json
import statistics
import sys
from collections import Counter, defaultdict

CREDIT_OK = (0.8, 1.25)


def med(v):
    v = [x for x in v if x == x]
    return statistics.median(v) if v else float("nan")


def load(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def credit(r):
    hp0 = r.get("foeHp0") or 0
    return (hp0 - r["player"].get("foeHp", hp0)) / hp0 if hp0 > 0 else float("nan")


def within_a_card(r, cards=1.5):
    """Whether the model leaves the creature within about one of the line's own cards.

    Credit alone reads small creatures as badly off: a crowd of 15-25 hp bats takes about 8 a card,
    so one card short is a fifth of the crowd, and a line that ends 8 short of 44 reads 0.82 when
    it is exactly one swing from the log (2026-09-21).
    """
    p = r["player"]
    hp0 = r.get("foeHp0") or 0
    if p.get("killed"):
        return True
    took = hp0 - p.get("foeHp", hp0)
    n = p.get("cards") or 0
    if took <= 0 or n <= 0:
        return False
    return p.get("foeHp", hp0) <= cards * (took / float(n))


def verdict(a, b, eps=(1.0, 0.5, 0.5)):
    le = all(x <= y + e for x, y, e in zip(a, b, eps))
    ge = all(x >= y - e for x, y, e in zip(a, b, eps))
    if le and ge:
        return "ties"
    if le:
        return "dominates"
    if ge:
        return "loses"
    return "trades"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("results")
    ap.add_argument("--pick", default="live", choices=("live", "fast", "safe"))
    ap.add_argument("--min", type=int, default=5)
    a = ap.parse_args(argv)
    rows = load(a.results)
    print("%d fights; planner line = %s; staged as %s" % (
        len(rows), a.pick, dict(Counter(r.get("staged") for r in rows))))

    by = defaultdict(list)
    for r in rows:
        by[(r["char"], r["species"])].append(r)
    order = sorted(by, key=lambda k: -len(by[k]))

    print("\n1. IS THE MODEL RIGHT ABOUT THESE FIGHTS?  (the player's cards, model vs log)")
    print("   %-12s %-13s %4s  %7s  %6s  %8s  %11s  %11s" % (
        "character", "creature", "n", "credit", "1 card", "time m/l", "soft hp m/l", "armour m/l"))
    fac = {}
    for k in order:
        rs = by[k]
        if len(rs) < a.min:
            continue
        cr = med([credit(r) for r in rs])
        tq = med([r["player"]["ticks"] / float(r["logged"]["ticks"]) for r in rs if r["logged"]["ticks"]])
        near = sum(1 for r in rs if within_a_card(r)) / float(len(rs))
        ok = (CREDIT_OK[0] <= cr <= CREDIT_OK[1]) or (near >= 0.7)
        if ok:
            fac[k] = cr
        print("   %-12s %-13s %4d  %6.2f  %5.0f%%  %8.2f  %5.1f/%-5.1f  %5.1f/%-5.1f  %s" % (
            k[0], k[1], len(rs), cr, 100 * near, tq,
            med([r["player"]["hp"] for r in rs]), med([r["logged"]["soft"] for r in rs]),
            med([r["player"]["soaked"] for r in rs]), med([r["logged"]["soaked"] for r in rs]),
            "" if ok else "<- model off, excluded below"))

    print("\n2. DOES THE PLANNER BEAT THE PLAYER?  (both lines in the model, calibrated per fight)")
    print("   The creature is staged at exactly the damage your line did to it in the model, so your")
    print("   line kills on its last card as it did in the log, and the planner faces the same creature")
    print("   in the same units. No scaling: each column is a modelled kill.")
    print("   %-12s %-13s %4s | %7s %7s %8s %8s | %4s %4s %5s %4s" % (
        "character", "creature", "n", "you", "planner", "soft hp", "armour", "dom", "lose", "trade", "tie"))
    tot = Counter()
    wins = []
    for k in order:
        if k not in fac:
            continue
        rs = [r for r in by[k]
              if (r.get("cal") or {}).get("player", {}).get("killed")
              and (r.get("cal") or {}).get(a.pick, {}).get("killed")]
        if not rs:
            continue
        v = Counter()
        for r in rs:
            c = r["cal"]
            pl = (c[a.pick]["ticks"], c[a.pick]["hp"], c[a.pick]["soaked"])
            yo = (c["player"]["ticks"], c["player"]["hp"], c["player"]["soaked"])
            w = verdict(pl, yo)
            v[w] += 1
            if w == "loses":
                wins.append(r)
        tot.update(v)
        print("   %-12s %-13s %4d | %7.0f %7.0f %+8.1f %+8.1f | %3.0f%% %3.0f%% %4.0f%% %3.0f%%" % (
            k[0], k[1], len(rs),
            med([r["cal"]["player"]["ticks"] for r in rs]), med([r["cal"][a.pick]["ticks"] for r in rs]),
            med([r["cal"][a.pick]["hp"] - r["cal"]["player"]["hp"] for r in rs]),
            med([r["cal"][a.pick]["soaked"] - r["cal"]["player"]["soaked"] for r in rs]),
            *[100.0 * v[x] / len(rs) for x in ("dominates", "loses", "trades", "ties")]))
    n = sum(tot.values())
    if n:
        print("   ALL %d fights: planner dominates %.0f%%, loses %.0f%%, trades %.0f%%, ties %.0f%%" % (
            n, *[100.0 * tot[x] / n for x in ("dominates", "loses", "trades", "ties")]))
    print("   ticks: median modelled time to kill; soft hp and armour: planner minus you (medians,")
    print("   negative is the planner better)")

    print("\n3. WHERE YOUR LINE BEATS THE PLANNER'S  (%d fights)" % len(wins))
    if wins:
        pc, lc = Counter(), Counter()
        for r in wins:
            pc.update(r["cal"]["player"].get("line", []))
            lc.update(r["cal"][a.pick].get("line", []))
        pt, lt = float(sum(pc.values())) or 1, float(sum(lc.values())) or 1
        cards = sorted(set(pc) | set(lc), key=lambda x: -abs(pc[x] / pt - lc[x] / lt))
        print("   %-22s %8s %8s   (share of cards in each line)" % ("card", "you", "planner"))
        for x in cards[:12]:
            print("   %-22s %7.0f%% %7.0f%%" % (x, 100 * pc[x] / pt, 100 * lc[x] / lt))
        print("   matchups:", ", ".join("%s/%s %d" % (c, s, k) for (c, s), k in
                                       Counter((r["char"], r["species"]) for r in wins).most_common(8)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
