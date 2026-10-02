package com.maidllmlocal.network;

import com.maidllmlocal.client.ClientRelayHandler;
import com.maidllmlocal.relay.RelayHub;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * server → client：请用你本机自己的配置把这份请求发出去。
 *
 * <p><b>只带 siteId 和请求体，绝不带 url / secret_key</b> —— 否则恶意服务器可以让你的客户端
 * 把请求（连同你的 key）打到任意地址去。客户端一律按 {@code siteId} 在自己本机的
 * {@code config/touhou_little_maid/sites/llm.json} 里查 url/key，查不到就报错让服务端回退。
 *
 * <p>body 用 {@code byte[]} 而非 writeUtf：请求体是大字符串，字符串编码有长度上限，
 * 用字节数组既没有这个坑，又能顺手把大小卡在 {@link RelayHub#MAX_BODY_BYTES}。
 *
 * <p>forge 分支：无 StreamCodec，encode/decode 手写（{@link NetBuf}），由
 * {@link NetworkInit} 登记进 SimpleChannel。
 *
 * @param requestId 服务端用来找回等待中的 callback
 * @param siteId    服务端指定的站点 id；客户端按<b>同名</b>站点解析自己的配置
 * @param bodyUtf8  与 TLM 本来要 POST 出去的那份 JSON 逐字节一致（UTF-8）
 */
public record RelayRequestPackage(int requestId, String siteId, byte[] bodyUtf8) {

    public static RelayRequestPackage of(int requestId, String siteId, String body) {
        return new RelayRequestPackage(requestId, siteId, body.getBytes(StandardCharsets.UTF_8));
    }

    public String body() {
        return new String(bodyUtf8, StandardCharsets.UTF_8);
    }

    public static void encode(RelayRequestPackage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.requestId);
        buf.writeUtf(msg.siteId, 128);
        NetBuf.writeBytes(buf, msg.bodyUtf8, RelayHub.MAX_BODY_BYTES);
    }

    public static RelayRequestPackage decode(FriendlyByteBuf buf) {
        return new RelayRequestPackage(buf.readVarInt(), buf.readUtf(128),
                NetBuf.readBytes(buf, RelayHub.MAX_BODY_BYTES));
    }

    public static void handle(RelayRequestPackage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getDirection().getReceptionSide() == LogicalSide.CLIENT) {
            ctx.enqueueWork(() -> onHandle(msg));
        }
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void onHandle(RelayRequestPackage msg) {
        ClientRelayHandler.onRequest(msg);
    }
}
