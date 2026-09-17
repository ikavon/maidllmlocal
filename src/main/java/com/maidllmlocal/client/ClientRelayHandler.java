package com.maidllmlocal.client;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.network.RelayHelloPackage;
import com.maidllmlocal.network.RelayRequestPackage;
import com.maidllmlocal.network.RelayResponsePackage;
import com.maidllmlocal.relay.RelaySite;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端侧：收到服务端的中继请求后，<b>用本机自己的配置</b>把这次请求发出去，再把结果回传。
 *
 * <p>这是整个方案的关键一笔 —— 请求从此由玩家自己的机器发出，用的是玩家自己的 key 与供应方，
 * 服务端既不需要出网、也拿不到玩家的凭据。服务器上"所有人都被迫用同一个供应方"的老问题因此消失。
 *
 * <p>对应地，开发参考了同作者的 {@code maidttslocal}（把 TTS 通道同样搬到客户端执行），
 * 两者叠加即是"每玩家完整的本机 LLM + TTS"。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientRelayHandler {
    /** 与 TLM 自己的 LLM 超时一致；中继侧的服务端还有 15s 的更短超时用于回退。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ClientRelayHandler() {
    }

    /**
     * 登录后自报家门。
     *
     * <p>只上报本机<b>已启用、配了 url、且 api_type 是 player_relay 或 maica</b> 的站点 id ——
     * 这一条同时充当了"同意机制"：不想为某个服务器花自己额度的玩家，只要不在本机 llm.json 里
     * 配对应站点即可，无需任何额外开关或界面。两种 api_type 共用一张能力表，
     * 服务端按本次要用的站点 id 查表，互不干扰。
     */
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        resync();
    }

    /**
     * （重新）上报本机能力。登录时自动调用；自动登录换到 token、把本机站点 enable 之后
     * 也要调一次，否则服务端还以为这台客户端什么都能不了。
     */
    public static void resync() {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        Set<String> ids = localRelaySiteIds();
        PacketDistributor.sendToServer(RelayHelloPackage.of(ids));
        MaidLLMLocal.LOGGER.info("announced {} relay-capable local site(s): {}", ids.size(), ids);
    }

    private static Set<String> localRelaySiteIds() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            for (LLMSite site : AvailableSites.LLM_SITES.values()) {
                boolean relayable = RelaySite.API_TYPE.equals(site.getApiType())
                        || com.maidllmlocal.maica.MaicaSite.API_TYPE.equals(site.getApiType());
                if (site.enabled() && relayable && !site.url().isEmpty()) {
                    ids.add(site.id());
                }
            }
            // TTS 侧：本机已启用的 mtts 站点同样上报（共用同一张能力表，键是站点 id）
            for (var site : AvailableSites.TTS_SITES.values()) {
                if (site.enabled() && com.maidllmlocal.maica.MttsSite.API_TYPE.equals(site.getApiType())
                        && !site.url().isEmpty()) {
                    ids.add(site.id());
                }
            }
        } catch (Throwable t) {
            MaidLLMLocal.LOGGER.warn("failed to enumerate local relay sites", t);
        }
        return ids;
    }

    public static void onRequest(RelayRequestPackage request) {
        LLMOpenAISite site = lookupLocalSite(request.siteId());
        if (site == null) {
            // 服务端指定的站点在本机不存在 —— 报错让它回退到自己的配置，而不是我们瞎猜一个地址。
            reply(RelayResponsePackage.failure(request.requestId(),
                    "no enabled local relay site with id=" + request.siteId()));
            return;
        }
        String body = request.body();
        CompletableFuture
                .supplyAsync(() -> post(request.requestId(), site, body), Util.backgroundExecutor())
                .thenAccept(ClientRelayHandler::reply);
    }

    /** 只在本机配置里按 id 找，且必须是 player_relay 类型 —— 服务端给的信息仅一个 id 而已。 */
    private static LLMOpenAISite lookupLocalSite(String siteId) {
        // secretKey() 在 LLMOpenAISite 上而不在 LLMSite 接口上，故这里收敛到具体类型
        if (!(AvailableSites.LLM_SITES.get(siteId) instanceof LLMOpenAISite site)) {
            return null;
        }
        if (!site.enabled() || !RelaySite.API_TYPE.equals(site.getApiType()) || site.url().isEmpty()) {
            return null;
        }
        return site;
    }

    private static RelayResponsePackage post(int requestId, LLMOpenAISite site, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(site.url()))
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            String secretKey = site.secretKey();
            if (secretKey != null && !secretKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + secretKey);
            }
            site.headers().forEach(builder::header);

            HttpResponse<String> response = HTTP.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 真的有应答（哪怕是 4xx/5xx）就原样回传，不回退 —— 否则会变成"我的端点报错，
            // 却悄悄拿服务端的 key 又试了一遍"。
            return new RelayResponsePackage(requestId, response.statusCode(),
                    response.body().getBytes(StandardCharsets.UTF_8), "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RelayResponsePackage.failure(requestId, "interrupted");
        } catch (Exception e) {
            // 没能发出去（本机端点没起、连不上等）—— 让服务端回退。
            MaidLLMLocal.LOGGER.warn("relay request {} failed before getting a response: {}", requestId, e.toString());
            return RelayResponsePackage.failure(requestId, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void reply(RelayResponsePackage response) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        // 发包要回客户端主线程
        minecraft.execute(() -> {
            try {
                PacketDistributor.sendToServer(response);
            } catch (Throwable t) {
                MaidLLMLocal.LOGGER.warn("failed to send relay response back", t);
            }
        });
    }
}