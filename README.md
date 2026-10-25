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
| **Shared waypoints** | Create a waypoint, everyone sees it. Only the owner (or an OP) can delete it; deletions propagate as tombstones. |
| **Resume where you left off** | Per-server sync state means reconnecting only transfers what actually changed. |
| **Server-authoritative** | All data aggregated under `<world>/journeysync/` — conflicts resolved by last-write-wins with SHA-1 dedup. |
| **Graceful degradation** | No JourneyMap on the client? No JourneySync on the server? Nothing breaks — everyone just plays normally. |
| **Zero hard dependency** | JourneyMap 5.2.10 is resolved at runtime via reflection; nothing is required at compile time. |
| **Bilingual** | Chat messages and status output in English and 中文. |

---

## Installation

1. Drop the jar into the **`mods/` folder of both the server and every client**.
2. That's it. Sync starts automatically when a player joins.

> **Requires:** Minecraft 1.7.10 · Forge · [JourneyMap](https://journeymap.info) 5.2.10 *(soft dependency — the mod idles harmlessly without it)*

## Usage

Join a server — everything else is automatic. Progress is reported in chat.

| Command | Effect |
|---|---|
| `/journeysync status` | Show sync state, tile/waypoint counters |
| `/jsync pause` | Pause syncing |
| `/jsync resume` | Resume syncing |

## How It Works

Sync follows one lifecycle, repeated on every join:

| Phase | What happens |
|---|---|
| **1. Handshake** | Client connects, announces itself. Server replies with its configuration and the current manifest (every tile's SHA-1, gzipped) plus all shared waypoints. |
| **2. Diff** | Client compares the manifest against (a) tiles already on disk and (b) hashes it synced last time. Anything missing or changed goes onto a download list; anything it has that the server doesn't goes onto an upload list. Identical content on both sides is skipped entirely. |
| **3. Transfer** | Tiles move in both directions as Schematica-style ACKed chunks (≤ 24 KB per packet, token-bucket rate limited). Each tile is verified against its SHA-1 before being accepted. Progress is reported in chat. |
| **4. Apply** | Downloaded tiles are written to the JourneyMap cache as temp files + atomic move, then `RegionImageCache.clear()` is called via reflection so JourneyMap re-reads them from disk. Waypoint changes are written likewise, followed by `WaypointStore.reset()`. |
| **5. Watch** | The client then scans its cache every 5 seconds. New tiles JourneyMap renders (exploration) or new waypoints the player creates are hashed and uploaded incrementally; tiles and waypoints pushed by other players arrive and are applied the same way as in phase 4. |

<details>
<summary><b>More detail</b></summary>

- **Tiles** — JourneyMap writes 512×512 PNG tiles under `DIM<dim>/{day|night|<vSlice>}/<rx>,<rz>.png`.
  JourneySync watches that directory: anything new or changed is hashed and uploaded; the server
  stores it byte-for-byte in its own cache and broadcasts it to whoever needs it.
- **Waypoints** — every waypoint JSON is mirrored server-side with an owner and a revision counter.
  Deletes are tombstones, so a client that was offline doesn't resurrect deleted waypoints on reconnect.
- **Conflicts** — last write wins; identical content (same SHA-1) is a no-op, so re-uploading an
  unchanged tile costs nothing.
- **Delivery** — tiles already on your disk are never re-sent; new tiles arrive as
  Schematica-style ACKed chunks (≤ 24 KB per packet) throttled by a token-bucket rate limiter.

</details>

## Configuration

`config/journeysync.cfg`

| Option | Default | Description |
|---|---|---|
| `syncEnabled` | `true` | Master switch for all syncing |
| `uploadEnabled` / `downloadEnabled` | `true` | Directional toggles |
| `rateLimitKBps` | `0` | Upload/download throttle (0 = unlimited) |
| `scanIntervalSeconds` | `5` | How often the local cache is scanned for changes |
| `uploadSettleSeconds` | `3` | A changed tile is uploaded only after its file has stayed unchanged this long, debouncing re-renders while moving (0 = off) |
| `logDebug` | `false` | Verbose logging |

## Building

```bash
./gradlew build        # spotless + unit tests; jar lands in build/libs
```

For a dev environment, copy a `journeymap-1.7.10-5.2.10-unlimited.jar` into `libs/` (gitignored).

## Architecture

JourneyMap 5.2.10 has no plugin API on 1.7.10, so integration is **file-level + three public
methods via reflection**, all isolated in `client/JmBridge.java`:

| Reflection target | Purpose |
|---|---|
| `FileHandler.getJMWorldDir(Minecraft)` | Locate the active JM cache directory (world GUID dir) |
| `RegionImageCache.instance().clear()` | Force JM to re-read tiles from disk after we write new PNGs |
| `WaypointStore.instance().reset()` | Force JM to re-scan waypoint files |

```
com.donkeytt.journeysync/
├── client/   ClientSyncManager (worker-thread state machine), JmBridge, commands
├── server/   ServerSyncManager (aggregation, broadcast), ServerWaypointStore (tombstones)
├── network/  packets, chunk reassembly, rate limiter
├── data/     TileKey / Manifest / WaypointRecord
└── io/       streaming SHA-1, atomic tile writes
```

---

<h2 id="中文">中文</h2>

**JourneySync** 通过服务器同步 [JourneyMap](https://journeymap.info) 的地图瓦片和信标，
让所有玩家共同绘制同一张地图 —— 不再需要手动复制、或用 Syncthing 同步
`journeymap/data/mp/...` 目录。

- **进服自动全量同步**全服已探索地图，之后实时增量双向同步
- **信标全服共享**：新建即全员可见；仅所有者或 OP 可删除，删除以墓碑版本传播
- **断点续传**：按服务器记录同步状态，重连只传变化部分
- **服务端权威**：聚合存储在 `<world>/journeysync/`；后写覆盖 + SHA-1 内容哈希去重
- **完全无害**：未装 JourneyMap 的客户端、未装本 mod 的服务端，均不受影响

安装：服务端与客户端都放入 jar 即可。游戏内用 `/journeysync`（或 `/jsync`）查看状态、暂停/恢复同步。

<div align="center">

Made for the GTNH community · [MIT License](LICENSE)

</div>
