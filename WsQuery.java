import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 全对话轮探针：模拟 maidllmlocal 的完整会话路径
 * （initiated → auth → established → 通告宽限 → params → query → 流式聚合 → chat_loop_finished）。
 * 用法: java WsQuery "wss://..." "<token>" ["你好"]
 */
public class WsQuery {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        String token = args[1];
        String text = args.length > 2 ? args[2] : "你好，莫妮卡";
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
                                + "\",\"frontend_id\":\"maidllmlocal|0.2.0\"}", true);
                    }
                    case "maica_connection_established" -> {
                        System.out.println("<<< established, sending params");
                        ws.sendText("{\"type\":\"params\",\"chat_params\":{\"stream_output\":true,"
                                + "\"target_lang\":\"zh\",\"savefile_access\":false,"
                                + "\"enable_mt\":false,\"enable_mf\":false},\"reset\":false}", true);
                        // params 无回执帧要求等待，直接发 query（与 mod 行为一致：drain 只是清残留）
                        String query = "{\"type\":\"query\",\"chat_session\":-1,\"pprt\":true,"
                                + "\"query\":[{\"role\":\"user\",\"content\":\"" + text + "\"}]}";
                        System.out.println(">>> query: " + text);
                        ws.sendText(query, true);
                    }
                    case "maica_core_streaming_continue" -> {
                        String content = frame.replaceAll(".*\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1");
                        reply.append(content);
                        System.out.print(".");
                    }
                    case "maica_core_complete" -> System.out.println("\n<<< core_complete");
                    case "maica_chat_loop_finished", "maica_worker_loop_finished" -> {
                        System.out.println("<<< " + status);
                        if ("maica_chat_loop_finished".equals(status)) {
                            done.countDown();
                        }
                    }
                    default -> {
                        if (frame.length() > 160) {
                            System.out.println("<<< [" + status + "] " + frame.substring(0, 160) + "...");
                        } else {
                            System.out.println("<<< [" + status + "] " + frame);
                        }
                    }
                }
            }
        };

        long t0 = System.currentTimeMillis();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
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
