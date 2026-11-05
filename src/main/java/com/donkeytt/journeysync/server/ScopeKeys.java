package com.donkeytt.journeysync.server;

import java.util.UUID;

/**
 * Scope key conventions. Every player reads the {@link #GLOBAL} scope plus their own scope:
 * {@code team:<teamId>} for GTNHLib team members or {@code solo:<playerUuid>} for everyone else.
 * Keys are used as directory names under {@code journeysync/scopes/}; {@link #dirName(String)}
 * maps them to filesystem-safe names.
 */
public final class ScopeKeys {

    public static final String GLOBAL = "global";
    public static final String TEAM_PREFIX = "team:";
    public static final String SOLO_PREFIX = "solo:";

    private ScopeKeys() {}

    public static String teamKey(String teamId) {
        return TEAM_PREFIX + teamId;
    }

    public static String soloKey(UUID playerUuid) {
        return SOLO_PREFIX + playerUuid;
    }

    /**
     * Map a scope key to a directory name. Keys only ever contain {@code [a-z0-9_-]} plus the
     * {@code :} separator, and {@code ':'} is illegal in Windows paths, so a plain replace is
     * enough: {@code team:x -> team_x}. Injective because no legal key character maps to '_'.
     */
    public static String dirName(String key) {
        return key.replace(':', '_');
    }
}
