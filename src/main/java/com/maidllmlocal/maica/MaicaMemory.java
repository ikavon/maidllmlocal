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
 * 女仆的长期记忆与关系状态：存在女仆实体 NBT 里（跟着存档走），
 * 由 MAICA 的 {@code write_memory} 触发器写入，每轮对话开头把最新若干条
 * 注入 system 消息——session=-1 模式下后端不托管记忆，这是本机托管的等价物
 * （对应 MAS 非 -1 模式的后端记忆）。
 *
 * <p>记忆的形式就是<b>一句自然语言</b>：它的读者是 LLM 自己，所以句子就是对的格式。
 */
public final class MaicaMemory {

    private static final String ROOT_TAG = "maidllmlocal";
    private static final String MEMORIES_TAG = "memories";

    /** 单条上限（MAICA 协议对未注明双语的字符串上限就是 256 字符）。 */
    private static final int MAX_ENTRY_CHARS = 256;
    /** 总条数上限：超出丢最旧。 */
    private static final int MAX_ENTRIES = 50;
    /** 注入 prompt 的记忆总量上限：session=-1 的 16KB 预算里划给记忆的份额。 */
    private static final int MAX_PROMPT_BYTES = 2 * 1024;

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
     * 拼本轮注入用的上下文块：长期记忆（最新优先，总量 ≤2KB）+ 当前好感度状态。
     * 好感度行永远带上——模型知道关系状态，回复才会随关系变化。
     */
    public static String promptBlock(EntityMaid maid) {
        StringBuilder block = new StringBuilder();
        List<String> memories = readAll(maid);
        if (!memories.isEmpty()) {
            block.append("【长期记忆】（你记得的关于主人和你们之间的事）\n");
            // 从最新往最旧装，装不下就跳过的那条不再回头——新记忆更相关
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
            for (String memory : picked) {
                block.append("- ").append(memory).append('\n');
            }
        }
        FavorabilityManager fm = maid.getFavorabilityManager();
        block.append("【当前好感度】等级 ").append(fm.getLevel())
                .append("（").append(maid.getFavorability()).append(" 点）");
        return block.toString();
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
