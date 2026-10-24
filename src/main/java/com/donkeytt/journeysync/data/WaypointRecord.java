package com.donkeytt.journeysync.data;

import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A shared waypoint record. Field names mirror JourneyMap's waypoint JSON schema so the record can
 * be written straight to {@code waypoints/<fileName>.json}, plus sync bookkeeping
 * ({@link #owner}, {@link #rev}, {@link #deleted} tombstone).
 */
public class WaypointRecord implements Comparable<WaypointRecord> {

    /** Matches JM's Waypoint.getFileName() sanitization. */
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|]");

    // --- JourneyMap waypoint schema ---
    public String id;
    public String name;
    public String icon;
    public int x;
    public int y;
    public int z;
    public int r;
    public int g;
    public int b;
    public boolean enable;
    public String type;
    public String origin;
    public SortedSet<Integer> dimensions = new TreeSet<Integer>();

    // --- sync bookkeeping ---
    /** Owning player UUID; empty for legacy/server-created records. */
    public String owner = "";
    /** Server-assigned revision, strictly increasing; -1 = not yet accepted by the server. */
    public long rev = -1;
    /** Tombstone: record deleted but kept for sync propagation. */
    public boolean deleted;

    public WaypointRecord() {}

    /** Recompute id from name and coordinates, matching JM's {@code name_x,y,z} convention. */
    public String computeId() {
        return name + "_" + x + "," + y + "," + z;
    }

    /** JM-compatible on-disk file name. */
    public String fileName() {
        String base = (id == null || id.isEmpty()) ? computeId() : id;
        return ILLEGAL.matcher(base)
            .replaceAll("_") + ".json";
    }

    /** Build a fresh local record from parsed waypoint JSON fields. */
    public static WaypointRecord fromWaypointJson(String json) {
        return WaypointJson.GSON.fromJson(json, WaypointRecord.class);
    }

    public String toWaypointJson() {
        return WaypointJson.GSON.toJson(this);
    }

    @Override
    public int compareTo(WaypointRecord other) {
        return Long.compare(this.rev, other.rev);
    }

    /** Gson instance matching JM's pretty-printed waypoint files. */
    public static final class WaypointJson {

        static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting()
            .create();
    }
}
