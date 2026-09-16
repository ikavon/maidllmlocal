package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidllmlocal.MaidLLMLocal;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * MAICA 站点的 LLMClient：不自己发任何网络请求——把这一轮对话<b>中继给女仆主人的客户端</b>，
 * 由客户端用它本机的 MAICA 账号（wss + access_token）跑完，文本回传后在此完成 callback。
 *
 * <p>为什么不在服务端直连 WS：MAICA 是<b>单账号单连接</b>（新登录踢旧会话），且每玩家要用
 * 各自的账号——token 不出玩家本机是本模组的根本纪律。服务端这个类因而只是个"调度器"。
 *
 * <p>TLM 的其余环节（气泡、TTS、历史落 NBT）全部由 {@code callback.onSuccess} 之后的
 * 原有代码驱动，本类不需要知道它们的存在。
 */
public class MaicaClient implements LLMClient {
    private final MaicaSite site;

    public MaicaClient(MaicaSite site) {
        this.site = site;
    }

    @Override
    public void chat(LLMCallback callback) {
        String messagesJson;
        try {
            List<LLMMessage> history = callback.getMessages();
            if (enableMt()) {
                history = withContextInjection(callback.getMaid(), history);
            }
            messagesJson = MaicaMessages.toJson(history);
        } catch (Throwable t) {
            fail(callback, t);
            return;
        }

        CompletableFuture<MaicaRoundResult> future = MaicaRelayHub.dispatch(callback.getMaid(), site.id(), messagesJson);
        if (future == null) {
            fail(callback, new RuntimeException(
                    "owner client unavailable for maica site " + site.id()
                            + " (offline, mod missing, or no matching local site)"));
            return;
        }

        future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                fail(callback, throwable);
                return;
            }
            // 情绪清洗与 [player] 替换统一在服务端做：客户端只回原文，逻辑只有一份
            String playerName = callback.getMaid().getOwner() != null
                    ? callback.getMaid().getOwner().getName().getString() : "";
            String text = MaicaText.stripTags(MaicaText.replacePlayer(result.text(), playerName));
            if (text.isEmpty()) {
                fail(callback, new RuntimeException("maica reply empty after emotion stripping"));
                return;
            }
            // 情绪标签摘下后存一笔，TTS 那一轮（拿到的是无标签文本）还能取回主导情绪
            MaicaEmotionCache.put(text, MaicaText.dominantEmotion(result.text()));
            callback.runOnServerThread(() -> {
                // MTrigger 落地（好感度/记忆/换任务）后再出气泡：文本与动作同一拍呈现
                if (enableMt()) {
                    MaicaTriggers.apply(callback.getMaid(), result.triggers());
                } else if (!result.triggers().isEmpty()) {
                    MaidLLMLocal.LOGGER.warn("dropped {} maica trigger(s): server-side site {} lacks enable_mt in headers",
                            result.triggers().size(), site.id());
                }
                callback.onSuccess(new ResponseChat(text));
            });
        });
    }

    /**
     * 服务端本机站点 headers 里的 MTrigger 开关：控制<b>注入与执行</b>。
     * 客户端那份同名 headers 控制上传与收集——服务器管理员对「AI 动女仆实体」有最终否决权。
     */
    private boolean enableMt() {
        return Boolean.parseBoolean(site.headers().getOrDefault("enable_mt", "false"));
    }

    /**
     * 把本机托管的上下文（长期记忆 + 当前好感度）拼成一条 system 消息，插到人设消息之后。
     * 只改这一轮的发送副本，不写回 TLM 历史——否则历史里会越攒越多条重复的记忆。
     */
    private static List<LLMMessage> withContextInjection(EntityMaid maid, List<LLMMessage> history) {
        String block = MaicaMemory.promptBlock(maid);
        if (block.isEmpty()) {
            return history;
        }
        LLMMessage context = new LLMMessage(Role.SYSTEM, block, maid.level().getGameTime());
        List<LLMMessage> copy = new ArrayList<>(history.size() + 1);
        boolean inserted = false;
        for (LLMMessage msg : history) {
            if (!inserted && msg.role() != Role.SYSTEM && msg.role() != Role.DEVELOPER) {
                copy.add(context);
                inserted = true;
            }
            copy.add(msg);
        }
        if (!inserted) {
            copy.add(context);
        }
        return copy;
    }

    private void fail(LLMCallback callback, Throwable throwable) {
        MaidLLMLocal.LOGGER.debug("maica chat failed: {}", throwable.toString());
        callback.runOnServerThread(() -> callback.onFailure(dummyRequest(), throwable, 0));
    }

    /**
     * {@code onFailure} 的签名要一个 HttpRequest，但本客户端根本没发 HTTP。
     * TLM 只拿它记日志，给一个指向站点主机（https 化，wss 不是合法 HttpRequest scheme）的哑请求。
     */
    private HttpRequest dummyRequest() {
        String url = site.url().replaceFirst("^wss?://", "https://");
        try {
            return HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(1)).GET().build();
        } catch (Throwable t) {
            return HttpRequest.newBuilder().uri(URI.create("https://maicadev.monika.love/"))
                    .timeout(Duration.ofSeconds(1)).GET().build();
        }
    }
}
