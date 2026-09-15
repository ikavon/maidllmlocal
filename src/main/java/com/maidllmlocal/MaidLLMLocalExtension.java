package com.maidllmlocal;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ServiceType;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.maidllmlocal.maica.MaicaSite;
import com.maidllmlocal.maica.MttsSite;
import com.maidllmlocal.relay.RelaySite;

/**
 * 通过 TLM 官方的扩展点注册站点类型 —— 不需要 mixin 站点系统，这是它留给附属的正门。
 *
 * <p>用 {@code @LittleMaidExtension} 标注即可被 TLM 的注解扫描收进 {@code TouhouLittleMaid.EXTENSIONS}，
 * 随后 {@code SerializerRegister.init()} 会回调 {@link #registerAIChatSerializer}。
 */
@LittleMaidExtension
public class MaidLLMLocalExtension implements ILittleMaid {

    @Override
    public void registerAIChatSerializer(SerializerRegister register) {
        register.register(ServiceType.LLM, RelaySite.API_TYPE, new RelaySite.Serializer());
        register.register(ServiceType.LLM, MaicaSite.API_TYPE, new MaicaSite.Serializer());
        register.register(ServiceType.TTS, MttsSite.API_TYPE, new MttsSite.Serializer());
    }
}