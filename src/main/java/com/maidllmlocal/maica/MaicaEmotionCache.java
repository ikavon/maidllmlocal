package com.maidllmlocal.maica;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天气泡文本 → 主导情绪 的短期缓存。
 *
 * <p>为什么需要它：TLM 的 LLM 与 TTS 是<b>两次独立调用</b>——{@code MaicaClient.chat} 拿得到
 * 带情绪标签的原文，而 {@code MttsClient.play} 拿到的 ttsText 是<b>已经摘掉标签</b>的干净文本，
 * 到那时情绪信息已经丢了。所以在 chat 完成时按"清洗后的文本"记一笔，TTS 时按同一把钥匙取回。
 * （与 maica4tlm 适配器的 lookup_emotion 同款做法。）
 */
public final class MaicaEmotionCache {

    private static final int MAX_ENTRIES = 256;

    @SuppressWarnings("serial")
    private static final Map<String, String> CACHE = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private MaicaEmotionCache() {
    }

    private static String key(String cleanText) {
        // 归一化：去全部空白，截断防长文（移植 normalize_text_key）
        String normalized = cleanText == null ? "" : cleanText.replaceAll("\\s+", "");
        return normalized.length() > 512 ? normalized.substring(0, 512) : normalized;
    }

    public static synchronized void put(String cleanText, String emotion) {
        if (emotion == null || emotion.isEmpty()) {
            return;
        }
        CACHE.put(key(cleanText), emotion);
    }

    /** 取不到返回 null（缓存未命中就无情绪，MTTS 用默认语气）。 */
    public static synchronized String lookup(String cleanText) {
        return CACHE.get(key(cleanText));
    }
}
