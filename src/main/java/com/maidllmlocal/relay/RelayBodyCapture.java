package com.maidllmlocal.relay;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * 包住请求体 JSON 的 {@link HttpRequest.BodyPublisher}：行为完全委托给标准实现，另外把字符串留一份。
 *
 * <p><b>为什么需要它。</b>TLM 的 {@code LLMOpenAIClient.chat()} 把请求体写成
 * {@code HttpRequest.BodyPublishers.ofString(GSON.toJson(chatCompletion))} —— 内联的，而 {@code HttpRequest}
 * 本身<b>取不回</b> body 字符串。但 {@code httpRequest.bodyPublisher()} 取得到，于是：
 * <ul>
 *   <li>mixin 把那一行 {@code ofString} 换成这个实现；</li>
 *   <li>中继时用 {@code ((RelayBodyCapture) request.bodyPublisher()).json()} 类型安全地读回来。</li>
 * </ul>
 * 比 ThreadLocal 干净，也比反射去掏 {@code StringPublisher} 的私有字段稳。
 */
public final class RelayBodyCapture implements HttpRequest.BodyPublisher {
    private final String json;
    private final HttpRequest.BodyPublisher delegate;

    public RelayBodyCapture(String json) {
        this.json = json;
        this.delegate = HttpRequest.BodyPublishers.ofString(json);
    }

    /** 原始请求体 JSON（与 TLM 本来要 POST 出去的那份逐字节一致）。 */
    public String json() {
        return json;
    }

    @Override
    public long contentLength() {
        return delegate.contentLength();
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        delegate.subscribe(subscriber);
    }
}