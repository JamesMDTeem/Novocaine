#!/usr/bin/env python3
"""A fingerprint of everything the combat suite's regeneration stage reads.

    python tools/combat/derived_stamp.py            print it
    python tools/combat/derived_stamp.py --write    print it and store it as the last regeneration's

WHY (2026-09-27). The regeneration stage of tools/check-combat.ps1 - parse_deck.py, estimate.py
--write-pack, creature_sizes.py, player_lines.py - took 482 of the suite's 1,130 seconds, and on a
rerun with nothing new it rewrites the same files byte for byte ("nothing derived moved"). The
suite now compares this stamp with the one stored after the last successful regeneration and skips
the stage when they match.

WHAT IT COVERS, and it errs towards "changed": every log in the pool and every deck dump (path,
size, modification time - a synced fight is a new file); every Python source in tools/combat (the
estimator and its readers); and every data/combat file the stage reads but does not write (the
wiki scrapes, the move sheets, creature notes, weapon classes). A derived file that is missing also
forces a run. The stored stamp lives in the ignored pool directory, beside what it describes.
"""

import hashlib
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
POOL = os.path.join(ROOT, "data", "combat", "pool")
STAMP = os.path.join(POOL, ".derived-stamp")

# Written by the regeneration stage - their own contents are its output, not its input.
DERIVED = ("moves_sheet.json", "moves_ingame.json", "opponents.json", "weapons_seen.json",
           "characters.json", "animal_moves_measured.json", "creature_sizes.json",
           "player_lines.json", "individuals.json")


def stamp():
    h = hashlib.sha256()
    for dirpath, dirs, files in os.walk(POOL):
        # Dot-names in the pool are this project's caches (.sweep-cache/, .context-cache.pkl,
        # .deck-dumps.pkl) and this stamp itself; a cache written by a check must not make the
        # next run regenerate.
        dirs[:] = sorted(x for x in dirs if not x.startswith("."))
        for f in sorted(files):
            if f.startswith("."):
                continue
            p = os.path.join(dirpath, f)
            try:
                st = os.stat(p)
            except OSError:
                continue
            h.update(("%s|%d|%d\n" % (os.path.relpath(p, POOL), st.st_size, int(st.st_mtime))).encode())
    tools = os.path.join(ROOT, "tools", "combat")
    for f in sorted(os.listdir(tools)):
        if f.endswith(".py"):
            with open(os.path.join(tools, f), "rb") as fh:
                h.update(f.encode() + b"\0" + fh.read())
    data = os.path.join(ROOT, "data", "combat")
    for f in sorted(os.listdir(data)):
        p = os.path.join(data, f)
        if (not os.path.isfile(p)) or (f in DERIVED):
            continue
        with open(p, "rb") as fh:
            h.update(f.encode() + b"\0" + fh.read())
    for f in DERIVED:
        if not os.path.exists(os.path.join(data, f)):
            h.update(b"missing " + f.encode())
    return h.hexdigest()


def main(argv):
    s = stamp()
    if "--write" in argv:
        with open(STAMP, "w") as fh:
            fh.write(s + "\n")
    elif "--check" in argv:
        try:
            with open(STAMP) as fh:
                old = fh.read().strip()
        except OSError:
            old = None
        print("unchanged" if old == s else "changed")
        return 0
    print(s)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
