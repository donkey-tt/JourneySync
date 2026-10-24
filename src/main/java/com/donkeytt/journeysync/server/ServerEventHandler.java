package com.donkeytt.journeysync.server;

import java.io.File;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Server-side event wiring: initializes storage per world, ticks the sync manager, cleans up on
 * player logout.
 */
public class ServerEventHandler {

    public static void register() {
        ServerEventHandler handler = new ServerEventHandler();
        FMLCommonHandler.instance()
            .bus()
            .register(handler);
        MinecraftForge.EVENT_BUS.register(handler);
    }

    /** Called from the common proxy once the overworld save directory is known. */
    public static void initStorage(File worldDir) {
        ServerSyncManager.instance()
            .init(worldDir);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ServerSyncManager.instance()
                .tick();
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            ServerSyncManager.instance()
                .onPlayerLogout((EntityPlayerMP) event.player);
        }
    }
}
