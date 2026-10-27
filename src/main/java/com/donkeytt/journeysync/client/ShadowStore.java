package com.donkeytt.journeysync.client;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.Manifest;
import com.donkeytt.journeysync.data.TileEntry;
import com.donkeytt.journeysync.data.TileKey;
import com.donkeytt.journeysync.io.Hashing;
import com.donkeytt.journeysync.io.TileFiles;

/**
 * Local mirror of the server-authoritative tile bytes, one copy per tile under
 * {@code config/journeysync/shadow/<serverId>/}. The pixel-level upload guards compare a local
 * render against this reference: size heuristics cannot tell a small content loss from a
 * re-encode jitter, but "no explored pixel the reference lacks" can. Worker-thread only.
 */
final class ShadowStore {

    private final File dir;

    ShadowStore(File dir) {
        this.dir = dir;
    }

    File file(String relPath) {
        return new File(dir, relPath);
    }

    /** Store (or refresh) the server-side reference bytes for a tile. */
    void write(String relPath, byte[] data) {
        File target = file(relPath);
        File parent = target.getParentFile();
        if (parent == null) {
            return;
        }
        parent.mkdirs();
        try {
            File tmp = new File(parent, target.getName() + ".tmp");
            Files.write(tmp.toPath(), data);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            JourneySync.LOG.debug("Failed writing shadow tile {}", relPath, e);
        }
    }

    /**
     * The server-authoritative bytes for a tile, or null when no shadow exists or it predates the
     * server's current version (and so cannot judge new pixels).
     */
    byte[] reference(String relPath, TileEntry serverEntry) {
        if (serverEntry == null) {
            return null;
        }
        File shadow = file(relPath);
        if (!shadow.isFile()) {
            return null;
        }
        try {
            byte[] reference = Files.readAllBytes(shadow.toPath());
            if (!Hashing.sha1Hex(reference)
                .equals(serverEntry.sha1)) {
                return null; // shadow predates the server's current version; cannot judge
            }
            return reference;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Seed shadows from local tiles that already match the server byte-for-byte (covers content
     * synced before the shadow existed), and drop shadows the server no longer tracks.
     */
    void backfill(Manifest manifest, LocalTileIndex local, TileFiles jmTiles) {
        if (manifest == null) {
            return;
        }
        Set<String> tracked = manifest.tiles.keySet();
        prune(dir, "", tracked);
        for (Map.Entry<String, TileEntry> e : manifest.tiles.entrySet()) {
            String relPath = e.getKey();
            if (file(relPath).isFile()) {
                continue;
            }
            LocalTileIndex.Stat stat = local.stat(relPath);
            if (stat == null || !stat.sha1.equals(e.getValue().sha1)) {
                continue;
            }
            try {
                TileKey key = TileKey.parse(relPath);
                File src = key == null ? null : jmTiles.resolve(key);
                if (src != null && src.isFile()) {
                    write(relPath, Files.readAllBytes(src.toPath()));
                }
            } catch (Exception ex) {
                JourneySync.LOG.debug("Failed backfilling shadow {}", relPath, ex);
            }
        }
    }

    private void prune(File dir, String prefix, Set<String> tracked) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                prune(f, rel, tracked);
                String[] remaining = f.list();
                if (remaining != null && remaining.length == 0) {
                    f.delete();
                }
            } else if (!tracked.contains(rel)) {
                f.delete();
            }
        }
    }
}
