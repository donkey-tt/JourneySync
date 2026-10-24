# JourneySync

**One map, drawn together.** JourneySync is a both-sides Forge 1.7.10 mod (made for GTNH) that syncs
[JourneyMap](https://journeymap.info) map tiles and waypoints through the server, so every player on a
server shares one collectively-drawn map — no more manually copying (or Syncthing-syncing)
`journeymap/data/mp/...` between players.

## How it works

```
Player A's JM renders tiles to disk ──(5s mtime scan)──> SHA-1 compare ──> chunked upload
                                                                              │
                                                                              v
Player B <── PNG written (temp file + atomic move) ── RegionImageCache.clear() <── server
```

- **Server is the authority.** All tiles and waypoints are aggregated under
  `<world>/journeysync/` (`manifest.json` + `tiles/` + `waypoints.json`).
- **Tiles**: full sync when you join (downloading everything the community has explored), then
  real-time incremental both ways. Last write wins, with SHA-1 content-hash dedup — re-uploading a
  tile identical to the server's is a no-op.
- **Waypoints**: fully shared. Create one, everyone sees it; only the owner (or an OP) can delete it.
  Deletions propagate as tombstones so rejoining clients don't resurrect them.
- **Resume**: per-server sync state is kept in `config/journeysync/state-<server>.json`, so
  reconnecting after a big map only transfers what changed.
- **Graceful degradation**: clients without JourneyMap (or where the integration can't be resolved
  yet) simply idle; the game never crashes. A server without JourneySync is likewise harmless.

## Requirements

- Minecraft 1.7.10 (GTNH), Forge
- JourneyMap 5.2.10 (1.7.10) — *soft* dependency, resolved at runtime via reflection; there is no
  compile-time dependency on JourneyMap

Install the jar on **both** server and client.

## Usage

Join a server and sync starts automatically. Progress is reported in chat.

| Command | Effect |
|---|---|
| `/journeysync status` (alias `/jsync`) | Show sync state and counters |
| `/journeysync pause` / `/journeysync resume` | Pause/resume syncing |

Configuration lives in `config/journeysync.cfg` (upload/download toggles, rate limit KB/s,
scan interval, debug logging).

## Building

```bash
./gradlew build        # runs spotless + tests; jar lands in build/libs
```

For a dev environment, copy a `journeymap-1.7.10-5.2.10-unlimited.jar` into `libs/` (gitignored).

## Architecture notes

JourneyMap 5.2.10 has no plugin API on 1.7.10, so integration is file-level plus three public
methods reached by reflection, all isolated in `client/JmBridge.java`:

- `FileHandler.getJMWorldDir(Minecraft)` — locate the active JM cache directory
- `RegionImageCache.instance().clear()` — force JM to re-read tiles from disk after we write new PNGs
- `WaypointStore.instance().reset()` — force JM to re-scan waypoint files

Tile paths follow JourneyMap's own layout `DIM<dim>/{day|night|<vSlice>}/<rx>,<rz>.png`, so the
server-side store is byte-for-byte a shared copy of a JM cache. The network protocol is a
SimpleNetworkWrapper channel with a gzipped manifest blob and Schematica-style ACKed chunked
transfers (≤ 24 KB per packet) with a token-bucket rate limiter.

```
com.donkeytt.journeysync/
├── client/   ClientSyncManager (worker-thread state machine), JmBridge, commands
├── server/   ServerSyncManager (aggregation, broadcast), ServerWaypointStore (tombstones)
├── network/  packets, chunk reassembly, rate limiter
├── data/     TileKey/Manifest/WaypointRecord
└── io/       streaming SHA-1, atomic tile writes
```

---

# 中文简介

JourneySync 是一个 Forge 1.7.10（GTNH）双端 mod：通过服务器同步
[JourneyMap](https://journeymap.info) 的地图瓦片和信标，让所有玩家共同绘制同一张地图，
不再需要手动复制或用 Syncthing 同步 `journeymap/data/mp/...` 目录。

- **进服自动全量同步** 全服已探索地图，之后实时增量双向同步
- **信标全服共享**：新建即全员可见；仅所有者或 OP 可删除，删除以墓碑版本传播
- **断点续传**：按服务器记录同步状态，重连只传变化部分
- 服务端聚合存储在 `<world>/journeysync/`；冲突处理为后写覆盖 + SHA-1 内容哈希去重
- 对未装 JourneyMap 的客户端/未装本 mod 的服务端完全无害

安装：服务端与客户端都放入 jar 即可。游戏内用 `/journeysync`（或 `/jsync`）查看状态、暂停/恢复同步。
