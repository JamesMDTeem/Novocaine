# Pathing, Gates, and Navigation System Analysis

## Executive Summary

After reviewing `Router.java`, `BotNav.java`, `Gates.java`, `Map.java`, and `AStar.java`, I've identified **critical flaws** that explain why "pathing still tries to cut through walls" and "Router might be getting bypassed." The fixes in recent commits address symptoms but miss root causes.

---

## Critical Findings

### 1. Router Bypass Is Real and Systematic

**Location:** `BotNav.travelTo()` lines 801-841

```java
// Stage 0-11: NO ROUTE AT ALL - straight line hops
for (int stage = 0; (stage < MAX_STAGES) && abort.running(); stage++) {
    Coord2d at = me();
    if ((at == null) || (at.dist(dest) <= HOP)
        || (WorldAnchor.capture(gui, dest) != null))
        break;
    Coord2d far = at.add(dest.sub(at).div(at.dist(dest)).mul(HOP));
    if (!walkStraight(far, LEG_TOL))
        break;
    Barriers.learn(gui);
}

// ONLY HERE does plan() get called
List<Coord2d> route = plan(dest);
```

**The Problem:** When `WorldAnchor.capture(gui, dest)` returns `null` (destination beyond map file coverage, or different segment), the code executes **12 stages of blind straight-line hops** (`MAX_STAGES = 12`, each up to 36 tiles = **432 tiles of unchecked movement**) before *ever* calling `plan()`.

**Evidence from logs** (BotNav.java:817-822): "Twenty-eight journeys in one log ran with no route at all... replayed against botmap.json, the 176-tile leg at 10:30 crosses EIGHTEEN remembered WALL tiles and twenty-three solid ones."

**This IS the "router bypass" - it's not a bug, it's the designed fallback that runs for 12 hops.**

---

### 2. Wall Check in `walkStraight` Is Incomplete

**Location:** `BotNav.walkStraight()` lines 1627-1636

```java
double open = clearSpan(me.rc, dir, span);
if (open < span) {
    if (open < MCache.tilesz.x) {
        cancelWalk();
        return false;
    }
    span = open;
}
```

**The Problem:** `clearSpan()` only checks `Observed.WALL` tiles that the **client cannot see** (line 1266: `Observed.at(here.seg, p.add(off).floor(MCache.tilesz)) != Observed.WALL || occupied(gui, p)`).

- It **exempts gateway tiles** (lines 1254-1258) with a 2-tile slack
- It **only checks Observed.WALL** - not `Observed.SOLID`, not deep water, not cliffs
- A palisade recorded as `Observed.SOLID` (not WALL) is **invisible** to this check

**Evidence:** Router.java:483 comment: "Testing only SOLID here made every palisade in the world invisible to the router... What it produced was a route straight through the south wall."

---

### 3. Gate Passability Uses Stale Data

**Location:** `Router.World.passable()` lines 483-495

```java
if ((s == Observed.SOLID) || (s == Observed.WALL)) {
    if (gates.contains(t))
        return true;  // Gate gob standing here = passable
    return false;
}
if (s == Observed.GATE)
    return true;
```

**The Problem:** 
- `gateTiles()` (lines 427-439) captures **loaded** gate gobs ONCE at World construction
- Gates can open/close between searches - the set is **stale immediately**
- A gate that was closed when captured is treated as passable; one that opened after is treated as blocked
- The router has **no way to know current gate state** during search

---

### 4. "Hop and Re-plan" Doesn't Check Walls Before Hopping

**Location:** `BotNav.travelTo()` lines 836-839

```java
NLog.log(log, "cannot plan to " + Gates.fmt(dest) + " from here - walking one hop"
    + " towards it, to " + Gates.fmt(far) + ", and asking again from there");
if (!walkStraight(far, LEG_TOL))
    break;
```

**The Problem:** The hop destination `far` is computed as a straight line toward destination. `walkStraight` **does** check walls via `clearSpan`, but:
- The check only runs *during* the hop, not *before* committing to the hop direction
- If the straight line to `far` crosses a wall, the bot walks into it until `clearSpan` stops it
- Then the leg fails, re-plan happens, but **the same wall is still there**

---

### 5. Corner Post Forgiveness Is Still Too Broad

**Location:** `BotNav.clearSpan()` lines 1248-1270

```java
// Forgive walls only where the gateway actually IS
if ((gateFrom >= 0) && (d >= (gateFrom - slack)) && (d <= (gateTo + slack)))
    continue;   // the gateway's own posts, which we are entitled to pass between
```

**The Problem:** `slack = 2 tiles` (line 1260). A gateway is 3 tiles wide. With 2-tile slack on each side, **7 tiles are forgiven** - the gateway plus 2 tiles on each side. A corner post 3 tiles from the gateway is **still forgiven**.

**Evidence from Gates.java:103-108:** "WIDE_CORRIDOR = TILE * 15.0... With no corridor at all... a gate forty tiles to the side of the route passes both comfortably."

---

### 6. Local Pathfinder and Router Coordination Is Broken

**The Architecture Problem:**

| Layer | Vision | Plans | Re-checks |
|-------|--------|-------|-----------|
| Router | Entire map file | Full route (waypoints) | Never - assumes route is valid |
| Local PF | 88 tiles | One leg at a time | Every hop |

**The Gap:** Router certifies lines between waypoints over **observed** tiles. Local PF re-checks only **loaded** objects within 44 tiles. When a leg is longer than 44 tiles, the middle is **never checked by either**.

**Code Evidence:** Router.java:46-47: "the local pathfinder re-checks it on arrival" - but arrival is at the waypoint, not along the leg.

---

### 7. AStar compareTo Fix Is Correct But Incomplete

**Location:** `AStar.java` lines 117-131

```java
@Override
public int compareTo(Node n) {
    if (this == n)
        return 0;

    int diff = Double.compare(f(), n.f());
    if (diff == 0)
        diff = Double.compare(h, n.h);
    if (diff == 0)
        diff = Integer.compare(order, n.order);
    return diff;
}
```

**The Fix:** Pure function, no mutation - correct. Uses `order` for tie-breaking - correct.

**Remaining Issue:** The heuristic `h` is Euclidean distance (line 89), but the graph edges use Euclidean weights. This is **admissible but not consistent** for a visibility graph - the heuristic can overestimate when the direct line is blocked but a detour exists. Not a bug, but suboptimal.

---

## Better Architecture Proposal

### Core Principle: **Hierarchical Pathfinding with Contracts**

```
┌─────────────────────────────────────────────────────────────┐
│                    TRAVEL MANAGER                            │
│  (owns journey budget, coordinates layers, handles failures) │
└─────────────────────────────────────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
┌─────────────────┐ ┌───────────────┐ ┌──────────────────┐
│  GLOBAL ROUTER  │ │  LOCAL PF     │ │  GATE MANAGER    │
│  (tile A* over  │ │  (visibility  │ │  (state machine  │
│   Observed +    │ │   graph A*)   │ │   for each gate) │
│   Terrain)      │ │               │ │                  │
└─────────────────┘ └───────────────┘ └──────────────────┘
          │                │                │
          └────────────────┼────────────────┘
                           ▼
              ┌─────────────────────────┐
              │   SHARED WORLD MODEL    │
              │  (single source of truth │
              │   for obstacles, gates,  │
              │   hazards, terrain)      │
              └─────────────────────────┘
```

### Key Changes:

1. **Single World Model** - Both Router and Local PF read from same `Observed` + `Terrain` + live gate states
2. **Router Plans Corridors, Not Lines** - Output is a sequence of *corridors* (tile ranges), not waypoints
3. **Local PF Is Constraint Solver** - Given a corridor, find path within it; if impossible, report *which constraint failed*
4. **Gate Manager Owns Gate State** - Single authority on gate open/closed/locked, with callbacks
5. **Explicit Failure Contracts** - Each layer returns structured failure reason, not just boolean

---

## Better Algorithms

### 1. Router: Anytime Repairing A* (ARA*) or LPA*

**Current:** Single-shot A* from scratch every re-plan
**Better:** Incremental search that reuses previous search tree

```java
// Instead of Router.route() returning List<Coord>
// Return a Route object with:
public interface Route {
    List<Corridor> corridors();  // Each corridor = tile range + constraints
    boolean isValid(WorldModel world);  // Can re-validate incrementally
    Route repair(WorldModel world, Coord from);  // Incremental repair
}
```

**Why:** Re-planning from scratch on every hop (BotNav line 1063) throws away all previous computation. LPA* reuses `g` values and only updates changed nodes.

### 2. Local PF: Visibility Graph + Constraint-Based Search

**Current:** Builds full visibility graph per search, A* over vertices
**Better:** Pre-compute visibility graph for loaded area; search with dynamic constraints

```java
// Map.main() currently rebuilds everything
// Better: 
public class LocalPathfinder {
    private VisibilityGraph graph;  // Built once per loaded area
    private Set<Constraint> activeConstraints;  // Gates, keepouts, hazards
    
    public Path findPath(Coord from, Coord to, Corridor corridor) {
        // Only consider vertices inside corridor
        // Constraints applied as edge filters
    }
}
```

### 3. Gate Handling: State Machine with Explicit Transitions

**Current:** Ad-hoc logic scattered across `Gates.pass()`, `blocking()`, `onRoute()`, `towards()`
**Better:** Single gate state machine per gate

```java
public enum GateState {
    UNKNOWN,      // Not loaded yet
    OPEN,         // Confirmed open
    CLOSED,       // Confirmed shut
    LOCKED,       // Tried to open, failed
    OPENING,      // Clicked, waiting for swing
    CLOSING       // Clicked, waiting for swing
}

public class GateManager {
    private Map<Long, GateState> gateStates = new ConcurrentHashMap<>();
    
    public GateAction decide(GateContext ctx) {
        // Single decision point with full context
    }
}
```

---

## Better Data Flow

### Current (Broken):
```
BotNav.travelTo() 
  → Router.route() [reads Observed + Terrain + stale gateTiles()]
  → BotNav.itinerary() [converts to world coords]
  → BotNav.walkStraight() [checks clearSpan against Observed.WALL only]
  → Map.main() [reads loaded gobs, builds visibility graph]
  → Gates.blocking()/onRoute() [reads loaded gates, checks geometry]
```

### Proposed (Unified):
```
TravelManager.executeJourney(dest)
  → WorldModel.getCurrentState()  // Single snapshot: Observed + Terrain + GateStates + Hazards
  → GlobalRouter.planCorridors(worldModel, from, dest)  // Returns Corridor[]
  → for each corridor:
       LocalPF.findPathInCorridor(worldModel, corridor)  // Returns Path or FailureReason
       if FailureReason.GATE_BLOCKS:
           GateManager.handleBlockedGate(gate, corridor)
           continue  // Re-plan from new position
       if FailureReason.WALL_BLOCKS:
           WorldModel.learnWall(failureLocation)
           GlobalRouter.repairRoute(worldModel, currentPos)
           continue
```

---

## Better Failure Handling

### Current Failure Modes (and why they're wrong):

| Situation | Current Behavior | Why It's Wrong |
|-----------|------------------|----------------|
| Router can't place destination | 12 blind hops (432 tiles) | Walks through walls |
| Local PF refuses click | `stepRefused` → `Walk.lineClear` → server walk | Server walks through water/walls |
| Leg stops short of waypoint | `driftedIntoWall` check → re-plan | Re-plan returns identical route |
| Gate shut on route | `Gates.pass()` → open → step through | May open wrong gate (air lock confusion) |
| No route found after re-plans | `arrived()` with wall check | Distance-only = false arrival |

### Proposed Failure Contracts:

```java
public sealed interface TravelResult permits Arrived, Failed, Blocked, NeedsReplan {
    // Success
    record Arrived(Coord position) implements TravelResult {}
    
    // Hard failure - give up on this destination
    record Failed(String reason, Coord lastPosition) implements TravelResult {}
    
    // Recoverable - something specific blocks us
    record Blocked(Blocker blocker, Coord position) implements TravelResult {}
    
    // Route invalid - need new plan from current position
    record NeedsReplan(InvalidationReason reason, Coord position) implements TravelResult {}
}

public sealed interface Blocker permits GateBlocker, WallBlocker, HazardBlocker, WaterBlocker {
    record GateBlocker(long gateId, GateState state) implements Blocker {}
    record WallBlocker(Coord wallTile, boolean isGateway) implements Blocker {}
    record HazardBlocker(Gob hazard, double radius) implements Blocker {}
    record WaterBlocker(Coord waterTile, boolean deep) implements Blocker {}
}

public enum InvalidationReason {
    WALL_LEARNED,      // New wall discovered on route
    GATE_STATE_CHANGED, // Gate opened/closed/locked
    HAZARD_MOVED,      // Beast moved onto route
    CORRIDOR_EXITED    // Drifted out of planned corridor
}
```

---

## Concrete Code Improvements

### 1. Fix Router Bypass - Replace Blind Hops with Corridor Walking

**File:** `BotNav.java`, replace `travelTo` lines 801-841

```java
// NEW: Walk toward destination using router corridors, not blind hops
public boolean travelTo(Coord2d dest, double tol) throws InterruptedException {
    Barriers.learn(gui);
    refusedGates.clear();
    
    // Try to get a route - if WorldAnchor fails, use LOCAL router fallback
    List<Coord2d> route = plan(dest);
    
    if (route == null) {
        // No global route - use LOCAL corridor walking instead of blind hops
        return travelByLocalCorridors(dest, tol);
    }
    
    // ... rest of existing logic with corridor validation
}

private boolean travelByLocalCorridors(Coord2d dest, double tol) throws InterruptedException {
    // Use local pathfinder's visibility graph to walk in corridors
    // Each hop plans to edge of known terrain, learns, re-plans
    // NEVER walks straight line beyond loaded objects
    Coord2d current = me();
    int hops = 0;
    final int MAX_LOCAL_HOPS = 20;
    
    while (current != null && current.dist(dest) > tol && hops < MAX_LOCAL_HOPS) {
        double reach = hop();  // Distance to furthest loaded object
        Coord2d aim = current.add(dest.sub(current).div(current.dist(dest)).mul(reach));
        
        // Check corridor is clear BEFORE walking
        if (!corridorClear(current, aim)) {
            // Hit unknown wall - learn it, re-plan
            Barriers.learn(gui);
            List<Coord2d> newRoute = plan(dest);
            if (newRoute != null) return travelTo(dest, tol);  // Now we have a route
            // Still no route - try different direction (wall follow)
            aim = wallFollow(current, aim, dest);
        }
        
        if (!walkStraight(aim, Math.min(LEG_TOL, reach * 0.5)))
            return false;
            
        current = me();
        hops++;
        Barriers.learn(gui);
    }
    return arrived(dest, tol);
}
```

### 2. Fix Wall Check - Check ALL Impassable Types

**File:** `BotNav.java`, `clearSpan` method lines 1232-1274

```java
private double clearSpan(Coord2d from, Coord2d dir, double span) {
    WorldAnchor here = WorldAnchor.capturePlayer(gui);
    Gob me = player();
    if (here == null || me == null) return span;
    
    Coord2d off = here.sc.sub(me.rc);
    int steps = Math.max(1, (int) Math.ceil((span / MCache.tilesz.x) * 2));
    
    // Find gateway range on this line (for post forgiveness)
    double gateFrom = -1, gateTo = -1;
    for (int i = 0; i <= steps; i++) {
        double d = (span * i) / steps;
        Coord t = from.add(dir.mul(d)).add(off).floor(MCache.tilesz);
        if (Observed.gate(here.seg, t)) {
            if (gateFrom < 0) gateFrom = d;
            gateTo = d;
        }
    }
    
    double postSlack = MCache.tilesz.x;  // REDUCED from 2 tiles to 1 tile
    
    for (int i = 1; i <= steps; i++) {
        double d = (span * i) / steps;
        Coord2d p = from.add(dir.mul(d));
        Coord tile = p.add(off).floor(MCache.tilesz);
        
        // Check ALL blocking types, not just WALL
        byte obs = Observed.at(here.seg, tile);
        boolean isBlocking = (obs == Observed.WALL) 
                          || (obs == Observed.SOLID)
                          || (obs == Observed.CLIFF);  // If exists
        
        // Check water via Terrain
        int water = Terrain.classAt(gui, here.seg, tile);
        if (water == Terrain.DEEP || (water == Terrain.SHALLOW && Map.BLOCK_WATER))
            isBlocking = true;
        
        if (!isBlocking) continue;
        
        // Gateway post forgiveness - ONLY for actual gateway tiles ± 1 tile
        if (gateFrom >= 0 && d >= (gateFrom - postSlack) && d <= (gateTo + postSlack))
            continue;
        
        // Visible objects are handled by local PF - only block on UNKNOWN walls
        if (BotNav.occupied(gui, p))
            continue;
            
        // Stop 1 tile before the wall
        return Math.max(0, d - MCache.tilesz.x);
    }
    return span;
}
```

### 3. Fix Gate Passability - Query Live State in Router

**File:** `Router.java`, `World` class

```java
// Add method to query current gate state
private boolean isGatePassableNow(Coord tile) {
    // Check if any loaded gate gob stands on this tile AND is open
    for (Gob g : Gates.loaded(gui)) {
        if (g.rc.add(off).floor(MCache.tilesz).equals(tile)) {
            return Gates.isOpen(g);  // Live state!
        }
    }
    // Fall back to observed record
    return Observed.gate(seg, tile);
}

// In passable():
if ((s == Observed.SOLID) || (s == Observed.WALL)) {
    if (isGatePassableNow(t))  // Uses LIVE state
        return true;
    return false;
}
if (s == Observed.GATE)
    return isGatePassableNow(t);  // Verify it's actually open
```

### 4. Fix Gate Selection - Single Decision Point

**File:** `Gates.java`, replace `pick()`, `blocking()`, `onRoute()`, `towards()` with unified logic

```java
public class GateManager {
    private final GameUI gui;
    private final Map<Long, GateState> gateStates = new ConcurrentHashMap<>();
    
    public enum GateAction {
        NONE,
        OPEN_AND_CROSS(long gateId, Coord2d approachPoint, Coord2d crossPoint),
        WAIT_FOR_OPEN(long gateId),
        DETOUR_AROUND(long gateId)  // Gate locked, find alternative
    }
    
    public GateAction decide(Coord2d from, Coord2d to, Coord2d nextWaypoint) {
        // 1. Check if route crosses ANY gate tile (from Router's corridor)
        List<Long> gatesOnRoute = findGatesOnLine(from, nextWaypoint);
        
        // 2. For each gate on route, get LIVE state
        for (Long gateId : gatesOnRoute) {
            Gob gate = nav.gob(gateId);
            if (gate == null) continue;  // Not loaded yet
            
            GateState state = getCurrentState(gate);
            if (state == GateState.OPEN) {
                // Gate is open - just cross it
                return GateAction.OPEN_AND_CROSS(gateId, 
                    squareApproach(gate, from), 
                    beyond(gate, from, nextWaypoint));
            }
            if (state == GateState.CLOSED) {
                // Gate is shut - need to open it
                return GateAction.OPEN_AND_CROSS(gateId,
                    squareApproach(gate, from),
                    beyond(gate, from, nextWaypoint));
            }
            if (state == GateState.LOCKED) {
                // Can't use this gate - need detour
                return GateAction.DETOUR_AROUND(gateId);
            }
        }
        
        // 3. No gate on route - but leg failed, so maybe gate NEAR route?
        if (legFailed) {
            Gob nearby = findNearbyShutGate(from, to);
            if (nearby != null) return GateAction.OPEN_AND_CROSS(...);
        }
        
        return GateAction.NONE;
    }
}
```

### 5. Fix Drift Handling - Corridor-Based, Not Waypoint-Based

**File:** `BotNav.java`, replace `driftedIntoWall` and `restIsWalkable`

```java
// Instead of checking waypoint-to-waypoint lines
// Check: am I still inside the PLANNED CORRIDOR?

private boolean inCorridor(Coord2d position, Corridor corridor) {
    // Corridor = center line + half-width
    // Check distance from position to corridor center line
    double distToCenter = distanceToLine(position, corridor.start, corridor.end);
    return distToCenter <= corridor.halfWidth;
}

private boolean corridorClear(Coord2d from, Coord2d to) {
    // Check the corridor (not just center line) for walls
    // Uses Router's clear() but with character width
    return Router.walkableCorridor(gui, seg, from, to, CHARACTER_WIDTH);
}
```

---

## Implementation Priority

### Phase 1: Critical Fixes (Do First)
1. **Replace blind hops in `travelTo`** - The 432-tile wall-walking is the #1 cause of "paths through walls"
2. **Fix `clearSpan` to check SOLID + WALL + WATER** - Currently only checks WALL
3. **Reduce gateway post slack from 2 tiles to 1 tile** - Stops corner-post forgiveness

### Phase 2: Architecture Improvements
4. **Unified GateManager** - Single state machine replaces 4 picker methods
5. **Router returns Corridors, not Waypoints** - Enables corridor validation
6. **Live gate state in Router** - Query loaded gobs during search

### Phase 3: Algorithmic Upgrades
7. **Incremental Router (LPA*)** - Reuse search tree on re-plan
8. **Constraint-based Local PF** - Dynamic edge filtering instead of rebuild
9. **Structured Failure Contracts** - Replace boolean returns with typed results

---

## CS Patterns We Should Use Instead

| Current Approach | Better Pattern | Source |
|------------------|----------------|--------|
| Re-plan from scratch | **LPA* / D* Lite** (Incremental A*) | Koenig & Likhachev, 2002 |
| Single heuristic A* | **Anytime Repairing A* (ARA*)** | Likhachev et al., 2003 |
| Visibility graph rebuild | **Lazy Visibility Graph** | Cui et al., 2017 |
| Ad-hoc gate logic | **Hierarchical Task Network (HTN)** | Erol et al., 1994 |
| Boolean failure returns | **Result/Outcome Types** (Rust-style) | Functional programming |
| Global mutable state | **Actor Model / ECS** | Game architecture patterns |

---

## Files to Modify (Priority Order)

1. `BotNav.java` - `travelTo()` blind hops, `clearSpan()` wall check, `driftedIntoWall()` corridor check
2. `Router.java` - `World.passable()` live gate state, add `walkableCorridor()`
3. `Gates.java` - Replace 4 pickers with `GateManager` class
4. `Map.java` - Separate graph build from search; add constraint filtering
5. `AStar.java` - Add incremental search support (LPA*)

---

## Testing Strategy

1. **Replay logged failures** against `botmap.json` - verify no wall crossings
2. **Unit test `clearSpan`** with various wall/gate configurations
3. **Integration test** gate state transitions (open→shut→locked→open)
4. **Stress test** long journeys (500+ tiles) with dynamic obstacles
5. **Regression test** air lock traversal (inner/outer gate confusion)

---

## Implementation Status (uncommitted, working tree)

The targeted fixes below are in the working tree (not yet committed). The big refactor
(section "Proposed Architecture") is **not** being done - the committed history took the
targeted-fix route instead, and these fixes extend it.

**Already in the working tree:**

- `MapView.java`: new `pfrefusal` diagnostic field, set on every path that starts no search
  ("no player", "destination outside the search window", "threw ..."). The out-of-window bounds
  check now runs BEFORE the running search is cancelled, so a refused click no longer dead-stops
  a bot that was mid-walk. `pfRightClick` far targets aim at the hitbox EDGE (stepping back by the
  max hitbox radius) instead of the centre, which the pathfinder refuses as a vertex inside a box.
  `pfLeftClick`/`pfLeftClickGob` clamp far targets to 40 tiles (440 px), inside the 88-tile window.
- `Pathfinder.java`: `terminate` and `moveinterupted` are now `volatile` - the search thread
  never blocks, so a non-volatile flag could be hoisted out of the A* loop and a cancelled search
  could publish over the next hop's state.
- `BotNav.java`: `lastKeepouts` + `ringedOff()` so a bear's keep-out ring is never filed as
  blocked ground (`learnRefusal` skips it). `blockedThere()` now also consults `Observed.solid`,
  so a remembered wall (scrolled out of view) blocks an aim instead of being walked into.
  `clearSpan` blocks on SOLID and water, and gateway-post forgiveness narrowed from 2 tiles to 1.
  Keep-outs are kept while a live search reads them.

**Added on top (this session, after re-verification):**

- `stepTo` no longer direct-walks on a click that never started a search (throwaway) - that was the
  give-up loop in the logs (clear line, seven hops, no progress). It fails the leg cleanly instead,
  and the stale live search is terminated so it cannot keep clicking at its old target while the
  caller re-plans. Direct-walk is also suppressed when `ringedOff()` explains the refusal.
  Genuine search refusals (NO_ROUTE/STUCK with a line the walker proves clear) still direct-walk.
- `hopToward` aim clamped to `min(len*0.9, ...)` so a destination closer than `HOP_MIN` (12 tiles)
  can no longer be overshot by up to 11 tiles (the "walks into the wall just past the destination"
  shape).

**Corrected during re-verification:**

- The earlier concern that `clearSpan`'s water rule was "inconsistent with the router" is NOT real.
  `Terrain.ground()` and the pathfinder `Map` both block DEEP always and SHALLOW only when
  `BLOCK_WATER`; `clearSpan` now matches both exactly.
- `Terrain.classes()` is cached per segment-grid, so the per-sample water lookup in `clearSpan`
  is a hash lookup after the first sample in a grid - no per-sample map-file read, no hoisting needed.

**Open items from the analysis still not addressed:** Router/WORLD layer split, LPA*, typed failure
results, the `GateManager` consolidation. See "Proposed Architecture".

---

*Analysis based on code review of commits: ad2d4bea2, 722efd2b9, 230649500, f4fbf289e, d8ea29f1c*