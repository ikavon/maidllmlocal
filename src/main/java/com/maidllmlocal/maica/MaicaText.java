package com.maidllmlocal.maica;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MAICA 回复文本的后处理（移植 maica4tlm 的 emotions.py）。
 *
 * <p>MAICA 模型按训练契约在每句前输出 {@code [情绪]} 标签（中英皆可能，中括号内可能有空格），
 * 并用 {@code [player]} 占位符指代玩家。TLM 前端不认识这些标记：
 * <ul>
 *   <li>{@link #replacePlayer} —— 占位符换成玩家名</li>
 *   <li>{@link #stripTags} —— 摘掉情绪标签（气泡与 TTS 都用干净文本）</li>
 *   <li>{@link #dominantEmotion} —— 选主导情绪，留给 MTTS 的 emotion 参数（Step 4 用）</li>
 * </ul>
 */
public final class MaicaText {

    /** [player] / [player_nickname]，容忍中括号内空格，大小写不敏感。 */
    private static final Pattern PLAYER_RE = Pattern.compile(
            "\\[\\s*player(?:_nickname)?\\s*]", Pattern.CASE_INSENSITIVE);

    /** 候选标签：中括号内是纯中文或纯英文单词（允许内部空格），再由词表裁决。 */
    private static final Pattern TAG_RE = Pattern.compile(
            "\\[\\s*([A-Za-z一-鿿][A-Za-z一-鿿 ]{0,23}?)\\s*]");

    /** 标准情绪词表：英文（小写）→ 中文（API Documents.md "开始对话" 一节）。 */
    private static final Map<String, String> EN2ZH = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                {"smile", "微笑"}, {"happy", "开心"}, {"worry", "担心"}, {"grin", "笑"},
                {"think", "思考"}, {"angry", "生气"}, {"blush", "脸红"}, {"gaze", "凝视"},
                {"upset", "沉重"}, {"daydreaming", "憧憬"}, {"surprise", "惊喜"}, {"awkward", "尴尬"},
                {"meaningful", "意味深长"}, {"unexpected", "惊讶"}, {"relaxed", "轻松"}, {"shy", "害羞"},
                {"eagering", "急切"}, {"proud", "得意"}, {"dissatisfied", "不满"}, {"serious", "严肃"},
                {"touched", "感动"}, {"excited", "激动"}, {"love", "宠爱"}, {"wink", "眨眼"},
                {"sad", "伤心"}, {"disgust", "厌恶"}, {"fear", "害怕"}, {"kawaii", "可爱"},
                {"smiling", "微笑"}, {"worrying", "担心"}, {"grinning", "笑"}, {"thinking", "思考"},
                {"gazing", "凝视"}, {"surprised", "惊喜"}, {"relaxing", "轻松"}, {"eager", "急切"},
                {"winking", "眨眼"}, {"disgusting", "厌恶"}, {"fearing", "害怕"},
        };
        for (String[] pair : pairs) {
            EN2ZH.put(pair[0], pair[1]);
        }
    }

    private MaicaText() {
    }

    public static String replacePlayer(String text, String playerName) {
        if (text == null || playerName == null || playerName.isEmpty()) {
            return text;
        }
        return PLAYER_RE.matcher(text).replaceAll(Matcher.quoteReplacement(playerName));
    }

    /** 标签内容 → 标准中文情绪词；不在词表内返回 {@code null}（不是情绪标签，不能摘）。 */
    static String normalizeTag(String inner) {
        String key = inner.trim().toLowerCase();
        String zh = EN2ZH.get(key);
        if (zh != null) {
            return zh;
        }
        String stripped = inner.trim();
        return EN2ZH.containsValue(stripped) ? stripped : null;
    }

    /** 摘掉全部标准情绪标签并规整空白；非词表内容的 [...] 原样保留。 */
    public static String stripTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = TAG_RE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            if (normalizeTag(m.group(1)) != null) {
                m.appendReplacement(out, "");
            }
        }
        m.appendTail(out);
        return out.toString().replaceAll("[ \\t]{2,}", " ").strip();
    }

    /**
     * 主导情绪：出现次数最多者；平票取最先出现的。无标签返回 null。
     * 供 MTTS 的 emotion 参数使用（Step 4）。
     */
    public static String dominantEmotion(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = TAG_RE.matcher(text);
        Map<String, int[]> counts = null; // value = {次数}
        String firstTop = null;
        int top = 0;
        java.util.List<String> order = new java.util.ArrayList<>();
        while (m.find()) {
            String zh = normalizeTag(m.group(1));
            if (zh == null) {
                continue;
            }
            if (counts == null) {
                counts = new LinkedHashMap<>();
            }
            int[] c = counts.computeIfAbsent(zh, k -> {
                order.add(k);
                return new int[1];
            });
            c[0]++;
            if (c[0] > top) {
                top = c[0];
            }
        }
        if (counts == null) {
            return null;
        }
        for (String zh : order) { // 保持出现顺序解决平票
            if (counts.get(zh)[0] == top) {
                return zh;
            }
        }
        return firstTop;
    }
}
