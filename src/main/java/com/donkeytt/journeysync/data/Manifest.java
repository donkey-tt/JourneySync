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
     * @param localSize  tile file sizes on local disk (relPath -> bytes)
     * @return paths whose content on the server differs from both local disk and synced state
     */
    public Set<String> diffDownload(Map<String, String> localSha1, Map<String, String> syncedSha1,
        Map<String, Long> localSize) {
        Set<String> out = new LinkedHashSet<String>();
        for (Map.Entry<String, TileEntry> e : tiles.entrySet()) {
            String path = e.getKey();
            TileEntry server = e.getValue();
            String serverHash = server.sha1;
            String local = localSha1.get(path);
            if (local != null && local.equals(serverHash)) {
                continue; // already identical on disk, nothing to do
            }
            String synced = syncedSha1.get(path);
            if (synced != null && synced.equals(serverHash)) {
                // We synced this content before. Usually the local file then matches too (the
                // skip above) - but JourneyMap can re-render the tile from incomplete region
                // data, shrinking it. Restore the fuller server version in that case.
                Long size = localSize.get(path);
                if (!isRegression(server.size, size == null ? 0 : size.longValue())) {
                    continue; // local render may be newer content; watch-phase upload handles it
                }
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
     * @param localSize tile file sizes on local disk (relPath -> bytes)
     */
    public Set<String> diffUpload(Map<String, String> localSha1, Map<String, Long> localSize) {
        Set<String> out = new LinkedHashSet<String>();
        for (Map.Entry<String, String> e : localSha1.entrySet()) {
            TileEntry server = tiles.get(e.getKey());
            if (server == null) {
                out.add(e.getKey());
                continue;
            }
            if (server.sha1.equals(e.getValue())) {
                continue;
            }
            Long size = localSize.get(e.getKey());
            if (isRegression(server.size, size == null ? 0 : size.longValue())) {
                continue; // local render looks less complete than the server's; never clobber it
            }
            out.add(e.getKey());
        }
        return out;
    }

    /**
     * A local render that clearly holds less map content than a version we have already seen
     * (on the server or in our own sync history). JourneyMap re-renders tiles from whatever
     * region data is loaded, which can be a strict subset of what was rendered before -
     * uploading that would regress the shared map. Exploration only adds detail, so tiles grow
     * monotonically and a notably smaller file means an incomplete re-render.
     */
    public static boolean isRegression(long knownSize, long localSize) {
        return knownSize > 0 && localSize > 0 && localSize * 5 < knownSize * 4;
    }
}
