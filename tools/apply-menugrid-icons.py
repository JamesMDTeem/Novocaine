# Applies our own menu-grid icons to the "Nurgling Imports" resources under
# res/customclient/menugrid/NurglingImports*.
#
# Why this exists: gen-menugrid-res.py builds each of our menu entries by copying the IMAGE
# layer of an existing Hurricane template res. That is fine for getting a button on screen,
# but it left every one of our buttons wearing somebody else's picture - four of them shared
# the same red flower, three the same fish - so the menu was unreadable at a glance. The
# icons here are ours, one per entry, drawn to say what the entry does.
#
# Sources live in tools/menugrid-icons/:
#   <Name>.svg   hand-editable source; underscores in the name are the path separators, so
#                NurglingImports_LpAssistant_AutoLpBot.svg -> NurglingImports/LpAssistant/AutoLpBot.res
#   <Name>.png   the 128x128 RGBA render of that svg, committed so a plain checkout can
#                re-apply the icons without a browser installed
#
# Usage (from the repo root):
#   python tools/apply-menugrid-icons.py                 # png -> res
#   python tools/apply-menugrid-icons.py --render        # svg -> png -> res (needs Chrome)
#   python tools/apply-menugrid-icons.py --render-only   # svg -> png, don't touch the res files
#
# Packing is done by ResForge (https://github.com/Nightdawg/ResForge), which swaps just the
# embedded PNG and keeps the image layer's header - z, sub-z, id, draw offset and the
# scale=4.0 metadata that makes a 128x128 source draw at 32x32 - byte-for-byte. Point
# RESFORGE_JAR at the jar, or drop it in tools/. `resforge verify` re-reads what we wrote.
#
# The icons draw on the menu grid's black background at 32x32, so they are built for that:
# few shapes, heavy dark outlines, saturated fills, and no detail that survives only at
# 128px. The six crew bots also carry a three-dot badge in the bottom-right - the thing
# that distinguishes them from the stock single-character bots is that a crew runs them.

import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICONS = os.path.join(ROOT, "tools", "menugrid-icons")
MENUGRID = os.path.join(ROOT, "res", "customclient", "menugrid")
SUPERSAMPLE = 512

CHROME_CANDIDATES = [
    os.environ.get("CHROME"),
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]


def find_java():
    for c in (os.environ.get("JAVA_HOME"), r"C:\Program Files\Java\jdk-21"):
        if c and os.path.isfile(os.path.join(c, "bin", "java.exe")):
            return os.path.join(c, "bin", "java.exe")
    java = shutil.which("java")
    if java:
        return java
    sys.exit("no java found - set JAVA_HOME to a JDK 21 install")


def find_resforge():
    env = os.environ.get("RESFORGE_JAR")
    if env and os.path.isfile(env):
        return env
    here = sorted(f for f in os.listdir(os.path.join(ROOT, "tools"))
                  if f.startswith("resforge") and f.endswith(".jar"))
    if here:
        return os.path.join(ROOT, "tools", here[-1])
    sys.exit("no ResForge jar - set RESFORGE_JAR, or put resforge-<ver>.jar in tools/\n"
             "  gh release download --repo Nightdawg/ResForge --pattern 'resforge-*.jar'")


def render(names):
    """svg -> 128x128 png. Chrome rasterises at 4x and Pillow downsamples, because the
    outlines are thin enough at 32x32 that a 1:1 rasterisation aliases them into dust."""
    from PIL import Image
    chrome = next((c for c in CHROME_CANDIDATES if c and os.path.isfile(c)), None)
    if not chrome:
        sys.exit("no Chrome/Edge found for rasterising - set CHROME, or skip --render and "
                 "use the committed PNGs")
    for n in names:
        svg = os.path.join(ICONS, n + ".svg").replace(os.sep, "/")
        # Chrome draws an SVG document at its intrinsic size, so wrap it in a page that
        # stretches it to the window instead.
        html = os.path.join(ICONS, "_render.html")
        raw = os.path.join(ICONS, "_render.png")
        with open(html, "w", encoding="utf-8") as f:
            f.write("<style>html,body{margin:0;padding:0;background:transparent}"
                    f"img{{width:{SUPERSAMPLE}px;height:{SUPERSAMPLE}px;display:block}}</style>"
                    f'<img src="file:///{svg}">')
        subprocess.run([chrome, "--headless", "--disable-gpu", "--hide-scrollbars",
                        "--force-device-scale-factor=1",
                        "--default-background-color=00000000",
                        f"--window-size={SUPERSAMPLE},{SUPERSAMPLE}",
                        f"--screenshot={raw}", html], check=True, capture_output=True)
        img = Image.open(raw).convert("RGBA").resize((128, 128), Image.Resampling.LANCZOS)
        img.save(os.path.join(ICONS, n + ".png"))
        os.remove(raw)
        os.remove(html)
        print(f"rendered {n}.png")


def apply(names):
    java, jar = find_java(), find_resforge()
    for n in names:
        png = os.path.join(ICONS, n + ".png")
        res = os.path.join(MENUGRID, n.replace("_", os.sep) + ".res")
        if not os.path.isfile(res):
            sys.exit(f"no such resource: {res}")
        r = subprocess.run([java, "-jar", jar, "replace", res, "image", png, res],
                           capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"resforge replace failed for {n}:\n{r.stdout}{r.stderr}")
        print(f"applied {n}.png -> {os.path.relpath(res, ROOT)}")
    v = subprocess.run([java, "-jar", jar, "verify", MENUGRID], capture_output=True, text=True)
    print(v.stdout.strip() or v.stderr.strip())
    if v.returncode != 0:
        sys.exit("resforge verify failed")


if __name__ == "__main__":
    names = sorted(f[:-4] for f in os.listdir(ICONS) if f.endswith(".svg"))
    args = sys.argv[1:]
    if "--render" in args or "--render-only" in args:
        render(names)
    if "--render-only" not in args:
        apply(names)
