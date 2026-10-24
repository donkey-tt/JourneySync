package com.donkeytt.journeysync.network.packets;

import io.netty.buffer.ByteBuf;

/**
 * Varint helpers (FML 1.7.10's {@code ByteBufUtils} only ships UTF-8 string helpers).
 */
public final class BufUtil {

    private BufUtil() {}

    public static void writeVarInt(ByteBuf buf, int value) {
        int v = value;
        do {
            int b = v & 0x7f;
            v >>>= 7;
            if (v != 0) {
                b |= 0x80;
            }
            buf.writeByte(b);
        } while (v != 0);
    }

    public static int readVarInt(ByteBuf buf, int maxBytes) {
        int value = 0;
        int size = 0;
        int b;
        do {
            b = buf.readByte();
            value |= (b & 0x7f) << (size++ * 7);
            if (size > maxBytes) {
                throw new IllegalArgumentException("VarInt too big");
            }
        } while ((b & 0x80) == 0x80);
        return value;
    }
}
