# Novocaine

A custom Haven & Hearth client: a live pull of
[Nightdawg/Hurricane](https://github.com/Nightdawg/Hurricane) with our own features
layered on top. All credit for the base client goes to Nightdawg and the Hurricane
project (and Loftar's Vanilla client under that).

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

## Remotes

- `origin` — this repo (our fork).
- `upstream` — `https://github.com/Nightdawg/Hurricane.git` (releases are fetched
  shallowly, on demand, by the update script).

## Building & playing

Requires JDK 21 and Apache Ant. `.\update-and-play.ps1` resolves both, builds with
Ant, and launches via `bin\Play.bat`. See Hurricane's own docs for base-client
details.
