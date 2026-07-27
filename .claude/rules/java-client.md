---
paths:
  - "src/**/*.java"
  - "build.xml"
---

# Java client conventions

This tree is mostly **vendored upstream Hurricane code** with a small amount of
fork-authored code. Which one you're in changes the rules.

## Indentation follows the file, not a global style

- **Fork-authored** (`src/haven/automated/**` — the `lp/` and `alchemy/` packages):
  **4 spaces**, no tabs. Verified across `LpPlanner`, `LpSpec`, `AlchemyService`,
  `pathfinder/Map`.
- **Vendored upstream** (everything else under `src/haven/`, `src/dolda/`, `src/com/`,
  `src/org/`): **tabs**. Match the file you are editing.

Never reformat a vendored file. The fork is maintained as a patch between the
`vendor-baseline` and `alchemy` tags, so gratuitous whitespace changes in upstream files
turn a clean re-apply into a hand-merge on the next update.

## Keep the fork's footprint minimal

The whole update strategy depends on the fork touching upstream as little as possible.
New behaviour belongs in a new class under `src/haven/automated/`, wired in with the
smallest possible edit to the upstream file — ideally one line, as with `GameUI.tick`.
Prefer adding a call over restructuring upstream logic.

## Style in fork-authored code

Match what's already in `haven/automated/lp/`:

- Class-level Javadoc explaining *why* the class is split out the way it is, not just
  what it does.
- Inline comments that record the reasoning behind a non-obvious rule (e.g. why a task
  with an unequippable tool is skipped rather than attempted).
- `LpAlias`-style named constants for item-name lists rather than inline string arrays.
