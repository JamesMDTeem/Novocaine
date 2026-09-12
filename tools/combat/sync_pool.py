#!/usr/bin/env python3
"""Pull pooled combat logs from the team server to data/combat/pool/.

    python tools/combat/sync_pool.py
    python tools/combat/sync_pool.py --dry-run
    python tools/combat/sync_pool.py --from-db            [no credentials needed]

TWO WAYS IN. The HTTP path below is the portable one and the only one that works
from a machine that is not the server's own host. It needs the endpoint and the
client token, and if you do not have those the pool simply cannot be refreshed.

--from-db is the fallback for the machine that HOSTS the mapper server: it reads
the same rows straight out of the server's SQLite through `wsl sqlite3 -readonly`,
which needs no token at all. It exists because the corpus silently went stale for
two days - every fight was reaching the server, and nothing was bringing them down
- and the reason nobody noticed is that a stale pool looks exactly like a quiet
one. Read-only is not a convention here: this is the LIVE database behind a
running service, so the query goes through sqlite3's own -readonly flag rather
than through Python, which would otherwise open it read-write and create WAL
files under a server that is mid-write.

Config SOLELY from env vars:

    HHM_COMBATLOG_ENDPOINT  e.g. https://host/client/{token}/combatlog
    HHM_COMBATLOG_TOKEN     Bearer token, only if the endpoint needs it beyond
                            the URL token. When set, sent as Authorization: Bearer <token>.

Flow:

    GET {endpoint}/ids              -> string[] fightIds
    GET {endpoint}/export?since=ms  paging until <500 returned

Each fight is written to data/combat/pool/<characterId>-<fightId>.jsonl
(both segments sanitized to [A-Za-z0-9_-]) and manifest.json is maintained as
{fightId: receivedAt}. Re-runs are idempotent: already-present fights are skipped
and paging resumes from the local manifest's max receivedAt.

Stdlib only (this module). The opening-decay fitter tools/combat/decay_fit.py is the one exception that requires scipy/numpy (see tools/combat/requirements.txt) for O(t)=O0*exp(-t/tau) fitting.
"""

import io
import json
import os
import re
import subprocess
import sys

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

_SANITIZE_RE = re.compile(r"[^A-Za-z0-9_-]")


def _sanitize(s):
    """Sanitize a path segment to [A-Za-z0-9_-], replacing anything else with _."""
    if s is None:
        return "_"
    s = str(s)
    # Empty after sanitization still needs a placeholder
    out = _SANITIZE_RE.sub("_", s)
    return out if out else "_"


def _eprint(msg):
    sys.stderr.write(msg + "\n")


def _redact_url(url, endpoint):
    # Never echo a token that may be embedded in the endpoint path
    if endpoint and url.startswith(endpoint):
        return "[endpoint]" + url[len(endpoint):]
    return url


def _load_manifest(path):
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            _eprint("manifest %s is not a dict, ignoring" % path)
            return {}
        # Coerce string ints where possible, warn on bad values
        out = {}
        for k, v in data.items():
            try:
                out[str(k)] = int(v)
            except (ValueError, TypeError):
                _eprint("manifest entry %r has non-int receivedAt %r, ignoring value" % (k, v))
                out[str(k)] = 0
        return out
    except (OSError, ValueError) as e:
        _eprint("manifest %s unreadable (%s), starting empty" % (path, e))
        return {}


def _save_manifest(path, manifest):
    d = os.path.dirname(path)
    if d and not os.path.exists(d):
        os.makedirs(d, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
        f.write("\n")
    try:
        os.replace(tmp, path)
    except OSError:
        import shutil
        shutil.move(tmp, path)


def _http_get(url, token, endpoint_for_redact=""):
    """GET url with Authorization if token given. Returns (status, body_bytes).

    Retries transient 429/5xx and timeouts with bounded backoff (stdlib only).
    401/403 are not retried and surface immediately so auth misconfig is obvious."""
    import urllib.request
    import urllib.error
    import time as _time

    last_status = None
    last_body = b""
    for attempt in range(3):
        req = urllib.request.Request(url)
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                status = getattr(resp, "status", 200)
                body = resp.read()
                if status == 429 or 500 <= status < 600:
                    last_status, last_body = status, body
                    if attempt < 2:
                        # Honor Retry-After if present
                        try:
                            ra = resp.headers.get("Retry-After")
                            delay = float(ra) if ra is not None else (1.5 ** attempt)
                        except Exception:
                            delay = 1.5 ** attempt
                        _time.sleep(min(delay, 10))
                        continue
                    return status, body
                return status, body
        except urllib.error.HTTPError as e:
            body = e.read() if hasattr(e, "read") else b""
            if e.code == 429 or 500 <= e.code < 600:
                last_status, last_body = e.code, body
                if attempt < 2:
                    try:
                        ra = e.headers.get("Retry-After") if hasattr(e, "headers") else None
                        delay = float(ra) if ra is not None else (1.5 ** attempt)
                    except Exception:
                        delay = 1.5 ** attempt
                    _time.sleep(min(delay, 10))
                    continue
            return e.code, body
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last_status = None
            last_body = str(e).encode("utf-8", "replace")
            if attempt < 2:
                _time.sleep(1.5 ** attempt)
                continue
            _eprint("request failed: %s  (%s)" % (_redact_url(url, endpoint_for_redact), e))
            return 599, last_body
    if last_status is not None:
        return last_status, last_body
    return 599, b""


def _get_json(url, token, endpoint_for_redact=""):
    status, body = _http_get(url, token, endpoint_for_redact)
    if status < 200 or status >= 300:
        _eprint("GET %s failed with HTTP %d" % (_redact_url(url, endpoint_for_redact), status))
        if status in (401, 403):
            _eprint("  auth failed -- check HHM_COMBATLOG_ENDPOINT and HHM_COMBATLOG_TOKEN")
        try:
            _eprint(body.decode("utf-8", errors="replace")[:500])
        except Exception:
            pass
        sys.exit(1)
    try:
        return json.loads(body.decode("utf-8"))
    except ValueError as e:
        _eprint("invalid JSON from %s: %s" % (_redact_url(url, endpoint_for_redact), e))
        sys.exit(1)


def _get_json_soft(url, token, endpoint_for_redact=""):
    """Like _get_json but returns None on failure instead of exiting. For /ids."""
    status, body = _http_get(url, token, endpoint_for_redact)
    if status < 200 or status >= 300:
        return None
    try:
        return json.loads(body.decode("utf-8"))
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

DEFAULT_DB = "/home/james/HnHMapperServer/data/grids.db"


def _wsl_sqlite(db, sql):
    """Run one read-only query against the server database and return its stdout.

    Shells out to `wsl.exe -e sqlite3 -readonly` rather than using Python's own
    sqlite3 module. The database lives inside WSL and is open by a running
    service; reaching it over the \\wsl.localhost share and letting Python open
    it would take a write lock and drop WAL files beside a live writer.
    """
    out = subprocess.run(
        ["wsl.exe", "-e", "sqlite3", "-readonly", db, sql],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if out.returncode != 0:
        _eprint("sqlite3 failed: " + out.stderr.decode("utf-8", "replace").strip())
        sys.exit(2)
    return out.stdout.decode("utf-8", "replace")


def stamp_sync(root, source, fights, decks):
    """Record that a pull happened, so staleness can be told apart from quiet.

    THE POINT OF THIS FILE. The corpus went two days stale and every check stayed
    green, because a pool nobody has refreshed looks exactly like a pool nobody has
    added to. The age of the newest FIGHT cannot separate those two - a weekend with
    no fighting reads the same as a sync that has stopped running. The age of the last
    SYNC can, and it is true regardless of whether anyone played.

    Written on every successful pull by either path. tools/combat/pool_check.py reads
    it and fails when it is too old.
    """
    import time
    path = os.path.join(root, "data", "combat", "pool", "last-sync.json")
    doc = {"at": int(time.time() * 1000), "source": source,
           "fights": fights, "decks": decks}
    try:
        d = os.path.dirname(path)
        if not os.path.exists(d):
            os.makedirs(d, exist_ok=True)
        with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(doc, indent=2, sort_keys=True) + "\n")
    except Exception as e:
        _eprint("could not write last-sync.json: %s" % e)


def _posix(win_path):
    """C:\\x\\y -> /mnt/c/x/y, so a WSL process can reach a Windows path."""
    q = os.path.abspath(win_path)
    return "/mnt/" + q[0].lower() + q[2:].replace(chr(92), "/")


def _iso_to_ms(ts):
    """'2026-09-11 18:33:28.7416062' -> epoch ms, to match the HTTP path's manifest."""
    import datetime
    ts = (ts or "").strip()
    if not ts:
        return 0
    head = ts[:19]
    frac = ts[20:23] if len(ts) > 20 else "0"
    try:
        dt = datetime.datetime.strptime(head, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return 0
    try:
        ms = int((frac + "000")[:3])
    except ValueError:
        ms = 0
    return int(dt.timestamp() * 1000) + ms


def _sync_from_db(argv, dry_run):
    """Fill the pool from the server's own SQLite. See the module docstring."""
    db = DEFAULT_DB
    if "--db" in argv:
        i = argv.index("--db")
        if i + 1 < len(argv):
            db = argv[i + 1]

    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    pool_dir = os.path.join(root, "data", "combat", "pool")
    manifest_path = os.path.join(pool_dir, "manifest.json")
    manifest = _load_manifest(manifest_path)

    rows = _wsl_sqlite(db, "SELECT Id, CharacterId, FightId, ReceivedAt FROM CombatLogs ORDER BY Id;")
    want = []
    for line in rows.splitlines():
        parts = line.split("|", 3)
        if len(parts) != 4:
            continue
        rid, char_id, fight_id, recv = parts
        if fight_id in manifest:
            continue
        fname = "%s-%s.jsonl" % (_sanitize(char_id), _sanitize(fight_id))
        want.append((rid, fight_id, fname, _iso_to_ms(recv)))

    print("server rows: %d   already pooled: %d   to fetch: %d"
          % (len(rows.splitlines()), len(manifest), len(want)))
    if dry_run or not want:
        for _, fid, _, _ in want[:20]:
            print("  would fetch " + fid)
        if len(want) > 20:
            print("  ... and %d more" % (len(want) - 20))
        return 0

    if not os.path.exists(pool_dir):
        os.makedirs(pool_dir, exist_ok=True)

    # One sqlite3 invocation for the whole batch. Per-row subprocesses cost more
    # in process startup than in query time once the backlog runs to hundreds.
    #
    # The batch goes in through .read rather than as an argument: a backlog of a
    # few hundred fights builds a script well past the Windows command-line limit,
    # and the failure it produces ("filename or extension is too long") names
    # neither sqlite nor the length as the cause.
    posix_pool = _posix(pool_dir)
    script_path = os.path.join(pool_dir, ".sync-batch.sql")
    with io.open(script_path, "w", encoding="utf-8", newline="\n") as fh:
        for rid, _, fname, _ in want:
            fh.write(".output %s/%s\n" % (posix_pool, fname))
            fh.write("SELECT PayloadJson FROM CombatLogs WHERE Id=%s;\n" % rid)
        fh.write(".output stdout\n")
    try:
        _wsl_sqlite(db, ".read %s" % _posix(script_path))
    finally:
        try:
            os.remove(script_path)
        except OSError:
            pass

    wrote = 0
    for _, fight_id, fname, recv in want:
        fpath = os.path.join(pool_dir, fname)
        if os.path.exists(fpath) and (os.path.getsize(fpath) > 0):
            manifest[fight_id] = recv
            wrote += 1
        else:
            _eprint("  empty or missing after extract: " + fname)
    _save_manifest(manifest_path, manifest)
    print("wrote %d fights, manifest now %d" % (wrote, len(manifest)))

    # DECKS TOO. The decks went stale alongside the fights and for the same reason, and a
    # fight without the deck it was thrown from cannot be read for move levels - which is
    # what left 2418 of 3022 pooled fights unusable the last time the two drifted apart.
    decks = _decks_from_db(db, root, dry_run)
    if not dry_run:
        stamp_sync(root, "db", wrote, decks)
    return 0


def _decks_from_db(db, root, dry_run):
    """The deck half of --from-db. Names files exactly as the HTTP path does."""
    decks_dir = os.path.join(root, "data", "combat", "pool", "decks")
    manifest_path = os.path.join(decks_dir, "manifest.json")
    manifest = _load_manifest(manifest_path)

    rows = _wsl_sqlite(db, "SELECT Id, DeckId, ReceivedAt FROM CombatDecks ORDER BY Id;")
    want = []
    for line in rows.splitlines():
        parts = line.split("|", 2)
        if len(parts) != 3:
            continue
        rid, deck_id, recv = parts
        if deck_id in manifest:
            continue
        want.append((rid, deck_id, "deck-%s.json" % _sanitize(deck_id), _iso_to_ms(recv)))

    print("server decks: %d   already pooled: %d   to fetch: %d"
          % (len(rows.splitlines()), len(manifest), len(want)))
    if dry_run or not want:
        return 0

    if not os.path.exists(decks_dir):
        os.makedirs(decks_dir, exist_ok=True)
    posix_dir = _posix(decks_dir)
    script_path = os.path.join(decks_dir, ".sync-batch.sql")
    with io.open(script_path, "w", encoding="utf-8", newline="\n") as fh:
        for rid, _, fname, _ in want:
            fh.write(".output %s/%s\n" % (posix_dir, fname))
            fh.write("SELECT PayloadJson FROM CombatDecks WHERE Id=%s;\n" % rid)
        fh.write(".output stdout\n")
    try:
        _wsl_sqlite(db, ".read %s" % _posix(script_path))
    finally:
        try:
            os.remove(script_path)
        except OSError:
            pass

    wrote = 0
    for _, deck_id, fname, recv in want:
        fpath = os.path.join(decks_dir, fname)
        if os.path.exists(fpath) and (os.path.getsize(fpath) > 0):
            manifest[deck_id] = recv
            wrote += 1
        else:
            _eprint("  empty or missing after extract: " + fname)
    _save_manifest(manifest_path, manifest)
    print("wrote %d decks, manifest now %d" % (wrote, len(manifest)))
    return wrote


def main(argv=None):
    if argv is None:
        argv = sys.argv[1:]

    dry_run = "--dry-run" in argv

    if "--from-db" in argv:
        return _sync_from_db(argv, dry_run)

    endpoint = os.environ.get("HHM_COMBATLOG_ENDPOINT", "").strip()
    token = os.environ.get("HHM_COMBATLOG_TOKEN", "").strip()

    if not endpoint:
        _eprint("HHM_COMBATLOG_ENDPOINT is not set.")
        _eprint("  Set it to the server combatlog base, e.g.:")
        _eprint("    HHM_COMBATLOG_ENDPOINT=https://host/client/<token>/combatlog")
        _eprint("  and optionally HHM_COMBATLOG_TOKEN for Bearer auth when the URL")
        _eprint("  token alone is not sufficient (Authorization: Bearer <token>).")
        sys.exit(2)

    # Normalize: no trailing slash, so /ids and /export append cleanly
    endpoint = endpoint.rstrip("/")

    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    pool_dir = os.path.join(root, "data", "combat", "pool")
    manifest_path = os.path.join(pool_dir, "manifest.json")

    # Ensure pool dir exists (even for dry-run, for manifest read)
    manifest = _load_manifest(manifest_path)

    # Determine since: max receivedAt in manifest, or 0 if empty
    since = 0
    if manifest:
        try:
            since = max(int(v) for v in manifest.values())
        except (ValueError, TypeError):
            # Manifest has non-int values – treat as 0 and let server return all
            since = 0

    # Fetch server fightIds (informational; export is source of truth)
    ids_url = endpoint + "/ids"
    server_ids = _get_json_soft(ids_url, token if token else None, endpoint)
    if server_ids is not None and not isinstance(server_ids, list):
        server_ids = None
    if server_ids is not None:
        print("server ids: %d" % len(server_ids))

    downloaded = 0
    skipped = 0
    present = len(manifest)
    seen_fight_ids = set(manifest.keys())
    would_download = []
    paging_since = since

    while True:
        export_url = "%s/export?since=%d" % (endpoint, paging_since)
        batch = _get_json(export_url, token if token else None, endpoint)
        if not isinstance(batch, list):
            _eprint("unexpected export response (expected list) from %s" % _redact_url(export_url, endpoint))
            sys.exit(1)
        if not batch:
            break

        # Track max receivedAt in this batch regardless of dedup, for paging
        try:
            batch_max = max(int(e.get("receivedAt", paging_since)) for e in batch if isinstance(e.get("receivedAt"), int))
        except ValueError:
            batch_max = paging_since
        if not isinstance(batch_max, int):
            batch_max = paging_since

        for entry in batch:
            if not isinstance(entry, dict):
                continue
            fight_id = entry.get("fightId")
            char_id = entry.get("characterId")
            received_at = entry.get("receivedAt")
            lines = entry.get("lines")
            if not fight_id:
                continue
            manifest_key = str(fight_id)
            if manifest_key in seen_fight_ids:
                skipped += 1
                continue
            if dry_run:
                would_download.append(manifest_key)
                seen_fight_ids.add(manifest_key)
                continue
            safe_char = _sanitize(char_id if char_id else "unknown")
            safe_fight = _sanitize(fight_id)
            # Enforce filename length limit (255) for safety
            fname = "%s-%s.jsonl" % (safe_char, safe_fight)
            if len(fname) > 240:
                # Truncate fight part, keep char prefix
                keep = 240 - len(safe_char) - 6
                safe_fight = safe_fight[:max(keep, 8)]
                fname = "%s-%s.jsonl" % (safe_char, safe_fight)
            fpath = os.path.join(pool_dir, fname)
            if not os.path.exists(pool_dir):
                os.makedirs(pool_dir, exist_ok=True)
            try:
                tmp = fpath + ".tmp"
                with open(tmp, "w", encoding="utf-8", newline="\n") as out:
                    if isinstance(lines, list):
                        for ln in lines:
                            if isinstance(ln, str):
                                out.write(ln.rstrip("\r\n") + "\n")
                            else:
                                out.write(json.dumps(ln) + "\n")
                    elif isinstance(lines, str):
                        # Ensure trailing newline for jsonl consumers
                        out.write(lines if lines.endswith("\n") else lines + "\n")
                    elif lines is None:
                        pass
                    else:
                        out.write(json.dumps(lines) + "\n")
                os.replace(tmp, fpath)
            except OSError as e:
                _eprint("failed to write %s: %s" % (_redact_url(fpath, endpoint), e))
                sys.exit(1)
            try:
                manifest[manifest_key] = int(received_at) if isinstance(received_at, int) else paging_since
            except (ValueError, TypeError):
                manifest[manifest_key] = paging_since
            seen_fight_ids.add(manifest_key)
            downloaded += 1

        if len(batch) < 500:
            break
        # Advance paging cursor to max in batch; if no progress, break to avoid loop
        if batch_max <= paging_since:
            break
        paging_since = batch_max

    if dry_run:
        if would_download:
            print("would download %d fight(s):" % len(would_download))
            for fid in would_download:
                print("  %s" % fid)
        else:
            print("would download 0 fight(s) - pool is up to date")
        print("present: %d  would_download: %d  skipped: %d" % (present, len(would_download), skipped))
        return 0

    # Persist manifest if we downloaded anything (or to ensure file exists)
    if downloaded > 0:
        _save_manifest(manifest_path, manifest)
    else:
        # Ensure manifest file exists even on first run with no downloads (empty dict)
        if not os.path.exists(manifest_path):
            _save_manifest(manifest_path, manifest)

    print("pool sync: downloaded %d  skipped %d  present %d" % (downloaded, skipped, present + downloaded))

    _sync_decks(endpoint, token, root, dry_run)
    if not dry_run:
        # Same stamp the --from-db path writes. Whichever route refreshed the pool, the
        # staleness check reads one file and does not care which.
        stamp_sync(root, "http", len(manifest), None)
    return 0


def _sync_decks(endpoint, token, root, dry_run):
    """Pull the team's combat-deck dumps alongside their fights.

    A fight log names the move that was thrown; only the deck says what LEVEL it was, and mu -
    a factor in every attack weight - is not recoverable without that. Pulling fights without
    decks is what left 2418 of 3022 pooled fights unusable for every level-keyed measurement:
    estimate.levels_at() correctly reports a character we hold no dump for as unknown, so those
    fights are skipped rather than credited to somebody else's card levels.

    Written as deck-<character>-<wall>.json, byte-identical in NAME to what the client writes
    locally, so estimate.deck_history() parses a pooled dump and a local one with one code path
    and neither has to know where it came from.

    A failure here never fails the fight sync: the fights are the expensive half and are
    already on disk by the time this runs.
    """
    deck_endpoint = endpoint
    for suffix in ("/combatlog/ids", "/combatlog"):
        if deck_endpoint.endswith(suffix):
            deck_endpoint = deck_endpoint[: -len(suffix)] + "/combatdeck"
            break
    else:
        if not deck_endpoint.endswith("/combatdeck"):
            deck_endpoint = deck_endpoint + "/combatdeck"

    decks_dir = os.path.join(root, "data", "combat", "pool", "decks")
    manifest_path = os.path.join(decks_dir, "manifest.json")
    manifest = _load_manifest(manifest_path)
    since = 0
    if manifest:
        try:
            since = max(int(v) for v in manifest.values())
        except (ValueError, TypeError):
            since = 0

    seen = set(manifest.keys())
    downloaded = 0
    skipped = 0
    would = []
    paging_since = since

    while True:
        export_url = "%s/export?since=%d" % (deck_endpoint, paging_since)
        batch = _get_json_soft(export_url, token if token else None, deck_endpoint)
        if not isinstance(batch, list):
            # An older server has no /combatdeck at all. Say so once and leave the fights alone.
            _eprint("deck sync: no usable /combatdeck export at %s - skipping decks"
                    % _redact_url(deck_endpoint, deck_endpoint))
            return
        if not batch:
            break
        try:
            batch_max = max(int(e.get("receivedAt", paging_since)) for e in batch
                            if isinstance(e.get("receivedAt"), int))
        except ValueError:
            batch_max = paging_since

        for entry in batch:
            if not isinstance(entry, dict):
                continue
            deck_id = entry.get("deckId")
            payload = entry.get("payload")
            received_at = entry.get("receivedAt")
            if not deck_id or not payload:
                continue
            key = str(deck_id)
            if key in seen:
                skipped += 1
                continue
            if dry_run:
                would.append(key)
                seen.add(key)
                continue
            fname = "deck-%s.json" % _sanitize(key)
            fpath = os.path.join(decks_dir, fname)
            try:
                if not os.path.exists(decks_dir):
                    os.makedirs(decks_dir, exist_ok=True)
                tmp = fpath + ".tmp"
                with open(tmp, "w", encoding="utf-8", newline="\n") as out:
                    out.write(payload if isinstance(payload, str) else json.dumps(payload))
                os.replace(tmp, fpath)
            except OSError as e:
                _eprint("failed to write %s: %s" % (fname, e))
                return
            manifest[key] = int(received_at) if isinstance(received_at, int) else paging_since
            seen.add(key)
            downloaded += 1

        if len(batch) < 500:
            break
        if batch_max <= paging_since:
            break
        paging_since = batch_max

    if dry_run:
        print("deck sync: would download %d deck(s)" % len(would))
        return

    if downloaded > 0 or not os.path.exists(manifest_path):
        try:
            if not os.path.exists(decks_dir):
                os.makedirs(decks_dir, exist_ok=True)
            _save_manifest(manifest_path, manifest)
        except OSError as e:
            _eprint("failed to write deck manifest: %s" % e)

    print("deck sync: downloaded %d  skipped %d  present %d"
          % (downloaded, skipped, len(manifest)))


if __name__ == "__main__":
    sys.exit(main())
