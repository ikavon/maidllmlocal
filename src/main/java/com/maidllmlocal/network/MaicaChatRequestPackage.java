package com.maidllmlocal.network;

import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.client.ClientMaicaHandler;
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
 * server → client：请用你本机的 MAICA 账号（本机 llm.json 里 api_type=maica 的站点）跑这一轮对话。
 *
 * <p>与 {@link RelayRequestPackage} 同样的安全纪律：<b>只带 siteId 和消息体，绝不带 url / token</b>。
 * 客户端按 siteId 在自己本机配置里查 wss 地址与 access_token，查不到就回错让服务端走 onFailure。
 *
 * @param requestId     服务端用来找回等待中的 callback
 * @param siteId        服务端指定的站点 id；客户端按<b>同名</b>站点解析自己的配置
 * @param messagesUtf8  OpenAI 风格 {@code [{"role","content"}, ...]} 的 JSON（UTF-8），
 *                      已按 session=-1 的协议约束裁剪（≤10 条 / ≤16KB，保 system 头）
 * @param targetLang    本轮的语言覆盖（{@code zh}/{@code en}），空串 = 用站点 headers 的默认值。
 *                      来源是 TLM 每女仆的「聊天语言」设置——一条连接会被多个女仆共用，
 *                      语言是 per-maid 的，只能在请求粒度下发
 */
public record MaicaChatRequestPackage(int requestId, String siteId, byte[] messagesUtf8, String targetLang)
        implements CustomPacketPayload {

    /** session=-1 的协议上限就是 16KB，留一倍余量防呆。 */
    public static final int MAX_MESSAGES_BYTES = 32 * 1024;

    public static final CustomPacketPayload.Type<MaicaChatRequestPackage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaidLLMLocal.MODID, "maica_chat_request"));

    public static final StreamCodec<ByteBuf, MaicaChatRequestPackage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MaicaChatRequestPackage::requestId,
            ByteBufCodecs.stringUtf8(128), MaicaChatRequestPackage::siteId,
            ByteBufCodecs.byteArray(MAX_MESSAGES_BYTES), MaicaChatRequestPackage::messagesUtf8,
            ByteBufCodecs.stringUtf8(8), MaicaChatRequestPackage::targetLang,
            MaicaChatRequestPackage::new);

    public static MaicaChatRequestPackage of(int requestId, String siteId, String messagesJson, String targetLang) {
        return new MaicaChatRequestPackage(requestId, siteId, messagesJson.getBytes(StandardCharsets.UTF_8),
                targetLang == null ? "" : targetLang);
    }

    public String messagesJson() {
        return new String(messagesUtf8, StandardCharsets.UTF_8);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MaicaChatRequestPackage message, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            context.enqueueWork(() -> onHandle(message));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void onHandle(MaicaChatRequestPackage message) {
        ClientMaicaHandler.onChatRequest(message);
    }
}
