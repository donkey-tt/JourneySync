package com.donkeytt.journeysync;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * JourneySync configuration.
 */
public class Config {

    public static final String CATEGORY_GENERAL = "general";
    public static final String CATEGORY_TRANSFER = "transfer";

    // general
    public static boolean syncEnabled = true;
    public static boolean uploadEnabled = true;
    public static boolean downloadEnabled = true;
    public static boolean logDebug = false;

    // transfer
    /** 0 = unlimited */
    public static int rateLimitKBps = 0;
    public static int scanIntervalSeconds = 5;
    public static int chunkSizeBytes = 24 * 1024;
    public static int maxTileBytes = 32 * 1024 * 1024;
    /** 0 = upload as soon as a change is detected (no debounce) */
    public static int uploadSettleSeconds = 3;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        syncEnabled = configuration
            .getBoolean("syncEnabled", CATEGORY_GENERAL, syncEnabled, "Master switch for JourneySync");
        uploadEnabled = configuration.getBoolean(
            "uploadEnabled",
            CATEGORY_GENERAL,
            uploadEnabled,
            "Upload locally rendered map tiles/waypoints to the server");
        downloadEnabled = configuration.getBoolean(
            "downloadEnabled",
            CATEGORY_GENERAL,
            downloadEnabled,
            "Download map tiles/waypoints shared by other players");
        logDebug = configuration.getBoolean("logDebug", CATEGORY_GENERAL, logDebug, "Verbose debug logging");

        rateLimitKBps = configuration.getInt(
            "rateLimitKBps",
            CATEGORY_TRANSFER,
            rateLimitKBps,
            0,
            1024 * 1024,
            "Transfer rate limit in KB/s (0 = unlimited)");
        scanIntervalSeconds = configuration.getInt(
            "scanIntervalSeconds",
            CATEGORY_TRANSFER,
            scanIntervalSeconds,
            1,
            120,
            "How often the local JourneyMap cache is scanned for changes");
        chunkSizeBytes = configuration.getInt(
            "chunkSizeBytes",
            CATEGORY_TRANSFER,
            chunkSizeBytes,
            4 * 1024,
            28 * 1024,
            "Network chunk payload size in bytes (must stay below 32767)");
        maxTileBytes = configuration.getInt(
            "maxTileBytes",
            CATEGORY_TRANSFER,
            maxTileBytes,
            1024 * 1024,
            256 * 1024 * 1024,
            "Sanity limit for a single tile transfer");
        uploadSettleSeconds = configuration.getInt(
            "uploadSettleSeconds",
            CATEGORY_TRANSFER,
            uploadSettleSeconds,
            0,
            60,
            "Wait until a changed tile file has stayed unchanged this many seconds before uploading"
                + " (debounces re-renders while moving; 0 = off)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static boolean rateLimited() {
        return rateLimitKBps > 0;
    }
}
