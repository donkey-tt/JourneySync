package com.donkeytt.journeysync.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S→C: result of a tile upload. Codes: 0 = stored, 1 = duplicate (content already known),
 * 2 = rejected (invalid path / failed verification).
 */
public class TileUploadAckPacket implements IMessage {

    public int transferId;
    public int code;

    public TileUploadAckPacket() {}

    public TileUploadAckPacket(int transferId, int code) {
        this.transferId = transferId;
        this.code = code;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        transferId = buf.readInt();
        code = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(transferId);
        buf.writeByte(code);
    }

    public static class Handler implements IMessageHandler<TileUploadAckPacket, IMessage> {

        @Override
        public IMessage onMessage(TileUploadAckPacket message, MessageContext ctx) {
            com.donkeytt.journeysync.client.ClientSyncManager.instance()
                .onTileUploadAck(message);
            return null;
        }
    }
}
