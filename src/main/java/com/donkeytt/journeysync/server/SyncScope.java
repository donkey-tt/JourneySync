package com.donkeytt.journeysync.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.Manifest;
import com.donkeytt.journeysync.io.TileFiles;

/**
 * One scope's on-disk store: {@code tiles/}, {@code manifest.json} and {@code waypoints.json}
 * under {@code journeysync/scopes/<dirName>}. Loading a scope is lazy - the directory is only
 * materialized (or read) when a player actually needs the scope.
 */
public class SyncScope {

    public final String key;
    public final File dir;
    public final TileFiles tileFiles;
    public final Manifest manifest;
    public final ServerWaypointStore waypointStore;

    public SyncScope(String key, File dir) {
        this.key = key;
        this.dir = dir;
        this.tileFiles = new TileFiles(new File(dir, "tiles"));
        this.manifest = Manifest.fromJson(readStringQuietly(new File(dir, "manifest.json")));
        this.waypointStore = new ServerWaypointStore(new File(dir, "waypoints.json"));
    }

    public void save() {
        writeStringQuietly(new File(dir, "manifest.json"), manifest.toJson());
        waypointStore.save();
    }

    static String readStringQuietly(File f) {
        try {
            if (f.isFile()) {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            JourneySync.LOG.error("Failed reading {}", f, e);
        }
        return "";
    }

    static void writeStringQuietly(File f, String s) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            JourneySync.LOG.error("Failed writing {}", f, e);
        }
    }
}
