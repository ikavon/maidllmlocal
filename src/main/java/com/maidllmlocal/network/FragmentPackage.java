package com.maidllmlocal.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * client → server：大 C2S 消息的传输分片（forge 分支特有，main 分支没有对应物 ——
 * NeoForge 1.21 的 C2S 没有 32KB 限制，大包直接发）。
 *
 * <p>1.20.1 的 {@code ServerboundCustomPayloadPacket} 解码上限是 32767 字节，
 * 而 relay 响应/MAICA 文本/MTTS 音频都远超它。发送侧（{@link NetworkInit#sendToServer}）
 * 把超限消息的编码字节切成 ≤{@link NetworkInit#MAX_FRAGMENT_BYTES} 的片，
 * 服务端 {@link FragmentAssembler} 按 (玩家, streamId) 重组后走该消息类型自己的
 * {@code handleReassembled} —— 与直发路径汇合在同一处业务逻辑。
 *
 * <p>TCP 保序，片按 seq 递增发送即按序到达；即便如此重组器也不信任顺序（按 seq 落位）。
 *
 * @param streamId  客户端为每条被分片的消息分配的流水号（进程内递增即可，跨玩家无需唯一——服务端按玩家隔离）
 * @param msgTypeId 逻辑消息类型（{@link NetworkInit} 的 ID_* 常量）
 * @param seq       片序号，0 起
 * @param total     总片数
 * @param part      本片携带的原文字节
 */
public record FragmentPackage(int streamId, int msgTypeId, int seq, int total, byte[] part) {

    public static void encode(FragmentPackage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.streamId);
        buf.writeVarInt(msg.msgTypeId);
        buf.writeVarInt(msg.seq);
        buf.writeVarInt(msg.total);
        NetBuf.writeBytes(buf, msg.part, NetworkInit.MAX_FRAGMENT_BYTES);
    }

    public static FragmentPackage decode(FriendlyByteBuf buf) {
        return new FragmentPackage(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), NetBuf.readBytes(buf, NetworkInit.MAX_FRAGMENT_BYTES));
    }

    public static void handle(FragmentPackage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getDirection().getReceptionSide() == LogicalSide.SERVER) {
            // 重组簿记全部在主线程做，省掉并发容器
            ctx.enqueueWork(() -> {
                // Forge 的 getSender() 在服务端侧就是 ServerPlayer（NeoForge 的 player() 返回
                // 泛型 Player，main 分支的 instanceof 由此而来），判空即可
                ServerPlayer player = ctx.getSender();
                if (player != null) {
                    FragmentAssembler.feed(player, msg);
                }
            });
        }
        ctx.setPacketHandled(true);
    }
}
