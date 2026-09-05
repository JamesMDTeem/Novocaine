#!/usr/bin/env python3
"""Pull pooled combat logs from the team server to data/combat/pool/.

    python tools/combat/sync_pool.py
    python tools/combat/sync_pool.py --dry-run

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

import json
import os
import re
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

def main(argv=None):
    if argv is None:
        argv = sys.argv[1:]

    dry_run = "--dry-run" in argv

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
    return 0


if __name__ == "__main__":
    sys.exit(main())
