package com.donkeytt.journeysync.server;

import java.util.Map;
import java.util.UUID;

import net.minecraftforge.common.MinecraftForge;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.JourneySync;
import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamEvents;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Optional GTNHLib integration for team-scoped sync. This class is the ONLY place
 * {@code com.gtnewhorizon.gtnhlib.*} types appear; callers gate entry on {@link #teamSyncActive()}
 * so the JVM never loads it when GTNHLib is absent (same lazy-class pattern as JmBridge).
 *
 * <p>
 * Event handlers only queue affected player UUIDs for a scope re-check: team caches may not be
 * settled when the event fires, so the actual scope is recomputed later in the manager's tick,
 * where {@link TeamManager#getTeamByPlayer(UUID)} returns the final team.
 * </p>
 */
public final class TeamBridge {

    private TeamBridge() {}

    /** GTNHLib present AND the user opted into team scoping. */
    public static boolean teamSyncActive() {
        return Config.syncByTeam && Loader.isModLoaded("gtnhlib");
    }

    /** Register the team-event listener on the Forge event bus. */
    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.register(new Handler());
        JourneySync.LOG.info("JourneySync: GTNHLib detected, team-scoped sync enabled");
    }

    /**
     * The player's current team id (lowercase UUID string), or null when teamless.
     *
     * <p>
     * {@link TeamManager#getTeamByPlayer(UUID)} logs an upstream ERROR line for teamless players,
     * so this is only called from the scope re-check points (hello / tick drain), never per packet.
     * </p>
     */
    public static String teamIdFor(UUID playerUuid) {
        try {
            Team team = TeamManager.getTeamByPlayer(playerUuid);
            if (team != null && team.getTeamId() != null) {
                return team.getTeamId()
                    .toString();
            }
        } catch (Throwable t) {
            JourneySync.LOG.warn("GTNHLib team lookup failed for {}, falling back to solo", playerUuid, t);
        }
        return null;
    }

    /**
     * Team membership events. Each handler only enqueues the affected player(s) into the
     * manager's pending re-check set; the scope is recomputed on the next server tick.
     */
    public static class Handler {

        @SubscribeEvent
        public void onTeamJoin(TeamEvents.TeamJoinEvent e) {
            queue(e.player);
        }

        @SubscribeEvent
        public void onTeamLeave(TeamEvents.TeamLeaveEvent e) {
            queue(e.player);
        }

        @SubscribeEvent
        public void onTeamKick(TeamEvents.TeamKickEvent e) {
            queue(e.kicked);
        }

        @SubscribeEvent
        public void onTeamMerge(TeamEvents.TeamMergeEvent e) {
            // Members of both the consumed and surviving teams change scope.
            ServerSyncManager.instance()
                .queueAllOnlineScopeRecheck();
        }

        @SubscribeEvent
        public void onTeamDisband(TeamEvents.TeamDisbandEvent e) {
            if (e.newTeamsByMember != null) {
                for (Map.Entry<UUID, ?> entry : e.newTeamsByMember.entrySet()) {
                    queue(entry.getKey());
                }
            }
        }

        @SubscribeEvent
        public void onTeamCreate(TeamEvents.TeamCreateEvent e) {
            queue(e.owner);
        }

        private static void queue(UUID playerUuid) {
            if (playerUuid != null) {
                ServerSyncManager.instance()
                    .queueScopeRecheck(playerUuid);
            }
        }
    }
}
