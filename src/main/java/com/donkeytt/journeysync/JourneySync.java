package com.donkeytt.journeysync;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.server.ServerEventHandler;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

@Mod(modid = JourneySync.MODID, version = Tags.VERSION, name = "JourneySync", acceptedMinecraftVersions = "[1.7.10]")
public class JourneySync {

    public static final String MODID = "journeysync";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(
        clientSide = "com.donkeytt.journeysync.ClientProxy",
        serverSide = "com.donkeytt.journeysync.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        PacketHandler.init();
        ServerEventHandler.register();
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.serverStopping(event);
    }
}
