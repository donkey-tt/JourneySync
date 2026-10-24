package com.donkeytt.journeysync.network.packets;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S→C: start of one tile download.
 */
public class TileBeginPacket implements IMessage {

    public int transferId;
    public String relPath;
    public String sha1;
    public int totalBytes;
    public int chunkSize;

    public TileBeginPacket() {}

    public TileBeginPacket(int transferId, String relPath, String sha1, int totalBytes, int chunkSize) {
        this.transferId = transferId;
        this.relPath = relPath;
        this.sha1 = sha1;
        this.totalBytes = totalBytes;
        this.chunkSize = chunkSize;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        transferId = buf.readInt();
        relPath = ByteBufUtils.readUTF8String(buf);
        sha1 = ByteBufUtils.readUTF8String(buf);
        totalBytes = buf.readInt();
        chunkSize = BufUtil.readVarInt(buf, 4);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(transferId);
        ByteBufUtils.writeUTF8String(buf, relPath);
        ByteBufUtils.writeUTF8String(buf, sha1);
        buf.writeInt(totalBytes);
        BufUtil.writeVarInt(buf, chunkSize);
    }

    public static class Handler implements IMessageHandler<TileBeginPacket, IMessage> {

        @Override
        public IMessage onMessage(TileBeginPacket message, MessageContext ctx) {
            com.donkeytt.journeysync.client.ClientSyncManager.instance()
                .onTileBegin(message);
            return null;
        }
    }
}
