package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * api_type = "maica" 的站点：直接说 MAICA WebSocket 协议，而不是 OpenAI HTTP。
 *
 * <p>字段语义沿用 openai 站点（同一份 llm.json 格式）：
 * <ul>
 *   <li>{@code url} = MAICA 的 WebSocket 地址（官方节点 {@code wss://maicadev.monika.love/websocket}）</li>
 *   <li>{@code secret_key} = MAICA access_token（DCC 账号 register 流程获得）——<b>只留在玩家本机</b>，
 *       服务端的同名站点里这个字段填空即可，服务端永远用不到它（见 {@link MaicaClient}）</li>
 *   <li>{@code headers} 里可放 {@code target_lang: zh|en}（客户端握手时下发给 MAICA）</li>
 *   <li>{@code models} 仅为游戏内站点编辑界面能选出模型而存在，MAICA 后端不使用该字段</li>
 * </ul>
 *
 * <p>继承 {@link LLMOpenAISite} 只为白拿它的字段集与 codec 零件；传输与它毫无关系——
 * {@link #client()} 返回的是走中继的 {@link MaicaClient}。
 */
public class MaicaSite extends LLMOpenAISite {
    public static final String API_TYPE = "maica";

    public MaicaSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                     boolean hasThinkingField, Map<String, String> headers,
                     Map<String, LLMOpenAISite.ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    @Override
    public LLMClient client() {
        return new MaicaClient(this);
    }

    /** 与 {@link com.maidllmlocal.relay.RelaySite.Serializer} 同款写法，理由见彼处注释。 */
    public static class Serializer extends LLMOpenAISite.Serializer {
        public static final Codec<MaicaSite> MAICA_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(Site.ID).forGetter(MaicaSite::id),
                ResourceLocation.CODEC.fieldOf(Site.ICON).forGetter(MaicaSite::icon),
                Codec.STRING.fieldOf(Site.URL).forGetter(MaicaSite::url),
                Codec.BOOL.fieldOf(Site.ENABLED).forGetter(MaicaSite::enabled),
                Codec.STRING.fieldOf(Site.SECRET_KEY).forGetter(MaicaSite::secretKey),
                Codec.BOOL.optionalFieldOf(Site.HAS_THINKING_FIELD, false).forGetter(MaicaSite::hasThinkingField),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(Site.HEADERS).forGetter(MaicaSite::headers),
                MODELS_CODEC.fieldOf(Site.MODELS).forGetter(MaicaSite::modelEntries)
        ).apply(instance, MaicaSite::new));

        @Override
        public MaicaSite defaultSite() {
            return new MaicaSite(API_TYPE, SerializableSite.defaultIcon(API_TYPE),
                    "wss://maicadev.monika.love/websocket", false, "", false,
                    Map.of("target_lang", "zh"),
                    Map.of("daa4", new LLMOpenAISite.ModelEntry("daa4")));
        }

        @SuppressWarnings("unchecked")
        @Override
        public Codec<LLMOpenAISite> codec() {
            return (Codec<LLMOpenAISite>) (Codec<?>) MAICA_CODEC;
        }
    }
}
