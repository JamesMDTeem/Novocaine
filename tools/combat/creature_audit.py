#!/usr/bin/env python3
"""How well the pack's creatures match the logs: their blows, the openings their cards make on us,
their grievous wounds, and the order they throw their cards in.

    python tools/combat/creature_audit.py [species ...]

James (2026-09-28): "verify animal damage/strength, their own moves being accurate in terms of
grievous + openings + base damage and their move policy is accurate". Every number here is the
pack AS THE CLIENT LOADS IT (Pack.Cards.move: a species' own coefficient where it has one, the
pooled card figure otherwise; openings times the species factor) scored against clean creature
engagements - the engagements whose defence reading is sound (Engagement.defence_ok), the same
population creature_damage_check.py scores.

  DAMAGE   observed swing (SHP + ARM, before armour) against coef * combined^2 in the card's attack
           colours, in total per (species, card). A cell that uses its OWN coefficient is scored
           in-sample and on held-out creatures (fit on half the gobs, predicted on the other half);
           a cell that uses the POOLED coefficient is scored as-is - that is the case with no data
           of its own to lean on, and for the cells that do have their own data the pooled figure
           is ALSO scored with that species left out of the pool, which is the test of the
           fallback the others rely on.
  OPENINGS the gain each creature card makes on us per colour (fightlog.attributed_gains) against
           the pack's pct * species factor * (1 - standing), read as the implied scale - which the
           client multiplies by cbrt(pressure reference / our block weight) and which should sit
           near 1 and hold steady - and the share of the gain landing in colours the pack says
           the card does not open.
  GRIEVOUS the HHP channel of each creature blow on us against what the pack carries.
  POLICY   how often a creature throws the same card twice running, against the random draw
           (sum p^2) and against the client's deterministic quota deal (Repertoire.pick) - which
           says whether the game draws or deals - and the pack's own held-out gain for its rule.
"""

import json
import math
import os
import random
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fightlog  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
COLOURS = ("green", "blue", "yellow", "red")
CIDX = {"g": 0, "b": 1, "y": 2, "r": 3}
MIN_N = 20

_OPENS = None


def _opens():
    global _OPENS
    if _OPENS is None:
        import estimate
        _OPENS = estimate.opens_map(estimate.load_moves())
    return _OPENS


def _file(path):
    """One log's creature evidence: (blows, gains, sequences)."""
    blows, gains, seqs = [], [], []
    try:
        log = fightlog.read(path, _opens())
    except Exception:
        return blows, gains, seqs
    for eng in log.engagements:
        if not eng.res or "kritter" not in eng.res:
            continue
        sp = eng.res.rsplit("/", 1)[-1]
        seq = [m.get("move") for m in sorted(eng.moves, key=lambda m: m.get("t") or 0)
               if (m.get("actor") == "foe") and m.get("move")]
        if seq:
            seqs.append((sp, eng.gob, seq, bool(eng.defence_ok)))
        if not eng.defence_ok:
            continue
        for h in fightlog.hits(eng, log.me):
            if h.get("actor") == "me" or not h.get("move"):
                continue
            blows.append((sp, eng.gob, h["move"], list(h.get("openings") or (0, 0, 0, 0)),
                          (h.get("shp") or 0) + (h.get("soaked") or 0), h.get("shp") or 0,
                          h.get("hhp") or 0))
        for actor, mv, colour, standing, gain in fightlog.attributed_gains(eng, _opens(), log.me):
            if actor == "me":
                continue
            gains.append((sp, eng.gob, mv, colour, standing, gain))
    return blows, gains, seqs


def load_pack():
    with open(os.path.join(ROOT, "data", "combat", "animal_moves_measured.json"), encoding="utf-8") as f:
        cards = json.load(f)
    with open(os.path.join(ROOT, "data", "combat", "opponents.json"), encoding="utf-8") as f:
        opp = json.load(f)["opponents"]
    opp = opp if isinstance(opp, list) else list(opp.values())
    return dict((m["name"], m) for m in cards["moves"]), cards.get("species_factor") or {}, \
        dict((o["name"], o) for o in opp)


def coef_of(card, sp):
    """(coefficient, "own"|"pooled") as Pack.Cards.move resolves it."""
    d = (card or {}).get("damage") or {}
    own = ((d.get("by_species") or {}).get(sp) or {}).get("coef")
    if own is not None:
        return own, "own"
    return d.get("coef"), "pooled"


def colours_of(card):
    cols = ((card or {}).get("damage") or {}).get("colours")
    return [CIDX[c] for c in cols if c in CIDX] if cols else [0, 1, 2, 3]


def combined_in(openings, idx):
    p = 1.0
    for i in idx:
        p *= 1.0 - min(openings[i], 100) / 100.0
    return 1.0 - p


def ratio_coef(pairs):
    den = sum(c * c for _sw, c in pairs)
    return (sum(sw for sw, _c in pairs) / den) if den > 0 else None


def damage_section(blows, cards, only):
    print("DAMAGE - total swing observed / predicted, per species and card (1.00 is exact)")
    print("  in = the pack's own coefficient on every blow; held-out = fitted on half the creatures,")
    print("  scored on the rest; pooled-lo = the pooled figure with this species left out of it.\n")
    cells = defaultdict(list)
    for sp, gob, mv, op, swing, _shp, _hhp in blows:
        if only and sp not in only:
            continue
        card = cards.get(mv)
        if card is None:
            continue
        c = combined_in(op, colours_of(card))
        if c < 0.05:
            continue
        cells[(sp, mv)].append((swing, c, gob))
    print("  %-12s %-22s %5s %6s %8s %8s %9s %9s" % ("species", "card", "n", "coef", "source",
                                                      "in", "held-out", "pooled-lo"))
    worst = []
    tot_obs = tot_pred = 0.0
    fallback = []
    for (sp, mv), rows in sorted(cells.items(), key=lambda kv: (-len(kv[1]))):
        if len(rows) < MIN_N:
            continue
        card = cards[mv]
        coef, src = coef_of(card, sp)
        if coef is None:
            continue
        obs = sum(s for s, _c, _g in rows)
        pred = sum(coef * c * c for _s, c, _g in rows)
        tot_obs += obs
        tot_pred += pred
        # Held out: half the creatures fit the coefficient, the other half are scored.
        gobs = sorted(set(g for _s, _c, g in rows))
        rnd = random.Random(1)
        rnd.shuffle(gobs)
        a = set(gobs[: len(gobs) // 2])
        fit = ratio_coef([(s, c) for s, c, g in rows if g in a])
        test = [(s, c) for s, c, g in rows if g not in a]
        held = (sum(s for s, _c in test) / sum(fit * c * c for _s, c in test)) \
            if (fit and test and sum(c for _s, c in test) > 0) else None
        # The pooled fallback, WITHOUT this species in the pool.
        others = [(s, combined_in(op, colours_of(card)))
                  for sp2, _g, mv2, op, s, _shp, _h in blows if (mv2 == mv) and (sp2 != sp)]
        others = [(s, c) for s, c in others if c >= 0.05]
        plo = ratio_coef(others) if len(others) >= MIN_N else None
        plo_ratio = (obs / sum(plo * c * c for _s, c, _g in rows)) if plo else None
        if plo_ratio is not None:
            fallback.append((plo_ratio, sp, mv, len(rows)))
        print("  %-12s %-22s %5d %6.1f %8s %8.2f %9s %9s"
              % (sp[:12], mv[:22], len(rows), coef, src, obs / pred if pred else float("nan"),
                 ("%.2f" % held) if held else "-", ("%.2f" % plo_ratio) if plo_ratio else "-"))
        worst.append((abs(math.log(obs / pred)) if (obs > 0 and pred > 0) else 0, sp, mv, obs / pred if pred else 0))
    print("\n  all cells: observed / predicted %.3f over the corpus" % (tot_obs / tot_pred if tot_pred else float("nan")))
    if fallback:
        lr = sorted(r for r, _s, _m, _n in fallback)
        print("  the POOLED card figure on species it did not see: median %.2f, range %.2f-%.2f over %d cells"
              % (lr[len(lr) // 2], lr[0], lr[-1], len(lr)))
        print("  - what a thrower with no blows of its own used to be priced at. Worst:")
        for r, sp, mv, n in sorted(fallback, key=lambda x: -abs(math.log(x[0])))[:6]:
            print("      %-12s %-22s x%.2f (n=%d)" % (sp, mv, r, n))
    strength_section(blows, cards)
    return cells


def _rank1(cells, iters=60):
    """coef[species, card] = base[card] * strength[species], alternating ratios of totals - the fit
    estimate._derive_by_strength publishes."""
    sps = sorted(set(s for s, _c in cells))
    cds = sorted(set(c for _s, c in cells))
    S = dict((s, 1.0) for s in sps)
    B = {}
    for c in cds:
        rows = [x for (_s, cc), v in cells.items() if cc == c for x in v]
        den = sum(x * x for _sw, x in rows)
        B[c] = (sum(sw for sw, _x in rows) / den) if den else 0.0
    for _ in range(iters):
        for s in sps:
            num = sum(sw for (ss, _c), v in cells.items() if ss == s for sw, _x in v)
            den = sum(B[c] * x * x for (ss, c), v in cells.items() if ss == s for _sw, x in v)
            S[s] = (num / den) if den else 1.0
        for c in cds:
            num = sum(sw for (_s, cc), v in cells.items() if cc == c for sw, _x in v)
            den = sum(S[s] * x * x for (s, cc), v in cells.items() if cc == c for _sw, x in v)
            B[c] = (num / den) if den else 0.0
    return S, B


def strength_section(blows, cards):
    """The fallback the pack now uses, held out: each (species, card) cell with blows of its own is
    left out and predicted from card base x species strength fitted on every other cell."""
    cells = defaultdict(list)
    for sp, _g, mv, op, swing, _shp, _hhp in blows:
        card = cards.get(mv)
        if (card is None) or str(sp).startswith(("body#", "?#")):
            continue
        c = combined_in(op, colours_of(card))
        if c >= 0.05:
            cells[(sp, mv)].append((swing, c))
    cells = dict((k, v) for k, v in cells.items() if len(v) >= 10)
    out = []
    for key, rows in cells.items():
        rest = dict((k, v) for k, v in cells.items() if k != key)
        if not any(s == key[0] for s, _c in rest) or not any(c == key[1] for _s, c in rest):
            continue
        S, B = _rank1(rest)
        pred = S[key[0]] * B[key[1]] * sum(x * x for _sw, x in rows)
        obs = sum(sw for sw, _x in rows)
        if pred > 0 and obs > 0:
            out.append(obs / pred)
    if out:
        lg = sorted(abs(math.log(r)) for r in out)
        print("  card base x species STRENGTH on the same held-out cells (what the pack uses now): median "
              "x%.2f, 90th percentile x%.2f, over %d cells"
              % (math.exp(lg[len(lg) // 2]), math.exp(lg[int(0.9 * (len(lg) - 1))]), len(lg)))


def openings_section(gains, cards, factor, only):
    print("\nOPENINGS - what a creature card opens on us, against the pack")
    print("  scale = observed gain / (pct * species factor * (1 - standing)); the client multiplies")
    print("  by cbrt(reference / our block weight), so a sound card sits near 1 and holds steady.")
    print("  stray = share of the card's observed gain in colours the pack says it does not open.\n")
    by = defaultdict(list)
    for sp, gob, mv, colour, standing, gain in gains:
        if only and sp not in only:
            continue
        by[(sp, mv)].append((colour, standing, gain))
    print("  %-12s %-22s %5s %-22s %7s %13s %6s" % ("species", "card", "n", "pack opens", "scale",
                                                    "p25-p75", "stray"))
    scales_all = []
    flagged = []
    for (sp, mv), rows in sorted(by.items(), key=lambda kv: -len(kv[1])):
        if len(rows) < MIN_N:
            continue
        card = cards.get(mv)
        if card is None:
            continue
        f = factor.get(sp, 1.0)
        op = card.get("openings") or {}
        pct = dict((c, (op.get(c) or 0) * f) for c in COLOURS)
        ks, stray, total = [], 0.0, 0.0
        for colour, standing, gain in rows:
            total += max(gain, 0)
            if pct.get(colour, 0) <= 0:
                stray += max(gain, 0)
                continue
            room = 1.0 - min(standing, 99) / 100.0
            if room > 0.05:
                ks.append(gain / (pct[colour] * room))
        ks.sort()
        med = ks[len(ks) // 2] if ks else None
        q1 = ks[len(ks) // 4] if ks else None
        q3 = ks[(3 * len(ks)) // 4] if ks else None
        sshare = stray / total if total else 0.0
        if med is not None:
            scales_all.append(med)
        opens_txt = " ".join("%s%.0f" % (c[0], v) for c, v in pct.items() if v > 0) or "nothing"
        print("  %-12s %-22s %5d %-22s %7s %13s %5.0f%%"
              % (sp[:12], mv[:22], len(rows), opens_txt[:22], ("%.2f" % med) if med is not None else "-",
                 ("%.2f-%.2f" % (q1, q3)) if ks else "-", 100 * sshare))
        if (med is not None and (med < 0.6 or med > 1.6)) or sshare > 0.25:
            flagged.append((sp, mv, med, sshare, len(rows)))
    if scales_all:
        s = sorted(scales_all)
        print("\n  median scale over cards: %.2f (p25 %.2f, p75 %.2f, %d cards)"
              % (s[len(s) // 2], s[len(s) // 4], s[(3 * len(s)) // 4], len(s)))
    if flagged:
        print("  off by more than 0.6-1.6, or more than a quarter of the gain in unlisted colours:")
        for sp, mv, med, sshare, n in flagged:
            print("      %-12s %-22s scale %s, stray %.0f%% (n=%d)"
                  % (sp, mv, ("%.2f" % med) if med is not None else "-", 100 * sshare, n))


def grievous_section(blows, cards, only):
    print("\nGRIEVOUS - hard hitpoints (HHP) taken off us, per creature card")
    print("  per_soft = HHP / soft damage, observed against the pack's figure. FoeModel.strike does not")
    print("  apply a creature card's grievous share at all (BeastMove.grievous is read by nothing), so")
    print("  the planner prices none of this.\n")
    agg = defaultdict(lambda: [0, 0, 0.0, 0.0])
    for sp, _g, mv, _op, _sw, shp, hhp in blows:
        if only and sp not in only:
            continue
        a = agg[(sp, mv)]
        a[0] += 1
        a[1] += 1 if hhp > 0 else 0
        a[2] += shp
        a[3] += hhp
    rows = [(k, v) for k, v in agg.items() if v[1] > 0]
    print("  %-12s %-22s %6s %8s %9s %10s %9s" % ("species", "card", "blows", "with HHP", "HHP", "per_soft",
                                                  "pack"))
    tot = 0.0
    for (sp, mv), (n, nh, shp, hhp) in sorted(rows, key=lambda kv: -kv[1][3]):
        g = (cards.get(mv) or {}).get("grievous") or {}
        print("  %-12s %-22s %6d %8d %9.0f %10.3f %9s" % (sp[:12], mv[:22], n, nh, hhp,
                                                          hhp / shp if shp else float("nan"),
                                                          ("%.3f" % g["per_soft"]) if g.get("per_soft") is not None else "-"))
        tot += hhp
    allhp = sum(v[2] for v in agg.values())
    print("\n  HHP from creatures: %.0f of %.0f soft damage (%.1f%%) - lasting damage the planner does not see"
          % (tot, allhp, 100.0 * tot / allhp if allhp else 0))


def quota_repeats(mix, n=2000):
    """The client's deal (Repertoire.pick, the D'Hondt quota): repeat rate of the sequence it deals."""
    cards = [c for c, p in mix if p > 0]
    p = [pp for _c, pp in mix if pp > 0]
    s = sum(p)
    p = [x / s for x in p]
    owed = [0.0] * len(p)
    thrown = [0] * len(p)
    prev, rep = None, 0
    for _ in range(n):
        best, bd = 0, None
        for i in range(len(p)):
            owed[i] += p[i]
            d = owed[i] / (thrown[i] + 1)
            if (bd is None) or (d > bd):
                best, bd = i, d
        thrown[best] += 1
        if best == prev:
            rep += 1
        prev = best
    return rep / float(n - 1)


def policy_section(seqs, opp, only):
    print("\nPOLICY - does the creature DRAW its cards or DEAL them?")
    print("  repeat = how often the next card is the one just thrown. A random draw from the mix repeats")
    print("  sum(p^2) of the time; the client's quota deal (Repertoire.pick) repeats far less. Solo,")
    print("  clean engagements only. held-out = the pack's own test of its state rule (bits gained).\n")
    used = defaultdict(Counter)
    pairs_n = Counter()
    repeats = Counter()
    for sp, _g, seq, ok in seqs:
        if only and sp not in only:
            continue
        if not ok:
            continue
        used[sp].update(seq)
        for a, c in zip(seq, seq[1:]):
            pairs_n[sp] += 1
            repeats[sp] += 1 if a == c else 0
    print("  %-12s %6s %8s %8s %8s %9s %s" % ("species", "pairs", "logged", "draw", "deal", "held-out",
                                              "verdict"))
    closer_draw = closer_deal = 0
    for sp in sorted(pairs_n, key=lambda k: -pairs_n[k]):
        cnt, pairs, rep = used[sp], pairs_n[sp], repeats[sp]
        if pairs < 50:
            continue
        n = float(sum(cnt.values()))
        mix = [(c, v / n) for c, v in cnt.most_common()]
        draw = sum(p * p for _c, p in mix)
        deal = quota_repeats(mix)
        obs = rep / float(pairs)
        pm = ((opp.get(sp) or {}).get("policy_model") or {}).get("confidence") or {}
        held = pm.get("held_out_gain_bits")
        # A binomial standard error on the logged rate, to say which it is nearer beyond noise.
        se = math.sqrt(max(obs * (1 - obs), 1e-9) / pairs)
        if abs(obs - draw) < abs(obs - deal):
            closer_draw += 1
            verdict = "nearer draw" + ("" if abs(obs - draw) < 2 * se else ", and repeats MORE than it")
        else:
            closer_deal += 1
            verdict = "nearer deal" + ("" if abs(obs - deal) < 2 * se else ", but off it")
        print("  %-12s %6d %7.0f%% %7.0f%% %7.0f%% %9s %s"
              % (sp[:12], pairs, 100 * obs, 100 * draw, 100 * deal,
                 ("%.3f" % held) if held is not None else "-", verdict))
    print("\n  nearer a random draw: %d species; nearer the deterministic deal: %d" % (closer_draw, closer_deal))
    print("  A creature that repeats more than a random draw is STICKY: the card it just threw predicts")
    print("  the next. Knowing it gains 0.06-0.34 bits a decision on held-out creatures (2026-09-28).")


def main(argv):
    only = set(argv)
    cards, factor, opp = load_pack()
    import estimate_parallel
    blows, gains, seqs = [], [], []
    for b, g, s in estimate_parallel.ordered_map(_file, sorted(fightlog.default_logs()[0])):
        blows.extend(b)
        gains.extend(g)
        seqs.extend(s)
    print("creature evidence: %d clean blows on us, %d attributed opening gains, %d engagements\n"
          % (len(blows), len(gains), len(seqs)))
    damage_section(blows, cards, only)
    openings_section(gains, cards, factor, only)
    grievous_section(blows, cards, only)
    policy_section(seqs, opp, only)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
