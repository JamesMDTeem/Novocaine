---
title: Code Metrics (generated)
aliases: [Code Metrics, Hotspots, Complexity]
tags: [reference, generated]
---

# Code Metrics (generated)

> [!warning] Generated file — do not edit by hand.
> Regenerate with `java rag/DepGraph.java`. Reference detection is heuristic (whole-word
> simple-name matching); same-name types across packages are conflated. Full per-type fan-out is in
> `rag/import-graph.jsonl`.

**Totals:** 921 types · 205,604 source lines · 342 `// ND:` Hurricane markers · 138 TODO/FIXME/XXX/HACK.

See [[Package-Map]], [[Key-Classes]], [[Class-Index]].

## Most-referenced types (fan-in) — the load-bearing classes

How many files reference each type. High fan-in = change with extra care.

| Type | Referenced by (files) |
|---|---|
| `Coord` | 328 |
| `Utils` | 278 |
| `Resource` | 253 |
| `UI` | 178 |
| `Gob` | 174 |
| `Widget` | 165 |
| `GOut` | 161 |
| `Pipe` | 151 |
| `Loading` | 146 |
| `GameUI` | 129 |
| `Tex` | 123 |
| `Coord2d` | 102 |
| `FromResource` | 102 |
| `Text` | 100 |
| `Message` | 100 |
| `OptWnd` | 88 |
| `Indir` | 87 |
| `Coord3f` | 84 |
| `TexI` | 83 |
| `State` | 75 |
| `RenderTree` | 73 |
| `Area` | 71 |
| `Window` | 70 |
| `Expression` | 70 |
| `Render` | 66 |
| `PUtils` | 66 |
| `ItemInfo` | 58 |
| `Type` | 57 |
| `DataBuffer` | 56 |
| `Button` | 56 |
| `Sprite` | 54 |
| `VectorFormat` | 53 |
| `ShaderMacro` | 53 |
| `Config` | 53 |
| `NumberFormat` | 50 |

## Largest types by line count

| Type | Package | Lines |
|---|---|---|
| `OptWnd` | `haven` | 5668 |
| `MapView` | `haven` | 3417 |
| `GameUI` | `haven` | 3352 |
| `Utils` | `haven` | 2916 |
| `Gob` | `haven` | 2779 |
| `Resource` | `haven` | 2434 |
| `BotNav` | `haven.automated.nbots.world` | 2100 |
| `LpSpec` | `haven.automated.lp` | 2093 |
| `MapFile` | `haven` | 2041 |
| `Widget` | `haven` | 2033 |
| `ChatUI` | `haven` | 1942 |
| `JSONObject` | `org.json` | 1842 |
| `MiniMap` | `haven` | 1748 |
| `VorbisFile` | `com.jcraft.jorbis` | 1397 |
| `MapWnd` | `haven` | 1394 |
| `Skeleton` | `haven` | 1342 |
| `Drft` | `com.jcraft.jorbis` | 1327 |
| `AutoLpBot` | `haven.automated.lp` | 1321 |
| `Fightsess` | `haven` | 1314 |
| `MCache` | `haven` | 1309 |
| `GobIcon` | `haven` | 1296 |
| `AWTToolkit` | `haven.iosys.tk` | 1271 |
| `Config` | `haven` | 1259 |
| `MenuGrid` | `haven` | 1206 |
| `CheckpointManager` | `haven` | 1194 |
| `UI` | `haven` | 1155 |
| `BGL` | `haven.render.gl` | 1138 |
| `JSONArray` | `org.json` | 1130 |
| `GLDrawList` | `haven.render.gl` | 1101 |
| `GLEnvironment` | `haven.render.gl` | 1052 |
| `FightWnd` | `haven` | 1048 |
| `Window` | `haven` | 989 |
| `ExtInventory` | `haven` | 927 |
| `RenderTree` | `haven.render` | 926 |
| `RichText` | `haven` | 910 |

## Hurricane change density — files with the most `// ND:` markers

`// ND:` comments mark intentional Hurricane (Nightdawg) behavior. High counts = heavily customized.

| File | `// ND:` markers | Lines |
|---|---|---|
| `src/haven/OptWnd.java` | 28 | 5668 |
| `src/haven/Gob.java` | 26 | 2779 |
| `src/haven/Config.java` | 19 | 1259 |
| `src/haven/MapView.java` | 17 | 3417 |
| `src/haven/GameUI.java` | 16 | 3352 |
| `src/haven/LoginScreen.java` | 14 | 762 |
| `src/haven/automated/CoracleScript.java` | 13 | 258 |
| `src/haven/Window.java` | 13 | 989 |
| `src/haven/MapWnd.java` | 9 | 1394 |
| `src/haven/automated/AggroNearestTarget.java` | 6 | 158 |
| `src/haven/automated/AUtils.java` | 6 | 503 |
| `src/haven/automated/EnterNearestVehicle.java` | 6 | 127 |
| `src/haven/automated/SkisScript.java` | 6 | 163 |
| `src/haven/Equipory.java` | 6 | 556 |
| `src/haven/GItem.java` | 6 | 818 |
| `src/haven/GobReadyForHarvestInfo.java` | 6 | 187 |
| `src/haven/GobIcon.java` | 5 | 1296 |
| `src/haven/IMeter.java` | 5 | 204 |
| `src/haven/MiniMap.java` | 5 | 1748 |
| `src/haven/automated/AggroOrTargetCursorNearest.java` | 4 | 120 |
| `src/haven/automated/CloverScript.java` | 4 | 153 |
| `src/haven/automated/StackAllItems.java` | 4 | 98 |
| `src/haven/CheckpointManager.java` | 4 | 1194 |
| `src/haven/ExtInventory.java` | 4 | 927 |
| `src/haven/Fightsess.java` | 4 | 1314 |

## Packages by total source lines

| Package | Types | Lines |
|---|---|---|
| `haven` | 378 | 114,353 |
| `haven.render.gl` | 34 | 8,649 |
| `com.jcraft.jorbis` | 31 | 8,284 |
| `haven.automated.nbots.world` | 25 | 8,105 |
| `haven.render` | 55 | 7,741 |
| `haven.automated` | 40 | 7,551 |
| `haven.automated.lp` | 20 | 6,697 |
| `org.json` | 16 | 6,060 |
| `haven.render.sl` | 65 | 4,726 |
| `haven.resutil` | 18 | 3,771 |
| `haven.iosys.tk` | 17 | 3,682 |
| `haven.automated.nbots` | 8 | 2,335 |
| `haven.automated.pathfinder` | 10 | 2,221 |
| `haven.automated.nbots.task` | 11 | 2,108 |
| `haven.sprites` | 20 | 1,431 |
| `haven.automated.nbots.core` | 10 | 1,373 |
| `com.jcraft.jogg` | 5 | 1,277 |
| `haven.error` | 7 | 991 |
| `haven.res.ui.music` | 4 | 793 |
| `haven.automated.mapper` | 3 | 788 |
| `haven.render.jogl` | 6 | 783 |
| `haven.res.ui.croster` | 7 | 586 |
| `haven.test` | 7 | 560 |
| `haven.rs` | 4 | 485 |
| `haven.render.lwjgl` | 4 | 474 |
| `haven.iosys.audio` | 3 | 459 |
| `dolda.coe` | 5 | 455 |
| `dolda.xiphutil` | 6 | 450 |
| `haven.automated.helpers` | 3 | 450 |
| `haven.widgets` | 3 | 443 |

#reference #generated
