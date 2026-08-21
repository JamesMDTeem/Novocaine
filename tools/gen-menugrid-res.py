# Generates the "Nurgling Imports" custom menu-grid resources (Haven .res files) under
# res/customclient/menugrid/, following the same layout Hurricane uses for its own custom
# categories (Bots, Combat Decks, ...). Each res is built whole: an action layer
# (parent/name/ad), a tooltip, and our own icon from tools/menugrid-icons/.
#
# Haven resource format (verified against Resource.java and a hexdump of Bots.res):
#   "Haven Resource 1" | uint16 version | layers...
#   layer = cstring type | int32 LE payload length | payload
#   action payload = cstring parent-res | uint16 parent-ver | cstring name | cstring preq
#                  | uint16 hotkey | uint16 n | n * cstring ad
#   tooltip payload = utf-8 text (no terminator)
#   image payload  = uint8 z-lo | int8 z-hi | int16 subz | uint8 flags | int16 id
#                  | int16 off-x | int16 off-y | [flags&4: cstring key | uint8 len | len bytes,
#                    terminated by an empty key] | png
#
# Usage: python tools/gen-menugrid-res.py   (from the repo root)
#
# This used to copy the IMAGE layer out of an upstream template res (Bots.res, Toggles/*.res,
# ...), which meant it could only run against a full install - the templates are upstream
# payload and gitignored - and it left every one of our buttons wearing somebody else's
# picture: four shared one red flower, three shared one fish. Both problems are gone. The
# icons are ours (see tools/apply-menugrid-icons.py for where they come from and how to
# re-skin one), and nothing here reads a file it does not own, so this runs in a bare clone.

import os
import struct

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.join(BASE, "res", "customclient", "menugrid")
ICONS = os.path.join(BASE, "tools", "menugrid-icons")

SIG = b"Haven Resource 1"

# Every menu-grid icon in the tree, ours and upstream's, uses exactly these image-layer
# fields, so they are constants rather than parameters. scale=4.0 is the one that matters:
# it is what makes a 128x128 source draw at the 32x32 the menu grid actually lays out.
IMG_Z = 0
IMG_SUBZ = 0
IMG_ID = -1
IMG_OFF = (0, 0)
IMG_SCALE = 4.0


def cstr(s):
    return s.encode("utf-8") + b"\0"


def image_payload(png):
    out = struct.pack("<BbhBh", IMG_Z & 0xff, IMG_Z >> 8, IMG_SUBZ, 4, IMG_ID)
    out += struct.pack("<hh", *IMG_OFF)
    scale = struct.pack("<f", IMG_SCALE)
    out += cstr("scale") + struct.pack("<B", len(scale)) + scale
    out += cstr("")
    return out + png


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


def build(target, parent, name, hotkey, ad, tooltip):
    # The icon is named after the resource path, with the separators flattened to
    # underscores: NurglingImports/LpAssistant/AutoLpBot.res -> the .png of the same name.
    icon = os.path.join(ICONS, target[:-4].replace("/", "_") + ".png")
    png = open(icon, "rb").read()
    assert png[:4] == b"\x89PNG", icon
    layers = [("action", action_payload(parent, 4, name, hotkey, ad))]
    if tooltip:
        layers.append(("tooltip", tooltip.encode("utf-8")))
    layers.append(("image", image_payload(png)))
    write_res(os.path.join(ROOT, target), 4, layers)


# The category pagina: same parent as Bots/CombatDecks/etc. No ad - it's a folder.
#
# The RESOURCE PATH stays "NurglingImports" while the label does not. Renaming the directory would
# rename every leaf's path, and those paths are what an action bar stores - so everyone who has put
# one of these on their belt would find it blank after an update. The label is the part anybody
# sees; the path is bookkeeping, and it is honest bookkeeping about where the code came from.
build("NurglingImports.res",
      "customclient/menugrid/CustomClientExtras", "| Novocaine |", ord('N'), [],
      "Everything this client adds: the LP assistant, and the bots a crew of characters runs "
      "together.")

# Two sub-folders, because nine items in one folder is a list you read rather than one you glance
# at. The three settings-ish items stay at the top level - they are what you open first and what
# the two folders both depend on.
build("NurglingImports/LpAssistant.res",
      "customclient/menugrid/NurglingImports", "| LP Assistant |", 0, [],
      "Finding and doing the things this character has not yet learned anything from.")

build("NurglingImports/CrewBots.res",
      "customclient/menugrid/NurglingImports", "| Crew Bots |", 0, [],
      "Bots written for several characters working one site at once, rather than one character "
      "working alone.")

# LP Assistant Manager window (settings + per-character reset).
#
# The ad payload is deliberately unchanged by the move: a leaf's place in the menu comes from its
# parent, and the ad is what MenuGrid dispatches on. Keeping them separate is what lets the folders
# be rearranged without touching the switch in MenuGrid.useCustom at all.
build("NurglingImports/LpAssistant/LpAssistantManager.res",
      "customclient/menugrid/NurglingImports/LpAssistant", "LP Assistant Manager", 0,
      ["@", "NurglingImports", "LpAssistantManager"],
      "Configure the LP assistant: discovery markers, harvest overlays, the auto-LP bot's "
      "felling/eating rules and search radius, and the per-character LP-log reset.")

# The Auto-LP bot window (Start/Stop, status).
build("NurglingImports/LpAssistant/AutoLpBot.res",
      "customclient/menugrid/NurglingImports/LpAssistant", "Auto LP Bot", 0,
      ["@", "NurglingImports", "AutoLpBot"],
      "Walks to whatever nearby thing would yield an LP product this character hasn't "
      "discovered yet (picks, mines, processes; felling only if enabled), and repeats until "
      "nothing reachable is left. Configure in the LP Assistant Manager.")

# Quick on/off toggle for the whole LP assistant.
build("NurglingImports/LpAssistant/LpAssistantToggle.res",
      "customclient/menugrid/NurglingImports/LpAssistant", "Toggle LP Assistant", 0,
      ["@", "NurglingImports", "LpAssistantToggle"],
      "Turn the LP assistant (undiscovered-product markers and discovery tracking) on or off.")

# Shared behaviour toggles for the crew bots below.
build("NurglingImports/NBotsSettings.res",
      "customclient/menugrid/NurglingImports", "Custom Settings", 0,
      ["@", "NurglingImports", "NBotsSettings"],
      "Behaviour shared by the crew bots, grouped by what it answers: working together, looking "
      "after themselves, getting around (water, gates, remembered walls), and what you can see of "
      "what they are doing.")

# Where the bots go for water, food, tools and storage.
build("NurglingImports/NBotPlaces.res",
      "customclient/menugrid/NurglingImports", "Bot Places", 0,
      ["@", "NurglingImports", "NBotPlaces"],
      "Name regions of the map and tag what they are for - water, food, tools, somewhere to dump "
      "output. Bots ask for a place by role rather than being told coordinates, so one definition "
      "serves every bot and every client launched from this install.")

# Account switcher: one button that lists the saved accounts and switches between them with a click.
build("NurglingImports/AltManager.res",
      "customclient/menugrid/NurglingImports", "Alt Manager", 0,
      ["@", "NurglingImports", "AltManager"],
      "Lists the accounts saved on this client and switches between them with one click. "
      "Gated by the Alt Manager checkbox in Advanced Settings -> Gameplay Automation.")

# The three crew bots. Each is a separate class from the stock Bots-tab version, which is left
# exactly as it is - see haven.automated.nbots.NBot for why.
build("NurglingImports/CrewBots/NCellarDiggerBot.res",
      "customclient/menugrid/NurglingImports/CrewBots", "Cellar Digger (crew)", 0,
      ["@", "NurglingImports", "NCellarDiggerBot"],
      "Digs a cellar with several characters at once: boulders are worked by standing position "
      "rather than claimed whole, the dig itself is taken one character at a time, the pickaxe is "
      "fetched if it isn't in hand, and running out of water is a trip to refill rather than the "
      "end of the shift.")

build("NurglingImports/CrewBots/NCleanupBot.res",
      "customclient/menugrid/NurglingImports/CrewBots", "Cleanup (crew)", 0,
      ["@", "NurglingImports", "NCleanupBot"],
      "Clears trees, bushes, boulders, stumps and soil piles, swapping between axe, pickaxe and "
      "shovel by itself. Several can work one site without converging on the same trunk.")

build("NurglingImports/CrewBots/NWaterScoutBot.res",
      "customclient/menugrid/NurglingImports/CrewBots", "Water Scout (crew)", 0,
      ["@", "NurglingImports", "NWaterScoutBot"],
      "Follows a coastline or a river bank by boat, revealing map as it goes. Pick ocean or "
      "fresh water and which side to keep the deep water on.")

build("NurglingImports/CrewBots/NPlowBot.res",
      "customclient/menugrid/NurglingImports/CrewBots", "Plower (crew)", 0,
      ["@", "NurglingImports", "NPlowBot"],
      "Ploughs a field furrow by furrow, up one column of tiles and down the next. "
      "Holds the field while it works, so a second bot picks a different one.")

build("NurglingImports/CrewBots/NSurveyBot.res",
      "customclient/menugrid/NurglingImports/CrewBots", "Survey (crew)", 0,
      ["@", "NurglingImports", "NSurveyBot"],
      "Mans land surveys: digs each one out, removes it once drained, and moves to the next. "
      "Holds the whole area while it works, so a second bot picks a different field.")

build("NurglingImports/CrewBots/NBeeSmokerBot.res",
      "customclient/menugrid/NurglingImports/CrewBots", "Bee Smoker (crew)", 0,
      ["@", "NurglingImports", "NBeeSmokerBot"],
      "Builds a pyre under every wild beehive in sight, waits for the smoke to send the swarm "
      "off, then raids each hive before moving on.")

build("NurglingImports/CrewBots/NDragonflyBot.res",
      "customclient/menugrid/NurglingImports/CrewBots", "Dragonfly (crew)", 0,
      ["@", "NurglingImports", "NDragonflyBot"],
      "Rows a dugout around a swamp, catching every dragonfly it can, then hearths home and "
      "files the catch.")

# The LP Helper toggle. A toggle rather than a window launcher because the window is not something
# you open - it appears on its own whenever a container is open and the toggle is on.
build("NurglingImports/StudyHelper.res",
      "customclient/menugrid/NurglingImports", "LP Helper", 0,
      ["@", "NurglingImports", "StudyHelper"],
      "While a container is open, ranks the curiosities inside by LP/Hour per Mental Weight and "
      "shows which ones fit your Attention and study grid. Grab from the top of the list down to "
      "the red line.")

# The Eating Helper toggle - same shape as the LP Helper above, a window that shows/hides itself
# off the OptWnd checkbox rather than something this button opens directly.
build("NurglingImports/EatHelper.res",
      "customclient/menugrid/NurglingImports", "Eating Helper", 0,
      ["@", "NurglingImports", "EatHelper"],
      "Enter target attribute points and it works out which foods from your cookbook catalog get "
      "you there, ranked by hunger cost. Advise only - nothing is eaten automatically.")

build("NurglingImports/Schedules.res",
      "customclient/menugrid/NurglingImports", "Schedules", 0,
      ["@", "NurglingImports", "SchedulesWindow"],
      "Runs bots on a timed loop - e.g. dragonfly for ten minutes, wait fifteen, repeat.")
