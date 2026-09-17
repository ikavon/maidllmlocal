package com.maidllmlocal.relay;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.maidllmlocal.util.TlmSiteCodec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * api_type = "player_relay" 的站点。
 *
 * <p>语义与内置的 openai 站点<b>完全相同</b>（请求体构造、模型列表、headers 都一样），唯一区别是
 * {@link #getApiType()} 返回别的字符串 —— mixin 据此认出"这次 HTTP 该改派给女仆主人的客户端"。
 *
 * <p>所以本类不需要自己的 {@code client()}：从 {@link LLMOpenAISite} 继承来的那个
 * {@code new LLMOpenAIClient(LLM_HTTP_CLIENT, this)} 已经够用，传输整段由 mixin 接管。
 * 站点自己的 {@code url}/{@code secret_key} 只在<b>回退</b>时用得上（主人离线 / 客户端没配 / 超时）。
 */
public class RelaySite extends LLMOpenAISite {
    public static final String API_TYPE = "player_relay";

    public RelaySite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                     boolean hasThinkingField, Map<String, String> headers,
                     Map<String, LLMOpenAISite.ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    /**
     * 自有 codec：字段集与 {@link LLMOpenAISite.Serializer#CODEC} 一致（复用父类的 {@code MODELS_CODEC}），
     * 只是把构造目标换成 {@link RelaySite}，这样读回来的就是带正确 api_type 的对象。
     *
     * <p>注意 1.21 的 MODELS 是 {@code Map<String, ModelEntry>}，不是 1.20 的 {@code Map<String, String>}。
     */
    public static class Serializer extends LLMOpenAISite.Serializer {
        /** 容错版 models：数组或对象映射都吃（见 {@link TlmSiteCodec#tolerantModels} 注释）。 */
        private static final Codec<Map<String, LLMOpenAISite.ModelEntry>> MODELS =
                TlmSiteCodec.tolerantModels(MODELS_CODEC, SINGLE_MODEL_CODEC);

        /**
         * 构造目标仍是 {@link RelaySite}，所以从 json 读回来的对象是货真价实的 RelaySite，
         * {@code getApiType()} 因此正确 —— 这一点很关键，{@code LLMSite.writeSites} 正是靠
         * api_type 反查 serializer 决定用哪份 codec 写回的。
         */
        public static final Codec<RelaySite> RELAY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(Site.ID).forGetter(RelaySite::id),
                ResourceLocation.CODEC.fieldOf(Site.ICON).forGetter(RelaySite::icon),
                Codec.STRING.fieldOf(Site.URL).forGetter(RelaySite::url),
                Codec.BOOL.fieldOf(Site.ENABLED).forGetter(RelaySite::enabled),
                Codec.STRING.fieldOf(Site.SECRET_KEY).forGetter(RelaySite::secretKey),
                Codec.BOOL.optionalFieldOf(Site.HAS_THINKING_FIELD, false).forGetter(RelaySite::hasThinkingField),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(Site.HEADERS).forGetter(RelaySite::headers),
                MODELS.optionalFieldOf(Site.MODELS, Map.of()).forGetter(RelaySite::modelEntries)
        ).apply(instance, RelaySite::new));

        @Override
        public RelaySite defaultSite() {
            // 默认给一个模型条目，否则游戏内站点编辑界面选不出模型。真正可用的模型名由管理员/玩家各自填。
            return new RelaySite(API_TYPE, SerializableSite.defaultIcon(API_TYPE), "", false, "", false,
                    Map.of(), Map.of("maica-daa4", new LLMOpenAISite.ModelEntry("maica-daa4")));
        }

        /**
         * 父类 {@code Serializer} 实现的是 {@code SerializableSite<LLMOpenAISite>}，而泛型不协变，
         * 所以 {@code Codec<RelaySite>} 无法直接覆写 {@code codec()}，需要一次未检查的向上转型。
         *
         * <p>安全性：读取端由 {@code RelaySite::new} 构造，必然产出 RelaySite；写入端只会拿到
         * RelaySite 实例（api_type 决定了只有本类型会查到这份 serializer）。不存在把普通
         * {@code LLMOpenAISite} 传进来写的情况。
         */
        @SuppressWarnings("unchecked")
        @Override
        public Codec<LLMOpenAISite> codec() {
            return (Codec<LLMOpenAISite>) (Codec<?>) RELAY_CODEC;
        }
    }
}