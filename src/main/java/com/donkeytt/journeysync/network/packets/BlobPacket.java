package com.donkeytt.journeysync.network.packets;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.client.ClientSyncManager;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S→C: a fragment of a gzipped JSON blob (tile manifest or full waypoint list).
 *
 * <p>
 * The blob is gzipped, split into chunks of at most {@link #CHUNK_SIZE} bytes and reassembled on
 * the client before gunzip + parse.
 * </p>
 */
public class BlobPacket implements IMessage {

    /** Conservative payload cap, safely below the 32767 custom-payload limit. */
    public static final int CHUNK_SIZE = 24 * 1024;

    public byte blobType;
    public int seq;
    public int total;
    public byte[] data;

    public BlobPacket() {}

    public BlobPacket(byte blobType, int seq, int total, byte[] data) {
        this.blobType = blobType;
        this.seq = seq;
        this.total = total;
        this.data = data;
    }

    /** Split a gzipped payload into blob packets. */
    public static List<BlobPacket> split(byte blobType, byte[] gzipped) {
        int total = Math.max(1, (gzipped.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        List<BlobPacket> out = new ArrayList<BlobPacket>(total);
        for (int seq = 0; seq < total; seq++) {
            int off = seq * CHUNK_SIZE;
            int len = Math.min(CHUNK_SIZE, gzipped.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(gzipped, off, chunk, 0, len);
            out.add(new BlobPacket(blobType, seq, total, chunk));
        }
        return out;
    }

    /** Gzip a string payload. */
    public static byte[] gzip(String json) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            GZIPOutputStream gz = new GZIPOutputStream(bos);
            gz.write(json.getBytes("UTF-8"));
            gz.close();
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("gzip failed", e);
        }
    }

    /** Gunzip to a string. */
    public static String gunzip(byte[] data) {
        try {
            GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(data));
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = gz.read(buf)) != -1) {
                bos.write(buf, 0, read);
            }
            gz.close();
            return bos.toString("UTF-8");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("gunzip failed", e);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        blobType = buf.readByte();
        seq = BufUtil.readVarInt(buf, 4);
        total = BufUtil.readVarInt(buf, 4);
        int len = BufUtil.readVarInt(buf, 4);
        data = new byte[len];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(blobType);
        BufUtil.writeVarInt(buf, seq);
        BufUtil.writeVarInt(buf, total);
        BufUtil.writeVarInt(buf, data.length);
        buf.writeBytes(data);
    }

    public static class ClientHandler implements IMessageHandler<BlobPacket, IMessage> {

        @Override
        public IMessage onMessage(BlobPacket message, MessageContext ctx) {
            try {
                ClientSyncManager.instance()
                    .onBlobFragment(message);
            } catch (Throwable t) {
                JourneySync.LOG
                    .error("Failed handling blob fragment {} ({}/{})", message.blobType, message.seq, message.total, t);
            }
            return null;
        }
    }
}
