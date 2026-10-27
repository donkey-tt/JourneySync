package com.donkeytt.journeysync.network;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.HelloAckPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.TileChunk;
import com.donkeytt.journeysync.network.packets.TileRequestPacket;
import com.donkeytt.journeysync.network.packets.TileTransferAck;
import com.donkeytt.journeysync.network.packets.TileTransferBegin;
import com.donkeytt.journeysync.network.packets.WaypointChangePacket;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * Channel registration. All packets use discriminator-based SimpleNetworkWrapper on channel
 * {@code "journeysync"}. Bidirectional packets (transfer begin/chunk/ack) are one class with a
 * per-side handler.
 */
public final class PacketHandler {

    public static final String CHANNEL = "journeysync";
    public static final int PROTOCOL_VERSION = 2;

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
        wrapper.registerMessage(TileTransferBegin.ClientHandler.class, TileTransferBegin.class, id++, Side.CLIENT);
        wrapper.registerMessage(TileTransferBegin.ServerHandler.class, TileTransferBegin.class, id++, Side.SERVER);
        wrapper.registerMessage(TileChunk.ClientHandler.class, TileChunk.class, id++, Side.CLIENT);
        wrapper.registerMessage(TileChunk.ServerHandler.class, TileChunk.class, id++, Side.SERVER);
        wrapper.registerMessage(TileTransferAck.ClientHandler.class, TileTransferAck.class, id++, Side.CLIENT);
        wrapper.registerMessage(TileTransferAck.ServerHandler.class, TileTransferAck.class, id++, Side.SERVER);
        wrapper.registerMessage(WaypointChangePacket.Handler.class, WaypointChangePacket.class, id++, Side.SERVER);
        JourneySync.LOG.info("Registered {} network packets on channel {}", id, CHANNEL);
    }
}
