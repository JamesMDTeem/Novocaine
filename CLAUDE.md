# Novocaine

A fork of the **Hurricane** Haven & Hearth game client (Java, Ant, JDK 21), carrying an
alchemy integration, a ported LP-assistant feature set, and the `nbots` crew-bot
automation. Published as `github.com/JamesMDTeem/Novocaine`.

> **This repository is PUBLIC.** Anything committed here is published. Never put the
> mapper server's tokens, `.env` values, admin password, or `/client/<token>/` URLs in
> this repo — not in code, comments, notes, or release text.

## Layout

- `src/` — Java source. Fork-authored code lives under `src/haven/automated/**` in seven
  packages: `alchemy/`, `cookbook/`, `helpers/`, `lp/`, `mapper/`, `nbots/`,
  `pathfinder/`. It also reaches into 13 vendored `src/haven/*.java` files; see
  `.claude/rules/java-client.md` for the list and the conventions.
- `bin/` — **the live game install**, not build output. Holds the runtime jars,
  resources, `manifest.json`, `alchemy-book-dump.json` (the Alchemy Book extract worth
  inspecting when debugging alchemy), and the `nbots` runtime state: `botmap.json`
  (learned terrain), `botplaces.json` (drawn areas), `hitboxes.db`, and `logs/`. Do not
  treat as disposable — deleting it loses learned world state, not just a build.
- `build/classes/`, `build/classes-lib/` — Ant compile output. Disposable, and blocked
  from reading by a deny rule.
- `lib/`, `dist/`, `Release/`, `steam/` — vendored jars and release artifacts.
- `update-and-play.ps1` — the update/rebuild/launch script. **Protected by a deny rule**;
  it is not edited automatically.

## Build

JDK 21. Ant is at `C:\ant\apache-ant-1.10.17\bin\ant.bat`, **not on PATH**, and must be
run **from PowerShell** — the Bash `ant` wrapper fails here (POSIX classpath handed to a
Windows JVM). Ant is the only trustworthy typecheck in this repo; jdtls loses the source
root and reports bogus package errors across the whole tree.

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
hand-merging unless upstream touches the same lines the fork does, which the update
script reports rather than guessing at.

Because upstream tracks the same jars and resources this install holds as untracked
files, checking out a release **must** overwrite them. Local databases are backed up
first — they accumulate state that is in no release.

**The patch pathspec is `src tools res steam build.xml update-and-play.ps1 README.md`.**
Anything outside it is deleted by the baseline checkout and not restored by the re-apply.
That is how `CLAUDE.md` and `.claude/rules/java-client.md` disappeared during the v1.67
update — they were never in the patch. Either keep repo-config files inside the pathspec
or expect to restore them after every update.

## Conventions

- No AI attribution in commits or release notes (user-level rule).
- Java conventions, indentation, and the CRLF-sensitive files: see
  `.claude/rules/java-client.md`.
