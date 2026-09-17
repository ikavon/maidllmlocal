package com.maidllmlocal.account;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidllmlocal.MaidLLMLocal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * DCC 账号 → MAICA access_token（对齐 maica4tlm/auth.py，语义逐条照搬）。
 *
 * <ul>
 *   <li>节点发现：{@code GET {nameserver}} → {@code content.servers[]}，优先 {@code isOfficial}；</li>
 *   <li>换 token：{@code POST {httpBase}/register}，体 {@code {"content": {username|email, password}}}
 *       → {@code {"success": true, "content": "<token>"}}。凭据是<b>明文 HTTPS</b>——
 *       RSA 加密发生在服务端生成 token 时，客户端不需要持有公钥。</li>
 * </ul>
 *
 * <p><b>Fail2Ban 纪律</b>（服务端 20 次密码错误锁 600 秒，HTTP/WS 共享计数）：
 * 业务失败（success=false / 非 200 的应答体）立即抛出、绝不重试；只有网络/解析层错误才重试，
 * 最多 2 次。这条不是防御性编程，是真会把整个 DCC 账号的 API 访问锁进冷宫的。
 */
public final class MaicaAuthClient {

    public static final String NAMESERVER = "https://maicadev.monika.love/api/servers";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private static final Gson GSON = new Gson();

    /** nameserver 选出的节点（三件套：REST / WS / TTS）。 */
    public record Node(String name, String httpBase, String wsUrl, String ttsBase) {
    }

    /** 携带服务端原文信息的登录异常（给聊天栏 toast 用，玩家能看懂是首要目标）。 */
    public static class AuthException extends Exception {
        /** true = 服务端明确拒绝（凭据/格式问题，重试无意义）；false = 网络层耗尽。 */
        public final boolean credentialProblem;

        public AuthException(String message) {
            this(message, true);
        }

        public AuthException(String message, boolean credentialProblem) {
            super(message);
            this.credentialProblem = credentialProblem;
        }
    }

    private MaicaAuthClient() {
    }

    public static Node discover() throws AuthException {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(NAMESERVER)).timeout(REQUEST_TIMEOUT).GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                var servers = root.getAsJsonObject("content").getAsJsonArray("servers");
                if (servers.isEmpty()) {
                    throw new AuthException("nameserver returned no servers", false);
                }
                JsonObject chosen = servers.get(0).getAsJsonObject();
                for (var el : servers) {
                    JsonObject s = el.getAsJsonObject();
                    if (s.has("isOfficial") && s.get("isOfficial").getAsBoolean()) {
                        chosen = s;
                        break;
                    }
                }
                return new Node(
                        optString(chosen, "name"),
                        trimTrailingSlash(optString(chosen, "httpInterface")),
                        optString(chosen, "wsInterface"),
                        trimTrailingSlash(optString(chosen, "ttsInterface")));
            } catch (AuthException fatal) {
                throw fatal; // 协议内容错误不重试
            } catch (Exception e) {
                last = e;
                MaidLLMLocal.LOGGER.warn("maica node discovery failed (attempt {}): {}", attempt + 1, e.toString());
            }
        }
        throw new AuthException("node discovery failed: " + (last == null ? "?" : last.getMessage()), false);
    }

    /**
     * 用 DCC 凭据换 access_token。{@code account} 含 {@code @} 按 email 字段发，否则 username
     * （与适配器一致：DCC 允许用邮箱登录）。
     */
    public static String fetchToken(Node node, String account, String password) throws AuthException {
        JsonObject content = new JsonObject();
        content.addProperty(account.contains("@") ? "email" : "username", account);
        content.addProperty("password", password);
        JsonObject body = new JsonObject();
        body.add("content", content);

        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(node.httpBase() + "/register"))
                        .header("Content-Type", "application/json")
                        .timeout(REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                boolean success = root.has("success") && root.get("success").getAsBoolean()
                        && root.has("content") && root.get("content").isJsonPrimitive()
                        && !root.get("content").getAsString().isBlank();
                if (response.statusCode() == 200 && success) {
                    return root.get("content").getAsString();
                }
                // 业务失败：密码错 / ToS 未接受 / 邮箱未验证……不重试，把服务端原话带回去。
                // 5xx 是服务端自己的问题，不算凭据错误（不该拉黑这组凭据的指纹）。
                throw new AuthException("HTTP " + response.statusCode() + ": " + abbreviate(response.body()),
                        response.statusCode() < 500);
            } catch (AuthException fatal) {
                throw fatal;
            } catch (Exception e) {
                last = e;
                MaidLLMLocal.LOGGER.warn("maica register failed (attempt {}): {}", attempt + 1, e.toString());
            }
        }
        throw new AuthException("register failed (network): " + (last == null ? "?" : last.getMessage()));
    }

    /**
     * 验证 token 是否真能通过后端登录校验，返回论坛用户名。
     *
     * <p>为什么必须有这步：API 文档明说 <b>/register「不会验证登录信息是否正确」</b>
     * （v1.3 起只验格式，源码 {@code maica_http.py check_legality → wrapped_validate}
     * 注释 "It does login too"）——账号或密码错时 register 照样发一个"格式正确的错误 token"。
     * 不校验就回填，玩家会先收到假的成功提示、真正聊天时才撞 30 秒握手超时。
     * 用 GET /legality + Bearer 头做一次"只登录不开口"的核验，失败原样带回。
     */
    public static String verifyToken(Node node, String token) throws AuthException {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(node.httpBase() + "/legality"))
                        .header("Authorization", "Bearer " + token)
                        .timeout(REQUEST_TIMEOUT)
                        .GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                boolean success = response.statusCode() == 200
                        && root.has("success") && root.get("success").getAsBoolean();
                if (success) {
                    String name = root.has("content") && root.get("content").isJsonPrimitive()
                            ? root.get("content").getAsString() : "";
                    MaidLLMLocal.LOGGER.info("maica token verified, forum user: {}", name.isBlank() ? "<unknown>" : name);
                    return name;
                }
                // /legality 的失败体里 content 就是人话（如 "Invalid username/email or password"），优先透出
                String detail = root.has("content") && root.get("content").isJsonPrimitive()
                        ? root.get("content").getAsString() : abbreviate(response.body());
                throw new AuthException(detail, response.statusCode() < 500);
            } catch (AuthException fatal) {
                throw fatal;
            } catch (Exception e) {
                last = e;
                MaidLLMLocal.LOGGER.warn("maica legality check failed (attempt {}): {}", attempt + 1, e.toString());
            }
        }
        throw new AuthException("legality check failed (network): " + (last == null ? "?" : last.getMessage()));
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
    }

    private static String trimTrailingSlash(String url) {
        return url.replaceAll("/+$", "");
    }

    /** 服务端应答可能很长（HTML 错误页之类），截一下只给关键信息。 */
    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }
}
