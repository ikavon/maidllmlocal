package com.maidllmlocal.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.maidllmlocal.relay.RelayBodyCapture;
import com.maidllmlocal.relay.RelayHub;
import com.maidllmlocal.relay.RelaySite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 中继的全部机关：把 {@code LLMOpenAIClient.chat()} 里那一次 HTTP 发送改派给女仆主人的客户端。
 *
 * <h2>为什么这么小</h2>
 * TLM 的请求构造与响应处理（解析、工具循环、token 计数、气泡、TTS）全部留在原处，一个字节都不动。
 * 我们只插在两处：
 * <ol>
 *   <li>{@code BodyPublishers.ofString(json)} —— 换成 {@link RelayBodyCapture}，把请求体留一份。
 *       必须这么做，因为 1.21 的 {@code chat()} 把请求体内联掉了，而 {@code HttpRequest} 取不回 body
 *       字符串（{@code bodyPublisher()} 取得回，所以换成我们自己的实现就拿到了）。</li>
 *   <li>{@code httpClient.sendAsync(...)} —— 换成"发包给主人客户端"，并返回一个由客户端回包来完成的
 *       future。原代码的 {@code .whenComplete((r, t) -> handle(...))} 原样继续跑。</li>
 * </ol>
 *
 * <h2>失配时的行为</h2>
 * mixin 配置用 {@code defaultRequire: 1}：TLM 若重构 {@code chat()} 导致 redirect 目标找不到，
 * 游戏会<b>明确报错</b>而不是悄悄退化成"不中继"。
 */
@Mixin(LLMOpenAIClient.class)
public abstract class LLMOpenAIClientMixin {

    @Shadow
    @Final
    protected LLMOpenAISite site;

    /**
     * 本轮的 callback。{@code chat()} 从头到 {@code sendAsync} 是同步执行的，所以在这段同步区间里
     * 存一下再读出来是安全的；TLM 对同一女仆的对话本身也是串行的。
     */
    @Unique
    private LLMCallback maidllmlocal$callback;

    @Inject(method = "chat", at = @At("HEAD"))
    private void maidllmlocal$rememberCallback(LLMCallback callback, CallbackInfo ci) {
        this.maidllmlocal$callback = callback;
    }

    @Redirect(method = "chat", at = @At(value = "INVOKE",
            target = "Ljava/net/http/HttpRequest$BodyPublishers;ofString(Ljava/lang/String;)Ljava/net/http/HttpRequest$BodyPublisher;"))
    private HttpRequest.BodyPublisher maidllmlocal$captureBody(String json) {
        return new RelayBodyCapture(json);
    }

    @Redirect(method = "chat", at = @At(value = "INVOKE",
            target = "Ljava/net/http/HttpClient;sendAsync(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<HttpResponse<String>> maidllmlocal$relay(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<String> bodyHandler) {

        // 只接管 player_relay 站点；内置的 openai/deepseek/… 等一律原样放过
        if (!RelaySite.API_TYPE.equals(this.site.getApiType())) {
            return client.sendAsync(request, bodyHandler);
        }
        LLMCallback callback = this.maidllmlocal$callback;
        if (callback == null || !(request.bodyPublisher().orElse(null) instanceof RelayBodyCapture capture)) {
            return client.sendAsync(request, bodyHandler);
        }

        CompletableFuture<HttpResponse<String>> relayed = RelayHub.dispatch(
                callback.getMaid(), this.site.id(), capture.json(), request,
                () -> client.sendAsync(request, bodyHandler));

        // 返回 null = 这次不中继（主人离线 / 客户端没装或没同意 / 本机没这个站点）
        // -> 直接走服务端自己那份配置，兜底零成本
        return relayed != null ? relayed : client.sendAsync(request, bodyHandler);
    }
}