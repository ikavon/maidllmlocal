package com.maidllmlocal.network;

import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.relay.RelayHub;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.charset.StandardCharsets;

/**
 * client → server：本机那次请求的结果。
 *
 * <p>{@code error} 与 {@code statusCode} 是<b>分开</b>的两种失败，处理方式不同：
 * <ul>
 *   <li>{@code error} 非空 = 客户端<b>根本没能发出</b>这次请求（本机没配该站点、连不上、超时）→ 服务端回退到自己那份配置；</li>
 *   <li>{@code statusCode} + {@code body} = 请求真的发了，这是对方返回的应答（哪怕是 4xx/5xx）→ 原样交给 TLM 处理，
 *       <b>不回退</b>。否则会变成"玩家自己的端点报错，却悄悄拿服务端的 key 重试一遍"。</li>
 * </ul>
 */
public record RelayResponsePackage(int requestId, int statusCode, byte[] bodyUtf8, String error)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RelayResponsePackage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaidLLMLocal.MODID, "relay_response"));

    public static final StreamCodec<ByteBuf, RelayResponsePackage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RelayResponsePackage::requestId,
            ByteBufCodecs.VAR_INT, RelayResponsePackage::statusCode,
            ByteBufCodecs.byteArray(RelayHub.MAX_RESPONSE_BYTES), RelayResponsePackage::bodyUtf8,
            ByteBufCodecs.stringUtf8(512), RelayResponsePackage::error,
            RelayResponsePackage::new);

    /** 请求没能发出 —— 让服务端回退。 */
    public static RelayResponsePackage failure(int requestId, String error) {
        return new RelayResponsePackage(requestId, 0, new byte[0], error);
    }

    public String body() {
        return new String(bodyUtf8, StandardCharsets.UTF_8);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RelayResponsePackage message, IPayloadContext context) {
        if (context.flow().isServerbound()) {
            // 必须切回主线程再补全 future：TLM 的 handle() 里 token 计数会用 DataAttachment。
            context.enqueueWork(() -> onHandle(message, context));
        }
    }

    private static void onHandle(RelayResponsePackage message, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer) {
            RelayHub.onResponse(message.requestId(), message.statusCode(), message.body(), message.error());
        }
    }
}