package com.maidllmlocal.maica;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端的「知识条目」捎带通道。
 *
 * <p>托管模式下，女仆 NBT 里的长期记忆与静态世界事实要作为 temp savefile additions 送后端
 * （见 {@link MaicaMemory#additions}、{@link MaicaScene#STATIC_FACTS}），但这些数据在服务端
 * 的女仆实体上，而真正发 query 的是玩家客户端。为此把它们塞进 {@code messagesJson} 数组的
 * 一条<b>哨兵消息</b>里：role 用 {@link #ROLE}，正文带 {@link #PREFIX} 前缀 + JSON 数组。
 *
 * <p><b>为什么不用新协议字段</b>：加字段要动包结构——主线的 registrar 与 Forge 线的
 * SimpleChannel 都得升级、两端必须同换，而这两条线的网络层实现完全不同（一个
 * {@code CustomPacketPayload}、一个带分片的重写 SimpleChannel），改动得在两边各写一遍。
 * 哨兵走的是既有的消息数组，天然只在托管模式出现，且<b>老客户端不会受伤</b>：托管下
 * 客户端只取最后一条 user 消息，其余元素一律忽略。
 *
 * <p>客户端的职责：把这颗哨兵<b>摘掉</b>（绝不能让它进 query——MAICA 只认
 * system/user/assistant，多一个 role 整轮报废），解出条目，合并进
 * {@code savefile.mas_player_additions}。
 */
public final class MaicaCarrier {

    /** 哨兵 role。MAICA 不认这个 role，所以它绝不能活到 query 里。 */
    public static final String ROLE = "developer";
    /** 正文前缀：客户端靠它认出哨兵，避免误伤将来可能出现的同名 role。 */
    public static final String PREFIX = "maidllmlocal/additions:";

    /**
     * 单轮 temp additions 的条数上限。后端硬限制：
     * {@code SessionPersistent.validate} 在 {@code len(form_info(where='temp')) > 32} 时
     * 抛 MaicaInputWarning——超了整轮吃警告。发送前一律裁到这个数。
     */
    public static final int MAX_ADDITIONS = 32;

    private static final Gson GSON = new Gson();

    private MaicaCarrier() {
    }

    /** 把条目挂进消息数组末尾（托管下客户端只读最后一条 user，位置不影响它）。 */
    public static String attach(String messagesJson, List<String> additions) {
        if (additions.isEmpty()) {
            return messagesJson;
        }
        JsonArray messages = JsonParser.parseString(messagesJson).getAsJsonArray();
        JsonArray payload = new JsonArray();
        for (String addition : additions) {
            payload.add(addition);
        }
        JsonObject carrier = new JsonObject();
        carrier.addProperty("role", ROLE);
        carrier.addProperty("content", PREFIX + payload);
        messages.add(carrier);
        return GSON.toJson(messages);
    }

    /** 摘哨兵的结果：干净的消息数组 + 解出的条目。 */
    public record Stripped(String messagesJson, List<String> additions) {
    }

    /**
     * 摘掉哨兵：认出前缀就取走条目并从数组里删除该元素。没有哨兵（老服务端 / -1 模式）
     * 时原样返回、条目为空——不抛异常，坏内容当没有。
     */
    public static Stripped strip(String messagesJson) {
        JsonArray messages;
        try {
            messages = JsonParser.parseString(messagesJson).getAsJsonArray();
        } catch (RuntimeException malformed) {
            return new Stripped(messagesJson, List.of());
        }
        List<String> additions = new ArrayList<>();
        boolean found = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonElement element = messages.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            if (!message.has("role") || !message.get("role").isJsonPrimitive()
                    || !ROLE.equals(message.get("role").getAsString())) {
                continue;
            }
            if (!message.has("content") || !message.get("content").isJsonPrimitive()) {
                continue;
            }
            String content = message.get("content").getAsString();
            if (!content.startsWith(PREFIX)) {
                continue;
            }
            try {
                for (JsonElement item : JsonParser.parseString(content.substring(PREFIX.length()))
                        .getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        additions.add(item.getAsString());
                    }
                }
            } catch (RuntimeException malformed) {
                // 坏载荷不放行：条目当空，但哨兵照样摘掉（它绝不能进 query）
            }
            messages.remove(i);
            found = true;
        }
        return new Stripped(found ? GSON.toJson(messages) : messagesJson, additions);
    }
}
