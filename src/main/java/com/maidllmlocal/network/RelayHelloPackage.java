package com.maidllmlocal.network;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.relay.RelayHub;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * client → server：登录后自报家门 —— "我能中继，且本机配了这些站点 id"。
 *
 * <p>这正是<b>同意机制</b>：只有本机 llm.json 里配了 {@code player_relay} 站点并被启用，其 id 才会出现在这里。
 * 玩家不想为某个服务器花自己的额度，只要不配那个站点即可，无需任何额外开关或界面。
 * 服务器若收不到本包（玩家没装模组），就一律走自己那份配置，行为与现在完全一致。
 */
public record RelayHelloPackage(String siteIds) implements CustomPacketPayload {

    private static final String SEPARATOR = "\n";

    public static final CustomPacketPayload.Type<RelayHelloPackage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaidLLMLocal.MODID, "relay_hello"));

    public static final StreamCodec<ByteBuf, RelayHelloPackage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RelayHelloPackage::siteIds,
            RelayHelloPackage::new);

    public static RelayHelloPackage of(Set<String> ids) {
        return new RelayHelloPackage(String.join(SEPARATOR, ids));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RelayHelloPackage message, IPayloadContext context) {
        if (context.flow().isServerbound()) {
            context.enqueueWork(() -> onHandle(message, context));
        }
    }

    private static void onHandle(RelayHelloPackage message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(message.siteIds().split(SEPARATOR))
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
     * <p>⚠️ <b>必须先问对端有没有这个 channel</b>：该包是 optional 注册的，服务端装了新版而客户端
     * 没有时，直接发会抛 {@code UnsupportedOperationException}。问一句的代价是零，不问的代价是
     * 每次登录一条异常。
     */
    private static void replyCapability(ServerPlayer player, Set<String> ids) {
        if (!player.connection.hasChannel(RelayCapabilityPackage.TYPE)) {
            MaidLLMLocal.LOGGER.info(
                    "relay capability: 客户端没有该 channel（旧版本），跳过下发 MTrigger 状态");
            return;
        }
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (String id : ids) {
            // headers() 在 LLMOpenAISite 上而不在 LLMSite 接口上，故收敛到具体类型
            if (AvailableSites.LLM_SITES.get(id) instanceof LLMOpenAISite site) {
                flags.put(id, Boolean.parseBoolean(site.headers().getOrDefault("enable_mt", "false")));
            }
        }
        PacketDistributor.sendToPlayer(player, RelayCapabilityPackage.of(flags));
    }
}