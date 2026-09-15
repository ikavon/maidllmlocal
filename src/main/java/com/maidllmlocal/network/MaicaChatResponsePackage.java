package com.maidllmlocal.network;

import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.maica.MaicaRelayHub;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.charset.StandardCharsets;

/**
 * client → server：本机那一轮 MAICA 对话的结果。
 *
 * <p>{@code ok=false} 覆盖一切失败（本机没配站点、WS 连不上、MAICA 致命错误、超时）——
 * 服务端没有自己的 MAICA token，<b>不存在回退</b>，一律转成 {@code onFailure} 气泡。
 * {@code text} 是客户端聚合好的整段回复原文（含情绪标签，清洗在服务端统一做）。
 */
public record MaicaChatResponsePackage(int requestId, boolean ok, byte[] textUtf8, String error)
        implements CustomPacketPayload {

    /** 一轮回复按 max_tokens 4096 估也就十几 KB，64KB 上限防呆。 */
    public static final int MAX_TEXT_BYTES = 64 * 1024;

    public static final CustomPacketPayload.Type<MaicaChatResponsePackage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaidLLMLocal.MODID, "maica_chat_response"));

    public static final StreamCodec<ByteBuf, MaicaChatResponsePackage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MaicaChatResponsePackage::requestId,
            ByteBufCodecs.BOOL, MaicaChatResponsePackage::ok,
            ByteBufCodecs.byteArray(MAX_TEXT_BYTES), MaicaChatResponsePackage::textUtf8,
            ByteBufCodecs.stringUtf8(512), MaicaChatResponsePackage::error,
            MaicaChatResponsePackage::new);

    public static MaicaChatResponsePackage success(int requestId, String text) {
        return new MaicaChatResponsePackage(requestId, true,
                text.getBytes(StandardCharsets.UTF_8), "");
    }

    public static MaicaChatResponsePackage failure(int requestId, String error) {
        return new MaicaChatResponsePackage(requestId, false, new byte[0], error);
    }

    public String text() {
        return new String(textUtf8, StandardCharsets.UTF_8);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MaicaChatResponsePackage message, IPayloadContext context) {
        if (context.flow().isServerbound()) {
            // 回主线程再补全：callback 会碰女仆实体与气泡
            context.enqueueWork(() -> onHandle(message, context));
        }
    }

    private static void onHandle(MaicaChatResponsePackage message, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer) {
            MaicaRelayHub.onResponse(message.requestId(), message.ok(), message.text(), message.error());
        }
    }
}
