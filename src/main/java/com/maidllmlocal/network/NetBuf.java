package com.maidllmlocal.network;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;

/**
 * {@link FriendlyByteBuf} 的手写编解码辅助。
 *
 * <p>forge 分支专用:1.20.1 没有 {@code StreamCodec}/{@code ByteBufCodecs}
 * (main 分支的 payload 全靠它们),这边等价物就是手写 read/write。
 *
 * <p>byte[] 字段一律带显式上限,<b>编码与解码两侧都校验</b>——与
 * {@code ByteBufCodecs.byteArray(max)} 同一防呆语义:超长直接抛异常断连接,
 * 而不是让服务端按攻击者给的长度分配数组。
 */
final class NetBuf {

    private NetBuf() {}

    static void writeBytes(FriendlyByteBuf buf, byte[] data, int maxBytes) {
        if (data.length > maxBytes) {
            throw new EncoderException("payload too large: " + data.length + " > " + maxBytes);
        }
        buf.writeVarInt(data.length);
        buf.writeBytes(data);
    }

    static byte[] readBytes(FriendlyByteBuf buf, int maxBytes) {
        int len = buf.readVarInt();
        if (len < 0 || len > maxBytes) {
            throw new DecoderException("payload too large: " + len + " > " + maxBytes);
        }
        byte[] out = new byte[len];
        buf.readBytes(out);
        return out;
    }
}
