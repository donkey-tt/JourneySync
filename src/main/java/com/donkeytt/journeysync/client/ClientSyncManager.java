package com.donkeytt.journeysync.client;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.Tags;
import com.donkeytt.journeysync.client.QuietPeriods.Type;
import com.donkeytt.journeysync.data.Manifest;
import com.donkeytt.journeysync.data.PixelMerge;
import com.donkeytt.journeysync.data.TileEntry;
import com.donkeytt.journeysync.data.TileKey;
import com.donkeytt.journeysync.io.Hashing;
import com.donkeytt.journeysync.io.TileFiles;
import com.donkeytt.journeysync.network.IncomingTransfer;
import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.network.RateLimiter;
import com.donkeytt.journeysync.network.TileChunking;
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.HelloAckPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.TileChunk;
import com.donkeytt.journeysync.network.packets.TileRequestPacket;
import com.donkeytt.journeysync.network.packets.TileTransferAck;
import com.donkeytt.journeysync.network.packets.TileTransferBegin;
import com.donkeytt.journeysync.network.packets.WaypointChangePacket;

/**
 * Client-side sync engine: a state machine driven by one worker thread. Network handlers (Netty
 * threads) only enqueue events; everything else - scanning, guard decisions, transfers, waypoint
 * and shadow IO - happens on the worker. UI-facing side effects (chat, JM refreshes) are deferred
 * to the client tick via flags.
 *
 * <p>
 * The per-concern collaborators: {@link LocalTileIndex} (disk view), {@link UploadGuards} (pure
 * upload decisions), {@link QuietPeriods} (debounce windows), {@link RenderUnionCache} (pixel
 * union), {@link ShadowStore} (server reference bytes), {@link TileWatcher} (immediate file
 * events), {@link WaypointSync} (waypoints), {@link JmBridge} (JourneyMap reflection).
 * </p>
 */
public class ClientSyncManager {

    private static final ClientSyncManager INSTANCE = new ClientSyncManager();

    public static ClientSyncManager instance() {
        return INSTANCE;
    }

    private enum Phase {
        DISCONNECTED,
        AWAIT_MANIFEST,
        DIFFING,
        TRANSFERRING,
        WATCHING
    }

    private static final long STATE_FLUSH_MILLIS = 2_000;

    /**
     * A tile JourneyMap wrote more recently than this is considered actively owned by it; both
     * restores into it and targeted texture pushes are deferred (see {@link #enqueueRestore} and
     * {@link #applyInboundTile}). Must exceed JM's ~31s re-render cadence so a continuously
     * re-rendered tile stays deferred for the whole episode.
     */
    private static final long LOCAL_WRITER_GRACE_MILLIS = 60_000;

    // --- connection state ---
    private volatile Phase phase = Phase.DISCONNECTED;
    private volatile boolean paused;
    private String serverId = "unknown";
    private volatile boolean helloSent;

    // --- worker ---
    private final BlockingQueue<Object> events = new LinkedBlockingQueue<Object>();
    private Thread worker;
    private final Object workerLock = new Object();

    // --- sync state (worker-thread only unless marked volatile) ---
    private Manifest serverManifest;
    private ClientState state;
    private File stateFile;
    private ShadowStore shadowStore;
    private WaypointSync waypoints;
    private File jmDir;
    private TileFiles jmTiles;

    private final LocalTileIndex localTiles = new LocalTileIndex();
    private final Set<String> failedTiles = new HashSet<String>();

    /** relPath -> when JourneyMap last wrote the tile (any file change not authored by us). */
    private final Map<String, Long> jmWriteAt = new HashMap<String, Long>();
    private final Deque<String> downloadQueue = new ArrayDeque<String>();
    private final Deque<String> uploadQueue = new ArrayDeque<String>();

    /** Server-initiated transfers being reassembled (pushes and pull responses). */
    private final Map<Integer, IncomingTransfer> pushedTransfers = new HashMap<Integer, IncomingTransfer>();
    private final Map<Byte, BlobAssembly> blobs = new HashMap<Byte, BlobAssembly>();

    /**
     * Full-waypoint blob received before the JM directory was resolved. Unlike the manifest (a
     * plain field), applying waypoints touches the filesystem, so it must wait for jmDir - and
     * silently writing into the process CWD is not an acceptable fallback.
     */
    private String pendingWaypointBlob;

    private RateLimiter rateLimiter = new RateLimiter(0);

    /** Debounces watch-phase uploads until a drifting tile's file stat has settled. */
    private UploadSettler uploadSettler = new UploadSettler(0);

    private final QuietPeriods quiets = new QuietPeriods();
    private final RenderUnionCache renderCache = new RenderUnionCache();
    private final TileWatcher tileWatcher = new TileWatcher();

    private int clientTransferId;

    /**
     * Regions (32x32 chunk squares) JourneyMap should re-render, queued by the rerender command.
     * Entries are [dimension, minChunkX, minChunkZ, night?1:0]; drained one at a time onto JM's
     * task system, which ignores a new region task while one is still enabled.
     */
    private final Deque<int[]> remapQueue = new ArrayDeque<int[]>();
    private int remapTick;
    private static final int REMAP_POLL_TICKS = 10; // ~0.5s between busy-flag polls

    // progress
    private int totalToDownload;
    private int downloaded;
    private int totalToUpload;
    private int uploaded;
    private long lastProgressChat;
    private long lastScanMillis;
    private boolean announcedComplete;

    // state persistence (flushed periodically, not per tile)
    private boolean stateDirty;
    private long lastStateFlush;

    // tick flags (worker -> client tick)
    private volatile boolean needsWaypointReset;
    private final BlockingQueue<String> chatQueue = new LinkedBlockingQueue<String>();

    private ClientSyncManager() {}

    // ------------------------------------------------------------------ lifecycle

    public void onConnect() {
        tileWatcher.stop();
        phase = Phase.AWAIT_MANIFEST;
        announcedComplete = false;
        serverManifest = null;
        blobs.clear();
        pushedTransfers.clear();
        downloadQueue.clear();
        uploadQueue.clear();
        failedTiles.clear();
        localTiles.clear();
        quiets.clear();
        renderCache.clear();
        jmWriteAt.clear();
        remapQueue.clear();
        pendingWaypointBlob = null;
        jmDir = null;
        jmTiles = null;

        serverId = computeServerId();
        helloSent = false;
        File configDir = new File(Minecraft.getMinecraft().mcDataDir, "config/journeysync");
        stateFile = new File(configDir, "state-" + serverId + ".json");
        state = ClientState.load(stateFile);
        stateDirty = false;
        lastStateFlush = System.currentTimeMillis();
        shadowStore = new ShadowStore(new File(configDir, "shadow/" + serverId));
        waypoints = new WaypointSync(
            state,
            record -> PacketHandler.wrapper.sendToServer(new WaypointChangePacket(record)));

        // The rate limiter is rebuilt on HelloAck, once the server's effective config is known;
        // until then (and after a protocol-mismatch disconnect) no transfer can start anyway.
        uploadSettler = new UploadSettler(Config.uploadSettleSeconds * 1000L);

        startWorker();

        // The client may tick before the server login completes, but never vice versa: defer the
        // Hello to the first tick where the player exists, so the server is guaranteed past login
        // (sending during ClientConnectedToServerEvent is silently dropped by FML mid-handshake).
        JourneySync.LOG.info("Connected to server (serverId={}), waiting for join to send Hello", serverId);
    }

    public void onDisconnect() {
        phase = Phase.DISCONNECTED;
        flushState();
    }

    private void startWorker() {
        synchronized (workerLock) {
            if (worker != null && worker.isAlive()) {
                return;
            }
            worker = new Thread(new Runnable() {

                @Override
                public void run() {
                    workerLoop();
                }
            }, "JourneySync-Worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void workerLoop() {
        while (true) {
            try {
                tickWorker();
            } catch (Throwable t) {
                JourneySync.LOG.error("Worker loop error", t);
            }
        }
    }

    // ------------------------------------------------------------------ worker

    private void tickWorker() throws InterruptedException {
        flushStateIfDue(System.currentTimeMillis());
        if (phase == Phase.DISCONNECTED) {
            events.poll(500, TimeUnit.MILLISECONDS);
            return;
        }
        if (paused) {
            events.poll(200, TimeUnit.MILLISECONDS);
            return;
        }

        Object ev = events.poll(100, TimeUnit.MILLISECONDS);
        long now = System.currentTimeMillis();

        if (ev != null) {
            dispatch(ev);
        }

        switch (phase) {
            case AWAIT_MANIFEST:
                break;
            case DIFFING:
                if (serverManifest != null && ensureJmDir()) {
                    scanLocal();
                    computeDiff();
                    phase = Phase.TRANSFERRING;
                    lastProgressChat = 0;
                }
                break;
            case TRANSFERRING:
            case WATCHING:
                if (!ensureJmDir()) {
                    break;
                }
                if (pendingWaypointBlob != null) {
                    String blob = pendingWaypointBlob;
                    pendingWaypointBlob = null;
                    applyWaypointBlob(blob);
                }
                String job = downloadQueue.poll();
                if (job != null) {
                    doDownload(job);
                    break;
                }
                job = uploadQueue.poll();
                if (job != null) {
                    doUpload(job);
                    break;
                }
                waypoints.flush();
                if (phase == Phase.TRANSFERRING && downloadQueue.isEmpty() && uploadQueue.isEmpty()) {
                    phase = Phase.WATCHING;
                    if (!announcedComplete) {
                        announcedComplete = true;
                        flushState();
                        chatQueue.offer("journeysync.chat.sync_complete");
                    }
                }
                if (phase == Phase.WATCHING
                    && (now - lastScanMillis > Config.scanIntervalSeconds * 1000L || tileWatcher.hasPending())) {
                    incrementalScan();
                }
                maybeProgressChat(now);
                break;
            default:
                break;
        }
    }

    /**
     * The single consumer for inbound events: server pushes, upload acks, handshake replies and
     * blob fragments all run here, on the worker.
     */
    private void dispatch(Object ev) {
        if (ev instanceof TileTransferBegin) {
            TileTransferBegin begin = (TileTransferBegin) ev;
            pushedTransfers.put(
                begin.transferId,
                new IncomingTransfer(begin.transferId, begin.relPath, begin.sha1, begin.totalBytes, begin.chunkSize));
        } else if (ev instanceof TileChunk) {
            TileChunk data = (TileChunk) ev;
            IncomingTransfer transfer = pushedTransfers.get(data.transferId);
            if (transfer != null && transfer.addChunk(data.seq, data.data)) {
                pushedTransfers.remove(data.transferId);
                finishPushedDownload(transfer);
            }
        } else if (ev instanceof TileTransferAck) {
            // Late/unknown acks: the active upload wait-loop consumes matching acks directly from
            // the event queue; anything reaching here is stale and can be dropped.
        } else if (ev instanceof HelloAckPacket) {
            handleHelloAck((HelloAckPacket) ev);
        } else if (ev instanceof BlobPacket) {
            handleBlobFragment((BlobPacket) ev);
        } else if (ev instanceof WaypointBlobEvent) {
            String json = ((WaypointBlobEvent) ev).json;
            if (jmDir == null) {
                pendingWaypointBlob = json;
            } else {
                applyWaypointBlob(json);
            }
        }
    }

    private void handleHelloAck(HelloAckPacket msg) {
        if (msg.protocolVersion != PacketHandler.PROTOCOL_VERSION) {
            chatQueue.offer("journeysync.chat.version_mismatch");
            phase = Phase.DISCONNECTED;
            return;
        }
        int effectiveLimit = Config.rateLimitKBps > 0 ? Config.rateLimitKBps : msg.rateLimitKBps;
        rateLimiter = new RateLimiter(effectiveLimit > 0 ? effectiveLimit * 1024L : 0);
        phase = Phase.DIFFING;
    }

    private void handleBlobFragment(BlobPacket packet) {
        BlobAssembly assembly = blobs.computeIfAbsent(packet.blobType, k -> new BlobAssembly());
        assembly.total = packet.total;
        if (assembly.parts.put(packet.seq, packet.data) == null) {
            assembly.receivedBytes += packet.data.length;
        }
        if (assembly.parts.size() == packet.total) {
            blobs.remove(packet.blobType);
            byte[] joined = new byte[assembly.receivedBytes];
            int off = 0;
            for (int i = 0; i < packet.total; i++) {
                byte[] part = assembly.parts.get(i);
                System.arraycopy(part, 0, joined, off, part.length);
                off += part.length;
            }
            handleBlob(packet.blobType, BlobPacket.gunzip(joined));
        }
    }

    private void handleBlob(byte type, String json) {
        if (type == PacketHandler.BLOB_MANIFEST) {
            serverManifest = Manifest.fromJson(json);
            JourneySync.LOG.info("Received manifest: {} tiles", serverManifest.tiles.size());
        } else if (type == PacketHandler.BLOB_WAYPOINTS_FULL) {
            // Route through the worker: applying writes waypoint files, which requires jmDir -
            // not yet resolved this early after join. dispatch() stashes it until then.
            events.offer(new WaypointBlobEvent(json));
        }
    }

    /** Worker-queue wrapper for a full-waypoint blob (see {@link #pendingWaypointBlob}). */
    private static final class WaypointBlobEvent {

        final String json;

        WaypointBlobEvent(String json) {
            this.json = json;
        }
    }

    private static class BlobAssembly {

        int total;
        final Map<Integer, byte[]> parts = new HashMap<Integer, byte[]>();
        int receivedBytes;
    }

    // ------------------------------------------------------------------ downloads

    private void doDownload(String relPath) throws InterruptedException {
        if (failedTiles.contains(relPath)) {
            return;
        }
        List<String> batch = new ArrayList<String>(1);
        batch.add(relPath);
        PacketHandler.wrapper.sendToServer(new TileRequestPacket(batch));

        long deadline = System.currentTimeMillis() + 60_000;
        IncomingTransfer transfer = null;
        List<Object> stashed = new ArrayList<Object>();

        while (System.currentTimeMillis() < deadline) {
            Object ev = events.poll(500, TimeUnit.MILLISECONDS);
            if (ev == null) {
                continue;
            }
            if (ev instanceof TileTransferBegin && transfer == null
                && ((TileTransferBegin) ev).relPath.equals(relPath)) {
                TileTransferBegin begin = (TileTransferBegin) ev;
                transfer = new IncomingTransfer(
                    begin.transferId,
                    begin.relPath,
                    begin.sha1,
                    begin.totalBytes,
                    begin.chunkSize);
            } else
                if (ev instanceof TileChunk && transfer != null && ((TileChunk) ev).transferId == transfer.transferId) {
                    TileChunk data = (TileChunk) ev;
                    if (transfer.addChunk(data.seq, data.data)) {
                        break;
                    }
                } else {
                    stashed.add(ev);
                }
        }

        for (Object leftover : stashed) {
            dispatch(leftover);
        }

        if (transfer == null || !transfer.isComplete()) {
            JourneySync.LOG.warn("Download timed out: {}", relPath);
            failTile(relPath);
            return;
        }
        if (!transfer.verifySha1()) {
            JourneySync.LOG.warn("Download hash mismatch: {}", relPath);
            failTile(relPath);
            PacketHandler.wrapper.sendToServer(new TileTransferAck(transfer.transferId, TileTransferAck.REJECTED));
            return;
        }

        TileKey key = TileKey.parse(relPath);
        if (key == null) {
            failTile(relPath);
            return;
        }
        try {
            applyInboundTile(key, transfer.getData());
            downloaded++;
            PacketHandler.wrapper.sendToServer(new TileTransferAck(transfer.transferId, TileTransferAck.OK));
            JourneySync.LOG.debug("Applied downloaded tile {} ({} bytes)", relPath, transfer.totalBytes);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed writing downloaded tile {}", relPath, e);
            failTile(relPath);
        }
    }

    /** A server-initiated transfer (broadcast or restore push) finished reassembling. */
    private void finishPushedDownload(IncomingTransfer transfer) {
        try {
            if (!transfer.verifySha1()) {
                JourneySync.LOG.warn("Pushed tile failed verification: {}", transfer.relPath);
                PacketHandler.wrapper.sendToServer(new TileTransferAck(transfer.transferId, TileTransferAck.REJECTED));
                return;
            }
            TileKey key = TileKey.parse(transfer.relPath);
            if (key == null) {
                return;
            }
            applyInboundTile(key, transfer.getData());
            PacketHandler.wrapper.sendToServer(new TileTransferAck(transfer.transferId, TileTransferAck.OK));
            JourneySync.LOG.debug("Applied pushed tile {} ({} bytes)", transfer.relPath, transfer.totalBytes);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed writing pushed tile {}", transfer.relPath, e);
        }
    }

    /**
     * Shared success path for every inbound tile (requested download or server push): write the
     * JM file and the shadow copy, fold the pixels into the union, record the file's real stat so
     * the next scan's fast path hits, and refresh the live texture.
     */
    private void applyInboundTile(TileKey key, byte[] data) throws IOException {
        String relPath = key.relPath();
        boolean jmWriting = isJmWriting(relPath);
        jmTiles.writeAtomic(key, data);
        shadowStore.write(relPath, data);
        renderCache.accumulate(relPath, data); // an in-flight union upload must not lose these pixels
        quiets.quietUntil(Type.INBOUND, relPath, System.currentTimeMillis() + QuietPeriods.INBOUND_QUIET_MILLIS);
        File file = jmTiles.resolve(key);
        String sha1 = Hashing.sha1Hex(data);
        if (serverManifest != null) {
            // The received bytes ARE the server's stored version (pushes and pull responses both
            // send the server's merged store): refresh the manifest snapshot so the shadow stays a
            // valid pixel-guard reference. Without this the entry goes stale the moment anyone
            // uploads new pixels, the guard goes blind for that tile, and observers echo their
            // JM-clobbered subsets back to the server once per cooldown window.
            serverManifest.tiles.put(relPath, new TileEntry(sha1, data.length));
        }
        noteLocalTile(relPath, sha1, file.lastModified(), file.length());
        state.syncedTiles.put(relPath, new ClientState.TileState(sha1, file.lastModified(), file.length()));
        // Targeted display refresh, only while JourneyMap is NOT actively rendering this tile:
        // a texture push under a live JM render is futile (its next update overwrites the texture
        // from its own master image) and was part of the visible flicker. There is deliberately
        // NO full-cache-clear fallback anymore - that storm re-renders every displayed region
        // from stale masters and was the black flash. Idle tiles pick the new file up through
        // JM's own image-cache expiry within ~15s even without a push.
        if (!jmWriting) {
            JmBridge.refreshTile(jmDir, key, data);
        }
        markStateDirty();
    }

    private void failTile(String relPath) {
        failedTiles.add(relPath);
        if (failedTiles.size() > 500) {
            failedTiles.clear(); // avoid unbounded growth; retried on next join
        }
    }

    // ------------------------------------------------------------------ uploads

    private void doUpload(String relPath) throws InterruptedException {
        TileKey key = TileKey.parse(relPath);
        if (key == null) {
            return;
        }
        File file = jmTiles.resolve(key);
        if (!file.isFile()) {
            return; // vanished; watcher will reconcile
        }
        byte[] current;
        try {
            current = java.nio.file.Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            JourneySync.LOG.error("Failed reading local tile {}", relPath, e);
            failTile(relPath);
            return;
        }

        // The payload is the pixel union of every render accumulated for this tile (the
        // renderCache, fed at scan time) merged with the file as it stands now. A re-render that
        // dropped pixels once its chunks unloaded does not drop them from the payload, and the
        // server-side merge is itself a union - this can never regress the shared map.
        byte[] data = current;
        byte[] cached = renderCache.get(relPath);
        if (cached != null) {
            byte[] merged = PixelMerge.mergePng(cached, current);
            if (merged != null) {
                data = merged;
            }
        }
        String sha1 = Hashing.sha1Hex(data);

        if (serverManifest != null) {
            TileEntry entry = serverManifest.tiles.get(relPath);
            if (entry != null && entry.sha1.equals(sha1)) {
                // Server already holds exactly this payload (e.g. it was pushed back to us).
                adoptServerEntry(relPath, entry, current, file);
                return;
            }
            byte[] reference = shadowStore.reference(relPath, entry);
            if (reference != null) {
                boolean adds = quiets.isQuiet(Type.INBOUND, relPath, System.currentTimeMillis())
                    ? PixelInfo.addsNewPixelsStrict(data, reference)
                    : PixelInfo.addsNewPixels(data, reference);
                if (!adds) {
                    // The accumulated union adds nothing over the server's version: everything we
                    // have rendered is already shared. Adopt the server's entry as our sync point
                    // and drop the union; the file on disk may still be a rotting subset.
                    adoptServerEntry(relPath, entry, current, file);
                    if (PixelInfo.hasNetLoss(current, reference)) {
                        enqueueRestore(relPath);
                        JourneySync.LOG
                            .debug("Union adds no pixels and local render regressed; restoring: {}", relPath);
                    } else {
                        JourneySync.LOG.debug("Skipping upload; union adds no new pixels: {}", relPath);
                    }
                    return;
                }
            }
        }
        if (uploadCooldownActive(relPath, data.length)) {
            // Cosmetic re-render within the per-tile cooldown; drop quietly.
            noteLocalTile(relPath, Hashing.sha1Hex(current), file.lastModified(), file.length());
            uploadSettler.clear(relPath);
            JourneySync.LOG.debug("Upload throttled within cooldown: {}", relPath);
            return;
        }

        int transferId = nextClientTransferId();
        int chunkSize = TileChunking.effectiveChunkSize(Config.chunkSizeBytes);
        PacketHandler.wrapper.sendToServer(new TileTransferBegin(transferId, relPath, sha1, data.length, chunkSize));
        TileChunking.sendChunks(data, chunkSize, transferId, (id, seq, chunk) -> {
            rateLimiter.acquire(chunk.length);
            PacketHandler.wrapper.sendToServer(new TileChunk(id, seq, chunk));
        });

        long deadline = System.currentTimeMillis() + 60_000;
        TileTransferAck ack = null;
        List<Object> stashed = new ArrayList<Object>();
        while (System.currentTimeMillis() < deadline) {
            Object candidate = events.poll(500, TimeUnit.MILLISECONDS);
            if (candidate == null) {
                continue;
            }
            if (candidate instanceof TileTransferAck && ((TileTransferAck) candidate).transferId == transferId) {
                ack = (TileTransferAck) candidate;
                break;
            }
            stashed.add(candidate);
        }
        for (Object leftover : stashed) {
            dispatch(leftover);
        }

        if (ack == null) {
            JourneySync.LOG.warn("Upload timed out: {}", relPath);
            failTile(relPath);
            return;
        }
        if (ack.code == TileTransferAck.OK || ack.code == TileTransferAck.NOTHING_NEW) {
            uploaded++;
            JourneySync.LOG.debug("Uploaded tile {} ({} bytes)", relPath, data.length);
            if (Config.tileUploadCooldownSeconds > 0) {
                quiets.quietUntil(
                    Type.UPLOAD,
                    relPath,
                    System.currentTimeMillis() + Config.tileUploadCooldownSeconds * 1000L);
            }
            renderCache.remove(relPath); // the server now holds everything we had accumulated
            noteLocalTile(relPath, Hashing.sha1Hex(current), file.lastModified(), file.length());
            if (ack.storedSha1 != null && serverManifest != null) {
                // Adopt the server's authoritative entry straight from the ack - it carries what
                // was actually stored, which can differ from our payload after the server-side
                // merge (this also covers the NOTHING_NEW absorbed-by-merge case).
                serverManifest.tiles.put(relPath, new TileEntry(ack.storedSha1, ack.storedSize));
            }
            if (ack.storedSha1 != null && !ack.storedSha1.equals(sha1)) {
                // Absorbed by a merge: the server kept a version different from ours (the union
                // of ours with other clients' pixels). Adopt it as our sync point and pull it so
                // the local disk/display converge to the merged map; otherwise the next scans
                // would re-upload our subset forever.
                state.syncedTiles.put(
                    relPath,
                    new ClientState.TileState(ack.storedSha1, System.currentTimeMillis(), ack.storedSize));
                enqueueRestore(relPath);
            } else {
                state.syncedTiles.put(relPath, new ClientState.TileState(sha1, file.lastModified(), data.length));
                shadowStore.write(relPath, data); // our bytes are now the server-side reference for pixel checks
            }
            markStateDirty();
        } else {
            JourneySync.LOG.warn("Upload rejected by server (code {}): {}", ack.code, relPath);
            failTile(relPath);
        }
    }

    /**
     * Adopt the server's manifest entry as our sync point without uploading: note the file's real
     * stat, record the entry, and stop tracking settle/union state for the tile.
     */
    private void adoptServerEntry(String relPath, TileEntry entry, byte[] current, File file) {
        noteLocalTile(relPath, Hashing.sha1Hex(current), file.lastModified(), file.length());
        state.syncedTiles.put(relPath, new ClientState.TileState(entry.sha1, file.lastModified(), entry.size));
        uploadSettler.clear(relPath);
        renderCache.remove(relPath); // the server already contains everything we rendered
        markStateDirty();
    }

    /** Whether the per-tile upload cooldown currently blocks a candidate of this size. */
    private boolean uploadCooldownActive(String relPath, long size) {
        if (!quiets.isQuiet(Type.UPLOAD, relPath, System.currentTimeMillis())) {
            return false;
        }
        ClientState.TileState recorded = state.syncedTiles.get(relPath);
        return UploadGuards.cooldownBlocks(size, recorded == null ? 0 : recorded.size);
    }

    private int nextClientTransferId() {
        clientTransferId = (clientTransferId + 1) & 0x7fffffff;
        if (clientTransferId == 0) {
            clientTransferId = 1;
        }
        return clientTransferId;
    }

    // ------------------------------------------------------------------ local scanning

    private boolean ensureJmDir() {
        if (jmTiles != null) {
            return true;
        }
        File dir = JmBridge.getJMWorldDir();
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        jmDir = dir;
        jmTiles = new TileFiles(dir);
        tileWatcher.start(dir);
        JourneySync.LOG.info("JourneyMap cache directory: {}", dir);
        return true;
    }

    /** Full scan: hash all tiles, reusing recorded hashes for unchanged files. */
    private void scanLocal() {
        scanTiles();
        waypoints.scan(jmDir);
        lastScanMillis = System.currentTimeMillis();
    }

    /** Incremental scan while watching: watcher events first, then stat-based re-hash. */
    private void incrementalScan() {
        tileWatcher.drain(this::processTileFile);
        scanTiles();
        reconcileWithServer();
        waypoints.scan(jmDir);
        lastScanMillis = System.currentTimeMillis();
    }

    private void scanTiles() {
        File[] dims = jmDir.listFiles();
        if (dims == null) {
            return;
        }
        Set<String> seen = new HashSet<String>();
        for (File dimDir : dims) {
            if (!dimDir.isDirectory() || !dimDir.getName()
                .startsWith("DIM")) {
                continue;
            }
            scanTilesIn(dimDir, dimDir.getName(), seen);
        }
        // Reconcile deletions: drop vanished tiles from local tracking.
        localTiles.retainAll(seen);
        uploadSettler.retainAll(seen);
        quiets.retainAll(seen);
        jmWriteAt.keySet()
            .retainAll(seen);
    }

    private void scanTilesIn(File dir, String prefix, Set<String> seen) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                scanTilesIn(f, prefix + "/" + f.getName(), seen);
                continue;
            }
            String name = f.getName();
            if (!name.endsWith(".png") || name.contains(".sync-conflict")) {
                continue;
            }
            String relPath = prefix + "/" + name;
            if (TileKey.parse(relPath) == null) {
                continue;
            }
            seen.add(relPath);
            processTileFile(f, relPath);
        }
    }

    /**
     * The full per-file guard chain, shared by the directory scan and the file watcher: stat
     * fast path, recorded-stat fast path, hash, then the upload guard chain (or restore, for
     * regressions). Anything that wants a local tile evaluated funnels through here.
     */
    private void processTileFile(File f, String relPath) {
        long mtime = f.lastModified();
        long size = f.length();

        if (localTiles.matches(relPath, mtime, size)) {
            // Stat unchanged since the last scan - but a previously deferred tile may now
            // have stayed stable long enough to upload.
            maybeEnqueueSettled(relPath, mtime, size);
            return; // unchanged
        }
        // Fast path: reuse the recorded synced hash when the file matches the recorded stat.
        ClientState.TileState recorded = state.syncedTiles.get(relPath);
        if (recorded != null && recorded.mtime == mtime && recorded.size == size) {
            noteLocalTile(relPath, recorded.sha1, mtime, size);
            uploadSettler.clear(relPath);
            return;
        }
        // Reaching here means the file changed outside anything we wrote: JourneyMap rendered it.
        jmWriteAt.put(relPath, Long.valueOf(System.currentTimeMillis()));
        try {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            String sha1 = Hashing.sha1Hex(data);
            noteLocalTile(relPath, sha1, mtime, size);
            renderCache.accumulate(relPath, data); // capture happens even when the upload is deferred
            maybeEnqueueUpload(relPath, sha1, mtime, size);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed hashing {}", f, e);
        }
    }

    /**
     * Watch-phase incremental upload: a tile whose content drifted from what we last synced (new
     * exploration or a JM re-render) goes onto the upload queue, unless the server already holds
     * this exact content. When the settle debounce is enabled, the tile must additionally keep an
     * unchanged file stat for the settle period before it is queued, so re-renders while moving
     * collapse into a single upload of the converged tile.
     */
    private void maybeEnqueueUpload(String relPath, String sha1, long mtime, long size) {
        if (!Config.uploadEnabled || serverManifest == null) {
            return;
        }
        if (!passesUploadGuards(relPath, sha1, size)) {
            return;
        }
        if (!uploadSettler.disabled() && !uploadSettler.observe(relPath, mtime, size, System.currentTimeMillis())) {
            if (Config.logDebug) {
                JourneySync.LOG.debug("Tile still settling, upload deferred: {}", relPath);
            }
            return; // file still settling
        }
        if (!uploadQueue.contains(relPath)) {
            uploadQueue.add(relPath);
        }
    }

    /**
     * The shared upload guard chain used by every enqueue path (fresh hash or settled re-check).
     * Returns false when the tile must not upload; each rejection clears the settle tracker.
     * Regressions no longer block the upload - the payload is the accumulated pixel union, which
     * can only add coverage - they only schedule a restore so the rotting local file is refreshed
     * for display.
     */
    private boolean passesUploadGuards(String relPath, String sha1, long size) {
        ClientState.TileState recorded = state.syncedTiles.get(relPath);
        TileEntry serverEntry = serverManifest == null ? null : serverManifest.tiles.get(relPath);
        boolean cooldown = uploadCooldownActive(relPath, size);
        UploadGuards.Verdict verdict = UploadGuards
            .verdict(sha1, size, failedTiles.contains(relPath), recorded, serverEntry, cooldown);
        if (verdict != UploadGuards.Verdict.PROCEED) {
            uploadSettler.clear(relPath);
            return false;
        }
        if (UploadGuards.regresses(serverEntry, recorded, size)) {
            enqueueRestore(relPath); // the file is rotting; the union upload itself is harmless
        }
        return true;
    }

    /**
     * Stability re-check for tiles whose stat did not change since the last scan: a tile deferred
     * by the settle debounce uploads once it has stayed stable for the settle period. Guards are
     * re-validated against the recorded local hash, which still matches the file content here.
     */
    private void maybeEnqueueSettled(String relPath, long mtime, long size) {
        if (!Config.uploadEnabled || serverManifest == null || uploadSettler.disabled()) {
            return;
        }
        if (!uploadSettler.fireIfSettled(relPath, mtime, size, System.currentTimeMillis())) {
            return; // not tracked, not settled yet, or already fired
        }
        String sha1 = localTiles.sha1(relPath);
        if (sha1 == null) {
            return;
        }
        if (!passesUploadGuards(relPath, sha1, size)) {
            return;
        }
        if (!uploadQueue.contains(relPath)) {
            uploadQueue.add(relPath);
        }
    }

    /**
     * Re-download a tile whose local file regressed below a known fuller version. JourneyMap
     * clobbers synced tiles with partial re-renders whenever its region cache is incomplete (our
     * cache clears make it worse); without this, the local view silently rots even though the
     * server stays correct.
     *
     * <p>
     * Activity gate: while the tile file was written within the last {@link
     * #LOCAL_WRITER_GRACE_MILLIS} (JourneyMap is actively re-rendering it - the player is near,
     * or the region is displayed on an open map), restoring is futile: JM's in-memory master
     * image still holds its own version and its next flush overwrites our restore within ~30s,
     * re-triggering the fight on both sides. We stay out of the way; once JM stops writing, the
     * next scan re-detects the regression and one restore lands and sticks.
     * </p>
     */
    private void enqueueRestore(String relPath) {
        if (!Config.downloadEnabled || serverManifest == null || !serverManifest.tiles.containsKey(relPath)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (quiets.isQuiet(Type.RESTORE, relPath, now)) {
            return;
        }
        if (isJmWriting(relPath)) {
            return; // JourneyMap still owns this tile; restoring now would be clobbered right back
        }
        quiets.quietUntil(Type.RESTORE, relPath, now + QuietPeriods.RESTORE_QUIET_MILLIS);
        if (!downloadQueue.contains(relPath)) {
            downloadQueue.add(relPath);
            JourneySync.LOG.debug("Local tile regressed; restoring from server: {}", relPath);
        }
    }

    /**
     * Backstop for tiles whose drift the stat-based scan somehow missed: if the disk hash no
     * longer matches the server's entry, yet the last hash we synced IS the server's entry, the
     * local file silently fell behind (JourneyMap overwrote it without a visible stat change).
     * A strictly smaller file cannot be pending new exploration, so restoring is safe; tiles we
     * genuinely modified are larger than our last sync and are left for the upload path.
     */
    private void reconcileWithServer() {
        if (serverManifest == null || !Config.downloadEnabled || jmTiles == null) {
            return;
        }
        for (Map.Entry<String, TileEntry> e : serverManifest.tiles.entrySet()) {
            String relPath = e.getKey();
            LocalTileIndex.Stat stat = localTiles.stat(relPath);
            if (stat == null || stat.sha1.equals(e.getValue().sha1)) {
                continue;
            }
            ClientState.TileState recorded = state.syncedTiles.get(relPath);
            if (recorded == null || !recorded.sha1.equals(e.getValue().sha1)) {
                continue; // we diverged from the server; the upload path owns this tile
            }
            if (stat.size >= recorded.size) {
                continue; // not smaller than what we synced; not a silent regression
            }
            enqueueRestore(relPath);
        }
    }

    /**
     * Whether the local render adds no pixel information beyond the server-authoritative shadow.
     * JourneyMap re-renders tiles from stale in-memory region data whenever its cache is
     * incomplete; the result is a strict subset of the server's version that would clobber newer
     * shared content on upload. Size alone cannot separate that from compression jitter (a 4%
     * content loss looks like a 4% re-encode), so the decision is pixel-level. While a tile is in
     * its inbound quiet period (a pushed/downloaded version was just applied), color-only changes
     * no longer count - two clients rendering the same chunks differ by dithering noise, and
     * treating that as "new" makes them ping-pong the tile back and forth.
     */
    private boolean addsNoNewPixels(String relPath) {
        TileKey key = TileKey.parse(relPath);
        if (key == null) {
            return false;
        }
        File file = jmTiles.resolve(key);
        if (!file.isFile()) {
            return false;
        }
        try {
            return addsNoNewPixels(relPath, java.nio.file.Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean addsNoNewPixels(String relPath, byte[] localData) {
        byte[] reference = shadowStore
            .reference(relPath, serverManifest == null ? null : serverManifest.tiles.get(relPath));
        if (reference == null) {
            return false;
        }
        if (quiets.isQuiet(Type.INBOUND, relPath, System.currentTimeMillis())) {
            return !PixelInfo.addsNewPixelsStrict(localData, reference);
        }
        return !PixelInfo.addsNewPixels(localData, reference);
    }

    /**
     * Whether JourneyMap wrote this tile recently (it is being actively rendered near the player or displayed on an
     * open map).
     */
    private boolean isJmWriting(String relPath) {
        Long at = jmWriteAt.get(relPath);
        return at != null && System.currentTimeMillis() - at.longValue() < LOCAL_WRITER_GRACE_MILLIS;
    }

    private void noteLocalTile(String relPath, String sha1, long mtime, long size) {
        localTiles.note(relPath, sha1, mtime, size);
    }

    // ------------------------------------------------------------------ diffing

    private void computeDiff() {
        Map<String, String> syncedSha1 = state.syncedSha1Map();
        shadowStore.backfill(serverManifest, localTiles, jmTiles);
        Set<String> toDownload = serverManifest.diffDownload(localTiles.sha1Map(), syncedSha1, localTiles.sizeMap());
        Set<String> toUpload = new HashSet<String>();
        if (Config.uploadEnabled) {
            // One prune pass with every baseline: the join path has no renderCache yet, so a
            // self-regressing or pixel-empty candidate is dropped instead of uploaded (the union
            // payload cannot harm the server, but it would trigger restore churn on ack).
            for (String path : serverManifest.diffUpload(localTiles.sha1Map())) {
                ClientState.TileState recorded = state.syncedTiles.get(path);
                LocalTileIndex.Stat stat = localTiles.stat(path);
                long size = stat == null ? 0 : stat.size;
                if (UploadGuards.regresses(serverManifest.tiles.get(path), recorded, size)) {
                    JourneySync.LOG.debug("Skipping self-regressing upload at join: {}", path);
                    continue;
                }
                if (addsNoNewPixels(path)) {
                    JourneySync.LOG.debug("Skipping stale re-render upload at join (no new pixels): {}", path);
                    continue;
                }
                toUpload.add(path);
            }
        }

        downloadQueue.clear();
        if (Config.downloadEnabled) {
            downloadQueue.addAll(toDownload);
        }
        uploadQueue.clear();
        uploadQueue.addAll(toUpload);

        totalToDownload = downloadQueue.size();
        downloaded = 0;
        totalToUpload = uploadQueue.size();
        uploaded = 0;
        lastProgressChat = System.currentTimeMillis();

        JourneySync.LOG.info(
            "Sync diff: {} to download, {} to upload (local {} tiles, server {} tiles)",
            totalToDownload,
            totalToUpload,
            localTiles.size(),
            serverManifest.tiles.size());
        chatQueue.offer("journeysync.chat.sync_start");
    }

    private void applyWaypointBlob(String json) {
        if (waypoints.applyServerBlob(json, jmDir)) {
            needsWaypointReset = true;
            markStateDirty();
        }
    }

    // ------------------------------------------------------------------ packets in (Netty threads)

    public void onTransferBegin(TileTransferBegin packet) {
        events.offer(packet);
    }

    public void onTransferChunk(TileChunk packet) {
        events.offer(packet);
    }

    public void onTransferAck(TileTransferAck packet) {
        events.offer(packet);
    }

    public void onHelloAck(HelloAckPacket packet) {
        events.offer(packet);
    }

    public void onBlobFragment(BlobPacket packet) {
        events.offer(packet);
    }

    // ------------------------------------------------------------------ tick-facing

    public void onClientTick() {
        if (!helloSent) {
            if (Minecraft.getMinecraft().thePlayer == null) {
                return;
            }
            helloSent = true;
            PacketHandler.wrapper.sendToServer(new HelloPacket(JmBridge.isJourneyMapLoaded(), Tags.VERSION));
            JourneySync.LOG.info("Joined world, sent Hello (serverId={})", serverId);
        }
        if (needsWaypointReset) {
            needsWaypointReset = false;
            JmBridge.resetWaypoints();
        }
        drainRemapQueue();
        String chatKey;
        while ((chatKey = chatQueue.poll()) != null) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                if ("journeysync.chat.sync_start".equals(chatKey)) {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(chatKey, totalToDownload, totalToUpload));
                } else if ("journeysync.chat.sync_progress".equals(chatKey)) {
                    mc.thePlayer.addChatMessage(
                        new ChatComponentTranslation(chatKey, downloaded, totalToDownload, uploaded, totalToUpload));
                } else {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(chatKey));
                }
            }
        }
    }

    private void maybeProgressChat(long now) {
        if (phase == Phase.TRANSFERRING && now - lastProgressChat > 10_000) {
            lastProgressChat = now;
            chatQueue.offer("journeysync.chat.sync_progress");
        }
    }

    /**
     * Feed queued region re-renders onto JM's task system, one region at a time: enabling a
     * MapRegionTask while one is already enabled is silently ignored by JM, so the next entry
     * waits for the busy flag to clear (polled every {@link #REMAP_POLL_TICKS} ticks rather than
     * every tick to keep the reflective busy check cheap). A request that fails outright drops
     * its entry - retrying a broken integration would only spam the log.
     */
    private void drainRemapQueue() {
        if (remapQueue.isEmpty()) {
            return;
        }
        if (remapTick > 0) {
            remapTick--;
            return;
        }
        remapTick = REMAP_POLL_TICKS;
        if (!JmBridge.isRegionRemapReady()) {
            return; // JM task controller not up yet (or gone with the world); keep entries queued
        }
        if (JmBridge.isRegionTaskBusy()) {
            return; // still rendering the previous region; retry at the next poll
        }
        int[] entry = remapQueue.poll();
        File worldDir = jmDir != null ? jmDir : JmBridge.getJMWorldDir();
        if (JmBridge.remapRegion(worldDir, entry[0], entry[1], entry[2], entry[3] == 1)) {
            JourneySync.LOG.debug(
                "Region re-render queued: dim {} chunks ({}, {}) {}",
                entry[0],
                entry[1],
                entry[2],
                entry[3] == 1 ? "night" : "day");
        } else {
            JourneySync.LOG.warn("Region re-render request failed; dropping entry ({} left)", remapQueue.size());
        }
    }

    // ------------------------------------------------------------------ command API

    public String statusLine() {
        String statusKey;
        switch (phase) {
            case DISCONNECTED:
                statusKey = "journeysync.status.offline";
                break;
            case AWAIT_MANIFEST:
                statusKey = "journeysync.status.handshake";
                break;
            case DIFFING:
                statusKey = "journeysync.status.scanning";
                break;
            case TRANSFERRING:
                statusKey = "journeysync.status.syncing";
                break;
            case WATCHING:
                statusKey = "journeysync.status.watching";
                break;
            default:
                statusKey = "journeysync.status.offline";
                break;
        }
        return StatCollector.translateToLocal(statusKey) + " | "
            + StatCollector.translateToLocalFormatted(
                "journeysync.status.progress",
                downloaded,
                totalToDownload,
                uploaded,
                totalToUpload);
    }

    public void setPaused(boolean value) {
        this.paused = value;
        chatQueue.offer(value ? "journeysync.chat.paused" : "journeysync.chat.resumed");
    }

    /**
     * Queue JM re-renders of the region (32x32 chunks) containing the player, day and night
     * variants - the repair tool for map damage that predates the pixel-union pipeline. JM only
     * renders loaded chunks, so the player must stand in the affected area for a complete render;
     * the rewritten tiles then flow through the file watcher into the normal upload path like any
     * other render. Returns false when the JM task integration is unavailable or no player
     * position can be read; nothing is queued.
     */
    public boolean queueRerender() {
        if (!JmBridge.isRegionRemapSupported()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return false;
        }
        int dimension = mc.thePlayer.dimension;
        int minChunkX = ((int) Math.floor(mc.thePlayer.posX)) >> 4 & ~31;
        int minChunkZ = ((int) Math.floor(mc.thePlayer.posZ)) >> 4 & ~31;
        remapQueue.add(new int[] { dimension, minChunkX, minChunkZ, 0 });
        remapQueue.add(new int[] { dimension, minChunkX, minChunkZ, 1 });
        return true;
    }

    // ------------------------------------------------------------------ state + helpers

    private void markStateDirty() {
        stateDirty = true;
    }

    private void flushStateIfDue(long now) {
        if (stateDirty && now - lastStateFlush > STATE_FLUSH_MILLIS) {
            flushState();
        }
    }

    /** Persist the per-server state; cheap enough for per-tile dirty marking. */
    private void flushState() {
        if (state == null || stateFile == null) {
            return;
        }
        state.save(stateFile);
        stateDirty = false;
        lastStateFlush = System.currentTimeMillis();
    }

    private String computeServerId() {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.multiplayer.ServerData serverData = mc.func_147104_D();
        if (serverData != null) {
            String raw = serverData.serverName + "_" + serverData.serverIP;
            return raw.replaceAll("[^A-Za-z0-9._-]", "_");
        }
        // Singleplayer/LAN host: key the state by the save folder so different worlds don't
        // share sync state (their JourneyMap caches are per-world too).
        net.minecraft.server.integrated.IntegratedServer integrated = mc.getIntegratedServer();
        if (integrated != null && integrated.getFolderName() != null) {
            return ("sp_" + integrated.getFolderName()).replaceAll("[^A-Za-z0-9._-]", "_");
        }
        return "sp";
    }
}
