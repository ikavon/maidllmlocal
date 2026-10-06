package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.entity.favorability.FavorabilityManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 女仆的长期记忆与关系状态：存在女仆实体 NBT 里（跟着存档走），由 MAICA 的
 * {@code write_memory} 触发器写入，<b>托管模式</b>（{@code chat_session ≥ 0}）下
 * 每轮随场景一起注入最后一条 user 消息（见 {@link MaicaClient#withSceneWrap}）。
 *
 * <p><b>为什么 session=-1 不再注入</b>（2026-10-07 定案）：-1 是前端自持上下文的
 * 纯对话模式，而 TLM 的 system 人设每轮重建、历史队列里只有问答轮次——注入块没有
 * 任何持久载体，所以「只在首轮注入」从第二轮起就等于失忆，只剩「每轮重发」一条路。
 * 首轮注入既然不成立，每轮重发的那份固定开销就是要砍掉的东西（1-2KB / 轮）。
 * 官方对 -1 的定位也是「作用相对受限, 不建议用于一般情况」，且 2026-10-06 的后端
 * 改动把 -1 的 prompt 明确划归前端（{@code savefile_loadable} 对 -1 直接关闭）。
 * 结论：<b>-1 = 纯对话模式（不写记忆、不注入记忆），长期记忆是托管模式的能力</b>。
 *
 * <p>记忆的形式就是<b>一句自然语言</b>：它的读者是 LLM 自己，所以句子就是对的格式。
 * 注入语域对齐 MAICA 骨架的 known_info（第三人称、半角句号、只给可验证事实）——
 * 2026-10-07 的 API 文档「重要的引言」强调核心模型是脆弱的微调 RP 模型、对劣质
 * 上下文敏感，纯自然语言的注入比「【长期记忆】- 列表」式结构化提示更安全。
 */
public final class MaicaMemory {

    private static final String ROOT_TAG = "maidllmlocal";
    private static final String MEMORIES_TAG = "memories";

    /** 单条上限（MAICA 协议对未注明双语的字符串上限就是 256 字符）。 */
    private static final int MAX_ENTRY_CHARS = 256;
    /** 总条数上限：超出丢最旧。 */
    private static final int MAX_ENTRIES = 50;
    /** 注入 prompt 的记忆总量上限：每轮 user 消息预算里划给记忆的份额。 */
    private static final int MAX_PROMPT_BYTES = 1024;

    private MaicaMemory() {
    }

    /** 追加一条记忆（write_memory 触发器的落点）。超出上限时丢最旧。 */
    public static void append(EntityMaid maid, String memory) {
        String trimmed = memory.length() > MAX_ENTRY_CHARS
                ? memory.substring(0, MAX_ENTRY_CHARS) : memory;
        CompoundTag data = maid.getPersistentData();
        CompoundTag root = data.contains(ROOT_TAG, Tag.TAG_COMPOUND)
                ? data.getCompound(ROOT_TAG) : new CompoundTag();
        ListTag list = root.contains(MEMORIES_TAG, Tag.TAG_LIST)
                ? root.getList(MEMORIES_TAG, Tag.TAG_STRING) : new ListTag();
        list.add(StringTag.valueOf(trimmed));
        while (list.size() > MAX_ENTRIES) {
            list.remove(0);
        }
        root.put(MEMORIES_TAG, list);
        data.put(ROOT_TAG, root);
    }

    /**
     * 拼本轮注入用的上下文块：长期记忆（最新优先，总量 ≤1KB）+ 当前关系状态。
     *
     * <p>关系状态行保留（托管模式）：MAICA 原生做法里 savefile 的 {@code mas_affection}
     * 也是进 known_info 的——MC 侧的好感度是 TLM 自己的表，后端完全不知道，不报给她
     * 就无从判断关系阶段。这一行每轮都在变（MTrigger 会改它），
     * 不属于"重复注入的固定内容"。
     */
    public static String promptBlock(EntityMaid maid) {
        StringBuilder block = new StringBuilder();
        List<String> picked = pickWithinBudget(readAll(maid));
        if (!picked.isEmpty()) {
            block.append("(莫妮卡记得这些事:");
            for (String memory : picked) {
                block.append(' ').append(asStatement(memory));
            }
            block.append(")\n");
        }
        FavorabilityManager fm = maid.getFavorabilityManager();
        block.append("(莫妮卡与 {player_name} 的关系等级是 ").append(fm.getLevel())
                .append(", 好感度 ").append(maid.getFavorability()).append(" 点.)");
        return block.toString();
    }

    /** 记忆条目落进 prompt 前的收尾：补半角句号（known_info 语域一句一断）。 */
    private static String asStatement(String memory) {
        String s = memory.strip();
        return s.endsWith(".") || s.endsWith("。") ? s : s + ".";
    }

    /**
     * 从最新往最旧取，装不下就跳过那条（新记忆更相关，不回头）；返回最旧在前，
     * 保证阅读顺序是从过去到现在。
     */
    private static List<String> pickWithinBudget(List<String> memories) {
        LinkedList<String> picked = new LinkedList<>();
        int budget = MAX_PROMPT_BYTES;
        for (int i = memories.size() - 1; i >= 0; i--) {
            String memory = memories.get(i);
            int cost = memory.getBytes(StandardCharsets.UTF_8).length + 4;
            if (cost > budget) {
                continue;
            }
            budget -= cost;
            picked.addFirst(memory);
        }
        return picked;
    }

    /** 全部记忆，最旧在前。 */
    private static List<String> readAll(EntityMaid maid) {
        CompoundTag data = maid.getPersistentData();
        if (!data.contains(ROOT_TAG, Tag.TAG_COMPOUND)) {
            return List.of();
        }
        ListTag list = data.getCompound(ROOT_TAG).getList(MEMORIES_TAG, Tag.TAG_STRING);
        List<String> memories = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            memories.add(list.getString(i));
        }
        return memories;
    }
}
