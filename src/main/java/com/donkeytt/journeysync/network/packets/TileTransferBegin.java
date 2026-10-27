package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.client.ClientSyncManager;
import com.donkeytt.journeysync.server.ServerSyncManager;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Start of one chunked tile transfer, both directions: S→C announces a tile the server is sending
 * (requested download or broadcast), C→S announces an upload. Same wire format either way.
 */
public class TileTransferBegin implements IMessage {

    public int transferId;
    public String relPath;
    public String sha1;
    public int totalBytes;
    public int chunkSize;

    public TileTransferBegin() {}

    public TileTransferBegin(int transferId, String relPath, String sha1, int totalBytes, int chunkSize) {
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

    /** S→C: a tile the server is sending to this client. */
    public static class ClientHandler implements IMessageHandler<TileTransferBegin, IMessage> {

        @Override
        public IMessage onMessage(TileTransferBegin message, MessageContext ctx) {
            try {
                ClientSyncManager.instance()
                    .onTransferBegin(message);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling transfer begin", t);
            }
            return null;
        }
    }

    /** C→S: the client announces an upload. */
    public static class ServerHandler implements IMessageHandler<TileTransferBegin, IMessage> {

        @Override
        public IMessage onMessage(TileTransferBegin message, MessageContext ctx) {
            try {
                ServerSyncManager.instance()
                    .onUploadBegin(ctx.getServerHandler().playerEntity, message);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling upload begin", t);
            }
            return null;
        }
    }
}
