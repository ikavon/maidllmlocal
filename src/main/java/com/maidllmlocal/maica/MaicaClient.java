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
        EntityMaid maid = callback.getMaid();
        // 玩家名贯穿三处：输入宏展开、输出占位符替换、write_memory 落盘前清洗
        String playerName = maid.getOwner() != null ? maid.getOwner().getName().getString() : "";
        String messagesJson;
        try {
            List<LLMMessage> history = callback.getMessages();
            if (hosted()) {
                // 托管模式：后端丢弃 system（骨架提示词覆盖），人设/记忆/场景全部
                // 改挂到最后一条 user 消息正文——客户端只取这一条发出（见 CROSSFRONTEND.md L2）
                history = withSceneWrap(maid, history);
            } else if (enableMt()) {
                history = withContextInjection(maid, history);
            }
            // 输入侧展开：MAS 人设卡常带 {player_name}，发出去前换成真名，模型不必再见宏
            messagesJson = MaicaMessages.toJson(expandPlayerMacros(history, playerName));
        } catch (Throwable t) {
            fail(callback, t);
            return;
        }

        CompletableFuture<MaicaRoundResult> future = MaicaRelayHub.dispatch(maid, site.id(), messagesJson);
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
            // 情绪清洗与玩家占位符替换统一在服务端做：客户端只回原文，逻辑只有一份
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
                    MaicaTriggers.apply(maid, result.triggers(), playerName);
                } else if (!result.triggers().isEmpty()) {
                    MaidLLMLocal.LOGGER.warn("dropped {} maica trigger(s): server-side site {} lacks enable_mt in headers",
                            result.triggers().size(), site.id());
                }
                callback.onSuccess(new ResponseChat(text));
            });
        });
    }

    /** 把消息正文里的玩家占位符（[player] / {player_name} 两族）换成真名。只动发送副本。 */
    private static List<LLMMessage> expandPlayerMacros(List<LLMMessage> history, String playerName) {
        if (playerName.isEmpty()) {
            return history;
        }
        List<LLMMessage> out = new ArrayList<>(history.size());
        for (LLMMessage msg : history) {
            out.add(new LLMMessage(msg.role(), MaicaText.replacePlayer(msg.message(), playerName), msg.gameTime()));
        }
        return out;
    }

    /**
     * 服务端本机站点 headers 里的 MTrigger 开关：控制<b>注入与执行</b>。
     * 客户端那份同名 headers 控制上传与收集——服务器管理员对「AI 动女仆实体」有最终否决权。
     */
    private boolean enableMt() {
        return Boolean.parseBoolean(site.headers().getOrDefault("enable_mt", "false"));
    }

    /**
     * 服务端本机站点 headers 里的 chat_session：≥0 时走托管模式的注入路径。
     * 注意这是<b>服务端</b>站点的 headers——玩家客户端那份同名配置控制连接行为，
     * 两边要设成同一个号（设计文档「实验 A/B」）。
     */
    private boolean hosted() {
        try {
            return Integer.parseInt(site.headers().getOrDefault("chat_session", "-1").trim()) >= 0;
        } catch (NumberFormatException bad) {
            return false;
        }
    }

    /**
     * 托管模式的注入：场景事实（{@link MaicaScene}）+ 女仆 NBT 里的长期记忆，
     * 都并进最后一条 user 消息。只改发送副本，不写回 TLM 历史。
     */
    private static List<LLMMessage> withSceneWrap(EntityMaid maid, List<LLMMessage> history) {
        String memory = MaicaMemory.promptBlock(maid);
        List<LLMMessage> copy = new ArrayList<>(history.size());
        boolean wrapped = false;
        // 倒序找最后一条 user，包好后前面的原样保留（客户端只会取这一条发）
        for (int i = history.size() - 1; i >= 0; i--) {
            LLMMessage msg = history.get(i);
            if (!wrapped && msg.role() == Role.USER) {
                String text = MaicaScene.wrap(maid, msg.message());
                if (!memory.isEmpty()) {
                    text = text + "\n\n" + memory;
                }
                copy.add(0, new LLMMessage(msg.role(), text, msg.gameTime()));
                wrapped = true;
                continue;
            }
            copy.add(0, msg);
        }
        return copy;
    }

    /**
     * 把本机托管的上下文（长期记忆 + 当前好感度）拼成文本，<b>并入</b>人设 system 消息的正文。
     *
     * <p>为什么不是"再插一条 system 消息"：MAICA 后端对 query 的校验是
     * "System message must be at the beginning"——全列表只允许位于开头的那一条 system。
     * 多插一条（哪怕紧挨着人设）整轮直接 400（2026-09-17 实测）。所以合并进唯一的人设
     * system 消息；历史里若没有 system 人设，才把注入块作为单独一条放在开头。
     *
     * <p>只改这一轮的发送副本，不写回 TLM 历史——否则历史里会越攒越多条重复的记忆。
     */
    private static List<LLMMessage> withContextInjection(EntityMaid maid, List<LLMMessage> history) {
        String block = MaicaMemory.promptBlock(maid);
        if (block.isEmpty()) {
            return history;
        }
        List<LLMMessage> copy = new ArrayList<>(history.size());
        boolean merged = false;
        for (LLMMessage msg : history) {
            if (!merged && (msg.role() == Role.SYSTEM || msg.role() == Role.DEVELOPER)) {
                copy.add(new LLMMessage(msg.role(), msg.message() + "\n\n" + block, msg.gameTime()));
                merged = true;
                continue;
            }
            copy.add(msg);
        }
        if (!merged) {
            copy.add(0, new LLMMessage(Role.SYSTEM, block, maid.level().getGameTime()));
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
