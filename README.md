# Novocaine

A custom Haven & Hearth client: a live pull of
[Nightdawg/Hurricane](https://github.com/Nightdawg/Hurricane) with our own features
layered on top. All credit for the base client goes to Nightdawg and the Hurricane
project (and Loftar's Vanilla client under that).

## Getting started (new install)

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
   .\update-and-play.ps1
   ```
   The first run fetches the full Hurricane release (source, resources, jars — a few
   hundred MB) from upstream, builds, and launches. That first run takes a few
   minutes; every run after is much faster.

If either tool is missing, the script tells you exactly what to install and where to
put it, then stops cleanly — just rerun it once you have both.

## How this fork works

The custom work is deliberately kept as a **patch between two tags** rather than a
long-lived branch:

```
vendor-baseline .. alchemy
```

Updating to a new Hurricane release means: check out the upstream release, re-apply
that patch, rebuild. `update-and-play.ps1` automates the whole cycle:

```powershell
.\update-and-play.ps1              # update to latest upstream release, rebuild, launch
.\update-and-play.ps1 -SkipUpdate  # rebuild + launch what's checked out (after editing the fork)
.\update-and-play.ps1 -Tag v1.67   # pin a specific upstream release
```

After committing any change to the fork, re-point the tag or the next update will
re-apply stale work:

```powershell
git tag -f alchemy HEAD
```

## Custom features

- **Alchemy Book mirror** (`src/haven/automated/alchemy/`) — passively reads the
  in-game Alchemy Book via reflection on login and uploads ingredient discoveries and
  elixir crafts to the mapper server. One integration hook: a single line in
  `GameUI.tick`.
- **Reflective contract tools** (`tools/extract-alchbook.py`,
  `tools/check-alchbook-contract.sh`) — the alchemy code reflects into classes the
  game server ships, which the compiler cannot check. If the book ever reports empty
  after a game update, run these (from WSL) to diagnose drift. Silence, not
  exceptions, is the failure mode.
- **Nurgling Imports** (`src/haven/automated/lp/`, menu grid: Custom Client Extras →
  Nurgling Imports) — the LP-assistant feature set ported from nurgling2: discovery
  markers (world + minimap, right-clickable) for undiscovered LP products, always-on
  harvest overlays, a never-crafted highlight in the crafting menu, and an Auto-LP bot
  that walks to and collects nearby undiscovered products. Configure via the "LP
  Assistant Manager" button; toggle quickly via "Toggle LP Assistant".
  `tools/gen-lpspec.py` / `tools/gen-menugrid-res.py` regenerate the underlying data
  and menu resources — see the comments in each for when to rerun them.

## Remotes

- `origin` — this repo (our fork).
- `upstream` — `https://github.com/Nightdawg/Hurricane.git` (releases are fetched
  shallowly, on demand, by the update script).

## Building & playing

Requires JDK 17–21 and Apache Ant (see "Getting started" above). `.\update-and-play.ps1`
resolves both, builds with Ant, and launches via `bin\Play.bat`. See Hurricane's own
docs for base-client details.
