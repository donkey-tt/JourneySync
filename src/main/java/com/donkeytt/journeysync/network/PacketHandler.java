package com.donkeytt.journeysync.network;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.HelloAckPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.TileAckPacket;
import com.donkeytt.journeysync.network.packets.TileBeginPacket;
import com.donkeytt.journeysync.network.packets.TileDataPacket;
import com.donkeytt.journeysync.network.packets.TileRequestPacket;
import com.donkeytt.journeysync.network.packets.TileUploadAckPacket;
import com.donkeytt.journeysync.network.packets.TileUploadBeginPacket;
import com.donkeytt.journeysync.network.packets.TileUploadDataPacket;
import com.donkeytt.journeysync.network.packets.WaypointChangePacket;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * Channel registration. All packets use discriminator-based SimpleNetworkWrapper on channel
 * {@code "journeysync"}.
 */
public final class PacketHandler {

    public static final String CHANNEL = "journeysync";
    public static final int PROTOCOL_VERSION = 1;

    public static final byte BLOB_MANIFEST = 1;
    public static final byte BLOB_WAYPOINTS_FULL = 2;

    public static SimpleNetworkWrapper wrapper;

    private PacketHandler() {}

    public static void init() {
        wrapper = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        int id = 0;
        wrapper.registerMessage(HelloPacket.Handler.class, HelloPacket.class, id++, Side.SERVER);
        wrapper.registerMessage(HelloAckPacket.Handler.class, HelloAckPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(BlobPacket.ClientHandler.class, BlobPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(TileRequestPacket.Handler.class, TileRequestPacket.class, id++, Side.SERVER);
        wrapper.registerMessage(TileBeginPacket.Handler.class, TileBeginPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(TileDataPacket.ClientHandler.class, TileDataPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(TileAckPacket.Handler.class, TileAckPacket.class, id++, Side.SERVER);
        wrapper.registerMessage(TileUploadBeginPacket.Handler.class, TileUploadBeginPacket.class, id++, Side.SERVER);
        wrapper.registerMessage(TileUploadDataPacket.Handler.class, TileUploadDataPacket.class, id++, Side.SERVER);
        wrapper.registerMessage(TileUploadAckPacket.Handler.class, TileUploadAckPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(WaypointChangePacket.Handler.class, WaypointChangePacket.class, id++, Side.SERVER);
        JourneySync.LOG.info("Registered {} network packets on channel {}", id, CHANNEL);
    }
}
