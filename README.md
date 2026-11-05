<div align="center">

# JourneySync

**One map, drawn together — 所有人的足迹，绘成同一张地图。**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.7.10-green.svg?logo=minecraft)](https://minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-1.7.10-orange.svg)](https://files.minecraftforge.net)
[![GTNH](https://img.shields.io/badge/GTNH-2.8.x-blue.svg)](https://gtnh.miraheze.org)
[![JourneyMap](https://img.shields.io/badge/JourneyMap-5.2.10-8a5cf6.svg)](https://journeymap.info)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Build](https://img.shields.io/badge/build-%E2%9C%93-brightgreen.svg)](https://github.com/donkey-tt/JourneySync)

*A both-sides Forge 1.7.10 mod that syncs [JourneyMap](https://journeymap.info) map tiles and
waypoints through the server — so every player on a server shares one collectively-drawn map.*

*一个 Forge 1.7.10（GTNH）双端 mod：通过服务器同步 JourneyMap 的地图瓦片与信标，
让所有玩家共同绘制同一张地图。*

[Installation](#installation) ·
[Usage](#usage) ·
[How It Works](#how-it-works) ·
[Configuration](#configuration) ·
[Building](#building) ·
[Architecture](#architecture) ·
[中文](#中文)

</div>

---

## Features

| | |
|---|---|
| **Full sync on join** | Download everything the community has explored, then stay in sync in real time — bidirectional, incremental. |
| **Team-scoped sync** | With [GTNHLib](https://github.com/GTNewHorizons/GTNHLib) installed, map tiles and waypoints sync per `/gtnhteam` team — teammates share a map, everyone else keeps their own on top of the global baseline. Disable with `syncByTeam=false`. |
| **Monotone map** | Uploads carry the pixel union of every render seen this session, and the server merges pixel-wise too — the shared map only ever grows. A partial re-render (chunks unloaded) can never shrink it. |
| **Resume where you left off** | Per-server sync state means reconnecting only transfers what actually changed. |
| **Server-authoritative** | All data aggregated under `<world>/journeysync/`; identical content is deduped by SHA-1. |
| **Repair tool** | `/jsync rerender` re-renders the region you stand in through JourneyMap's own task system, healing damage from before this pipeline existed. |
| **Graceful degradation** | No JourneyMap on the client? No JourneySync on the server? Nothing breaks — everyone just plays normally. |
| **Zero hard dependency** | JourneyMap 5.2.10 is resolved at runtime via reflection; nothing is required at compile time. |
| **Bilingual** | Chat messages and status output in English and 中文. |

---

## Installation

1. Drop the jar into the **`mods/` folder of both the server and every client**.
2. That's it. Sync starts automatically when a player joins.

> **Requires:** Minecraft 1.7.10 · Forge · [JourneyMap](https://journeymap.info) 5.2.10 *(soft dependency — the mod idles harmlessly without it)*
> **Optional:** [GTNHLib](https://github.com/GTNewHorizons/GTNHLib) — enables team-scoped sync (`/gtnhteam`)
## Usage

Join a server — everything else is automatic. Progress is reported in chat.

| Command | Effect |
|---|---|
| `/journeysync status` | Show sync state, tile/waypoint counters |
| `/jsync pause` | Pause syncing |
| `/jsync resume` | Resume syncing |
| `/jsync rerender` | Re-render the region you're standing in (day + night) through JourneyMap's own map task — the repair tool for map damage that predates the pixel-union pipeline. Stand in the affected area: only loaded chunks are rendered. The rewritten tiles flow into the normal upload path and can only add coverage. |

## How It Works

Sync follows one lifecycle, repeated on every join:

| Phase | What happens |
|---|---|
| **1. Handshake** | Client connects, announces itself. Server replies with its configuration and the current manifest (every tile's SHA-1, gzipped) plus all shared waypoints. |
| **2. Diff** | Client compares the manifest against (a) tiles already on disk and (b) hashes it synced last time. Anything missing or changed goes onto a download list; anything it has that the server doesn't goes onto an upload list. Identical content on both sides is skipped entirely. |
| **3. Transfer** | Tiles move in both directions as Schematica-style ACKed chunks (≤ 24 KB per packet, token-bucket rate limited). Each tile is verified against its SHA-1 before being accepted. Progress is reported in chat. |
| **4. Apply** | Downloaded tiles are written to the JourneyMap cache as temp files + atomic move, then the live map texture (day/night and cave layers alike) is refreshed in place via reflection, so other viewed tiles don't flicker. Waypoint changes are written likewise, followed by `WaypointStore.reset()`. |
| **5. Watch** | The client watches the tile directories directly (a periodic scan is the backstop): each JourneyMap render is picked up immediately, and uploads are debounced until the file stat settles. Every version of a tile seen this session is unioned into that tile's upload payload — a re-render that dropped pixels (chunks unloaded) uploads its pixels anyway, so nothing is lost. Tiles pushed by other players arrive and are applied as in phase 4; a tile whose local render regressed is automatically re-downloaded for display. |

<details>
<summary><b>More detail</b></summary>

- **Tiles** — JourneyMap writes 512×512 PNG tiles under `DIM<dim>/{day|night|<vSlice>}/<rx>,<rz>.png`.
  JourneySync watches that directory: anything new or changed is hashed, and the upload payload is
  the pixel union of every version seen this session merged with the current file. The server
  pixel-merges incoming uploads into its stored tile (union again) and broadcasts the result.
- **Waypoints** — every waypoint JSON is mirrored server-side with an owner and a revision counter.
  Deletes are tombstones, so a client that was offline doesn't resurrect deleted waypoints on reconnect.
- **Conflicts** — tiles never conflict: both sides merge pixel-wise, so coverage only grows.
  Waypoints are last-write-wins by revision; identical tile content (same SHA-1) is a no-op.
- **Delivery** — tiles already on your disk are never re-sent; new tiles arrive as
  Schematica-style ACKed chunks (≤ 24 KB per packet) throttled by a token-bucket rate limiter.

</details>

## Configuration

`config/journeysync.cfg`

| Option | Default | Description |
|---|---|---|
| `uploadEnabled` / `downloadEnabled` | `true` | Directional toggles |
| `rateLimitKBps` | `0` | Client upload throttle (0 = unlimited) |
| `scanIntervalSeconds` | `2` | How often the local cache is scanned for changes |
| `uploadSettleSeconds` | `2` | A changed tile is uploaded only after its file has stayed unchanged this long, debouncing re-renders while moving (0 = off) |
| `tileUploadCooldownSeconds` | `60` | After uploading a tile, wait this long before uploading it again unless it grew significantly (suppresses cosmetic re-render jitter; 0 = off) |
| `chunkSizeBytes` | `24576` | Network chunk payload size in bytes (4 KB–28 KB) |
| `maxTileBytes` | `32 MiB` | Sanity limit for a single tile transfer |
| `logDebug` | `false` | Verbose logging |
| `syncByTeam` | `true` | Group map/waypoint sync by GTNHLib team instead of server-wide (players without a team only share with themselves) |
| `shareDeathWaypoints` | `false` | Share JourneyMap death waypoints (`type=Death`) with the sync scope; off keeps them private to the player |

Death waypoints never sync by default — JourneyMap auto-creates them (`type: "Death"`), so the client filter skips them entirely and the server rejects stragglers. A waypoint previously shared by an older version is tombstoned once on the next scan.

### Team sync

When `syncByTeam=true` **and** [GTNHLib](https://github.com/GTNewHorizons/GTNHLib) is present on the
server, sync is scoped by `/gtnhteam` membership instead of being strictly server-wide:

- Every player sees the **`global` scope** (the server baseline) plus their **own scope**:
  `team:<id>` for team members, `solo:<uuid>` for teamless players.
- **Uploads land in your own scope.** Teammates share tiles and waypoints live; a teamless
  player's map stays private on top of the global baseline — exactly like having a one-player team.
- **Your scope wins on conflicts.** A tile or waypoint in your own scope shadows the global
  version (including deletions); you still pull every global tile your scope lacks.
- **Joining a team migrates your personal waypoints** (the ones you created while teamless) into
  the team store. Leaving a team takes nothing back — records stay with the team scope.
- **Scope changes apply within a tick** — joining, leaving, or being kicked refreshes your map and
  waypoint view automatically.

With `syncByTeam=false` or no GTNHLib installed, everything behaves exactly as before: all players
share the single `global` scope.

All scope data lives under `<world>/journeysync/scopes/<scope>/` (`tiles/`, `manifest.json`,
`waypoints.json`); saves created by older versions are migrated into `scopes/global/` on first
start. Waypoint revisions use one server-wide counter (`journeysync/epoch`) so ordering stays
monotonic across every scope.

## Building

```bash
./gradlew spotlessApply build   # formatting + unit tests; jar lands in build/libs
```

> CI runs `spotlessCheck` as a separate post-build step — `build` alone does **not** verify
> formatting, so always `spotlessApply` before committing.

For a dev environment, copy a `journeymap-1.7.10-5.2.10-unlimited.jar` into `libs/` (gitignored).

## Architecture

JourneyMap 5.2.10 has no plugin API on 1.7.10, so integration is **file-level plus a handful of
reflective calls**, all isolated in `client/JmBridge.java` (instances are read lazily at call
time — JM constructs them late and recreates them per world):

| Reflection target | Purpose |
|---|---|
| `FileHandler.getJMWorldDir(Minecraft)` | Locate the active JM cache directory (world GUID dir) |
| `RegionImageSet.getHolder(...)` → `ImageHolder.getTexture()` → `TextureImpl.setImage(...)` | Refresh just the changed tile's live texture (surface and cave layers) — no full cache clear, no flicker |
| `WaypointStore.instance().reset()` | Force JM to re-scan waypoint files |
| `JourneymapClient.toggleTask(MapRegionTask.Manager, true, RegionCoord)` | Queue a region re-render (`/jsync rerender`), gated on the task controller's busy flag |

```
com.donkeytt.journeysync/
├── client/   ClientSyncManager (single-threaded state machine; network handlers only enqueue)
│             ├─ UploadGuards      pure upload decision chain (unit-tested)
│             ├─ QuietPeriods      per-tile debounce windows (cooldown / restore / inbound)
│             ├─ LocalTileIndex    in-memory view of the JM tile directory (stat + hash)
│             ├─ RenderUnionCache  per-tile pixel union of every render seen this session
│             ├─ ShadowStore       server-authoritative reference copies for pixel guards
│             ├─ TileWatcher       file-watch fast path (scan interval is the backstop)
│             ├─ WaypointSync      waypoint scan / outbox / blob application
│             └─ JmBridge          all JourneyMap reflection, isolated
├── server/   ServerSyncManager (per-scope stores, pixel merge, broadcast; synchronized handlers)
│             ├─ SyncScope         tiles + manifest + waypoints under scopes/<key>/
│             ├─ ScopeKeys         global / team:<id> / solo:<uuid> keys (+ fs-safe dir names)
│             ├─ ScopeMerge        merged-view + solo→team migration helpers (unit-tested)
│             ├─ WaypointEpoch     server-wide waypoint revision counter
│             ├─ ServerWaypointStore (tombstones; revs from the shared epoch)
│             └─ TeamBridge        all GTNHLib team lookups/events, isolated
├── network/  packets (bidirectional transfer begin/chunk/ack), chunking, reassembly,
│             rate limiter
├── data/     TileKey / Manifest / WaypointRecord / PixelMerge / PixelInfo
└── io/       SHA-1 hashing, atomic tile writes
```

Threading model: on the client, every inbound packet is enqueued and consumed by one worker
thread (no locks, one owner for all sync state); UI side effects are deferred to the client tick.
On the server, handlers run on Netty threads behind a single lock — all state access is
serialized without touching the server main thread.

---

<h2 id="中文">中文</h2>

**JourneySync** 通过服务器同步 [JourneyMap](https://journeymap.info) 的地图瓦片和信标，
让所有玩家共同绘制同一张地图 —— 不再需要手动复制、或用 Syncthing 同步
`journeymap/data/mp/...` 目录。

- **进服自动全量同步**全服已探索地图，之后实时增量双向同步
- **地图只增不减**：每次渲染都并入该瓦片的上传载荷（像素并集），服务端合并同样取并集 ——
  区块卸载导致的局部重绘永远无法让共享地图缩水
- **信标共享**：新建即共享可见；仅所有者或 OP 可删除，删除以墓碑版本传播
- **团队同步（可选）**：服务端装有 GTNHLib 时按 `/gtnhteam` 队伍分组同步 ——
  队友共享地图与信标，无队伍玩家在全球基线之上独享自己的范围
  （`syncByTeam=false` 可关闭）；加入队伍时个人信标自动迁入队伍存储
- **断点续传**：按服务器记录同步状态，重连只传变化部分
- **服务端权威**：聚合存储在 `<world>/journeysync/`；SHA-1 内容哈希去重
- **修复工具**：`/jsync rerender` 借 JourneyMap 自己的任务系统重渲染脚下区域，
  治愈本管线之前留下的地图损伤（站在受损区域内执行，重绘只会增加覆盖）
- **完全无害**：未装 JourneyMap 的客户端、未装本 mod 的服务端，均不受影响

安装：服务端与客户端都放入 jar 即可。游戏内用 `/journeysync`（或 `/jsync`）查看状态、暂停/恢复同步，`/jsync rerender` 重渲染当前区域。

<div align="center">

Made for the GTNH community · [MIT License](LICENSE)

</div>
