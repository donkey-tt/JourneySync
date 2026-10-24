package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.JourneySync;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * One chunk of tile payload. Used for S→C downloads (via {@link ClientHandler}); the upload
 * direction uses {@link TileUploadDataPacket} with its own discriminator.
 */
public class TileDataPacket implements IMessage {

    public int transferId;
    public int seq;
    public byte[] data;

    public TileDataPacket() {}

    public TileDataPacket(int transferId, int seq, byte[] data) {
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

    public static class ClientHandler implements IMessageHandler<TileDataPacket, IMessage> {

        @Override
        public IMessage onMessage(TileDataPacket message, MessageContext ctx) {
            try {
                com.donkeytt.journeysync.client.ClientSyncManager.instance()
                    .onTileData(message);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling tile data", t);
            }
            return null;
        }
    }
}
