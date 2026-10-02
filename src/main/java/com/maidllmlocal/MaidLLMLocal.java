package com.maidllmlocal;

import com.maidllmlocal.debug.DebugCommands;
import com.maidllmlocal.maica.MaicaRelayHub;
import com.maidllmlocal.maica.MttsRelayHub;
import com.maidllmlocal.network.NetworkInit;
import com.maidllmlocal.relay.RelayHub;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * MaidLLMLocal —— 把 TLM 的 LLM 请求从「服务端发 HTTP」改成「发给女仆主人的客户端、由客户端用自己的配置发」。
 *
 * <h2>为什么要改</h2>
 * TLM 的站点配置是服务端全局一份，服务器上所有玩家共用一个 key 与供应方 —— 成本与隐私都不合理，
 * 改站点还得有 OP 权限。而 {@code AvailableSites} 在<b>客户端与服务端各自读本机</b>
 * {@code config/touhou_little_maid/sites/llm.json}，服务端下发的 {@code SyncAISitesMessage} 只喂编辑器 GUI、
 * 并不覆盖它 —— 所以"每玩家一份配置"在文件层早已成立，缺的只是"谁发这次 HTTP"。
 *
 * <h2>怎么改的</h2>
 * 注册一个 api_type 为 {@code player_relay} 的站点（{@link com.maidllmlocal.relay.RelaySite}），
 * 再用一个 mixin 把 {@code LLMOpenAIClient.chat()} 里那一次 {@code sendAsync} 换成"发给主人客户端"。
 * TLM 原有的响应解析、工具循环、气泡、TTS 全部零改动照跑；主人不可用或客户端没同意时直接走原路径，
 * 即服务端自己那份配置生效。
 *
 * <h2>forge 分支入口差异</h2>
 * Forge 的 {@code @Mod} 没有 {@code dist} 参数（NeoForge 专属）——客户端入口改为构造期
 * 按 {@link FMLEnvironment#dist} 分流到 {@link MaidLLMLocalClient#register}。
 * 网络注册也从 PayloadRegistrar 事件换成 {@link NetworkInit}（SimpleChannel，构造期直接完成）。
 */
@Mod(MaidLLMLocal.MODID)
public final class MaidLLMLocal {
    public static final String MODID = "maidllmlocal";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 模组版本字符串（gradle.properties 的 {@code mod_version}），用于对外的身份标识
     * ——MAICA 握手的 {@code frontend_id} 是 {@code <type>|<version>} 约定，
     * 从 mod 元数据取就不必每次发版回来改常量。
     */
    public static String version() {
        return ModList.get().getModContainerById(MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    public MaidLLMLocal(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // SimpleChannel 注册必须在登录协商开始前完成，mod 构造期最稳
        NetworkInit.init();

        MinecraftForge.EVENT_BUS.addListener(MaidLLMLocal::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(DebugCommands::register);

        // 客户端入口：只在物理客户端执行。分支不在专用服务器上走进 if 体，
        // MaidLLMLocalClient（及其牵出的 Minecraft/Screen 类）也就不会被类加载。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MaidLLMLocalClient.register(modEventBus);
        }
    }

    /** 玩家下线要清掉他的中继能力，并把在途请求回退掉，否则女仆会卡在等待气泡上直到超时。 */
    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RelayHub.onPlayerGone(player);
            MaicaRelayHub.onPlayerGone(player);
            MttsRelayHub.onPlayerGone(player);
            NetworkInit.clearPlayer(player.getUUID()); // 在途分片流一并丢弃
        }
    }
}
