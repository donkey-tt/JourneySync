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
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.HelloAckPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.TileBeginPacket;
import com.donkeytt.journeysync.network.packets.TileDataPacket;
import com.donkeytt.journeysync.network.packets.TileUploadAckPacket;

/**
 * Server-side aggregation point. Owns the on-disk tile store and manifest, receives uploads,
 * serves downloads and broadcasts changes. All handler entry points are synchronized; handlers run
 * on Netty worker threads.
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

    private long lastSaveMillis;
    private boolean dirty;

    private ServerSyncManager() {}

    /** Called once per world load from the server proxy. */
    public synchronized void init(File worldDir) {
        this.dataDir = new File(worldDir, "journeysync");
        this.tileFiles = new TileFiles(new File(dataDir, "tiles"));
        this.manifest = Manifest.fromJson(readStringQuietly(new File(dataDir, "manifest.json")));
        this.waypointStore = new ServerWaypointStore(new File(dataDir, "waypoints.json"));
        this.uploads.clear();
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

    public void onHello(EntityPlayerMP player, HelloPacket hello) {
        if (hello.protocolVersion != PacketHandler.PROTOCOL_VERSION) {
            JourneySync.LOG.warn(
                "Player {} uses JourneySync protocol {}, server expects {} - ignoring",
                player.getCommandSenderName(),
                hello.protocolVersion,
                PacketHandler.PROTOCOL_VERSION);
            return;
        }
        if (!hello.hasJourneyMap) {
            JourneySync.LOG.info(
                "Player {} has JourneySync but no JourneyMap; sync will be inactive on their client",
                player.getCommandSenderName());
        }
        sendTo(
            player,
            new HelloAckPacket(
                PacketHandler.PROTOCOL_VERSION,
                manifest.epoch,
                waypointStore.epoch,
                Config.rateLimitKBps));
        sendManifest(player);
        sendWaypoints(player);
    }

    public void onTileRequest(EntityPlayerMP player, List<String> paths) {
        int sent = 0;
        for (String path : paths) {
            if (sent++ >= tileRequestLimit()) {
                break;
            }
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

    private int tileRequestLimit() {
        return 64;
    }

    public void onTileUploadBegin(EntityPlayerMP player,
        com.donkeytt.journeysync.network.packets.TileUploadBeginPacket msg) {
        TileKey key = TileKey.parse(msg.relPath);
        if (key == null || msg.totalBytes <= 0 || msg.totalBytes > Config.maxTileBytes) {
            JourneySync.LOG.warn(
                "Rejecting upload begin from {}: bad path {} or size {}",
                player.getCommandSenderName(),
                msg.relPath,
                msg.totalBytes);
            sendTo(player, new TileUploadAckPacket(msg.transferId, 2));
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

    public void onTileUploadData(EntityPlayerMP player,
        com.donkeytt.journeysync.network.packets.TileUploadDataPacket msg) {
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
            sendTo(player, new TileUploadAckPacket(transfer.transferId, 2));
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
            sendTo(player, new TileUploadAckPacket(transfer.transferId, 1)); // duplicate (or nothing new after merge)
            return;
        }

        try {
            tileFiles.writeAtomic(key, data);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed storing tile {}", key.relPath(), e);
            sendTo(player, new TileUploadAckPacket(transfer.transferId, 2));
            return;
        }

        manifest.tiles.put(
            key.relPath(),
            new TileEntry(
                sha1,
                System.currentTimeMillis(),
                data.length,
                player.getUniqueID()
                    .toString()));
        manifest.epoch++;
        dirty = true;
        sendTo(player, new TileUploadAckPacket(transfer.transferId, 0));

        // Broadcast to everyone else.
        broadcastTile(key, data, player);
    }

    public void onTileAck(EntityPlayerMP player, com.donkeytt.journeysync.network.packets.TileAckPacket msg) {
        if (!msg.ok && Config.logDebug) {
            JourneySync.LOG.debug("Tile download failed for {}", player.getCommandSenderName());
        }
    }

    public void onWaypointChange(EntityPlayerMP player, WaypointRecord record) {
        WaypointRecord accepted = waypointStore.apply(
            record,
            player.getUniqueID()
                .toString(),
            isOp(player));
        waypointStore.save();
        if (accepted != null) {
            broadcastWaypoints();
        }
    }

    public synchronized void onPlayerLogout(EntityPlayerMP player) {
        uploads.remove(player.getUniqueID());
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
        int chunkSize = Math.min(Config.chunkSizeBytes, BlobPacket.CHUNK_SIZE);
        int chunks = (data.length + chunkSize - 1) / chunkSize;
        sendTo(player, new TileBeginPacket(nextTransferId(player), key.relPath(), sha1, data.length, chunkSize));
        int transferId = lastSentTransferId.get(player.getUniqueID());
        for (int seq = 0; seq < chunks; seq++) {
            int off = seq * chunkSize;
            int len = Math.min(chunkSize, data.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(data, off, chunk, 0, len);
            sendTo(player, new TileDataPacket(transferId, seq, chunk));
        }
    }

    /** server-side per-player outgoing transfer id counter */
    private final Map<UUID, Integer> lastSentTransferId = new HashMap<UUID, Integer>();

    private int nextTransferId(EntityPlayerMP player) {
        int id = lastSentTransferId.containsKey(player.getUniqueID()) ? lastSentTransferId.get(player.getUniqueID()) + 1
            : 1;
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

    private void sendTo(EntityPlayerMP player, cpw.mods.fml.common.network.simpleimpl.IMessage msg) {
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
