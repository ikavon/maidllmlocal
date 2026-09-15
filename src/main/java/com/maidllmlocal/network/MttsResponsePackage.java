package com.maidllmlocal.network;

import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.maica.MttsRelayHub;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * client → server：本机那次 MTTS 合成的结果（音频字节，官方 lossless=false 时是 mp3）。
 *
 * <p>音频可能数百 KB——payload 走 TCP 分片没有问题，上限 4MB 防呆。
 */
public record MttsResponsePackage(int requestId, boolean ok, byte[] audio, String error)
        implements CustomPacketPayload {

    public static final int MAX_AUDIO_BYTES = 4 * 1024 * 1024;

    public static final CustomPacketPayload.Type<MttsResponsePackage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaidLLMLocal.MODID, "mtts_response"));

    public static final StreamCodec<ByteBuf, MttsResponsePackage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MttsResponsePackage::requestId,
            ByteBufCodecs.BOOL, MttsResponsePackage::ok,
            ByteBufCodecs.byteArray(MAX_AUDIO_BYTES), MttsResponsePackage::audio,
            ByteBufCodecs.stringUtf8(512), MttsResponsePackage::error,
            MttsResponsePackage::new);

    public static MttsResponsePackage success(int requestId, byte[] audio) {
        return new MttsResponsePackage(requestId, true, audio, "");
    }

    public static MttsResponsePackage failure(int requestId, String error) {
        return new MttsResponsePackage(requestId, false, new byte[0], error);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MttsResponsePackage message, IPayloadContext context) {
        if (context.flow().isServerbound()) {
            // 回主线程再补全：callback 会碰女仆实体与声音系统
            context.enqueueWork(() -> onHandle(message, context));
        }
    }

    private static void onHandle(MttsResponsePackage message, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer) {
            MttsRelayHub.onResponse(message.requestId(), message.ok(), message.audio(), message.error());
        }
    }
}
