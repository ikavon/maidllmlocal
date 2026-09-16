package com.maidllmlocal.client;

import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.client.maica.ClientMaicaSessions;
import com.maidllmlocal.client.maica.MaicaWsSession;
import com.maidllmlocal.maica.MaicaRoundResult;
import com.maidllmlocal.maica.MaicaTrigger;
import com.maidllmlocal.network.MaicaChatRequestPackage;
import com.maidllmlocal.network.MaicaChatResponsePackage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端侧：收到服务端的 MAICA 对话请求后，用<b>本机的 MAICA 账号</b>跑完这一轮，把文本回传。
 *
 * <p>与 {@link ClientRelayHandler} 的差别：relay 是"代发一次无状态 HTTP"，
 * 这里是"在本机维护一条 WS 长连接上跑一轮有状态对话"——会话的握手/保活/续传
 * 全部在 {@link MaicaWsSession} 里，本类只管收发包的胶水。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientMaicaHandler {

    private ClientMaicaHandler() {
    }

    public static void onChatRequest(MaicaChatRequestPackage request) {
        MaicaWsSession session = ClientMaicaSessions.get(request.siteId());
        if (session == null) {
            // 本机没配同名 maica 站点（或缺 token）——服务端会转 onFailure 气泡
            reply(MaicaChatResponsePackage.failure(request.requestId(),
                    "no enabled local maica site with id=" + request.siteId() + " (or token missing)"));
            return;
        }
        // 一轮对话可能几十秒，绝不能堵 netty/主线程
        CompletableFutureCompat.run(() -> doQuery(session, request));
    }

    private static MaicaChatResponsePackage doQuery(MaicaWsSession session, MaicaChatRequestPackage request) {
        long start = System.currentTimeMillis();
        try {
            MaicaRoundResult result = session.query(request.messagesJson());
            MaidLLMLocal.LOGGER.info("maica chat ok: {} chars, {} trigger(s) in {}ms",
                    result.text().length(), result.triggers().size(), System.currentTimeMillis() - start);
            return MaicaChatResponsePackage.success(request.requestId(), result.text(),
                    MaicaTrigger.toJsonArray(result.triggers()));
        } catch (Throwable t) {
            MaidLLMLocal.LOGGER.warn("maica chat failed: {}", t.toString());
            String message = t.getMessage();
            return MaicaChatResponsePackage.failure(request.requestId(),
                    t.getClass().getSimpleName() + (message == null ? "" : ": " + message));
        }
    }

    private static void reply(MaicaChatResponsePackage response) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        // 发包要回客户端主线程
        minecraft.execute(() -> {
            try {
                PacketDistributor.sendToServer(response);
            } catch (Throwable t) {
                MaidLLMLocal.LOGGER.warn("failed to send maica response back", t);
            }
        });
    }

    /** 与 ClientRelayHandler 同一个后台执行器；独立成方法只为让 onChatRequest 读起来短。 */
    private static final class CompletableFutureCompat {
        static void run(java.util.function.Supplier<MaicaChatResponsePackage> task) {
            java.util.concurrent.CompletableFuture
                    .supplyAsync(task, Util.backgroundExecutor())
                    .thenAccept(ClientMaicaHandler::reply);
        }
    }
}
