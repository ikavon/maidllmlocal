package com.maidllmlocal.network;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.relay.RelayHub;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * client → server：登录后自报家门 —— "我能中继，且本机配了这些站点 id"。
 *
 * <p>这正是<b>同意机制</b>：只有本机 llm.json 里配了 {@code player_relay} 站点并被启用，其 id 才会出现在这里。
 * 玩家不想为某个服务器花自己的额度，只要不配那个站点即可，无需任何额外开关或界面。
 * 服务器若收不到本包（玩家没装模组），就一律走自己那份配置，行为与现在完全一致。
 *
 * <p>forge 分支：无 StreamCodec，encode/decode 手写。
 */
public record RelayHelloPackage(String siteIds) {

    private static final String SEPARATOR = "\n";

    /** 站点 id 列表实际最多几百字节；8KB 上限既防呆又远离 C2S 32KB 协议红线（本包直发不分片）。 */
    private static final int MAX_SITE_IDS_CHARS = 8 * 1024;

    public static RelayHelloPackage of(Set<String> ids) {
        return new RelayHelloPackage(String.join(SEPARATOR, ids));
    }

    public static void encode(RelayHelloPackage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.siteIds, MAX_SITE_IDS_CHARS);
    }

    public static RelayHelloPackage decode(FriendlyByteBuf buf) {
        return new RelayHelloPackage(buf.readUtf(MAX_SITE_IDS_CHARS));
    }

    public static void handle(RelayHelloPackage msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getDirection().getReceptionSide() == LogicalSide.SERVER) {
            ctx.enqueueWork(() -> onHandle(msg, ctx));
        }
        ctx.setPacketHandled(true);
    }

    private static void onHandle(RelayHelloPackage msg, NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(msg.siteIds().split(SEPARATOR))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(ids::add);
        RelayHub.onHello(player, ids);
        replyCapability(player, ids);
    }

    /**
     * 把"服务端这些站点开了 MTrigger"回给客户端 —— {@link RelayCapabilityPackage} 的发送侧。
     *
     * <p>只报客户端<b>声明过</b>的那些 id（∩ 服务端自己的站点表），最小必要：
     * 客户端声明过什么，才是它本机真有的站点。
     *
     * <p>forge 分支差异：main 分支的 {@code RelayCapabilityPackage} 是 optional 注册，
     * 发送前要 {@code hasChannel} 探一句；这边 SimpleChannel 的握手协商<b>强制两端
     * channel 集合与协议版本一致</b>（对端没有本模组根本进不了服），能走到这里
     * channel 必然存在，无需再探。
     */
    private static void replyCapability(ServerPlayer player, Set<String> ids) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (String id : ids) {
            // headers() 在 LLMOpenAISite 上而不在 LLMSite 接口上，故收敛到具体类型
            if (AvailableSites.LLM_SITES.get(id) instanceof LLMOpenAISite site) {
                flags.put(id, Boolean.parseBoolean(site.headers().getOrDefault("enable_mt", "false")));
            }
        }
        NetworkInit.sendToPlayer(player, RelayCapabilityPackage.of(flags));
    }
}
