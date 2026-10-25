package com.donkeytt.journeysync.client;

import java.io.File;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;

import com.donkeytt.journeysync.JourneySync;

import cpw.mods.fml.common.Loader;

/**
 * Single choke point for all JourneyMap integration. Everything is reflective: JourneyMap is an
 * optional runtime dependency and this mod must load and degrade gracefully without it.
 *
 * <p>
 * Reflection targets verified against JourneyMap 1.7.10-5.2.10:
 * </p>
 * <ul>
 * <li>{@code journeymap.client.io.FileHandler.getJMWorldDir(Minecraft)} — current world's cache
 * directory</li>
 * <li>{@code journeymap.client.model.RegionImageCache.instance().clear()} — force JM to re-read
 * tiles from disk</li>
 * <li>{@code journeymap.client.waypoint.WaypointStore.instance().reset()} — force JM to re-scan
 * the waypoints directory</li>
 * </ul>
 */
public final class JmBridge {

    private static boolean jmPresent;
    private static boolean resolved;
    private static Method getJMWorldDir;
    private static Object regionImageCache;
    private static Method regionCacheClear;
    private static Object waypointStore;
    private static Method waypointReset;

    // targeted single-tile texture refresh (falls back to the full cache clear when unavailable)
    private static boolean tileRefreshResolved;
    private static Method mapTypeDay;
    private static Method mapTypeNight;
    private static Method regionCoordFromRegionPos;
    private static Method getRegionImageSet;
    private static Method getHolder;
    private static Method holderGetTexture;
    private static Method textureSetImage;

    private static long lastRegionClear;
    private static long lastWaypointReset;

    private JmBridge() {}

    /** Cheap check; full resolution happens lazily on first use. */
    public static boolean isJourneyMapLoaded() {
        return Loader.isModLoaded("journeymap");
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        jmPresent = isJourneyMapLoaded();
        if (!jmPresent) {
            JourneySync.LOG.warn("JourneyMap not loaded - JourneySync client sync is disabled");
            return;
        }
        try {
            Class<?> fileHandler = Class.forName("journeymap.client.io.FileHandler");
            getJMWorldDir = fileHandler.getMethod("getJMWorldDir", Minecraft.class);
        } catch (Throwable t) {
            JourneySync.LOG.error("JourneyMap FileHandler.getJMWorldDir not found; cache dir detection degraded", t);
        }
        try {
            Class<?> cacheClass = Class.forName("journeymap.client.model.RegionImageCache");
            Object cache = cacheClass.getMethod("instance")
                .invoke(null);
            regionCacheClear = cacheClass.getMethod("clear");
            regionImageCache = cache;
        } catch (Throwable t) {
            JourneySync.LOG.error("JourneyMap RegionImageCache integration unavailable", t);
        }
        try {
            Class<?> storeClass = Class.forName("journeymap.client.waypoint.WaypointStore");
            waypointStore = storeClass.getMethod("instance")
                .invoke(null);
            waypointReset = storeClass.getMethod("reset");
        } catch (Throwable t) {
            JourneySync.LOG.error("JourneyMap WaypointStore integration unavailable", t);
        }
        try {
            Class<?> mapTypeClass = Class.forName("journeymap.client.model.MapType");
            mapTypeDay = mapTypeClass.getMethod("day", int.class);
            mapTypeNight = mapTypeClass.getMethod("night", int.class);
            Class<?> coordClass = Class.forName("journeymap.client.model.RegionCoord");
            regionCoordFromRegionPos = coordClass
                .getMethod("fromRegionPos", File.class, int.class, int.class, int.class);
            Class<?> cacheClass = Class.forName("journeymap.client.model.RegionImageCache");
            getRegionImageSet = cacheClass.getMethod("getRegionImageSet", coordClass);
            // getHolder is protected on the abstract ImageSet; only the concrete RegionImageSet
            // widens it to public.
            Class<?> regionImageSetClass = Class.forName("journeymap.client.model.RegionImageSet");
            getHolder = regionImageSetClass.getMethod("getHolder", mapTypeClass);
            Class<?> holderClass = Class.forName("journeymap.client.model.ImageHolder");
            holderGetTexture = holderClass.getMethod("getTexture");
            Class<?> textureClass = Class.forName("journeymap.client.render.texture.TextureImpl");
            textureSetImage = textureClass.getMethod("setImage", java.awt.image.BufferedImage.class, boolean.class);
            tileRefreshResolved = true;
        } catch (Throwable t) {
            JourneySync.LOG.error("JourneyMap targeted tile refresh unavailable; falling back to full cache clears", t);
        }
    }

    /**
     * The JourneyMap cache directory of the currently joined world, e.g.
     * {@code .minecraft/journeymap/data/mp/Minecraft_xxx}. Falls back to the only directory under
     * {@code journeymap/data/mp} when the reflective route is unavailable. Returns null when no
     * directory can be determined.
     */
    public static File getJMWorldDir() {
        resolve();
        if (getJMWorldDir != null) {
            try {
                File dir = (File) getJMWorldDir.invoke(null, Minecraft.getMinecraft());
                if (dir != null) {
                    return dir;
                }
            } catch (Throwable t) {
                if (ConfigDebugOnce.log("worlddir")) {
                    JourneySync.LOG.error("getJMWorldDir failed", t);
                }
            }
        }
        return fallbackMpDir();
    }

    private static File fallbackMpDir() {
        File data = new File(Minecraft.getMinecraft().mcDataDir, "journeymap/data/mp");
        File[] dirs = data.listFiles();
        if (dirs == null) {
            return null;
        }
        File best = null;
        for (File d : dirs) {
            if (!d.isDirectory()) {
                continue;
            }
            if (best == null || d.lastModified() > best.lastModified()) {
                best = d;
            }
        }
        return best;
    }

    /** Force JM to drop its in-memory tile cache so externally written tiles get read from disk. */
    public static void clearRegionImageCache() {
        resolve();
        if (regionCacheClear == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRegionClear < 10_000) {
            return; // throttle: JM rebuilds this cache expensively
        }
        lastRegionClear = now;
        try {
            regionCacheClear.invoke(regionImageCache);
        } catch (Throwable t) {
            if (ConfigDebugOnce.log("regionclear")) {
                JourneySync.LOG.error("RegionImageCache.clear failed", t);
            }
        }
    }

    /**
     * Update a single tile's GL texture from freshly applied bytes. A full
     * {@link #clearRegionImageCache()} expires every viewed tile's texture at once and makes JM
     * re-render all of them from its in-memory region data - stale regions flicker and each
     * re-render can overwrite the freshly restored file. Pushing the new pixels into just this
     * tile's texture avoids that storm entirely. Returns false when the targeted route is
     * unavailable and the caller should fall back to the full clear.
     */
    public static boolean refreshTile(File worldDir, com.donkeytt.journeysync.data.TileKey key, byte[] png) {
        resolve();
        if (!tileRefreshResolved || worldDir == null) {
            return false;
        }
        if (!"day".equals(key.kind) && !"night".equals(key.kind)) {
            return false; // cave/underground layers use vSlice-typed MapTypes; keep the full clear
        }
        try {
            Object mapType = ("night".equals(key.kind) ? mapTypeNight : mapTypeDay).invoke(null, key.dimension);
            Object coord = regionCoordFromRegionPos.invoke(null, worldDir, key.regionX, key.regionZ, key.dimension);
            Object set = getRegionImageSet.invoke(regionImageCache, coord);
            if (set == null) {
                return true; // not live in JM's cache; its next draw reads the file we wrote
            }
            Object holder = getHolder.invoke(set, mapType);
            if (holder == null) {
                return true; // this map type isn't cached for the region
            }
            Object texture = holderGetTexture.invoke(holder);
            if (texture == null) {
                return true; // no live texture; next draw builds from the file we wrote
            }
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (img == null) {
                return false;
            }
            textureSetImage.invoke(texture, img, true);
            return true;
        } catch (Throwable t) {
            if (ConfigDebugOnce.log("tilerefresh")) {
                JourneySync.LOG.error("Targeted tile refresh failed", t);
            }
            return false;
        }
    }

    /** Force JM to re-scan the waypoints directory. */
    public static void resetWaypoints() {
        resolve();
        if (waypointReset == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastWaypointReset < 2_000) {
            return;
        }
        lastWaypointReset = now;
        try {
            waypointReset.invoke(waypointStore);
        } catch (Throwable t) {
            if (ConfigDebugOnce.log("waypoints")) {
                JourneySync.LOG.error("WaypointStore.reset failed", t);
            }
        }
    }

    /** Log each unique degradation once to avoid log spam. */
    private static final class ConfigDebugOnce {

        private static final java.util.Set<String> logged = java.util.Collections
            .synchronizedSet(new java.util.HashSet<String>());

        static boolean log(String key) {
            return logged.add(key);
        }
    }
}
