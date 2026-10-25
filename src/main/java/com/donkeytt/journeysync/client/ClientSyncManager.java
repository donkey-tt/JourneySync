package com.donkeytt.journeysync.client;

import java.io.File;
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

    // progress
    private int totalToDownload;
    private int downloaded;
    private int totalToUpload;
    private int uploaded;
    /** Watch-phase uploads since join; drives the periodic chat notice. */
    private int watchUploaded;
    private long lastProgressChat;
    private long lastScanMillis;
    private boolean announcedComplete;

    // tick flags
    private volatile boolean needsRegionClear;
    private volatile boolean needsWaypointReset;
    private final BlockingQueue<String> chatQueue = new LinkedBlockingQueue<String>();

    private ClientSyncManager() {}

    // ------------------------------------------------------------------ lifecycle

    public void onConnect() {
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

        serverId = computeServerId();
        helloSent = false;
        stateFile = new File(
            new File(Minecraft.getMinecraft().mcDataDir, "config/journeysync"),
            "state-" + serverId + ".json");
        state = ClientState.load(stateFile);

        int effectiveLimit = Config.rateLimitKBps > 0 ? Config.rateLimitKBps : serverRateLimitKBps;
        rateLimiter = new RateLimiter(effectiveLimit > 0 ? effectiveLimit * 1024L : 0);
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
                if (phase == Phase.WATCHING && now - lastScanMillis > Config.scanIntervalSeconds * 1000L) {
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
            String sha1 = Hashing.sha1Hex(data);
            noteLocalTile(key.relPath(), sha1, System.currentTimeMillis(), data.length);
            state.syncedTiles
                .put(key.relPath(), new ClientState.TileState(sha1, System.currentTimeMillis(), data.length));
            needsRegionClear = true;
            PacketHandler.wrapper.sendToServer(new TileAckPacket(transfer.transferId, true));
            saveStateQuietly();
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
            String sha1 = Hashing.sha1Hex(data);
            long now = System.currentTimeMillis();
            noteLocalTile(relPath, sha1, now, data.length);
            state.syncedTiles.put(relPath, new ClientState.TileState(sha1, now, data.length));
            needsRegionClear = true;
            downloaded++;
            saveStateQuietly();
            PacketHandler.wrapper.sendToServer(new TileAckPacket(transfer.transferId, true));
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
        byte[] data;
        String sha1;
        try {
            data = java.nio.file.Files.readAllBytes(file.toPath());
            sha1 = Hashing.sha1Hex(data);
        } catch (Exception e) {
            JourneySync.LOG.error("Failed reading local tile {}", relPath, e);
            failTile(relPath);
            return;
        }

        // Skip when the server already has this content (e.g. pushed back to us).
        if (serverManifest != null) {
            com.donkeytt.journeysync.data.TileEntry entry = serverManifest.tiles.get(relPath);
            if (entry != null && entry.sha1.equals(sha1)) {
                noteLocalTile(relPath, sha1, file.lastModified(), data.length);
                state.syncedTiles.put(relPath, new ClientState.TileState(sha1, file.lastModified(), data.length));
                uploadSettler.clear(relPath);
                return;
            }
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
            JourneySync.LOG.info("Uploaded tile {} ({} bytes)", relPath, data.length);
            if (phase == Phase.WATCHING) {
                watchUploaded++;
                if (watchUploaded == 1 || watchUploaded % 10 == 0) {
                    chatQueue.offer("journeysync.chat.watch_upload");
                }
            }
            noteLocalTile(relPath, sha1, file.lastModified(), data.length);
            state.syncedTiles.put(relPath, new ClientState.TileState(sha1, file.lastModified(), data.length));
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
        JourneySync.LOG.info("JourneyMap cache directory: {}", dir);
        return true;
    }

    /** Full scan: hash all tiles, reusing recorded hashes for unchanged files. */
    private void scanLocal() {
        scanTiles(true);
        scanWaypoints();
        lastScanMillis = System.currentTimeMillis();
    }

    /** Incremental scan while watching: only re-hash files whose mtime/size changed. */
    private void incrementalScan() {
        scanTiles(false);
        scanWaypoints();
        lastScanMillis = System.currentTimeMillis();
    }

    private void scanTiles(boolean full) {
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
            scanTilesIn(dimDir, dimDir.getName(), seen, full);
        }
        // Reconcile deletions: drop vanished tiles from local maps.
        localSha1.keySet()
            .retainAll(seen);
        localMtime.keySet()
            .retainAll(seen);
        localSize.keySet()
            .retainAll(seen);
        uploadSettler.retainAll(seen);
    }

    private void scanTilesIn(File dir, String prefix, Set<String> seen, boolean full) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                scanTilesIn(f, prefix + "/" + f.getName(), seen, full);
                continue;
            }
            String name = f.getName();
            if (!name.endsWith(".png") || name.contains(".sync-conflict")) {
                continue;
            }
            String relPath = prefix + "/" + name;
            TileKey key = TileKey.parse(relPath);
            if (key == null) {
                continue;
            }
            seen.add(relPath);
            long mtime = f.lastModified();
            long size = f.length();

            String known = localSha1.get(relPath);
            if (known != null && localMtime.get(relPath) == mtime && localSize.get(relPath) == size) {
                // Stat unchanged since the last scan - but a previously deferred tile may now
                // have stayed stable long enough to upload.
                maybeEnqueueSettled(relPath, mtime, size);
                continue; // unchanged
            }
            // Fast path: reuse the recorded synced hash when the file matches the recorded stat.
            ClientState.TileState recorded = state.syncedTiles.get(relPath);
            if (recorded != null && recorded.mtime == mtime && recorded.size == size) {
                noteLocalTile(relPath, recorded.sha1, mtime, size);
                uploadSettler.clear(relPath);
                continue;
            }
            try {
                String sha1 = Hashing.sha1Hex(f);
                noteLocalTile(relPath, sha1, mtime, size);
                maybeEnqueueUpload(relPath, recorded, sha1, mtime, size);
            } catch (Exception e) {
                JourneySync.LOG.error("Failed hashing {}", f, e);
            }
        }
    }

    /**
     * Watch-phase incremental upload: a tile whose content drifted from what we last synced (new
     * exploration or a JM re-render) goes onto the upload queue, unless the server already holds
     * this exact content. When the settle debounce is enabled, the tile must additionally keep an
     * unchanged file stat for the settle period before it is queued, so re-renders while moving
     * collapse into a single upload of the converged tile.
     */
    private void maybeEnqueueUpload(String relPath, ClientState.TileState recorded, String sha1, long mtime,
        long size) {
        if (!Config.uploadEnabled || serverManifest == null) {
            return;
        }
        if (recorded != null && recorded.sha1.equals(sha1)) {
            uploadSettler.clear(relPath);
            return; // unchanged since our last sync (stat drift only)
        }
        if (failedTiles.contains(relPath)) {
            return; // persistently failing; retried on next join
        }
        com.donkeytt.journeysync.data.TileEntry serverEntry = serverManifest.tiles.get(relPath);
        if (serverEntry != null && serverEntry.sha1.equals(sha1)) {
            uploadSettler.clear(relPath);
            return; // server already has this content
        }
        if (!uploadSettler.disabled() && !uploadSettler.observe(relPath, mtime, size, System.currentTimeMillis())) {
            JourneySync.LOG.debug("Tile still settling, upload deferred: {}", relPath);
            return; // file still settling
        }
        if (!uploadQueue.contains(relPath)) {
            uploadQueue.add(relPath);
        }
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
        if (failedTiles.contains(relPath)) {
            uploadSettler.clear(relPath);
            return; // persistently failing; retried on next join
        }
        String sha1 = localSha1.get(relPath);
        if (sha1 == null) {
            return;
        }
        ClientState.TileState recorded = state.syncedTiles.get(relPath);
        if (recorded != null && recorded.sha1.equals(sha1)) {
            uploadSettler.clear(relPath);
            return; // content matches our last sync
        }
        com.donkeytt.journeysync.data.TileEntry serverEntry = serverManifest.tiles.get(relPath);
        if (serverEntry != null && serverEntry.sha1.equals(sha1)) {
            uploadSettler.clear(relPath);
            return; // server already has this content
        }
        if (!uploadQueue.contains(relPath)) {
            uploadQueue.add(relPath);
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
        Set<String> toDownload = serverManifest.diffDownload(localSha1, syncedSha1);
        Set<String> toUpload;
        if (Config.uploadEnabled) {
            toUpload = serverManifest.diffUpload(localSha1);
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
        watchUploaded = 0;
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

        long epoch;
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
        String chatKey;
        while ((chatKey = chatQueue.poll()) != null) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                if ("journeysync.chat.sync_start".equals(chatKey)) {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(chatKey, totalToDownload, totalToUpload));
                } else if ("journeysync.chat.sync_progress".equals(chatKey)) {
                    mc.thePlayer.addChatMessage(
                        new ChatComponentTranslation(chatKey, downloaded, totalToDownload, uploaded, totalToUpload));
                } else if ("journeysync.chat.watch_upload".equals(chatKey)) {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(chatKey, watchUploaded));
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

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean value) {
        this.paused = value;
        chatQueue.offer(value ? "journeysync.chat.paused" : "journeysync.chat.resumed");
    }

    public boolean isActive() {
        return phase != Phase.DISCONNECTED;
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
