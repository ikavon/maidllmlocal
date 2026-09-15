package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ServiceType;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.TTSSiteFormLayout;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * api_type = "mtts" 的 TTS 站点：MAICA 的官方语音合成（GET {url}/generate，Bearer 鉴权）。
 *
 * <p>字段语义：{@code url} = MTTS 服务根地址（官方 {@code https://maicadev.monika.love/tts}），
 * {@code secret_key} = MAICA access_token（与 maica LLM 站点同一个，<b>只留在玩家本机</b>）。
 *
 * <p>与 LLM 侧同样没有服务端兜底：服务端同名站点 secret_key 留空，任何失败直接 onFailure。
 */
public class MttsSite implements TTSSite {
    public static final String API_TYPE = "mtts";

    private final String id;
    private final ResourceLocation icon;
    private final Map<String, String> headers;
    private String url;
    private boolean enabled;
    private String secretKey;

    public MttsSite(String id, ResourceLocation icon, String url, boolean enabled,
                    String secretKey, Map<String, String> headers) {
        this.id = id;
        this.icon = icon;
        this.url = url;
        this.enabled = enabled;
        this.secretKey = secretKey;
        this.headers = headers;
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    @Override
    public TTSClient client() {
        return new MttsClient(this);
    }

    @Override
    public TTSSiteFormLayout formLayout() {
        return new MttsFormLayout(this);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public ResourceLocation icon() {
        return icon;
    }

    @Override
    public String url() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String secretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    @Override
    public Map<String, String> headers() {
        return headers;
    }

    public static class Serializer implements SerializableSite<MttsSite> {
        public static final Codec<MttsSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(Site.ID).forGetter(MttsSite::id),
                ResourceLocation.CODEC.fieldOf(Site.ICON).forGetter(MttsSite::icon),
                Codec.STRING.fieldOf(Site.URL).forGetter(MttsSite::url),
                Codec.BOOL.fieldOf(Site.ENABLED).forGetter(MttsSite::enabled),
                Codec.STRING.fieldOf(Site.SECRET_KEY).forGetter(MttsSite::secretKey),
                Codec.unboundedMap(Codec.STRING, Codec.STRING)
                        .optionalFieldOf(Site.HEADERS, Map.of()).forGetter(MttsSite::headers)
        ).apply(instance, MttsSite::new));

        @Override
        public Codec<MttsSite> codec() {
            return CODEC;
        }

        @Override
        public MttsSite defaultSite() {
            return new MttsSite(API_TYPE, SerializableSite.defaultIcon(API_TYPE),
                    "https://maicadev.monika.love/tts", false, "", Map.of());
        }
    }
}
