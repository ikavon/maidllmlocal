package com.maidllmlocal.network;

import com.maidllmlocal.client.ClientMaicaHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * server → client：请用你本机的 MAICA 账号（本机 llm.json 里 api_type=maica 的站点）跑这一轮对话。
 *
 * <p>与 {@link RelayRequestPackage} 同样的安全纪律：<b>只带 siteId 和消息体，绝不带 url / token</b>。
 * 客户端按 siteId 在自己本机配置里查 wss 地址与 access_token，查不到就回错让服务端走 onFailure。
 *
 * <p>forge 分支：无 StreamCodec，encode/decode 手写（{@link NetBuf}）。S2C 上限 1MB，本包 32KB 直发。
 *
 * @param requestId     服务端用来找回等待中的 callback
 * @param siteId        服务端指定的站点 id；客户端按<b>同名</b>站点解析自己的配置
 * @param messagesUtf8  OpenAI 风格 {@code [{"role","content"}, ...]} 的 JSON（UTF-8），
 *                      已按 session=-1 的协议约束裁剪（≤10 条 / ≤16KB，保 system 头）
 * @param targetLang    本轮的语言覆盖（{@code zh}/{@code en}），空串 = 用站点 headers 的默认值。
 *                      来源是 TLM 每女仆的「聊天语言」设置——一条连接会被多个女仆共用，
 *                      语言是 per-maid 的，只能在请求粒度下发
 */
public record MaicaChatRequestPackage(int requestId, String siteId, byte[] messagesUtf8, String targetLang) {

    /** session=-1 的协议上限就是 16KB，留一倍余量防呆。 */
    public static final int MAX_MESSAGES_BYTES = 32 * 1024;

    public static MaicaChatRequestPackage of(int requestId, String siteId, String messagesJson, String targetLang) {
        return new MaicaChatRequestPackage(requestId, siteId, messagesJson.getBytes(StandardCharsets.UTF_8),
                targetLang == null ? "" : targetLang);
    }

    public String messagesJson() {
        return new String(messagesUtf8, StandardCharsets.UTF_8);
    }

    public static void encode(MaicaChatRequestPackage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.requestId);
        buf.writeUtf(msg.siteId, 128);
        NetBuf.writeBytes(buf, msg.messagesUtf8, MAX_MESSAGES_BYTES);
        buf.writeUtf(msg.targetLang, 8);
    }

    public static MaicaChatRequestPackage decode(FriendlyByteBuf buf) {
        return new MaicaChatRequestPackage(buf.readVarInt(), buf.readUtf(128),
                NetBuf.readBytes(buf, MAX_MESSAGES_BYTES), buf.readUtf(8));
    }

    public static void handle(MaicaChatRequestPackage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getDirection().getReceptionSide() == LogicalSide.CLIENT) {
            ctx.enqueueWork(() -> onHandle(msg));
        }
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void onHandle(MaicaChatRequestPackage msg) {
        ClientMaicaHandler.onChatRequest(msg);
    }
}
