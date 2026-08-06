---
title: Pathfinding
aliases: [Pathfinding, Pathfinder, Router, A-star, AStar, Navigation]
tags: [subsystem, automation, navigation]
---

# Pathfinding / Navigation

Navigation is now **two layers**, and knowing which one you are in is the whole game:

1. **The route layer — `haven.automated.nbots.world`** (`Router`, `GridAStar`). Plans a *strategic*
   route across a whole map segment, over ground the character has actually seen, and hands back a
   short list of waypoints. This is what stops a bot pacing back and forth against a lake shore or a
   palisade: the local pathfinder only ever sees an ~88-tile window, so it answers the wrong
   question over long distances.
2. **The travel layer — `BotNav` (same package)**. Walks the waypoint list leg by leg. Each leg is a
   straight line it asks the **legacy client pathfinder** to walk (`gui.map.pfthread`, a
   `haven.automated.pathfinder.Pathfinder`), reacting to its typed `Pathfinder.Refusal`, crossing
   gates through `GateManager`, and learning from refusals (`Refused`) when the client and our own
   record disagree.

The **legacy `haven.automated.pathfinder`** package (below) still exists and is still used — as the
*local* per-hop pathfinder the travel layer drives, and directly for "go to and click this object."
It is no longer the strategic planner.

## The route layer — `Router` + `GridAStar`

Source: `src/haven/automated/nbots/world/Router.java`, `src/haven/automated/pathfinder/GridAStar.java`.

- **Tile resolution, deliberately.** An earlier version searched over 4-tile blocks; every serious
  routing bug came from it, because a palisade is one tile thick and a gateway is a one-tile gap in
  it — neither survives a coarse grid. At tile resolution a wall is a line of blocked tiles and A*
  walks through the gap. See the long rationale at the top of `Router.java`.
- **`Router.World`** is the walkability map A* reads: passability from [[Automation-Bots#Subpackages|`Observed`]]
  (`SOLID`/`WALL`/`GATE`/`UNSEEN`, gates re-opened by the live gob list) and water from `Terrain`.
  Cost model: seen ground = 1, never-seen ground = `UNKNOWN` (3, passable but expensive — a route is
  a hypothesis the local pathfinder rechecks on arrival), a client-refused tile = `REFUSED_COST`
  (200, a strong detour but never impassable, so two bad inferences can't seal a bot in).
- **`GridAStar.search`** is a **stateless** full A* with an octile heuristic. It returns the **raw**
  tile path; it does *no* waypoint thinning of its own.
  > [!note] This was an LPA* (incremental) search until it was removed. The incremental cache never
  > actually helped — the router re-plans from the player's *current* tile, so the start moved every
  > call and forced a full search anyway — and it was a single `static` instance mutated from both a
  > bot thread and the UI thread, a data race. A full search over a few hundred tiles takes a few
  > milliseconds; there was nothing to save.
- **`Router.simplify` is the single waypoint-thinning authority.** It keeps a waypoint only where the
  straight line from the last kept one stops being clear, checked at **quarter-tile** resolution
  against the character's **half-width** (`HALFWIDTH = 3/11`) — the corner-safety the tile-resolution
  redesign exists to guarantee. Nothing else may thin a path, or a coarser pass drops the very corner
  waypoints this check keeps.
- **Entry points:** `route` (UNSEEN passable), `routeClamped` (UNSEEN impassable; returns the best
  path to the reachable tile nearest the goal), `reachable`/`answerable` (a *final* yes/no for
  callers that choose targets, e.g. the LP assistant), `walkable` (is this straight line clear now).
- `updateTile`/`updateTiles`/`invalidateCache` are **no-ops today**, kept because their callers
  (`Refused`, `Observed`) still correctly express "the world changed"; a stateless search just reads
  the change on its next run.

## The travel layer — `BotNav`

`src/haven/automated/nbots/world/BotNav.java` walks a route to completion: convert waypoints to world
coordinates, walk each leg straight, and when the local pathfinder refuses a hop, decide whether the
refusal means anything (a shut gate, a keep-out ring around a monster, a stockpile the map didn't
record) before falling back to a direct server walk or re-planning. Gates are opened/crossed via
`GateManager`; refusals that contradict our own record are learned via `Refused`. Most of the file is
comments recording the exact misbehaviour each rule prevents — read them before touching it.

## The legacy pathfinder — `haven.automated.pathfinder`

A from-scratch A* over a per-journey **visibility graph** (not a tile grid), driven on its own thread.
Still the *local* pathfinder (`gui.map.pfthread`) the travel layer hands each leg to, and the
"walk to and click this object" path.

| File | Role |
|---|---|
| `Pathfinder.java` | Orchestrator, `implements Runnable`. Computes a path and walks it; exposes typed `Pathfinder.Refusal` (`NO_ROUTE`, `STUCK`, …). |
| `AStar.java` | A* over the `Vertex`/`Edge` visibility graph. |
| `Map.java` | Builds the graph for one `src → dest` from `MCache` + obstacle collision boxes. |
| `GridAStar.java` | Stateless tile-grid A* for the **route layer** (see above) — *not* part of the legacy graph search. |
| `Vertex.java`, `Edge.java` | Graph primitives. |
| `TraversableObstacle.java` | Obstacle footprint to route around. |
| `PFListener.java` | `pfDone(Pathfinder)` completion callback. |
| `Utils.java`, `Dbg.java` | Helpers / debug drawing. |

- Obstacles come from `HitBoxes` (`hitboxes.db`), loaded at startup — see [[Resource-System]].
- Bots block on the local pathfinder with `AUtils.waitPf(gui)`; the route layer is called directly
  (`Router.route`/`routeClamped`/`reachable`).

## Diagnostics

The navigation stack logs to `bin/logs/` via [[Automation-Bots|`NLog`]]: `nbot-*.log` per bot,
`sight.log` (vision range), and `crash.log` for exceptions. "pathfinder refused … walking there
directly" is a **deliberate, deduplicated fallback**, not an error.

## Related
- [[Automation-Bots]] · [[Game-State-Model]] · [[Rendering-Pipeline]]

#subsystem #navigation
