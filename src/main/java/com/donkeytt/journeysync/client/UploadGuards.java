package com.donkeytt.journeysync.client;

import com.donkeytt.journeysync.data.Manifest;
import com.donkeytt.journeysync.data.TileEntry;

/**
 * The pure decision core of the upload guard chain: given a candidate tile (sha1 + size) and the
 * two "already known content" baselines, decide whether an upload may proceed. Extracted so the
 * ordering and semantics are unit-testable; the manager supplies the live state.
 */
final class UploadGuards {

    private UploadGuards() {}

    enum Verdict {

        PROCEED,
        SKIP_FAILED,
        SKIP_SYNCED,
        SKIP_SERVER_HAS,
        SKIP_THROTTLED
    }

    /**
     * Order matters: a persistently failing tile is never retried this session; content identical
     * to our last sync or to the server's entry needs no transfer; a cosmetic re-render inside the
     * per-tile cooldown is dropped unless it grew significantly.
     */
    static Verdict verdict(String sha1, long size, boolean previouslyFailed, ClientState.TileState recorded,
        TileEntry serverEntry, boolean withinCooldown) {
        if (previouslyFailed) {
            return Verdict.SKIP_FAILED;
        }
        if (recorded != null && recorded.sha1.equals(sha1)) {
            return Verdict.SKIP_SYNCED;
        }
        if (serverEntry != null && serverEntry.sha1.equals(sha1)) {
            return Verdict.SKIP_SERVER_HAS;
        }
        if (withinCooldown && cooldownBlocks(size, recorded == null ? 0 : recorded.size)) {
            return Verdict.SKIP_THROTTLED;
        }
        return Verdict.PROCEED;
    }

    /**
     * Cosmetic re-render suppression inside the cooldown window: a candidate that did not grow
     * meaningfully beyond the last synced size is jitter, not new exploration.
     */
    static boolean cooldownBlocks(long size, long recordedSize) {
        return recordedSize <= 0 || size <= recordedSize + recordedSize / 4;
    }

    /**
     * Whether a candidate render holds clearly less content than a fuller version we already
     * know: the server's entry or our own last sync (comparing against only one of the two would
     * let a client upload a partial render after its own fuller version was clobbered, which
     * restarts the ping-pong). Such a file is rotting locally; it must be restored, though the
     * union upload itself remains harmless.
     */
    static boolean regresses(TileEntry serverEntry, ClientState.TileState recorded, long size) {
        if (serverEntry != null && Manifest.isRegression(serverEntry.size, size)) {
            return true;
        }
        return recorded != null && Manifest.isRegression(recorded.size, size);
    }
}
