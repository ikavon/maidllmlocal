package com.maidllmlocal.client.maica;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidllmlocal.MaidLLMLocal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读 MAS 侧「MAICA MC Handoff」submod 落盘的 handoff.json（每玩家本机文件，
 * 路径由站点 header {@code handoff_file} 给出，空 = 不启用交接）。
 *
 * <p>交接内容只有一件事：MAS 侧 write_memory 蒸馏出的记忆条目
 * （{@code mas_player_additions}）。托管模式下它们作为每轮 query 的
 * 临时 savefile 注入，使「她从 MAS 来到了 Minecraft」这类事件知识跨前端流动。
 * 协议与原理见 docs/CROSSFRONTEND.md。
 *
 * <p>文件由 MAS 侧先写临时文件再改名，因此读到的永远是完整 JSON；
 * 按 mtime 缓存，不变不重复解析。
 */
public final class MaicaHandoff {

    private final Path path;
    private long lastModified = -1L;
    private JsonArray additions = new JsonArray();

    private MaicaHandoff(Path path) {
        this.path = path;
    }

    /** 空路径返回 null（未启用交接）。文件不存在不报错——MAS 侧可能还没跑过。 */
    public static MaicaHandoff of(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        return new MaicaHandoff(Path.of(filePath));
    }

    /** 当前交接的记忆条目（无文件/解析失败时为空数组，绝不抛）。 */
    public synchronized JsonArray additions() {
        try {
            long modified = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L;
            if (modified == lastModified) {
                return additions;
            }
            lastModified = modified;
            if (modified < 0) {
                additions = new JsonArray();
                return additions;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray loaded = root.has("mas_player_additions")
                    ? root.getAsJsonArray("mas_player_additions") : new JsonArray();
            MaidLLMLocal.LOGGER.info("maica handoff loaded: {} addition(s) from {}", loaded.size(), path);
            additions = loaded;
        } catch (Throwable t) {
            MaidLLMLocal.LOGGER.warn("maica handoff read failed ({}): {}", path, t.toString());
        }
        return additions;
    }

    /** 是否拿到了任何交接内容（空交接不值得往 query 里挂 savefile 字段）。 */
    public boolean isEmpty() {
        return additions().size() == 0;
    }
}
