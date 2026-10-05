package com.maidllmlocal.client.maica;

import com.maidllmlocal.MaidLLMLocal;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 极简的 WebSocket-over-raw-socket 实现：只做 MAICA 会话实际用到的那一部分
 * （握手 + 文本帧收发 + ping/pong + close），不碰任何业务语义。
 *
 * <h2>为什么不用 {@code java.net.http.WebSocket}</h2>
 * JDK 的 HTTP/1.1 客户端在做 WebSocket 升级握手时，会在 GET 请求里附带
 * {@code Content-Length: 0}。这个头对 WS 升级是无意义的，MAICA 的接入层
 * （nginx 前置 CDN）见到它直接回 502，握手当场失败，表现为
 * {@code WebSocketHandshakeException}，但同一台机器的 REST 接口（POST /api）完全正常。
 * 对照实验：curl 手动加上 {@code Content-Length: 0} 复现 502，去掉就是 101。
 * JDK 没有开关能关掉头部的发送，所以这里自己写握手。
 *
 * <p>协议实现严格遵循 RFC 6455 的客户端侧要求：客户端发往服务器的帧必须掩码。
 * 服务端发来的帧不掩码；ping 必须回 pong；close 帧必须回 close；文本帧可能
 * 被分片（FIN=0 + continuation），这里在内部拼完再回调，对上层暴露的仍是整条消息。
 *
 * <h2>线程模型</h2>
 * 一个连接一条只读线程（reader），它负责收帧并把整条文本回调出去；
 * 所有写操作（文本帧、pong、close）经 {@link #writeLock} 串行化，
 * 所以任意业务线程调 {@link #sendText} 都安全。业务侧读回调是拉的还是推的，
 * 由调用方自己决定——这里只保证"每条完整文本恰好回调一次，顺序与线上顺序一致"。
 */
public final class MaicaRawSocket {

    /** WebSocket 掩码魔数（RFC 6455 §4.1）。 */
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int FRAME_CONTINUATION = 0x0;
    private static final int FRAME_TEXT = 0x1;
    private static final int FRAME_BINARY = 0x2;
    private static final int FRAME_CLOSE = 0x8;
    private static final int FRAME_PING = 0x9;
    private static final int FRAME_PONG = 0xA;

    /** 单条文本消息的重拼装上限（MAICA 的一轮回复远小于此；防流控失灵的内存暴涨）。 */
    private static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    private static final String HEADER_CRLF = "\r\n";

    /** 只回调三类事件：整条文本、连接出错、连接关闭。签名刻意对齐 JDK 的 Listener。 */
    public interface Listener {
        void onText(String fullText);

        void onError(Throwable error);

        void onClose(int statusCode, String reason);
    }

    private final URI uri;
    private volatile Listener listener;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();
    private Thread reader;

    /** 分片拼装缓冲：收到未 FIN 的数据帧时积累，收到 continuation 时续接。 */
    private final StringBuilder pending = new StringBuilder();

    public MaicaRawSocket(String wsUrl) {
        this.uri = URI.create(wsUrl);
    }

    /**
     * 建 TCP/TLS 连接并完成握手；成功后才返回。握手失败抛 {@link IOException}，
     * 此时连接已被关掉，调用方不必清理。
     */
    public void connect(Listener listener, Duration timeout)
            throws IOException, NoSuchAlgorithmException {
        this.listener = listener;

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"ws".equals(scheme) && !"wss".equals(scheme)) {
            throw new IOException("unsupported ws scheme: " + scheme);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (port < 0) {
            port = "wss".equals(scheme) ? 443 : 80;
        }
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }

        Socket socket;
        if ("wss".equals(scheme)) {
            // 必须用不带参数的 createSocket()：带 (host, port) 的版本会立刻建连并做 TLS 握手，
            // 后面 socket.connect() 直接抛 SocketException("already connected")。
            // 未连接的 socket 上先设好 SNI，再 connect()，SNI 就会出现在握手里。
            // createSocket 的声明返回类型是 Socket，得强转成 SSLSocket 才能碰 SSL 参数。
            SSLSocketFactory factory = (SSLSocketFactory) SSLContext.getDefault()
                    .getSocketFactory();
            SSLSocket ssl = (SSLSocket) factory.createSocket();
            SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(host)));
            ssl.setSSLParameters(params);
            socket = ssl;
        } else {
            socket = socketWithProxy(scheme, host);
        }
        try {
            socket.connect(new InetSocketAddress(host, port), Math.toIntExact(timeout.toMillis()));
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(0); // 不超时：断线由 TCP 层报，应用层自己管读超时
            socket.setSendBufferSize(64 * 1024);

            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            sendHandshake(path, host);
            checkSwitchingProtocols(socket);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e instanceof IOException ioe ? ioe
                    : new IOException("ws connect failed", e);
        }

        reader = new Thread(() -> readLoop(), "maidllmlocal-maica-ws-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** 握手时随机生成的 key，留着校验 Sec-WebSocket-Accept 用。 */
    private String handshakeKey;

    /** HTTP 升级请求：故意不发 Content-Length（见类注释，MAICA 接入层见到它回 502）。 */
    private void sendHandshake(String path, String host) throws IOException {
        byte[] keyBytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(keyBytes);
        handshakeKey = Base64.getEncoder().encodeToString(keyBytes);

        StringBuilder request = new StringBuilder(512);
        request.append("GET ").append(path).append(" HTTP/1.1").append(HEADER_CRLF)
                .append("Host: ").append(host).append(HEADER_CRLF)
                .append("Upgrade: websocket").append(HEADER_CRLF)
                .append("Connection: Upgrade").append(HEADER_CRLF)
                .append("Sec-WebSocket-Key: ").append(handshakeKey).append(HEADER_CRLF)
                .append("Sec-WebSocket-Version: 13").append(HEADER_CRLF)
                .append("User-Agent: Java-MaidLLMLocal-WS/1.0").append(HEADER_CRLF)
                .append(HEADER_CRLF);
        writeRaw(request.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /** 读响应头到空行为止，校验 101 与 Sec-WebSocket-Accept。失败抛 IOException。 */
    private void checkSwitchingProtocols(Socket socket) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new IOException("ws handshake: 服务端直接关闭连接，未返回状态行");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        if (!statusLine.startsWith("HTTP/1.")) {
            throw new IOException("ws handshake 失败: 响应协议异常 " + statusLine);
        }
        // "HTTP/1.1 101 Switching Protocols" → 状态码是第 2 段，别去切版本号
        String[] parts = statusLine.split("\\s+");
        String status = parts.length >= 2 ? parts[1] : "";
        if (!"101".equals(status)) {
            throw new IOException("ws handshake 失败: 服务端返回 " + statusLine);
        }
        // 不校验 Accept 也能通（有的中间件不回这个头），但服务端明确回错值时必须报错——
        // 否则说明我们连到的不是同一个端点，后续帧全是乱码
        String accept = headers.get("sec-websocket-accept");
        if (accept != null && !accept.equalsIgnoreCase(expectedAccept(handshakeKey))) {
            throw new IOException("ws handshake 失败: Sec-WebSocket-Accept 不匹配");
        }
        if (socket instanceof SSLSocket ssl) {
            String alpn = ssl.getHandshakeApplicationProtocol();
            if (alpn != null) {
                MaidLLMLocal.LOGGER.debug("maica ws negotiated ALPN: {}", alpn);
            }
        }
    }

    private static String expectedAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /**
     * 主读循环：一帧一帧地读，拼完一条完整消息后回调。
     * 任何 IO 异常都视为"连接死了"，回调 onError（而不是 close），
     * 上层据此前抛异常去重连；正常 close 帧回调 onClose。
     */
    private void readLoop() {
        try {
            while (true) {
                int finAndOp = readByte();
                if (finAndOp < 0) {
                    closeQuietly();
                    emitOnClose(-1, "");
                    return;
                }
                boolean fin = (finAndOp & 0x80) != 0;
                int opcode = finAndOp & 0x0F;
                int maskAndLen = readByte();
                if ((maskAndLen & 0x80) != 0) {
                    // 服务端帧必须不掩码；见到掩码说明连到了怪东西
                    throw new IOException("ws frame from server is masked (protocol violation)");
                }
                long length = maskAndLen & 0x7F;
                if (length == 126) {
                    // 扩展长度是大端：先读到的高字节在左。写反了会把 312 读成 14337，
                    // 整条流从此错位——所有 ≥126 字节的帧（也就是 MAICA 的几乎所有回复）全废
                    length = ((readByte() & 0xFFL) << 8) | (readByte() & 0xFFL);
                } else if (length == 127) {
                    long l = 0;
                    for (int i = 0; i < 8; i++) {
                        l = (l << 8) | (readByte() & 0xFF);
                    }
                    length = l;
                }
                if (length > MAX_MESSAGE_BYTES) {
                    // 超长帧不是读一半就了事——剩下的字节会污染后续帧的解析，只能断线
                    throw new IOException("ws frame too large: " + length + " bytes");
                }
                byte[] payload = readFully((int) length);

                switch (opcode) {
                    case FRAME_TEXT, FRAME_CONTINUATION -> {
                        pending.append(new String(payload, StandardCharsets.UTF_8));
                        if (pending.length() > MAX_MESSAGE_BYTES) {
                            throw new IOException("ws message exceeded " + MAX_MESSAGE_BYTES + " bytes");
                        }
                        if (fin) {
                            String message = pending.toString();
                            pending.setLength(0);
                            emitOnText(message);
                        }
                    }
                    case FRAME_BINARY -> {
                        // MAICA 只用文本帧；二进制帧出现说明协议不对，记一条便于排查
                        MaidLLMLocal.LOGGER.warn("maica ws received binary frame ({} bytes), ignored", length);
                    }
                    case FRAME_PING -> {
                        sendControl(FRAME_PONG, payload);
                    }
                    case FRAME_PONG -> {
                        // 我们的实现从不主动 ping，收到就忽略
                    }
                    case FRAME_CLOSE -> {
                        int code = payload.length >= 2
                                ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
                        String reason = payload.length > 2
                                ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                        // 回 close 时只带状态码（控制帧载荷必须 ≤125 字节，原样回显可能超长）
                        byte[] echo = payload.length >= 2
                                ? new byte[]{payload[0], payload[1]} : new byte[0];
                        sendControl(FRAME_CLOSE, echo);
                        closeQuietly();
                        emitOnClose(code, reason);
                        return;
                    }
                    default -> throw new IOException("unknown ws opcode: 0x"
                            + Integer.toHexString(opcode));
                }
            }
        } catch (IOException e) {
            closeQuietly();
            emitOnError(e);
        }
    }

    /** 发一条客户端->服务端的文本帧（已掩码）。线程安全。 */
    public void sendText(String text) {
        if (socket == null || socket.isClosed()) {
            throw new IllegalStateException("maica ws not connected");
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                out.write(frame(FRAME_TEXT, data));
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** 发一条控制帧（pong / close）。失败不致命——控制帧丢失不该杀掉业务流。 */
    private void sendControl(int opcode, byte[] payload) {
        synchronized (writeLock) {
            try {
                out.write(frame(opcode, payload));
                out.flush();
            } catch (IOException ignored) {
                // 控制帧失败忽略
            }
        }
    }

    /** 拼一条客户端->服务端的完整帧：FIN + opcode + MASK + 长度 + 掩码键 + 掩码后的载荷。 */
    private static byte[] frame(int opcode, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 14);
        out.write(0x80 | opcode); // FIN + opcode（控制帧长度必 <126）
        byte[] mask = new byte[4];
        ThreadLocalRandom.current().nextBytes(mask);
        writeLength(out, data.length, 0x80);
        out.write(mask);
        for (int i = 0; i < data.length; i++) {
            out.write((data[i] ^ mask[i & 3]) & 0xFF);
        }
        return out.toByteArray();
    }

    /**
     * 写第二个头字节及其扩展长度字节。{@code flags} 通常是 MASK(0x80)——
     * 短帧的长度直接塞进这同一字节里，所以必须带上 flags。
     */
    private static void writeLength(ByteArrayOutputStream out, int length, int flags) throws IOException {
        if (length < 126) {
            out.write(flags | length);
        } else if (length < 65536) {
            out.write(flags | 126);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(flags | 127);
            long l = length;
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((l >> (i * 8)) & 0xFF));
            }
        }
    }

    private void writeRaw(byte[] data) throws IOException {
        synchronized (writeLock) {
            out.write(data);
            out.flush();
        }
    }

    /** 立刻断开（不等握手回复）：业务侧"这一轮不要了"的唯一姿势。 */
    public void abort() {
        if (socket != null) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 关 socket 失败没有意义
            }
        }
    }

    // ---------- 读原语 ----------

    private int readByte() throws IOException {
        int b = in.read();
        return b;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("ws connection closed mid-frame (got " + off + "/" + n + " bytes)");
            }
            off += r;
        }
        return buf;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
        }
        if (b < 0 && buf.size() == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    // ---------- 回调派发 ----------

    private void emitOnText(String text) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onText(text);
            } catch (Throwable t) {
                MaidLLMLocal.LOGGER.warn("maica ws onText listener failed", t);
            }
        }
    }

    private void emitOnError(Throwable error) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onError(error);
            } catch (Throwable t) {
                // 回调里抛错也不该杀读者线程
            }
        }
    }

    private void emitOnClose(int code, String reason) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onClose(code, reason);
            } catch (Throwable t) {
                // 同上
            }
        }
    }

    /**
     * 按系统代理配置建 socket。非 wss 走 HTTP CONNECT 隧道（与 JDK HttpClient 的默认行为对齐）；
     * wss 分支不会进来——{@code SSLSocketFactory.wrapSocket} 是 protected，反射又会被强封装拦，
     * 为了「TLS 走代理」不值得，所以 wss 一律直连。
     */
    private static Socket socketWithProxy(String scheme, String host) {
        Proxy proxy = null;
        String prop = "wss".equals(scheme) ? "https.proxyHost" : "http.proxyHost";
        String hostStr = System.getProperty(prop);
        if (hostStr == null || hostStr.isEmpty()) {
            hostStr = System.getProperty("http.proxyHost");
        }
        if (hostStr != null && !hostStr.isEmpty() && !nonProxyMatch(host)) {
            String portProp = "wss".equals(scheme) ? "https.proxyPort" : "http.proxyPort";
            String portStr = System.getProperty(portProp);
            int port = portStr == null ? 8080 : Integer.parseInt(portStr);
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(hostStr, port));
        }
        return proxy == null ? new Socket() : new Socket(proxy);
    }

    private static boolean nonProxyMatch(String host) {
        String pattern = System.getProperty("http.nonProxyHosts", "");
        for (String entry : pattern.split("|")) {
            if (entry.trim().equalsIgnoreCase(host) || entry.trim().equalsIgnoreCase("*")) {
                return true;
            }
        }
        return false;
    }
}
