package com.maidllmlocal.client;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.maica.MttsSite;
import com.maidllmlocal.network.MttsRequestPackage;
import com.maidllmlocal.network.MttsResponsePackage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端侧：收到服务端的 MTTS 请求后，用<b>本机的 MTTS 配置</b>调 {@code GET /generate}，
 * 音频字节回传。
 *
 * <p>格式嗅探（移植 maica4tlm 的 audio.py）：{@code lossless=false} 时官方节点直接回 mp3 → 透传；
 * 若回了 wav（自部署节点），本模组<b>不转码</b>（Java 侧没有现成的 mp3 编码器），
 * 直接报错——用官方节点不会走到这条。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientMttsHandler {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ClientMttsHandler() {
    }

    public static void onRequest(MttsRequestPackage request) {
        MttsSite site = lookupLocalSite(request.siteId());
        if (site == null) {
            reply(MttsResponsePackage.failure(request.requestId(),
                    "no enabled local mtts site with id=" + request.siteId() + " (or token missing)"));
            return;
        }
        CompletableFuture
                .supplyAsync(() -> generate(request.requestId(), site, request.contentJson()), Util.backgroundExecutor())
                .thenAccept(ClientMttsHandler::reply);
    }

    private static MttsSite lookupLocalSite(String siteId) {
        TTSSite site = AvailableSites.TTS_SITES.get(siteId);
        if (!(site instanceof MttsSite mtts)) {
            return null;
        }
        if (!mtts.enabled() || mtts.url().isEmpty()
                || mtts.secretKey() == null || mtts.secretKey().isEmpty()) {
            return null;
        }
        return mtts;
    }

    private static MttsResponsePackage generate(int requestId, MttsSite site, String contentJson) {
        long start = System.currentTimeMillis();
        try {
            String url = site.url().replaceAll("/+$", "") + "/generate?content="
                    + URLEncoder.encode(contentJson, StandardCharsets.UTF_8);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + site.secretKey())
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();

            String contentType = response.headers().firstValue("content-type").orElse("");
            String format = sniff(body);
            if (contentType.contains("json") || "unknown".equals(format)) {
                String detail = new String(body, 0, Math.min(body.length, 300), StandardCharsets.UTF_8);
                return MttsResponsePackage.failure(requestId,
                        "MTTS HTTP " + response.statusCode() + ": " + detail);
            }
            if ("wav".equals(format)) {
                // 自部署 MTTS 才回 wav；官方 lossless=false 直接回 mp3。Java 侧不转码。
                return MttsResponsePackage.failure(requestId,
                        "MTTS returned wav (self-hosted node?); only mp3 is supported, set lossless=false");
            }
            MaidLLMLocal.LOGGER.info("mtts ok: {} bytes {} in {}ms",
                    body.length, format, System.currentTimeMillis() - start);
            return MttsResponsePackage.success(requestId, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MttsResponsePackage.failure(requestId, "interrupted");
        } catch (Exception e) {
            MaidLLMLocal.LOGGER.warn("mtts request {} failed: {}", requestId, e.toString());
            return MttsResponsePackage.failure(requestId,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 嗅探音频容器：ID3 标签或 0xFFEx 帧同步 = mp3；RIFF....WAVE = wav。 */
    private static String sniff(byte[] data) {
        if (data == null || data.length < 4) {
            return "unknown";
        }
        if (data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            return "mp3";
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0) {
            return "mp3";
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') {
            return "wav";
        }
        if (data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') {
            return "ogg";
        }
        return "unknown";
    }

    private static void reply(MttsResponsePackage response) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        minecraft.execute(() -> {
            try {
                PacketDistributor.sendToServer(response);
            } catch (Throwable t) {
                MaidLLMLocal.LOGGER.warn("failed to send mtts response back", t);
            }
        });
    }
}
