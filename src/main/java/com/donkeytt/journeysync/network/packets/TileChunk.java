package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.client.ClientSyncManager;
import com.donkeytt.journeysync.server.ServerSyncManager;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * One chunk of a tile transfer payload, both directions (see {@link TileTransferBegin}).
 */
public class TileChunk implements IMessage {

    public int transferId;
    public int seq;
    public byte[] data;

    public TileChunk() {}

    public TileChunk(int transferId, int seq, byte[] data) {
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

    /** S→C: a chunk of a tile the server is sending to this client. */
    public static class ClientHandler implements IMessageHandler<TileChunk, IMessage> {

        @Override
        public IMessage onMessage(TileChunk message, MessageContext ctx) {
            try {
                ClientSyncManager.instance()
                    .onTransferChunk(message);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling transfer chunk", t);
            }
            return null;
        }
    }

    /** C→S: a chunk of an uploaded tile. */
    public static class ServerHandler implements IMessageHandler<TileChunk, IMessage> {

        @Override
        public IMessage onMessage(TileChunk message, MessageContext ctx) {
            try {
                ServerSyncManager.instance()
                    .onUploadData(ctx.getServerHandler().playerEntity, message);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling upload data", t);
            }
            return null;
        }
    }
}
