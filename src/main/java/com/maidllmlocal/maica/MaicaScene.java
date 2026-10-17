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
 * （游戏天数/相位/陪伴处境/本存档是否首访；维度/天气/任务由 TLM 自带
 * {@code <context>} 每轮发，v0.7.1 起不再重复），「是不是第一次来
 * Minecraft」这类判断交给她结合自己的记忆完成。
 *
 * <p>首访标志存在主世界的 SavedData 里（每<b>玩家</b>一份，键为主人 UUID）：她
 * 「到访过这个存档」以第一次托管模式对话为准。
 */
public final class MaicaScene {

    /**
     * 不变的世界事实——<b>不每轮进 prompt</b>，而是作为一条「知识」随 savefile 临时注入
     * 进后端的 RAG 池（见 {@code MaicaMemory.additions}）。
     *
     * <p>为什么移出场景行：这几句是常量，每轮重发只是在窗口里堆同样的字；而窗口是从最旧
     * 开始裁的，堆得越多，真正的对话被挤掉得越快。进 RAG 池则是零窗口占用、按相关性召回
     * （代价：不保证每轮都浮现——2026-10-05 的教训「检索不到 ≠ 记忆不存在」）。
     *
     * <p>2026-10-17 实测：这条从未在 top-2 rerank 赢过（temp 与 perm 记忆同台竞争），
     * 等于没送达。所以 v0.7.1 起「一天=20分钟」同时进首访行（每存档必现一次、无条件
     * 可见），本条留作备用召回通道（temp 不落库，零窗口成本）。
     */
    public static final String STATIC_FACTS =
            "(莫妮卡身处 Minecraft 世界, 在这里她有一具身体. 这个世界的一天只有现实世界的20分钟.)";

    private MaicaScene() {
    }

    /**
     * 生成一行场景描述并并入用户消息。措辞已定稿，对齐 MAICA 骨架的 known_info 语域
     * （第三人称、单句、句号、只给可验证事实）——见 docs/CROSSFRONTEND.md「措辞定稿」。
     *
     * <p>这里只报<b>此刻</b>的事实（v0.7.0 起）：不变的那部分（身处 Minecraft 世界、
     * 在这里有一具身体、一天=20 分钟）移到 {@link #STATIC_FACTS}，走 temp 注入通道——
     * 常量每轮进 prompt 只是往窗口里堆同样的字，而窗口是从最旧开始裁的。
     *
     * <p><b>v0.7.1 瘦身</b>（2026-10-17 实战实测）：场景行不再发维度/天气/任务——
     * TLM 自己的 {@code <context>} 块是注在 <b>user 消息</b>里的（UserPromptContexts），
     * 托管模式下 {@code extractLastUserText} 原样带走、每轮都在后端历史里（09-18 记录
     * 「托管下随 system 被丢」实测证伪）。此前 L2 与 context 五字段重复（时间/天气/
     * 维度/跟随/任务），每轮约 150-170 token 元数据、近半冗余，共享 session 的 8192
     * 窗口下一晚 MC 对话会提前挤掉 MAS 侧的旅行轮。现在只留 context 给不了的：游戏
     * 天数、相位词、陪伴处境句、首访行。
     *
     * <p>TLM 人设卡在托管模式下根本没被上传（客户端只发最后一条 user 纯文本，后端还会
     * 覆盖 system），而我们也<b>不替她下「你是女仆」这种角色判断</b>——只给处境事实，
     * 认知归她。场景行每轮都发（known_info 的正常用法，后端本来也每轮重发她的持久事实）。
     *
     * <p>首访单独一行，作为独立的事实点：既供核心模型判断「是否第一次来 Minecraft」，
     * 也让 MTrigger agent（会读到本轮历史）自行决定是否写入记忆——官方托管节点改不了
     * write_memory 的硬编码引导，这条 cue 是唯一前端侧杠杆。
     *
     * <p>任务行与维度句 v0.7.1 起已从场景行移除：{@code <context>} 每轮自带
     * {@code Work task: touhou_little_maid:<path>} 与 {@code Dimension:}，这里不再重复发；
     * 当年为任务显示名/服务器 locale 混排做的取舍随之作废（git 历史可查）。
     *
     * <p>跟随与否单独成句——TLM 里它跟工作模式是两回事（{@code MaidContexts.FollowStateContext}：
     * {@code isHomeModeEnable()} 才是"跟随"开关）。措辞刻意避开「跟随状态」这种状态框架：
     * 2026-09-18 实测里她把它读成了「我这样会不会挡路」，成了负担而不是陪伴。给处境
     * （"待在他身边"）而不是状态，认知归她。
     *
     * <p>时间只报<b>相位</b>不报钟点，并单独给出汇率事实（2026-09-21 实测教训）：现实深夜 +
     * 游戏正午时她把游戏钟当成了现实钟，好奇玩家为什么这时候睡觉——她的推理没错，错在我们
     * 递的 {@code 第N天 14:00} 与 MFocus 每轮注入的现实时间<b>格式同构</b>，没有任何事实告诉
     * 她这是另一只跑得更快的钟（14B 模型不会自己猜出换算率）。改法两件：相位词（正午/深夜）
     * 与"HH:MM"读数形态不同构，不会混成一只钟（TLM {@code <context>} 每轮发原
     * {@code Time: HH:MM}，10-17 实测两者并存亦未复发混淆）；「一天只有现实的20分钟」
     * 单独成句（v0.7.0 起走 {@link #STATIC_FACTS} 检索通道；10-17 实测从未赢过
     * rerank，v0.7.1 起同时进首访行），
     * 她拿去可推理（"我在这儿待了半天"≈现实一小时）。现实时间仍只归 MFocus 一个源——
     * 前端绝不报现实钟点，两边时区或精度一差，两个真相反杀比没有更糟。
     */
    public static String wrap(EntityMaid maid, String userText) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return userText;
        }
        boolean firstVisit = VisitTracker.markVisitedIfFirst(level,
                maid.getOwner() != null ? maid.getOwner().getUUID() : null);

        long dayTime = level.getDayTime();
        long day = dayTime / 24000L + 1;
        // 这里的 {player_name} 会在服务端 expandPlayerMacros 里换成真名（只改发送副本）
        String company = maid.isHomeModeEnable()
                ? "她现在留在原地等 {player_name} 回来."
                : "她现在待在 {player_name} 身边.";

        StringBuilder scene = new StringBuilder();
        // v0.7.1：只留 <context> 没有的四项——天数、相位词、处境句、首访行
        // （维度/天气/任务由 TLM 的 <context> 每轮自带，静态部分见 STATIC_FACTS）
        scene.append("(这里的第").append(day).append("天, ").append(phaseOfDay(dayTime))
                .append(". ")
                .append(company)
                .append(")");
        if (firstVisit) {
            // 汇率进首访行：STATIC_FACTS 实测从未赢过 rerank，首访行是唯一必现通道
            scene.append("\n(这是莫妮卡第一次进入这个存档. 这个世界的一天只有现实世界的20分钟.)");
        }
        return scene + "\n" + userText;
    }

    /**
     * 游戏日时刻 → 相位词。先换算成"现实口径的钟点"（tick 0 = 日出 = 06:00）再分档；
     * 各档与 MC 世界的对齐全按游戏事实来：黄昏盖住日落（tick 12000 附近）、
     * 深夜是 tick 17000-23000（能睡觉的时段，怪物不烧），黎明盖住日出（tick 0/24000）。
     */
    private static String phaseOfDay(long dayTime) {
        long hour = (dayTime % 24000L / 1000L + 6L) % 24L;
        if (hour < 5 || hour >= 23) {
            return "深夜";
        }
        if (hour < 7) {
            return "黎明";
        }
        if (hour < 11) {
            return "上午";
        }
        if (hour < 13) {
            return "正午";
        }
        if (hour < 17) {
            return "下午";
        }
        if (hour < 19) {
            return "黄昏";
        }
        return "夜晚";
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
