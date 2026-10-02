package com.maidllmlocal.network;

import com.maidllmlocal.MaidLLMLocal;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端侧的分片重组器（仅主线程调用，见 {@link FragmentPackage#handle}）。
 *
 * <p>簿记按 (玩家 UUID, streamId) 隔离；玩家下线由 {@link MaidLLMLocal} 调 {@link #clear} 兜底，
 * 另有 30 秒陈旧驱逐防止客户端半途弃流占内存。所有上限都防呆：total 超限、
 * 单玩家并发流超限、未知 msgTypeId 一律丢弃并告警，绝不按对端给的数字直接分配大数组。
 */
final class FragmentAssembler {

    /** 半途流的最长存活时间：正常一条 4MB 流上百个片也是毫秒级到齐，30 秒纯属兜底。 */
    private static final long STALE_MS = 30_000L;
    /** 单玩家同时在途的分片流数量上限（正常使用是 1——中继请求天然串行）。 */
    private static final int MAX_STREAMS_PER_PLAYER = 8;

    private static final class Assembly {
        final int msgTypeId;
        final byte[][] parts;
        int received;
        long lastUpdateMs = System.currentTimeMillis();

        Assembly(int msgTypeId, int total) {
            this.msgTypeId = msgTypeId;
            this.parts = new byte[total][];
        }
    }

    private static final Map<UUID, Map<Integer, Assembly>> BY_PLAYER = new HashMap<>();

    private FragmentAssembler() {}

    static void feed(ServerPlayer player, FragmentPackage frag) {
        evictStale();

        if (frag.total() <= 0 || frag.total() > NetworkInit.MAX_TOTAL_FRAGMENTS
                || frag.seq() < 0 || frag.seq() >= frag.total()) {
            MaidLLMLocal.LOGGER.warn("fragment: 非法分片头 seq={} total={}，丢弃（玩家 {}）",
                    frag.seq(), frag.total(), player.getGameProfile().getName());
            return;
        }

        Map<Integer, Assembly> streams = BY_PLAYER.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        Assembly asm = streams.get(frag.streamId());
        if (asm == null) {
            if (frag.seq() != 0) {
                return; // 半路杀出的流（重启/驱逐后残片），无头可续，丢弃
            }
            if (streams.size() >= MAX_STREAMS_PER_PLAYER) {
                MaidLLMLocal.LOGGER.warn("fragment: 玩家 {} 并发流超限（{}），丢弃新流",
                        player.getGameProfile().getName(), streams.size());
                return;
            }
            asm = new Assembly(frag.msgTypeId(), frag.total());
            streams.put(frag.streamId(), asm);
        } else if (asm.msgTypeId != frag.msgTypeId() || asm.parts.length != frag.total()) {
            MaidLLMLocal.LOGGER.warn("fragment: 流 {} 头信息不一致，整流丢弃", frag.streamId());
            streams.remove(frag.streamId());
            return;
        }

        if (asm.parts[frag.seq()] == null) {
            asm.parts[frag.seq()] = frag.part();
            asm.received++;
        }
        asm.lastUpdateMs = System.currentTimeMillis();

        if (asm.received == asm.parts.length) {
            streams.remove(frag.streamId());
            int size = 0;
            for (byte[] p : asm.parts) {
                size += p.length;
            }
            byte[] full = new byte[size];
            int pos = 0;
            for (byte[] p : asm.parts) {
                System.arraycopy(p, 0, full, pos, p.length);
                pos += p.length;
            }
            NetworkInit.dispatchReassembled(asm.msgTypeId, full, player);
        }
    }

    /** 玩家下线（与 {@code RelayHub.onPlayerGone} 同点位调用）：清掉他在途的所有流。 */
    static void clear(UUID playerId) {
        BY_PLAYER.remove(playerId);
    }

    private static void evictStale() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Map<Integer, Assembly>>> players = BY_PLAYER.entrySet().iterator();
        while (players.hasNext()) {
            Map<Integer, Assembly> streams = players.next().getValue();
            streams.values().removeIf(a -> now - a.lastUpdateMs > STALE_MS);
            if (streams.isEmpty()) {
                players.remove();
            }
        }
    }
}
