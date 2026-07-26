# Generates the "Nurgling Imports" custom menu-grid resources (Haven .res files) under
# res/customclient/menugrid/, following the same layout Hurricane uses for its own custom
# categories (Bots, Combat Decks, ...). Each new res reuses the IMAGE layer of an existing
# template res (icons), and gets a freshly built action layer (parent/name/ad) and tooltip.
#
# Haven resource format (verified against Resource.java and a hexdump of Bots.res):
#   "Haven Resource 1" | uint16 version | layers...
#   layer = cstring type | int32 LE payload length | payload
#   action payload = cstring parent-res | uint16 parent-ver | cstring name | cstring preq
#                  | uint16 hotkey | uint16 n | n * cstring ad
#   tooltip payload = utf-8 text (no terminator)
#
# Usage: python tools/gen-menugrid-res.py   (from the repo root)
#
# The TEMPLATES this reads (Bots.res, Bots/*.res, Toggles/*.res, ...) are upstream payload and are
# gitignored - only our own NurglingImports output is tracked. A fresh clone (or a worktree) has
# only the outputs, so copy the templates in from a full install's res/customclient/menugrid/
# before re-running this, and remove them again afterwards so an `ant bin` can't overwrite the
# install's res tree with a partial one.

import os
import struct

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "res", "customclient", "menugrid")

SIG = b"Haven Resource 1"


def read_layers(path):
    data = open(path, "rb").read()
    assert data[:16] == SIG, path
    ver = struct.unpack("<H", data[16:18])[0]
    off = 18
    layers = []
    while off < len(data):
        end = data.index(b"\0", off)
        ltype = data[off:end].decode("ascii")
        off = end + 1
        (ln,) = struct.unpack("<i", data[off:off + 4])
        off += 4
        layers.append((ltype, data[off:off + ln]))
        off += ln
    return ver, layers


def cstr(s):
    return s.encode("utf-8") + b"\0"


def action_payload(parent, parent_ver, name, hotkey, ad):
    out = cstr(parent) + struct.pack("<H", parent_ver) + cstr(name) + cstr("")
    out += struct.pack("<H", hotkey) + struct.pack("<H", len(ad))
    for a in ad:
        out += cstr(a)
    return out


def write_res(path, ver, layers):
    out = SIG + struct.pack("<H", ver)
    for ltype, payload in layers:
        out += cstr(ltype) + struct.pack("<i", len(payload)) + payload
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(out)
    print(f"wrote {os.path.relpath(path, ROOT)} ({len(out)} bytes, {len(layers)} layers)")


def build(template, target, parent, name, hotkey, ad, tooltip):
    ver, layers = read_layers(os.path.join(ROOT, template))
    kept = [(t, p) for (t, p) in layers if t not in ("action", "tooltip", "pagina")]
    new = [("action", action_payload(parent, 4, name, hotkey, ad))]
    if tooltip:
        new.append(("tooltip", tooltip.encode("utf-8")))
    write_res(os.path.join(ROOT, target), ver, new + kept)


# The category pagina: same parent as Bots/CombatDecks/etc. No ad - it's a folder.
build("Bots.res", "NurglingImports.res",
      "customclient/menugrid/CustomClientExtras", "| Nurgling Imports |", ord('N'), [],
      "Features ported over from the nurgling2 client.")

# LP Assistant Manager window (settings + per-character reset).
build("OtherScriptsAndTools/FlowerMenuAutoSelectManager.res",
      "NurglingImports/LpAssistantManager.res",
      "customclient/menugrid/NurglingImports", "LP Assistant Manager", 0,
      ["@", "NurglingImports", "LpAssistantManager"],
      "Configure the LP assistant: discovery markers, harvest overlays, the auto-LP bot's "
      "felling/eating rules and search radius, and the per-character LP-log reset.")

# The Auto-LP bot window (Start/Stop, status).
build("Bots/FishingBot.res",
      "NurglingImports/AutoLpBot.res",
      "customclient/menugrid/NurglingImports", "Auto LP Bot", 0,
      ["@", "NurglingImports", "AutoLpBot"],
      "Walks to whatever nearby thing would yield an LP product this character hasn't "
      "discovered yet (picks, mines, processes; felling only if enabled), and repeats until "
      "nothing reachable is left. Configure in the LP Assistant Manager.")

# Quick on/off toggle for the whole LP assistant.
build("Toggles/HighlightCliffs.res",
      "NurglingImports/LpAssistantToggle.res",
      "customclient/menugrid/NurglingImports", "Toggle LP Assistant", 0,
      ["@", "NurglingImports", "LpAssistantToggle"],
      "Turn the LP assistant (undiscovered-product markers and discovery tracking) on or off.")

# Shared behaviour toggles for the crew bots below.
build("OtherScriptsAndTools/FlowerMenuAutoSelectManager.res",
      "NurglingImports/NBotsSettings.res",
      "customclient/menugrid/NurglingImports", "Custom Settings", 0,
      ["@", "NurglingImports", "NBotsSettings"],
      "Behaviour shared by the crew bots, grouped by what it answers: working together, looking "
      "after themselves, getting around (water, gates, remembered walls), and what you can see of "
      "what they are doing.")

# Where the bots go for water, food, tools and storage.
build("OtherScriptsAndTools/AutoDropManager.res",
      "NurglingImports/NBotPlaces.res",
      "customclient/menugrid/NurglingImports", "Bot Places", 0,
      ["@", "NurglingImports", "NBotPlaces"],
      "Name regions of the map and tag what they are for - water, food, tools, somewhere to dump "
      "output. Bots ask for a place by role rather than being told coordinates, so one definition "
      "serves every bot and every client launched from this install.")

# The three crew bots. Each is a separate class from the stock Bots-tab version, which is left
# exactly as it is - see haven.automated.nbots.NBot for why.
build("Bots/CellarDiggingBot.res",
      "NurglingImports/NCellarDiggerBot.res",
      "customclient/menugrid/NurglingImports", "Cellar Digger (crew)", 0,
      ["@", "NurglingImports", "NCellarDiggerBot"],
      "Digs a cellar with several characters at once: boulders are worked by standing position "
      "rather than claimed whole, the dig itself is taken one character at a time, the pickaxe is "
      "fetched if it isn't in hand, and running out of water is a trip to refill rather than the "
      "end of the shift.")

build("Bots/CleanupBot.res",
      "NurglingImports/NCleanupBot.res",
      "customclient/menugrid/NurglingImports", "Cleanup (crew)", 0,
      ["@", "NurglingImports", "NCleanupBot"],
      "Clears trees, bushes, boulders, stumps and soil piles, swapping between axe, pickaxe and "
      "shovel by itself. Several can work one site without converging on the same trunk.")

build("Bots/OceanScoutBot.res",
      "NurglingImports/NWaterScoutBot.res",
      "customclient/menugrid/NurglingImports", "Water Scout (crew)", 0,
      ["@", "NurglingImports", "NWaterScoutBot"],
      "Follows a coastline or a river bank by boat, revealing map as it goes. Pick ocean or "
      "fresh water and which side to keep the deep water on.")
