package com.donkeytt.journeysync.server;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.donkeytt.journeysync.io.TileFiles;
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
 * Server-side aggregation point. Owns the on-disk tile store and manifest, receives uploads,
 * serves downloads and broadcasts changes. Handlers run on Netty worker threads; every entry
 * point is synchronized on this instance, which serializes all state access without putting any
 * of the work onto the server main thread.
 */
public class ServerSyncManager {

    private static final ServerSyncManager INSTANCE = new ServerSyncManager();

    public static ServerSyncManager instance() {
        return INSTANCE;
    }

    private File dataDir;
    private TileFiles tileFiles;
    private Manifest manifest;
    private ServerWaypointStore waypointStore;

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
        this.tileFiles = new TileFiles(new File(dataDir, "tiles"));
        this.manifest = Manifest.fromJson(readStringQuietly(new File(dataDir, "manifest.json")));
        this.waypointStore = new ServerWaypointStore(new File(dataDir, "waypoints.json"));
        this.uploads.clear();
        this.lastSentTransferId.clear();
        this.lastSaveMillis = System.currentTimeMillis();
        this.dirty = false;
        JourneySync.LOG.info(
            "JourneySync server storage initialized at {} ({} tiles, {} waypoints)",
            dataDir,
            manifest.tiles.size(),
            waypointStore.allRecords()
                .size());
    }

    public synchronized void shutdown() {
        saveNow();
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
        sendTo(player, new HelloAckPacket(PacketHandler.PROTOCOL_VERSION, Config.rateLimitKBps));
        sendManifest(player);
        sendWaypoints(player);
    }

    /** Batch size is bounded at the wire level by {@link TileRequestPacket#MAX_BATCH}. */
    public synchronized void onTileRequest(EntityPlayerMP player, List<String> paths) {
        for (String path : paths) {
            TileKey key = TileKey.parse(path);
            if (key == null) {
                continue;
            }
            byte[] data;
            try {
                data = tileFiles.read(key);
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

        // The shared map is incremental: merge the upload into the stored tile (union of explored
        // pixels) instead of overwriting it. A partial or stale render can then never shrink the
        // shared map, and clients always receive the merged accumulation of everyone's renders.
        try {
            if (tileFiles.exists(key)) {
                byte[] stored = tileFiles.read(key);
                byte[] merged = PixelMerge.mergePng(stored, data);
                if (merged != null) {
                    data = merged;
                }
            }
        } catch (Exception e) {
            JourneySync.LOG.warn("Tile merge failed for {}, storing upload as-is", key.relPath(), e);
        }

        String sha1 = Hashing.sha1Hex(data);

        TileEntry existing = manifest.tiles.get(key.relPath());
        if (existing != null && existing.sha1.equals(sha1)) {
            sendTo(
                player,
                new TileTransferAck(transfer.transferId, TileTransferAck.NOTHING_NEW, existing.sha1, existing.size));
            return;
        }

        try {
            tileFiles.writeAtomic(key, data);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed storing tile {}", key.relPath(), e);
            sendTo(player, new TileTransferAck(transfer.transferId, TileTransferAck.REJECTED));
            return;
        }

        manifest.tiles.put(key.relPath(), new TileEntry(sha1, data.length));
        dirty = true;
        sendTo(player, new TileTransferAck(transfer.transferId, TileTransferAck.OK, sha1, data.length));

        // Broadcast to everyone else.
        broadcastTile(key, data, player);
    }

    public synchronized void onDownloadAck(EntityPlayerMP player, TileTransferAck msg) {
        if (msg.code != TileTransferAck.OK && Config.logDebug) {
            JourneySync.LOG.debug("Tile download failed for {}", player.getCommandSenderName());
        }
    }

    public synchronized void onWaypointChange(EntityPlayerMP player, WaypointRecord record) {
        WaypointRecord accepted = waypointStore.apply(
            record,
            player.getUniqueID()
                .toString(),
            isOp(player));
        if (accepted != null) {
            dirty = true; // persisted with the next periodic save
            broadcastWaypoints();
        }
    }

    public synchronized void onPlayerLogout(EntityPlayerMP player) {
        uploads.remove(player.getUniqueID());
        lastSentTransferId.remove(player.getUniqueID());
    }

    /** Called from the server tick handler: periodic persistence. */
    public synchronized void tick() {
        long now = System.currentTimeMillis();
        if (dirty && now - lastSaveMillis > 30_000) {
            saveNow();
        }
    }

    private void saveNow() {
        lastSaveMillis = System.currentTimeMillis();
        dirty = false;
        File parent = dataDir;
        if (!parent.isDirectory() && !parent.mkdirs()) {
            JourneySync.LOG.error("Cannot create data dir {}", dataDir);
            return;
        }
        writeStringQuietly(new File(dataDir, "manifest.json"), manifest.toJson());
        waypointStore.save();
    }

    // ------------------------------------------------------------------ sending

    private void sendManifest(EntityPlayerMP player) {
        sendBlob(player, PacketHandler.BLOB_MANIFEST, manifest.toJson());
    }

    private void sendWaypoints(EntityPlayerMP player) {
        sendBlob(player, PacketHandler.BLOB_WAYPOINTS_FULL, waypointStore.toJson());
    }

    private void broadcastWaypoints() {
        String json = waypointStore.toJson();
        for (Object o : players()) {
            sendBlob((EntityPlayerMP) o, PacketHandler.BLOB_WAYPOINTS_FULL, json);
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

    private void broadcastTile(TileKey key, byte[] data, EntityPlayerMP except) {
        for (Object o : players()) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player == except) {
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

    private static String readStringQuietly(File f) {
        try {
            if (f.isFile()) {
                return new String(
                    java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            JourneySync.LOG.error("Failed reading {}", f, e);
        }
        return "";
    }

    private static void writeStringQuietly(File f, String s) {
        try {
            java.nio.file.Files.write(f.toPath(), s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            JourneySync.LOG.error("Failed writing {}", f, e);
        }
    }
}
