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
 * Result of a tile transfer, both directions.
 *
 * <p>
 * S→C (upload result): {@link #OK} stored, {@link #NOTHING_NEW} the server already held this
 * content (duplicate, or nothing new survived the pixel merge), {@link #REJECTED} invalid path /
 * failed verification. For OK/NOTHING_NEW the ack carries the server's authoritative manifest
 * entry for the tile (the stored bytes may differ from the upload after the server-side merge),
 * so the client can refresh its manifest snapshot without another round trip.
 * </p>
 *
 * <p>
 * C→S (download result): {@link #OK} verified and applied, {@link #REJECTED} failed verification.
 * No entry fields in this direction.
 * </p>
 */
public class TileTransferAck implements IMessage {

    public static final byte OK = 0;
    public static final byte NOTHING_NEW = 1;
    public static final byte REJECTED = 2;

    public int transferId;
    public byte code;

    /** Server's stored sha1 after this transfer, or null when not applicable. */
    public String storedSha1;
    /** Server's stored size after this transfer. */
    public int storedSize;

    public TileTransferAck() {}

    public TileTransferAck(int transferId, byte code) {
        this(transferId, code, null, 0);
    }

    public TileTransferAck(int transferId, byte code, String storedSha1, int storedSize) {
        this.transferId = transferId;
        this.code = code;
        this.storedSha1 = storedSha1;
        this.storedSize = storedSize;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        transferId = buf.readInt();
        code = buf.readByte();
        if (buf.readBoolean()) {
            storedSha1 = ByteBufUtils.readUTF8String(buf);
            storedSize = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(transferId);
        buf.writeByte(code);
        boolean hasEntry = storedSha1 != null;
        buf.writeBoolean(hasEntry);
        if (hasEntry) {
            ByteBufUtils.writeUTF8String(buf, storedSha1);
            buf.writeInt(storedSize);
        }
    }

    /** S→C: the result of an upload this client sent. */
    public static class ClientHandler implements IMessageHandler<TileTransferAck, IMessage> {

        @Override
        public IMessage onMessage(TileTransferAck message, MessageContext ctx) {
            ClientSyncManager.instance()
                .onTransferAck(message);
            return null;
        }
    }

    /** C→S: the result of a download the server sent. */
    public static class ServerHandler implements IMessageHandler<TileTransferAck, IMessage> {

        @Override
        public IMessage onMessage(TileTransferAck message, MessageContext ctx) {
            try {
                ServerSyncManager.instance()
                    .onDownloadAck(ctx.getServerHandler().playerEntity, message);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling download ack", t);
            }
            return null;
        }
    }
}
