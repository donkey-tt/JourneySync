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
 * <li>{@code RegionImageSet.getHolder(...)} → `ImageHolder.getTexture()` → `TextureImpl.setImage(...)} —
 * refresh just one changed tile's live texture (gated: only while JM is idle on that tile)</li>
 * <li>{@code journeymap.client.waypoint.WaypointStore.instance().reset()} — force JM to re-scan
 * the waypoints directory</li>
 * </ul>
 */
public final class JmBridge {

    private static boolean jmPresent;
    private static boolean resolved;
    private static Method getJMWorldDir;
    private static Object regionImageCache;
    private static Object waypointStore;
    private static Method waypointReset;

    // targeted single-tile texture refresh (day/night and cave vSlice layers alike)
    private static boolean tileRefreshResolved;
    private static Method mapTypeDay;
    private static Method mapTypeNight;
    private static Method mapTypeUnderground;
    private static Method regionCoordFromRegionPos;
    private static Method getRegionImageSet;
    private static Method getHolder;
    private static Method holderGetTexture;
    private static Method textureSetImage;

    private static long lastWaypointReset;

    // region re-render requests (repair tool: JM only re-renders chunks its task system decides to,
    // so damage predating the pixel-union pipeline can only be healed by explicitly queueing a
    // MapRegionTask for the affected region - the same task JM's own "automapping" feature uses)
    private static boolean remapResolved;
    private static Object jmClient;
    private static java.lang.reflect.Field taskControllerField;
    private static Method jmToggleTask;
    private static Method isTaskManagerEnabled;
    private static Class<?> mapRegionTaskManagerClass;
    private static Method regionCoordFromChunkPos;

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
            regionImageCache = cacheClass.getMethod("instance")
                .invoke(null);
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
            mapTypeUnderground = mapTypeClass.getMethod("underground", Integer.class, int.class);
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
            JourneySync.LOG.error("JourneyMap targeted tile refresh unavailable", t);
        }
        try {
            Class<?> jmClientClass = Class.forName("journeymap.client.JourneymapClient");
            jmClient = jmClientClass.getMethod("getInstance")
                .invoke(null);
            jmToggleTask = jmClientClass.getMethod("toggleTask", Class.class, boolean.class, Object.class);
            // The controller instance is created by JourneymapClient.startMapping() and nulled by
            // stopMapping() - long after any first resolve() and again on every world change - so
            // only the Field is resolved here; the instance is read fresh at each use.
            taskControllerField = jmClientClass.getDeclaredField("multithreadTaskController");
            taskControllerField.setAccessible(true);
            Class<?> taskControllerClass = Class.forName("journeymap.client.task.multi.TaskController");
            isTaskManagerEnabled = taskControllerClass.getMethod("isTaskManagerEnabled", Class.class);
            Class<?> mapTypeClass = Class.forName("journeymap.client.model.MapType");
            Class<?> coordClass = Class.forName("journeymap.client.model.RegionCoord");
            regionCoordFromChunkPos = coordClass
                .getMethod("fromChunkPos", File.class, mapTypeClass, int.class, int.class);
            mapRegionTaskManagerClass = Class.forName("journeymap.client.task.multi.MapRegionTask$Manager");
            remapResolved = true;
        } catch (Throwable t) {
            JourneySync.LOG
                .error("JourneyMap region re-render integration unavailable; the rerender command is disabled", t);
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

    /**
     * Update a single tile's GL texture from freshly applied bytes, for surface (day/night) and
     * cave (vSlice) layers alike: the tile's kind is the directory name, which is exactly
     * {@code Integer.toString(vSlice)} for underground map types (RegionImageHandler.getImageDir).
     * Only meaningful while JM is idle on this tile: a push under a live JM render is overwritten
     * by its next texture update from its own master image, and refreshing a tile JM is not
     * rendering is unnecessary anyway (its image cache expires within ~15s and re-reads the file).
     * There is deliberately no full-cache-clear fallback: it expires every viewed tile at once,
     * makes JM re-render them from stale in-memory regions, and visibly flickers.
     */
    public static boolean refreshTile(File worldDir, com.donkeytt.journeysync.data.TileKey key, byte[] png) {
        resolve();
        if (!tileRefreshResolved || worldDir == null) {
            return false;
        }
        try {
            Object mapType = mapTypeFor(key);
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

    /**
     * The JM MapType for a tile: "day"/"night" map to the surface factories, anything else is a
     * cave vSlice (TileKey's kind regex admits only those three shapes). MapType equality is
     * (name, vSlice, dimension), so the constructed type matches the holder key exactly.
     */
    private static Object mapTypeFor(com.donkeytt.journeysync.data.TileKey key) throws Exception {
        if ("day".equals(key.kind)) {
            return mapTypeDay.invoke(null, key.dimension);
        }
        if ("night".equals(key.kind)) {
            return mapTypeNight.invoke(null, key.dimension);
        }
        return mapTypeUnderground.invoke(null, Integer.valueOf(key.kind), key.dimension);
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

    /** Whether the region re-render integration resolved (the repair command needs it). */
    public static boolean isRegionRemapSupported() {
        resolve();
        return remapResolved;
    }

    /**
     * Whether the rerender pipeline can actually run right now: the integration resolved and JM's
     * task controller exists, i.e. mapping has started in the current world. Queued entries must
     * wait (not drop) while this is false - it flips late after world join and again after every
     * world change.
     */
    public static boolean isRegionRemapReady() {
        resolve();
        return remapResolved && taskController() != null;
    }

    /**
     * The live multithreadTaskController instance, or null before startMapping()/after
     * stopMapping(). Read fresh on every call: the instance is recreated per world.
     */
    private static Object taskController() {
        if (taskControllerField == null) {
            return null;
        }
        try {
            return taskControllerField.get(jmClient);
        } catch (Throwable t) {
            if (ConfigDebugOnce.log("remapcontroller")) {
                JourneySync.LOG.error("Reading JourneymapClient.multithreadTaskController failed", t);
            }
            return null;
        }
    }

    /**
     * Whether JM is currently running or has accepted a region remap task. JM ignores a new task
     * while one is enabled ("Task already enabled"), so callers must wait for the busy flag to
     * clear before queueing the next region.
     */
    public static boolean isRegionTaskBusy() {
        resolve();
        if (!remapResolved) {
            return false;
        }
        Object controller = taskController();
        if (controller == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isTaskManagerEnabled.invoke(controller, mapRegionTaskManagerClass));
        } catch (Throwable t) {
            if (ConfigDebugOnce.log("remapbusy")) {
                JourneySync.LOG.error("TaskController.isTaskManagerEnabled failed", t);
            }
            return false;
        }
    }

    /**
     * Ask JM to re-render the region (32x32 chunks) containing the given chunk, using the same
     * task its own automapping drives. Only loaded chunks are rendered; the caller is expected to
     * stand inside the affected area so the render is complete. The rewritten tile file is picked
     * up by the file watcher and folded into the pixel union like any other render.
     */
    public static boolean remapRegion(File worldDir, int dimension, int chunkX, int chunkZ, boolean night) {
        resolve();
        if (!remapResolved || worldDir == null || taskController() == null) {
            return false; // without a controller JM's toggleTask is a silent no-op
        }
        try {
            Object mapType = (night ? mapTypeNight : mapTypeDay).invoke(null, dimension);
            Object coord = regionCoordFromChunkPos.invoke(null, worldDir, mapType, chunkX, chunkZ);
            jmToggleTask.invoke(jmClient, mapRegionTaskManagerClass, true, coord);
            return true;
        } catch (Throwable t) {
            if (ConfigDebugOnce.log("remap")) {
                JourneySync.LOG.error("Region re-render request failed", t);
            }
            return false;
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
