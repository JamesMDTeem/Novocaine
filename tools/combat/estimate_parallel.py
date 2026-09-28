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
import sys
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


# FUSED SWEEPS (2026-09-27). A --write-pack ran a dozen sweeps over the same 15,467 logs, each
# parsing every file again - ~5 s of the ~5 s each took was the parse. prefetch() runs them in ONE
# pass: a worker takes a chunk, and each sweep's own chunk function runs over it in turn against a
# read cache, so a file is parsed once per reading (fightlog.read with and without an opens map)
# instead of once per sweep. The sweeps are grouped by that reading and the cache is dropped between
# groups, and the chunks are a quarter the usual size, so a worker holds ~100 parsed logs and not
# ~500 of each kind (a 480-file chunk in both readings held 662 MB).
#
# What the first consumer of each sweep gets is its parts, one per chunk, in file order - the same
# as map_chunks would hand it, over finer chunks. That changes nothing a consumer computes: each
# folds parts in order, and the chunking already varies with COMBAT_JOBS. --write-pack was diffed
# byte for byte, fused against unfused, when this went in.
#
# Nothing a prefetched sweep reads is written by the pack build (it writes the derived files and
# reads only the logs, the move sheet and the wiki scrapes), so running it early changes no input.
PACK_SWEEPS = (
    # fightlog.read(path) - no opens map
    ("weapons_seen", "agility_band", "agility_control", "agi_records", "flee_points",
     "write_characters", "animal_cooldowns", "animal_soak", "animal_restores", "animal_grievous"),
    # fightlog.read(path, opens)
    ("mu_from_reductions", "collect"),
)

_PREFETCHED = {}


def _fused_chunk(task):
    groups, paths = task
    import fightlog
    real = fightlog.read
    cache = {}

    def read(path, opens=None):
        k = (path, id(opens))
        hit = cache.get(k)
        if hit is None:
            try:
                hit = (True, real(path, opens))
            except Exception as x:
                hit = (False, x)
            cache[k] = hit
        if not hit[0]:
            raise hit[1]
        return hit[1]

    out = []
    fightlog.read = read
    try:
        for group in groups:
            for sid in group:
                out.append((sid, _SWEEPS[sid](paths)))
            cache.clear()
    finally:
        fightlog.read = real
    return out


def prefetch(groups, paths):
    """Run every sweep in `groups` over `paths` in one pass, for map_chunks to hand to the first
    caller of each with the same paths. A no-op wherever map_chunks would not pool."""
    paths = list(paths)
    if (not enabled()) or (len(paths) <= 1) or _in_worker() or _BUILDING:
        return
    key = tuple(paths)
    groups = tuple(tuple(sid for sid in g if (sid, key) not in _SWEPT) for g in groups)
    # A sweep already on disk for exactly this corpus and code is handed over as it is.
    kept = []
    for g in groups:
        rest = []
        for sid in g:
            hit = _stored_sweep(sid, paths)
            if hit is not None:
                _PREFETCHED[(sid, key)] = hit[0]
            else:
                rest.append(sid)
        kept.append(tuple(rest))
    groups = tuple(g for g in kept if g)
    if not groups:
        return
    import time
    t0 = time.time()
    workers = worker_count()
    pool = _get_pool(context(), workers)
    n = len(paths)
    size = max(1, (n + (workers * 16) - 1) // (workers * 16))
    tasks = [(groups, paths[i:i + size]) for i in range(0, n, size)]
    parts = defaultdict(list)
    for res in pool.imap(_fused_chunk, tasks, chunksize=1):
        for sid, part in res:
            parts[sid].append(part)
    for sid, got in parts.items():
        _PREFETCHED[(sid, key)] = got
        _store_sweep(sid, paths, got)
    if os.environ.get("COMBAT_TIMINGS"):
        sys.stderr.write("[fused %-20s %6d files %7.1f s: %s]\n"
                         % ("%d sweeps" % len(parts), n, time.time() - t0,
                            " ".join(sid for g in groups for sid in g)))
        sys.stderr.flush()


def warm():
    """Build (or load) the context now if this process will pool, so what it carries - the gob map,
    measured mu - is in place before anything measures it again. A no-op in a worker, while the
    context is being built, or where no pool would be started."""
    if enabled() and (_CTX is None) and not _BUILDING and not _in_worker():
        context()


def ordered_map(fn, items, chunksize=32):
    """fn over items, results in item order, on a spawn pool that carries NO context.

    For the pack's side scripts (creature_sizes.py, player_lines.py): each file's part reads
    nothing the context holds, so map_chunks would make them pay the gob map and the mu sweeps -
    ~20 s - for nothing. fn must be a module-level function of a module that guards its main.
    Serial where map_chunks would be (COMBAT_JOBS of two or fewer, or already in a worker).
    """
    items = list(items)
    kind = None
    if not _in_worker() and all(isinstance(x, str) for x in items):
        # Named by the function's file and name: under spawn a script's own functions all live
        # in "__main__", and two scripts' _file_part must not share a store.
        mod = sys.modules.get(fn.__module__)
        where = os.path.splitext(os.path.basename(getattr(mod, "__file__", "") or fn.__module__))[0]
        kind = "map.%s.%s" % (where, fn.__qualname__)
        hit = _stored_sweep(kind, items)
        if hit is not None:
            return hit[0]
    if (not enabled()) or (len(items) <= 1) or _in_worker():
        out = [fn(x) for x in items]
    else:
        pool = get_context("spawn").Pool(processes=worker_count())
        try:
            out = list(pool.imap(fn, items, chunksize=chunksize))
        finally:
            pool.terminate()
            pool.join()
    if kind is not None:
        _store_sweep(kind, items, out)
    return out


def _in_worker():
    """True inside a pool worker, which is a daemon and may not create children."""
    from multiprocessing import current_process
    return current_process().daemon


def _init_worker(ctx):
    global _CTX
    if isinstance(ctx, str):
        import pickle
        with open(ctx, "rb") as fh:
            ctx = pickle.load(fh)
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
    global _POOL, _POOL_WORKERS, _POOL_CTX_FILE
    if _POOL is None or _POOL_WORKERS != workers:
        close()
        # THE CONTEXT GOES BY FILE, NOT IN THE SPAWN MESSAGE (2026-09-27). A spawned child imports
        # the parent's __main__ before it reads its process object, and the parent writes that
        # object - initargs and all - into a pipe it blocks on, starting the workers one after
        # another. With a 3.7 MB context and estimate.py as __main__ (1.2 s to import), every
        # pool took ~1.3 s per worker to start: 21.4 s on sixteen, before any work. Handed a path,
        # the message fits the pipe, the parent does not wait, and the children import in parallel.
        import pickle
        import tempfile
        fd, _POOL_CTX_FILE = tempfile.mkstemp(prefix="combat-ctx-", suffix=".pkl")
        with os.fdopen(fd, "wb") as fh:
            pickle.dump(ctx, fh, protocol=pickle.HIGHEST_PROTOCOL)
        _POOL = get_context("spawn").Pool(
            processes=workers, initializer=_init_worker, initargs=(_POOL_CTX_FILE,))
        _POOL_WORKERS = workers
    return _POOL


_POOL_CTX_FILE = None


def close():
    global _POOL, _POOL_WORKERS, _POOL_CTX_FILE
    if _POOL is not None:
        try:
            # Terminated, not closed: every caller drains its imap before this runs, so the pool
            # is idle and there is nothing to wait for.
            _POOL.terminate()
            _POOL.join()
        finally:
            _POOL = None
            _POOL_WORKERS = None
    if _POOL_CTX_FILE is not None:
        try:
            os.remove(_POOL_CTX_FILE)
        except OSError:
            pass
        _POOL_CTX_FILE = None


atexit.register(close)


def context():
    """The corpus-independent inputs every worker needs, built once per process.

    gob_species() belongs here for the same reason it is seeded in the initializer:
    bucket() and theirs() would each trigger a full raw pass in every worker otherwise.
    """
    global _CTX, _BUILDING, _BOOT
    if _CTX is None:
        import estimate
        cached = _cached_context()
        if cached is not None:
            _CTX = cached
            return _CTX
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
        _store_context(_CTX)
    return _CTX


# THE CONTEXT ON DISK (2026-09-27). Every python check in the suite built its own - the gob map and
# the mu sweeps, ~40 s at three workers, half of experiment_check.py - from the same corpus, all
# at once. It is a pure function of what _context_key reads, so it is kept beside the pool and
# reused while that is unchanged. COMBAT_CTX_CACHE=0 turns it off.
_CTX_CACHE_NAME = ".context-cache.pkl"


def _context_key():
    """A fingerprint of everything the context could depend on, erring towards "changed": every
    file in every log directory and the whole pool (path, size, mtime - a live client appending
    to a log changes it), every Python source in tools/combat, every file in data/combat, and the
    interpreter version."""
    import hashlib
    import estimate
    import fightlog
    h = hashlib.sha256(sys.version.encode())
    pool = os.path.join(estimate.ROOT, "data", "combat", "pool")

    # os.scandir, whose entries carry size and time from the directory listing itself - an
    # os.stat per file made this ~2 s over 15,000 logs. Names beginning with a dot are this
    # project's own caches (.context-cache.pkl, .deck-dumps.pkl, .sweep-cache/, .derived-stamp),
    # which change when they are written and must not change the key they are filed under.
    def walk(d, deep):
        try:
            entries = sorted(os.scandir(d), key=lambda e: e.name)
        except OSError:
            return
        for e in entries:
            if e.name.startswith("."):
                continue
            try:
                if e.is_dir():
                    if deep:
                        walk(e.path, True)
                    continue
                st = e.stat()
            except OSError:
                continue
            h.update(("%s|%d|%d\n" % (e.path, st.st_size, st.st_mtime_ns)).encode())

    for d in fightlog.find_log_dirs(estimate.ROOT):
        walk(d, False)
    walk(pool, True)
    for sub in (("tools", "combat"), ("data", "combat")):
        base = os.path.join(estimate.ROOT, *sub)
        for f in sorted(os.listdir(base)):
            p = os.path.join(base, f)
            if os.path.isfile(p) and ((sub[0] == "data") or f.endswith(".py")):
                with open(p, "rb") as fh:
                    h.update(f.encode() + b"\0" + fh.read())
    return h.hexdigest()


# SWEEPS ON DISK (2026-09-28). What a sweep hands back is a pure function of the logs it was
# given, the code, the data files and the context, and all of those are in _context_key - so its
# result is kept under that key and the paths, in data/combat/pool/.sweep-cache. NOT under the
# worker count: that only sets how the files are chunked, and every consumer folds its parts in
# file order - fused against unfused (a quarter the chunk size) and 3, 8 and 16 workers all gave
# byte-identical packs and reports - so the pack build's sixteen-worker sweeps serve the checks'
# three- and eight-worker ones, and a run after an edit computes each sweep once, not twice.
# A rerun with nothing changed - after a Java-only edit, say - reads its sweeps back instead of
# the corpus. Any change to a log, a tools/combat source or a data/combat file is a new key.
# COMBAT_SWEEP_CACHE=0 turns it off; the directory is trimmed to SWEEP_CACHE_BYTES, oldest first.
SWEEP_CACHE_BYTES = 8 * 1024 ** 3
_KEY_BASE = None


def _sweep_cache_on():
    return (os.environ.get("COMBAT_SWEEP_CACHE", "1").strip() != "0") and not _in_worker()


def _sweep_path(kind, paths):
    global _KEY_BASE
    import hashlib
    import estimate
    if _KEY_BASE is None:
        _KEY_BASE = _context_key()
    h = hashlib.sha256(_KEY_BASE.encode())
    h.update(b"\0" + kind.encode() + b"\0")
    h.update("\n".join(paths).encode("utf-8", "surrogatepass"))
    return os.path.join(estimate.ROOT, "data", "combat", "pool", ".sweep-cache",
                        "%s-%s.pkl" % (kind, h.hexdigest()[:40]))


def _stored_sweep(kind, paths):
    """(result,) when this sweep over these paths is on disk under the current key, else None.
    Read afresh each time: a consumer that changes what it was handed must not change it for the
    next one, just as a sweep made twice gives two copies."""
    if not _sweep_cache_on():
        return None
    import pickle
    try:
        with open(_sweep_path(kind, paths), "rb") as fh:
            return (pickle.load(fh),)
    except Exception:
        return None


def _store_sweep(kind, paths, out):
    if not _sweep_cache_on():
        return
    import pickle
    p = _sweep_path(kind, paths)
    d = os.path.dirname(p)
    try:
        os.makedirs(d, exist_ok=True)
        tmp = "%s.%d" % (p, os.getpid())
        with open(tmp, "wb") as fh:
            pickle.dump(out, fh, protocol=pickle.HIGHEST_PROTOCOL)
        os.replace(tmp, p)
    except OSError:
        return
    try:
        files = sorted((e.stat().st_mtime, e.stat().st_size, e.path) for e in os.scandir(d)
                       if e.name.endswith(".pkl"))
        total = sum(s for _t, s, _p in files)
        for _t, s, f in files:
            if total <= SWEEP_CACHE_BYTES:
                break
            os.remove(f)
            total -= s
    except OSError:
        pass


def _cache_path():
    import estimate
    return os.path.join(estimate.ROOT, "data", "combat", "pool", _CTX_CACHE_NAME)


def _cached_context():
    """The stored context when its key still matches, with this process seeded from it exactly as
    building it would have left it; None otherwise."""
    if os.environ.get("COMBAT_CTX_CACHE", "1").strip() == "0":
        return None
    import pickle
    import estimate
    try:
        with open(_cache_path(), "rb") as fh:
            key, ctx, swept = pickle.load(fh)
    except Exception:
        return None
    if key != _context_key():
        return None
    # The sweeps the context was measured from, so a caller that asks for measure_mu() or
    # ok_boost() itself (estimate_check's mu sections) is not sent back to the corpus.
    _SWEPT.update(swept)
    estimate._GOB_RES = ctx["gob_res"]
    estimate.seed_measured_mu(ctx["mu_state"])
    return ctx


def _store_context(ctx):
    if os.environ.get("COMBAT_CTX_CACHE", "1").strip() == "0":
        return
    import pickle
    path = _cache_path()
    if not os.path.isdir(os.path.dirname(path)):
        return
    tmp = "%s.%d" % (path, os.getpid())
    try:
        with open(tmp, "wb") as fh:
            swept = dict((k, v) for k, v in _SWEPT.items() if k[0] in ("measure_mu", "ok_boost"))
            pickle.dump((_context_key(), ctx, swept), fh, protocol=pickle.HIGHEST_PROTOCOL)
        os.replace(tmp, path)
    except OSError:
        try:
            os.remove(tmp)
        except OSError:
            pass


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
    # THE CONTEXT FIRST, THEN THE CACHE. Building the context runs measure_mu and ok_boost in
    # the bootstrap pool; a measure_mu that arrived here first used to miss the cache, build the
    # context (which ran measure_mu), and then run itself again - 50 s of a replay.py.
    if enabled() and (len(paths) > 1) and (_CTX is None) and not _BUILDING and not _in_worker():
        context()
    key = (sweep_id, tuple(paths))
    if (sweep_id in _REUSED) and (key in _SWEPT):
        return _SWEPT[key]
    hit = None if (key in _PREFETCHED) else _stored_sweep(sweep_id, paths)
    if key in _PREFETCHED:
        # Made by prefetch(), in the one read of the corpus it shared with the other sweeps.
        out = _PREFETCHED.pop(key)
    elif hit is not None:
        out = hit[0]
    else:
        # COMBAT_TIMINGS=1 names every sweep and what it cost, on stderr - see _timed.
        if os.environ.get("COMBAT_TIMINGS") and not _in_worker():
            out = _timed(sweep_id, paths)
        else:
            out = _map_chunks(sweep_id, paths)
        _store_sweep(sweep_id, paths, out)
    if sweep_id in _REUSED:
        _SWEPT[key] = out
    return out


# SWEEPS RUN MORE THAN ONCE PER PROCESS, KEPT (2026-09-27): a --write-pack mapped measure_mu
# twice, ok_boost three times, weapons_seen twice - ~5 s of file reads each, same corpus, same
# answer. Only sweeps whose every consumer reads its parts into fresh containers are listed; a
# consumer that mutated a part in place would see its own edits on the second call.
_REUSED = frozenset(("measure_mu", "ok_boost", "weapons_seen", "animal_restores",
                     "animal_grievous", "replay_passes", "mu_from_reductions",
                     "agility_band"))
_SWEPT = {}


def _timed(sweep_id, paths):
    import time
    t0 = time.time()
    out = _map_chunks(sweep_id, paths)
    sys.stderr.write("[sweep %-20s %6d files %7.1f s]\n" % (sweep_id, len(paths), time.time() - t0))
    sys.stderr.flush()
    return out


def _map_chunks(sweep_id, paths):
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
        # WHETHER A WEAPON'S COOLDOWN MODIFIER IS IN THE REPORTED COOLDOWN, per weapon, from the
        # cooldowns themselves: a weapon card thrown with both hands on it (so it is what swung) at
        # no initiative, whose cooldown / base lies above the agility law's ceiling, could only have
        # it applied; one below the floor times the modifier could only not. Counted here, judged in
        # the merge.
        mods = estimate.fightlog.coolmod_hands(log)
        # ONLY WHERE THE HANDS CAN BE TRUSTED. Swaps were first logged at schema 13, so an older
        # log can show a pickaxe in hand after the fight swapped to a sword (James: "combat started
        # with that weapon then the weapon was swapped"). All 47 pickaxe cooldowns that "could not
        # carry" its 1.15 were from such logs; from schema 13 on it reads 20 that need it and none
        # that forbid it - the same as the B12's 510 to 0 (2026-09-27).
        if mods and (log.schema >= 13):
            moves = _moves()
            for eng in log.engagements:
                for m in eng.moves:
                    mv = moves.get(m.get("name"))
                    cd = m.get("cd")
                    if (m.get("actor") != "me") or (not mv) or (not cd) or (cd <= 0) \
                            or (not mv.get("damage_share")) or (mv.get("cooldown") is None) \
                            or mv.get("cooldown_mu"):
                        continue
                    held = _hands_at(log, m.get("t") or 0)
                    if (held[0] is None) or (held[0] != held[1]) or (held[0] not in mods):
                        continue
                    cm = mods[held[0]]
                    r = cd / float(mv["cooldown"])
                    rec = out.setdefault(held[0].rsplit("/", 1)[-1],
                                         {"res": held[0], "n": 0, "quality": [], "recovered_base": [],
                                          "base_iv": []})
                    tally = rec.setdefault("coolmod_tally", [0, 0])
                    if r > AGI_CEIL + 0.02:
                        tally[0] += 1
                    elif r < (AGI_FLOOR * cm) - 0.02:
                        tally[1] += 1
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
            for k in ("damage", "armpen", "range", "grievous", "coolmod"):
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


# The agility law's factor range, (1/2)^(1/7) to 2^(1/7) - Formulas.agilityCooldownFactor.
AGI_FLOOR, AGI_CEIL = 0.5 ** (1.0 / 7.0), 2.0 ** (1.0 / 7.0)


def _hands_at(log, t):
    """The resources in the two hand slots at time `t`, from the gear rows."""
    hands = {}
    for g in (log.gear or []):
        if g.get("slot") not in (6, 7):
            continue
        if (g.get("t") or 0) > t:
            break
        hands[g.get("slot")] = g.get("res")
    return [hands.get(6), hands.get(7)]


def weapons_seen_merge(parts):
    """Reduce the raw sightings exactly as the old single pass did."""
    merged = {}
    for part in parts:
        for base, rec in part.items():
            dst = merged.setdefault(base, {"res": rec["res"], "n": 0, "quality": [],
                                           "recovered_base": [], "base_iv": []})
            dst["n"] += rec["n"]
            if "coolmod_tally" in rec:
                t = dst.setdefault("coolmod_tally", [0, 0])
                t[0] += rec["coolmod_tally"][0]
                t[1] += rec["coolmod_tally"][1]
            for k in ("damage", "armpen", "range", "grievous", "coolmod"):
                if k in rec:
                    dst.setdefault(k, set()).update(rec[k])
            dst["quality"].extend(rec["quality"])
            dst["recovered_base"].extend(rec["recovered_base"])
            dst["base_iv"].extend(rec.get("base_iv") or ())
    # Sets do not serialise, and a weapon read at two qualities has two damages and one
    # base - so the tooltip figures are kept as sorted lists and the base as a range.
    for base, rec in merged.items():
        for k in ("damage", "armpen", "range", "grievous", "coolmod"):
            if k in rec:
                rec[k] = sorted(rec[k])
        # THE VERDICT on the modifier: cooldowns only it explains, and none it cannot -> applies;
        # the reverse -> does not; both -> disputed (null), and the model then leaves it out.
        tally = rec.pop("coolmod_tally", None)
        if tally is not None:
            rec["coolmod_evidence"] = {"only_with": tally[0], "only_without": tally[1]}
            rec["coolmod_applies"] = (True if (tally[0] > 0 and tally[1] == 0)
                                      else (False if (tally[1] > 0 and tally[0] == 0) else None))
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
        mods = estimate.fightlog.coolmod_hands(log)
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
                # A WEAPON CARD WITH A MODIFYING WEAPON IN HAND runs on base x modifier (2026-09-27,
                # Formulas.cooldownTicks): all 47 disagreements this control reported that day were
                # Dunki's B12 at 1.25, read as a bat at twice his agility when the client's table put
                # it at half. Dividing it out in logs older than schema 13 broke eight pickaxe
                # readings - fights that had swapped to a sword without a gear row saying so.
                base = mv["cooldown"]
                if mods and mv.get("damage_share"):
                    held = _hands_at(log, m.get("t") or 0)
                    cm = estimate.fightlog.held_coolmod(log, m.get("t") or 0, mods)
                    if cm:
                        # Schema 13 and on, both hands on the modifying weapon: it is what swung,
                        # and its modifier is in the number. Otherwise the hands are not known well
                        # enough to say, and the reading is left out.
                        if (log.schema < 13) or (held[0] != held[1]):
                            continue
                        base = base * cm
                obs[eng.gob].append((base, cd))
        best = {}
        for r in log.agility:
            best[r["gob"]] = (r.get("min"), r.get("max"))
        stale = estimate.stale_brackets(log, moves)
        # THE CLIENT'S BRACKET IS WRONG WITH A PICKAXE IN HAND, in every log recorded before
        # 2026-09-27: it has cooldown tables for the B12 and the Cutblade and none for the pickaxe,
        # and it did not even see the pickaxe (gob.currentWeapon), so a Quick Barrage at 20 x 1.15 x
        # 0.906 = 21 against a slow red deer was read through the default table as a deer 1.2-1.7
        # times our agility (Shade-1790485979192-Shade-9). Fightsess now reads the hands from the
        # equipment and narrows nothing then; the brackets already logged are not evidence.
        no_table = any(r.rsplit("/", 1)[-1] not in ("b12axe", "cutblade") for r in mods) if mods else False
        tol = 1.0 + estimate.AGILITY_EDGE_TOL
        for gob, (lo_r, hi_r) in best.items():
            iv = estimate.agility_interval(obs.get(gob, []), agi_me) if obs.get(gob) else None
            clo = (lo_r or 0.0) * agi_me
            chi = float("inf") if (hi_r is None or hi_r >= 2.0) else hi_r * agi_me
            if iv is None:
                agree = None
            else:
                olo, ohi, _capped = iv
                if (olo > ohi) or (clo > chi) or (gob in stale) or no_table:
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
            # CLEAN ENGAGEMENTS ONLY, as the damage coefficient is (2026-09-28): in a bat swarm the
            # sampled bat's Wingbeat is timed beside wounds other bats dealt, unlogged, and the
            # crowd read Wingbeat at 0.52 hard per soft against 0.18 in clean fights.
            if not eng.defence_ok:
                continue
            for h in _foe_intake(eng, log.me):
                shp, hhp = h["shp"], h["hhp"]
                nm = h["move"]
                if nm and (shp > 0):
                    obs[nm].append((shp, hhp))
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


def _flee_points_chunk(paths):
    import estimate
    out = []
    for p in paths:
        r = estimate._flee_file(p)
        if r is not None:
            out.append(r)
    return out


def _replay_passes_chunk(paths):
    import replay
    return [replay._passes(p) for p in paths]


def _miss_sounds_chunk(paths):
    import estimate
    return estimate.miss_sound_tally(paths)


def _own_throws_ip_chunk(paths):
    import estimate
    return estimate.own_throws_with_ip(paths)


def _coverage_uses_chunk(paths):
    import experiment
    return experiment.coverage_uses(paths)


_SWEEPS = {
    "coverage_uses": _coverage_uses_chunk,
    "own_throws_ip": _own_throws_ip_chunk,
    "miss_sounds": _miss_sounds_chunk,
    "replay_passes": _replay_passes_chunk,
    "flee_points": _flee_points_chunk,
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
