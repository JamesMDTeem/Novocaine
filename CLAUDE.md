# Novocaine

A deliberately tiny fork of the **Hurricane** Haven & Hearth game client (Java, Ant,
JDK 21), plus a ported LP-assistant feature set. Published as
`github.com/JamesMDTeem/Novocaine`.

> **This repository is PUBLIC.** Anything committed here is published. Never put the
> mapper server's tokens, `.env` values, admin password, or `/client/<token>/` URLs in
> this repo — not in code, comments, notes, or release text.

## Layout

- `src/` — Java source. The fork's own code is the alchemy package plus one line in
  `GameUI.tick`.
- `bin/` — **the live game install**, not build output. Holds the runtime jars,
  resources, `manifest.json`, and `alchemy-book-dump.json` (the Alchemy Book extract
  worth inspecting when debugging alchemy). Do not treat as disposable.
- `build/classes/`, `build/classes-lib/` — Ant compile output. Disposable, and blocked
  from reading by a deny rule.
- `lib/`, `dist/`, `Release/`, `steam/` — vendored jars and release artifacts.
- `update-and-play.ps1` — the update/rebuild/launch script. **Protected by a deny rule**;
  it is not edited automatically.

## Build

JDK 21. Ant is at `C:\ant\apache-ant-1.10.17\bin\ant.bat`, **not on PATH**, and must be
run **from PowerShell** — the Bash `ant` wrapper fails here (POSIX classpath handed to a
Windows JVM). Useful targets from `build.xml`: `jars`, `bin`, `deftgt`, `run`.

The normal path is not a bare `ant` call but `update-and-play.ps1`, which does its own Ant
and JDK discovery:

```
.\update-and-play.ps1 [-Tag <release>] [-SkipUpdate] [-NoLaunch]
```

Upstream releases come from `Nightdawg/Hurricane`; with no `-Tag` it takes the newest.
Use `-SkipUpdate` to rebuild without moving the baseline, `-NoLaunch` to build without
starting the game. See the `/novo-build` skill rather than re-deriving this.

## The fork is a patch, not a branch

The fork is kept as a patch between two tags (`vendor-baseline..alchemy`) rather than a
long-lived branch. Updating means: check out the upstream release, record it as a source
baseline commit on `master`, re-apply the patch, re-tag, push, rebuild. Nothing needs
hand-merging unless upstream touches the same line of `GameUI.tick`, which the update
script reports rather than guessing at.

Because upstream tracks the same jars and resources this install holds as untracked
files, checking out a release **must** overwrite them. Local databases are backed up
first — they accumulate state that is in no release.

## Conventions

- No AI attribution in commits or release notes (user-level rule).
- Java conventions: see `.claude/rules/java-client.md`.
