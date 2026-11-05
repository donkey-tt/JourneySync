package com.donkeytt.journeysync.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.donkeytt.journeysync.JourneySync;
import com.google.gson.Gson;

/**
 * Server-wide waypoint revision counter, persisted as {@code {"epoch":N}} in
 * {@code journeysync/epoch}. Waypoint revisions must be monotonic across ALL scopes, so every
 * accepted change (in any scope) and every scope migration takes its {@code rev} from this single
 * counter instead of a per-store sequence. Lazily-loaded stores raise the floor on load so a lost
 * epoch file can never re-issue a lower rev than a store already carries.
 */
public class WaypointEpoch {

    private final File file;
    private long epoch;

    public WaypointEpoch(File file) {
        this.file = file;
        this.epoch = load(file);
    }

    /** Next revision for a newly accepted waypoint change. */
    public synchronized long next() {
        return ++epoch;
    }

    /** Raise the counter to at least {@code floor} (e.g. a store epoch loaded from disk). */
    public synchronized void raiseFloor(long floor) {
        if (floor > epoch) {
            epoch = floor;
        }
    }

    public synchronized long current() {
        return epoch;
    }

    public synchronized void save() {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        try {
            Files.write(
                file.toPath(),
                new Gson().toJson(new Stored(epoch))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            JourneySync.LOG.error("Failed saving waypoint epoch {}", file, e);
        }
    }

    private static long load(File file) {
        try {
            if (file.isFile()) {
                String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                Stored stored = new Gson().fromJson(json, Stored.class);
                if (stored != null) {
                    return Math.max(0, stored.epoch);
                }
            }
        } catch (Exception e) {
            JourneySync.LOG.error("Failed loading waypoint epoch {}, starting at 0", file, e);
        }
        return 0;
    }

    private static class Stored {

        long epoch;

        Stored(long epoch) {
            this.epoch = epoch;
        }
    }
}
