package com.maidllmlocal.maica;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidllmlocal.MaidLLMLocal;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 MTrigger 调用：MAICA 后端在每轮对话后决策出的「角色该做点什么」。
 *
 * <p>双端共享：客户端（{@code MaicaWsSession}）从 {@code maica_mtrigger_trigger} 帧解析出本对象，
 * 序列化成 JSON 数组随响应包回传；服务端（{@link MaicaTriggers}）再解析回来作用到女仆实体。
 * 帧格式（官方节点 2026-09-16 实测）：{@code {"name": "...", "arguments": {...}}}。
 */
public record MaicaTrigger(String name, JsonObject arguments) {

    /**
     * 解析一帧 maica_mtrigger_trigger 的 content。形状不符返回 null（调用方记日志）——
     * 触发器是数据帧不是状态帧，坏一个不杀一轮。
     */
    public static MaicaTrigger fromFrameContent(String content) {
        try {
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
            if (!obj.has("name") || !obj.get("name").isJsonPrimitive()) {
                return null;
            }
            JsonObject args = obj.has("arguments") && obj.get("arguments").isJsonObject()
                    ? obj.getAsJsonObject("arguments") : new JsonObject();
            return new MaicaTrigger(obj.get("name").getAsString(), args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 响应包载荷 → 触发器列表。整段解析失败按空表处理（触发器丢了，回复文本还在）。 */
    public static List<MaicaTrigger> fromJsonArray(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            List<MaicaTrigger> triggers = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                MaicaTrigger trigger = fromFrameContent(array.get(i).toString());
                if (trigger != null) {
                    triggers.add(trigger);
                }
            }
            return triggers;
        } catch (Throwable t) {
            MaidLLMLocal.LOGGER.warn("maica triggers json malformed, dropped: {}", abbreviate(json));
            return List.of();
        }
    }

    /** 触发器列表 → 响应包载荷（JSON 数组字符串）。空表给 "[]"，解析端少一个特例。 */
    public static String toJsonArray(List<MaicaTrigger> triggers) {
        JsonArray array = new JsonArray();
        if (triggers != null) {
            for (MaicaTrigger trigger : triggers) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", trigger.name());
                obj.add("arguments", trigger.arguments() == null ? new JsonObject() : trigger.arguments());
                array.add(obj);
            }
        }
        return array.toString();
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
