package com.donkeytt.journeysync.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

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

    /**
     * Highest revision this store has ever assigned, persisted for back-compat. New revisions come
     * from the server-wide {@link WaypointEpoch}; this value only serves as a load-time floor.
     */
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
     * Apply a client-proposed change. Assigns owner for new records and a revision drawn from the
     * shared epoch counter for every accepted change.
     *
     * @param revs shared revision source (WaypointEpoch::next), called once per accepted change
     * @return the authoritative record to broadcast, or null when rejected
     */
    public synchronized WaypointRecord apply(WaypointRecord incoming, String ownerUuid, boolean isOp,
        LongSupplier revs) {
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
            existing.rev = revs.getAsLong();
            epoch = Math.max(epoch, existing.rev);
            return copy(existing);
        }

        WaypointRecord accepted = incoming;
        accepted.owner = (existing == null || existing.owner.isEmpty()) ? ownerUuid : existing.owner;
        accepted.rev = revs.getAsLong();
        epoch = Math.max(epoch, accepted.rev);
        accepted.deleted = false;
        byId.put(accepted.id, accepted);
        return copy(accepted);
    }

    /** Store or replace a record verbatim (used by scope migration; revs are pre-assigned). */
    public synchronized void put(WaypointRecord record) {
        if (record != null && record.id != null) {
            byId.put(record.id, record);
            epoch = Math.max(epoch, record.rev);
        }
    }

    public synchronized List<WaypointRecord> allRecords() {
        return new ArrayList<WaypointRecord>(byId.values());
    }

    /** Whether this store holds a record (live or tombstone) for {@code id}. */
    public synchronized boolean has(String id) {
        return byId.containsKey(id);
    }

    public synchronized String toJson() {
        return toJson(epoch, byId.values());
    }

    /**
     * Serialize an arbitrary record set in the same {@code {epoch, records[]}} wire format this
     * store persists - used for merged (global + own scope) waypoint blobs.
     */
    public static String toJson(long epoch, Collection<WaypointRecord> records) {
        Stored stored = new Stored();
        stored.epoch = epoch;
        stored.records = new ArrayList<WaypointRecord>(records);
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
