package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maidllmlocal.MaidLLMLocal;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/**
 * MTrigger 调用的服务端执行器：把 MAICA 的决策落到女仆实体上。
 *
 * <p>为什么不走 TLM 的 {@code onFunctionCall} 工具循环：MTrigger 的语义是「随回复一并
 * 做出的善后决策」，TLM 的工具循环会把工具结果再喂回 LLM 产生第二轮——对 MAICA 是
 * 双倍延迟双倍额度，且 MAICA 不认识 tool 角色的消息。这里直接作用实体，文本走原有
 * {@code onSuccess} 路径，两者互不阻塞。
 *
 * <p>每条触发器独立 try/catch：坏触发器记 warn 跳过，绝不杀掉整轮回复。
 */
public final class MaicaTriggers {

    /** 好感度单轮变化钳制：防一轮暴涨暴跌，也防后端给出离谱数值。 */
    static final int MAX_AFFECTION_DELTA = 5;

    private MaicaTriggers() {
    }

    public static void apply(EntityMaid maid, List<MaicaTrigger> triggers, String playerName) {
        for (MaicaTrigger trigger : triggers) {
            try {
                applyOne(maid, trigger, playerName);
            } catch (Throwable t) {
                MaidLLMLocal.LOGGER.warn("maica trigger {} failed: {}", trigger.name(), t.toString());
            }
        }
    }

    private static void applyOne(EntityMaid maid, MaicaTrigger trigger, String playerName) {
        switch (trigger.name()) {
            case "alter_affection" -> alterAffection(maid, trigger.arguments());
            case "write_memory" -> writeMemory(maid, trigger.arguments(), playerName);
            case "switch_work_task" -> switchWorkTask(maid, trigger.arguments());
            default -> MaidLLMLocal.LOGGER.warn("unknown maica trigger {}, ignored", trigger.name());
        }
    }

    /** {@code {"alter_value": <float>}} → 取整、钳制 ±{@value #MAX_AFFECTION_DELTA}、写进 TLM 好感度。 */
    private static void alterAffection(EntityMaid maid, JsonObject args) {
        JsonElement value = args.get("alter_value");
        if (value == null || !value.isJsonPrimitive()) {
            MaidLLMLocal.LOGGER.warn("maica alter_affection without numeric alter_value: {}", args);
            return;
        }
        float raw = value.getAsFloat();
        int delta = Math.max(-MAX_AFFECTION_DELTA, Math.min(MAX_AFFECTION_DELTA, Math.round(raw)));
        if (delta == 0) {
            return;
        }
        if (delta > 0) {
            maid.getFavorabilityManager().add(delta);
        } else {
            maid.getFavorabilityManager().reduce(-delta);
        }
        MaidLLMLocal.LOGGER.info("maica affection {} (raw {}) → level {} ({} pt)",
                delta, raw, maid.getFavorabilityManager().getLevel(), maid.getFavorability());
    }

    /** {@code {"memory_item": "<一句记忆>"}} → 女仆 NBT（下轮起注入 prompt）。 */
    private static void writeMemory(EntityMaid maid, JsonObject args, String playerName) {
        JsonElement item = args.get("memory_item");
        if (item == null || !item.isJsonPrimitive()) {
            MaidLLMLocal.LOGGER.warn("maica write_memory without memory_item: {}", args);
            return;
        }
        // 蒸馏句也可能带玩家占位符，落盘前换成真名（模型自己就是 DDLC/MAS 训练的）
        String memory = MaicaText.replacePlayer(item.getAsString().strip(), playerName);
        if (memory.isEmpty()) {
            return;
        }
        MaicaMemory.append(maid, memory);
        MaidLLMLocal.LOGGER.info("maica memory appended: {}", abbreviate(memory));
    }

    /**
     * {@code {"choice": "<条目>"}} → 换工作模式。
     * 先按任务 uid 路径匹配（跨语言环境稳健），再按显示名匹配（本机 locale 一致时命中）；
     * 后端判断「无合适项」时会回传布尔 False，那不算失败。
     */
    private static void switchWorkTask(EntityMaid maid, JsonObject args) {
        JsonElement choice = args.get("choice");
        if (choice == null || !choice.isJsonPrimitive() || !choice.getAsJsonPrimitive().isString()) {
            MaidLLMLocal.LOGGER.info("maica task switch: backend returned no suitable choice");
            return;
        }
        String name = choice.getAsString();

        IMaidTask matched = null;
        for (Map.Entry<ResourceLocation, IMaidTask> entry : TaskManager.getTaskMap().entrySet()) {
            if (entry.getKey().getPath().equals(name)) {
                matched = entry.getValue();
                break;
            }
        }
        if (matched == null) {
            for (Map.Entry<ResourceLocation, IMaidTask> entry : TaskManager.getTaskMap().entrySet()) {
                if (entry.getValue().getName().getString().equals(name)) {
                    matched = entry.getValue();
                    break;
                }
            }
        }
        if (matched == null) {
            MaidLLMLocal.LOGGER.warn("maica task switch: no task matches choice {}", name);
            return;
        }
        // isEnable 条件不满足的任务 TLM 大脑自然不会执行，这里不需要预检
        maid.setTask(matched);
        MaidLLMLocal.LOGGER.info("maica task switch: maid {} → {}", maid.getUUID(), matched.getUid());
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
