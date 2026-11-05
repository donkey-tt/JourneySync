package com.donkeytt.journeysync.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import com.donkeytt.journeysync.data.Manifest;
import com.donkeytt.journeysync.data.WaypointRecord;

/**
 * Pure helpers for combining two scopes' views and for the solo -> team waypoint migration.
 * The "overlay" scope always wins on id/path conflicts - the player-visible view is
 * {@code global + own}, with own winning.
 */
public final class ScopeMerge {

    private ScopeMerge() {}

    /**
     * Union of {@code base.tiles} and {@code overlay.tiles}; overlay entries win on the same
     * relative path. Returns a fresh Manifest; inputs are not modified.
     */
    public static Manifest mergeManifests(Manifest base, Manifest overlay) {
        Manifest merged = new Manifest();
        merged.tiles.putAll(base.tiles);
        merged.tiles.putAll(overlay.tiles);
        return merged;
    }

    /**
     * Merge waypoint records: overlay wins on id conflict, including when the overlay record is a
     * tombstone (a deletion in the own scope shadows a live global record). The merged view is
     * read-only (serialized to a blob); records are shared, not copied.
     */
    public static Map<String, WaypointRecord> mergeWaypoints(List<WaypointRecord> base, List<WaypointRecord> overlay) {
        Map<String, WaypointRecord> merged = new LinkedHashMap<String, WaypointRecord>();
        if (base != null) {
            for (WaypointRecord r : base) {
                if (r != null && r.id != null) {
                    merged.put(r.id, r);
                }
            }
        }
        if (overlay != null) {
            for (WaypointRecord r : overlay) {
                if (r != null && r.id != null) {
                    merged.put(r.id, r);
                }
            }
        }
        return merged;
    }

    /**
     * Records that migrate when a player's scope becomes a team scope: their own non-deleted
     * records (owner == ownerUuid), as deep copies re-stamped with a fresh rev from the shared
     * epoch. Tombstones stay behind (and are re-stamped tombstones in the source store by the
     * caller) so the deletion remains visible to the old scope.
     */
    public static Map<String, WaypointRecord> migrateOwned(List<WaypointRecord> records, String ownerUuid,
        LongSupplier revs) {
        Map<String, WaypointRecord> out = new LinkedHashMap<String, WaypointRecord>();
        for (WaypointRecord r : records) {
            if (r != null && !r.deleted && ownerUuid.equals(r.owner)) {
                WaypointRecord c = copy(r);
                c.rev = revs.getAsLong();
                out.put(c.id, c);
            }
        }
        return out;
    }

    /** Deep copy via the JourneyMap-schema JSON round trip (same trick as ServerWaypointStore). */
    public static WaypointRecord copy(WaypointRecord r) {
        return WaypointRecord.fromWaypointJson(r.toWaypointJson());
    }
}
