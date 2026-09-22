package com.maidllmlocal.client.maica;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.maica.MaicaRoundResult;
import com.maidllmlocal.maica.MaicaTrigger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 一条到 MAICA 的 WebSocket 长连接（玩家客户端侧，每站点一条）。
 *
 * <p>移植 maica4tlm 的 wsclient.py，语义逐条对应：
 * <ul>
 *   <li>握手：等 initiated → auth（access_token + frontend_id）→ established → 1.5s 宽限收通告
 *       → 下发 params → drain 到 worker_loop_finished</li>
 *   <li>单工：一轮收到 chat_loop_finished 前不能发下一条 —— {@link #query} 整个 {@code synchronized}</li>
 *   <li>断线：生成中断线 → 重连 + {@code reconn} 续传；续传缓冲为空（中断在生成前）则抛出请调用方重发</li>
 *   <li>{@code maica_loop_warn_reset}：后端放弃本轮但连接还在——有文本就降级返回，断开以保证下一轮干净</li>
 *   <li>致命错误/超时：一律断开连接（排空接不住迟到的残留帧，实测会污染下一轮）</li>
 *   <li>sping 应用层心跳 30s（静默，服务端不回复）</li>
 * </ul>
 *
 * <p>java.net.http.WebSocket 是回调式 API，这里用阻塞队列把它拍平成"读下一帧"的同步模型——
 * 协议状态机比回调链好懂得多，且 {@link #query} 本来就跑在后台线程上。
 */
public final class MaicaWsSession implements WebSocket.Listener {

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FIRST_FRAME_TIMEOUT = Duration.ofSeconds(20);
    private static final long QUERY_TIMEOUT_MS = 90_000L;
    private static final long ANNOUNCE_GRACE_MS = 1_500L;
    private static final long DRAIN_BUDGET_MS = 5_000L;
    private static final long SPING_INTERVAL_S = 30L;

    /** 连接在收帧途中死掉时进队列的哨兵。 */
    private static final String POISON = " POISON_PILL_7f3a9d ";

    private final String wsUrl;
    private final String token;
    /** 站点配置的默认 target_lang（headers 或 maica_account.json 下发）。 */
    private final String targetLang;
    /** 当前会话生效的 target_lang——每轮可能因女仆级语言设置被覆盖（见 {@link #query}）。 */
    private String activeLang;
    /** 站点 headers 里的开关：开了才下发 enable_mt 并收集触发器帧。 */
    private final boolean enableMt;
    /**
     * -1 = 前端自持上下文（现状默认）；0-9 = 托管会话，后端记历史、开 MFocus/存档 RAG。
     * 托管与 -1 的语义差异见 docs/CROSSFRONTEND.md。
     */
    private final int chatSession;
    /** MAS→MC 交接文件读取器；null = 未配置（-1 模式下也用不上）。 */
    private final MaicaHandoff handoff;

    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
    private volatile WebSocket ws;

    private static final ScheduledExecutorService SPING = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "maidllmlocal-maica-sping");
        thread.setDaemon(true);
        return thread;
    });

    public MaicaWsSession(String wsUrl, String token, String targetLang, boolean enableMt,
                          int chatSession, MaicaHandoff handoff) {
        this.wsUrl = wsUrl;
        this.token = token;
        this.targetLang = targetLang;
        this.enableMt = enableMt;
        this.chatSession = chatSession;
        this.handoff = handoff;
    }

    private boolean hosted() {
        return chatSession >= 0;
    }

    // ---------- 对外 ----------

    /**
     * 跑完一整轮对话，返回聚合文本与本轮的 MTrigger 调用。
     * 全程持锁（MAICA 单工），并发调用会排队而不是互相踩。
     *
     * @param langOverride 本轮的语言覆盖（{@code zh}/{@code en}），空串 = 用站点配置的默认 target_lang。
     *                     语言是 per-maid 的而连接是 per-站点共享的，所以只能在请求粒度重发 params。
     */
    public synchronized MaicaRoundResult query(String messagesJson, String langOverride) throws Exception {
        ensureConnected();
        // 语言变了就先重发一次 chat_params（reset:false，不动历史）再发 query——MAICA 的
        // target_lang 是会话级参数，不发就不会生效。同会话多女仆来回切换时按此对齐
        String desiredLang = (langOverride != null && !langOverride.isEmpty()) ? langOverride : targetLang;
        if (!desiredLang.equals(activeLang)) {
            sendChatParams(desiredLang);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "query");
        payload.addProperty("pprt", true);
        if (hosted()) {
            // 托管模式：历史在后端，query 只发本轮用户消息纯文本；
            // 交接记忆走 savefile 临时注入（后端与持久档合并后参与 RAG）
            payload.addProperty("chat_session", chatSession);
            String queryText = extractLastUserText(messagesJson);
            payload.addProperty("query", queryText);
            int attached = 0;
            if (handoff != null && !handoff.isEmpty()) {
                JsonObject savefile = new JsonObject();
                savefile.add("mas_player_additions", handoff.additions());
                payload.add("savefile", savefile);
                attached = handoff.additions().size();
            }
            // 原文进日志——托管模式下这是唯一能看到"她实际收到什么"的地方：
            // L2 场景包装只改发送副本（TLM 历史里没有），后端存的历史也取不回来。
            MaidLLMLocal.LOGGER.info("maica query (session={}, handoff additions={}):\n{}",
                    chatSession, attached, queryText);
        } else {
            payload.addProperty("chat_session", -1);
            payload.add("query", JsonParser.parseString(messagesJson).getAsJsonArray());
        }
        send(payload);
        try {
            return collectStream(System.currentTimeMillis() + QUERY_TIMEOUT_MS);
        } catch (DeadConnectionException dead) {
            MaidLLMLocal.LOGGER.info("maica connection dropped mid-round, trying reconn resume");
            return recoverStream();
        }
    }

    /**
     * 从 TLM 发来的 OpenAI 消息数组里取最后一条 user 消息的纯文本。
     * 托管模式下后端自己保管历史与人设，前端数组里只有这一条有发送价值
     * （场景包装已在服务端并入这条消息正文，见 docs/CROSSFRONTEND.md 的 L2 层）。
     */
    private static String extractLastUserText(String messagesJson) {
        JsonArray messages = JsonParser.parseString(messagesJson).getAsJsonArray();
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonObject message = messages.get(i).getAsJsonObject();
            if (!"user".equals(message.get("role").getAsString())) {
                continue;
            }
            if (message.get("content").isJsonPrimitive()) {
                return message.get("content").getAsString();
            }
            // content 是数组（vision 形态）：拼出其中的文本段
            StringBuilder text = new StringBuilder();
            for (var part : message.getAsJsonArray("content")) {
                JsonObject piece = part.getAsJsonObject();
                if (piece.has("text")) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(piece.get("text").getAsString());
                }
            }
            return text.toString();
        }
        throw new IllegalArgumentException("no user message in TLM history payload");
    }

    /** 连接不再使用时断开（站点被删/游戏退出）。 */
    public synchronized void close() {
        WebSocket current = ws;
        ws = null;
        if (current != null) {
            current.abort();
        }
    }

    // ---------- 连接生命周期 ----------

    private void ensureConnected() throws Exception {
        if (ws != null) {
            return;
        }
        inbox.clear();
        MaidLLMLocal.LOGGER.info("maica ws connecting {} ...", wsUrl);
        WebSocket connected = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(wsUrl), this)
                .get(20, TimeUnit.SECONDS);
        ws = connected;
        try {
            handshake();
        } catch (Throwable t) {
            close();
            throw t;
        }
    }

    private void handshake() throws Exception {
        MaicaProtocol.Envelope first = recv(FIRST_FRAME_TIMEOUT.toMillis());
        MaidLLMLocal.LOGGER.info("maica first frame: {} (code={})", first.status(), first.code());
        if (!MaicaProtocol.CONNECTION_INITIATED.equals(first.status())
                && first.severity() == MaicaProtocol.Severity.FATAL) {
            throw new Exception("MAICA first frame fatal: " + first.status() + " " + first.content());
        }

        JsonObject auth = new JsonObject();
        auth.addProperty("type", "auth");
        auth.addProperty("access_token", token);
        // frontend_id 约定是 <type>|<version>（服务端只拿它记日志），版本从 mod 元数据取——
        // 写死的话每次发版都得记得回来改，漏了后端日志里就一直是旧版本号
        auth.addProperty("frontend_id", "maidllmlocal|" + MaidLLMLocal.version());
        send(auth);

        // 登录成功依次收 login_id/user/nickname → established → model_anno → 可选 feature_*
        long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT.toMillis();
        StringBuilder warnings = new StringBuilder();
        while (true) {
            MaicaProtocol.Envelope env;
            try {
                env = recv(remaining(deadline));
            } catch (FrameTimeoutException timeout) {
                // 握手卡死时必须带走证据：服务端到底说过什么（token 无效/ToS 未接受/互踢…）
                throw new Exception("MAICA auth timeout; server frames so far: ["
                        + warnings.toString().trim() + "]", timeout);
            }
            MaidLLMLocal.LOGGER.info("maica handshake frame: {} (code={}) {}",
                    env.status(), env.code(), abbreviate(env.content()));
            if (MaicaProtocol.ESTABLISHED.equals(env.status())) {
                break;
            }
            if (MaicaProtocol.LOGIN_REJECT_STATUSES.contains(env.status())) {
                // 登录拒绝 = established 永远等不到。后端只把它发成 NOTICE（它的循环不崩），
                // 但我们对玩家必须立刻说人话——不能拿 30 秒超时换一句日志黑话。
                throw new MaicaAuthReject(env.status(), env.content());
            }
            if (env.severity() == MaicaProtocol.Severity.FATAL) {
                throw new Exception("MAICA auth failed: " + env.status() + " " + env.content());
            }
            warnings.append(env.status()).append('=').append(abbreviate(env.content())).append(' ');
        }

        // established 之后还有 model_anno / feature_* 通告，给一小段宽限期收集
        long graceEnd = System.currentTimeMillis() + ANNOUNCE_GRACE_MS;
        while (System.currentTimeMillis() < graceEnd) {
            try {
                MaicaProtocol.Envelope env = recv(graceEnd - System.currentTimeMillis());
                if (env.severity() == MaicaProtocol.Severity.FATAL) {
                    throw new Exception("MAICA announcement fatal: " + env.status() + " " + env.content());
                }
            } catch (FrameTimeoutException quiet) {
                break; // 通告完毕
            }
        }

        sendChatParams(targetLang);
        drain(DRAIN_BUDGET_MS);

        MaidLLMLocal.LOGGER.info("maica ws established (target_lang={}, handshake warnings: {})",
                activeLang, warnings.length() == 0 ? "none" : warnings);
    }

    /**
     * 下发 chat_params：TLM 场景最小集。enable_mt 跟着站点配置走——关着时后端根本不发触发器帧。
     * 托管模式打开 savefile_access（存档 RAG）与 enable_mf（MFocus/每轮现实时间注入）；
     * 这两者在 -1 下被后端 prompt_writable 总闸屏蔽，开了也白开。
     *
     * <p>握手时调一次，之后每当本轮语言与当前生效值不同时再调一次（见 {@link #query}）——
     * target_lang 是会话级参数，per-maid 覆盖只能靠重发 params 生效。
     */
    private void sendChatParams(String lang) {
        JsonObject chatParams = new JsonObject();
        chatParams.addProperty("stream_output", true);
        chatParams.addProperty("target_lang", lang);
        chatParams.addProperty("savefile_access", hosted());
        chatParams.addProperty("enable_mt", enableMt);
        chatParams.addProperty("enable_mf", hosted());
        JsonObject params = new JsonObject();
        params.addProperty("type", "params");
        params.add("chat_params", chatParams);
        params.addProperty("reset", false);
        send(params);
        activeLang = lang;
    }

    // ---------- 一轮对话 ----------

    private MaicaRoundResult collectStream(long deadline) throws Exception {
        StringBuilder buf = new StringBuilder();
        StringBuilder notices = new StringBuilder();
        List<MaicaTrigger> triggers = new ArrayList<>();
        while (true) {
            MaicaProtocol.Envelope env = recv(remaining(deadline));
            String status = env.status();

            if (MaicaProtocol.STREAM_CONTINUE.equals(status)) {
                buf.append(env.content());
                continue;
            }
            if (MaicaProtocol.MTRIGGER_TRIGGER.equals(status)) {
                // MTrigger 调用帧：content 是 {"name","arguments"}。它是数据帧不是状态帧——
                // 坏帧记 warn 跳过，一个坏触发器不该杀掉整轮回复
                MaicaTrigger trigger = MaicaTrigger.fromFrameContent(env.content());
                if (trigger != null) {
                    triggers.add(trigger);
                    MaidLLMLocal.LOGGER.info("maica trigger: {} {}", trigger.name(), trigger.arguments());
                } else {
                    MaidLLMLocal.LOGGER.warn("maica trigger frame malformed: {}", abbreviate(env.content()));
                }
                continue;
            }
            if (MaicaProtocol.LOOP_FINISHED.equals(status)) {
                // 真实后端每轮结束后会补发一帧 worker_loop_finished，吞掉免得残留到下一轮
                drain(DRAIN_BUDGET_MS);
                if (notices.length() > 0) {
                    MaidLLMLocal.LOGGER.info("maica round finished with notices: {}", notices);
                }
                return new MaicaRoundResult(buf.toString(), triggers);
            }
            if (MaicaProtocol.SESSION_WARN_RESET.equals(status)) {
                // 后端 warning → 发本帧 → continue：本轮不会再有 finished 帧，就此结束
                close(); // 断开是唯一能保证下一轮干净的做法
                if (buf.length() > 0) {
                    MaidLLMLocal.LOGGER.warn("maica round reset by server, degraded to {} chars", buf.length());
                    return new MaicaRoundResult(buf.toString(), triggers);
                }
                throw new Exception("MAICA round reset with no output: " + env.content());
            }

            switch (env.severity()) {
                case FATAL -> {
                    close();
                    throw new Exception("MAICA fatal " + status + ": " + env.content());
                }
                case NOTICE -> {
                    notices.append(status).append(' ');
                    MaidLLMLocal.LOGGER.warn("maica notice {} (code={}): {}", status, env.code(), env.content());
                }
                default -> {
                }
            }
        }
    }

    /** 断线续传：重连+重认证后发 reconn，收完缓冲。仅核心模型已开始生成时有效。
     *  续传缓冲只保存文本——中断那一轮的触发器会丢（见 {@link MaicaRoundResult}）。 */
    private MaicaRoundResult recoverStream() throws Exception {
        close();
        ensureConnected();
        JsonObject reconn = new JsonObject();
        reconn.addProperty("type", "reconn");
        send(reconn);

        StringBuilder buf = new StringBuilder();
        long deadline = System.currentTimeMillis() + QUERY_TIMEOUT_MS;
        while (true) {
            MaicaProtocol.Envelope env = recv(remaining(deadline));
            String status = env.status();
            if (MaicaProtocol.STREAM_CONTINUE.equals(status)) {
                buf.append(env.content());
                continue;
            }
            if (MaicaProtocol.LOOP_FINISHED.equals(status) || MaicaProtocol.RECONN_DRAINED.equals(status)) {
                if (MaicaProtocol.RECONN_DRAINED.equals(status) && buf.length() == 0) {
                    throw new Exception("MAICA reconn buffer empty: interruption happened before generation, resend the round");
                }
                return new MaicaRoundResult(buf.toString(), List.of());
            }
            if (MaicaProtocol.RECONN_EMPTY.equals(status)) {
                throw new Exception("MAICA reconn buffer empty: interruption happened before generation, resend the round");
            }
            if (MaicaProtocol.RECONN_BUFFER_STARTED.equals(status)) {
                continue;
            }
            if (env.severity() == MaicaProtocol.Severity.FATAL) {
                close();
                throw new Exception("MAICA fatal during reconn " + status + ": " + env.content());
            }
        }
    }

    // ---------- 帧收发 ----------

    private void send(JsonObject obj) {
        WebSocket current = ws;
        if (current == null) {
            throw new IllegalStateException("maica ws not connected");
        }
        current.sendText(obj.toString(), true).join();
    }

    private MaicaProtocol.Envelope recv(long timeoutMs) throws Exception {
        if (timeoutMs <= 0) {
            throw new FrameTimeoutException();
        }
        String raw = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (raw == null) {
            throw new FrameTimeoutException();
        }
        if (POISON.equals(raw)) {
            throw new DeadConnectionException();
        }
        return MaicaProtocol.parse(raw);
    }

    /** 吞掉残留帧：见到 worker_loop_finished / 安静 quiet 秒 / 超 budget。绝不外抛。 */
    private void drain(long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                MaicaProtocol.Envelope env = recv(deadline - System.currentTimeMillis());
                if (MaicaProtocol.WORKER_LOOP_FINISHED.equals(env.status())) {
                    return;
                }
            } catch (Exception e) {
                return;
            }
        }
    }

    private static long remaining(long deadline) {
        return deadline - System.currentTimeMillis();
    }

    /** 日志里的 content 截断到 120 字符（通告帧可能很长）。 */
    private static String abbreviate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > 120 ? content.substring(0, 120) + "..." : content;
    }

    // ---------- WebSocket.Listener ----------

    private final StringBuilder partial = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            inbox.offer(partial.toString());
            partial.setLength(0);
        }
        // java.net.http.WebSocket 是拉取式流控：初始额度只有 1 条消息，
        // 不持续 request 就会在收到第一帧(initiated)后永远静默——WsProbe 对照实验实锤
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        inbox.offer(POISON);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        inbox.offer(POISON);
        return CompletableFuture.completedFuture(null);
    }

    static {
        // sping 心跳：对所有活会话每 30s 发一次 {"type":"sping"}（静默，服务端不回复）
        SPING.scheduleAtFixedRate(() -> {
            for (MaicaWsSession session : ClientMaicaSessions.all()) {
                try {
                    if (session.ws != null) {
                        JsonObject sping = new JsonObject();
                        sping.addProperty("type", "sping");
                        session.send(sping);
                    }
                } catch (Throwable t) {
                    MaidLLMLocal.LOGGER.debug("maica sping failed: {}", t.toString());
                }
            }
        }, SPING_INTERVAL_S, SPING_INTERVAL_S, TimeUnit.SECONDS);
    }

    private static final class FrameTimeoutException extends Exception {
    }

    private static final class DeadConnectionException extends Exception {
    }

    /**
     * 登录被拒（凭据无效 / 邮箱未验证 / ToS 未接受 / 风控锁定 / 账号被占）。
     * message 被设计成可直接进聊天气泡：服务端原话 + 玩家下一步动作。
     */
    public static final class MaicaAuthReject extends Exception {
        public final String status;

        public MaicaAuthReject(String status, String content) {
            super("MAICA 登录被拒 [" + status + "]："
                    + (content == null || content.isBlank() ? "（服务端未给原因，见日志）" : content)
                    + authHint(status));
            this.status = status;
        }

        private static String authHint(String status) {
            return switch (status) {
                case "maica_login_token_invalid", "maica_login_token_corrupted" ->
                        "。处理：核对 maica_account.json——标识符须是论坛【登录ID】(精确匹配，昵称不算)或注册邮箱，密码须与论坛一致，改后重新进世界";
                case "maica_login_email_unchecked" ->
                        "。处理：先去 forum.monika.love 完成邮箱验证";
                case "maica_login_tos_unaccepted" ->
                        "。处理：先在 MAICA 论坛接受最新版服务条款";
                case "maica_login_f2b", "maica_login_banned" ->
                        "。处理：账号被临时风控，等约 10 分钟后再进世界（别反复重试，会续锁）";
                case "maica_connection_reuse_denied" ->
                        "。处理：该 MAICA 账号已被其它连接占用（如 MAS 客户端），先断开那边";
                default -> "";
            };
        }
    }
}
