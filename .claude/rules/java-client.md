---
paths:
  - "src/**/*.java"
  - "build.xml"
---

# Java client conventions

This tree is mostly **vendored upstream Hurricane code** with a growing amount of
fork-authored code. Which one you're in changes the rules.

## Indentation follows the file, not a global style

- **Fork-authored** — everything under `src/haven/automated/**`: the `alchemy/`,
  `cookbook/`, `helpers/`, `lp/`, `mapper/`, `nbots/` and `pathfinder/` packages.
  **4 spaces**, no tabs. Verified across `LpPlanner`, `LpSpec`, `AlchemyService`,
  `pathfinder/Map`, and the whole of `nbots/`.
- **Vendored upstream** (everything else under `src/haven/`, `src/dolda/`, `src/com/`,
  `src/org/`): **tabs**. Match the file you are editing.

Never reformat a vendored file, and check that an edit has not changed a file's line
endings. The hazard is not hypothetical: `src/haven/GameUI.java` currently differs from
`upstream-v1.67` by 6644 lines, which collapses to **63 insertions and 3 deletions**
under `--ignore-all-space` — the rest is a CRLF/LF mismatch. A diff in that state hides
the real fork edit completely. `git diff --ignore-all-space` is the way to see what the
fork actually changed in a vendored file.

Two fork-authored files are deliberately **CRLF**: `src/haven/automated/lp/AutoLpBot.java`
and `src/haven/automated/lp/LpSpec.java`. After editing either, confirm the endings
survived — `grep -cUvP '\r' <file>` must report 0.

The fork is maintained as a patch between the `vendor-baseline` and `alchemy` tags, so
gratuitous whitespace changes in upstream files turn a clean re-apply into a hand-merge on
the next update.

## Keep the fork's footprint minimal

The whole update strategy depends on the fork touching upstream as little as possible.
New behaviour belongs in a new class under `src/haven/automated/`, wired in with the
smallest possible edit to the upstream file. Prefer adding a call over restructuring
upstream logic.

The fork currently reaches into 13 vendored `src/haven/*.java` files — `Astronomy`,
`Client`, `GItem`, `GameUI`, `Gob`, `GobLpDiscoveryInfo`, `MCache`, `Makewindow`,
`MapView`, `MapWnd`, `MenuGrid`, `MiniMap`, `OptWnd`. That is the budget, not a target to
grow; adding a 14th wants a reason.

## Style in fork-authored code

Match what's already in `haven/automated/lp/` and `haven/automated/nbots/`:

- Class-level Javadoc explaining *why* the class is split out the way it is, not just
  what it does.
- Inline comments that record the reasoning behind a non-obvious rule (e.g. why a task
  with an unequippable tool is skipped rather than attempted), and — in `nbots` — what
  the observed misbehaviour was that the rule exists to prevent.
- `LpAlias`-style named constants for item-name lists rather than inline string arrays.

A comment in this tree is not evidence of what the code does. Several `nbots` comments
describe an intent the code next to them does not implement. When a comment and the code
disagree, trust the code and treat the gap as a bug worth reporting.
