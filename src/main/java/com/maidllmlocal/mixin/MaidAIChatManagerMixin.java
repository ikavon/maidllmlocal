package com.maidllmlocal.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.UserPromptContexts;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.maidllmlocal.maica.MaicaClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * MAICA 站点不向站点要人设：TLM 在女仆人设为空时会拿 AUTO_GEN_SETTING 提示词去打一轮
 * 「生成人设」（{@code MaidAIChatManager.tryToChat} → {@code onSettingIsEmpty}）。站点指向
 * MAICA 时这一轮会原样中继进后端，成了纯粹的噪音：
 * <ul>
 *   <li>托管模式下它变成一轮正式的 MAICA 对话——MFocus、后端历史落库、MTrigger 全套照跑，
 *       而玩家的第一句话反而被吞掉（这一轮发出去的是生成请求，不是玩家消息）；</li>
 *   <li>生成回来的「人设卡」写进 {@code customSetting}，对托管毫无用处——后端骨架本就覆盖
 *       system，人设卡根本没被上传（见 MaicaScene 注释）。</li>
 * </ul>
 *
 * <p>处理方式：只包住 {@code onSettingIsEmpty} 这<b>一次调用</b>。站点客户端是
 * {@link MaicaClient} 时不走生成，直接把玩家的原话按正常对话发出去——等价于 TLM 在
 * 「有设定」时走的那条路，只是消息列表里没有 system（人设归后端）。其余站点
 * （玩家自配的 openai/deepseek、以及本模组的 player_relay）原样 {@code original.call}，
 * 一个字节都不动；TLM 的 {@code AutoGenSettingEnabled} 全局开关对我们也不再有任何影响——
 * 我们不是「生成人设」，是「照常说话」。
 *
 * <p>为什么用 {@code @WrapOperation} 而不是 {@code @Redirect}/覆写整个 {@code tryToChat}：
 * 它把目标实例作为带类型的参数递进来（不必给父类声明的 getMaid 再写一遍 @Shadow），
 * 且 {@code original.call} 天然保留原路径——既避开了私有方法 shadow，也不涉及在调用点
 * cancel 时的操作数栈清理。锚点失效时 {@code defaultRequire: 1} 会明确报错而非静默退化。
 */
@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class MaidAIChatManagerMixin {

    @WrapOperation(method = "tryToChat", at = @At(value = "INVOKE",
            target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatManager;"
                    + "onSettingIsEmpty(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/ChatClientInfo;"
                    + "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMClient;)V"))
    private void maidllmlocal$noAutoGenForMaica(MaidAIChatManager self, ChatClientInfo clientInfo,
                                                LLMClient client, Operation<Void> original,
                                                @Local(argsOnly = true) String message) {
        if (!(client instanceof MaicaClient)) {
            original.call(clientInfo, client);
            return;
        }

        // 与 MaidAIChatManager.normalChat 同构的一小段：那边是私有方法，这里只能照抄一份。
        // 顺序即字节码里的顺序：context 注入 → 用户消息入列 → 历史落档 → 发出去。
        // ⚠️ TLM 若改动 normalChat 的发送前处理，这里要跟着改（两处容易对照）。
        EntityMaid maid = self.getMaid();
        String withContext = UserPromptContexts.addContext(maid, message);
        maid.getAiChatManager().addUserHistory(message);
        List<LLMMessage> messages = Lists.newArrayList(LLMMessage.userChat(maid, withContext));
        client.chat(new LLMCallback(self, messages));
    }
}
