package com.donkeytt.journeysync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

import com.donkeytt.journeysync.io.Hashing;
import com.donkeytt.journeysync.network.IncomingTransfer;

public class IncomingTransferTest {

    @Test
    public void assemblesOutOfOrderAndVerifies() {
        byte[] payload = new byte[100_000];
        new Random(7).nextBytes(payload);
        String sha1 = Hashing.sha1Hex(payload);
        int chunkSize = 24 * 1024;
        int chunks = (payload.length + chunkSize - 1) / chunkSize;

        IncomingTransfer t = new IncomingTransfer(1, "DIM0/day/0,0.png", sha1, payload.length, chunkSize);
        assertEquals(chunks, t.expectedChunks());

        // feed in reverse order
        for (int seq = chunks - 1; seq >= 0; seq--) {
            int off = seq * chunkSize;
            int len = Math.min(chunkSize, payload.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(payload, off, chunk, 0, len);
            boolean complete = t.addChunk(seq, chunk);
            if (seq > 0) {
                assertFalse(complete);
            } else {
                assertTrue(complete);
            }
        }
        assertTrue(t.verifySha1());
        assertEquals(payload.length, t.getData().length);
        // assembled once and cached: no re-join (and no chunk storage left) on later calls
        assertSame(t.getData(), t.getData());
    }

    @Test
    public void lateDuplicateChunksAfterAssemblyAreIgnored() {
        byte[] payload = new byte[] { 1, 2, 3, 4, 5 };
        IncomingTransfer t = new IncomingTransfer(1, "p", Hashing.sha1Hex(payload), 5, 4);
        t.addChunk(0, new byte[] { 1, 2, 3, 4 });
        t.addChunk(1, new byte[] { 5 });
        byte[] first = t.getData();
        assertTrue(t.addChunk(1, new byte[] { 9 })); // still complete, payload untouched
        assertSame(first, t.getData());
        assertArrayEquals(payload, t.getData());
    }

    @Test
    public void detectsCorruption() {
        byte[] payload = new byte[] { 1, 2, 3, 4, 5 };
        IncomingTransfer t = new IncomingTransfer(1, "p", Hashing.sha1Hex(payload), 5, 4);
        t.addChunk(0, new byte[] { 1, 2, 3, 4 });
        t.addChunk(1, new byte[] { 6 }); // corrupted last byte
        assertTrue(t.isComplete());
        assertFalse(t.verifySha1());
    }
}
