package com.maidllmlocal.network;

import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.client.ClientMttsHandler;
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
 * server → client：请用你本机的 MTTS 配置（tts.json 里 api_type=mtts 的同名站点）合成这段语音。
 *
 * <p>与 LLM 侧同一纪律：<b>只带 siteId 与 content JSON，绝不带 url / token</b>。
 *
 * @param requestId    服务端用来找回等待中的 callback
 * @param siteId       服务端指定的 TTS 站点 id
 * @param contentUtf8  MTTS /generate 的 content 参数（JSON 字符串：text/emotion/target_lang/...）
 */
public record MttsRequestPackage(int requestId, String siteId, byte[] contentUtf8)
        implements CustomPacketPayload {

    public static final int MAX_CONTENT_BYTES = 32 * 1024;

    public static final CustomPacketPayload.Type<MttsRequestPackage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaidLLMLocal.MODID, "mtts_request"));

    public static final StreamCodec<ByteBuf, MttsRequestPackage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MttsRequestPackage::requestId,
            ByteBufCodecs.stringUtf8(128), MttsRequestPackage::siteId,
            ByteBufCodecs.byteArray(MAX_CONTENT_BYTES), MttsRequestPackage::contentUtf8,
            MttsRequestPackage::new);

    public static MttsRequestPackage of(int requestId, String siteId, String contentJson) {
        return new MttsRequestPackage(requestId, siteId, contentJson.getBytes(StandardCharsets.UTF_8));
    }

    public String contentJson() {
        return new String(contentUtf8, StandardCharsets.UTF_8);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MttsRequestPackage message, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            context.enqueueWork(() -> onHandle(message));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void onHandle(MttsRequestPackage message) {
        ClientMttsHandler.onRequest(message);
    }
}
