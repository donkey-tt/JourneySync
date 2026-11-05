package com.donkeytt.journeysync.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.donkeytt.journeysync.data.WaypointRecord;
import com.google.gson.Gson;

public class WaypointSyncTest {

    private File dir;
    private ClientState state;
    private final List<WaypointRecord> sent = new ArrayList<WaypointRecord>();
    private WaypointSync sync;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("waypointsync-test")
            .toFile();
        state = new ClientState();
        sync = new WaypointSync(state, sent::add);
    }

    @After
    public void tearDown() throws Exception {
        deleteTree(dir);
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
    }

    private static WaypointRecord record(String id, String name, long rev) {
        WaypointRecord r = new WaypointRecord();
        r.id = id;
        r.name = name;
        r.x = 1;
        r.y = 64;
        r.z = 2;
        r.rev = rev;
        return r;
    }

    private static String blob(WaypointRecord... records) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("epoch", 99);
        out.put("records", Arrays.asList(records));
        return new Gson().toJson(out);
    }

    private File waypointFile(WaypointRecord record) {
        return new File(new File(dir, "waypoints"), record.fileName());
    }

    private void writeWaypoint(WaypointRecord record) throws Exception {
        File f = waypointFile(record);
        f.getParentFile()
            .mkdirs();
        Files.write(
            f.toPath(),
            record.toWaypointJson()
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void prunesWaypointMissingFromBlob() throws Exception {
        WaypointRecord kept = record("kept_1,64,2", "kept", 5);
        WaypointRecord gone = record("gone_3,64,4", "gone", 3);
        writeWaypoint(kept);
        writeWaypoint(gone);
        state.waypointRevs.put(kept.id, 5L);
        state.waypointRevs.put(gone.id, 3L);
        sync.scanHash.put(kept.id, 111);
        sync.scanHash.put(gone.id, 222);

        assertTrue(sync.applyServerBlob(blob(kept), dir));

        assertTrue(waypointFile(kept).isFile());
        assertFalse(waypointFile(gone).exists());
        assertEquals(Long.valueOf(5), state.waypointRevs.get(kept.id));
        assertFalse(state.waypointRevs.containsKey(gone.id));
        assertTrue(sync.scanHash.containsKey(kept.id));
        assertFalse(sync.scanHash.containsKey(gone.id));
    }

    @Test
    public void prunesLocallyKnownTombstoneIds() {
        // The id's file is already gone (it was a tombstone); once it leaves the readable scope
        // the rev/scan bookkeeping must drop too, not linger as a ghost entry.
        WaypointRecord live = record("live_1,64,2", "live", 2);
        state.waypointRevs.put(live.id, 2L);
        state.waypointRevs.put("stale_9,64,9", 7L);
        sync.scanHash.put("stale_9,64,9", 333);

        assertTrue(sync.applyServerBlob(blob(live), dir));

        assertFalse(state.waypointRevs.containsKey("stale_9,64,9"));
        assertFalse(sync.scanHash.containsKey("stale_9,64,9"));
        assertEquals(Long.valueOf(2), state.waypointRevs.get(live.id));
    }

    @Test
    public void keepsLocallyCreatedUnsyncedWaypoints() throws Exception {
        WaypointRecord local = record("mine_1,64,2", "mine", -1);
        writeWaypoint(local);
        // Never server-acked, so no waypointRevs entry: pruning must not touch it.
        state.waypointRevs.put("team_5,64,6", 4L);

        assertTrue(sync.applyServerBlob(blob(), dir));

        assertTrue(waypointFile(local).isFile());
        assertFalse(state.waypointRevs.containsKey("team_5,64,6"));

        // And the next scan still queues it for upload.
        sync.scan(dir);
        sync.flush();
        assertEquals(1, sent.size());
        assertEquals(local.id, sent.get(0).id);
    }

    @Test
    public void applyStillWritesDeletesAndGates() throws Exception {
        WaypointRecord added = record("add_1,64,2", "add", 5);
        WaypointRecord dying = record("del_3,64,4", "del", 6);
        dying.deleted = true;
        writeWaypoint(dying);
        state.waypointRevs.put(dying.id, 3L);
        // Listed but already at this rev: skipped by the gate, yet not pruned.
        WaypointRecord same = record("same_7,64,8", "same", 9);
        writeWaypoint(same);
        state.waypointRevs.put(same.id, 9L);

        assertTrue(sync.applyServerBlob(blob(added, dying, same), dir));

        assertTrue(waypointFile(added).isFile());
        assertFalse(waypointFile(dying).exists());
        assertTrue(waypointFile(same).isFile());
        assertEquals(Long.valueOf(5), state.waypointRevs.get(added.id));
        assertEquals(Long.valueOf(6), state.waypointRevs.get(dying.id));
        assertEquals(
            added.toWaypointJson()
                .hashCode(),
            (int) sync.scanHash.get(added.id));
        WaypointRecord parsed = WaypointRecord
            .fromWaypointJson(new String(Files.readAllBytes(waypointFile(added).toPath()), StandardCharsets.UTF_8));
        assertEquals("add", parsed.name);

        // Re-applying the identical blob is a no-op: everything is listed and rev-gated.
        assertFalse(sync.applyServerBlob(blob(added, dying, same), dir));
    }

    @Test
    public void malformedBlobIsIgnored() {
        assertFalse(sync.applyServerBlob("not json", dir));
        assertFalse(sync.applyServerBlob("{}", dir));
    }

    @Test
    public void deathWaypointsAreNotUploaded() throws Exception {
        WaypointRecord death = record("Death 12_5,64,7", "Death 12", -1);
        death.type = "Death";
        WaypointRecord normal = record("home_1,64,2", "home", -1);
        normal.type = "Normal";
        writeWaypoint(death);
        writeWaypoint(normal);

        sync.scan(dir);
        sync.flush();

        // Only the normal waypoint is queued; the death point stays entirely local.
        assertEquals(1, sent.size());
        assertEquals(normal.id, sent.get(0).id);
        // Untracked for deletion detection too: scanHash only holds shareable ids.
        assertTrue(sync.scanHash.containsKey(normal.id));
        assertFalse(sync.scanHash.containsKey(death.id));
    }

    @Test
    public void deathWaypointsUploadWhenSharingEnabled() throws Exception {
        com.donkeytt.journeysync.Config.shareDeathWaypoints = true;
        try {
            WaypointRecord death = record("Death 12_5,64,7", "Death 12", -1);
            death.type = "Death";
            writeWaypoint(death);

            sync.scan(dir);
            sync.flush();

            assertEquals(1, sent.size());
            assertEquals(death.id, sent.get(0).id);
        } finally {
            com.donkeytt.journeysync.Config.shareDeathWaypoints = false;
        }
    }

    @Test
    public void previouslySyncedDeathWaypointIsTombstonedOnce() throws Exception {
        // A death waypoint already shared by an older version must be unsynced exactly once:
        // one tombstone goes out, and later scans stay quiet.
        WaypointRecord death = record("Death 12_5,64,7", "Death 12", -1);
        death.type = "Death";
        writeWaypoint(death);
        state.waypointRevs.put(death.id, 9L);

        sync.scan(dir);
        sync.flush();
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).deleted);

        sent.clear();
        sync.scan(dir);
        sync.flush();
        assertTrue(sent.isEmpty());
    }

    @Test
    public void selfUnsyncedDeathTombstoneKeepsLocalFile() throws Exception {
        // After we tombstone our own death waypoint, the blob echo of that tombstone must NOT
        // delete the user's local file - only the rev advances.
        WaypointRecord death = record("Death 12_5,64,7", "Death 12", -1);
        death.type = "Death";
        writeWaypoint(death);
        state.waypointRevs.put(death.id, 9L);

        sync.scan(dir);
        sync.flush();
        assertEquals(1, sent.size());

        WaypointRecord tombstone = record(death.id, "Death 12", 15);
        tombstone.deleted = true;
        assertTrue(sync.applyServerBlob(blob(tombstone), dir));

        assertTrue("own death waypoint file must survive", waypointFile(death).isFile());
        assertEquals(Long.valueOf(15), state.waypointRevs.get(death.id));
    }
}
