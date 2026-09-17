package com.maidllmlocal.client.maica;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;

/**
 * MAICA 协议的信封解析与严重性分级（移植 maica4tlm 的 protocol.py）。
 *
 * <p><b>状态码不是 HTTP 状态码。</b>文档明说"此处的状态码模式与HTTP状态码类似, 但性质不同"，
 * 且后端自己的失败模型是 {@code is_breaking}：400 段是警告（连接与生成都不断），500 段才是错误。
 * 所以这里<b>按 status 字符串白名单分级</b>，code 只在 status 未知时作回退，
 * 且回退用后端自己的错误带 {@code [500,1000)}（1000/1001 是"核心完成/循环结束"的<b>正常</b>码）。
 * 绝不按 4xx 区间判错——同一个 status（如 mfocus 天气失败）会带 204/400/406 不同的 code，
 * 按区间判会把优雅降级误杀成整轮报废。这条教训来自 MAICA 作者的当面批评。
 */
final class MaicaProtocol {

    // --- 连接/登录 ---
    static final String CONNECTION_INITIATED = "maica_connection_initiated";
    static final String ESTABLISHED = "maica_connection_established";
    static final String MODEL_ANNO = "maica_model_anno";
    static final String FEATURE_PREFIX = "maica_feature_";

    // --- 对话流 ---
    static final String STREAM_CONTINUE = "maica_core_streaming_continue";
    static final String LOOP_FINISHED = "maica_chat_loop_finished";
    static final String WORKER_LOOP_FINISHED = "maica_worker_loop_finished";
    static final String SESSION_WARN_RESET = "maica_loop_warn_reset";
    static final String RECONN_DRAINED = "maica_reconn_buffer_drained";
    static final String RECONN_EMPTY = "maica_reconn_buffer_empty";
    static final String RECONN_BUFFER_STARTED = "maica_reconn_buffer_started";

    // --- MTrigger ---
    /** MTrigger 调用帧：content 是 {"name","arguments"}，一轮可能有多帧。 */
    static final String MTRIGGER_TRIGGER = "maica_mtrigger_trigger";

    enum Severity {NORMAL, NOTICE, FATAL}

    /** 非致命警告：记录后继续本轮（对应后端 CommonMaicaWarning + continue）。 */
    private static final Set<String> NOTICE_STATUSES = Set.of(
            SESSION_WARN_RESET,
            "maica_loop_warn_resets", // 09-17 实测：握手里的这条是复数拼写
            "maica_login_token_corrupted", "maica_login_token_invalid",
            "maica_login_f2b", "maica_login_banned",
            "maica_login_email_unchecked", "maica_login_tos_unaccepted",
            "maica_connection_reuse_denied",
            "maica_input_param_bad",
            "maica_input_query_censored",
            "maica_mfocus_weather_failed",
            "mfocus_serp_failed",
            "maica_unified_warning"
    );

    /**
     * 登录阶段的拒绝。后端把它们发成 NOTICE 级别（"我的循环不崩"），但对客户端而言
     * <b>established 永远等不到了</b>——服务端发完就 loop_warn_reset 回 stage 1 重新等 auth。
     * v0.4.0 游戏内实测教训：只按"400 段是警告"一刀切，把 token 打错这种一句话能说清的
     * 失败拖成 30 秒 FrameTimeoutException + 日志考古。握手循环必须用这个集合快速失败。
     */
    static final Set<String> LOGIN_REJECT_STATUSES = Set.of(
            "maica_login_token_corrupted", "maica_login_token_invalid",
            "maica_login_f2b", "maica_login_banned",
            "maica_login_email_unchecked", "maica_login_tos_unaccepted",
            "maica_connection_reuse_denied");

    /** 致命错误：本轮报废 → 断开 → 抛出（对应后端 CommonMaicaError，is_breaking）。 */
    private static final Set<String> FATAL_STATUSES = Set.of(
            "maica_unified_error",
            "maica_uncaught_exception"
    );

    private MaicaProtocol() {
    }

    /** 一帧消息。{@code code} 可能是数字或字符串，解析失败按 null 处理。 */
    record Envelope(String status, String content, Integer code, String type) {
        boolean isCritical() {
            return (code != null && code >= 500 && code < 1000) || "error".equals(type);
        }

        Severity severity() {
            return classify(status, code, type);
        }
    }

    static Envelope parse(String raw) {
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        String status = obj.has("status") && !obj.get("status").isJsonNull()
                ? obj.get("status").getAsString() : "";
        String content = obj.has("content") && !obj.get("content").isJsonNull()
                ? obj.get("content").isJsonPrimitive() ? obj.get("content").getAsString()
                : obj.get("content").toString() : "";
        Integer code = null;
        if (obj.has("code") && !obj.get("code").isJsonNull()) {
            try {
                code = obj.get("code").getAsInt();
            } catch (Throwable ignored) {
            }
        }
        String type = obj.has("type") && !obj.get("type").isJsonNull()
                ? obj.get("type").getAsString() : "";
        return new Envelope(status, content, code, type);
    }

    static Severity classify(String status, Integer code, String type) {
        boolean critical = (code != null && code >= 500 && code < 1000) || "error".equals(type);
        if (FATAL_STATUSES.contains(status)) {
            return Severity.FATAL;
        }
        if (NOTICE_STATUSES.contains(status)) {
            // 镜像后端 maica_ws.py:195：警告被显式传入错误带内的 code 时后端也会断开
            return critical ? Severity.FATAL : Severity.NOTICE;
        }
        // 已知正常流、feature_* 通告、以及未知 status：只有落在后端错误带才算致命，
        // 未知 status 降级为 NOTICE 而不是静默忽略——新 status 出现时能在日志里被看见
        return critical ? Severity.FATAL
                : (status.startsWith(FEATURE_PREFIX) || isKnownNormal(status) ? Severity.NORMAL : Severity.NOTICE);
    }

    private static boolean isKnownNormal(String status) {
        return switch (status) {
            case CONNECTION_INITIATED, ESTABLISHED, MODEL_ANNO,
                 "maica_login_id", "maica_login_user", "maica_login_nickname",
                 "maica_provider_anno", "pong",
                 RECONN_BUFFER_STARTED, RECONN_DRAINED, RECONN_EMPTY,
                 "maica_connection_reuse_attempt", "maica_connection_reuse_stale",
                 WORKER_LOOP_FINISHED, "maica_params_accepted", "maica_params_reset", "maica_session_reset",
                 "maica_mcore_gen_start", STREAM_CONTINUE, "maica_core_complete", LOOP_FINISHED,
                 "maica_quality_status", "maica_history_sliced", "maica_history_slice_hint",
                 "maica_mspire_searching", "maica_mspire_page_found",
                 "maica_mfocus_tool_call", "maica_mfocus_tool_resp",
                 "maica_mfocus_tool_start", "maica_mfocus_tool_fin",
                 MTRIGGER_TRIGGER, "maica_mtrigger_tool_call",
                 "maica_mtrigger_tool_start", "maica_mtrigger_tool_fin" -> true;
            default -> false;
        };
    }
}
