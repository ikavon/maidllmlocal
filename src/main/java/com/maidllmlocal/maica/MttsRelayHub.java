package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.network.MttsRequestPackage;
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
 * MTTS 中继的中枢。语义与 {@link MaicaRelayHub} 完全一致（无回退、能力表共享、下线清在途），
 * 只是完成值从对话文本换成了音频字节。MTTS 实测一轮约 1~2s，超时给 60s 已非常宽裕。
 */
public final class MttsRelayHub {
    private static final long RELAY_TIMEOUT_MS = 60_000L;

    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "maidllmlocal-mtts-timer");
        thread.setDaemon(true);
        return thread;
    });

    private MttsRelayHub() {
    }

    private record Pending(CompletableFuture<byte[]> future, UUID owner) {
    }

    /**
     * @return {@code null} 表示不能中继（无主/离线/客户端没配同名 mtts 站点），调用方直接 onFailure；
     *         非 null 的 future 完成值是音频字节。
     */
    public static CompletableFuture<byte[]> dispatch(EntityMaid maid, String siteId, String contentJson) {
        if (!(maid.getOwner() instanceof ServerPlayer owner)) {
            return null;
        }
        if (!RelayHub.isCapable(owner.getUUID(), siteId)) {
            return null;
        }

        int requestId = NEXT_ID.getAndIncrement();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        PENDING.put(requestId, new Pending(future, owner.getUUID()));

        TIMER.schedule(() -> {
            Pending timedOut = PENDING.remove(requestId);
            if (timedOut != null) {
                MaidLLMLocal.LOGGER.debug("mtts request {} timed out", requestId);
                timedOut.future.completeExceptionally(new RuntimeException("mtts relay timeout"));
            }
        }, RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        PacketDistributor.sendToPlayer(owner, MttsRequestPackage.of(requestId, siteId, contentJson));
        return future;
    }

    /** 客户端回包。必须在服务器主线程上调用。 */
    public static void onResponse(int requestId, boolean ok, byte[] audio, String error) {
        Pending pending = PENDING.remove(requestId);
        if (pending == null) {
            return;
        }
        if (ok && audio != null && audio.length > 0) {
            pending.future.complete(audio);
        } else {
            pending.future.completeExceptionally(
                    new RuntimeException("mtts client: " + (error == null || error.isEmpty() ? "empty audio" : error)));
        }
    }

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
