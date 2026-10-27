package com.donkeytt.journeysync.network;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import com.donkeytt.journeysync.io.Hashing;

/**
 * Receiver-side assembler for one chunked tile transfer. Chunks may arrive in any order; the
 * payload is joined in sequence order once complete.
 */
public class IncomingTransfer {

    public final int transferId;
    public final String relPath;
    public final String sha1;
    public final int totalBytes;
    private final int expectedChunks;
    private final int chunkSize;

    private final List<byte[]> chunks;
    private final BitSet received;
    private int chunksReceived;
    private byte[] assembled;

    public IncomingTransfer(int transferId, String relPath, String sha1, int totalBytes, int chunkSize) {
        this.transferId = transferId;
        this.relPath = relPath;
        this.sha1 = sha1;
        this.totalBytes = totalBytes;
        this.chunkSize = chunkSize;
        this.expectedChunks = (totalBytes + chunkSize - 1) / chunkSize;
        this.chunks = new ArrayList<byte[]>(expectedChunks);
        for (int i = 0; i < expectedChunks; i++) {
            chunks.add(null);
        }
        this.received = new BitSet(expectedChunks);
    }

    public int expectedChunks() {
        return expectedChunks;
    }

    /** Add one chunk; duplicates are ignored. Returns true when the transfer is complete. */
    public boolean addChunk(int seq, byte[] payload) {
        if (seq < 0 || seq >= expectedChunks || received.get(seq)) {
            return isComplete();
        }
        received.set(seq);
        chunksReceived++;
        chunks.set(seq, payload);
        return isComplete();
    }

    public boolean isComplete() {
        return chunksReceived == expectedChunks;
    }

    /** Join chunks in sequence order (assembled once, then cached; the chunk list is freed). */
    public byte[] getData() {
        if (assembled == null) {
            byte[] out = new byte[totalBytes];
            int off = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, out, off, chunk.length);
                off += chunk.length;
            }
            assembled = out;
            chunks.clear(); // late duplicate chunks are rejected by the BitSet, never stored
        }
        return assembled;
    }

    /** Verify assembled content against the announced SHA-1. */
    public boolean verifySha1() {
        return sha1 == null || sha1.isEmpty() || sha1.equals(Hashing.sha1Hex(getData()));
    }
}
