package com.donkeytt.journeysync.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Debounces watch-phase tile uploads. While the player moves through a region JourneyMap
 * re-renders its tile repeatedly, and uploading every intermediate version just makes the other
 * clients re-render and bounce their own versions back. A tile is only worth uploading once its
 * file stat has stopped changing for a settle period - i.e. when the render has converged.
 *
 * <p>
 * Tracks per-tile file stat and reports each tile exactly once per stable episode.
 */
final class UploadSettler {

    static final class Entry {

        long mtime;
        long size;
        long stableSince;
        boolean fired;
    }

    private final long settleMillis;
    private final Map<String, Entry> pending = new HashMap<String, Entry>();

    UploadSettler(long settleMillis) {
        this.settleMillis = settleMillis;
    }

    /** 0 settle = feature off; callers upload immediately. */
    boolean disabled() {
        return settleMillis <= 0;
    }

    /**
     * Observes the current stat of a drifting tile. Returns true exactly once per stable episode:
     * when mtime/size have remained unchanged for at least {@code settleMillis}.
     */
    boolean observe(String relPath, long mtime, long size, long nowMillis) {
        Entry e = pending.get(relPath);
        if (e == null || e.mtime != mtime || e.size != size) {
            e = new Entry();
            e.mtime = mtime;
            e.size = size;
            e.stableSince = nowMillis;
            e.fired = false;
            pending.put(relPath, e);
            return false;
        }
        if (!e.fired && nowMillis - e.stableSince >= settleMillis) {
            e.fired = true;
            return true;
        }
        return false;
    }

    /**
     * Stability check for scans where the tile's stat did NOT change since the last scan (the
     * common case: most scans short-circuit before {@link #observe} would run). Returns true
     * exactly once when a previously observed episode has now stayed stable for the settle period.
     */
    boolean fireIfSettled(String relPath, long mtime, long size, long nowMillis) {
        Entry e = pending.get(relPath);
        if (e == null) {
            return false;
        }
        if (e.mtime != mtime || e.size != size) {
            e.mtime = mtime;
            e.size = size;
            e.stableSince = nowMillis;
            e.fired = false;
            return false;
        }
        if (!e.fired && nowMillis - e.stableSince >= settleMillis) {
            e.fired = true;
            return true;
        }
        return false;
    }

    /** Stops tracking a tile (synced, failed, vanished). */
    void clear(String relPath) {
        pending.remove(relPath);
    }

    /** Drops entries whose tile vanished from disk. */
    void retainAll(Set<String> seen) {
        Iterator<Map.Entry<String, Entry>> it = pending.entrySet()
            .iterator();
        while (it.hasNext()) {
            if (!seen.contains(
                it.next()
                    .getKey())) {
                it.remove();
            }
        }
    }

    int pendingCount() {
        return pending.size();
    }
}
