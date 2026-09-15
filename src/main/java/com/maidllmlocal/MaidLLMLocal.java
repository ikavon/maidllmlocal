package com.maidllmlocal;

import com.maidllmlocal.maica.MaicaRelayHub;
import com.maidllmlocal.maica.MttsRelayHub;
import com.maidllmlocal.network.MaicaChatRequestPackage;
import com.maidllmlocal.network.MaicaChatResponsePackage;
import com.maidllmlocal.network.MttsRequestPackage;
import com.maidllmlocal.network.MttsResponsePackage;
import com.maidllmlocal.network.RelayHelloPackage;
import com.maidllmlocal.network.RelayRequestPackage;
import com.maidllmlocal.network.RelayResponsePackage;
import com.maidllmlocal.relay.RelayHub;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
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
 */
@Mod(MaidLLMLocal.MODID)
public final class MaidLLMLocal {
    public static final String MODID = "maidllmlocal";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MaidLLMLocal(IEventBus modEventBus) {
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, MaidLLMLocal::onRegisterPayloads);
        NeoForge.EVENT_BUS.addListener(MaidLLMLocal::onPlayerLoggedOut);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1.0.0");
        // S->C：请用你本机的配置把这份请求发出去
        registrar.playToClient(RelayRequestPackage.TYPE, RelayRequestPackage.STREAM_CODEC, RelayRequestPackage::handle);
        // C->S：本机那次请求的结果
        registrar.playToServer(RelayResponsePackage.TYPE, RelayResponsePackage.STREAM_CODEC, RelayResponsePackage::handle);
        // C->S：登录时的能力/同意声明
        registrar.playToServer(RelayHelloPackage.TYPE, RelayHelloPackage.STREAM_CODEC, RelayHelloPackage::handle);
        // S->C：请用你本机的 MAICA 账号跑这一轮对话
        registrar.playToClient(MaicaChatRequestPackage.TYPE, MaicaChatRequestPackage.STREAM_CODEC, MaicaChatRequestPackage::handle);
        // C->S：这一轮 MAICA 对话的结果
        registrar.playToServer(MaicaChatResponsePackage.TYPE, MaicaChatResponsePackage.STREAM_CODEC, MaicaChatResponsePackage::handle);
        // S->C：请用你本机的 MTTS 配置合成这段语音
        registrar.playToClient(MttsRequestPackage.TYPE, MttsRequestPackage.STREAM_CODEC, MttsRequestPackage::handle);
        // C->S：MTTS 合成的音频字节
        registrar.playToServer(MttsResponsePackage.TYPE, MttsResponsePackage.STREAM_CODEC, MttsResponsePackage::handle);
    }

    /** 玩家下线要清掉他的中继能力，并把在途请求回退掉，否则女仆会卡在等待气泡上直到超时。 */
    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RelayHub.onPlayerGone(player);
            MaicaRelayHub.onPlayerGone(player);
            MttsRelayHub.onPlayerGone(player);
        }
    }
}