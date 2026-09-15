package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Client;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.google.gson.JsonObject;
import com.maidllmlocal.MaidLLMLocal;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * MTTS 站点的 TTSClient：与 {@link MaicaClient} 同款中继——打包 content JSON 发给女仆主人的
 * 客户端，客户端用本机的 MTTS url + token 调 {@code GET /generate}，音频字节回传后完成 callback。
 *
 * <p>MTTS 协议（移植 maica4tlm 的 shim.py）：{@code GET {base}/generate?content=<urlencoded json>}
 * + {@code Authorization: Bearer <token>}；content 是 JSON 字符串：
 * {@code {"text","emotion","target_lang","persistence":false,"lossless":false}}。
 * {@code lossless=false} 时官方节点直接返回 mp3，正好喂给 TLM（只解码 MP3/OGG）。
 */
public class MttsClient implements TTSClient {
    private final MttsSite site;

    public MttsClient(MttsSite site) {
        this.site = site;
    }

    @Override
    public void play(String text, TTSConfig config, TTSCallback callback) {
        if (text == null || text.isBlank()) {
            fail(callback, new RuntimeException("empty tts text"));
            return;
        }

        String language = config.language() == null ? "" : config.language().toLowerCase();
        String targetLang = language.startsWith("zh") ? "zh" : language.startsWith("en") ? "en" : "zh";
        // 情绪在 LLM 那一轮就定好了（ttsText 是无标签文本，此处已无从得知）
        String emotion = MaicaEmotionCache.lookup(text);

        JsonObject content = new JsonObject();
        content.addProperty("text", text);
        content.addProperty("emotion", emotion == null ? "" : emotion);
        content.addProperty("target_lang", targetLang);
        content.addProperty("persistence", false);
        content.addProperty("lossless", false);

        CompletableFuture<byte[]> future = MttsRelayHub.dispatch(
                callback.getMaid(), site.id(), Client.GSON.toJson(content));
        if (future == null) {
            fail(callback, new RuntimeException(
                    "owner client unavailable for mtts site " + site.id()
                            + " (offline, mod missing, or no matching local site)"));
            return;
        }

        future.whenComplete((audio, throwable) -> {
            if (throwable != null) {
                fail(callback, throwable);
            } else {
                callback.onSuccess(audio);
            }
        });
    }

    private void fail(TTSCallback callback, Throwable throwable) {
        MaidLLMLocal.LOGGER.debug("mtts failed: {}", throwable.toString());
        callback.onFailure(dummyRequest(), throwable, 0);
    }

    private HttpRequest dummyRequest() {
        try {
            return HttpRequest.newBuilder().uri(URI.create(site.url()))
                    .timeout(Duration.ofSeconds(1)).GET().build();
        } catch (Throwable t) {
            return HttpRequest.newBuilder().uri(URI.create("https://maicadev.monika.love/"))
                    .timeout(Duration.ofSeconds(1)).GET().build();
        }
    }
}
