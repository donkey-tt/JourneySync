package com.donkeytt.journeysync.client;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-tile quiet windows: relPath -> epoch-millis deadline, one map per reason. Three reasons:
 * re-uploading a tile just uploaded (UPLOAD, suppresses cosmetic re-render jitter), re-restoring
 * a tile JourneyMap keeps clobbering (RESTORE), and re-uploading dithering noise right after
 * applying an inbound version of a tile (INBOUND, stops two clients rendering the same chunks
 * from flipping the tile back and forth forever). Worker-thread only.
 */
final class QuietPeriods {

    enum Type {

        UPLOAD,
        RESTORE,
        INBOUND
    }

    /**
     * After applying an inbound tile, only genuinely new pixels may re-upload it for this long:
     * two clients rendering the same chunks differ by dithering/lighting noise, and treating
     * that as "new" makes them ping-pong the tile.
     */
    static final long INBOUND_QUIET_MILLIS = 120_000;

    /** At most one regression restore per tile per this window. */
    static final long RESTORE_QUIET_MILLIS = 15_000;

    private final Map<Type, Map<String, Long>> until = new EnumMap<Type, Map<String, Long>>(Type.class);

    QuietPeriods() {
        for (Type type : Type.values()) {
            until.put(type, new HashMap<String, Long>());
        }
    }

    /** Whether the tile is inside its quiet window; an expired entry is removed on read. */
    boolean isQuiet(Type type, String relPath, long now) {
        Long deadline = until.get(type)
            .get(relPath);
        if (deadline == null) {
            return false;
        }
        if (now >= deadline.longValue()) {
            until.get(type)
                .remove(relPath);
            return false;
        }
        return true;
    }

    void quietUntil(Type type, String relPath, long epochMillis) {
        until.get(type)
            .put(relPath, Long.valueOf(epochMillis));
    }

    /** Drop entries whose tile vanished from disk. */
    void retainAll(Set<String> live) {
        for (Map<String, Long> m : until.values()) {
            m.keySet()
                .retainAll(live);
        }
    }

    void clear() {
        for (Map<String, Long> m : until.values()) {
            m.clear();
        }
    }
}
