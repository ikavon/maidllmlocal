package com.maidllmlocal.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.client.ServerMtState;
import com.maidllmlocal.client.maica.MaicaAutoLogin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * server → client：服务端那份站点配置里，<b>哪些站点开了 MTrigger</b>。
 *
 * <p>是 {@link RelayHelloPackage} 的应答，在服务端收到能力上报后立刻发回。用来把玩家侧的
 * {@code enable_mt} 从"要配的开"降为"想关才配"（见 {@link ServerMtState}）。
 *
 * <h2>forge 分支与 main 的差异（optional → 强制协商）</h2>
 * main 分支把它注册成 optional 包：老客户端没有这个 channel 也能进服，代价是发送前要
 * {@code hasChannel} 探一句。Forge 的 SimpleChannel 没有 optional 语义——握手协商强制
 * 两端 channel 集合与 {@link NetworkInit#PROTOCOL_VERSION} 一致，不一致直接拒绝登录。
 * 于是：①发送前无需探测（能进服 = channel 必然存在）；②今后给任何包加字段都要 bump
 * PROTOCOL_VERSION，且两端同换 jar——这正是 main 分支用 optional 避开的代价，公测阶段
 * （服主统一发包）可以接受，写进安装说明。
 */
public record RelayCapabilityPackage(String json) {

    /** 站点 id → 布尔，几十个站点也就几百字节；4KB 已是防呆上限。 */
    public static final int MAX_JSON_BYTES = 4 * 1024;

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

    public static void encode(RelayCapabilityPackage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.json, MAX_JSON_BYTES);
    }

    public static RelayCapabilityPackage decode(FriendlyByteBuf buf) {
        return new RelayCapabilityPackage(buf.readUtf(MAX_JSON_BYTES));
    }

    public static void handle(RelayCapabilityPackage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getDirection().getReceptionSide() != LogicalSide.CLIENT) {
            ctx.setPacketHandled(true);
            return;
        }
        // 本包只会在客户端被收到（专用服务器上永远走不到这里）。enqueueWork 回主线程：
        // 下面要读/写 AvailableSites 的站点表，还可能回写文件。
        ctx.enqueueWork(() -> {
            ServerMtState.set(msg.flags());
            MaidLLMLocal.LOGGER.info("relay capability: 服务端站点 MTrigger 状态 = {}", msg.flags());
            // 状态到手后重跑一次 headers 同步：登录时的同步可能跑在这个包之前，
            // 后到者生效即幂等（FollowEnableMt 的语义见 MaicaAutoLogin）
            MaicaAutoLogin.onServerStateUpdated();
        });
        ctx.setPacketHandled(true);
    }
}
