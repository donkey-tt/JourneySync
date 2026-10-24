package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.WaypointRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * C→S: propose a waypoint add/update/delete. The server assigns the authoritative revision and
 * broadcasts the change to all other players.
 */
public class WaypointChangePacket implements IMessage {

    private static final Gson GSON = new GsonBuilder().create();

    public WaypointRecord record;

    public WaypointChangePacket() {}

    public WaypointChangePacket(WaypointRecord record) {
        this.record = record;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        record = GSON.fromJson(ByteBufUtils.readUTF8String(buf), WaypointRecord.class);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, GSON.toJson(record));
    }

    public static class Handler implements IMessageHandler<WaypointChangePacket, IMessage> {

        @Override
        public IMessage onMessage(WaypointChangePacket message, MessageContext ctx) {
            try {
                com.donkeytt.journeysync.server.ServerSyncManager.instance()
                    .onWaypointChange(ctx.getServerHandler().playerEntity, message.record);
            } catch (Throwable t) {
                JourneySync.LOG.error("Failed handling waypoint change", t);
            }
            return null;
        }
    }
}
