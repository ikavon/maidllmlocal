package com.maidllmlocal.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.client.ServerMtState;
import com.maidllmlocal.client.maica.MaicaAutoLogin;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * server → client：服务端那份站点配置里，<b>哪些站点开了 MTrigger</b>。
 *
 * <p>是 {@link RelayHelloPackage} 的应答，在服务端收到能力上报后立刻发回。用来把玩家侧的
 * {@code enable_mt} 从"要配的开"降为"想关才配"（见 {@link ServerMtState}）。
 *
 * <h2>为什么用可选包（optional）而不是给现有包加字段</h2>
 * registrar 的版本号是<b>按包逐个比对</b>的，任一注册包的版本不一致 → 连接被
 * <b>直接拒绝</b>（{@code NetworkComponentNegotiator} 返回失败 → {@code multiplayer.disconnect.incompatible}）。
 * 所以给现有包加字段 = 逼所有玩家同换 jar。而注册成 <b>optional</b> 的包在协商时会被"对端没有
 * 就摘掉"，于是<b>老客户端照常进服</b>（收不到这个包而已，行为与加这个功能之前完全一致）。
 *
 * <p>代价是<b>发送前必须问一句</b>对端有没有这个 channel（{@code ICommonPacketListener.hasChannel}），
 * 否则会抛 {@code UnsupportedOperationException} —— 见 {@link RelayHelloPackage} 里的守卫。
 */
public record RelayCapabilityPackage(String json) implements CustomPacketPayload {

    /** 站点 id → 布尔，几十个站点也就几百字节；4KB 已是防呆上限。 */
    public static final int MAX_JSON_BYTES = 4 * 1024;

    public static final CustomPacketPayload.Type<RelayCapabilityPackage> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MaidLLMLocal.MODID, "relay_capability"));

    public static final StreamCodec<ByteBuf, RelayCapabilityPackage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_JSON_BYTES), RelayCapabilityPackage::json,
            RelayCapabilityPackage::new);

    public static RelayCapabilityPackage of(Map<String, Boolean> flags) {
        JsonObject root = new JsonObject();
        flags.forEach(root::addProperty);
        return new RelayCapabilityPackage(root.toString());
    }

    /** 解析成 map。内容损坏时返回空表 —— 空表等同"不知道"，不会凭空打开任何开关。 */
    public Map<String, Boolean> flags() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                parsed.getAsJsonObject().entrySet().forEach(e -> {
                    if (e.getValue().isJsonPrimitive()) {
                        out.put(e.getKey(), e.getValue().getAsBoolean());
                    }
                });
            }
        } catch (Exception e) {
            MaidLLMLocal.LOGGER.warn("relay capability: 内容解析失败，视同不知道: {}", json, e);
        }
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RelayCapabilityPackage message, IPayloadContext context) {
        if (!context.flow().isClientbound()) {
            return;
        }
        // 本包只会在客户端被收到（专用服务器上永远走不到这里）。enqueueWork 回主线程：
        // 下面要读/写 AvailableSites 的站点表，还可能回写文件。
        context.enqueueWork(() -> {
            ServerMtState.set(message.flags());
            MaidLLMLocal.LOGGER.info("relay capability: 服务端站点 MTrigger 状态 = {}", message.flags());
            // 状态到手后重跑一次 headers 同步：登录时的同步可能跑在这个包之前，
            // 后到者生效即幂等（FollowEnableMt 的语义见 MaicaAutoLogin）
            MaicaAutoLogin.onServerStateUpdated();
        });
    }
}