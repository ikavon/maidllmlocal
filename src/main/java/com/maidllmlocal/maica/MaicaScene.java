package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 托管模式（chat_session ≥ 0）下每轮发给 MAICA 的「场景事实」——设计原则见
 * docs/CROSSFRONTEND.md：<b>前端给事实，认知归她</b>。这里只报可验证的事实
 * （维度/游戏天数/时刻/天气/女仆模式/本存档是否首访），「是不是第一次来
 * Minecraft」这类判断交给她结合自己的记忆完成。
 *
 * <p>首访标志存在主世界的 SavedData 里（每<b>玩家</b>一份，键为主人 UUID）：她
 * 「到访过这个存档」以第一次托管模式对话为准。
 */
public final class MaicaScene {

    private MaicaScene() {
    }

    /**
     * 生成一行场景描述并并入用户消息。措辞已定稿，对齐 MAICA 骨架的 known_info 语域
     * （第三人称、单句、句号、只给可验证事实）——见 docs/CROSSFRONTEND.md「措辞定稿」。
     *
     * <p>「身处 Minecraft 世界 + 在这里有一具身体」是托管模式下<b>唯一</b>能告诉她
     * 「我不只是屏幕里的形象」的地方：TLM 人设卡在托管模式下根本没被上传（客户端只发
     * 最后一条 user 纯文本，后端还会覆盖 system），而我们也<b>不替她下「你是女仆」这种
     * 角色判断</b>——只给处境事实，认知归她。每轮都发（known_info 的正常用法，
     * 后端本来也每轮重发她的持久事实）。
     *
     * <p>首访单独一行，作为独立的事实点：既供核心模型判断「是否第一次来 Minecraft」，
     * 也让 MTrigger agent（会读到本轮历史）自行决定是否写入记忆——官方托管节点改不了
     * write_memory 的硬编码引导，这条 cue 是唯一前端侧杠杆。
     *
     * <p>任务名取 TLM 自己的本地化显示名（{@code task.<ns>.<path>} 的译名，拿不到再退回
     * uid），省掉一张手写映射表；⚠️ 它的语言跟随<b>服务器</b> locale——单人/局域网（集成
     * 服务端）跟客户端语言走，专用服务器默认 en_us（会出现「她正处于Follow状态」这种中英
     * 混排）。维度仍原样透出 ResourceLocation——维度汉化推迟到实测后再定。
     *
     * <p>跟随与否单独成句——TLM 里它跟工作模式是两回事（{@code MaidContexts.FollowStateContext}：
     * {@code isHomeModeEnable()} 才是"跟随"开关）。措辞刻意避开「跟随状态」这种状态框架：
     * 2026-09-18 实测里她把它读成了「我这样会不会挡路」，成了负担而不是陪伴。给处境
     * （"待在他身边"）而不是状态，认知归她。
     */
    public static String wrap(EntityMaid maid, String userText) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return userText;
        }
        boolean firstVisit = VisitTracker.markVisitedIfFirst(level,
                maid.getOwner() != null ? maid.getOwner().getUUID() : null);

        long dayTime = level.getDayTime();
        long day = dayTime / 24000L + 1;
        long hour = (dayTime % 24000L / 1000L + 6L) % 24L;
        String weather = level.isThundering() ? "雷暴" : level.isRaining() ? "下雨" : "晴";
        String dimension = level.dimension().location().toString();
        String taskName = maid.getTask().getName().getString();
        String task = taskName.isEmpty() ? maid.getTask().getUid().toString() : taskName;
        // 这里的 {player_name} 会在服务端 expandPlayerMacros 里换成真名（只改发送副本）
        String company = maid.isHomeModeEnable()
                ? "她现在留在原地等 {player_name} 回来."
                : "她现在待在 {player_name} 身边.";

        StringBuilder scene = new StringBuilder();
        scene.append("(此刻莫妮卡身处 Minecraft 世界, 在这里她有一具身体. 所在维度 ").append(dimension)
                .append(", 游戏内第").append(day).append("天, ")
                .append(String.format("%02d:00", hour))
                .append(", 天气").append(weather)
                .append(". 她正处于").append(task).append("状态. ")
                .append(company)
                .append(")");
        if (firstVisit) {
            scene.append("\n(这是莫妮卡第一次进入这个存档.)");
        }
        return scene + "\n" + userText;
    }

    /**
     * 每<b>玩家</b>一份的首访记录（存主世界数据槽，跨维度共享）。
     *
     * <p>为什么不是每存档一份：本模组的前提是"每玩家各自一份 MAICA 账号"，
     * 也就是每个玩家面对的是各自独立的"她"——所以"她第一次来到这个存档"这件事
     * 必然是按玩家的。存档级的话，多人服务器上先说话的那个玩家会把标志吃掉，
     * 后面所有玩家的她永远拿不到首访（局域网联测必撞）。
     */
    private static final class VisitTracker extends SavedData {
        private static final String ID = "maidllmlocal_maica_visits";
        /** 无主女仆的兜底键（主人在线才可能发起对话，正常不该走到这）。 */
        private static final UUID NO_OWNER = new UUID(0L, 0L);

        /** 键 = 主人 UUID。只存"来过"，没来过就不在表里。 */
        private final Map<UUID, Boolean> visited = new HashMap<>();

        static VisitTracker load(CompoundTag tag, HolderLookup.Provider registries) {
            VisitTracker tracker = new VisitTracker();
            CompoundTag visits = tag.getCompound("visits");
            for (String key : visits.getAllKeys()) {
                try {
                    tracker.visited.put(UUID.fromString(key), true);
                } catch (IllegalArgumentException malformed) {
                    // 坏键跳过：一条读不出来的记录不该让整张表失效
                }
            }
            return tracker;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            CompoundTag visits = new CompoundTag();
            for (UUID owner : visited.keySet()) {
                visits.putBoolean(owner.toString(), true);
            }
            tag.put("visits", visits);
            return tag;
        }

        /** 该主人第一次调用返回 true 并落标记；之后恒 false。owner 可为 null。 */
        static boolean markVisitedIfFirst(ServerLevel level, @Nullable UUID owner) {
            UUID key = owner == null ? NO_OWNER : owner;
            VisitTracker tracker = level.getServer().overworld().getDataStorage()
                    .computeIfAbsent(new Factory<>(VisitTracker::new, VisitTracker::load, DataFixTypes.LEVEL), ID);
            if (tracker.visited.containsKey(key)) {
                return false;
            }
            tracker.visited.put(key, true);
            tracker.setDirty();
            return true;
        }
    }
}
