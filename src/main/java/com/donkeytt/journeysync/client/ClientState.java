package com.donkeytt.journeysync.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import com.donkeytt.journeysync.JourneySync;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Per-server client state persisted under {@code config/journeysync/}: which tiles are already
 * synced (path -> sha1, plus file mtime/size to skip re-hashing on join) and the last applied
 * waypoint revision per id. Enables resume-after-disconnect.
 */
public class ClientState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    public static class TileState {

        public String sha1;
        public long mtime;
        public long size;

        public TileState() {}

        public TileState(String sha1, long mtime, long size) {
            this.sha1 = sha1;
            this.mtime = mtime;
            this.size = size;
        }
    }

    /** relPath -> synced tile state */
    public Map<String, TileState> syncedTiles = new LinkedHashMap<String, TileState>();
    /** waypoint id -> last applied server revision */
    public Map<String, Long> waypointRevs = new LinkedHashMap<String, Long>();

    public static ClientState load(File file) {
        ClientState state = new ClientState();
        try {
            if (file.isFile()) {
                ClientState parsed = GSON
                    .fromJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), ClientState.class);
                if (parsed != null) {
                    state = parsed;
                }
            }
        } catch (Exception e) {
            JourneySync.LOG.error("Failed loading client state {}, starting fresh", file, e);
        }
        if (state.syncedTiles == null) {
            state.syncedTiles = new LinkedHashMap<String, TileState>();
        }
        if (state.waypointRevs == null) {
            state.waypointRevs = new LinkedHashMap<String, Long>();
        }
        return state;
    }

    public void save(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            Files.write(
                file.toPath(),
                GSON.toJson(this)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            JourneySync.LOG.error("Failed saving client state {}", file, e);
        }
    }

    /** Build the relPath -> sha1 map, reusing recorded hashes for unchanged files. */
    public Map<String, String> syncedSha1Map() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, TileState> e : syncedTiles.entrySet()) {
            out.put(e.getKey(), e.getValue().sha1);
        }
        return out;
    }
}
