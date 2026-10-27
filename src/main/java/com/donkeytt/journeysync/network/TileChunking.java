package com.donkeytt.journeysync.network;

/**
 * Shared tile-payload chunking: one packet-sized slice at a time, used by both transfer
 * directions.
 */
public final class TileChunking {

    /** Conservative chunk cap, safely below the 32767 custom-payload limit. */
    public static final int MAX_CHUNK = 24 * 1024;

    /** Receives each slice as a ready-to-send payload. */
    public interface ChunkSink {

        void chunk(int transferId, int seq, byte[] payload);
    }

    private TileChunking() {}

    /** Clamp the configured chunk size into the legal range. */
    public static int effectiveChunkSize(int configured) {
        return Math.min(Math.max(configured, 1024), MAX_CHUNK);
    }

    /** Slice {@code data} into fixed-size chunks and hand each to the sink in order. */
    public static void sendChunks(byte[] data, int chunkSize, int transferId, ChunkSink sink) {
        int chunks = (data.length + chunkSize - 1) / chunkSize;
        for (int seq = 0; seq < chunks; seq++) {
            int off = seq * chunkSize;
            int len = Math.min(chunkSize, data.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(data, off, chunk, 0, len);
            sink.chunk(transferId, seq, chunk);
        }
    }
}
