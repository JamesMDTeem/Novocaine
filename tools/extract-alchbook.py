#!/usr/bin/env python3
"""
Extract the Alchemy Book resource from the local Haven resource cache.

The book is not in this source tree: the server ships it as the resource
ui/alchbook, whose code layers define haven.res.ui.alchbook.* and whose src
layers carry the original Java. AlchemyBook.java reflects into those classes,
so check-alchbook-contract.sh needs them on disk to verify against.

Nothing here is committed -- the extracted output is Loftar's code, and it is
regenerated on demand from whatever the client has already downloaded.

Usage:
    tools/extract-alchbook.py [outdir]          # default: build/alchbook

The cache is found automatically on Windows and WSL; override with
HAVEN_CACHE=/path/to/data if it lives somewhere unusual.
"""
import glob
import os
import struct
import sys

SIG = b"Haven Resource 1"
RESNAME = b"res/ui/alchbook"


def cstr(buf, off):
    end = buf.index(b"\0", off)
    return buf[off:end].decode("utf-8", "replace"), end + 1


def find_cache():
    """
    Locate the resource cache. Runs from WSL as well as native Windows, where
    the same directory is reached by different paths.
    """
    if os.environ.get("HAVEN_CACHE"):
        return os.environ["HAVEN_CACHE"]
    candidates = []
    if os.environ.get("APPDATA"):
        candidates.append(os.path.join(os.environ["APPDATA"], "Haven and Hearth", "data"))
    candidates.append(os.path.expanduser("~/AppData/Roaming/Haven and Hearth/data"))
    candidates += sorted(glob.glob("/mnt/c/Users/*/AppData/Roaming/Haven and Hearth/data"))
    for c in candidates:
        if os.path.isdir(c):
            return c
    sys.exit(
        "resource cache not found; tried:\n  "
        + "\n  ".join(candidates)
        + "\nset HAVEN_CACHE to override"
    )


def find_resource(cache):
    """Cache files are hash-named; the resource name sits in the header."""
    for name in os.listdir(cache):
        path = os.path.join(cache, name)
        try:
            with open(path, "rb") as fp:
                head = fp.read(256)
        except OSError:
            continue
        if RESNAME in head and SIG in head:
            return path
    sys.exit(
        "ui/alchbook not in the cache. Log in with a character that has the "
        "Alchemy Book unlocked, then retry."
    )


def extract(path, outdir):
    buf = open(path, "rb").read()
    i = buf.index(SIG) + len(SIG) + 2  # signature + uint16 version
    classdir = os.path.join(outdir, "haven", "res", "ui", "alchbook")
    srcdir = os.path.join(outdir, "src")
    os.makedirs(classdir, exist_ok=True)
    os.makedirs(srcdir, exist_ok=True)
    n_class = n_src = 0

    while i < len(buf):
        try:
            layer, i = cstr(buf, i)
            (ln,) = struct.unpack_from("<i", buf, i)
            i += 4
        except (ValueError, struct.error):
            break
        if ln < 0 or i + ln > len(buf):
            break
        data, i = buf[i : i + ln], i + ln

        if layer == "code":
            cls, off = cstr(data, 0)
            leaf = cls.rsplit(".", 1)[-1] + ".class"
            open(os.path.join(classdir, leaf), "wb").write(data[off:])
            n_class += 1
        elif layer == "src":
            # a leading marker byte precedes the filename
            fn, off = cstr(data, 1)
            open(os.path.join(srcdir, os.path.basename(fn)), "wb").write(data[off:])
            n_src += 1

    print(f"{path}\n  {n_class} classes -> {classdir}\n  {n_src} sources -> {srcdir}")


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "build/alchbook"
    extract(find_resource(find_cache()), out)
