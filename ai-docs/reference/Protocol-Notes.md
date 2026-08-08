---
title: Protocol Notes
aliases: [Protocol, Wire Format, PROTOCOL-NOTES]
tags: [reference, networking, protocol]
---

# Protocol Notes — client↔server data reference

Everything learned from instrumenting a live H&H session (captured via the
Hurricane-1.67 client) that is useful for developing the client. Companion to
[[Networking-and-Protocol]], which covers the same stack from the class side.

> [!warning] The companion tools are NOT in this repo
> This document refers throughout to `protoscope/` (analyzer), `capture/`
> (loggers + probes), `resdump/` (resource mirror) and `test/` (synthetic
> capture + fuzz). None of them exist under the Novocaine tree — they live in
> the separate instrumentation workspace the capture was run from. Treat every
> command line below as "run it there", not here.

---

## 1. How to capture and inspect your own sessions

```powershell
# decoded two-way stream (rmsg/objd/map/wmsg lines) — needs FullDump in the jar
-Dhaven.dump=session.log
# raw datagrams both directions (dmsg lines, incl. transport msgs) — needs PacketDump
-Dhaven.pdump=packets.log
# vanilla recorder (inbound only) — works in ANY stock client
-Dhaven.record=session.log
```

Analyze: `python protoscope\protoscope.py session.log --out out\sess` →
`summary.md` + `messages.csv`, `resources.csv`, `gobs.csv`,
`gob_quality_candidates.csv`, `water.csv`, `tiles.csv`, `uimsgs.csv`
(catalog of every widget message in/out with arg signatures + examples),
`widgets.csv`, `quality.csv`, `mapreq.csv`.

The record format (all line kinds): `TIMESTAMP kind args...` where kind is
`rmsg` (reliable msg), `objd` (object delta), `map` (mapdata fragment),
`closed`, and — from the custom loggers — `wmsg` (outbound reliable msg,
payload is the RAW message, no REL header) and `dmsg` (raw datagram,
`IN|OUT type bprint`). Payloads are `bprint`-escaped (below).

---

## 2. Transport layer

| channel | endpoint | notes |
|---|---|---|
| game data | **UDP `46.4.95.116:1870`** (game.havenandhearth.com / ansgar.seatribe.se) | plaintext, see below |
| login/version | TCP `:80` same host | Seatribe web; no API surface found |
| resources | TCP `:443` `game.havenandhearth.com` (`haven.resurl` in haven-config.properties) | HTTPS with **custom CA pinned in the jar** (`ressrv.crt`) |

**Datagram types (first byte):** `0=SESS, 1=REL, 2=OBJDATA, 3=BEAT,
4=MAPREQ, 5=MAPDATA, 6=RESID, 7=OBJACK, 8=SESSKEY, 12=CRYPT`.
Observed wire mix from a real session: REL batches dominate; MAPREQ/MAPDATA
for every grid; OBJDATA/OBJACK pairs; RESID announcements; periodic BEAT.

**⚠ The game channel is UNENCRYPTED.** No `MSG_CRYPT` datagrams in any
captured session. The client sends an EC P-256 public key in SESSKEY and
supports crypto, but the server never enables it. A network-path observer
reads everything; an active MITM can inject forged datagrams. If you control
the server, enabling `Connection.Crypto` is the top hardening item.

**Reliability:** REL batches are `uint16 seq + [uint8 type + payload]*`.
Client acks via `MSG_OBJACK` (type 7); retransmits on missing acks.

---

## 3. Reliable-message types — ⚠ DIFFERENT NUMBERING FROM VANILLA

This build (Hurricane-1.67) renumbered RMessage types. Do not assume the
vanilla numbers:

| # | constant | payload format |
|---|---|---|
| 0 | `RMSG_NEWWDG` | `int32 id, str type, int32 parent, list pargs, list cargs` |
| 1 | `RMSG_WDGMSG` | `int32 widgetid, str name, list args` |
| 2 | `RMSG_DSTWDG` | `int32 widgetid` |
| 3 | `RMSG_MAPIV` | map bounds: `u8? + 4×int32` (min/max grid rect) |
| 4 | `RMSG_GLOBLOB` | global time/weather: `tm <long>, astro <floats>, light <color/float>, sky, wth <ints>` |
| 6 | `RMSG_RESID` | `resid` + TTO list (deps/name) |
| 13 | `RMSG_SESSKEY` | JWK-style key list (`[[kty,EC],[crv,P-256],[x,…],[y,…],[alg,ES256]]`) |
| 14 | `RMSG_FRAGMENT` | `int32 mid, u16 of, u16 total, bytes chunk` |
| 15 | `RMSG_ADDWDG` | `int32 id, int32 parent, list pargs` — **no type string** |
| 16 | `RMSG_WDGBAR` | `int32 deps…(-1)` then optional `int32 bars…(-1)`; if absent, bars = deps |
| 17 | `RMSG_USERAGENT` | `conf.id` + `java.vm`, `java.version`, `os.name` etc. (client fingerprint — the server sees exactly which client/Java/OS you run) |

Resources (`RMSG_RESID`): each carries a `resid` (int) + TTO list; the name
arrives in a separate `RESID` announcement (server tells the client which
resources exist; the client fetches them over HTTP).

---

## 4. Wire primitives + TTO

All little-endian. Verified encodings (see `protoscope/proto_wire.py`):
`uint8/16/32`, `int8/16/32/64`, `float32/64`, `half` (float16),
`minifloat` (5-bit exp + 11-bit mantissa), `cpfloat` (compressed float, 5
bytes), `coord` (2×int32), `string` (NUL-terminated, not length-prefixed).

**TTO ("tagged type object")** — the generic argument encoding for every
`pargs`/`cargs`/`args` list. Each element: 1-byte tag then value.
`0x00 END` terminates; nested lists recurse via `T_TTOL=0x08`.

| tag | value |
|---|---|
| 0x01 INT | int32 |
| 0x02 STR | NUL-terminated string |
| 0x03 COORD | 2×int32 |
| 0x04/0x05/0x09/0x0a | uint8/uint16/int8/int16 |
| 0x06/0x07 | color / float color |
| 0x08 TTOL | nested list |
| 0x0c NIL | nothing |
| 0x0d UID | int64 |
| 0x0e BYTES | `u8 len` + bytes |
| 0x0f/0x10 | float32/float64 |
| 0x15/0x16/0x17–0x1c | float8/float16/snorm/unorm/mnorm (8/16) |

Known gotcha: `Message.bytes()` **advances the read head** (it consumes) —
snapshot via `clone().bytes()` (this bug ate a session's datagrams once).

---

## 5. Object deltas (objd) — gob state

Each `objd` line: `flags id frame [initframe] attrs...`. Flags string: `n`
=new, `i` =initframe (>0), etc. Attrs: `TYPE:bprintblob` pairs.

| OD | meaning | payload |
|---|---|---|
| 0 REM | remove gob | — |
| 1 MOVE | position | `coord (tiles), u16 dir` |
| 2 RES | resource | `u16 resid` |
| 3/4 LINBEG/LINSTEP | movement path | path steps |
| 5 SPEECH | speech bubble | string |
| 6 COMPOSE | container contents | `u16 size` + item deltas (REM/RESATTR/FOLLOW/MOVE/RES/LINBEG/LINSTEP/HOMING) |
| 7 ZOFF | z offset | int16 |
| 8 LUMIN | light | — |
| 9 AVATAR | avatar | — |
| 10/11 FOLLOW/HOMING | follow target | gob id |
| 12 OVERLAY | overlay | `resid, u16?` + optional pose/frame list |
| 14 HEALTH | hp | `u8 cur, u8 max` |
| 16/17/18 CMPPOSE/MOD/EQU | composed item state | — |
| 19 ICON | icon | resid |
| 20 RESATTR | unstructured data | raw blob |

**What is NOT in object data:** quality. No attribute carries a quality
float. Quality only ever appears inside widget tooltips (see §8) — capped.

---

## 6. Mapdata grids — full format

Map requests: `MSG_MAPREQ` = `coord (grid coords, grid = 25×25 tiles)`;
responses `MSG_MAPDATA` = `int32 pktid, u16 off, u16 len, chunk` (defragged
by pktid; multiple fragments per grid). Grid payload:

```
coord grid, u8 ver?, then subs:
  t   = tile table:  u16 count + (u16 encid, u16 tileid, str resname, u16 ver)*
  t2  = tile id map (16-bit)     t3 = tile id map (8-bit, ids < 256)
  h   = heights:    u8 fmt, f32 min, f32 q, delta bytes ×(25×25)
  f   = first grid?  ob = overlays:  (u16 idx, u16 len, u8 type, int32 resid, blob)*
  pl  = plots       w  = water:      2×u32 (on/off bitmaps?)
  z   = compressed subfill: zlib(data) → another sub (e.g. t2/t3/h)
  end = 0xff
```

The `z` (zlib) sub is how the big grids stay small — decompress before
parsing the inner sub. Ore is just another tile (`gfx/tiles/rocks/…`) in the
tile table; **buried ore is present in the client's grid data even when not
visible** — which is exactly what OreAndStoneCounter exploits (it walks the
grid's flavor/overlay data around the cursor and tints hidden ore).

**Server validates map requests against its own simulated player position.**
Verified live: requests beyond the view radius → silently ignored; spoofed
click positions → ignored (0/27 grids served). There is no way to pull
mapdata (ores, terrain) outside the rendered view.

---

## 7. Widget system (the entire UI is server-driven)

- `newwdg id type parent pargs cargs` — create; `pargs`/`cargs` are the
  widget-constructor args (this is how the server opens windows, tooltips,
  inventory, the minimap, etc.).
- `wdgmsg id name args` — the workhorse. Inbound: server→widget commands
  (`set`, `tip`, `prog`, `chres`, `add`, `msg`…). Outbound: client actions.
- `dstwdg` destroy; `wdgbar` dependency bar.

**Observed inbound catalog** (real session): `set/tip` (image widgets),
`glut` (battr), `m` (mapview markers, arg = gob id), `tt` (tooltip: `item`,
`buff`), `prog`, `max`, `add`/`list`/`pid`/`ldr` (party), `move`
(server-authoritative position push: `coord, dir`), `chres`, `curs`,
`fill`, `msg`/`msg2` (notices), `attr` (chr stats), `srv`, `ext`,
`polowner`, `setbelt2`, `map-icons`, `sfx`, `bg`, `err`.

**Observed outbound catalog**: `click` (ground/object click, see §9),
`act` (actions: `'tracking'`, `'swim'`, `'crime'`…), `itemact`, `cl`
(click-by-gobid), `focus`, `play` (character selection), `tabfocus`.

---

## 8. Quality — what the client can and cannot see

- **The cap is server-side.** The client has zero quality-masking code; the
  `Quality` tooltip renders `args[1]` (a float32) as-is.
- Tooltip format: `tt` on the `item` widget carries arg pairs like
  `[resid, quality]` (e.g. `[13183, 13.100000381469727]`) — the quality IS
  sent as a float, but it is the **post-cap** value (`min(true, f(skill))`).
- The inspect notice (`msg2`) is **pre-formatted text**: the server sends
  `Info(gobid, "Quality: 46", syn)` — the client never sees the number, only
  the string. The client stores it on the gob (`SavedInfo`) and re-shows it
  on hover — that cache is safe to reuse for your own UI.
- **Consequences for dev:** a sample *below* your relevant stat is the true
  value; a sample *equal* to the stat means true ≥ stat (hidden). Track
  samples per tile/gob over time (`quality.csv`) and you converge on true
  values as stats rise — no protocol hack needed.
- Animals: quality exists server-side only; live animals send no quality in
  objd; only the post-mortem capped inspect text exists. Nothing more to
  squeeze.

---

## 9. Click / coordinate scale

- `click([screenX, screenY], [worldX, worldY], button, modflags, ...)`
  — world coords are in **posres units**: `world = tile × (1024/11)`
  (posres = 11/1024 per unit, i.e. ~93.09 world-units per tile). To convert
  for spoofing/probing: `tile = world × 11/1024`.
- Button: 1 = left, 2 = right, 3 = middle. Extra args (6-arg form):
  `time, clickedGobid, [more world], …`.
- Far clicks are validated server-side (walk requests only within reach;
  the spoof probe's 1000-tile clicks never moved the character).

---

## 10. Data-availability matrix (what to build features on)

| data | channel | client has it? |
|---|---|---|
| gob positions / hp / overlays / container contents | objd (in view only) | ✅ always |
| tiles, heights, water, plots, **buried ore** | mapdata (in view only) | ✅ always |
| day/night, weather, astro | GLOBLOB (rmsg 4) | ✅ |
| map bounds | MAPIV (rmsg 3) | ✅ |
| character stats (`chr attr` — all skills/attributes) | wdgmsg `attr` | ✅ |
| item quality (capped), tooltips, iteminfo | wdgmsg `tt` / `iteminfo` | ✅ (post-cap) |
| resource definitions + all resource-based UI **source code** | HTTP (`/res/<name>.res`) | ✅ any name fetches (see §11) |
| true node quality (water/animal) | — | ❌ never leaves the server |
| ore beyond the view radius | — | ❌ server validates mapreq distance |
| uncapped anything | — | ❌ no message returns it |

---

## 11. The resource server is a dev goldmine

`haven.resurl` → `https://game.havenandhearth.com/res/<name>.res`. Directory
listing is blocked (403), but **any known name fetches with no auth**, and
every resource's `deps` layer lists its dependencies — so the full tree is
crawlable (1,256 resources mirrored; `resdump/resdump.py` does
fetch+parse+strings). Resources contain:

- **`src` layers — the preprocessed Java source** of every resource-based
  widget (e.g. the full `LocalInspect` implementation lives in
  `ui/inspect.res`; tooltip parsers in `ui/tt/q/quality.res`). This is
  server-shipped client code — read it to learn exactly how any UI feature
  works, and to copy idioms into your own client.
- `code` layers (bytecode), `tileset2`/`tile` layers (tile defs + tags e.g.
  `cave`), `mat2` (materials), `tooltip` layers (e.g. "Black Coal"),
  textures.
- **No runtime game data** — ore-node resources are textures only; no
  quality tables, spawn tables, or cap formulas anywhere in the tree
  (verified by keyword scan of 1,200 resources).

---

## 12. Gotchas learned the hard way

1. **RMessage numbering differs from vanilla** (§3) — always check
   `src/haven/RMessage.java`, not upstream memory.
2. **`Message.bytes()` consumes** the message (advances read head). Use
   `clone().bytes()` when logging/peeking.
3. Outbound logged payloads (`wmsg`) are the **raw message**, NOT
   REL-wrapped (no seq/type header) — the wrap happens later in
   `sendpending()`.
4. **bprint escaping**: `\\` → backslash; chars 33..126 → literal; **space
   (0x20) and everything else → `\xx` lowercase hex**. So "Java 21" logs as
   `Java\20HotSpot`.
5. `haven.record` works in stock clients; the two-way loggers need the
   patched jar. **The jar is locked while the game runs** — the rebuild
   script falls back to `hafen.jar.probe` for a swap-on-close.
6. Transport TLS: the resource channel uses a pinned custom CA
   (`ressrv.crt` bundled in the res jar) — don't expect system-CA behavior.
7. Rebuilding: `python capture\rebuild-client.py` recompiles
   `src/haven/*.java` with the lib jars and surgically replaces changed
   inner classes in the Release jar (no full rebuild needed).

## 13. Dev recipes

- **Show hidden ore**: walk `MCache.Grid` flavor/overlay data around the
  cursor (pattern: `haven/automated/OreAndStoneCounter.java`) — buried ore
  tiles exist in your cached grid data.
- **Track water quality per tile**: correlate `tt` quality floats with the
  player's tile at collection time; store max-observed per tile.
- **New UI widget**: read the server-shipped `src` layer of a similar
  widget from `/res/` first — the pattern (incl. arg contracts) is there.
- **Reproducible testing**: `test/make_synth.py` writes a synthetic capture
  (SESSKEY/RESID/new-widget/tooltips/bear+tree objd/two grids with zlib
  subfill/fragmented mapdata) that exercises every decoder — add features
  against it, then validate on a real dump.
