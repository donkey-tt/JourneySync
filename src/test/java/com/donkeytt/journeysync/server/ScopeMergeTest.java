package com.donkeytt.journeysync.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.donkeytt.journeysync.data.Manifest;
import com.donkeytt.journeysync.data.TileEntry;
import com.donkeytt.journeysync.data.WaypointRecord;

public class ScopeMergeTest {

    private static WaypointRecord rec(String id, String owner, long rev, boolean deleted) {
        WaypointRecord r = new WaypointRecord();
        r.id = id;
        r.name = id;
        r.owner = owner;
        r.rev = rev;
        r.deleted = deleted;
        return r;
    }

    @Test
    public void manifestOverlayWinsPathConflicts() {
        Manifest base = new Manifest();
        base.tiles.put("DIM0/day/0,0.png", new TileEntry("aaa", 10));
        base.tiles.put("DIM0/day/1,0.png", new TileEntry("bbb", 10));
        Manifest overlay = new Manifest();
        overlay.tiles.put("DIM0/day/0,0.png", new TileEntry("own", 20));

        Manifest merged = ScopeMerge.mergeManifests(base, overlay);
        assertEquals(2, merged.tiles.size());
        assertEquals("own", merged.tiles.get("DIM0/day/0,0.png").sha1);
        assertEquals("bbb", merged.tiles.get("DIM0/day/1,0.png").sha1);
        // inputs untouched
        assertEquals("aaa", base.tiles.get("DIM0/day/0,0.png").sha1);
    }

    @Test
    public void waypointOverlayWinsIncludingTombstone() {
        List<WaypointRecord> base = new ArrayList<WaypointRecord>();
        base.add(rec("keep", "u1", 1, false));
        base.add(rec("shadowed", "u1", 2, false));
        List<WaypointRecord> overlay = new ArrayList<WaypointRecord>();
        overlay.add(rec("shadowed", "u2", 5, true)); // tombstone shadows live base record

        Map<String, WaypointRecord> merged = ScopeMerge.mergeWaypoints(base, overlay);
        assertEquals(2, merged.size());
        assertTrue(merged.get("shadowed").deleted);
        assertEquals("u2", merged.get("shadowed").owner);
        assertFalse(merged.get("keep").deleted);
    }

    @Test
    public void migrateOwnedSelectsOwnLiveRecords() {
        AtomicLong epoch = new AtomicLong(100);
        List<WaypointRecord> solo = new ArrayList<WaypointRecord>();
        solo.add(rec("mine", "u1", 1, false));
        solo.add(rec("mineDeleted", "u1", 2, true)); // tombstone stays behind
        solo.add(rec("theirs", "u2", 3, false)); // someone else's record stays

        Map<String, WaypointRecord> moved = ScopeMerge.migrateOwned(solo, "u1", epoch::incrementAndGet);
        assertEquals(1, moved.size());
        WaypointRecord m = moved.get("mine");
        assertEquals("u1", m.owner);
        assertFalse(m.deleted);
        assertTrue("migrated record is re-stamped above the epoch floor", m.rev > 100);
        // the store's record was not mutated
        assertEquals(1, solo.get(0).rev);
    }

    @Test
    public void migrateOwnedEmptyWhenNothingMatches() {
        List<WaypointRecord> solo = new ArrayList<WaypointRecord>();
        solo.add(rec("theirs", "u2", 3, false));
        solo.add(rec("gone", "u1", 1, true));
        assertTrue(
            ScopeMerge.migrateOwned(solo, "u1", () -> 9L)
                .isEmpty());
        assertTrue(
            ScopeMerge.migrateOwned(new ArrayList<WaypointRecord>(), "u1", () -> 9L)
                .isEmpty());
    }

    @Test
    public void mergedWaypointsJsonUsesSharedEpoch() {
        List<WaypointRecord> global = new ArrayList<WaypointRecord>();
        global.add(rec("g", "u1", 3, false));
        String json = ServerWaypointStore.toJson(
            42,
            ScopeMerge.mergeWaypoints(global, null)
                .values());
        assertTrue(json.contains("\"epoch\": 42"));
        assertTrue(json.contains("\"id\": \"g\""));
        assertNull(
            ScopeMerge.mergeWaypoints(null, null)
                .get("anything"));
    }
}
