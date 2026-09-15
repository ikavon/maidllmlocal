package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.ai.service.Client;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * TLM 的对话历史 → MAICA session=-1 的 {@code query} 字段（OpenAI 风格 messages 数组）。
 *
 * <p>session=-1 的协议硬约束（API Documents.md v1.3）：query 必须是 {@code {role, content}} 的 list，
 * <b>总长不能超 10 轮 / 16KB</b>。所以这里的裁剪不是防御性编程，而是协议要求——超了后端直接拒。
 *
 * <p>裁剪规则（移植 maica4tlm 的 prepare_messages）：system 消息<b>永远保留在头部</b>（TLM 的人设
 * 全靠它），其余从最新往最旧取，直到撞上任一上限。
 */
public final class MaicaMessages {
    /** 协议上限：10 轮。 */
    static final int MAX_MESSAGES = 10;
    /** 协议上限：16KB（按 UTF-8 字节计）。 */
    static final int MAX_BYTES = 16 * 1024;

    private MaicaMessages() {
    }

    /**
     * @param history TLM 的完整历史（含 system 人设与本轮 user 发言）
     * @return 可直接放进 query 字段的 JSON 数组字符串
     */
    public static String toJson(List<LLMMessage> history) {
        List<LLMMessage> system = new ArrayList<>();
        List<LLMMessage> rest = new ArrayList<>();
        for (LLMMessage msg : history) {
            if (msg.role() == Role.SYSTEM || msg.role() == Role.DEVELOPER) {
                system.add(msg);
            } else if (msg.role() == Role.USER || msg.role() == Role.ASSISTANT) {
                rest.add(msg);
            }
            // TOOL 消息在 M3a 不产生（本客户端不开工具轮）；若历史里混入则丢弃，
            // 免得 MAICA 收到不认识的 role
        }

        // 从最新往最旧装进剩余额度
        int budget = MAX_BYTES - bytesOf(system);
        List<LLMMessage> kept = new ArrayList<>();
        int slots = MAX_MESSAGES - system.size();
        for (int i = rest.size() - 1; i >= 0 && kept.size() < slots; i--) {
            LLMMessage msg = rest.get(i);
            int cost = byteLen(msg.message()) + 32; // 32 ≈ role 与 JSON 骨架的开销
            if (cost > budget && !kept.isEmpty()) {
                break; // 至少保住最新一条；单条超上限的情况交给后端报错
            }
            budget -= cost;
            kept.add(0, msg);
        }

        JsonArray array = new JsonArray();
        for (LLMMessage msg : system) {
            array.add(one("system", msg.message()));
        }
        for (LLMMessage msg : kept) {
            array.add(one(msg.role() == Role.USER ? "user" : "assistant", msg.message()));
        }
        return Client.GSON.toJson(array);
    }

    private static JsonObject one(String role, String content) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", role);
        obj.addProperty("content", content == null ? "" : content);
        return obj;
    }

    private static int bytesOf(List<LLMMessage> msgs) {
        int sum = 0;
        for (LLMMessage msg : msgs) {
            sum += byteLen(msg.message()) + 32;
        }
        return sum;
    }

    private static int byteLen(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }
}
