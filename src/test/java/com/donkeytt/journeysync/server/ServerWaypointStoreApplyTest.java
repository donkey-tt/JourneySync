package com.donkeytt.journeysync.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.donkeytt.journeysync.data.WaypointRecord;

/**
 * The store now takes revisions from the shared {@link WaypointEpoch} instead of its own counter;
 * these tests pin the supplier contract and the verbatim {@code put} used by scope migration.
 */
public class ServerWaypointStoreApplyTest {

    private static File tempFile() throws Exception {
        File dir = Files.createTempDirectory("jswps")
            .toFile();
        dir.deleteOnExit();
        return new File(dir, "waypoints.json");
    }

    private static WaypointRecord rec(String id) {
        WaypointRecord r = new WaypointRecord();
        r.id = id;
        r.name = id;
        return r;
    }

    @Test
    public void acceptedChangeDrawsRevFromSupplier() throws Exception {
        ServerWaypointStore store = new ServerWaypointStore(tempFile());
        AtomicLong shared = new AtomicLong(40);
        WaypointRecord accepted = store.apply(rec("a"), "u1", false, shared::incrementAndGet);
        assertEquals(41, accepted.rev);
        assertEquals(41, store.epoch); // store epoch tracks the shared counter as a floor
    }

    @Test
    public void rejectedChangeConsumesNoRev() throws Exception {
        ServerWaypointStore store = new ServerWaypointStore(tempFile());
        AtomicLong shared = new AtomicLong(40);
        assertNull(store.apply(rec(""), "u1", false, shared::incrementAndGet));
        assertEquals(40, shared.get());

        // deleting someone else's record as a non-op is rejected too
        WaypointRecord a = rec("a");
        store.apply(a, "u1", false, shared::incrementAndGet);
        WaypointRecord del = rec("a");
        del.deleted = true;
        assertNull(store.apply(del, "u2", false, shared::incrementAndGet));
        assertEquals(41, shared.get());
    }

    @Test
    public void putStoresVerbatimAndRaisesEpoch() throws Exception {
        ServerWaypointStore store = new ServerWaypointStore(tempFile());
        WaypointRecord r = rec("migrated");
        r.owner = "u1";
        r.rev = 77;
        store.put(r);
        assertEquals(77, store.epoch);
        assertTrue(
            store.allRecords()
                .contains(r));
    }

    @Test
    public void toJsonSerializesArbitrarySet() throws Exception {
        ServerWaypointStore store = new ServerWaypointStore(tempFile());
        store.put(rec("a"));
        String json = ServerWaypointStore.toJson(9L, store.allRecords());
        assertTrue(json.contains("\"epoch\": 9"));
        assertTrue(json.contains("\"id\": \"a\""));
    }
}
