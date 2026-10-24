package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.JourneySync;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * C→S: acknowledgement of a completed tile download (result of the SHA-1 verification).
 */
public class TileAckPacket implements IMessage {

    public int transferId;
    public boolean ok;

    public TileAckPacket() {}

    public TileAckPacket(int transferId, boolean ok) {
        this.transferId = transferId;
        this.ok = ok;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        transferId = buf.readInt();
        ok = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(transferId);
        buf.writeBoolean(ok);
    }

    public static class Handler implements IMessageHandler<TileAckPacket, IMessage> {

        @Override
        public IMessage onMessage(TileAckPacket message, MessageContext ctx) {
            try {
                com.donkeytt.journeysync.server.ServerSyncManager.instance()
                    .onTileAck(ctx.getServerHandler().playerEntity, message);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling tile ack", t);
            }
            return null;
        }
    }
}
