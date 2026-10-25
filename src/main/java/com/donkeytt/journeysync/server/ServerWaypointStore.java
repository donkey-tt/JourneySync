package com.donkeytt.journeysync.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.WaypointRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Server-side aggregate of all shared waypoints, persisted as {@code waypoints.json} under the
 * JourneySync data directory. Records are kept as tombstones after deletion so late-joining
 * clients learn about removals.
 */
public class ServerWaypointStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private final File file;
    private final Map<String, WaypointRecord> byId = new LinkedHashMap<String, WaypointRecord>();

    /** Revision sequence: every accepted change assigns {@code rev = ++epoch} to its record. */
    public long epoch;

    public ServerWaypointStore(File file) {
        this.file = file;
        load();
    }

    public synchronized void load() {
        byId.clear();
        epoch = 0;
        if (!file.isFile()) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Stored stored = GSON.fromJson(json, Stored.class);
            if (stored != null) {
                epoch = stored.epoch;
                if (stored.records != null) {
                    for (WaypointRecord r : stored.records) {
                        byId.put(r.id, r);
                    }
                }
            }
        } catch (Exception e) {
            JourneySync.LOG.error("Failed loading waypoint store {}, starting empty", file, e);
        }
    }

    public synchronized void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            Stored stored = new Stored();
            stored.epoch = epoch;
            stored.records = new ArrayList<WaypointRecord>(byId.values());
            Files.write(
                file.toPath(),
                GSON.toJson(stored)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            JourneySync.LOG.error("Failed saving waypoint store {}", file, e);
        }
    }

    /**
     * Apply a client-proposed change. Assigns owner for new records and the next revision for
     * every accepted change.
     *
     * @return the authoritative record to broadcast, or null when rejected
     */
    public synchronized WaypointRecord apply(WaypointRecord incoming, String ownerUuid, boolean isOp) {
        if (incoming == null || incoming.id == null || incoming.id.isEmpty()) {
            return null;
        }
        WaypointRecord existing = byId.get(incoming.id);

        if (incoming.deleted) {
            if (existing == null || existing.deleted) {
                return null;
            }
            if (!existing.owner.isEmpty() && !existing.owner.equals(ownerUuid) && !isOp) {
                return null; // only the owner (or an op) may delete
            }
            existing.deleted = true;
            existing.rev = ++epoch;
            return copy(existing);
        }

        WaypointRecord accepted = incoming;
        accepted.owner = (existing == null || existing.owner.isEmpty()) ? ownerUuid : existing.owner;
        accepted.rev = ++epoch;
        accepted.deleted = false;
        byId.put(accepted.id, accepted);
        return copy(accepted);
    }

    public synchronized List<WaypointRecord> allRecords() {
        return new ArrayList<WaypointRecord>(byId.values());
    }

    public synchronized String toJson() {
        Stored stored = new Stored();
        stored.epoch = epoch;
        stored.records = new ArrayList<WaypointRecord>(byId.values());
        return GSON.toJson(stored);
    }

    private static WaypointRecord copy(WaypointRecord r) {
        return GSON.fromJson(GSON.toJson(r), WaypointRecord.class);
    }

    private static class Stored {

        long epoch;
        List<WaypointRecord> records;
    }
}
