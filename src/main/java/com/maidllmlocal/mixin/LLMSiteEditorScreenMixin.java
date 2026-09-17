package com.maidllmlocal.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.LLMSiteEditorScreen;
import com.maidllmlocal.maica.MaicaSite;
import com.maidllmlocal.relay.RelaySite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保住自定义 api_type：TLM 的 {@code LLMSiteEditorScreen.buildSite()} 结尾写死
 * {@code new LLMOpenAISite(...)}，在编辑器里对 maica / player_relay 站点按一次"保存"，
 * 类型就被洗成普通 openai —— 中继与 WS 逻辑整个消失（服务端文件里的条目变成 openai 协议）。
 *
 * <p>{@code @WrapOperation} 只拦那一次构造调用：源站点是我们的类型 → 用编辑后的字段重建
 * <b>同类型</b>对象；否则原样放行。比整方法覆写小得多，TLM 编辑器改版时最先坏的是
 * 锚点描述符，报错也最好认。
 */
@Mixin(value = LLMSiteEditorScreen.class, remap = false)
public abstract class LLMSiteEditorScreenMixin {

    @Shadow
    @Final
    private LLMSite sourceSite;

    // NEW 锚点只认两种形状：纯类名，或带返回类型的完整构造器描述符 `(args)Lowner;`
    // （返回类型即 owner，天然消歧义——同一次调用里 LLMOpenAISite 有 3 个重载构造器）。
    // 写成 `owner.<init>(...)` 不合法：defaultRequire=1 下游戏启动直接崩，编译期不报错。
    @WrapOperation(method = "buildSite", at = @At(value = "NEW",
            target = "(Ljava/lang/String;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;"
                    + "ZLjava/lang/String;Ljava/util/Map;Ljava/util/List;)"
                    + "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/openai/LLMOpenAISite;"))
    private LLMOpenAISite maidllmlocal$preserveCustomApiType(String id, ResourceLocation icon, String url,
                                                             boolean enabled, String secretKey,
                                                             Map<String, String> headers,
                                                             List<LLMOpenAISite.ModelEntry> models,
                                                             Operation<LLMOpenAISite> original) {
        if (this.sourceSite instanceof MaicaSite || this.sourceSite instanceof RelaySite) {
            LinkedHashMap<String, LLMOpenAISite.ModelEntry> entries = new LinkedHashMap<>();
            for (LLMOpenAISite.ModelEntry entry : models) {
                entries.putIfAbsent(entry.name(), entry);
            }
            boolean thinking = ((LLMOpenAISite) this.sourceSite).hasThinkingField();
            if (this.sourceSite instanceof MaicaSite) {
                return new MaicaSite(id, icon, url, enabled, secretKey, thinking, headers, entries);
            }
            return new RelaySite(id, icon, url, enabled, secretKey, thinking, headers, entries);
        }
        return original.call(id, icon, url, enabled, secretKey, headers, models);
    }
}
