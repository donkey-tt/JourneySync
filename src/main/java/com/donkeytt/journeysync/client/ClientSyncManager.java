package com.donkeytt.journeysync.client;

import java.io.File;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.Tags;
import com.donkeytt.journeysync.data.Manifest;
import com.donkeytt.journeysync.data.TileKey;
import com.donkeytt.journeysync.data.WaypointRecord;
import com.donkeytt.journeysync.io.Hashing;
import com.donkeytt.journeysync.io.TileFiles;
import com.donkeytt.journeysync.network.IncomingTransfer;
import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.network.RateLimiter;
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.TileAckPacket;
import com.donkeytt.journeysync.network.packets.TileBeginPacket;
import com.donkeytt.journeysync.network.packets.TileDataPacket;
import com.donkeytt.journeysync.network.packets.TileRequestPacket;
import com.donkeytt.journeysync.network.packets.TileUploadAckPacket;
import com.donkeytt.journeysync.network.packets.TileUploadBeginPacket;
import com.donkeytt.journeysync.network.packets.TileUploadDataPacket;
import com.donkeytt.journeysync.network.packets.WaypointChangePacket;

/**
 * Client-side sync engine. A single worker thread drives all transfers sequentially; network
 * handlers (Netty threads) only enqueue events. UI-facing side effects (chat, JM refreshes) are
 * deferred to the client tick via flags.
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

    // --- connection state ---
    private volatile Phase phase = Phase.DISCONNECTED;
    private volatile boolean paused;
    private String serverId = "unknown";
    private int serverRateLimitKBps;
    private volatile boolean helloSent;

    // --- worker ---
    private final BlockingQueue<Object> events = new LinkedBlockingQueue<Object>();
    private Thread worker;
    private final Object workerLock = new Object();

    // --- sync state ---
    private Manifest serverManifest;
    private ClientState state;
    private File stateFile;
    private File shadowDir;
    private File jmDir;
    private TileFiles jmTiles;
    private final Map<String, String> localSha1 = new ConcurrentHashMap<String, String>();
    private final Map<String, Long> localMtime = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> localSize = new ConcurrentHashMap<String, Long>();
    private final Map<String, Integer> waypointScan = new ConcurrentHashMap<String, Integer>();
    private final Set<String> failedTiles = new HashSet<String>();

    private final Deque<String> downloadQueue = new ArrayDeque<String>();
    private final Deque<String> uploadQueue = new ArrayDeque<String>();
    private final List<String> waypointOutbox = new ArrayList<String>();

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

    /** relPath -> epoch millis before which cosmetic re-uploads of that tile are skipped. */
    private final Map<String, Long> uploadQuietUntil = new HashMap<String, Long>();

    /** relPath -> epoch millis before which another regression-restore of that tile is allowed. */
    private final Map<String, Long> restoreQuietUntil = new HashMap<String, Long>();

    /**
     * relPath -> epoch millis before which an upload of that tile must add genuinely new pixels.
     * Set when a pushed/downloaded tile is applied: two clients rendering the same chunks produce
     * slightly different pixels (dithering, lighting timing), and without this quiet period each
     * side's re-render looks like a "change" against the other's version - the two clients would
     * upload the same tile back and forth forever, flipping the shared map on every push.
     */
    private final Map<String, Long> inboundQuietUntil = new HashMap<String, Long>();
    private static final long INBOUND_QUIET_MILLIS = 120_000;

    /**
     * Per-tile pixel union of every local render seen this session, fed at scan time and consumed
     * by doUpload as the upload payload. JourneyMap re-renders trail tiles from whatever chunks
     * happen to be loaded, so any single render can be a subset that overwrites the file; the
     * cache keeps those dropped pixels alive until an upload ack confirms the server holds them
     * (the entry is removed on ack). Bounded so a long session cannot grow it without limit.
     */
    private final Map<String, byte[]> renderCache = new LinkedHashMap<String, byte[]>() {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 64;
        }
    };

    // progress
    private int totalToDownload;
    private int downloaded;
    private int totalToUpload;
    private int uploaded;
    private long lastProgressChat;
    private long lastScanMillis;
    private boolean announcedComplete;

    // tick flags
    private volatile boolean needsRegionClear;
    private volatile boolean needsWaypointReset;
    private final BlockingQueue<String> chatQueue = new LinkedBlockingQueue<String>();

    // Immediate file-watch pipeline: a JourneyMap tile write lands here and runs through the
    // same guard chain within one worker tick, instead of waiting for the next periodic scan.
    private WatchService tileWatcher;
    private Thread tileWatcherThread;
    private final BlockingQueue<String> watchQueue = new LinkedBlockingQueue<String>();
    private volatile File watchBaseDir;

    /**
     * Regions (32x32 chunk squares) JourneyMap should re-render, queued by the rerender command.
     * Entries are [dimension, minChunkX, minChunkZ, night?1:0]; drained one at a time onto JM's
     * task system, which ignores a new region task while one is still enabled.
     */
    private final Deque<int[]> remapQueue = new ArrayDeque<int[]>();
    private int remapTick;
    private static final int REMAP_POLL_TICKS = 10; // ~0.5s between busy-flag polls

    private ClientSyncManager() {}

    // ------------------------------------------------------------------ lifecycle

    public void onConnect() {
        stopTileWatcher();
        phase = Phase.AWAIT_MANIFEST;
        announcedComplete = false;
        serverManifest = null;
        blobs.clear();
        pushedTransfers.clear();
        downloadQueue.clear();
        uploadQueue.clear();
        waypointOutbox.clear();
        failedTiles.clear();
        localSha1.clear();
        localMtime.clear();
        localSize.clear();
        pendingWaypointBlob = null;
        jmDir = null;
        jmTiles = null;
        uploadQuietUntil.clear();
        restoreQuietUntil.clear();
        inboundQuietUntil.clear();
        renderCache.clear();
        remapQueue.clear();

        serverId = computeServerId();
        helloSent = false;
        stateFile = new File(
            new File(Minecraft.getMinecraft().mcDataDir, "config/journeysync"),
            "state-" + serverId + ".json");
        state = ClientState.load(stateFile);
        shadowDir = new File(new File(Minecraft.getMinecraft().mcDataDir, "config/journeysync"), "shadow/" + serverId);

        // The rate limiter is built in onHelloAck, once the server's effective config is known;
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
        saveStateQuietly();
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
        if (phase == Phase.DISCONNECTED) {
            events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        if (paused) {
            events.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }

        Object ev = events.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
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
                flushWaypointOutbox();
                if (phase == Phase.TRANSFERRING && downloadQueue.isEmpty() && uploadQueue.isEmpty()) {
                    phase = Phase.WATCHING;
                    if (!announcedComplete) {
                        announcedComplete = true;
                        saveStateQuietly();
                        chatQueue.offer("journeysync.chat.sync_complete");
                    }
                }
                if (phase == Phase.WATCHING
                    && (now - lastScanMillis > Config.scanIntervalSeconds * 1000L || !watchQueue.isEmpty())) {
                    incrementalScan();
                }
                maybeProgressChat(now);
                break;
            default:
                break;
        }
    }

    private void dispatch(Object ev) {
        if (ev instanceof TileBeginPacket) {
            TileBeginPacket begin = (TileBeginPacket) ev;
            pushedTransfers.put(
                begin.transferId,
                new IncomingTransfer(begin.transferId, begin.relPath, begin.sha1, begin.totalBytes, begin.chunkSize));
        } else if (ev instanceof TileDataPacket) {
            TileDataPacket data = (TileDataPacket) ev;
            IncomingTransfer transfer = pushedTransfers.get(data.transferId);
            if (transfer != null && transfer.addChunk(data.seq, data.data)) {
                pushedTransfers.remove(data.transferId);
                finishPushedDownload(transfer);
            }
        } else if (ev instanceof TileUploadAckPacket) {
            // Late/unknown acks: the active upload wait-loop consumes matching acks directly from
            // the event queue; anything reaching here is stale and can be dropped.
        } else if (ev instanceof BlobPacket) {
            onBlobFragment((BlobPacket) ev);
        } else if (ev instanceof WaypointBlobEvent) {
            String json = ((WaypointBlobEvent) ev).json;
            if (jmDir == null) {
                pendingWaypointBlob = json;
            } else {
                applyWaypointBlob(json);
            }
        }
    }

    private void finishPushedDownload(IncomingTransfer transfer) {
        try {
            if (!transfer.verifySha1()) {
                JourneySync.LOG.warn("Pushed tile failed verification: {}", transfer.relPath);
                PacketHandler.wrapper.sendToServer(new TileAckPacket(transfer.transferId, false));
                return;
            }
            TileKey key = TileKey.parse(transfer.relPath);
            if (key == null) {
                return;
            }
            byte[] data = transfer.getData();
            jmTiles.writeAtomic(key, data);
            writeShadow(key.relPath(), data);
            accumulateRender(key.relPath(), data); // an in-flight union upload must not lose these pixels
            inboundQuietUntil.put(key.relPath(), System.currentTimeMillis() + INBOUND_QUIET_MILLIS);
            String sha1 = Hashing.sha1Hex(data);
            noteLocalTile(key.relPath(), sha1, System.currentTimeMillis(), data.length);
            state.syncedTiles
                .put(key.relPath(), new ClientState.TileState(sha1, System.currentTimeMillis(), data.length));
            if (!JmBridge.refreshTile(jmDir, key, data)) {
                needsRegionClear = true;
            }
            PacketHandler.wrapper.sendToServer(new TileAckPacket(transfer.transferId, true));
            saveStateQuietly();
            JourneySync.LOG.debug("Applied pushed tile {} ({} bytes)", transfer.relPath, data.length);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed writing pushed tile {}", transfer.relPath, e);
        }
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
            Object ev = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (ev == null) {
                continue;
            }
            if (ev instanceof TileBeginPacket && transfer == null && ((TileBeginPacket) ev).relPath.equals(relPath)) {
                TileBeginPacket begin = (TileBeginPacket) ev;
                transfer = new IncomingTransfer(
                    begin.transferId,
                    begin.relPath,
                    begin.sha1,
                    begin.totalBytes,
                    begin.chunkSize);
            } else if (ev instanceof TileDataPacket && transfer != null
                && ((TileDataPacket) ev).transferId == transfer.transferId) {
                    TileDataPacket data = (TileDataPacket) ev;
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
            PacketHandler.wrapper.sendToServer(new TileAckPacket(transfer.transferId, false));
            return;
        }

        try {
            TileKey key = TileKey.parse(relPath);
            byte[] data = transfer.getData();
            jmTiles.writeAtomic(key, data);
            writeShadow(relPath, data);
            accumulateRender(relPath, data); // an in-flight union upload must not lose these pixels
            inboundQuietUntil.put(relPath, System.currentTimeMillis() + INBOUND_QUIET_MILLIS);
            String sha1 = Hashing.sha1Hex(data);
            long now = System.currentTimeMillis();
            noteLocalTile(relPath, sha1, now, data.length);
            state.syncedTiles.put(relPath, new ClientState.TileState(sha1, now, data.length));
            if (!JmBridge.refreshTile(jmDir, key, data)) {
                needsRegionClear = true;
            }
            downloaded++;
            saveStateQuietly();
            PacketHandler.wrapper.sendToServer(new TileAckPacket(transfer.transferId, true));
            JourneySync.LOG.debug("Applied downloaded tile {} ({} bytes)", relPath, data.length);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed writing downloaded tile {}", relPath, e);
            failTile(relPath);
        }
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
            byte[] merged = com.donkeytt.journeysync.data.PixelMerge.mergePng(cached, current);
            if (merged != null) {
                data = merged;
            }
        }
        String sha1 = Hashing.sha1Hex(data);

        if (serverManifest != null) {
            com.donkeytt.journeysync.data.TileEntry entry = serverManifest.tiles.get(relPath);
            if (entry != null && entry.sha1.equals(sha1)) {
                // Server already holds exactly this payload (e.g. it was pushed back to us).
                noteLocalTile(relPath, Hashing.sha1Hex(current), file.lastModified(), current.length);
                state.syncedTiles.put(relPath, new ClientState.TileState(entry.sha1, file.lastModified(), entry.size));
                uploadSettler.clear(relPath);
                renderCache.remove(relPath); // the server already contains everything we rendered
                return;
            }
            byte[] reference = entry == null ? null : serverReference(relPath);
            if (reference != null) {
                boolean adds = isInboundQuiet(relPath) ? PixelInfo.addsNewPixelsStrict(data, reference)
                    : PixelInfo.addsNewPixels(data, reference);
                if (!adds) {
                    // The accumulated union adds nothing over the server's version: everything we
                    // have rendered is already shared. Adopt the server's entry as our sync point
                    // and drop the union; the file on disk may still be a rotting subset.
                    String currentSha1 = Hashing.sha1Hex(current);
                    noteLocalTile(relPath, currentSha1, file.lastModified(), current.length);
                    state.syncedTiles
                        .put(relPath, new ClientState.TileState(entry.sha1, System.currentTimeMillis(), entry.size));
                    uploadSettler.clear(relPath);
                    renderCache.remove(relPath);
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
        if (uploadThrottled(relPath, data.length)) {
            // Cosmetic re-render within the per-tile cooldown; drop quietly.
            noteLocalTile(relPath, Hashing.sha1Hex(current), file.lastModified(), current.length);
            uploadSettler.clear(relPath);
            JourneySync.LOG.debug("Upload throttled within cooldown: {}", relPath);
            return;
        }

        int transferId = nextClientTransferId();
        int chunkSize = Config.chunkSizeBytes;
        int chunks = (data.length + chunkSize - 1) / chunkSize;
        PacketHandler.wrapper
            .sendToServer(new TileUploadBeginPacket(transferId, relPath, sha1, data.length, chunkSize));
        for (int seq = 0; seq < chunks; seq++) {
            int off = seq * chunkSize;
            int len = Math.min(chunkSize, data.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(data, off, chunk, 0, len);
            rateLimiter.acquire(len);
            PacketHandler.wrapper.sendToServer(new TileUploadDataPacket(transferId, seq, chunk));
        }

        long deadline = System.currentTimeMillis() + 60_000;
        TileUploadAckPacket ack = null;
        List<Object> stashed = new ArrayList<Object>();
        while (System.currentTimeMillis() < deadline) {
            Object candidate = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (candidate == null) {
                continue;
            }
            if (candidate instanceof TileUploadAckPacket
                && ((TileUploadAckPacket) candidate).transferId == transferId) {
                ack = (TileUploadAckPacket) candidate;
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
        if (ack.code == 0 || ack.code == 1) {
            uploaded++;
            JourneySync.LOG.debug("Uploaded tile {} ({} bytes)", relPath, data.length);
            if (Config.tileUploadCooldownSeconds > 0) {
                uploadQuietUntil.put(relPath, System.currentTimeMillis() + Config.tileUploadCooldownSeconds * 1000L);
            }
            renderCache.remove(relPath); // the server now holds everything we had accumulated
            noteLocalTile(relPath, Hashing.sha1Hex(current), file.lastModified(), current.length);
            com.donkeytt.journeysync.data.TileEntry serverEntry = serverManifest == null ? null
                : serverManifest.tiles.get(relPath);
            if (ack.code == 1 && serverEntry != null && !serverEntry.sha1.equals(sha1)) {
                // Absorbed by a merge: the server kept a version different from ours (the union
                // of ours with other clients' pixels). Adopt it as our sync point and pull it so
                // the local disk/display converge to the merged map; otherwise the next scans
                // would re-upload our subset forever.
                state.syncedTiles.put(
                    relPath,
                    new ClientState.TileState(serverEntry.sha1, System.currentTimeMillis(), serverEntry.size));
                enqueueRestore(relPath);
            } else {
                state.syncedTiles.put(relPath, new ClientState.TileState(sha1, file.lastModified(), data.length));
                writeShadow(relPath, data); // our bytes are now the server-side reference for pixel checks
            }
            saveStateQuietly();
        } else {
            JourneySync.LOG.warn("Upload rejected by server (code {}): {}", ack.code, relPath);
            failTile(relPath);
        }
    }

    private int clientTransferId;

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
        startTileWatcher(dir);
        JourneySync.LOG.info("JourneyMap cache directory: {}", dir);
        return true;
    }

    /** Full scan: hash all tiles, reusing recorded hashes for unchanged files. */
    private void scanLocal() {
        scanTiles();
        scanWaypoints();
        lastScanMillis = System.currentTimeMillis();
    }

    /** Incremental scan while watching: only re-hash files whose mtime/size changed. */
    private void incrementalScan() {
        drainWatchEvents();
        scanTiles();
        reconcileWithServer();
        scanWaypoints();
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
        // Reconcile deletions: drop vanished tiles from local maps.
        localSha1.keySet()
            .retainAll(seen);
        localMtime.keySet()
            .retainAll(seen);
        localSize.keySet()
            .retainAll(seen);
        uploadSettler.retainAll(seen);
        uploadQuietUntil.keySet()
            .retainAll(seen);
        restoreQuietUntil.keySet()
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

        String known = localSha1.get(relPath);
        if (known != null && localMtime.get(relPath) == mtime && localSize.get(relPath) == size) {
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
        try {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            String sha1 = Hashing.sha1Hex(data);
            noteLocalTile(relPath, sha1, mtime, size);
            accumulateRender(relPath, data); // capture happens even when the upload is deferred
            maybeEnqueueUpload(relPath, sha1, mtime, size);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed hashing {}", f, e);
        }
    }

    // ------------------------------------------------------------------ file watcher

    /**
     * Watch the tile kind directories (DIM0/day, DIM0/night, ...) so a JourneyMap tile write
     * runs the guard chain within one worker tick instead of waiting for the next periodic
     * scan. Only directories that already exist are registered; dimension folders created
     * later are picked up on the next connect. Degrades silently to scan-interval detection
     * when watching is unavailable.
     */
    private void startTileWatcher(File base) {
        stopTileWatcher();
        try {
            WatchService watcher = base.toPath()
                .getFileSystem()
                .newWatchService();
            int registered = 0;
            File[] dims = base.listFiles();
            if (dims != null) {
                for (File dim : dims) {
                    if (!dim.isDirectory() || !dim.getName()
                        .startsWith("DIM")) {
                        continue;
                    }
                    File[] kinds = dim.listFiles();
                    if (kinds == null) {
                        continue;
                    }
                    for (File kind : kinds) {
                        if (kind.isDirectory()) {
                            kind.toPath()
                                .register(
                                    watcher,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY);
                            registered++;
                        }
                    }
                }
            }
            if (registered == 0) {
                watcher.close();
                return;
            }
            tileWatcher = watcher;
            watchBaseDir = base;
            watchQueue.clear();
            tileWatcherThread = new Thread(new Runnable() {

                @Override
                public void run() {
                    watchLoop(watcher);
                }
            }, "JourneySync-TileWatcher");
            tileWatcherThread.setDaemon(true);
            tileWatcherThread.start();
            JourneySync.LOG.info("Watching {} tile directories for immediate change detection", registered);
        } catch (Exception e) {
            JourneySync.LOG.warn("Tile watch unavailable; falling back to scan-interval detection only", e);
            tileWatcher = null;
            watchBaseDir = null;
        }
    }

    private void stopTileWatcher() {
        if (tileWatcher != null) {
            try {
                tileWatcher.close();
            } catch (Exception ignored) {
                // already closed
            }
            tileWatcher = null;
        }
        if (tileWatcherThread != null) {
            tileWatcherThread.interrupt();
            tileWatcherThread = null;
        }
        watchBaseDir = null;
        watchQueue.clear();
    }

    private void watchLoop(WatchService watcher) {
        while (true) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (ClosedWatchServiceException e) {
                return; // watcher closed (disconnect / reconnect)
            } catch (InterruptedException e) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                String name = String.valueOf(event.context());
                if (!name.endsWith(".png") || name.contains(".sync-conflict")) {
                    continue;
                }
                File base = watchBaseDir;
                if (base == null) {
                    continue;
                }
                String relPath = base.toPath()
                    .relativize(((Path) key.watchable()).resolve(name))
                    .toString()
                    .replace('\\', '/');
                if (TileKey.parse(relPath) != null) {
                    watchQueue.offer(relPath);
                }
            }
            if (!key.reset()) {
                return;
            }
        }
    }

    /**
     * Feed queued watch events through the same per-file guard chain as the periodic scan.
     * Files younger than the write grace period are re-queued instead of dropped: a partial
     * write must never be hashed into the local record or judged by the pixel guards, and the
     * re-queue lets the next worker tick (~100ms later) see the completed file long before the
     * periodic-scan fallback would.
     */
    private void drainWatchEvents() {
        long now = System.currentTimeMillis();
        List<String> young = null;
        String relPath;
        while ((relPath = watchQueue.poll()) != null) {
            File f = new File(jmDir, relPath);
            if (!f.isFile()) {
                continue;
            }
            if (now - f.lastModified() <= 500) {
                if (young == null) {
                    young = new ArrayList<String>(2);
                }
                young.add(relPath);
                continue;
            }
            processTileFile(f, relPath);
        }
        if (young != null) {
            watchQueue.addAll(young);
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
     * The shared upload guard chain used by every enqueue path (fresh hash or settled re-check):
     * our own sync record, the server's entry, and the per-tile cooldown. Returns false when the
     * tile must not upload; each rejection clears the settle tracker. Regressions no longer block
     * the upload - the payload is the accumulated pixel union, which can only add coverage - they
     * only schedule a restore so the rotting local file is refreshed for display.
     */
    private boolean passesUploadGuards(String relPath, String sha1, long size) {
        if (failedTiles.contains(relPath)) {
            uploadSettler.clear(relPath);
            return false; // persistently failing; retried on next join
        }
        ClientState.TileState recorded = state.syncedTiles.get(relPath);
        if (recorded != null && recorded.sha1.equals(sha1)) {
            uploadSettler.clear(relPath);
            return false; // unchanged since our last sync (stat drift only)
        }
        com.donkeytt.journeysync.data.TileEntry serverEntry = serverManifest.tiles.get(relPath);
        if (serverEntry != null && serverEntry.sha1.equals(sha1)) {
            uploadSettler.clear(relPath);
            return false; // server already has this content
        }
        if (isLocalRegression(relPath, size)) {
            enqueueRestore(relPath); // the file is rotting; the union upload itself is harmless
        }
        if (uploadThrottled(relPath, size)) {
            uploadSettler.clear(relPath);
            return false; // cosmetic re-render within the per-tile cooldown
        }
        return true;
    }

    /**
     * Whether the candidate render regresses against any fuller version we know: the server's
     * current entry or our own last-synced state. Comparing only against the server lets a client
     * upload a partial render after its own fuller version was clobbered, which restarts the
     * ping-pong; comparing against both baselines makes regressions die on every client.
     */
    private boolean isLocalRegression(String relPath, long size) {
        com.donkeytt.journeysync.data.TileEntry serverEntry = serverManifest.tiles.get(relPath);
        if (serverEntry != null && com.donkeytt.journeysync.data.Manifest.isRegression(serverEntry.size, size)) {
            return true;
        }
        ClientState.TileState recorded = state.syncedTiles.get(relPath);
        return recorded != null && com.donkeytt.journeysync.data.Manifest.isRegression(recorded.size, size);
    }

    /**
     * Re-download a tile whose local file regressed below a known fuller version. JourneyMap
     * clobbers synced tiles with partial re-renders whenever its region cache is incomplete (our
     * cache clears make it worse); without this, the local view silently rots even though the
     * server stays correct. Rate-limited so a persistent JM re-render fight costs at most one
     * download per 15 seconds per tile - short enough that a re-rendered dip recovers almost
     * immediately, long enough to keep the fight cheap.
     */
    private void enqueueRestore(String relPath) {
        if (!Config.downloadEnabled || serverManifest == null || !serverManifest.tiles.containsKey(relPath)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long until = restoreQuietUntil.get(relPath);
        if (until != null && now < until.longValue()) {
            return;
        }
        restoreQuietUntil.put(relPath, now + 15_000);
        if (!downloadQueue.contains(relPath)) {
            downloadQueue.add(relPath);
            JourneySync.LOG.debug("Local tile regressed; restoring from server: {}", relPath);
        }
    }

    // ------------------------------------------------------------------ shadow copies

    private File shadowFile(String relPath) {
        return new File(shadowDir, relPath);
    }

    /** Server-authoritative copy of a tile, used for pixel-level "does this render add anything" checks. */
    private void writeShadow(String relPath, byte[] data) {
        File target = shadowFile(relPath);
        File parent = target.getParentFile();
        if (parent == null) {
            return;
        }
        parent.mkdirs();
        try {
            File tmp = new File(parent, target.getName() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), data);
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            JourneySync.LOG.debug("Failed writing shadow tile {}", relPath, e);
        }
    }

    /**
     * Seed the shadow store from tiles that already match the server byte-for-byte, so the
     * pixel-level guard also covers content synced before the shadow existed, and drop shadows
     * for tiles the server no longer tracks.
     */
    private void backfillShadows() {
        if (shadowDir == null || serverManifest == null || jmTiles == null) {
            return;
        }
        Set<String> tracked = serverManifest.tiles.keySet();
        pruneShadowDir(shadowDir, "", tracked);
        for (Map.Entry<String, com.donkeytt.journeysync.data.TileEntry> e : serverManifest.tiles.entrySet()) {
            String relPath = e.getKey();
            if (shadowFile(relPath).isFile()) {
                continue;
            }
            String local = localSha1.get(relPath);
            if (local == null || !local.equals(e.getValue().sha1)) {
                continue;
            }
            try {
                TileKey key = TileKey.parse(relPath);
                File src = key == null ? null : jmTiles.resolve(key);
                if (src != null && src.isFile()) {
                    writeShadow(relPath, java.nio.file.Files.readAllBytes(src.toPath()));
                }
            } catch (Exception ex) {
                JourneySync.LOG.debug("Failed backfilling shadow {}", relPath, ex);
            }
        }
    }

    private void pruneShadowDir(File dir, String prefix, Set<String> tracked) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                pruneShadowDir(f, rel, tracked);
                String[] remaining = f.list();
                if (remaining != null && remaining.length == 0) {
                    f.delete();
                }
            } else if (!tracked.contains(rel)) {
                f.delete();
            }
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
     * treating that as "new" makes them ping-pong the tile back and forth; only genuinely new
     * pixels (real exploration) may upload.
     */
    private boolean addsNoNewPixels(String relPath, byte[] localData) {
        byte[] reference = serverReference(relPath);
        if (reference == null) {
            return false;
        }
        if (isInboundQuiet(relPath)) {
            return !PixelInfo.addsNewPixelsStrict(localData, reference);
        }
        return !PixelInfo.addsNewPixels(localData, reference);
    }

    /**
     * The server-authoritative shadow bytes for a tile, or null when unavailable or when the
     * shadow predates the server's current version (and so cannot judge new pixels).
     */
    private byte[] serverReference(String relPath) {
        com.donkeytt.journeysync.data.TileEntry entry = serverManifest == null ? null
            : serverManifest.tiles.get(relPath);
        if (entry == null) {
            return null;
        }
        File shadow = shadowFile(relPath);
        if (!shadow.isFile()) {
            return null;
        }
        try {
            byte[] reference = java.nio.file.Files.readAllBytes(shadow.toPath());
            if (!Hashing.sha1Hex(reference)
                .equals(entry.sha1)) {
                return null; // shadow predates the server's current version; cannot judge
            }
            return reference;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Merge a render into the per-tile accumulated pixel union (the renderCache). JourneyMap
     * re-renders a trail tile from whatever chunks are currently loaded, so any single render can
     * be a subset that overwrites the file; unioning at scan time decouples capture from
     * transmission and keeps the dropped pixels alive until an upload ack confirms the server
     * holds them.
     */
    private void accumulateRender(String relPath, byte[] png) {
        byte[] cached = renderCache.get(relPath);
        if (cached == null) {
            renderCache.put(relPath, png);
            return;
        }
        byte[] merged = com.donkeytt.journeysync.data.PixelMerge.mergePng(cached, png);
        if (merged != null) {
            renderCache.put(relPath, merged);
        }
    }

    private boolean isInboundQuiet(String relPath) {
        Long until = inboundQuietUntil.get(relPath);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until.longValue()) {
            inboundQuietUntil.remove(relPath);
            return false;
        }
        return true;
    }

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

    /**
     * Per-tile upload cooldown. JourneyMap re-renders tiles as world time/lighting shifts, and each
     * render differs by a few bytes - indistinguishable from real new content by hash alone.
     * Returns true while the tile is in its cooldown window and the candidate is not significantly
     * larger than what we last synced (i.e. cosmetic jitter, not new exploration).
     */
    private boolean uploadThrottled(String relPath, long size) {
        Long until = uploadQuietUntil.get(relPath);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until.longValue()) {
            uploadQuietUntil.remove(relPath);
            return false;
        }
        ClientState.TileState recorded = state.syncedTiles.get(relPath);
        long baseline = recorded != null ? recorded.size : 0;
        return baseline <= 0 || size <= baseline + baseline / 4;
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
        String sha1 = localSha1.get(relPath);
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
        for (Map.Entry<String, com.donkeytt.journeysync.data.TileEntry> e : serverManifest.tiles.entrySet()) {
            String relPath = e.getKey();
            String local = localSha1.get(relPath);
            if (local == null || local.equals(e.getValue().sha1)) {
                continue;
            }
            ClientState.TileState recorded = state.syncedTiles.get(relPath);
            if (recorded == null || !recorded.sha1.equals(e.getValue().sha1)) {
                continue; // we diverged from the server; the upload path owns this tile
            }
            Long size = localSize.get(relPath);
            if (size != null && size.longValue() >= recorded.size) {
                continue; // not smaller than what we synced; not a silent regression
            }
            enqueueRestore(relPath);
        }
    }

    private void noteLocalTile(String relPath, String sha1, long mtime, long size) {
        localSha1.put(relPath, sha1);
        localMtime.put(relPath, mtime);
        localSize.put(relPath, size);
    }

    private void scanWaypoints() {
        File waypointDir = new File(jmDir, "waypoints");
        File[] files = waypointDir.listFiles();
        Map<String, Integer> current = new LinkedHashMap<String, Integer>();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || !f.getName()
                    .endsWith(".json")) {
                    continue;
                }
                try {
                    String json = new String(
                        java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                    WaypointRecord record = WaypointRecord.fromWaypointJson(json);
                    if (record == null || record.id == null || record.id.isEmpty()) {
                        continue;
                    }
                    current.put(record.id, json.hashCode());
                    Integer previous = waypointScan.get(record.id);
                    boolean knownServerSide = state.waypointRevs.containsKey(record.id);
                    if (!knownServerSide || previous == null || previous != json.hashCode()) {
                        enqueueWaypointChange(record);
                    }
                } catch (Exception e) {
                    JourneySync.LOG.error("Failed reading waypoint {}", f, e);
                }
            }
        }
        // Detect deletions of locally created/shared waypoints.
        for (String id : new HashSet<String>(waypointScan.keySet())) {
            if (!current.containsKey(id) && state.waypointRevs.containsKey(id)) {
                WaypointRecord tombstone = new WaypointRecord();
                tombstone.id = id;
                tombstone.deleted = true;
                enqueueWaypointChange(tombstone);
                waypointScan.remove(id); // fire once; server rejects duplicates harmlessly
            }
        }
        waypointScan.clear();
        waypointScan.putAll(current);
    }

    private void enqueueWaypointChange(WaypointRecord record) {
        synchronized (waypointOutbox) {
            waypointOutbox.add(record.toWaypointJson());
        }
    }

    private void flushWaypointOutbox() {
        List<String> batch;
        synchronized (waypointOutbox) {
            if (waypointOutbox.isEmpty()) {
                return;
            }
            batch = new ArrayList<String>(waypointOutbox);
            waypointOutbox.clear();
        }
        for (String json : batch) {
            WaypointRecord record = WaypointRecord.fromWaypointJson(json);
            if (record != null) {
                PacketHandler.wrapper.sendToServer(new WaypointChangePacket(record));
            }
        }
    }

    // ------------------------------------------------------------------ diffing

    private void computeDiff() {
        Map<String, String> syncedSha1 = state.syncedSha1Map();
        backfillShadows();
        Set<String> toDownload = serverManifest.diffDownload(localSha1, syncedSha1, localSize);
        Set<String> toUpload;
        if (Config.uploadEnabled) {
            toUpload = serverManifest.diffUpload(localSha1, localSize);
            // Never queue a join-time upload that regresses against a fuller known version.
            Set<String> pruned = new HashSet<String>();
            for (String path : toUpload) {
                Long size = localSize.get(path);
                if (isLocalRegression(path, size == null ? 0 : size.longValue())) {
                    JourneySync.LOG.debug("Skipping self-regressing upload at join: {}", path);
                    continue;
                }
                if (addsNoNewPixels(path)) {
                    JourneySync.LOG.debug("Skipping stale re-render upload at join (no new pixels): {}", path);
                    continue;
                }
                pruned.add(path);
            }
            toUpload = pruned;
        } else {
            toUpload = new HashSet<String>();
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
            localSha1.size(),
            serverManifest.tiles.size());
        chatQueue.offer("journeysync.chat.sync_start");
    }

    // ------------------------------------------------------------------ packets in

    public void onHelloAck(com.donkeytt.journeysync.network.packets.HelloAckPacket msg) {
        if (msg.protocolVersion != PacketHandler.PROTOCOL_VERSION) {
            chatQueue.offer("journeysync.chat.version_mismatch");
            phase = Phase.DISCONNECTED;
            return;
        }
        serverRateLimitKBps = msg.rateLimitKBps;
        int effectiveLimit = Config.rateLimitKBps > 0 ? Config.rateLimitKBps : serverRateLimitKBps;
        rateLimiter = new RateLimiter(effectiveLimit > 0 ? effectiveLimit * 1024L : 0);
        phase = Phase.DIFFING;
    }

    public void onBlobFragment(BlobPacket packet) {
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

    private void applyWaypointBlob(String json) {
        try {
            Stored stored = GSON.fromJson(json, Stored.class);
            if (stored == null || stored.records == null) {
                return;
            }
            boolean any = false;
            for (WaypointRecord record : stored.records) {
                Long applied = state.waypointRevs.get(record.id);
                if (applied != null && applied >= record.rev) {
                    continue;
                }
                File file = new File(new File(jmDir, "waypoints"), record.fileName());
                if (record.deleted) {
                    if (file.isFile() && !file.delete()) {
                        JourneySync.LOG.warn("Could not delete waypoint file {}", file);
                    }
                } else {
                    File parent = file.getParentFile();
                    if (!parent.isDirectory()) {
                        parent.mkdirs();
                    }
                    java.nio.file.Files.write(
                        file.toPath(),
                        record.toWaypointJson()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                state.waypointRevs.put(record.id, record.rev);
                waypointScan.put(
                    record.id,
                    record.toWaypointJson()
                        .hashCode());
                any = true;
            }
            if (any) {
                needsWaypointReset = true;
                saveStateQuietly();
            }
        } catch (Exception e) {
            JourneySync.LOG.error("Failed applying waypoint blob", e);
        }
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private static class Stored {

        List<WaypointRecord> records;
    }

    private static class BlobAssembly {

        int total;
        final Map<Integer, byte[]> parts = new HashMap<Integer, byte[]>();
        int receivedBytes;
    }

    public void onTileBegin(TileBeginPacket packet) {
        events.offer(packet);
    }

    public void onTileData(TileDataPacket packet) {
        events.offer(packet);
    }

    public void onTileUploadAck(TileUploadAckPacket packet) {
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
        if (needsRegionClear) {
            needsRegionClear = false;
            JmBridge.clearRegionImageCache();
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

    // ------------------------------------------------------------------ helpers

    private void saveStateQuietly() {
        if (state != null && stateFile != null) {
            state.save(stateFile);
        }
    }

    private String computeServerId() {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.multiplayer.ServerData serverData = mc.func_147104_D();
        if (serverData != null) {
            String raw = serverData.serverName + "_" + serverData.serverIP;
            return raw.replaceAll("[^A-Za-z0-9._-]", "_");
        }
        if (mc.isSingleplayer()) {
            return "sp";
        }
        return "unknown";
    }
}
