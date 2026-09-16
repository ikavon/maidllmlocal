import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 用与 mod 完全相同的 API 复现 MAICA 握手：initiated → auth → 打印 30s 内所有帧。 */
public class WsProbe {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        String token = args[1];
        CountDownLatch done = new CountDownLatch(1);
        long start = System.currentTimeMillis();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                String text = data.toString();
                System.out.println("FRAME (+" + (System.currentTimeMillis() - start) + "ms): "
                        + (text.length() > 300 ? text.substring(0, 300) + "..." : text));
                if (text.contains("maica_connection_initiated")) {
                    String auth = "{\"type\":\"auth\",\"access_token\":\"" + token
                            + "\",\"frontend_id\":\"maidllmlocal|0.2.0\"}";
                    System.out.println(">>> sending auth");
                    ws.sendText(auth, true).join();
                }
                if (text.contains("maica_connection_established")) {
                    done.countDown();
                }
                ws.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                System.out.println("ERROR: " + error);
                done.countDown();
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                System.out.println("CLOSED: " + statusCode + " " + reason);
                done.countDown();
                return null;
            }
        };
        System.out.println("connecting " + url + " ...");
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .buildAsync(URI.create(url), listener)
                .get(25, TimeUnit.SECONDS);
        System.out.println("connected (+" + (System.currentTimeMillis() - start) + "ms)");
        boolean established = done.await(35, TimeUnit.SECONDS);
        System.out.println(established ? "OK: handshake established" : "TIMEOUT: no established frame");
        ws.abort();
    }
}
