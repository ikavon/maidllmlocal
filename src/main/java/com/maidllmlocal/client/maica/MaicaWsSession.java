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
    private final String targetLang;
    /** 站点 headers 里的开关：开了才下发 enable_mt 并收集触发器帧。 */
    private final boolean enableMt;

    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
    private volatile WebSocket ws;

    private static final ScheduledExecutorService SPING = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "maidllmlocal-maica-sping");
        thread.setDaemon(true);
        return thread;
    });

    public MaicaWsSession(String wsUrl, String token, String targetLang, boolean enableMt) {
        this.wsUrl = wsUrl;
        this.token = token;
        this.targetLang = targetLang;
        this.enableMt = enableMt;
    }

    // ---------- 对外 ----------

    /**
     * 跑完一整轮对话，返回聚合文本与本轮的 MTrigger 调用。
     * 全程持锁（MAICA 单工），并发调用会排队而不是互相踩。
     */
    public synchronized MaicaRoundResult query(String messagesJson) throws Exception {
        ensureConnected();
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "query");
        payload.addProperty("chat_session", -1);
        payload.add("query", JsonParser.parseString(messagesJson).getAsJsonArray());
        payload.addProperty("pprt", true);
        send(payload);
        try {
            return collectStream(System.currentTimeMillis() + QUERY_TIMEOUT_MS);
        } catch (DeadConnectionException dead) {
            MaidLLMLocal.LOGGER.info("maica connection dropped mid-round, trying reconn resume");
            return recoverStream();
        }
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
        auth.addProperty("frontend_id", "maidllmlocal|0.3.0");
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

        // 下发参数：TLM 场景最小集。enable_mt 跟着站点配置走——关着时后端根本不发触发器帧
        JsonObject chatParams = new JsonObject();
        chatParams.addProperty("stream_output", true);
        chatParams.addProperty("target_lang", targetLang);
        chatParams.addProperty("savefile_access", false);
        chatParams.addProperty("enable_mt", enableMt);
        chatParams.addProperty("enable_mf", false);
        JsonObject params = new JsonObject();
        params.addProperty("type", "params");
        params.add("chat_params", chatParams);
        params.addProperty("reset", false);
        send(params);
        drain(DRAIN_BUDGET_MS);

        MaidLLMLocal.LOGGER.info("maica ws established (target_lang={}, handshake warnings: {})",
                targetLang, warnings.length() == 0 ? "none" : warnings);
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
}
