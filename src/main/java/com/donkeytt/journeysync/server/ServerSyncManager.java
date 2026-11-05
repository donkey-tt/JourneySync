package com.donkeytt.journeysync.server;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.Manifest;
import com.donkeytt.journeysync.data.PixelMerge;
import com.donkeytt.journeysync.data.TileEntry;
import com.donkeytt.journeysync.data.TileKey;
import com.donkeytt.journeysync.data.WaypointRecord;
import com.donkeytt.journeysync.io.Hashing;
import com.donkeytt.journeysync.network.IncomingTransfer;
import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.network.TileChunking;
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.HelloAckPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.TileChunk;
import com.donkeytt.journeysync.network.packets.TileTransferAck;
import com.donkeytt.journeysync.network.packets.TileTransferBegin;

import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * Server-side aggregation point. Owns the per-scope on-disk stores under
 * {@code journeysync/scopes/}, receives uploads, serves downloads and broadcasts changes.
 * Handlers run on Netty worker threads; every entry point is synchronized on this instance, which
 * serializes all state access without putting any of the work onto the server main thread.
 *
 * <p>
 * Scope model: every player reads the {@code global} scope plus their own scope
 * ({@code team:<id>} when GTNHLib teams are enabled and they are in one, {@code solo:<uuid>}
 * otherwise). Writes always land in the player's own scope; the merged view is what clients see.
 * When team sync is disabled or GTNHLib is absent every player is in {@code global}, which makes
 * the behavior identical to the original server-wide store.
 * </p>
 */
public class ServerSyncManager {

    private static final ServerSyncManager INSTANCE = new ServerSyncManager();

    public static ServerSyncManager instance() {
        return INSTANCE;
    }

    private File dataDir;
    private File scopesDir;

    /** scope key -> lazily loaded store. The global scope is always present after init. */
    private final Map<String, SyncScope> scopes = new HashMap<String, SyncScope>();

    /** Server-wide waypoint revision counter, shared by every scope. */
    private WaypointEpoch waypointEpoch;

    /** playerUuid -> assigned scope key, refreshed on hello and on team events. */
    private final Map<UUID, String> playerScopes = new HashMap<UUID, String>();

    /** playerUuids whose scope must be recomputed on the next tick (team events). */
    private final Set<UUID> pendingScopeCheck = new LinkedHashSet<UUID>();

    /** playerUuid -> (transferId -> incoming upload) */
    private final Map<UUID, Map<Integer, IncomingTransfer>> uploads = new HashMap<UUID, Map<Integer, IncomingTransfer>>();

    /** playerUuid -> last outgoing transfer id allocated for that player */
    private final Map<UUID, Integer> lastSentTransferId = new HashMap<UUID, Integer>();

    private long lastSaveMillis;
    private boolean dirty;

    private ServerSyncManager() {}

    /** Called once per world load from the common proxy. */
    public synchronized void init(File worldDir) {
        this.dataDir = new File(worldDir, "journeysync");
        this.scopesDir = new File(dataDir, "scopes");
        migrateLegacyStorage();
        this.scopes.clear();
        this.waypointEpoch = new WaypointEpoch(new File(dataDir, "epoch"));
        this.uploads.clear();
        this.lastSentTransferId.clear();
        this.playerScopes.clear();
        this.pendingScopeCheck.clear();
        SyncScope global = scope(ScopeKeys.GLOBAL);
        this.lastSaveMillis = System.currentTimeMillis();
        this.dirty = false;
        JourneySync.LOG.info(
            "JourneySync server storage initialized at {} ({} tiles, {} waypoints in global scope)",
            dataDir,
            global.manifest.tiles.size(),
            global.waypointStore.allRecords()
                .size());
    }

    public synchronized void shutdown() {
        saveNow();
    }

    // ------------------------------------------------------------------ scopes

    /** Get (or lazily load/create) the store for a scope key. */
    private SyncScope scope(String key) {
        SyncScope s = scopes.get(key);
        if (s == null) {
            s = new SyncScope(key, new File(scopesDir, ScopeKeys.dirName(key)));
            waypointEpoch.raiseFloor(s.waypointStore.epoch);
            scopes.put(key, s);
        }
        return s;
    }

    private String scopeOf(EntityPlayerMP player) {
        return scopeOf(player.getUniqueID());
    }

    private String scopeOf(UUID playerUuid) {
        String key = playerScopes.get(playerUuid);
        return key == null ? ScopeKeys.GLOBAL : key;
    }

    /**
     * The scope this player should have right now. Only called from the scope re-check points
     * (hello / tick drain): GTNHLib logs an upstream ERROR for teamless lookups, so this must
     * never run per packet.
     */
    private String computeScopeKey(EntityPlayerMP player) {
        if (!TeamBridge.teamSyncActive()) {
            return ScopeKeys.GLOBAL;
        }
        String teamId = TeamBridge.teamIdFor(player.getUniqueID());
        return teamId == null ? ScopeKeys.soloKey(player.getUniqueID()) : ScopeKeys.teamKey(teamId);
    }

    /**
     * Assign the player's scope. If it newly became a team scope, migrate their personal solo
     * waypoints into the team store and notify the team. Safe to call when nothing changed.
     *
     * @return true when the stored scope key changed
     */
    private boolean assignScope(EntityPlayerMP player) {
        UUID uuid = player.getUniqueID();
        String oldKey = playerScopes.get(uuid);
        String newKey = computeScopeKey(player);
        boolean changed = !newKey.equals(oldKey);
        if (changed) {
            playerScopes.put(uuid, newKey);
            JourneySync.LOG.info("Player {} sync scope: {} -> {}", player.getCommandSenderName(), oldKey, newKey);
        }
        if (newKey.startsWith(ScopeKeys.TEAM_PREFIX) && !newKey.equals(oldKey)) {
            if (migrateOwnedWaypoints(ScopeKeys.soloKey(uuid), newKey, uuid.toString())) {
                dirty = true;
                // Teammates' merged view changed; the player gets their full push from the caller.
                broadcastWaypoints(newKey, player);
            }
        }
        return changed;
    }

    /**
     * Move a player's own non-deleted waypoint records from one scope store into another,
     * tombstoning them in the source. Used for solo -> team promotion so the team gains the
     * member's personal waypoints. Other directions never migrate (records stay with the scope).
     *
     * @return true when at least one record migrated
     */
    private boolean migrateOwnedWaypoints(String fromKey, String toKey, String ownerUuid) {
        Map<String, WaypointRecord> moved = ScopeMerge
            .migrateOwned(scope(fromKey).waypointStore.allRecords(), ownerUuid, waypointEpoch::next);
        if (moved.isEmpty()) {
            return false;
        }
        SyncScope from = scope(fromKey);
        SyncScope to = scope(toKey);
        for (WaypointRecord rec : moved.values()) {
            to.waypointStore.put(rec);
            WaypointRecord tombstone = ScopeMerge.copy(rec);
            tombstone.deleted = true;
            tombstone.rev = waypointEpoch.next();
            from.waypointStore.put(tombstone);
        }
        JourneySync.LOG
            .info("Migrated {} waypoints owned by {} from scope {} to {}", moved.size(), ownerUuid, fromKey, toKey);
        return true;
    }

    /** Queue a player for a scope re-check at the next tick (called from GTNHLib team events). */
    public synchronized void queueScopeRecheck(UUID playerUuid) {
        pendingScopeCheck.add(playerUuid);
    }

    /** Queue every online player (used by team merges where two teams' membership changes). */
    public synchronized void queueAllOnlineScopeRecheck() {
        for (Object o : players()) {
            pendingScopeCheck.add(((EntityPlayerMP) o).getUniqueID());
        }
    }

    private void drainScopeRechecks() {
        if (pendingScopeCheck.isEmpty()) {
            return;
        }
        List<UUID> pending = new ArrayList<UUID>(pendingScopeCheck);
        pendingScopeCheck.clear();
        for (Object o : players()) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (pending.contains(player.getUniqueID()) && assignScope(player)) {
                sendManifest(player);
                sendWaypoints(player);
            }
        }
    }

    // ------------------------------------------------------------------ events

    public synchronized void onHello(EntityPlayerMP player, HelloPacket hello) {
        if (hello.protocolVersion != PacketHandler.PROTOCOL_VERSION) {
            JourneySync.LOG.warn(
                "Player {} uses JourneySync protocol {} (mod {}), server expects {} - ignoring",
                player.getCommandSenderName(),
                hello.protocolVersion,
                hello.modVersion,
                PacketHandler.PROTOCOL_VERSION);
            // Still reply so the client can surface the mismatch and stop cleanly instead of
            // waiting for a manifest that will never come.
            sendTo(player, new HelloAckPacket(PacketHandler.PROTOCOL_VERSION, Config.rateLimitKBps));
            return;
        }
        JourneySync.LOG.info(
            "Player {} joined with JourneySync {} (JourneyMap present: {})",
            player.getCommandSenderName(),
            hello.modVersion,
            hello.hasJourneyMap);
        // Hello arrives after login, so this is also the first scope assignment.
        assignScope(player);
        sendTo(player, new HelloAckPacket(PacketHandler.PROTOCOL_VERSION, Config.rateLimitKBps));
        sendManifest(player);
        sendWaypoints(player);
    }

    /** Batch size is bounded at the wire level by {@link TileRequestPacket#MAX_BATCH}. */
    public synchronized void onTileRequest(EntityPlayerMP player, List<String> paths) {
        SyncScope own = scope(scopeOf(player));
        SyncScope global = scope(ScopeKeys.GLOBAL);
        for (String path : paths) {
            TileKey key = TileKey.parse(path);
            if (key == null) {
                continue;
            }
            byte[] data;
            try {
                // The merged manifest lists own-scope entries first, so serve those bytes when
                // present; the global scope is the baseline fallback.
                data = own.tileFiles.exists(key) ? own.tileFiles.read(key) : global.tileFiles.read(key);
            } catch (Exception e) {
                JourneySync.LOG.debug("Requested tile missing: {}", path);
                continue;
            }
            sendTile(player, key, data);
        }
    }

    public synchronized void onUploadBegin(EntityPlayerMP player, TileTransferBegin msg) {
        TileKey key = TileKey.parse(msg.relPath);
        if (key == null || msg.totalBytes <= 0 || msg.totalBytes > Config.maxTileBytes) {
            JourneySync.LOG.warn(
                "Rejecting upload begin from {}: bad path {} or size {}",
                player.getCommandSenderName(),
                msg.relPath,
                msg.totalBytes);
            sendTo(player, new TileTransferAck(msg.transferId, TileTransferAck.REJECTED));
            return;
        }
        Map<Integer, IncomingTransfer> byId = uploads.get(player.getUniqueID());
        if (byId == null) {
            byId = new HashMap<Integer, IncomingTransfer>();
            uploads.put(player.getUniqueID(), byId);
        }
        byId.put(
            msg.transferId,
            new IncomingTransfer(msg.transferId, msg.relPath, msg.sha1, msg.totalBytes, msg.chunkSize));
    }

    public synchronized void onUploadData(EntityPlayerMP player, TileChunk msg) {
        Map<Integer, IncomingTransfer> byId = uploads.get(player.getUniqueID());
        if (byId == null) {
            return;
        }
        IncomingTransfer transfer = byId.get(msg.transferId);
        if (transfer == null) {
            return;
        }
        if (!transfer.addChunk(msg.seq, msg.data)) {
            return; // not complete yet
        }
        byId.remove(msg.transferId);
        finishUpload(player, transfer);
    }

    private void finishUpload(EntityPlayerMP player, IncomingTransfer transfer) {
        if (!transfer.verifySha1()) {
            JourneySync.LOG.warn("Upload hash mismatch from {}: {}", player.getCommandSenderName(), transfer.relPath);
            sendTo(player, new TileTransferAck(transfer.transferId, TileTransferAck.REJECTED));
            return;
        }
        TileKey key = TileKey.parse(transfer.relPath);
        byte[] data = transfer.getData();
        String uploaderScope = scopeOf(player);
        SyncScope own = scope(uploaderScope);

        // The shared map is incremental: merge the upload into the stored tile (union of explored
        // pixels) instead of overwriting it. A partial or stale render can then never shrink the
        // shared map, and clients always receive the merged accumulation of everyone's renders.
        try {
            if (own.tileFiles.exists(key)) {
                byte[] stored = own.tileFiles.read(key);
                byte[] merged = PixelMerge.mergePng(stored, data);
                if (merged != null) {
                    data = merged;
                }
            }
        } catch (Exception e) {
            JourneySync.LOG.warn("Tile merge failed for {}, storing upload as-is", key.relPath(), e);
        }

        String sha1 = Hashing.sha1Hex(data);

        TileEntry existing = own.manifest.tiles.get(key.relPath());
        if (existing != null && existing.sha1.equals(sha1)) {
            sendTo(
                player,
                new TileTransferAck(transfer.transferId, TileTransferAck.NOTHING_NEW, existing.sha1, existing.size));
            return;
        }

        try {
            own.tileFiles.writeAtomic(key, data);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed storing tile {}", key.relPath(), e);
            sendTo(player, new TileTransferAck(transfer.transferId, TileTransferAck.REJECTED));
            return;
        }

        own.manifest.tiles.put(key.relPath(), new TileEntry(sha1, data.length));
        dirty = true;
        sendTo(player, new TileTransferAck(transfer.transferId, TileTransferAck.OK, sha1, data.length));

        // Broadcast to every player whose visible scopes contain the uploader's scope.
        broadcastTile(key, data, uploaderScope, player);
    }

    public synchronized void onDownloadAck(EntityPlayerMP player, TileTransferAck msg) {
        if (msg.code != TileTransferAck.OK && Config.logDebug) {
            JourneySync.LOG.debug("Tile download failed for {}", player.getCommandSenderName());
        }
    }

    public synchronized void onWaypointChange(EntityPlayerMP player, WaypointRecord record) {
        // Server-side mirror of the client filter: a non-deletion death waypoint never enters a
        // scope store (keeps modified/old clients from leaking them). Tombstones still apply so a
        // previously synced record can be deleted.
        if (!Config.shareDeathWaypoints && record != null && !record.deleted && record.isDeath()) {
            return;
        }
        String uploaderScope = scopeOf(player);
        // Deletions must land where the live record actually sits: a record synced under an older
        // scope model may live in global (or an old team) while the player has moved on. Own scope
        // first, then global; anything else falls back to own (apply() rejects unknown ids there).
        String targetScope = uploaderScope;
        if (record.deleted && !scope(uploaderScope).waypointStore.has(record.id)
            && scope(ScopeKeys.GLOBAL).waypointStore.has(record.id)) {
            targetScope = ScopeKeys.GLOBAL;
        }
        WaypointRecord accepted = scope(targetScope).waypointStore.apply(
            record,
            player.getUniqueID()
                .toString(),
            isOp(player),
            waypointEpoch::next);
        if (accepted != null) {
            dirty = true; // persisted with the next periodic save
            broadcastWaypoints(targetScope, null);
        }
    }

    public synchronized void onPlayerLogout(EntityPlayerMP player) {
        UUID uuid = player.getUniqueID();
        uploads.remove(uuid);
        lastSentTransferId.remove(uuid);
        playerScopes.remove(uuid);
        pendingScopeCheck.remove(uuid);
    }

    /** Called from the server tick handler: pending scope re-checks, then periodic persistence. */
    public synchronized void tick() {
        drainScopeRechecks();
        long now = System.currentTimeMillis();
        if (dirty && now - lastSaveMillis > 30_000) {
            saveNow();
        }
    }

    private void saveNow() {
        lastSaveMillis = System.currentTimeMillis();
        dirty = false;
        for (SyncScope s : scopes.values()) {
            s.save();
        }
        if (waypointEpoch != null) {
            waypointEpoch.save();
        }
    }

    /**
     * Relocate pre-scope storage into {@code scopes/global/}: the old server-wide data becomes the
     * global scope, so existing worlds keep working with zero behavior change.
     */
    private void migrateLegacyStorage() {
        File globalDir = new File(scopesDir, ScopeKeys.GLOBAL);
        String[] legacy = { "tiles", "manifest.json", "waypoints.json" };
        for (String name : legacy) {
            File src = new File(dataDir, name);
            File dst = new File(globalDir, name);
            if (src.exists()) {
                if (dst.exists()) {
                    JourneySync.LOG.warn("Legacy {} kept in place; global scope already has its own {}", src, dst);
                } else {
                    moveQuietly(src.toPath(), dst.toPath());
                    JourneySync.LOG.info("Migrated legacy {} into global scope {}", src, dst);
                }
            }
        }
    }

    private static void moveQuietly(Path src, Path dst) {
        try {
            File parent = dst.getParent()
                .toFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                JourneySync.LOG.error("Cannot create {}", parent);
                return;
            }
            try {
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(src, dst);
            }
        } catch (Exception e) {
            JourneySync.LOG.error("Failed moving {} to {}", src, dst, e);
        }
    }

    // ------------------------------------------------------------------ sending

    /** The two scopes a player may read: global baseline plus their own scope. */
    private Set<String> visibleScopes(EntityPlayerMP player) {
        Set<String> visible = new LinkedHashSet<String>();
        visible.add(ScopeKeys.GLOBAL);
        visible.add(scopeOf(player));
        return visible;
    }

    private Manifest mergedManifest(EntityPlayerMP player) {
        String own = scopeOf(player);
        if (ScopeKeys.GLOBAL.equals(own)) {
            return scope(ScopeKeys.GLOBAL).manifest;
        }
        return ScopeMerge.mergeManifests(scope(ScopeKeys.GLOBAL).manifest, scope(own).manifest);
    }

    private String mergedWaypointsJson(EntityPlayerMP player) {
        String own = scopeOf(player);
        Map<String, WaypointRecord> merged = ScopeKeys.GLOBAL.equals(own)
            ? ScopeMerge.mergeWaypoints(scope(ScopeKeys.GLOBAL).waypointStore.allRecords(), null)
            : ScopeMerge.mergeWaypoints(
                scope(ScopeKeys.GLOBAL).waypointStore.allRecords(),
                scope(own).waypointStore.allRecords());
        return ServerWaypointStore.toJson(waypointEpoch.current(), merged.values());
    }

    private void sendManifest(EntityPlayerMP player) {
        sendBlob(player, PacketHandler.BLOB_MANIFEST, mergedManifest(player).toJson());
    }

    private void sendWaypoints(EntityPlayerMP player) {
        sendBlob(player, PacketHandler.BLOB_WAYPOINTS_FULL, mergedWaypointsJson(player));
    }

    /**
     * Push fresh merged waypoint blobs to every online player who can see {@code scopeKey}
     * (everyone for global changes, just that scope's members otherwise).
     *
     * @param except player to skip (already being pushed by the caller), or null
     */
    private void broadcastWaypoints(String scopeKey, EntityPlayerMP except) {
        for (Object o : players()) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player == except || !visibleScopes(player).contains(scopeKey)) {
                continue;
            }
            sendWaypoints(player);
        }
    }

    private void sendBlob(EntityPlayerMP player, byte type, String json) {
        List<BlobPacket> packets = BlobPacket.split(type, BlobPacket.gzip(json));
        for (BlobPacket p : packets) {
            sendTo(player, p);
        }
    }

    private void sendTile(EntityPlayerMP player, TileKey key, byte[] data) {
        String sha1 = Hashing.sha1Hex(data);
        int chunkSize = TileChunking.effectiveChunkSize(Config.chunkSizeBytes);
        int transferId = nextTransferId(player);
        sendTo(player, new TileTransferBegin(transferId, key.relPath(), sha1, data.length, chunkSize));
        TileChunking
            .sendChunks(data, chunkSize, transferId, (id, seq, chunk) -> sendTo(player, new TileChunk(id, seq, chunk)));
    }

    private int nextTransferId(EntityPlayerMP player) {
        Integer last = lastSentTransferId.get(player.getUniqueID());
        int id = last == null ? 1 : last + 1;
        lastSentTransferId.put(player.getUniqueID(), id);
        return id;
    }

    /**
     * Broadcast a changed tile to every player whose visible scopes contain the uploader's scope:
     * all players for global writes, only teammates for a team scope, nobody for solo.
     */
    private void broadcastTile(TileKey key, byte[] data, String uploaderScope, EntityPlayerMP uploader) {
        for (Object o : players()) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player == uploader || !visibleScopes(player).contains(uploaderScope)) {
                continue;
            }
            sendTile(player, key, data);
        }
    }

    private List<?> players() {
        return net.minecraft.server.MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
    }

    private void sendTo(EntityPlayerMP player, IMessage msg) {
        PacketHandler.wrapper.sendTo(msg, player);
    }

    private static boolean isOp(EntityPlayerMP player) {
        return net.minecraft.server.MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152596_g(player.getGameProfile());
    }
}
