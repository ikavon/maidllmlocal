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

    private record Entry(String wsUrl, String token, String targetLang, boolean enableMt,
                         String httpBase, MaicaWsSession session) {
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
        // MTrigger 开关：上传触发器表 + 握手下发 enable_mt + 收集触发器帧，默认关
        boolean enableMt = Boolean.parseBoolean(site.headers().getOrDefault("enable_mt", "false"));
        // REST 基地址覆盖（自建节点路径与官方不同时用）；空串 = 从 ws 地址推导
        String httpBase = site.headers().getOrDefault("http_base", "");

        Entry current = SESSIONS.get(siteId);
        if (current != null && current.wsUrl().equals(site.url())
                && current.token().equals(site.secretKey()) && current.targetLang().equals(targetLang)
                && current.enableMt() == enableMt && current.httpBase().equals(httpBase)) {
            return current.session();
        }
        // 配置变了（或第一次）：重建。旧会话若还在连着就顺手断掉
        if (current != null) {
            current.session().close();
        }
        Entry created = new Entry(site.url(), site.secretKey(), targetLang, enableMt, httpBase,
                new MaicaWsSession(site.url(), site.secretKey(), targetLang, enableMt));
        SESSIONS.put(siteId, created);
        if (enableMt) {
            // session=-1 下后端忽略 query 内联 trigger，只能用预上传的表（官方节点实测）
            MaicaTriggerUploader.uploadAsync(site.url(), site.secretKey(), httpBase);
        }
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
