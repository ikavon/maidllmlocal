package com.maidllmlocal.relay;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

/**
 * 把客户端回传的 HTTP 结果包成 TLM 期望的 {@link HttpResponse}，让原本那行
 * {@code .whenComplete((response, throwable) -> handle(callback, response, throwable, httpRequest))}
 * 原样继续跑 —— 于是响应解析、工具循环、token 计数、气泡、TTS 全部零改动。
 *
 * <p>只要 {@code statusCode()} 落在 2xx 且 {@code body()} 是一份良构的 OpenAI ChatCompletion JSON，
 * TLM 就完全分辨不出这次请求其实是在玩家机器上发的。
 */
public record SyntheticResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (name, value) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return request.uri();
    }

    @Override
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }
}