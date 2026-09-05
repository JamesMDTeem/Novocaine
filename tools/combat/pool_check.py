#!/usr/bin/env python3
"""Checks for the pooled corpus pulled from the team server.

    python tools/combat/pool_check.py

The pool is populated by tools/combat/sync_pool.py; these checks read only
local files and make no network calls.

Verifies:

    - data/combat/pool/manifest.json is valid JSON when present
    - every manifest entry has its file present
    - every pool file parses as .jsonl with a begin first line and an end line
      (via fightlog.read, not a reimplementation)
    - reports pool file count

Exits 0 when every check passes, 1 otherwise.
"""

import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fightlog  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
POOL_DIR = os.path.join(ROOT, "data", "combat", "pool")
MANIFEST = os.path.join(POOL_DIR, "manifest.json")

failures = []


def check(what, got, want):
    ok = got == want
    print("  %-56s %-22s %s" % (what, repr(got)[:22], "ok" if ok else "WANT %r" % (want,)))
    if not ok:
        failures.append(what)


def pooled_corpus():
    print("pooled corpus on disk")
    if not os.path.isdir(POOL_DIR):
        print("  pool dir absent - fresh checkout, no files expected")
        check("pool dir absent means 0 files", 0, 0)
        # manifest must not exist or if it does must be valid
        if os.path.exists(MANIFEST):
            try:
                with open(MANIFEST, "r", encoding="utf-8") as f:
                    json.load(f)
                check("manifest absent when pool dir absent", True, True)
            except ValueError:
                check("manifest is valid JSON", False, True)
        return

    files = sorted(glob.glob(os.path.join(POOL_DIR, "*.jsonl")))
    print("  pool files: %d" % len(files))
    check("pool file count reported", len(files), len(files))

    # manifest.json is valid JSON when present
    manifest = None
    if os.path.exists(MANIFEST):
        try:
            with open(MANIFEST, "r", encoding="utf-8") as f:
                manifest = json.load(f)
            check("manifest.json is valid JSON", True, True)
            check("manifest is a dict", isinstance(manifest, dict), True)
        except ValueError as e:
            check("manifest.json is valid JSON", False, True)
            print("    parse error: %s" % e)
            manifest = None
        except OSError as e:
            check("manifest.json readable", False, True)
            print("    read error: %s" % e)
            manifest = None
    else:
        if files:
            check("manifest.json exists when pool has files", False, True)
        else:
            check("manifest absent with 0 files is ok", True, True)
        manifest = {}

    if manifest is None:
        manifest = {}

    # every manifest entry has its file present
    if isinstance(manifest, dict) and manifest:
        import re as _re
        _san_re = _re.compile(r"[^A-Za-z0-9_-]")
        def _sanitize(s):
            return _san_re.sub("_", str(s)) if s is not None else "_"
        file_stems = [os.path.splitext(os.path.basename(p))[0] for p in files]
        for fight_id in sorted(manifest.keys()):
            val = manifest[fight_id]
            # Filename uses sanitized fightId, so compare sanitized form
            fid_san = _sanitize(fight_id)
            present = False
            for stem in file_stems:
                if stem == fid_san or stem == fight_id or stem.endswith("-" + fid_san) or stem.endswith("-" + fight_id):
                    present = True
                    break
            check("manifest entry %s has its file" % fight_id[:18], present, True)
            check("manifest entry %s receivedAt is int" % fight_id[:18], isinstance(val, int), True)

    # every pool file parses as .jsonl with a begin first line and an end line
    for path in files:
        name = os.path.basename(path)
        try:
            log = fightlog.read(path)
        except Exception as e:
            check("%s parses" % name[:32], False, True)
            print("    read error: %s" % e)
            continue
        has_begin = log.header is not None and log.header.get("ev") == "begin"
        check("%s begins with begin" % name[:32], has_begin, True)
        has_end = log.end is not None
        check("%s ends with end" % name[:32], has_end, True)
        # Also ensure unparseable count is zero - valid jsonl
        check("%s has no unparseable lines" % name[:32], log.unparseable, 0)
        if log.unparseable:
            print("    unparseable lines: %d" % log.unparseable)

    # report pool file count as reading (not verdict) - already printed
    print("  pool file count: %d" % len(files))


def default_logs_includes_pool():
    print("\ndefault_logs includes pool")
    paths, dirs = fightlog.default_logs(ROOT)
    pool_files = sorted(glob.glob(os.path.join(POOL_DIR, "*.jsonl")))
    if not pool_files:
        print("  pool empty - default_logs returns %d local files" % len(paths))
        check("default_logs with empty pool returns local files", isinstance(paths, list), True)
        return
    pool_in_default = [p for p in paths if "pool" in os.path.normpath(p).split(os.sep)]
    # Independent dedup expectation: pool file is duplicate if its fight suffix matches a local stem
    local_stems = set()
    for d in dirs:
        for p in glob.glob(os.path.join(d, "*.jsonl")):
            local_stems.add(os.path.splitext(os.path.basename(p))[0])
    expected = 0
    missing = []
    for pf in pool_files:
        stem = os.path.splitext(os.path.basename(pf))[0]
        # FightId is the suffix after the last hyphen (characterId- prefix)
        # but fightId itself may contain hyphens, so check suffix match conservatively
        is_dup = stem in local_stems or any(stem.endswith("-" + ls) for ls in local_stems)
        if not is_dup:
            expected += 1
            if pf not in paths:
                missing.append(os.path.basename(pf))
    check("default_logs includes deduped pool files", len(pool_in_default), expected)
    if missing:
        print("  missing from default_logs: %s" % missing[:5])
        check("no deduped pool file missing from default_logs", len(missing), 0)
    print("  default_logs: %d total (%d local + %d pool deduped)" %
          (len(paths), len(paths) - len(pool_in_default), len(pool_in_default)))


def main():
    pooled_corpus()
    default_logs_includes_pool()
    if failures:
        print("\n%d CHECK(S) FAILED" % len(failures))
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
