package com.maidllmlocal.client.gui;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.maidllmlocal.MaidLLMLocal;
import com.maidllmlocal.account.MaicaAccountConfig;
import com.maidllmlocal.client.ServerMtState;
import com.maidllmlocal.client.maica.MaicaAutoLogin;
import com.maidllmlocal.maica.MaicaSite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MAICA 设置界面（{@code /maidllmlocal set} 打开）。
 *
 * <h2>它管的是"玩家自己那一份"</h2>
 * 账号密码 / 语言 / MTrigger 写进 {@code config/maidllmlocal/maica_account.json}，
 * 会话号写进<b>本机</b> {@code llm.json} 的 maica 站点 headers。都是玩家自己游戏目录里的东西，
 * 所以<b>不需要 OP、也不需要服务端装本模组</b> —— 这正是它必须独立于 TLM 站点编辑器的原因
 * （那个走 {@code GameModeUtil.canEditSite}，专用服务器要 OP2）。
 *
 * <h2>作用域边界（状态面板如实写出）</h2>
 * {@code enable_mt} / {@code chat_session} 是<b>双端</b>语义：服务端那半由服务端自己的 llm.json 决定。
 * <ul>
 *   <li>单人模式：同进程共享同一份站点表与文件 → 改完两端立即生效，不必重进世界；</li>
 *   <li>专用服务器：本界面只能改玩家这一半，服务端那半要管理员改（面板会明说）。</li>
 * </ul>
 *
 * <h2>三处刻意的设计</h2>
 * <ol>
 *   <li><b>草稿态跨 {@code init()} 存活</b>：窗口缩放会让 {@code init()} 重跑并重建控件，
 *       若不留一份在字段里，玩家正在输入的账号会被清掉。输入先落到 {@code draft*}，
 *       控件从草稿初始化、输入时写回草稿。</li>
 *   <li><b>【保存】与【登录】分开</b>：保存只落盘；登录才真的去换 token。已有 token 时登录
 *       <b>不会</b>覆盖它（手工配置优先），要换账号请先【清除 token】—— 免得"点一下登录、
 *       失败了、原来的 token 也没了"。</li>
 *   <li><b>MTrigger 是三态</b>：跟随服务端 / 强制开 / 强制关。中间那档（跟随）是新玩家的默认，
 *       服务端开它就开，从"要配的开"降为"想关才配"（opt-out）。</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public class MaicaConfigScreen extends Screen {

    private static final String[] LANGS = {"", "zh", "en"};
    /** MTrigger 三态：null = 跟随服务端 / true = 强制开 / false = 强制关（opt-out）。 */
    private static final Boolean[] MT_STATES = {null, Boolean.TRUE, Boolean.FALSE};

    private final Screen parent;

    // ---- 草稿态（跨 init 存活）----
    private boolean loaded;
    private String draftUsername = "";
    private String draftPassword = "";
    private String draftLang = "";
    private Boolean draftMt;
    private String draftSession = "-1";

    private EditBox usernameBox;
    private EditBox passwordBox;
    private EditBox sessionBox;

    private final List<Component> status = new ArrayList<>();

    public MaicaConfigScreen(Screen parent) {
        super(Component.translatable("maidllmlocal.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        if (!loaded) {
            // 首次打开：用磁盘/站点里的现状做初值。之后 init 重跑（缩放窗口）保留草稿
            loaded = true;
            MaicaAccountConfig config = MaicaAccountConfig.readOrTemplate();
            if (config != null) {
                draftUsername = config.username();
                draftPassword = config.password();
                draftLang = config.targetLang() == null ? "" : config.targetLang();
                draftMt = config.enableMt();
            }
            draftSession = MaicaAutoLogin.siteHeaders().getOrDefault("chat_session", "-1");
        }

        int cx = this.width / 2;
        int col1 = cx - 155;
        int col2 = cx + 5;
        int w = 150;

        usernameBox = new EditBox(this.font, col1, 40, w, 18,
                Component.translatable("maidllmlocal.screen.username"));
        usernameBox.setMaxLength(128);
        usernameBox.setValue(draftUsername);
        usernameBox.setResponder(v -> draftUsername = v);
        addRenderableWidget(usernameBox);

        passwordBox = new EditBox(this.font, col1, 72, w, 18,
                Component.translatable("maidllmlocal.screen.password"));
        passwordBox.setMaxLength(128);
        passwordBox.setValue(draftPassword);
        // 密码打码（同 TLM 站点编辑器对 secret_key 的做法）。
        // 注意 formatter 收的是 String -> FormattedCharSequence，不是 Component。
        passwordBox.setFormatter((text, cursor) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        passwordBox.setResponder(v -> draftPassword = v);
        addRenderableWidget(passwordBox);

        // 右列两个循环按钮（比下拉轻，也不需要额外渲染）
        addRenderableWidget(Button.builder(langLabel(), b -> {
            draftLang = LANGS[(langIndex() + 1) % LANGS.length];
            b.setMessage(langLabel());
            refreshStatus();
        }).bounds(col2, 38, w, 20).build());

        addRenderableWidget(Button.builder(mtLabel(), b -> {
            draftMt = MT_STATES[(mtIndex() + 1) % MT_STATES.length];
            b.setMessage(mtLabel());
            refreshStatus();
        }).bounds(col2, 70, w, 20).build());

        sessionBox = new EditBox(this.font, col1, 104, 50, 18,
                Component.translatable("maidllmlocal.screen.session"));
        sessionBox.setMaxLength(3);
        sessionBox.setValue(draftSession);
        sessionBox.setHint(Component.literal("-1"));
        sessionBox.setResponder(v -> {
            draftSession = v;
            refreshStatus();
        });
        addRenderableWidget(sessionBox);

        int bw = 76;
        int by = this.height - 30;
        int bx = cx - (bw * 4 + 3 * 6) / 2;
        addRenderableWidget(Button.builder(Component.translatable("maidllmlocal.screen.login"),
                b -> onLogin()).bounds(bx, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("maidllmlocal.screen.save"),
                b -> onSave()).bounds(bx + bw + 6, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("maidllmlocal.screen.clear"),
                b -> onClearToken()).bounds(bx + (bw + 6) * 2, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("maidllmlocal.screen.close"),
                b -> onClose()).bounds(bx + (bw + 6) * 3, by, bw, 20).build());

        refreshStatus();
    }

    // ------------------------------------------------------------------ 动作

    /** 保存：只落盘（账号文件 + 会话号 header），不去登录。 */
    private void onSave() {
        if (!writeAccountFile() || !writeSessionHeader()) {
            return;
        }
        tell(Component.translatable("maidllmlocal.screen.saved"));
        refreshStatus();
    }

    /** 登录：先落盘，再走 {@link MaicaAutoLogin#apply}（本机没 token 才会真去换）。 */
    private void onLogin() {
        if (!writeAccountFile() || !writeSessionHeader()) {
            return;
        }
        MaicaAccountConfig config = currentConfig();
        if (hasToken()) {
            // apply 在已有 token 时只同步 headers（手工配置优先）。说清楚，免得玩家以为换号成功了
            tell(Component.translatable("maidllmlocal.screen.login.kept"));
        } else if (!config.hasLogin()) {
            tell(Component.translatable("maidllmlocal.screen.login.nocreds"));
            return;
        }
        MaicaAutoLogin.apply(config);
        refreshStatus();
    }

    private void onClearToken() {
        if (!hasToken()) {
            tell(Component.translatable("maidllmlocal.screen.clear.none"));
            return;
        }
        MaicaAutoLogin.clearToken();
        tell(Component.translatable("maidllmlocal.screen.cleared"));
        refreshStatus();
    }

    // ------------------------------------------------------------------ 助手

    private boolean writeAccountFile() {
        try {
            MaicaAccountConfig.save(draftUsername, draftPassword,
                    draftLang.isBlank() ? null : draftLang, draftMt);
            return true;
        } catch (Exception e) {
            MaidLLMLocal.LOGGER.warn("maica config screen: 保存账号文件失败", e);
            tell(Component.translatable("maidllmlocal.screen.save.failed", String.valueOf(e.getMessage())));
            return false;
        }
    }

    /** 会话号必须是 -1..9 的整数（两端一致的钳制范围，见 ClientMaicaSessions / MaicaClient）。 */
    private boolean writeSessionHeader() {
        String raw = draftSession == null ? "" : draftSession.trim();
        if (raw.isEmpty()) {
            return true; // 没填 = 不动
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            tell(Component.translatable("maidllmlocal.screen.session.invalid"));
            return false;
        }
        if (value < -1 || value > 9) {
            tell(Component.translatable("maidllmlocal.screen.session.invalid"));
            return false;
        }
        MaicaAutoLogin.setSiteHeaders(Map.of("chat_session", String.valueOf(value)));
        return true;
    }

    private MaicaAccountConfig currentConfig() {
        return new MaicaAccountConfig(draftUsername, draftPassword,
                draftLang.isBlank() ? null : draftLang, draftMt);
    }

    /** 本机 maica 站点当前有没有 token。控件回调都在主线程，可直接读站点表。 */
    private boolean hasToken() {
        return AvailableSites.LLM_SITES.get(MaicaSite.API_TYPE) instanceof LLMOpenAISite site
                && !site.secretKey().isEmpty();
    }

    private int langIndex() {
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(draftLang)) {
                return i;
            }
        }
        return 0;
    }

    private Component langLabel() {
        return draftLang == null || draftLang.isBlank()
                ? Component.translatable("maidllmlocal.screen.lang.auto")
                : Component.translatable("maidllmlocal.screen.lang.value", draftLang);
    }

    private int mtIndex() {
        for (int i = 0; i < MT_STATES.length; i++) {
            if (Objects.equals(MT_STATES[i], draftMt)) {
                return i;
            }
        }
        return 0;
    }

    private Component mtLabel() {
        String key = draftMt == null ? "maidllmlocal.screen.mt.follow"
                : draftMt ? "maidllmlocal.screen.mt.on" : "maidllmlocal.screen.mt.off";
        return Component.translatable(key);
    }

    /** 状态面板：只读地反映"现在到底是什么状态"—— 这块界面的主要价值就在这儿。 */
    private void refreshStatus() {
        status.clear();
        status.add(Component.translatable(hasToken()
                ? "maidllmlocal.screen.status.token.yes"
                : "maidllmlocal.screen.status.token.no"));

        if (!ServerMtState.isKnown()) {
            status.add(Component.translatable("maidllmlocal.screen.status.server.unknown"));
        } else {
            status.add(Component.translatable(Boolean.TRUE.equals(ServerMtState.get(MaicaSite.API_TYPE))
                    ? "maidllmlocal.screen.status.server.on"
                    : "maidllmlocal.screen.status.server.off"));
        }

        status.add(Component.translatable("maidllmlocal.screen.status.session",
                draftSession == null || draftSession.isBlank() ? "-1" : draftSession));
        status.add(Component.translatable("maidllmlocal.screen.hint.server"));
    }

    private void tell(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(message, false);
        }
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.21 的惯例：super.render 自己会画背景（NeoForge 自己的 ConfigurationScreen 也这么写）
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);

        int cx = this.width / 2;
        int col1 = cx - 155;
        int col2 = cx + 5;
        label(graphics, "maidllmlocal.screen.username", col1, 29);
        label(graphics, "maidllmlocal.screen.password", col1, 61);
        label(graphics, "maidllmlocal.screen.lang", col2, 27);
        label(graphics, "maidllmlocal.screen.mt", col2, 59);
        label(graphics, "maidllmlocal.screen.session", col1, 93);
        graphics.drawString(this.font, Component.translatable("maidllmlocal.screen.session.hint"),
                col1 + 56, 109, 0x808080, false);

        int y = 132;
        for (Component line : status) {
            graphics.drawString(this.font, line, col1, y, 0xA0A0A0, false);
            y += 12;
        }
    }

    private void label(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(this.font, Component.translatable(key), x, y, 0xE0E0E0, false);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }
}