package com.maidllmlocal.network;

import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.client.ClientRelayHandler;
import com.maidllmlocal.relay.RelayHub;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.charset.StandardCharsets;

/**
 * server → client：请用你本机自己的配置把这份请求发出去。
 *
 * <p><b>只带 siteId 和请求体，绝不带 url / secret_key</b> —— 否则恶意服务器可以让你的客户端
 * 把请求（连同你的 key）打到任意地址去。客户端一律按 {@code siteId} 在自己本机的
 * {@code config/touhou_little_maid/sites/llm.json} 里查 url/key，查不到就报错让服务端回退。
 *
 * <p>body 用 {@code byte[]} 而非 STRING_UTF8：请求体是大字符串，而字符串 codec 有长度上限，
 * 用字节数组既没有这个坑，又能顺手把大小卡在 {@link RelayHub#MAX_BODY_BYTES}。
 *
 * @param requestId 服务端用来找回等待中的 callback
 * @param siteId    服务端指定的站点 id；客户端按<b>同名</b>站点解析自己的配置
 * @param bodyUtf8  与 TLM 本来要 POST 出去的那份 JSON 逐字节一致（UTF-8）
 */
public record RelayRequestPackage(int requestId, String siteId, byte[] bodyUtf8) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RelayRequestPackage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaidLLMLocal.MODID, "relay_request"));

    public static final StreamCodec<ByteBuf, RelayRequestPackage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RelayRequestPackage::requestId,
            ByteBufCodecs.stringUtf8(128), RelayRequestPackage::siteId,
            ByteBufCodecs.byteArray(RelayHub.MAX_BODY_BYTES), RelayRequestPackage::bodyUtf8,
            RelayRequestPackage::new);

    public static RelayRequestPackage of(int requestId, String siteId, String body) {
        return new RelayRequestPackage(requestId, siteId, body.getBytes(StandardCharsets.UTF_8));
    }

    public String body() {
        return new String(bodyUtf8, StandardCharsets.UTF_8);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RelayRequestPackage message, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            context.enqueueWork(() -> onHandle(message));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void onHandle(RelayRequestPackage message) {
        ClientRelayHandler.onRequest(message);
    }
}