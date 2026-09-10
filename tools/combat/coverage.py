#!/usr/bin/env python3
"""Where the corpus dies, counted through the real gates rather than a copy of them.

    python tools/combat/coverage.py

WHY THIS EXISTS. Every other tool here reports what the corpus DOES say. This one reports
what it fails to say and why, which is the number that decides where the next hour goes.
An audit that is not a program gets done once and is stale a week later; this one runs.

It calls the same functions the estimator calls, so it cannot drift from them. A gate that
changes shows up here on the next run without anyone remembering to update a second copy.

Stdlib only.
"""

import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estimate  # noqa: E402
import fightlog  # noqa: E402


def survey(paths):
    moves = estimate.load_moves()
    opens = estimate.opens_map(moves)
    eng = Counter()
    card = Counter()
    logs = Counter()
    for p in sorted(paths):
        try:
            log = fightlog.read(p, opens)
        except Exception:
            logs["unreadable"] += 1
            continue
        if not log.rows:
            logs["empty"] += 1
            continue
        logs["read"] += 1
        if fightlog.is_ranged(log):
            logs["ranged"] += 1
        party = set()
        for r in log.party:
            party |= set(r.get("gobs") or [])
        party.discard(log.me)
        if party:
            logs["fought alongside a party"] += 1
        for e in log.engagements:
            eng["total"] += 1
            sts = getattr(e, "states", None) or ()
            mine = [m for m in e.moves if m.get("actor") == "me"]
            # The real gate, asked rather than reproduced.
            got = [g for g in fightlog.attributed_gains(e, opens, log.me)
                   if g[0] == "me"]
            if got:
                eng["produced attributed gains"] += 1
                card["gains attributed"] += len(got)
            elif not sts:
                eng["no state rows at all"] += 1
            elif not mine:
                eng["we never threw a card"] += 1
            elif any(sts[0].get("foe") or ()) and not getattr(e, "rejoined", False):
                eng["under way before we arrived, and not ours"] += 1
            else:
                eng["we acted, nothing survived attribution"] += 1
            if not e.offence_ok:
                eng["  (also: not clean enough for the damage half)"] += 1
                # ALLY OR STRANGER. The damage half cannot use either - there is no
                # per-observation test that can say whose damage number is whose, and it
                # was re-measured on 2026-09-10 at rms 39.12 in a party and 25.85 outside
                # one, against 3.16 clean. But which of the two it is decides what to do
                # about it: a stranger is bad luck, and a party is a choice.
                eng["    with a party of ours" if party
                    else "    with somebody who is not ours"] += 1
            for m in mine:
                card["cards thrown"] += 1
                can = opens.get(m.get("name") or m.get("move"))
                if can is None:
                    card["  no opening data for the card"] += 1
                elif not can:
                    card["  card opens nothing by design"] += 1
                else:
                    b, a = e.brackets(m)
                    if (b is None) or (a is None):
                        card["  no state row on one side of it"] += 1
    return logs, eng, card


def main(argv):
    paths = []
    for a in argv:
        import glob
        paths.extend(sorted(glob.glob(a)))
    if not paths:
        paths = fightlog.default_logs(estimate.ROOT)[0]
    logs, eng, card = survey(paths)
    print("\n%d log(s)\n" % logs["read"])
    tot = eng["total"] or 1
    print("ENGAGEMENTS")
    for k in sorted(eng, key=lambda k: -eng[k]):
        if k == "total":
            continue
        print("  %-46s %6d  (%.1f%%)" % (k, eng[k], 100.0 * eng[k] / tot))
    print("  %-46s %6d" % ("total", eng["total"]))
    t2 = card["cards thrown"] or 1
    print("\nOUR CARDS")
    for k in sorted(card, key=lambda k: -card[k]):
        if k == "cards thrown":
            continue
        print("  %-46s %6d  (%.1f%%)" % (k, card[k], 100.0 * card[k] / t2))
    print("  %-46s %6d" % ("thrown in total", card["cards thrown"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
