package com.maidllmlocal.client.maica;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidllmlocal.MaidLLMLocal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MTrigger 触发器表的构造与上传（客户端侧，每站点会话创建时一次）。
 *
 * <p>为什么必须预上传：session=-1 下后端<b>静默忽略</b> query 里的临时 trigger 字段
 * （官方节点 2026-09-16 实测），触发器表只能靠 REST {@code POST /trigger} 按 chat_session
 * 预存。我们固定用 -1，所以上传一次即可，配置变更导致会话重建时自然重传。
 *
 * <p>表的内容是本模组支持的三件套：好感度增减 / 长期记忆写回 / 换工作模式。
 * 上传是<b>尽力而为</b>：失败只记日志——没有表的后端不发触发器帧，聊天本身不受影响。
 */
public final class MaicaTriggerUploader {

    /** REST 短连接超时：别拖住会话创建。 */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(15);

    private MaicaTriggerUploader() {
    }

    /**
     * 异步上传触发器表，调用方不等待。
     *
     * @param wsUrl            站点 WS 地址，用于推导 REST 基地址
     * @param token            本机 MAICA access_token
     * @param httpBaseOverride 站点 headers 里的 {@code http_base} 覆盖；空串表示推导
     */
    public static void uploadAsync(String wsUrl, String token, String httpBaseOverride) {
        String httpBase = (httpBaseOverride == null || httpBaseOverride.isEmpty())
                ? deriveHttpBase(wsUrl) : httpBaseOverride;
        JsonObject body = new JsonObject();
        body.addProperty("access_token", token);
        body.addProperty("chat_session", "-1"); // 文档：除 content 外均为 str
        body.add("content", buildTable());

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(httpBase + "/trigger"))
                    .timeout(UPLOAD_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
        } catch (Throwable t) {
            MaidLLMLocal.LOGGER.warn("maica trigger table upload aborted, bad http base {}: {}", httpBase, t);
            return;
        }

        HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    // 短连接统一响应 {"success":bool, "exception":...}：以 body 为准，不看状态码区间
                    try {
                        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                        if (obj.has("success") && obj.get("success").getAsBoolean()) {
                            MaidLLMLocal.LOGGER.info("maica trigger table uploaded to {}", httpBase);
                        } else {
                            MaidLLMLocal.LOGGER.warn("maica trigger table rejected by {}: {}",
                                    httpBase, obj.has("exception") ? obj.get("exception").getAsString() : resp.body());
                        }
                    } catch (Throwable t) {
                        MaidLLMLocal.LOGGER.warn("maica trigger table upload: bad response (HTTP {}) from {}",
                                resp.statusCode(), httpBase);
                    }
                })
                .exceptionally(t -> {
                    MaidLLMLocal.LOGGER.warn("maica trigger table upload failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * 从 WS 地址推导 REST 基地址：{@code wss://host/websocket} → {@code https://host/api}。
     * 官方节点的 ws 路径是 {@code /websocket}，REST 在同一主机的 {@code /api}；
     * 自建节点路径不同就用站点 headers 的 {@code http_base} 显式覆盖。
     */
    static String deriveHttpBase(String wsUrl) {
        String base = wsUrl.strip();
        if (base.startsWith("wss://")) {
            base = "https://" + base.substring("wss://".length());
        } else if (base.startsWith("ws://")) {
            base = "http://" + base.substring("ws://".length());
        }
        if (base.endsWith("/websocket")) {
            base = base.substring(0, base.length() - "/websocket".length()) + "/api";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * 三件套触器表。switch 的 item_list 是当前注册的全部任务显示名（客户端 locale）——
     * 服务端执行时先按 uid 路径匹配再按显示名匹配，双语言环境错位只会降级不会误切。
     */
    private static JsonArray buildTable() {
        JsonArray table = new JsonArray();

        // 好感度增减：name 是协议固定值（缺了官方节点直接拒收），全程最多一个
        JsonObject affection = new JsonObject();
        affection.addProperty("template", "common_affection_template");
        affection.addProperty("name", "alter_affection");
        table.add(affection);

        // 长期记忆写回：name 同样固定。回传的 memory_item 由前端自存，后端不同步
        JsonObject memory = new JsonObject();
        memory.addProperty("template", "memory_writeback_template");
        memory.addProperty("name", "write_memory");
        table.add(memory);

        JsonObject switchTask = new JsonObject();
        switchTask.addProperty("template", "common_switch_template");
        switchTask.addProperty("name", "switch_work_task");
        JsonObject exprop = new JsonObject();
        JsonObject itemName = new JsonObject();
        itemName.addProperty("zh", "工作模式");
        itemName.addProperty("en", "work task");
        exprop.add("item_name", itemName);
        JsonArray items = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (IMaidTask task : TaskManager.getTaskMap().values()) {
                String name = task.getName().getString();
                if (!name.isEmpty() && seen.add(name)) {
                    items.add(name);
                }
            }
        } catch (Throwable t) {
            // 任务表读不出来就传空列表——上传依然有价值（另两个触发器固定可用）
            MaidLLMLocal.LOGGER.warn("maica trigger table: task list unavailable: {}", t.toString());
        }
        exprop.add("item_list", items);
        exprop.addProperty("suggestion", false);
        switchTask.add("exprop", exprop);
        table.add(switchTask);

        return table;
    }
}
