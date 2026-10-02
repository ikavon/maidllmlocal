package com.maidllmlocal.network;

import com.maidllmlocal.relay.RelayHub;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * client → server：本机那次请求的结果。
 *
 * <p>{@code error} 与 {@code statusCode} 是<b>分开</b>的两种失败，处理方式不同：
 * <ul>
 *   <li>{@code error} 非空 = 客户端<b>根本没能发出</b>这次请求（本机没配该站点、连不上、超时）→ 服务端回退到自己那份配置；</li>
 *   <li>{@code statusCode} + {@code body} = 请求真的发了，这是对方返回的应答（哪怕是 4xx/5xx）→ 原样交给 TLM 处理，
 *       <b>不回退</b>。否则会变成"玩家自己的端点报错，却悄悄拿服务端的 key 重试一遍"。</li>
 * </ul>
 *
 * <p>forge 分支：无 StreamCodec，encode/decode 手写（{@link NetBuf}）；响应体上限 4MB
 * 远超 1.20.1 的 C2S 32KB 协议限制，故登记为可分片（{@link NetworkInit} 超限自动走
 * {@link FragmentPackage}，重组后与本类 {@link #handle} 汇合在 {@link #handleReassembled}）。
 */
public record RelayResponsePackage(int requestId, int statusCode, byte[] bodyUtf8, String error) {

    /** 请求没能发出 —— 让服务端回退。 */
    public static RelayResponsePackage failure(int requestId, String error) {
        return new RelayResponsePackage(requestId, 0, new byte[0], error);
    }

    public String body() {
        return new String(bodyUtf8, StandardCharsets.UTF_8);
    }

    public static void encode(RelayResponsePackage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.requestId);
        buf.writeVarInt(msg.statusCode);
        NetBuf.writeBytes(buf, msg.bodyUtf8, RelayHub.MAX_RESPONSE_BYTES);
        buf.writeUtf(msg.error, 512);
    }

    public static RelayResponsePackage decode(FriendlyByteBuf buf) {
        return new RelayResponsePackage(buf.readVarInt(), buf.readVarInt(),
                NetBuf.readBytes(buf, RelayHub.MAX_RESPONSE_BYTES), buf.readUtf(512));
    }

    public static byte[] toBytes(RelayResponsePackage msg) {
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

    public static RelayResponsePackage fromBytes(byte[] data) {
        return decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));
    }

    public static void handle(RelayResponsePackage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getDirection().getReceptionSide() == LogicalSide.SERVER) {
            // 必须切回主线程再补全 future：TLM 的 handle() 里 token 计数会碰实体挂载数据。
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
    static void handleReassembled(RelayResponsePackage msg, ServerPlayer player) {
        RelayHub.onResponse(msg.requestId(), msg.statusCode(), msg.body(), msg.error());
    }
}
