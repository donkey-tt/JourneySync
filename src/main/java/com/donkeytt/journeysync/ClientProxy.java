package com.donkeytt.journeysync;

import net.minecraftforge.client.ClientCommandHandler;

import com.donkeytt.journeysync.client.ClientEventHandler;
import com.donkeytt.journeysync.client.JourneySyncCommand;

import cpw.mods.fml.common.event.FMLInitializationEvent;

/**
 * Client proxy: wires client events and the client-side command.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientEventHandler.register();
        ClientCommandHandler.instance.registerCommand(new JourneySyncCommand());
    }
}
