package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.JourneySync;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * C→S: one chunk of an uploaded tile.
 */
public class TileUploadDataPacket implements IMessage {

    public int transferId;
    public int seq;
    public byte[] data;

    public TileUploadDataPacket() {}

    public TileUploadDataPacket(int transferId, int seq, byte[] data) {
        this.transferId = transferId;
        this.seq = seq;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        transferId = buf.readInt();
        seq = BufUtil.readVarInt(buf, 4);
        int len = BufUtil.readVarInt(buf, 4);
        data = new byte[len];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(transferId);
        BufUtil.writeVarInt(buf, seq);
        BufUtil.writeVarInt(buf, data.length);
        buf.writeBytes(data);
    }

    public static class Handler implements IMessageHandler<TileUploadDataPacket, IMessage> {

        @Override
        public IMessage onMessage(TileUploadDataPacket message, MessageContext ctx) {
            try {
                com.donkeytt.journeysync.server.ServerSyncManager.instance()
                    .onTileUploadData(ctx.getServerHandler().playerEntity, message);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling tile upload data", t);
            }
            return null;
        }
    }
}
