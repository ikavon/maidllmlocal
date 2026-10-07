package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * 女仆的长期记忆：存在女仆实体 NBT 里（跟着存档走），由 MAICA 的
 * {@code write_memory} 触发器写入，<b>托管模式</b>（{@code chat_session ≥ 0}）下
 * 每轮随场景一起注入最后一条 user 消息（见 {@link MaicaClient#withSceneWrap}）。
 * 好感度不在这里报——见 {@link #promptBlock}。
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
    /** 送后端的记忆总量上限：temp 注入要每轮嵌入/重排，不该无限长。 */
    private static final int MAX_ADDITIONS_BYTES = 1024;
    /**
     * 单轮 temp 注入的条数上限。后端硬限制见 {@link MaicaCarrier#MAX_ADDITIONS}；
     * 这里留 1 个位置给静态世界事实（{@link MaicaScene#STATIC_FACTS}）。
     */
    private static final int MAX_ADDITIONS = MaicaCarrier.MAX_ADDITIONS - 1;

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
     * 托管模式每轮随 query 送后端的「知识」条目（temp savefile additions）——
     * 最新优先，≤1KB / ≤{@value #MAX_ADDITIONS} 条，按句去重。
     *
     * <p><b>为什么不进 prompt</b>（2026-10-07 用户拍板 C 方案）：进 prompt 就得每轮重发，
     * 而托管模式下我们发的 user 文本会进后端历史——N 轮之后窗口里就躺着 N 份同样的记忆，
     * 把真正的对话挤出去。改走 temp savefile 后窗口零占用，检索交给后端的 MF/RAG
     * （代价：每轮只重排出 top-2 条进 known_info，不保证每条都浮现——
     * 2026-10-05 的教训「检索不到 ≠ 记忆不存在」）。
     *
     * <p><b>temp 不入库</b>（后端源码实锤）：{@code content_temp} 是会话对象上的内存 dict，
     * 每轮被 query 里那份覆盖，检索时与 perm 合并进候选池当场重排——所以重发不会
     * 在向量库/数据库里累积副本。
     *
     * <p><b>为什么不报当前好感度</b>（2026-10-07 用户定调）：关系状态该由「变化」体现——
     * MTrigger 每轮的好感度增减本身就是事件，她感知得到；报一个数值只是固定内容。
     */
    public static List<String> additions(EntityMaid maid) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String memory : pickWithinBudget(readAll(maid))) {
            String sentence = asStatement(memory);
            if (seen.add(sentence)) {
                out.add(sentence);
            }
        }
        return out;
    }

    /** 记忆条目离开前端前的收尾：补半角句号（known_info 语域一句一断）。 */
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
        int budget = MAX_ADDITIONS_BYTES;
        for (int i = memories.size() - 1; i >= 0 && picked.size() < MAX_ADDITIONS; i--) {
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
