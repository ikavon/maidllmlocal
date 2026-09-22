package com.maidllmlocal.client.maica;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.account.MaicaAccountConfig;
import com.maidllmlocal.account.MaicaAuthClient;
import com.maidllmlocal.client.ClientRelayHandler;
import com.maidllmlocal.client.ServerMtState;
import com.maidllmlocal.maica.MaicaSite;
import com.maidllmlocal.maica.MttsSite;
import com.mojang.serialization.JsonOps;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端自动登录：玩家把 DCC 账号密码写进 {@code config/maidllmlocal/maica_account.json}，
 * 进世界时模组自己去换 access_token，回填到<b>本机</b>的 llm.json / tts.json，
 * 然后重新向服务端上报中继能力 —— 全程不需要玩家跑脚本、不需要懂 JSON 字段在哪、
 * 更不需要 OP 权限（写的是自己游戏目录的文件）。
 *
 * <h2>"配置里根本没有 maica 条目"也能完整成立</h2>
 * 两层保险：① 装了本模组后，TLM 的 {@code AvailableSites.init()} 会把已注册 serializer 的
 * defaultSite 自动写进两端内存和文件（{@code addDefaultSites→saveSites}）；② 即便文件里
 * 仍缺条目（玩家手删、或 TLM 将来改掉那个回写副作用），回填时直接用 serializer 的 codec
 * 完整创建该条目——与 TLM 自己写盘同一条编码路径，形状必然可被读回。
 * 内存条目则一定存在（① 的内存部分不受文件影响），能力上报与聊天永远不缺料。
 *
 * <h2>守住的纪律</h2>
 * <ul>
 *   <li><b>先验证、后回填</b>：/register 只加密不校验凭据（API 文档原话"不会验证登录信息是否正确"），
 *       密码错照样发 token。所以拿到 token 必须先过一遍 /legality 真登录，成功才回填+报喜；
 *       被拒则把服务端原话与处置建议直接打给玩家，且记住凭据指纹，本会话不再拿同一份错误
 *       凭据反复撞 auth（Fail2Ban 计数是 HTTP/WS 共享的）。</li>
 *   <li><b>手工配置优先</b>：本机 maica 站点已有 secret_key 就完全不插手。自动登录只填"空位"，
 *       永远不覆盖玩家（或管理员）写过的东西。</li>
 *   <li><b>最小写盘</b>：条目已存在时原位只改 {@code secret_key}/{@code enabled}（外加账号文件
 *       明确要求的 headers 键），其余站点、其余字段连格式都不碰；只有条目缺失才整条创建。
 *       不重走 TLM 的整表回写，就没有互相踩脚。</li>
 *   <li><b>Fail2Ban</b>：一次进服至多一次 register 尝试（网络层重试在
 *       {@link MaicaAuthClient} 里封顶 2 次），失败绝不循环。</li>
 * </ul>
 *
 * <p>专用服务端的服务器进程不跑这段（纯客户端事件）；服务端 llm.json 本来就不需要 token，
 * 站点开不开放仍由管理员决定 —— 这里把玩家的客户端条目 enable 只影响"本机愿意参与中继"
 * 的能力上报，单人模式下同一进程文件即服务端配置，正是省心所在。
 */
@OnlyIn(Dist.CLIENT)
public final class MaicaAutoLogin {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** 本游戏会话内被服务端明确拒绝过的凭据指纹（防止反复进世界累积 Fail2Ban 计数）。 */
    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_REJECTED =
            new java.util.concurrent.atomic.AtomicReference<>("");

    private MaicaAutoLogin() {
    }

    /** 进世界时：从磁盘读配置，交给 {@link #apply}。 */
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        MaicaAccountConfig config = MaicaAccountConfig.readOrTemplate();
        if (config == null) {
            return;
        }
        apply(config);
    }

    /**
     * 应用一份配置：已有 token 就只同步 headers，没有就走登录换 token。
     *
     * <p><b>必须主线程调用</b> —— 开头要读 {@code AvailableSites} 的站点表（普通
     * {@code LinkedHashMap}，非线程安全），后面还要替换站点对象。
     *
     * <p>公开出来是给<b>游戏内设置界面</b>用的：界面上点【登录】不必重进世界，靠的就是这条路。
     * 与 {@link #onLogin} 的唯一区别是配置来自参数（界面里的内存值）而不是磁盘。
     */
    public static void apply(MaicaAccountConfig config) {
        Minecraft minecraft = Minecraft.getInstance();
        // 主线程上把所有对 AvailableSites（普通 LinkedHashMap，非线程安全）的读做完，
        // 后台只碰这些快照与网络/磁盘。
        if (!(AvailableSites.LLM_SITES.get(MaicaSite.API_TYPE) instanceof LLMOpenAISite llmSite)) {
            return; // 理论不发生：注册的 serializer 必然带出 defaultSite（见类注释）
        }
        TTSSite rawTts = AvailableSites.TTS_SITES.get(MttsSite.API_TYPE);
        if (!llmSite.secretKey().isEmpty()) {
            // 已有 token（自动登录的成果或手工配置）→ 不碰登录，但账号文件里显式给出的
            // target_lang/enable_mt 仍要能生效——否则"换到 token 后删密码"的玩家永远同步不了
            // 这两个键（登录路径要求 hasLogin，这里不要求）。
            syncHeadersIfChanged(llmSite, config);
            return; // 手工配置优先
        }
        if (!config.hasLogin()) {
            return;
        }
        String username = config.username().trim();
        // 手填 JSON 时密码带上首尾空格几乎都是误输入，trim 更符合直觉（README 有注）
        String password = config.password().trim();
        String fingerprint = sha256(username + "\n" + password);
        if (fingerprint.equals(LAST_REJECTED.get())) {
            // 必须在 RUNNING 占位之前返回，否则一次跳过会把旗标卡死
            MaidLLMLocal.LOGGER.warn("maica auto login: 同一份凭据本会话已被服务端拒绝过，跳过重试"
                    + "（省 Fail2Ban 计数）；改完账号再试即可");
            // 必须让玩家看见：否则在设置界面点【登录】会毫无反应，比手改文件还困惑
            tell(Component.translatable("maidllmlocal.screen.login.rejected"), false);
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        MaidLLMLocal.LOGGER.info("maica auto login: registering for {} ...",
                username.contains("@") ? "<email>" : username);

        Util.backgroundExecutor().execute(() -> {
            try {
                MaicaAuthClient.Node node = MaicaAuthClient.discover();
                String token = MaicaAuthClient.fetchToken(node, username, password);
                // register 不校验凭据（API 文档明示，v1.3 起只验格式）——先过一遍 /legality
                // 真登录，失败就别回填、别报成功，把服务端原因原样告诉玩家。
                String forumUser = MaicaAuthClient.verifyToken(node, token);

                Map<String, String> mergedHeaders = new LinkedHashMap<>(llmSite.headers());
                if (config.targetLang() != null && !config.targetLang().isBlank()) {
                    mergedHeaders.put("target_lang", config.targetLang().trim());
                }
                Boolean mt = resolveEnableMt(config, llmSite.id());
                if (mt != null) {
                    mergedHeaders.put("enable_mt", String.valueOf(mt));
                }
                // 两个新站点对象在主线程外构造（只读快照 + 纯构造器），主线程只做 map 替换
                MaicaSite newLlm = new MaicaSite(llmSite.id(), llmSite.icon(), llmSite.url(),
                        true, token, llmSite.hasThinkingField(), mergedHeaders, llmSite.modelEntries());
                MttsSite newTts = rawTts instanceof MttsSite mtts
                        ? new MttsSite(mtts.id(), mtts.icon(), mtts.url(), true, token, mtts.headers())
                        : null;

                minecraft.execute(() -> {
                    AvailableSites.LLM_SITES.put(newLlm.id(), newLlm);
                    if (newTts != null) {
                        AvailableSites.TTS_SITES.put(newTts.id(), newTts);
                    }
                    ClientRelayHandler.resync();
                    tell(Component.translatable("maidllmlocal.autologin.ok",
                            forumUser.isBlank() ? node.name() : forumUser), true);
                    MaidLLMLocal.LOGGER.info(
                            "maica auto login: token written into local sites (node: {}, user: {}). "
                                    + "密码此后没有用途，可以从 maica_account.json 里删掉。",
                            node.name(), forumUser.isBlank() ? "<unknown>" : forumUser);
                });
                patchSiteFiles(newLlm, newTts);
            } catch (Exception e) {
                boolean credential = e instanceof MaicaAuthClient.AuthException ae && ae.credentialProblem;
                if (credential) {
                    LAST_REJECTED.set(fingerprint);
                }
                MaidLLMLocal.LOGGER.warn("maica auto login failed: {}", e.getMessage());
                String reason = e instanceof MaicaAuthClient.AuthException ? e.getMessage() : e.toString();
                minecraft.execute(() -> {
                    tell(Component.translatable("maidllmlocal.autologin.failed", reason), false);
                    if (credential) {
                        tell(Component.translatable("maidllmlocal.autologin.hint"), false);
                    }
                });
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /**
     * token 已存在时的轻量同步：账号文件里<b>显式给出</b>的 target_lang/enable_mt 若与站点
     * headers 不一致 → 换内存对象 + 原位回写文件，重新进一次世界即生效。
     *
     * <p>不要求密码存在（换到 token 后删密码是文档推荐姿势）；也不动 token/enabled/url——
     * 只同步这两个键，玩家手工设置的其余一切原样保留。enableMt 变化会让
     * {@code ClientMaicaSessions} 在下次取会话时自动重建（Entry 缓存键含 enableMt）。
     */
    private static void syncHeadersIfChanged(LLMOpenAISite site, MaicaAccountConfig config) {
        Map<String, String> current = site.headers();
        Map<String, String> desired = new LinkedHashMap<>();
        String lang = config.targetLang() == null ? "" : config.targetLang().trim();
        if (!lang.isEmpty() && !lang.equals(current.get("target_lang"))) {
            desired.put("target_lang", lang);
        }
        Boolean mt = resolveEnableMt(config, site.id());
        if (mt != null && !String.valueOf(mt).equals(current.getOrDefault("enable_mt", "false"))) {
            desired.put("enable_mt", String.valueOf(mt));
        }
        if (!desired.isEmpty()) {
            writeSiteHeaders(site, desired);
        }
    }

    /**
     * 某个站点最终该用的 {@code enable_mt}：账号文件显式值 → 服务端跟随 → 不动。
     *
     * <ol>
     *   <li>账号文件<b>显式</b>写了 {@code true}/{@code false} → 用它（{@code false} 就是 opt-out，
     *       服务端开着也不跟）</li>
     *   <li>留空（{@code null}）→ 用服务端下发的值（<b>跟随</b>，见 {@link ServerMtState}）</li>
     *   <li>服务端状态未知（服务端没装本模组 / 旧客户端 / 还没收到 ack）→ {@code null}，
     *       调用方<b>什么都不写</b>：保持既有的"默认关"，绝不凭空把开关打开</li>
     * </ol>
     */
    private static Boolean resolveEnableMt(MaicaAccountConfig config, String siteId) {
        if (config.enableMt() != null) {
            return config.enableMt();
        }
        return ServerMtState.get(siteId);
    }

    /**
     * 服务端状态到手（或变化）后重跑一次轻量同步。
     *
     * <p><b>为什么要重跑</b>：登录时的同步（{@code onLogin}）与这个 ack 是两条独立的时序 ——
     * ack 要等一个网络往返，多半晚于同步。所以进来时先跟一次、状态到了再跟一次，
     * 幂等设计下后到者生效即可（代价最多是"进服后第一次聊天仍按旧值"，下一轮自愈）。
     */
    public static void onServerStateUpdated() {
        if (!(AvailableSites.LLM_SITES.get(MaicaSite.API_TYPE) instanceof LLMOpenAISite llmSite)) {
            return;
        }
        MaicaAccountConfig config = MaicaAccountConfig.readOrTemplate();
        if (config == null) {
            return;
        }
        syncHeadersIfChanged(llmSite, config);
    }

    /** 设置界面用：当前站点 headers 的只读快照（预填 session 等字段）。主线程调用。 */
    public static Map<String, String> siteHeaders() {
        return AvailableSites.LLM_SITES.get(MaicaSite.API_TYPE) instanceof LLMOpenAISite site
                ? Map.copyOf(site.headers()) : Map.of();
    }

    /**
     * 设置界面用：合并写入站点 headers（内存 + 本机 llm.json）。主线程调用。
     *
     * <p>空白值被忽略（清空某个 header 请用 {@link #clearToken} 那类明确操作，
     * 免得"输入框空着"被误当成"要删掉这个键"）。
     */
    public static void setSiteHeaders(Map<String, String> updates) {
        if (!(AvailableSites.LLM_SITES.get(MaicaSite.API_TYPE) instanceof LLMOpenAISite llmSite)) {
            return;
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        updates.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                filtered.put(k, v.trim());
            }
        });
        if (!filtered.isEmpty()) {
            writeSiteHeaders(llmSite, filtered);
        }
    }

    /**
     * 设置界面用：清除本机 token（退出登录）。LLM 与 TTS 两个站点一起清，
     * <b>账号文件保留</b> —— 下次点【登录】还能拿回来。
     *
     * <p>刻意<b>不</b>清凭据指纹黑名单：那个的存在意义就是别拿同一份错凭据反复撞 auth
     * （Fail2Ban 计数）；玩家改过账号后指纹自然变化，无需在这里放行。
     */
    public static void clearToken() {
        if (!(AvailableSites.LLM_SITES.get(MaicaSite.API_TYPE) instanceof LLMOpenAISite llmSite)) {
            return;
        }
        MaicaSite cleared = new MaicaSite(llmSite.id(), llmSite.icon(), llmSite.url(),
                llmSite.enabled(), "", llmSite.hasThinkingField(), llmSite.headers(), llmSite.modelEntries());
        AvailableSites.LLM_SITES.put(cleared.id(), cleared);
        Path sitesDir = FMLPaths.CONFIGDIR.get().resolve("touhou_little_maid").resolve("sites");
        JsonElement llmEntry = encodeSiteEntry(SerializerRegister.getLLMSerializer(cleared.getApiType()), cleared);
        Util.backgroundExecutor().execute(() -> patchSiteFile(sitesDir.resolve("llm.json"), cleared.id(), llmEntry));

        if (AvailableSites.TTS_SITES.get(MttsSite.API_TYPE) instanceof MttsSite mtts) {
            MttsSite clearedTts = new MttsSite(mtts.id(), mtts.icon(), mtts.url(), mtts.enabled(), "", mtts.headers());
            AvailableSites.TTS_SITES.put(clearedTts.id(), clearedTts);
            JsonElement ttsEntry = encodeSiteEntry(SerializerRegister.getTTSSerializer(clearedTts.getApiType()), clearedTts);
            Util.backgroundExecutor().execute(() -> patchSiteFile(sitesDir.resolve("tts.json"), clearedTts.id(), ttsEntry));
        }
        ClientRelayHandler.resync();
        MaidLLMLocal.LOGGER.info("maica: 已按玩家要求清除本机 token（账号文件保留）");
    }

    /**
     * 改 headers 的<b>唯一姿势</b>：造新站点对象换回注册表 + 原位回写文件。
     *
     * <p>TLM 的 {@code LLMOpenAISite} 没有 {@code setHeaders}，只能整个换对象。
     * 写盘走 {@link #patchSiteFile}（只碰目标站点、headers 合并语义、其他站点一字不动）。
     */
    private static void writeSiteHeaders(LLMOpenAISite site, Map<String, String> updates) {
        Map<String, String> merged = new LinkedHashMap<>(site.headers());
        merged.putAll(updates);
        MaicaSite synced = new MaicaSite(site.id(), site.icon(), site.url(), site.enabled(),
                site.secretKey(), site.hasThinkingField(), merged, site.modelEntries());
        AvailableSites.LLM_SITES.put(synced.id(), synced);
        MaidLLMLocal.LOGGER.info("maica auto login: 同步站点 headers {} -> {}", site.headers(), merged);
        JsonElement entry = encodeSiteEntry(SerializerRegister.getLLMSerializer(synced.getApiType()), synced);
        Path llmFile = FMLPaths.CONFIGDIR.get()
                .resolve("touhou_little_maid").resolve("sites").resolve("llm.json");
        Util.backgroundExecutor().execute(() -> patchSiteFile(llmFile, synced.id(), entry));
    }

    /** 回填本机两份站点文件（后台线程调用；TLM 的整表回写与此互不相干）。 */
    private static void patchSiteFiles(MaicaSite llm, MttsSite tts) {
        Path sitesDir = FMLPaths.CONFIGDIR.get().resolve("touhou_little_maid").resolve("sites");
        patchSiteFile(sitesDir.resolve("llm.json"), llm.id(),
                encodeSiteEntry(SerializerRegister.getLLMSerializer(llm.getApiType()), llm));
        if (tts != null) {
            patchSiteFile(sitesDir.resolve("tts.json"), tts.id(),
                    encodeSiteEntry(SerializerRegister.getTTSSerializer(tts.getApiType()), tts));
        }
    }

    /** 与 TLM {@code writeSites} 同款：serializer codec 编码 + 补 api_type 键。编码对象已含 token/enabled/headers。 */
    private static <T extends Site> JsonElement encodeSiteEntry(SerializableSite<T> serializer, T site) {
        try {
            JsonElement json = serializer.codec().encodeStart(JsonOps.INSTANCE, site)
                    .resultOrPartial(err -> MaidLLMLocal.LOGGER.warn("maica auto login: encode failed: {}", err))
                    .orElse(null);
            if (json == null || !json.isJsonObject()) {
                return null;
            }
            json.getAsJsonObject().addProperty("api_type", site.getApiType());
            return json;
        } catch (Exception e) {
            MaidLLMLocal.LOGGER.warn("maica auto login: encode site failed", e);
            return null;
        }
    }

    private static void patchSiteFile(Path file, String siteId, JsonElement fullEntry) {
        try {
            if (!Files.isRegularFile(file)) {
                MaidLLMLocal.LOGGER.info("maica auto login: {} 不存在，跳过文件回填（本次会话内存配置仍生效）", file);
                return;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            boolean created = false;
            JsonObject site;
            if (root.has(siteId) && root.get(siteId).isJsonObject()) {
                // 原位只改我们带来的键——玩家的 url/图标/模型等选择保持原样
                site = root.getAsJsonObject(siteId);
                JsonObject src = fullEntry.getAsJsonObject();
                site.addProperty("secret_key", src.get("secret_key").getAsString());
                // 取传入条目的值（登录路径=开；headers 同步路径=保留玩家本机现有开关）
                site.addProperty("enabled", src.get("enabled").getAsBoolean());
                if (src.has("headers") && src.get("headers").isJsonObject()) {
                    JsonObject headers = site.has("headers") && site.get("headers").isJsonObject()
                            ? site.getAsJsonObject("headers") : new JsonObject();
                    src.getAsJsonObject("headers").entrySet()
                            .forEach(e -> headers.add(e.getKey(), e.getValue()));
                    site.add("headers", headers);
                }
            } else if (fullEntry != null && fullEntry.isJsonObject()) {
                // 条目缺失（被手删/文件更旧）：整条创建。TLM 自己的 init 也会把 defaultSite
                // 补回内存并回写文件，"删了"从来就不是一个能持久的表态，这里不再装作尊重。
                root.add(siteId, fullEntry.deepCopy());
                created = true;
            } else {
                MaidLLMLocal.LOGGER.info("maica auto login: {} 里没有 \"{}\" 条目且无法编码，跳过",
                        file.getFileName(), siteId);
                return;
            }
            Files.writeString(file, PRETTY.toJson(root), StandardCharsets.UTF_8);
            if (created) {
                MaidLLMLocal.LOGGER.info("maica auto login: 已在 {} 中创建完整站点条目 \"{}\"",
                        file.getFileName(), siteId);
            }
        } catch (Exception e) {
            // 写文件失败不影响本次会话（内存已生效），但要说清楚，免得下次进世界又"莫名"要登录
            MaidLLMLocal.LOGGER.warn("maica auto login: 回填 {} 失败", file, e);
        }
    }

    private static void tell(Component message, boolean actionBar) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, actionBar);
        }
    }

    /** 凭据指纹（不落明文；只为识别"同一份没改过的错误配置"）。 */
    private static String sha256(String s) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode()); // SHA-256 恒在，这条只是形式上的兜底
        }
    }
}
