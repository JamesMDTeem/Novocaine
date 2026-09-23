#!/usr/bin/env python3
"""Deterministic multiprocessing for the corpus sweeps in estimate.py.

Each estimator in estimate.py re-reads the whole 4.5k-file corpus, and the two tools run
a dozen-plus such passes each. This module runs the per-file half of a sweep in a reused
spawn pool and returns small picklable projections to the parent, in exactly the order the
serial loop would have visited the files. Every merge then happens in the parent in that
same order, which is what keeps the output byte-identical to the serial path.

WHY PATHS AND NOT LOGS. A parsed Log cannot cross a process boundary correctly:
Engagement.order is keyed on id(move) (fightlog gives each move a fresh object), and
brackets() looks moves up by the same id(). After a pickle round-trip the keys are stale
and brackets() silently returns (None, None). So workers receive paths only, parse the
file, reduce it immediately, and ship plain data.

WHY CHUNKS. A task per file would be 4556 IPC round-trips per sweep. A chunk is a
contiguous slice of the sorted path list; ordered imap preserves file order across the
pool. Never imap_unordered: every merge below depends on order.

COMBAT_JOBS controls the worker count. Unset means one worker per core; 1 (or 0) forces
the in-process serial path, which runs the very same chunk body so the two cannot drift.
The same serial path is taken whenever the effective count is TWO OR FEWER: on a small
machine the spawn pool costs more than the work a second worker saves, so a real pool is
only ever started above two. Chunk sizing stays about four chunks per worker either way.
PYTHONHASHSEED is deliberately left in os.environ: the suite pins it to 0 and the spawn
children must inherit it.
"""

import atexit
import math
import os
from collections import defaultdict
from multiprocessing import get_context


# ---------------------------------------------------------------------------------
# Pool plumbing. One pool, created lazily and reused for every sweep.
# ---------------------------------------------------------------------------------

def worker_count():
    """How many worker processes to use. COMBAT_JOBS overrides; 1 or 0 means serial."""
    raw = os.environ.get("COMBAT_JOBS")
    if raw is None or not raw.strip():
        return max(1, os.cpu_count() or 1)
    try:
        n = int(raw)
    except ValueError:
        n = os.cpu_count() or 1
    return max(1, n)


def enabled():
    """Whether a pool is worth starting: more than two workers.

    At one or two the spawn pool costs more than the work it saves, so map_chunks runs
    the same chunk body in this process instead. Kept as a predicate so the serial
    fallback and the pool cannot drift.
    """
    return worker_count() > 2


_CTX = None
_POOL = None
_POOL_WORKERS = None
# True while the parent is building the context itself. The context needs measured_mu,
# and building measured_mu is a corpus sweep of its own, so map_chunks must not ask for
# context() during that window or it would recurse into it forever.
_BUILDING = False
# THE POOL THE CONTEXT IS BUILT WITH. The context used to be built entirely in this process -
# gob_species and every sweep measured_mu makes - because the workers need its result. On
# 8514 logs that was 136 of a 285-second regeneration on one core of sixteen (profiled
# 2026-09-16), and it grows with the corpus. None of those sweeps reads the context: the
# gob map is what they build, and measure_mu/ok_boost reach nothing that reads mu or the gob
# map. So they run in a bootstrap pool seeded with the moves alone, which is closed before
# the real pool is made with the finished context.
_BOOT = None


def _in_worker():
    """True inside a pool worker, which is a daemon and may not create children."""
    from multiprocessing import current_process
    return current_process().daemon


def _init_worker(ctx):
    global _CTX
    _CTX = ctx
    import estimate
    # CRITICAL: seed the full-corpus gob map. Without it, each worker's first bucket() or
    # theirs() call reads every file in the corpus for itself.
    estimate._GOB_RES = ctx.get("gob_res")
    # Same for measured mu: mu_bounds() is reached from own_defence_weight() on every
    # log, and an unseeded worker would recompute measure_mu + ok_boost for itself.
    estimate._MU_STATE = ctx.get("mu_state")


def _run_chunk(task):
    sweep_id, chunk = task
    return _SWEEPS[sweep_id](chunk)


def _get_pool(ctx, workers):
    global _POOL, _POOL_WORKERS
    if _POOL is None or _POOL_WORKERS != workers:
        close()
        _POOL = get_context("spawn").Pool(
            processes=workers, initializer=_init_worker, initargs=(ctx,))
        _POOL_WORKERS = workers
    return _POOL


def close():
    global _POOL, _POOL_WORKERS
    if _POOL is not None:
        try:
            _POOL.close()
            _POOL.join()
        finally:
            _POOL = None
            _POOL_WORKERS = None


atexit.register(close)


def context():
    """The corpus-independent inputs every worker needs, built once per process.

    gob_species() belongs here for the same reason it is seeded in the initializer:
    bucket() and theirs() would each trigger a full raw pass in every worker otherwise.
    """
    global _CTX, _BUILDING, _BOOT
    if _CTX is None:
        import estimate
        moves = estimate.load_moves()
        opens = estimate.opens_map(moves)
        if enabled() and not _in_worker():
            _BOOT = _get_pool({"moves": moves, "opens": opens, "gob_res": None,
                               "mu_state": None}, worker_count())
        try:
            gob_res = _gob_species_parallel() if (_BOOT is not None) else estimate.gob_species()
            # measured_mu() must be computed before the real pool exists, because its workers
            # need it in mu_bounds() and must not recompute it. Its sweeps run in the
            # bootstrap pool where there is one, in this process otherwise.
            _BUILDING = True
            try:
                mu_state = estimate.measured_mu()
            finally:
                _BUILDING = False
        finally:
            if _BOOT is not None:
                _BOOT = None
                close()
        _CTX = {"moves": moves, "opens": opens, "gob_res": gob_res,
                "mu_state": mu_state}
    return _CTX


def _gob_species_parallel():
    """gob_species over the whole corpus in the bootstrap pool, merged as the serial loop reads.

    The serial version keeps the FIRST resource any log names for a gob, visiting files in
    default_logs order; the ordered map hands chunks back in that order and setdefault keeps
    the first again, so the map is identical.
    """
    import estimate
    import fightlog
    if estimate._GOB_RES is not None:
        return estimate._GOB_RES
    paths = list(fightlog.default_logs(estimate.ROOT)[0])
    out = {}
    tasks = [("gob_species", c) for c in _chunks(paths, worker_count())]
    for part in _BOOT.imap(_run_chunk, tasks, chunksize=1):
        for gob, res in part.items():
            out.setdefault(gob, res)
    estimate._GOB_RES = out
    return out


def _moves():
    return context()["moves"]


def _opens():
    return context()["opens"]


def _chunks(paths, workers):
    n = len(paths)
    # ~4 chunks per worker, so a slow file cannot leave a core idle at the tail.
    size = max(1, (n + (workers * 4) - 1) // (workers * 4))
    return [paths[i:i + size] for i in range(0, n, size)]


def map_chunks(sweep_id, paths):
    """Run one sweep over paths, returning per-chunk projections in file order.

    With COMBAT_JOBS=1 - or any effective worker count of two or fewer - the chunk body
    runs here, in this process, through the same function the workers run, so the serial
    fallback and the pool cannot diverge. The pool is ordered-imap: file order feeds
    every merge.
    """
    paths = list(paths)
    if _BUILDING and (_BOOT is not None) and not _in_worker() and (len(paths) > 1):
        tasks = [(sweep_id, c) for c in _chunks(paths, worker_count())]
        return list(_BOOT.imap(_run_chunk, tasks, chunksize=1))
    if not enabled() or len(paths) <= 1 or _BUILDING or _in_worker():
        return [_run_chunk((sweep_id, paths))]
    workers = worker_count()
    pool = _get_pool(context(), workers)
    tasks = [(sweep_id, c) for c in _chunks(paths, workers)]
    return list(pool.imap(_run_chunk, tasks, chunksize=1))


# ---------------------------------------------------------------------------------
# Shared small reducers used by the parent after an ordered map.
# ---------------------------------------------------------------------------------

def _extend_dict_of_lists(dst, part):
    """Merge a {key: [items]} projection, preserving first-seen key order."""
    for key, rows in part.items():
        dst.setdefault(key, []).extend(rows)


# ---------------------------------------------------------------------------------
# ok_boost: every uncensored Opportunity Knocks use, (before, after, level).
# ---------------------------------------------------------------------------------

def _kind_of(eng):
    """"player" or "creature", by the opponent's own resource.

    The same marker Pack.Opponent classifies on - gfx/borka/body is a person. Carried on
    an Opportunity Knocks use because the two populations do not behave alike and pooling
    them made a control on a stated constant fail: see estimate_check.opportunity_knocks.
    """
    return "player" if "gfx/borka/body" in (eng.res or "") else "creature"


def _ok_boost_chunk(paths):
    import estimate
    uses = []
    for path in paths:
        try:
            log = estimate.fightlog.read(path)
        except Exception:
            continue
        if estimate.fightlog.is_ranged(log):
            continue
        lv = estimate.levels_for_log(log)
        for eng in log.engagements:
            for m in eng.moves:
                if (m.get("actor") != "me") or (m.get("name") != "Opportunity Knocks"):
                    continue
                # BRACKETS BY FILE POSITION, NOT TIMESTAMP. This used to sort eng.states by
                # `t` and take the last state at or before the move and the first after,
                # which ignores Engagement.brackets()'s INTERVENING-MOVE STOP. When a
                # different card lands between the two states, the older state describes the
                # world before BOTH moves and the +points the other card opened are read as
                # Opportunity Knocks' multiplier. The stop is the primitive every other
                # openings read already uses (foe_card_opens, mu_from_reductions), and OK
                # feeds ok_boost / ok_boost_by_level / measured_mu / the estimate_check OK
                # verdict, so it has to use it too.
                #
                # Evidence, whole corpus at the time of the fix: 75 OK uses, brackets()
                # rejects 4 (5.3%) as un-separable and the old timestamp loop read all four.
                # The four are BonkiDonki-1788646023805-BonkiDonki-147.jsonl t=144832,
                # 0466-1788597602732-BonkiDonki-62.jsonl t=20994,
                # Santa_Samus-1788380742870-Santa_Samus-145.jsonl t=28258 and
                # 0430-1788472990381-ZzxcuV3-2.jsonl t=37217. Skipping them takes the
                # accepted count 75 -> 71; the other 71 readings are unchanged.
                before, after = eng.brackets(m)
                # AND THE ANNOUNCEMENT, WHERE IT BEAT THE MOVE ROW. brackets() pairs on the
                # `move` row; a state between the announcement and that row already carries
                # this card's own effect. Four of the corpus's 102 uses are in that state,
                # and two of them read "the card did nothing" for a card that did exactly
                # what its text says. See Engagement.announced_before.
                # None here is NOT "no announcement" - the function returns brackets'
                # answer unchanged in that case. It is "a move landed between the state and
                # the announcement", which is the same un-separable case brackets() itself
                # returns None for, so the read is dropped rather than fetched from the
                # stale side.
                before = eng.announced_before(m, log.me)
                if (before is None) or (after is None):
                    # The intervening-move stop makes the GAIN un-separable, and dropping
                    # those uses is right for the ratio. But a use against a target with
                    # NOTHING standing carries no ratio at all - ok_boost skips b <= 0 when
                    # it builds the interval - and the module docstring calls that reading
                    # THE DECISIVE ONE: the ordinary rule predicts the largest gain against
                    # an empty opponent, and the corpus shows zero. Recover it by file
                    # position without the stop, and keep it ONLY when the standing before
                    # really was zero, so the other three rejects stay dropped.
                    fb = fa = None
                    for st in eng.states:
                        if st.get("t", 0) <= m.get("t", 0):
                            fb = st.get("foe") or fb
                        elif fa is None:
                            fa = st.get("foe")
                    if (not fb) or (not fa) or max(fb) != 0:
                        continue
                    i = max(range(len(fb)), key=lambda k: fb[k])
                    uses.append((fb[i], fa[i], (lv or {}).get("Opportunity Knocks"),
                                 _kind_of(eng)))
                    continue
                fb, fa = before.get("foe"), after.get("foe")
                if (not fb) or (not fa):
                    continue
                i = max(range(len(fb)), key=lambda k: fb[k])
                uses.append((fb[i], fa[i], (lv or {}).get("Opportunity Knocks"),
                             _kind_of(eng)))
    return uses


# ---------------------------------------------------------------------------------
# weapons_seen: raw per-weapon sightings, reduced in the parent.
# ---------------------------------------------------------------------------------

def _weapons_seen_chunk(paths):
    import estimate
    out = {}
    for path in paths:
        try:
            log = estimate.fightlog.read(path)
        except Exception:
            continue
        ql = {}
        for g in (log.gear or []):
            if g.get("res"):
                ql[g["res"]] = g.get("ql")
        for w in (log.weapons or []):
            res = w.get("res")
            v = w.get("v") or {}
            if (not res) or ("damage" not in v):
                continue
            base = res.rsplit("/", 1)[-1]
            q = ql.get(res)
            rec = out.setdefault(base, {"res": res, "n": 0, "quality": [],
                                        "recovered_base": [], "base_iv": []})
            rec["n"] += 1
            for k in ("damage", "armpen", "range", "grievous"):
                if k in v:
                    rec.setdefault(k, set()).add(round(float(v[k]), 4))
            if q and (q > 0):
                rec["quality"].append(round(q, 4))
                rec["recovered_base"].append(round(v["damage"] / math.sqrt(q / 10.0), 3))
                # What this ONE sighting pins the base to. The tooltip rounds to the nearest
                # whole point (measured 2026-09-21: under rounding every weapon's sightings
                # share one base and it is the wiki's; floor and ceiling each break one), so a
                # shown 24 is anything in [23.5, 24.5) and the base is that over sqrt(ql/10).
                f = math.sqrt(q / 10.0)
                rec["base_iv"].append(((v["damage"] - 0.5) / f, (v["damage"] + 0.5) / f))
    return out


def weapons_seen_merge(parts):
    """Reduce the raw sightings exactly as the old single pass did."""
    merged = {}
    for part in parts:
        for base, rec in part.items():
            dst = merged.setdefault(base, {"res": rec["res"], "n": 0, "quality": [],
                                           "recovered_base": [], "base_iv": []})
            dst["n"] += rec["n"]
            for k in ("damage", "armpen", "range", "grievous"):
                if k in rec:
                    dst.setdefault(k, set()).update(rec[k])
            dst["quality"].extend(rec["quality"])
            dst["recovered_base"].extend(rec["recovered_base"])
            dst["base_iv"].extend(rec.get("base_iv") or ())
    # Sets do not serialise, and a weapon read at two qualities has two damages and one
    # base - so the tooltip figures are kept as sorted lists and the base as a range.
    for base, rec in merged.items():
        for k in ("damage", "armpen", "range", "grievous"):
            if k in rec:
                rec[k] = sorted(rec[k])
        rec["quality"] = sorted(set(rec["quality"]))
        b = sorted(set(rec["recovered_base"]))
        rec["recovered_base"] = {"lo": b[0], "hi": b[-1]} if b else None
        # THE BASE EVERY SIGHTING ALLOWS: the intersection of their rounding intervals, or
        # null when they share none. A span test on the point readings (|hi - lo| <= 0.5, or
        # a flat percentage) cannot tell rounding from a wrong quality curve: a stone axe at
        # quality 6.6 shows 24 for a true 24.34, 1.4% off on its own, while at damage 140 the
        # same test is far looser than the display. This is exact at every quality.
        iv = rec.pop("base_iv", None) or []
        if iv:
            lo = max(a for a, _ in iv)
            hi = min(b for _, b in iv)
            rec["shared_base"] = {"lo": round(lo, 3), "hi": round(hi, 3)} if lo <= hi else None
    return merged


# ---------------------------------------------------------------------------------
# agility_band: raw ratios and per-slice tick sets, reduced in the parent.
# ---------------------------------------------------------------------------------

def _agility_band_chunk(paths):
    import estimate
    moves = _moves()
    ratios = []
    groups = defaultdict(set)
    flat = defaultdict(set)
    # The excluded observations, kept in their own slice map. Nothing downstream mixes
    # them back in; estimate_check reads them to show what the exclusion is worth - the
    # widest slice with them in, against the widest slice without - which is the only form
    # of this control that fails if the gate is deleted.
    #
    # `flat` is the control: moves whose cooldown carries NO scaling term at all, so at a
    # fixed (card, level, initiative) slice the only thing left between the base and the
    # reported ticks would be noise - and there is none, so the slice must read 1.0. The
    # test is model.cooldown_scales, not `not ip_scale`: Dash declares `80 / mu` - divided
    # by the level weighting of OUR card, cooldown_mu=True - and no initiative term, so it
    # moves with the card's level and is not a control. Classifying on ip_scale alone put
    # Dash in here, and its slices across levels (80 at level 1 against 58 at level 4 is the
    # 1.379) read as a broken control - see estimate_check. Nothing about the opponent or
    # anyone's agility enters a manoeuvre's cooldown.
    held = defaultdict(set)
    dropped = 0
    for path in paths:
        try:
            log = estimate.fightlog.read(path)
        except Exception:
            continue
        if estimate.fightlog.is_ranged(log):
            continue
        # A WEAPON CAN CHANGE THE REPORTED COOLDOWN, and this reading's whole claim is
        # that at one card, level and initiative nothing is left in it but the opponent.
        # The `wpn` row has carried Coolmod since the recorder was written and nothing
        # read it. It costs 108 of 20,990 observations, from 19 of 5,599 files and one
        # item (the pickaxe, 1.15), and it is the difference between the widest slice
        # reading 1.2778 and reading 1.1/0.9 exactly. See fightlog.held_coolmod for why
        # these are dropped rather than divided through.
        mods = estimate.fightlog.coolmod_hands(log)
        lv = estimate.levels_for_log(log)
        for eng in log.engagements:
            sp = (eng.res or "?").split("/")[-1]
            for m in eng.moves:
                if m.get("actor") != "me":
                    continue
                name = m.get("name") or m.get("move")
                mv = moves.get(name)
                cd = m.get("cd")
                if (not mv) or (not cd) or (cd <= 0) or (mv.get("cooldown") is None):
                    continue
                # THE IP IS THE POSITION-BEFORE, NOT THE TIMESTAMP-BEFORE. This used to
                # scan the states sorted by `t` and take the last one at or before the
                # move, which reads a state that may sit before an intervening move - the
                # move's own bracket then cannot be separated and the ip it swings on is
                # not the one in that state. brackets() is the primitive every other move
                # read uses; when it says the move is un-separable, read no ip at all
                # rather than a stale one. Across the corpus the two before-states differ
                # for a small slice of our moves, and the divergence matters only because
                # ip is part of the band key, so a wrong ip splits one cooldown ratio into
                # the wrong slice.
                before, _after = eng.brackets(m)
                if before is None:
                    continue
                ip = before.get("myip")
                attack = estimate.model.takes_agility(mv)
                if mods and (estimate.fightlog.held_coolmod(
                        log, m.get("t") or 0, mods) is not None):
                    dropped += 1
                    if attack:
                        held[(name, (lv or {}).get(name), ip)].add(cd)
                    continue
                key = (name, (lv or {}).get(name), ip)
                if attack:
                    groups[key].add(cd)
                    if ((lv or {}).get(name) == 1) and (ip == 0):
                        ratios.append((name, sp, cd / float(mv["cooldown"])))
                elif not estimate.model.cooldown_scales(mv):
                    flat[key].add(cd)
    return (ratios, dict(groups), dict(flat), dropped, dict(held))


# ---------------------------------------------------------------------------------
# agility_control: client bracket vs ours, per species.
# ---------------------------------------------------------------------------------

def _agility_control_chunk(paths):
    import estimate
    moves = _moves()
    out = defaultdict(list)
    for path in paths:
        try:
            log = estimate.fightlog.read(path)
        except Exception:
            continue
        if estimate.fightlog.is_ranged(log):
            continue
        if not log.agility:
            continue
        agi_me = ((log.header or {}).get("attr") or {}).get("agi")
        if not agi_me:
            continue
        obs = defaultdict(list)
        for eng in log.engagements:
            for m in eng.moves:
                if m.get("actor") != "me":
                    continue
                nm = m.get("name")
                mv = moves.get(nm)
                cd = m.get("cd")
                if (not mv) or (not cd) or (cd <= 0):
                    continue
                if not estimate.model.takes_agility(mv):
                    continue
                if mv.get("cooldown") is None:
                    continue
                obs[eng.gob].append((mv["cooldown"], cd))
        best = {}
        for r in log.agility:
            best[r["gob"]] = (r.get("min"), r.get("max"))
        stale = estimate.stale_brackets(log, moves)
        tol = 1.0 + estimate.AGILITY_EDGE_TOL
        for gob, (lo_r, hi_r) in best.items():
            iv = estimate.agility_interval(obs.get(gob, []), agi_me) if obs.get(gob) else None
            clo = (lo_r or 0.0) * agi_me
            chi = float("inf") if (hi_r is None or hi_r >= 2.0) else hi_r * agi_me
            if iv is None:
                agree = None
            else:
                olo, ohi, _capped = iv
                if (olo > ohi) or (clo > chi) or (gob in stale):
                    agree = None
                else:
                    agree = (clo <= ohi * tol) and (olo <= chi * tol)
            sp = (log.names.get(gob) or "?").split("/")[-1]
            out[sp].append((gob, clo, chi, iv[0] if iv else None,
                            iv[1] if iv else None, agree))
    return dict(out)


# ---------------------------------------------------------------------------------
# agi_records_by_species: the client's own bracket records, per species.
# ---------------------------------------------------------------------------------

def _agi_records_chunk(paths):
    import estimate
    out = defaultdict(list)
    for path in paths:
        try:
            log = estimate.fightlog.read(path)
        except Exception:
            continue
        if estimate.fightlog.is_ranged(log):
            continue
        if not log.agility:
            continue
        agi_me = ((log.header or {}).get("attr") or {}).get("agi")
        if not agi_me:
            continue
        stale = estimate.stale_brackets(log, _moves())
        for r in log.agility:
            mn = r.get("min")
            mx = r.get("max")
            if mn == 0 and mx == 2:
                continue
            if mn is None and mx is None:
                continue
            gob = r.get("gob")
            sp = (log.names.get(gob) or "?").split("/")[-1]
            lo = (mn or 0.0) * agi_me
            hi = float("inf") if (mx is None or mx >= 2.0) else mx * agi_me
            out[sp].append({
                "gob": gob,
                "min": mn,
                "max": mx,
                "agiMe": agi_me,
                "lo": lo,
                "hi": hi,
                "file": os.path.basename(path),
                "t": r.get("t"),
                "stale": gob in stale,
            })
    return dict(out)


# ---------------------------------------------------------------------------------
# mu_from_reductions: raw reduction intervals, per (level, card).
# ---------------------------------------------------------------------------------

def _mu_from_reductions_chunk(paths):
    import estimate
    moves = _moves()
    opens = _opens()
    red = dict((n, [(t["colour"], t["pct"]) for t in (m.get("reduces") or [])])
               for n, m in moves.items() if m.get("reduces"))
    out = defaultdict(list)
    spans = defaultdict(list)
    inert = defaultdict(int)
    # Partly cancelled: the card removed something, but less than its listed share, which
    # mu cannot explain. See the comment at the gate below.
    netted = defaultdict(int)
    for path in paths:
        try:
            log = estimate.fightlog.read(path, opens)
        except Exception:
            continue
        if estimate.fightlog.is_ranged(log):
            continue
        lv = estimate.levels_for_log(log)
        for eng in log.engagements:
            for m in eng.moves:
                nm = m.get("name")
                if (m.get("actor") != "me") or (nm not in red):
                    continue
                level = lv.get(nm)
                if not level:
                    continue
                # ANCHORED ON THE ANNOUNCEMENT. A state between a card's
                # announcement and its `move` row already carries that card's
                # effect, so pairing on the move row reads a reduction that has
                # partly happened. This is our own defensive card acting on our own
                # openings, which is exactly the shape that goes wrong. See
                # Engagement.announced_before.
                _b, after_s = eng.brackets(m)
                before_s = eng.announced_before(m, log.me)
                if (before_s is None) or (after_s is None):
                    continue
                bv, av = before_s.get("mine"), after_s.get("mine")
                if not bv or not av:
                    continue
                for colour, pct in red[nm]:
                    i = estimate.fightlog.COLOURS.index(colour)
                    share = pct / 100.0
                    before, after = bv[i], av[i]
                    # Below 8 points the display's truncation is worth more than the
                    # reduction itself, and the interval covers everything.
                    if (before < 8) or (share <= 0):
                        continue
                    if after >= before:
                        inert[(level, nm)] += 1
                        continue
                    lo = (1.0 - ((after + 1.0) / (before + 1.0))) / share
                    hi = (1.0 - (after / before)) / share
                    # A READING BELOW ONE IS NOT A SMALL mu, IT IS A CANCELLED BRACKET.
                    # mu's floor is 1.0 by definition, so a card removing LESS than its
                    # listed share cannot be a measurement of mu - and the one mechanism
                    # that produces it is the one this instrument already declares: a gain
                    # the opponent put on the same colour inside the same bracket nets
                    # against the reduction and can only ever make it look smaller.
                    #
                    # The asymmetry is the whole point and it is not a convenience. A
                    # reading that removes MORE than the card allows is a genuine
                    # contradiction - a mislabelled deck or a wrong level - and must never
                    # be explained away; this control caught exactly that once, a Zig-Zag
                    # Ruse leaving 7 of 20 where the card leaves 10. One that removes LESS
                    # carries no information, because it is a floor below the floor.
                    #
                    # 2026-09-12: without this, one bracket (a level-1 Zig-Zag Ruse leaving
                    # 12 of 13, a single point off a 50% card) put mu(1) at 0.143 to 0.154
                    # and broke the control whose entire job is to contain 1.0.
                    if hi < (1.0 - 1e-9):
                        netted[(level, nm)] += 1
                        continue
                    out[(level, nm)].append((lo + hi) / 2.0)
                    spans[(level, nm)].append((lo, hi, before, after, share))
    return (dict(out), dict(inert), dict(spans), dict(netted))


# ---------------------------------------------------------------------------------
# animal_move_*: raw per-card observations, reduced in the parent.
# ---------------------------------------------------------------------------------

def _animal_ip_deltas(eng, me_gob, out):
    """("ip", card) -> [(delta, before)]: the creature's initiative against us across one of its
    own cards, from the state sampled before it to the one after, with nobody else acting in
    between - see estimate.animal_move_ip."""
    import bisect
    st = [s for s in eng.states if (s.get("foeip") is not None) and (s.get("t") is not None)]
    if len(st) < 2:
        return
    ts = [s["t"] for s in st]
    ev = sorted((m["t"], m.get("actor"), m.get("name") or m.get("move")) for m in eng.moves
                if (m.get("t") is not None) and (m.get("gob") in (None, eng.gob, me_gob)))
    for i, (t, actor, card) in enumerate(ev):
        if (actor != "foe") or not card:
            continue
        j = bisect.bisect_right(ts, t) - 1
        if (j < 0) or (j + 1 >= len(st)):
            continue
        a, b = st[j], st[j + 1]
        if (i and (a["t"] <= ev[i - 1][0])) or ((i + 1 < len(ev)) and (b["t"] >= ev[i + 1][0])) \
                or (b["t"] - t > 400):
            continue
        out[("ip", card)].append((b["foeip"] - a["foeip"], a["foeip"]))


def _animal_cooldowns_chunk(paths):
    import estimate
    gaps = defaultdict(list)
    for p in paths:
        try:
            log = estimate.fightlog.read(p, None)
        except (OSError, ValueError):
            continue
        if not log.rows:
            continue
        agi_me = ((log.header or {}).get("attr") or {}).get("agi")
        me_gob = (log.header or {}).get("megob")
        for eng in log.engagements:
            if getattr(eng, "others_present", True):
                continue
            _animal_ip_deltas(eng, me_gob, gaps)
            if not getattr(eng, "offence_ok", False):
                continue
            seq = defaultdict(list)
            acts = set()
            for m in eng.moves:
                if (m.get("actor") != "foe") or not estimate.theirs(eng, m):
                    continue
                nm, t = m.get("name") or m.get("move"), m.get("t")
                if nm and (t is not None):
                    seq[nm].append(t)
                    acts.add((t, nm))
            # ("next", card): the gap from throwing `card` to this creature's NEXT action,
            # whatever that was. One cooldown per combatant, set by the card thrown, so this
            # is the cooldown the card imposes - see animal_move_cooldowns.
            acts = sorted(acts)
            for (ta, na), (tb, _nb) in zip(acts, acts[1:]):
                d = (tb - ta) / 60.0            # milliseconds to ticks
                if 0.5 < d < 400:
                    gaps[("next", na)].append(d)
                    # And with who it was against: its attacks run on the agility rule ours do.
                    if agi_me and eng.res:
                        gaps[("agi", na)].append((d, agi_me, eng.res))
            # ("same", card): the gap between two throws of the same card - the rotation.
            for nm, ts in seq.items():
                ts = sorted(set(ts))
                for a, b in zip(ts, ts[1:]):
                    d = (b - a) / 60.0
                    if 0.5 < d < 400:
                        gaps[("same", nm)].append(d)
    return dict(gaps)


def _foe_intake(eng, me):
    """Each foe move's damage on US, with every me-damage float counted at most once.

    fightlog.hits() pairs every damage row on us within PAIR_MS of a foe move, so when
    two foes' moves land close together, the SAME me-damage rows are summed into both.
    On the corpus this is not hypothetical: 4,311 of 16,148 foe moves pair me-damage
    in-window, and 663 foe-move pairs sit within PAIR_MS of one another, so the animal
    soak/grievous observations can be averaged over doubled readings - the same A6 shape
    on the intake side.

    WHEN MORE THAN ONE FOE GOB IS IN THE ENGAGEMENT, cluster each me-damage row once and
    hand it to the nearest preceding foe move (nearest following when nothing precedes it
    inside the window). A row can therefore reach one move and only one, and a move that
    loses a contested row simply observes less rather than double counting. A SINGLE-FOE
    ENGAGEMENT IS UNTOUCHED: with one distinct foe gob the per-move sums below are exactly
    fightlog.hits()'s, so the serial single-foe path is byte-identical. The announcement
    veto is the same _announcement_by_other() hits() applies, so cross-calls agree.
    """
    import estimate
    fightlog = estimate.fightlog
    fm = [m for m in eng.moves if m.get("actor") != "me"]
    multi = len(set(m.get("gob") for m in fm)) > 1
    cluster = {}
    if multi:
        stamped = [(m, m.get("t")) for m in fm if m.get("t") is not None]
        for d in eng.damage:
            if d.get("gob") != me:
                continue
            t = d.get("t")
            cands = [m for m, mt in stamped if abs(mt - t) <= fightlog.PAIR_MS]
            if not cands:
                continue
            prec = [m for m in cands if m.get("t") <= t]
            pick = (max(prec, key=lambda m: m.get("t")) if prec
                    else min(cands, key=lambda m: m.get("t")))
            ch = d.get("ch")
            rec = cluster.setdefault(id(pick), {})
            rec[ch] = rec.get(ch, 0) + (d.get("v") or 0)
    out = []
    for m in eng.moves:
        if m.get("actor") == "me":
            continue
        before, _after = eng.brackets(m)
        if before is None:
            continue
        if fightlog._announcement_by_other(eng, me, m) is not None:
            chans = {}
        elif multi:
            chans = cluster.get(id(m), {})
        else:
            chans = {}
            for d in eng.damage:
                if abs(d["t"] - m["t"]) <= fightlog.PAIR_MS and d.get("gob") == me:
                    chans[d["ch"]] = chans.get(d["ch"], 0) + d["v"]
        out.append({"move": m.get("name") or m.get("move"),
                    "shp": chans.get("SHP", 0), "hhp": chans.get("HHP", 0),
                    "soaked": chans.get("ARM", 0)})
    return out


def _animal_soak_chunk(paths):
    import estimate
    band = defaultdict(list)
    for p in paths:
        try:
            log = estimate.fightlog.read(p, None)
        except (OSError, ValueError):
            continue
        if not log.rows:
            continue
        for eng in log.engagements:
            for h in _foe_intake(eng, log.me):
                shp, soak = h["shp"], h["soaked"]
                tot = shp + soak
                if (tot < 4) or (tot >= 8):
                    continue                    # matched band, so size cannot explain it
                nm = h["move"]
                if nm:
                    band[nm].append(soak / float(tot))
    return dict(band)


def _animal_restores_chunk(paths):
    import estimate
    obs = defaultdict(lambda: defaultdict(list))
    for p in paths:
        try:
            log = estimate.fightlog.read(p, None)
        except (OSError, ValueError):
            continue
        if not log.rows:
            continue
        for eng in log.engagements:
            for m in eng.moves:
                if (m.get("actor") != "foe") or not estimate.theirs(eng, m):
                    continue
                nm = m.get("name") or m.get("move")
                if not nm:
                    continue
                # The creature's own announcement, on the creature's own gob - see
                # Engagement.announced_before, which reads the actor off the move
                # row for anything that is not ours.
                _b, after = eng.brackets(m)
                before = eng.announced_before(m, log.me)
                if (before is None) or (after is None):
                    continue
                bv, av = before.get("foe"), after.get("foe")
                if not bv or not av:
                    continue
                for c in range(4):
                    if bv[c] >= 5:
                        obs[nm][c].append(max(0, bv[c] - av[c]) / float(bv[c]))
    # Plain dicts out: a worker result with a lambda default_factory will not pickle.
    return dict((nm, dict(bycol)) for nm, bycol in obs.items())


def _animal_grievous_chunk(paths):
    import estimate
    obs = defaultdict(list)
    for p in paths:
        try:
            log = estimate.fightlog.read(p, None)
        except (OSError, ValueError):
            continue
        if not log.rows:
            continue
        for eng in log.engagements:
            for h in _foe_intake(eng, log.me):
                shp, hhp = h["shp"], h["hhp"]
                nm = h["move"]
                if nm and (shp > 0):
                    obs[nm].append(hhp / float(shp))
    return dict(obs)


# ---------------------------------------------------------------------------------
# estimate_check's own corpus sweep: four whole-corpus counts.
# ---------------------------------------------------------------------------------

def _corpus_sweep_chunk(paths):
    import estimate
    moves = _moves()
    opens = _opens()
    scaling = set(n for n, m in moves.items() if m.get("stance") and m.get("attack_mult"))
    gains = thrown = openers = stance_fights = 0
    for pth in paths:
        try:
            log = estimate.fightlog.read(pth, opens)
        except Exception:
            continue
        if not log.rows:
            continue
        lv = estimate.levels_for_log(log)
        if lv and any(lv.get(n) for n in scaling):
            stance_fights += 1
        for eng in log.engagements:
            gains += sum(1 for g in estimate.fightlog.attributed_gains(
                eng, opens, log.me) if g[0] == "me")
            for m in eng.moves:
                if m.get("actor") != "me":
                    continue
                thrown += 1
                if opens.get(m.get("name") or m.get("move")):
                    openers += 1
    return {"gains": gains, "thrown": thrown, "openers": openers,
            "stance_fights": stance_fights}


# ---------------------------------------------------------------------------------
# collect: the big one. Per-file projections are merged in estimate._merge_per.
# ---------------------------------------------------------------------------------

def _collect_chunk(paths):
    import estimate
    # The card-sheet table is process-global and first-wins. Clearing it per chunk makes
    # each chunk return its own first occurrences, so the parent's setdefault in chunk
    # order reproduces the serial first-occurrence order exactly.
    estimate._CARD_SHEET.clear()
    per = {}
    foe = {}
    for p in paths:
        part, foe_delta = estimate._collect_file(p, _moves(), _opens())
        estimate._merge_per(per, part)
        estimate._merge_foe(foe, foe_delta)
    return per, foe, dict(estimate._CARD_SHEET)


# ---------------------------------------------------------------------------------
# measure_mu: the raw Take Aim parse; the per-level intersection is order-independent.
# ---------------------------------------------------------------------------------

def _measure_mu_chunk(paths):
    import estimate
    per = {}
    suspect = {}
    for path in paths:
        p, s = estimate._measure_mu_file(path)
        for level, bands in p.items():
            per.setdefault(level, []).extend(bands)
        for level, bands in s.items():
            suspect.setdefault(level, []).extend(bands)
    return per, suspect


# ---------------------------------------------------------------------------------
# write_characters: per-file character readings, last-wins folded in the parent.
# ---------------------------------------------------------------------------------

def _write_characters_chunk(paths):
    import estimate
    wep = estimate._weapons_pack_map()
    out = []
    for p in paths:
        rec = estimate._character_file(p, wep)
        if rec is not None:
            out.append(rec)
    return out


def _gob_species_chunk(paths):
    import estimate
    return estimate.gob_species(paths)


_SWEEPS = {
    "gob_species": _gob_species_chunk,
    "ok_boost": _ok_boost_chunk,
    "measure_mu": _measure_mu_chunk,
    "write_characters": _write_characters_chunk,
    "weapons_seen": _weapons_seen_chunk,
    "agility_band": _agility_band_chunk,
    "agility_control": _agility_control_chunk,
    "agi_records": _agi_records_chunk,
    "mu_from_reductions": _mu_from_reductions_chunk,
    "animal_cooldowns": _animal_cooldowns_chunk,
    "animal_soak": _animal_soak_chunk,
    "animal_restores": _animal_restores_chunk,
    "animal_grievous": _animal_grievous_chunk,
    "corpus_sweep": _corpus_sweep_chunk,
    "collect": _collect_chunk,
}
