package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.network.MaicaChatRequestPackage;
import com.maidllmlocal.relay.RelayHub;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MAICA 中继的中枢：在途对话 + 超时。
 *
 * <p>与 {@link RelayHub} 的两点关键差异：
 * <ul>
 *   <li><b>没有回退</b>。player_relay 失败了可以用服务端自己的 key 重发；MAICA 的 token 只在玩家
 *       本机（服务端同名站点的 secret_key 是空的），所以任何失败都只能 {@code onFailure}。
 *       这是设计意图，不是缺陷——服务端本来就不该碰玩家的 MAICA 账号。</li>
 *   <li><b>超时更长</b>（90s）。MAICA 一轮实测 1.2~5.4s，但生成可能更长，且没有像 TLM 60s HTTP
 *       超时那样的外部死线要抢。能力表（谁可以中继）与 {@link RelayHub} 共享同一份 Hello。</li>
 * </ul>
 */
public final class MaicaRelayHub {
    private static final long RELAY_TIMEOUT_MS = 90_000L;

    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "maidllmlocal-maica-timer");
        thread.setDaemon(true);
        return thread;
    });

    private MaicaRelayHub() {
    }

    private record Pending(CompletableFuture<MaicaRoundResult> future, UUID owner) {
    }

    /**
     * 尝试把这一轮对话改派给女仆主人的客户端。
     *
     * @param langOverride  本轮语言覆盖（{@code zh}/{@code en}），空串 = 用站点默认 target_lang
     * @return {@code null} 表示<b>不能中继</b>（无主 / 主人不在线 / 客户端没装模组或没配同名 maica 站点），
     *         调用方应直接 {@code onFailure}；非 null 的 future 完成值是客户端聚合好的
     *         回复原文 + 本轮 MTrigger 调用。
     */
    public static CompletableFuture<MaicaRoundResult> dispatch(EntityMaid maid, String siteId, String messagesJson,
                                                               String langOverride) {
        if (messagesJson == null || messagesJson.isEmpty()) {
            return null;
        }
        if (!(maid.getOwner() instanceof ServerPlayer owner)) {
            return null;
        }
        if (!RelayHub.isCapable(owner.getUUID(), siteId)) {
            return null;
        }

        int requestId = NEXT_ID.getAndIncrement();
        CompletableFuture<MaicaRoundResult> future = new CompletableFuture<>();
        PENDING.put(requestId, new Pending(future, owner.getUUID()));

        TIMER.schedule(() -> {
            Pending timedOut = PENDING.remove(requestId);
            if (timedOut != null) {
                MaidLLMLocal.LOGGER.debug("maica chat request {} timed out", requestId);
                timedOut.future.completeExceptionally(new RuntimeException("maica relay timeout"));
            }
        }, RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        PacketDistributor.sendToPlayer(owner, MaicaChatRequestPackage.of(requestId, siteId, messagesJson, langOverride));
        return future;
    }

    /** 客户端回包。必须在服务器主线程上调用（响应包的 handle 已 enqueueWork）。 */
    public static void onResponse(int requestId, boolean ok, String text, String triggersJson, String error) {
        Pending pending = PENDING.remove(requestId);
        if (pending == null) {
            return; // 已被超时或玩家下线处理掉了
        }
        if (ok && text != null && !text.isEmpty()) {
            // 触发器 JSON 解析失败按空表处理：回复文本还在，丢了触发器不至于废掉整轮
            pending.future.complete(new MaicaRoundResult(text, MaicaTrigger.fromJsonArray(triggersJson)));
        } else {
            pending.future.completeExceptionally(
                    new RuntimeException("maica client: " + (error == null || error.isEmpty() ? "empty reply" : error)));
        }
    }

    /** 玩家下线：清掉他在途的对话，否则女仆卡在等待气泡上直到超时。 */
    public static void onPlayerGone(ServerPlayer player) {
        UUID id = player.getUUID();
        PENDING.entrySet().removeIf(entry -> {
            if (!entry.getValue().owner().equals(id)) {
                return false;
            }
            entry.getValue().future().completeExceptionally(new RuntimeException("owner disconnected"));
            return true;
        });
    }
}
