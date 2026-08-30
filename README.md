# Novocaine

A custom Haven & Hearth client, built on
[Nightdawg/Hurricane](https://github.com/Nightdawg/Hurricane) (currently v1.69) with our own
features layered on top. All credit for the base client goes to Nightdawg and the Hurricane
project, and to Loftar's Vanilla client under that — Loftar's own README is preserved here as
[`README_Vanilla-Client`](README_Vanilla-Client).

## Just want to play?

Grab the newest zip from [Releases](https://github.com/JamesMDTeem/Novocaine/releases),
extract it, and run **`Novocaine.bat`**. You need a JRE, nothing else — no clone, no build.

That one file is also the updater: every launch checks GitHub for a newer release, downloads
the small delta rather than the ~170 MB full zip where it can, verifies every file against the
release manifest, and then starts the game with no console window attached. Your settings live
in the registry and `%APPDATA%\Haven and Hearth`, so they are never touched, and anything you
dropped into the install folder yourself is left alone.

```
Novocaine.bat              update, then play
Novocaine.bat -Check       is there a newer release? (installs nothing)
Novocaine.bat -Count 4     four clients at once, for a crew
Novocaine.bat -Console     keep a console window attached
Novocaine.bat -ZGC         generational ZGC instead of G1
```

## Getting started (building from source)

1. **Install a JDK.** [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21)
   (free, official OpenJDK builds) — during setup, tick "Set JAVA_HOME" and "Add to
   PATH" if the installer offers them. Any JDK 17–21 works.
2. **Install Apache Ant.** Grab the "Binary Distributions" zip from the
   [official site](https://ant.apache.org/bindownload.cgi) and extract it so
   `ant.bat` ends up at `C:\ant\apache-ant-<version>\bin\ant.bat` (i.e. extract the
   zip directly into `C:\ant`).
3. **Get this repo** — `git clone` it, or download it as a zip from GitHub and
   extract it.
4. **Run the launcher** from the repo folder in PowerShell:
   ```powershell
   .\Novocaine.ps1
   ```
   The first run downloads JOGL/LWJGL/Steamworks jars and the game resources
   (~300 MB) from the Hurricane CDN, builds, and launches. That first run takes a
   few minutes; every run after is much faster.

If either tool is missing, the script tells you exactly what to install and where to
put it, then stops cleanly — just rerun it once you have both.

## How this fork works

**master is the trunk.** We own this client and take full authorship. Upstream changes
are pulled in deliberately, one at a time, through a reviewed merge process — not by
an auto-update script that re-applies the whole fork as a patch.

The old auto-update flow (keeping the fork as a `vendor-baseline..alchemy` patch and
re-applying it on top of each new Hurricane release) is **retired**. The `vendor-baseline`
and `alchemy` tags remain as static historical markers only.

To bring in upstream changes:

```powershell
# See what's available upstream
.\tools\merge-upstream.ps1 -List

# Review the source delta before importing
.\tools\merge-upstream.ps1 -Diff v1.68

# Import after review (stages changes, no auto-commit by default)
.\tools\merge-upstream.ps1 -Import v1.68
# Review with: git diff --staged
# Then commit manually, or use -Commit to auto-commit

# Or cherry-pick a specific feature from hafen/nurgling
.\tools\merge-upstream.ps1 -Pick hafen abc1234

# See the full current fork delta vs upstream v1.67
.\tools\merge-upstream.ps1 -ForkDiff
```

The daily build/launch script is `Novocaine.ps1` — the same one that ships in a release,
which builds instead of updating when it finds a `build.xml` next to it:

```powershell
.\Novocaine.ps1                    # build + launch
.\Novocaine.ps1 -NoLaunch          # build only (the typecheck gate)
.\Novocaine.ps1 -Count 8           # build once, launch a crew of eight
.\Novocaine.ps1 -Count 2 -NoBuild  # two more clients against the build you have
.\Novocaine.ps1 -ZGC -Console      # ZGC, with a console to read GC logs in
```

`build-and-play.ps1` is kept as a shim that forwards to it, so old habits and the packaging
scripts keep working.

The JVM flags are **not** written out in `Novocaine.ps1`. They live in `Play.bat`, which has
to exist anyway — `hafen.hl` names it as the Steam launcher's `command-file` and the HL
launcher parses the `--add-exports` and `-D` properties out of it. `Novocaine.ps1` reads the
same line, so heap sizes and exports have exactly one home.

## Custom features

The in-game ones all live under a single menu-grid folder:
**Custom Client Extras → | Novocaine |**.

- **LP Assistant** (`src/haven/automated/lp/`, menu: | Novocaine | → | LP Assistant |) — the
  LP-assistant feature set ported from nurgling2: discovery markers (world + minimap,
  right-clickable) for undiscovered LP products, always-on harvest overlays, a never-crafted
  highlight in the crafting menu, and an **Auto LP Bot** that walks to and collects nearby
  undiscovered products. Configure via **LP Assistant Manager**; flip it off quickly via
  **Toggle LP Assistant**.

- **Crew bots** (`src/haven/automated/nbots/`, menu: | Novocaine | → | Crew Bots |) — bots
  written for several characters working one site at once rather than one character working
  alone: **Cellar Digger**, **Cleanup**, **Water Scout**, **Plower**, **Survey**,
  **Bee Smoker**, **Dragonfly**, **Stockpile Mover** and **Survey Planner**, all "(crew)".
  They coordinate
  across client processes so two characters never walk to the same working spot, share the
  areas you draw in **Bot Places**, and route over terrain they have actually seen rather than
  guessing. Shared behaviour is under **Custom Settings**.

  Their learned state lives in the install folder (`botmap.json`, `botplaces.json`, `logs/`),
  so it survives an update but not a fresh extract to a new directory.

- **Alchemy Book mirror** (`src/haven/automated/alchemy/`) — no menu entry; it runs passively.
  On login it reads the in-game Alchemy Book by reflection and writes
  `alchemy-book-dump.json`. If (and only if) you have configured a cookbook endpoint, it also
  uploads ingredient discoveries and elixir crafts to the same server — with no endpoint set,
  nothing leaves your machine. One integration hook: a single line in `GameUI.tick`.

  The alchemy code reflects into classes the *game server* ships, so the compiler cannot check
  it and the failure mode is **silence, not an exception** — a book that reports empty after a
  game update means the contract drifted. `tools/extract-alchbook.py` and
  `tools/check-alchbook-contract.sh` (run from WSL) diagnose that.

- `tools/gen-lpspec.py` / `tools/gen-menugrid-res.py` regenerate the LP data tables and the
  menu resources — see the comments in each for when to rerun them.

## Privacy

The client talks **only** to the official Seatribe server unless you configure an integration
yourself. The web-map upload, the cookbook/food-stats service, and the alchemy upload above are
all opt-in and all point at an endpoint you supply in the options window. There is no telemetry
and no default endpoint.

## Releasing to friends (maintainer)

Two distribution channels, each a one-command script:

- **GitHub Release** (easiest for friends — no clone, no build, just a JRE):
  ```powershell
  .\tools\make-release.ps1 -Draft        # build, zip bin\, publish a draft release
  .\tools\make-release.ps1 -Version 0.1.13
  ```
  Builds a clean client, zips `bin\` into `dist\Novocaine-<version>.zip`, and creates/updates
  the GitHub Release with it attached. Friends download the zip, extract, and run
  `Novocaine\Novocaine.bat`. Needs the [GitHub CLI](https://cli.github.com/) (`gh auth login`).

- **Steam Workshop** (friends-only visibility): see [`steam/README.md`](steam/README.md).
  ```powershell
  .\tools\make-steam-item.ps1            # stage the item into dist\steam-item (no upload)
  .\tools\make-steam-item.ps1 -Upload    # stage + upload (Steam must be running/logged in)
  ```
  The upload is yours to run — it needs Steam logged in, beta access to the game, and the
  Workshop Legal Agreement accepted. Metadata lives in `steam/workshop-client.properties`
  (kept separate from the repo-root Hurricane one so uploads never touch Nightdawg's item).

Both scripts build from your working tree; commit first if you want the release to match a
pushed state.

## Remotes

- `origin` — this repo (our fork).
- `upstream` — `https://github.com/Nightdawg/Hurricane.git` (Hurricane lineage)
- `hafen`    — `https://github.com/dolda2000/hafen-client.git` (Vanilla lineage, upstream of Hurricane)
- `nurgling` — `https://github.com/aleksandrsvoboda/nurgling2.git` (active fork with useful features)

`tools/merge-upstream.ps1` adds `hafen` and `nurgling` automatically on first run.

## Building & playing

Requires JDK 17–21 and Apache Ant (see "Getting started" above). `.\Novocaine.ps1`
resolves both, builds with Ant, and launches out of `bin\`.

A note on `bin\`: it is the **live game install**, not build output. Alongside the jars it
holds the crew-bot state and the alchemy dump. `ant clean` deletes it — use
`.\Novocaine.ps1` instead unless you actually mean to start over.

For base-client details, see [`README_Vanilla-Client`](README_Vanilla-Client) (Loftar's own
README) and the [Hurricane](https://github.com/Nightdawg/Hurricane) repo.