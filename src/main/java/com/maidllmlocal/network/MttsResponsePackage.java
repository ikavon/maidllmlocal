package com.maidllmlocal.network;

import com.maidllmlocal.maica.MttsRelayHub;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * client → server：本机那次 MTTS 合成的结果（音频字节，官方 lossless=false 时是 mp3）。
 *
 * <p>音频可能数百 KB——1.21/NeoForge 上直接发即可；forge 分支受 1.20.1 的 C2S 32KB
 * 协议限制，登记为可分片：超限时 {@link NetworkInit} 自动切片走 {@link FragmentPackage}，
 * 服务端重组后与直发路径汇合在 {@link #handleReassembled}。上限 4MB 防呆。
 */
public record MttsResponsePackage(int requestId, boolean ok, byte[] audio, String error) {

    public static final int MAX_AUDIO_BYTES = 4 * 1024 * 1024;

    public static MttsResponsePackage success(int requestId, byte[] audio) {
        return new MttsResponsePackage(requestId, true, audio, "");
    }

    public static MttsResponsePackage failure(int requestId, String error) {
        return new MttsResponsePackage(requestId, false, new byte[0], error);
    }

    public static void encode(MttsResponsePackage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.requestId);
        buf.writeBoolean(msg.ok);
        NetBuf.writeBytes(buf, msg.audio, MAX_AUDIO_BYTES);
        buf.writeUtf(msg.error, 512);
    }

    public static MttsResponsePackage decode(FriendlyByteBuf buf) {
        return new MttsResponsePackage(buf.readVarInt(), buf.readBoolean(),
                NetBuf.readBytes(buf, MAX_AUDIO_BYTES), buf.readUtf(512));
    }

    public static byte[] toBytes(MttsResponsePackage msg) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode(msg, buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    public static MttsResponsePackage fromBytes(byte[] data) {
        return decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));
    }

    public static void handle(MttsResponsePackage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getDirection().getReceptionSide() == LogicalSide.SERVER) {
            // 回主线程再补全：callback 会碰女仆实体与声音系统
            ctx.enqueueWork(() -> {
                // Forge 的 getSender() 在服务端侧就是 ServerPlayer（见 FragmentPackage 同款注释）
                ServerPlayer player = ctx.getSender();
                if (player != null) {
                    handleReassembled(msg, player);
                }
            });
        }
        ctx.setPacketHandled(true);
    }

    /** 直发路径与分片重组路径的共同汇合点（主线程、发送者已确认）。 */
    static void handleReassembled(MttsResponsePackage msg, ServerPlayer player) {
        MttsRelayHub.onResponse(msg.requestId(), msg.ok(), msg.audio(), msg.error());
    }
}
