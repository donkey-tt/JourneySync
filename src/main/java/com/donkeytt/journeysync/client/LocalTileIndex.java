package com.donkeytt.journeysync.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The client's in-memory view of the local JourneyMap tile directory: relPath -> file stat plus
 * content hash, filled by scans and updated whenever a tile is written. Worker-thread only.
 */
final class LocalTileIndex {

    static final class Stat {

        final String sha1;
        final long mtime;
        final long size;

        Stat(String sha1, long mtime, long size) {
            this.sha1 = sha1;
            this.mtime = mtime;
            this.size = size;
        }
    }

    private final Map<String, Stat> byPath = new HashMap<String, Stat>();

    void note(String relPath, String sha1, long mtime, long size) {
        byPath.put(relPath, new Stat(sha1, mtime, size));
    }

    Stat stat(String relPath) {
        return byPath.get(relPath);
    }

    String sha1(String relPath) {
        Stat s = byPath.get(relPath);
        return s == null ? null : s.sha1;
    }

    Long size(String relPath) {
        Stat s = byPath.get(relPath);
        return s == null ? null : Long.valueOf(s.size);
    }

    /** Scan fast path: the file stat is unchanged since the last note. */
    boolean matches(String relPath, long mtime, long size) {
        Stat s = byPath.get(relPath);
        return s != null && s.mtime == mtime && s.size == size;
    }

    /** Drop entries whose tile vanished from disk. */
    void retainAll(Set<String> live) {
        byPath.keySet()
            .retainAll(live);
    }

    void clear() {
        byPath.clear();
    }

    int size() {
        return byPath.size();
    }

    Map<String, String> sha1Map() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Stat> e : byPath.entrySet()) {
            out.put(e.getKey(), e.getValue().sha1);
        }
        return out;
    }

    Map<String, Long> sizeMap() {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Stat> e : byPath.entrySet()) {
            out.put(e.getKey(), Long.valueOf(e.getValue().size));
        }
        return out;
    }
}
