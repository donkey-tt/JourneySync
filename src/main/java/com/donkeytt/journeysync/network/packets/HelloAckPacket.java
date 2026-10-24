package com.donkeytt.journeysync.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S→C: reply to {@link HelloPacket} with server sync state and effective config.
 */
public class HelloAckPacket implements IMessage {

    public int protocolVersion;
    public long tileEpoch;
    public long waypointEpoch;
    public int rateLimitKBps;

    public HelloAckPacket() {}

    public HelloAckPacket(int protocolVersion, long tileEpoch, long waypointEpoch, int rateLimitKBps) {
        this.protocolVersion = protocolVersion;
        this.tileEpoch = tileEpoch;
        this.waypointEpoch = waypointEpoch;
        this.rateLimitKBps = rateLimitKBps;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = BufUtil.readVarInt(buf, 1);
        tileEpoch = buf.readLong();
        waypointEpoch = buf.readLong();
        rateLimitKBps = BufUtil.readVarInt(buf, 4);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        BufUtil.writeVarInt(buf, protocolVersion);
        buf.writeLong(tileEpoch);
        buf.writeLong(waypointEpoch);
        BufUtil.writeVarInt(buf, rateLimitKBps);
    }

    public static class Handler implements IMessageHandler<HelloAckPacket, IMessage> {

        @Override
        public IMessage onMessage(HelloAckPacket message, MessageContext ctx) {
            com.donkeytt.journeysync.client.ClientSyncManager.instance()
                .onHelloAck(message);
            return null;
        }
    }
}
