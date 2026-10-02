package com.maidllmlocal.network;

import com.maidllmlocal.maica.MaicaRelayHub;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * client → server：本机那一轮 MAICA 对话的结果。
 *
 * <p>{@code ok=false} 覆盖一切失败（本机没配站点、WS 连不上、MAICA 致命错误、超时）——
 * 服务端没有自己的 MAICA token，<b>不存在回退</b>，一律转成 {@code onFailure} 气泡。
 * {@code text} 是客户端聚合好的整段回复原文（含情绪标签，清洗在服务端统一做）。
 * {@code triggersUtf8} 是本轮 MTrigger 调用的 JSON 数组（{@code [{"name","arguments"}]}，
 * 服务端再解析执行）；站点没开 enable_mt 时是 {@code "[]"}。
 *
 * <p>forge 分支：无 StreamCodec，encode/decode 手写（{@link NetBuf}）；text 64KB + triggers 8KB
 * 可超 1.20.1 的 C2S 32KB 协议限制，故登记为可分片（见 {@link NetworkInit}）。
 */
public record MaicaChatResponsePackage(int requestId, boolean ok, byte[] textUtf8, byte[] triggersUtf8, String error) {

    /** 一轮回复按 max_tokens 4096 估也就十几 KB，64KB 上限防呆。 */
    public static final int MAX_TEXT_BYTES = 64 * 1024;

    /** 触发器参数都很小（单字段 ≤256 字符），一轮三五条，8KB 绰绰有余。 */
    public static final int MAX_TRIGGERS_BYTES = 8 * 1024;

    public static MaicaChatResponsePackage success(int requestId, String text, String triggersJson) {
        return new MaicaChatResponsePackage(requestId, true,
                text.getBytes(StandardCharsets.UTF_8),
                triggersJson.getBytes(StandardCharsets.UTF_8), "");
    }

    public static MaicaChatResponsePackage failure(int requestId, String error) {
        return new MaicaChatResponsePackage(requestId, false, new byte[0], new byte[0], error);
    }

    public String text() {
        return new String(textUtf8, StandardCharsets.UTF_8);
    }

    public String triggersJson() {
        return new String(triggersUtf8, StandardCharsets.UTF_8);
    }

    public static void encode(MaicaChatResponsePackage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.requestId);
        buf.writeBoolean(msg.ok);
        NetBuf.writeBytes(buf, msg.textUtf8, MAX_TEXT_BYTES);
        NetBuf.writeBytes(buf, msg.triggersUtf8, MAX_TRIGGERS_BYTES);
        buf.writeUtf(msg.error, 512);
    }

    public static MaicaChatResponsePackage decode(FriendlyByteBuf buf) {
        return new MaicaChatResponsePackage(buf.readVarInt(), buf.readBoolean(),
                NetBuf.readBytes(buf, MAX_TEXT_BYTES),
                NetBuf.readBytes(buf, MAX_TRIGGERS_BYTES),
                buf.readUtf(512));
    }

    public static byte[] toBytes(MaicaChatResponsePackage msg) {
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

    public static MaicaChatResponsePackage fromBytes(byte[] data) {
        return decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));
    }

    public static void handle(MaicaChatResponsePackage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getDirection().getReceptionSide() == LogicalSide.SERVER) {
            // 回主线程再补全：callback 会碰女仆实体与气泡
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
    static void handleReassembled(MaicaChatResponsePackage msg, ServerPlayer player) {
        MaicaRelayHub.onResponse(msg.requestId(), msg.ok(),
                msg.text(), msg.triggersJson(), msg.error());
    }
}
