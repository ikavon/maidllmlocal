package com.maidllmlocal.network;

import com.maidllmlocal.client.ClientMttsHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * server → client：请用你本机的 MTTS 配置（tts.json 里 api_type=mtts 的同名站点）合成这段语音。
 *
 * <p>与 LLM 侧同一纪律：<b>只带 siteId 与 content JSON，绝不带 url / token</b>。
 *
 * <p>forge 分支：无 StreamCodec，encode/decode 手写（{@link NetBuf}）。S2C 上限 1MB，本包 32KB 直发。
 *
 * @param requestId    服务端用来找回等待中的 callback
 * @param siteId       服务端指定的 TTS 站点 id
 * @param contentUtf8  MTTS /generate 的 content 参数（JSON 字符串：text/emotion/target_lang/...）
 */
public record MttsRequestPackage(int requestId, String siteId, byte[] contentUtf8) {

    public static final int MAX_CONTENT_BYTES = 32 * 1024;

    public static MttsRequestPackage of(int requestId, String siteId, String contentJson) {
        return new MttsRequestPackage(requestId, siteId, contentJson.getBytes(StandardCharsets.UTF_8));
    }

    public String contentJson() {
        return new String(contentUtf8, StandardCharsets.UTF_8);
    }

    public static void encode(MttsRequestPackage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.requestId);
        buf.writeUtf(msg.siteId, 128);
        NetBuf.writeBytes(buf, msg.contentUtf8, MAX_CONTENT_BYTES);
    }

    public static MttsRequestPackage decode(FriendlyByteBuf buf) {
        return new MttsRequestPackage(buf.readVarInt(), buf.readUtf(128),
                NetBuf.readBytes(buf, MAX_CONTENT_BYTES));
    }

    public static void handle(MttsRequestPackage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getDirection().getReceptionSide() == LogicalSide.CLIENT) {
            ctx.enqueueWork(() -> onHandle(msg));
        }
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void onHandle(MttsRequestPackage msg) {
        ClientMttsHandler.onRequest(msg);
    }
}
