package com.donkeytt.journeysync.data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The server-authoritative tile index: relative path -> {@link TileEntry}.
 *
 * <p>
 * Serialized with Gson, gzipped and transferred in {@code BlobPacket}s. {@link #epoch} bumps on
 * every accepted upload so clients can cheaply detect server-side changes in future versions.
 * </p>
 */
public class Manifest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    public long epoch;
    public Map<String, TileEntry> tiles = new LinkedHashMap<String, TileEntry>();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static Manifest fromJson(String json) {
        Manifest m = GSON.fromJson(json, Manifest.class);
        if (m == null) {
            m = new Manifest();
        }
        if (m.tiles == null) {
            m.tiles = new LinkedHashMap<String, TileEntry>();
        }
        return m;
    }

    /**
     * Decide what the client needs to download.
     *
     * @param localSha1  tiles already present on local disk (relPath -> sha1), from a full disk scan
     * @param syncedSha1 tiles the client already synced before (relPath -> sha1)
     * @return paths whose content on the server differs from both local disk and synced state
     */
    public Set<String> diffDownload(Map<String, String> localSha1, Map<String, String> syncedSha1) {
        Set<String> out = new LinkedHashSet<String>();
        for (Map.Entry<String, TileEntry> e : tiles.entrySet()) {
            String path = e.getKey();
            String serverHash = e.getValue().sha1;
            String local = localSha1.get(path);
            if (local != null && local.equals(serverHash)) {
                continue; // already identical on disk, nothing to do
            }
            String synced = syncedSha1.get(path);
            if (synced != null && synced.equals(serverHash)) {
                continue; // we already have this content somewhere (e.g. partially written file)
            }
            out.add(path);
        }
        return out;
    }

    /**
     * Decide what the client should upload: local tiles the server does not have, or has with
     * different content.
     *
     * @param localSha1 tiles present on local disk (relPath -> sha1)
     */
    public Set<String> diffUpload(Map<String, String> localSha1) {
        Set<String> out = new LinkedHashSet<String>();
        for (Map.Entry<String, String> e : localSha1.entrySet()) {
            TileEntry server = tiles.get(e.getKey());
            if (server == null || !server.sha1.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
