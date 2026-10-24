package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.network.PacketHandler;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * C→S: first packet after connect. Announces the client mod and whether JourneyMap is present.
 */
public class HelloPacket implements IMessage {

    public int protocolVersion;
    public boolean hasJourneyMap;
    public String modVersion;

    public HelloPacket() {}

    public HelloPacket(boolean hasJourneyMap, String modVersion) {
        this.protocolVersion = PacketHandler.PROTOCOL_VERSION;
        this.hasJourneyMap = hasJourneyMap;
        this.modVersion = modVersion;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = BufUtil.readVarInt(buf, 1);
        hasJourneyMap = buf.readBoolean();
        modVersion = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        BufUtil.writeVarInt(buf, protocolVersion);
        buf.writeBoolean(hasJourneyMap);
        ByteBufUtils.writeUTF8String(buf, modVersion == null ? "" : modVersion);
    }

    public static class Handler implements IMessageHandler<HelloPacket, IMessage> {

        @Override
        public IMessage onMessage(HelloPacket message, MessageContext ctx) {
            com.donkeytt.journeysync.server.ServerSyncManager.instance()
                .onHello(ctx.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
