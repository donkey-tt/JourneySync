package com.donkeytt.journeysync.network.packets;

import java.util.ArrayList;
import java.util.List;

import com.donkeytt.journeysync.JourneySync;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * C→S: request a batch of tiles by relative path.
 */
public class TileRequestPacket implements IMessage {

    public static final int MAX_BATCH = 16;

    public List<String> paths = new ArrayList<String>();

    public TileRequestPacket() {}

    public TileRequestPacket(List<String> paths) {
        this.paths = paths;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int size = BufUtil.readVarInt(buf, 2);
        if (size > MAX_BATCH) {
            throw new IllegalArgumentException("Tile request batch too large: " + size);
        }
        paths = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) {
            paths.add(ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        BufUtil.writeVarInt(buf, paths.size());
        for (String path : paths) {
            ByteBufUtils.writeUTF8String(buf, path);
        }
    }

    public static class Handler implements IMessageHandler<TileRequestPacket, IMessage> {

        @Override
        public IMessage onMessage(TileRequestPacket message, MessageContext ctx) {
            try {
                com.donkeytt.journeysync.server.ServerSyncManager.instance()
                    .onTileRequest(ctx.getServerHandler().playerEntity, message.paths);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling tile request", t);
            }
            return null;
        }
    }
}
