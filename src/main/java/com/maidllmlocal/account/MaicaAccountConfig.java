package com.maidllmlocal.account;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidllmlocal.MaidLLMLocal;
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 玩家输入的唯一一份配置：{@code config/maidllmlocal/maica_account.json}。
 *
 * <pre>{@code
 * {
 *   "username":   "DCC 用户名或邮箱",
 *   "password":   "DCC 密码",
 *   "target_lang": "",          // 可选："zh" / "en"；空 = 不改站点 headers
 *   "enable_mt":  null          // 可选：true / false；null = 不改站点 headers
 * }
 * }</pre>
 *
 * <p>模组在客户端进服时用它换 access_token，写回本机 llm.json / tts.json
 * （见 {@code MaicaAutoLogin}）。文件不存在时会自动生成模板，降低"该配在哪"的心智负担。
 *
 * <p><b>密码安全</b>：token 换到手后密码就没有用途了（MAICA 的 token 是静态凭据，不过期、
 * 不需要续），想删随时可以删，模组不会试图"记住密码"去刷 token。对外分享整合包配置前，
 * 这个文件和 llm.json / tts.json 一样属于<b>必须掏空</b>的东西。
 *
 * <p>只读语义：每次登录现读文件，改完不用重启游戏，重新进一次世界就会生效（仅当站点
 * 还没配上 token 时才会真正去登录）。
 */
public record MaicaAccountConfig(String username, String password,
                                 @Nullable String targetLang, @Nullable Boolean enableMt) {

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("maidllmlocal").resolve("maica_account.json");
    }

    /** 文件不存在时写出模板并返回 null；解析失败只记日志，同样返回 null（绝不清空玩家的文件）。 */
    @Nullable
    public static MaicaAccountConfig readOrTemplate() {
        Path path = file();
        try {
            if (!Files.exists(path)) {
                writeTemplate(path);
                MaidLLMLocal.LOGGER.info("maidllmlocal: 已生成 MAICA 账号模板 {}，填入 DCC 账号密码后重新进入世界即可", path);
                return null;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            return new MaicaAccountConfig(
                    str(root, "username"),
                    str(root, "password"),
                    str(root, "target_lang"),
                    root.has("enable_mt") && root.get("enable_mt").isJsonPrimitive()
                            ? root.get("enable_mt").getAsBoolean() : null);
        } catch (Exception e) {
            MaidLLMLocal.LOGGER.warn("maidllmlocal: 读取 {} 失败（跳过自动登录）", path, e);
            return null;
        }
    }

    /** 是否具备发起登录的最低条件。 */
    public boolean hasLogin() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }

    private static String str(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsString() : "";
    }

    /**
     * 覆盖保存这四项（游戏内设置界面用）。
     *
     * <p><b>与 {@link #writeTemplate} 的区别</b>：那个写的是<b>空模板</b>——拿它来保存会把
     * 玩家刚填的内容清空。这个写的是实参。
     *
     * <p><b>原位改</b>：先读回现有对象，只覆盖本模组管的这四个键，文件里其它键（将来新增的、
     * 或玩家自己留的字段）保持原样。读不回来（文件损坏）则按新文件重建，但记一条 warn——
     * 不静默吞掉。
     *
     * @param targetLang 空串或 {@code null} = 不指定（等同"不改站点 headers"）
     * @param enableMt   {@code null} = <b>跟随服务端</b>（见 {@code ServerMtState}）；
     *                   {@code true}/{@code false} = 显式覆盖（{@code false} 即 opt-out）
     */
    public static void save(String username, String password,
                            @Nullable String targetLang, @Nullable Boolean enableMt) throws IOException {
        Path path = file();
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        if (Files.exists(path)) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) {
                    root = parsed.getAsJsonObject();
                }
            } catch (Exception e) {
                MaidLLMLocal.LOGGER.warn("maidllmlocal: {} 解析失败，按新文件重建（原有内容会丢）", path, e);
            }
        }
        root.addProperty("username", username == null ? "" : username);
        root.addProperty("password", password == null ? "" : password);
        root.addProperty("target_lang", targetLang == null ? "" : targetLang.trim());
        if (enableMt == null) {
            root.add("enable_mt", JsonNull.INSTANCE);
        } else {
            root.addProperty("enable_mt", enableMt);
        }
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                StandardCharsets.UTF_8);
    }

    private static void writeTemplate(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        JsonObject template = new JsonObject();
        template.addProperty("username", "");
        template.addProperty("password", "");
        template.addProperty("target_lang", "");
        template.add("enable_mt", JsonNull.INSTANCE);
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(template), StandardCharsets.UTF_8);
    }
}
