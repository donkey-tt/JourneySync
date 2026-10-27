package com.donkeytt.journeysync.client;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.donkeytt.journeysync.data.PixelMerge;

/**
 * Per-tile pixel union of every local render seen this session. JourneyMap re-renders trail tiles
 * from whatever chunks happen to be loaded, so any single render can be a subset that overwrites
 * the file; this cache keeps those dropped pixels alive until an upload ack confirms the server
 * holds them (entries are removed on ack). Bounded by both tile count and total bytes so a long
 * session cannot grow it without limit. Worker-thread only.
 */
final class RenderUnionCache {

    private final long byteBudget;
    private final int maxTiles;
    private final Map<String, byte[]> union = new LinkedHashMap<String, byte[]>();
    private long bytes;

    RenderUnionCache() {
        this(64L * 1024 * 1024, 512);
    }

    RenderUnionCache(long byteBudget, int maxTiles) {
        this.byteBudget = byteBudget;
        this.maxTiles = maxTiles;
    }

    /** Merge a render into the tile's accumulated union (the first render seeds it). */
    void accumulate(String relPath, byte[] png) {
        byte[] cached = union.get(relPath);
        if (cached == null) {
            put(relPath, png);
            return;
        }
        byte[] merged = PixelMerge.mergePng(cached, png);
        if (merged == null) {
            return; // incomparable (unreadable or mismatched image); keep what we have
        }
        bytes -= cached.length;
        put(relPath, merged);
    }

    byte[] get(String relPath) {
        return union.get(relPath);
    }

    /** Drop the union (the server now holds everything we had accumulated). */
    void remove(String relPath) {
        byte[] dropped = union.remove(relPath);
        if (dropped != null) {
            bytes -= dropped.length;
        }
    }

    void clear() {
        union.clear();
        bytes = 0;
    }

    int tileCount() {
        return union.size();
    }

    private void put(String relPath, byte[] png) {
        union.put(relPath, png);
        bytes += png.length;
        trim();
    }

    /** Evict the oldest entries while over budget. */
    private void trim() {
        Iterator<Map.Entry<String, byte[]>> it = union.entrySet()
            .iterator();
        while ((bytes > byteBudget || union.size() > maxTiles) && it.hasNext()) {
            bytes -= it.next()
                .getValue().length;
            it.remove();
        }
    }
}
