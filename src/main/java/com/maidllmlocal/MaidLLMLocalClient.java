package com.maidllmlocal;

import com.maidllmlocal.account.MaicaAccountConfig;
import com.maidllmlocal.client.ClientRelayHandler;
import com.maidllmlocal.client.maica.MaicaAutoLogin;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端专用入口。
 *
 * <h3>模组启动时</h3>
 * 生成 {@code config/maidllmlocal/maica_account.json} 模板（已存在则不碰）——
 * 让玩家装完开一次游戏就"看得见该把账号配在哪"，不必先懂进世界这回事。
 *
 * <h3>进世界时（两件事，顺序有意义）</h3>
 * <ol>
 *   <li>向服务端自报"本机有哪些可中继的站点"（没有这一步，服务端只能对没装模组的玩家
 *       每句话白等十几秒超时）；</li>
 *   <li>MAICA 自动登录（{@link MaicaAutoLogin}）：本机没 token 而账号文件里有密码时，
 *       自己去换 token 回填站点配置。<b>注册在能力上报之后</b>——换到 token 它会再 resync 一次。</li>
 * </ol>
 */
@Mod(value = MaidLLMLocal.MODID, dist = Dist.CLIENT)
public final class MaidLLMLocalClient {

    public MaidLLMLocalClient(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(ClientRelayHandler::onLogin);
        NeoForge.EVENT_BUS.addListener(MaicaAutoLogin::onLogin);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // readOrTemplate 的副作用就是我们要的：缺文件时写出模板（存在/损坏时只读/只告警，绝不动玩家文件）
        event.enqueueWork(MaicaAccountConfig::readOrTemplate);
    }
}