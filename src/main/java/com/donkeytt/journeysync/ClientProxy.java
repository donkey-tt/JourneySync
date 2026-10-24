package com.donkeytt.journeysync;

import net.minecraftforge.client.ClientCommandHandler;

import com.donkeytt.journeysync.client.ClientEventHandler;
import com.donkeytt.journeysync.client.JourneySyncCommand;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Client proxy: wires client events and the client-side command.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientEventHandler.register();
        ClientCommandHandler.instance.registerCommand(new JourneySyncCommand());
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }
}
