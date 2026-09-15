package com.maidllmlocal.client.maica;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.maidllmlocal.maica.MaicaSite;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧的会话登记表：站点 id → 该站点的一条 MAICA WS 长连接（懒加载）。
 *
 * <p>url / token 一律从<b>本机</b> {@code AvailableSites} 按 id 查——服务端发过来的只有 id，
 * 这是"凭据不出本机"纪律的执行点。配置变了（玩家改了 llm.json 里的 url/token/lang）
 * 就扔掉旧会话重建，免得拿旧 token 撞墙。
 */
public final class ClientMaicaSessions {

    private static final Map<String, Entry> SESSIONS = new ConcurrentHashMap<>();

    private record Entry(String wsUrl, String token, String targetLang, MaicaWsSession session) {
    }

    private ClientMaicaSessions() {
    }

    /**
     * 取这个站点对应的会话；本机没配 / 没启用 / 不是 maica 站点 / 缺 url 或 token 时返回 null。
     */
    public static MaicaWsSession get(String siteId) {
        if (!(AvailableSites.LLM_SITES.get(siteId) instanceof LLMOpenAISite site)) {
            return null;
        }
        if (!site.enabled() || !MaicaSite.API_TYPE.equals(site.getApiType())
                || site.url().isEmpty() || site.secretKey() == null || site.secretKey().isEmpty()) {
            return null;
        }
        String targetLang = site.headers().getOrDefault("target_lang", "zh");

        Entry current = SESSIONS.get(siteId);
        if (current != null && current.wsUrl().equals(site.url())
                && current.token().equals(site.secretKey()) && current.targetLang().equals(targetLang)) {
            return current.session();
        }
        // 配置变了（或第一次）：重建。旧会话若还在连着就顺手断掉
        if (current != null) {
            current.session().close();
        }
        Entry created = new Entry(site.url(), site.secretKey(), targetLang,
                new MaicaWsSession(site.url(), site.secretKey(), targetLang));
        SESSIONS.put(siteId, created);
        return created.session();
    }

    /** 所有活会话（sping 心跳遍历用）。 */
    public static Collection<MaicaWsSession> all() {
        return SESSIONS.values().stream().map(Entry::session).toList();
    }

    /** 全部断开（退出游戏时）。 */
    public static void closeAll() {
        SESSIONS.values().forEach(entry -> entry.session().close());
        SESSIONS.clear();
    }
}
