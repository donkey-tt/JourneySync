package com.donkeytt.journeysync.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.WaypointRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Client-side waypoint synchronization: detects local waypoint-file changes and queues them for
 * the server, and applies full waypoint blobs pushed by the server. Runs entirely on the sync
 * worker thread.
 */
final class WaypointSync {

    private static final Gson GSON = new GsonBuilder().create();

    private final ClientState state;
    private final Consumer<WaypointRecord> sender;

    /** Local changes waiting to be sent. */
    private final List<WaypointRecord> outbox = new ArrayList<WaypointRecord>();

    /**
     * Waypoint id -> hash of the file content as seen at the last scan (echo suppression). Package-private for tests.
     */
    final Map<String, Integer> scanHash = new HashMap<String, Integer>();

    /** Synced ids already tombstoned for having become unshareable (e.g. death waypoints). */
    private final Set<String> unsyncSent = new HashSet<String>();

    WaypointSync(ClientState state, Consumer<WaypointRecord> sender) {
        this.state = state;
        this.sender = sender;
    }

    /** Drop all pending state (new connection). */
    void reset() {
        outbox.clear();
        scanHash.clear();
        unsyncSent.clear();
    }

    /** Scan the JM waypoints directory; queue local changes (adds, edits, deletions). */
    void scan(File jmDir) {
        File waypointDir = new File(jmDir, "waypoints");
        File[] files = waypointDir.listFiles();
        Map<String, Integer> current = new LinkedHashMap<String, Integer>();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || !f.getName()
                    .endsWith(".json")) {
                    continue;
                }
                try {
                    String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    WaypointRecord record = WaypointRecord.fromWaypointJson(json);
                    if (record == null || record.id == null || record.id.isEmpty()) {
                        continue;
                    }
                    if (!Config.shareDeathWaypoints && record.isDeath()) {
                        // Death waypoints stay local: not queued, not tracked for deletions.
                        // One-time unsync: if a previous version already shared this id, send a
                        // tombstone so the scope store drops it, then stop tracking it.
                        if (state.waypointRevs.containsKey(record.id) && unsyncSent.add(record.id)) {
                            WaypointRecord tombstone = new WaypointRecord();
                            tombstone.id = record.id;
                            tombstone.deleted = true;
                            outbox.add(tombstone);
                        }
                        continue;
                    }
                    current.put(record.id, json.hashCode());
                    Integer previous = scanHash.get(record.id);
                    boolean knownServerSide = state.waypointRevs.containsKey(record.id);
                    if (!knownServerSide || previous == null || previous.intValue() != json.hashCode()) {
                        outbox.add(record);
                    }
                } catch (Exception e) {
                    JourneySync.LOG.error("Failed reading waypoint {}", f, e);
                }
            }
        }
        // Detect deletions of locally created/shared waypoints.
        for (String id : new HashSet<String>(scanHash.keySet())) {
            if (!current.containsKey(id) && state.waypointRevs.containsKey(id)) {
                WaypointRecord tombstone = new WaypointRecord();
                tombstone.id = id;
                tombstone.deleted = true;
                outbox.add(tombstone);
                scanHash.remove(id); // fire once; server rejects duplicates harmlessly
            }
        }
        scanHash.clear();
        scanHash.putAll(current);
    }

    /** Send queued local changes. */
    void flush() {
        if (outbox.isEmpty()) {
            return;
        }
        List<WaypointRecord> batch = new ArrayList<WaypointRecord>(outbox);
        outbox.clear();
        for (WaypointRecord record : batch) {
            sender.accept(record);
        }
    }

    /**
     * Apply a full-waypoint blob from the server (revision-gated, tombstones delete files).
     * Returns true when anything changed on disk or in sync state, so the caller can trigger a
     * JM waypoint reload and persist the state.
     *
     * <p>
     * The blob is the complete state of the client's readable scopes, so afterwards every
     * server-known id absent from the record list is pruned: the record left our scope (team
     * switch), so its file is deleted and its rev/scan-hash bookkeeping dropped rather than
     * tombstoned. Locally created waypoints not yet acknowledged by the server are absent from
     * {@code state.waypointRevs} and therefore untouched.
     * </p>
     */
    boolean applyServerBlob(String json, File jmDir) {
        try {
            Stored stored = GSON.fromJson(json, Stored.class);
            if (stored == null || stored.records == null) {
                return false;
            }
            File waypointDir = new File(jmDir, "waypoints");
            Set<String> listed = new HashSet<String>();
            boolean any = false;
            for (WaypointRecord record : stored.records) {
                listed.add(record.id);
                Long applied = state.waypointRevs.get(record.id);
                if (applied != null && applied.longValue() >= record.rev) {
                    continue;
                }
                if (!Config.shareDeathWaypoints && !record.deleted && record.isDeath()) {
                    // Death waypoints are private: never let a leftover server record (from an
                    // older version) rewrite the local file. Tombstones still flow below.
                    state.waypointRevs.put(record.id, Long.valueOf(record.rev));
                    any = true;
                    continue;
                }
                File file = new File(waypointDir, record.fileName());
                if (record.deleted) {
                    // A record we ourselves unsynced (e.g. our own death waypoint) must not
                    // delete the user's local file; everyone else deletes their synced copy.
                    if (!unsyncSent.contains(record.id) && file.isFile() && !file.delete()) {
                        JourneySync.LOG.warn("Could not delete waypoint file {}", file);
                    }
                } else {
                    File parent = file.getParentFile();
                    if (!parent.isDirectory()) {
                        parent.mkdirs();
                    }
                    Files.write(
                        file.toPath(),
                        record.toWaypointJson()
                            .getBytes(StandardCharsets.UTF_8));
                }
                state.waypointRevs.put(record.id, Long.valueOf(record.rev));
                scanHash.put(
                    record.id,
                    record.toWaypointJson()
                        .hashCode());
                any = true;
            }
            // Prune stale ids: the blob covers every readable scope, so a server-known id that
            // is not listed no longer belongs to us (e.g. a team record after switching teams).
            for (String id : new HashSet<String>(state.waypointRevs.keySet())) {
                if (listed.contains(id)) {
                    continue;
                }
                state.waypointRevs.remove(id);
                scanHash.remove(id);
                WaypointRecord probe = new WaypointRecord();
                probe.id = id;
                File file = new File(waypointDir, probe.fileName());
                if (file.isFile() && !file.delete()) {
                    JourneySync.LOG.warn("Could not delete out-of-scope waypoint file {}", file);
                }
                any = true;
            }
            return any;
        } catch (Exception e) {
            JourneySync.LOG.error("Failed applying waypoint blob", e);
            return false;
        }
    }

    private static class Stored {

        List<WaypointRecord> records;
    }
}
