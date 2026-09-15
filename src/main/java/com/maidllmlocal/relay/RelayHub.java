package com.maidllmlocal.relay;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.network.RelayRequestPackage;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 中继的中枢：谁可以中继（能力表）+ 在途请求（pending）+ 超时与回退。
 *
 * <h2>中继是"可选"的，而不是"默认"的</h2>
 * 只有客户端在登录时主动报了 Hello、且上报的本机站点 id 里含这次要用的 id，才会中继。玩家想拒绝中继，
 * 只需<b>不</b>在本机 llm.json 里加 {@code player_relay} 站点 —— 于是它既不出现在 Hello 里，也不会替服务器花钱。
 * 这样"同意"不需要额外的配置项或界面，而是天然由"你有没有配这个站点"表达。
 *
 * <h2>任何失败都回退到服务端站点</h2>
 * 主人离线、客户端没配、客户端报错、超时 —— 一律改为用服务端自己那份 {@code llm.json} 的 url/key 发。
 * 实现上就是去调 {@link #dispatch} 收到的那个 fallback（即原始的 {@code sendAsync}），
 * 服务器因此始终保有可用性，不装模组的玩家体验也完全不变。
 */
public final class RelayHub {
    /** 请求体上限。TLM 的消息本身受 TLM 自己的 token 预算约束，这里只是防呆。 */
    public static final int MAX_BODY_BYTES = 256 * 1024;
    /** 回包上限，防止畸形客户端塞爆服务端。 */
    public static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /**
     * 中继超时。必须明显短于 TLM 自己的 60s HTTP 超时，回退才来得及在 TLM 的超时之前完成，
     * 否则玩家看到的是"超时失败"而不是"悄悄用服务端配置答上了"。
     */
    private static final long RELAY_TIMEOUT_MS = 15_000L;

    /** 玩家 UUID → 其本机可中继的站点 id 集合。没有条目 = 不中继。 */
    private static final Map<UUID, Set<String>> CAPABLE = new ConcurrentHashMap<>();
    /** requestId → 在途请求。 */
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "maidllmlocal-relay-timer");
        thread.setDaemon(true);
        return thread;
    });

    private RelayHub() {
    }

    private static final class Pending {
        final CompletableFuture<HttpResponse<String>> future;
        final Supplier<CompletableFuture<HttpResponse<String>>> fallback;
        final HttpRequest request;
        final UUID owner;

        Pending(CompletableFuture<HttpResponse<String>> future,
                Supplier<CompletableFuture<HttpResponse<String>>> fallback,
                HttpRequest request, UUID owner) {
            this.future = future;
            this.fallback = fallback;
            this.request = request;
            this.owner = owner;
        }
    }

    /**
     * 该玩家的客户端是否声明了本机有这个站点。MAICA 中继（{@code MaicaRelayHub}）与本类的
     * player_relay 共用同一张能力表 —— Hello 协议是同一份，查的键都是站点 id。
     */
    public static boolean isCapable(UUID playerId, String siteId) {
        Set<String> sites = CAPABLE.get(playerId);
        return sites != null && sites.contains(siteId);
    }

    /** 客户端登录后自报家门。判定标准仅此一处，之后所有中继都查这张表。 */
    public static void onHello(ServerPlayer player, Set<String> siteIds) {
        if (siteIds.isEmpty()) {
            CAPABLE.remove(player.getUUID());
        } else {
            CAPABLE.put(player.getUUID(), Set.copyOf(siteIds));
        }
    }

    /** 玩家下线：清掉能力，并把他在途的中继全部回退掉 —— 否则女仆会卡在等待气泡上直到超时。 */
    public static void onPlayerGone(ServerPlayer player) {
        CAPABLE.remove(player.getUUID());
        UUID id = player.getUUID();
        PENDING.entrySet().removeIf(entry -> {
            if (!entry.getValue().owner.equals(id)) {
                return false;
            }
            failOver(entry.getValue());
            return true;
        });
    }

    /**
     * 尝试把这次请求改派给女仆主人的客户端。
     *
     * @return {@code null} 表示<b>不中继</b>，调用方应当原样执行服务端自己那次 {@code sendAsync}。
     *         非 null 表示已改派，调用方把这个 future 交给原 {@code whenComplete} 即可。
     */
    public static CompletableFuture<HttpResponse<String>> dispatch(
            EntityMaid maid, String siteId, String bodyJson, HttpRequest request,
            Supplier<CompletableFuture<HttpResponse<String>>> fallback) {

        if (bodyJson == null || bodyJson.isEmpty()) {
            return null;
        }
        if (!(maid.getOwner() instanceof ServerPlayer owner)) {
            return null; // 无主 / 主人不在线 -> 服务端自己发
        }
        Set<String> sites = CAPABLE.get(owner.getUUID());
        if (sites == null || !sites.contains(siteId)) {
            return null; // 客户端没装模组 / 没同意 / 本机没这个站点 -> 服务端自己发
        }

        int requestId = NEXT_ID.getAndIncrement();
        CompletableFuture<HttpResponse<String>> future = new CompletableFuture<>();
        Pending pending = new Pending(future, fallback, request, owner.getUUID());
        PENDING.put(requestId, pending);

        TIMER.schedule(() -> {
            Pending timedOut = PENDING.remove(requestId);
            if (timedOut != null) {
                MaidLLMLocal.LOGGER.debug("relay request {} timed out, falling back to server site", requestId);
                failOver(timedOut);
            }
        }, RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        PacketDistributor.sendToPlayer(owner, RelayRequestPackage.of(requestId, siteId, bodyJson));
        return future;
    }

    /**
     * 客户端回包。必须在服务器主线程上调用（见 {@code RelayResponsePackage.handle} 里的 {@code enqueueWork}）——
     * TLM 的 {@code handle()} 里 token 计数分支会直接碰 NeoForge DataAttachment，在 netty 线程上碰它会出事。
     */
    public static void onResponse(int requestId, int statusCode, String body, String error) {
        Pending pending = PENDING.remove(requestId);
        if (pending == null) {
            return; // 已被超时或玩家下线处理掉了
        }
        if (error != null && !error.isEmpty()) {
            MaidLLMLocal.LOGGER.debug("relay request {} failed on client ({}), falling back", requestId, error);
            failOver(pending);
            return;
        }
        pending.future.complete(new SyntheticResponse(pending.request, statusCode, body));
    }

    /** 改用服务端自己那份站点配置重发，结果灌回同一个 future。 */
    private static void failOver(Pending pending) {
        try {
            pending.fallback.get().whenComplete((response, throwable) -> {
                if (throwable != null) {
                    pending.future.completeExceptionally(throwable);
                } else {
                    pending.future.complete(response);
                }
            });
        } catch (Throwable t) {
            pending.future.completeExceptionally(t);
        }
    }
}