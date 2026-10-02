package com.maidllmlocal.network;

import com.maidllmlocal.MaidLLMLocal;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Forge SimpleChannel 的注册与发送门面（main 分支对应物 = {@code RegisterPayloadHandlersEvent} +
 * 散在各处的 NeoForge {@code PacketDistributor} 静态调用）。
 *
 * <h2>与 main 分支的语义差异（移植时逐条对过）</h2>
 * <ul>
 *   <li><b>协商</b>：main 是逐包版本协商 + {@code optional()} 摘除；这边是单一
 *       {@link #PROTOCOL_VERSION} 字符串管整个 channel，两端不一致（或缺 channel）→ <b>登录被拒</b>。
 *       所以服务端装了本 mod 时，客户端必须也装（main 的 required 包语义下同样如此，行为等价）。
 *       改包结构 = bump PROTOCOL_VERSION = 两端同换 jar。</li>
 *   <li><b>C2S 32KB 硬限制</b>：1.20.1 的 {@code ServerboundCustomPayloadPacket} 解码上限
 *       32767 字节（已从 47.4.16 补丁后字节码确认 {@code sipush 32767}），而 NeoForge 1.21 无此限制。
 *       三个大 C2S 包（relay 响应 4MB / maica 响应 72KB / mtts 音频 4MB）超过
 *       {@link #MAX_DIRECT_C2S_BYTES} 时自动走 {@link FragmentPackage} 分片，
 *       服务端 {@link FragmentAssembler} 重组后走与原 handle 相同的
 *       {@code handleReassembled}。发送方仍然只调 {@link #sendToServer}，无感知。</li>
 * </ul>
 */
public final class NetworkInit {

    /** 与 main 分支 registrar("1.1.0") 对齐；包结构变化时 bump，两端必须一致。 */
    public static final String PROTOCOL_VERSION = "1.1.0";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(MaidLLMLocal.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    /** C2S 单包原文上限。协议硬限 32767，这里含 channel 名等框架开销留足余量。 */
    private static final int MAX_DIRECT_C2S_BYTES = 24 * 1024;
    /** 分片的单片原文上限（FragmentPackage 里 part 字段的防呆上限同源）。 */
    static final int MAX_FRAGMENT_BYTES = 24 * 1024;
    /** 重组允许的最大片数：256×24KB=6MB，覆盖最大 C2S 包（4MB 音频/响应）还有富余。 */
    static final int MAX_TOTAL_FRAGMENTS = 256;

    // 可分片消息的 msgTypeId（FragmentPackage 用它找回解码器/处理器；与 SimpleChannel 的
    // discriminator 是两套编号，互不相干）
    static final int ID_RELAY_RESPONSE = 1;
    static final int ID_MAICA_CHAT_RESPONSE = 2;
    static final int ID_MTTS_RESPONSE = 3;

    private static final AtomicInteger NEXT_STREAM_ID = new AtomicInteger(1);

    /** 一种可分片消息的编解码/处理三件套（类型安全靠 {@link #registerChunkable} 的 cls.cast 收口）。 */
    private record Chunkable(int id,
                             Function<Object, byte[]> encode,
                             Function<byte[], Object> decode,
                             BiConsumer<Object, ServerPlayer> handle) {}

    private static final Map<Class<?>, Chunkable> BY_CLASS = new HashMap<>();
    private static final Map<Integer, Chunkable> BY_ID = new HashMap<>();

    private NetworkInit() {}

    /** 在 mod 构造期调用（登录协商开始之前即可，构造期最稳）。 */
    public static void init() {
        int id = 0;
        // C->S：登录时的能力/同意声明（小包，永远直发）
        CHANNEL.messageBuilder(RelayHelloPackage.class, id++)
                .encoder(RelayHelloPackage::encode).decoder(RelayHelloPackage::decode)
                .consumerNetworkThread(RelayHelloPackage::handle).add();
        // S->C：请用你本机的配置把这份请求发出去（≤256KB，S2C 上限 1MB，直发）
        CHANNEL.messageBuilder(RelayRequestPackage.class, id++)
                .encoder(RelayRequestPackage::encode).decoder(RelayRequestPackage::decode)
                .consumerNetworkThread(RelayRequestPackage::handle).add();
        // C->S：本机那次请求的结果（可达 4MB → 注册直发通道之余登记为可分片）
        CHANNEL.messageBuilder(RelayResponsePackage.class, id++)
                .encoder(RelayResponsePackage::encode).decoder(RelayResponsePackage::decode)
                .consumerNetworkThread(RelayResponsePackage::handle).add();
        // S->C：服务端站点配置里哪些开了 MTrigger（≤4KB，直发）
        CHANNEL.messageBuilder(RelayCapabilityPackage.class, id++)
                .encoder(RelayCapabilityPackage::encode).decoder(RelayCapabilityPackage::decode)
                .consumerNetworkThread(RelayCapabilityPackage::handle).add();
        // S->C：请用你本机的 MAICA 账号跑这一轮对话（≤32KB，直发）
        CHANNEL.messageBuilder(MaicaChatRequestPackage.class, id++)
                .encoder(MaicaChatRequestPackage::encode).decoder(MaicaChatRequestPackage::decode)
                .consumerNetworkThread(MaicaChatRequestPackage::handle).add();
        // C->S：这一轮 MAICA 对话的结果（可达 72KB → 可分片）
        CHANNEL.messageBuilder(MaicaChatResponsePackage.class, id++)
                .encoder(MaicaChatResponsePackage::encode).decoder(MaicaChatResponsePackage::decode)
                .consumerNetworkThread(MaicaChatResponsePackage::handle).add();
        // S->C：请用你本机的 MTTS 配置合成这段语音（≤32KB，直发）
        CHANNEL.messageBuilder(MttsRequestPackage.class, id++)
                .encoder(MttsRequestPackage::encode).decoder(MttsRequestPackage::decode)
                .consumerNetworkThread(MttsRequestPackage::handle).add();
        // C->S：MTTS 合成的音频字节（可达 4MB → 可分片）
        CHANNEL.messageBuilder(MttsResponsePackage.class, id++)
                .encoder(MttsResponsePackage::encode).decoder(MttsResponsePackage::decode)
                .consumerNetworkThread(MttsResponsePackage::handle).add();
        // C->S：以上三种大包的传输分片
        CHANNEL.messageBuilder(FragmentPackage.class, id++)
                .encoder(FragmentPackage::encode).decoder(FragmentPackage::decode)
                .consumerNetworkThread(FragmentPackage::handle).add();

        registerChunkable(ID_RELAY_RESPONSE, RelayResponsePackage.class,
                RelayResponsePackage::toBytes, RelayResponsePackage::fromBytes,
                RelayResponsePackage::handleReassembled);
        registerChunkable(ID_MAICA_CHAT_RESPONSE, MaicaChatResponsePackage.class,
                MaicaChatResponsePackage::toBytes, MaicaChatResponsePackage::fromBytes,
                MaicaChatResponsePackage::handleReassembled);
        registerChunkable(ID_MTTS_RESPONSE, MttsResponsePackage.class,
                MttsResponsePackage::toBytes, MttsResponsePackage::fromBytes,
                MttsResponsePackage::handleReassembled);
    }

    private static <T> void registerChunkable(int msgTypeId, Class<T> cls,
                                              Function<T, byte[]> encode,
                                              Function<byte[], T> decode,
                                              BiConsumer<T, ServerPlayer> handle) {
        Chunkable c = new Chunkable(msgTypeId,
                o -> encode.apply(cls.cast(o)),
                b -> decode.apply(b),
                (o, p) -> handle.accept(cls.cast(o), p));
        BY_CLASS.put(cls, c);
        BY_ID.put(msgTypeId, c);
    }

    /** 玩家下线：丢弃其在途的分片流（与 RelayHub.onPlayerGone 同点位调用）。 */
    public static void clearPlayer(java.util.UUID playerId) {
        FragmentAssembler.clear(playerId);
    }

    /** S->C 单播。对应 main 分支的 {@code PacketDistributor.sendToPlayer(player, msg)}。 */
    public static void sendToPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /**
     * C->S。对应 main 分支的 {@code PacketDistributor.sendToServer(msg)}。
     * 可分片类型且原文超过 {@link #MAX_DIRECT_C2S_BYTES} 时自动切片走 {@link FragmentPackage}，
     * 否则按注册的原始消息直发 —— 调用方无感知。
     */
    public static void sendToServer(Object msg) {
        Chunkable c = BY_CLASS.get(msg.getClass());
        if (c != null) {
            byte[] full = c.encode().apply(msg);
            if (full.length > MAX_DIRECT_C2S_BYTES) {
                sendFragmented(c.id(), full);
                return;
            }
        }
        CHANNEL.sendToServer(msg);
    }

    private static void sendFragmented(int msgTypeId, byte[] full) {
        int streamId = NEXT_STREAM_ID.getAndIncrement();
        int total = (full.length + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES;
        for (int seq = 0; seq < total; seq++) {
            int from = seq * MAX_FRAGMENT_BYTES;
            int len = Math.min(MAX_FRAGMENT_BYTES, full.length - from);
            CHANNEL.sendToServer(new FragmentPackage(streamId, msgTypeId, seq, total,
                    Arrays.copyOfRange(full, from, from + len)));
        }
    }

    /** 分片重组完成后的分发（仅服务器主线程调用，见 {@link FragmentAssembler}）。 */
    static void dispatchReassembled(int msgTypeId, byte[] full, ServerPlayer player) {
        Chunkable c = BY_ID.get(msgTypeId);
        if (c == null) {
            MaidLLMLocal.LOGGER.warn("fragment: 未知 msgTypeId={}，丢弃 {} 字节", msgTypeId, full.length);
            return;
        }
        c.handle().accept(c.decode().apply(full), player);
    }
}
