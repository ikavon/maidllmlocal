import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MTrigger 探针：先 REST 预上传触发器表（session=-1 下内联 trigger 字段会被静默忽略，
 * 这是官方节点实测结论），再跑一整轮 enable_mt=true 的对话，把 maica_mtrigger_trigger 帧
 * 完整打出来。maidllmlocal 的 M3b 客户端逻辑就是这套流程的模组版。
 *
 * <p>用法: java WsTriggerProbe "wss://..." "<token>" ["请记住：我最喜欢的花是樱花"] [0sys|1sys|2sys]
 * <p>system 形状三档（对齐模组 MaicaClient 的发送形态）：0sys 纯 user（旧探针行为）；
 * 1sys 人设与注入块合并为唯一 system（修复后的形态）；2sys 两条 system 连排（2026-09-17
 * 游戏内实测被后端 400 拒掉的形态——"System message must be at the beginning"）。
 */
public class WsTriggerProbe {

    private static final String PERSONA = "你是莫妮卡，一位温柔的车万女仆。";
    // 注意：这两个串会直接拼进 JSON 字符串字面量，换行必须是字面量 \n（源码里 \\n），不能是真换行
    private static final String INJECT = "【长期记忆】（你记得的关于主人和你们之间的事）\\n- 主人最喜欢的花是樱花\\n【当前好感度】等级 2（35 点）";

    /** 与模组 MaicaTriggerUploader 同款的三件套（探针里任务列表随便给两个意思一下）。 */
    private static final String TABLE = "["
            + "{\"template\":\"common_affection_template\",\"name\":\"alter_affection\"},"
            + "{\"template\":\"memory_writeback_template\",\"name\":\"write_memory\"},"
            + "{\"template\":\"common_switch_template\",\"name\":\"switch_work_task\",\"exprop\":{"
            + "\"item_name\":{\"zh\":\"工作模式\",\"en\":\"work task\"},"
            + "\"item_list\":[\"待机\",\"耕作\"],\"suggestion\":false}}"
            + "]";

    public static void main(String[] args) throws Exception {
        String url = args[0];
        String token = args[1];
        String text = args.length > 2 ? args[2] : "请记住：我最喜欢的花是樱花";
        String mode = args.length > 3 ? args[3] : "0sys";
        // query 数组按模式拼装（json 手工拼，探针够用）
        String queryArray;
        switch (mode) {
            case "1sys" -> queryArray = "[{\"role\":\"system\",\"content\":\"" + PERSONA + "\\n\\n" + INJECT
                    + "\"},{\"role\":\"user\",\"content\":\"" + text + "\"}]";
            case "2sys" -> queryArray = "[{\"role\":\"system\",\"content\":\"" + PERSONA
                    + "\"},{\"role\":\"system\",\"content\":\"" + INJECT
                    + "\"},{\"role\":\"user\",\"content\":\"" + text + "\"}]";
            default -> queryArray = "[{\"role\":\"user\",\"content\":\"" + text + "\"}]";
        }

        // 1) REST 预上传触发器表
        String httpBase = url.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://")
                .replaceAll("/websocket$", "/api").replaceAll("/+$", "");
        HttpClient http = HttpClient.newHttpClient();
        String body = "{\"access_token\":\"" + token + "\",\"chat_session\":\"-1\",\"content\":" + TABLE + "}";
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(httpBase + "/trigger"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println(">>> POST " + httpBase + "/trigger -> HTTP " + resp.statusCode());
        System.out.println("<<< " + resp.body());

        // 2) WS 一整轮（enable_mt=true）
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder reply = new StringBuilder();

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    String frame = partial.toString();
                    partial.setLength(0);
                    handle(ws, frame);
                }
                ws.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                System.out.println("ERROR: " + error);
                done.countDown();
            }

            private void handle(WebSocket ws, String frame) {
                String status = frame.replaceAll(".*\"status\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                switch (status) {
                    case "maica_connection_initiated" -> {
                        System.out.println("<<< initiated, sending auth");
                        ws.sendText("{\"type\":\"auth\",\"access_token\":\"" + token
                                + "\",\"frontend_id\":\"maidllmlocal|0.3.0\"}", true);
                    }
                    case "maica_connection_established" -> {
                        System.out.println("<<< established, sending params (enable_mt=true)");
                        ws.sendText("{\"type\":\"params\",\"chat_params\":{\"stream_output\":true,"
                                + "\"target_lang\":\"zh\",\"savefile_access\":false,"
                                + "\"enable_mt\":true,\"enable_mf\":false},\"reset\":false}", true);
                        String query = "{\"type\":\"query\",\"chat_session\":-1,\"pprt\":true,"
                                + "\"query\":" + queryArray + "}";
                        System.out.println(">>> query(" + mode + "): " + text);
                        ws.sendText(query, true);
                    }
                    case "maica_core_streaming_continue" -> {
                        String content = frame.replaceAll(".*\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1");
                        reply.append(content);
                        System.out.print(".");
                    }
                    case "maica_mtrigger_trigger" ->
                            System.out.println("\n<<< MTRIGGER: " + frame);
                    case "maica_chat_loop_finished", "maica_worker_loop_finished" -> {
                        System.out.println("\n<<< " + status);
                        if ("maica_chat_loop_finished".equals(status)) {
                            done.countDown();
                        }
                    }
                    default -> {
                        if (frame.length() > 160) {
                            System.out.println("\n<<< [" + status + "] " + frame.substring(0, 160) + "...");
                        } else {
                            System.out.println("\n<<< [" + status + "] " + frame);
                        }
                    }
                }
            }
        };

        long t0 = System.currentTimeMillis();
        WebSocket ws = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(url), listener)
                .get(20, TimeUnit.SECONDS);
        System.out.println("connected (+" + (System.currentTimeMillis() - t0) + "ms)");

        if (!done.await(120, TimeUnit.SECONDS)) {
            System.out.println("TIMEOUT waiting for chat_loop_finished");
        } else {
            System.out.println("ROUND OK in " + (System.currentTimeMillis() - t0) + "ms");
            System.out.println("REPLY (" + reply.length() + " chars): " + reply);
        }
        ws.abort();
        System.exit(0);
    }
}
