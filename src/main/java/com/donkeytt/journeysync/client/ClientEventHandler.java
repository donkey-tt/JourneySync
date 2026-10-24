package com.donkeytt.journeysync.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Client-side event wiring (registered on the FML common bus, which receives client ticks and
 * connection lifecycle events): per-tick draining of sync side effects (chat messages, JM
 * refreshes).
 */
public class ClientEventHandler {

    public static void register() {
        ClientEventHandler handler = new ClientEventHandler();
        FMLCommonHandler.instance()
            .bus()
            .register(handler);
    }

    @SubscribeEvent
    public void onConnected(cpw.mods.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent event) {
        ClientSyncManager.instance()
            .onConnect();
    }

    @SubscribeEvent
    public void onDisconnected(cpw.mods.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientSyncManager.instance()
            .onDisconnect();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ClientSyncManager.instance()
                .onClientTick();
        }
    }
}
