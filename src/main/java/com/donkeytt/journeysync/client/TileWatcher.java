package com.donkeytt.journeysync.client;

import java.io.File;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.TileKey;

/**
 * Immediate file-watch pipeline for the JourneyMap tile directories: a tile write lands in a
 * queue and is evaluated by the worker within one tick, instead of waiting for the next periodic
 * scan. Only directories that already exist are registered (dimension folders created later are
 * picked up on the next connect); the periodic scan is the backstop for anything missed. Degrades
 * silently to scan-interval detection when watching is unavailable.
 */
final class TileWatcher {

    /** Files younger than this are re-queued: a partial write must never be hashed. */
    private static final long WRITE_GRACE_MILLIS = 500;

    private final BlockingQueue<String> pending = new LinkedBlockingQueue<String>();
    private WatchService watcher;
    private Thread thread;
    private volatile File baseDir;

    /** Watch the tile-kind directories ({@code DIM<dim>/<kind>}, e.g. {@code DIM0/day}) under the JM world dir. */
    void start(File base) {
        stop();
        try {
            WatchService newWatcher = base.toPath()
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
                                    newWatcher,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY);
                            registered++;
                        }
                    }
                }
            }
            if (registered == 0) {
                newWatcher.close();
                return;
            }
            watcher = newWatcher;
            baseDir = base;
            thread = new Thread(new Runnable() {

                @Override
                public void run() {
                    watchLoop(newWatcher);
                }
            }, "JourneySync-TileWatcher");
            thread.setDaemon(true);
            thread.start();
            JourneySync.LOG.info("Watching {} tile directories for immediate change detection", registered);
        } catch (Exception e) {
            JourneySync.LOG.warn("Tile watch unavailable; falling back to scan-interval detection only", e);
            watcher = null;
            baseDir = null;
        }
    }

    void stop() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (Exception ignored) {
                // already closed
            }
            watcher = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        baseDir = null;
        pending.clear();
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * Feed queued watch events through the per-file guard chain (the shared
     * {@code processTileFile}). Files younger than the write grace period are re-queued instead of
     * dropped, so the next worker tick (~100ms later) sees the completed file long before the
     * periodic-scan fallback would.
     */
    void drain(BiConsumer<File, String> processor) {
        File base = baseDir;
        if (base == null) {
            pending.clear();
            return;
        }
        long now = System.currentTimeMillis();
        List<String> young = null;
        String relPath;
        while ((relPath = pending.poll()) != null) {
            File f = new File(base, relPath);
            if (!f.isFile()) {
                continue;
            }
            if (now - f.lastModified() <= WRITE_GRACE_MILLIS) {
                if (young == null) {
                    young = new ArrayList<String>(2);
                }
                young.add(relPath);
                continue;
            }
            processor.accept(f, relPath);
        }
        if (young != null) {
            pending.addAll(young);
        }
    }

    private void watchLoop(WatchService watchService) {
        while (true) {
            WatchKey key;
            try {
                key = watchService.take();
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
                File base = baseDir;
                if (base == null) {
                    continue;
                }
                String relPath = base.toPath()
                    .relativize(((Path) key.watchable()).resolve(name))
                    .toString()
                    .replace('\\', '/');
                if (TileKey.parse(relPath) != null) {
                    pending.offer(relPath);
                }
            }
            if (!key.reset()) {
                return;
            }
        }
    }
}
