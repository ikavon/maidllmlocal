package com.maidllmlocal;

import com.maidllmlocal.client.ClientRelayHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端专用入口。只做一件事：登录成功后向服务端自报"本机有哪些可中继的站点"。
 *
 * <p>没有这一步，服务端就无从知道对面是不是装了模组，只能每次都发出去等超时才知道要回退 ——
 * 那样不装模组的玩家每句话都要白等十几秒。
 */
@Mod(value = MaidLLMLocal.MODID, dist = Dist.CLIENT)
public final class MaidLLMLocalClient {

    public MaidLLMLocalClient(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(ClientRelayHandler::onLogin);
    }
}