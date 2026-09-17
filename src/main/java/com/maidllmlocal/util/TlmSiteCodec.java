package com.maidllmlocal.util;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TLM 站点 codec 的容错工具。
 */
public final class TlmSiteCodec {

    private TlmSiteCodec() {
    }

    /**
     * 容错版 {@code models} codec：数组形式（TLM 原生写法）或对象映射
     * （{@code {"daa4": "daa4"}} / {@code {"daa4": {"name":"daa4","reasoning":false}}}）都接受。
     *
     * <p><b>为什么要容错</b>：TLM 的 {@code MODELS_CODEC} 只认 JSON 数组。而"models 是模型表"这个
     * 直觉很容易让人写出对象形式——一旦形状不符，TLM 会把<b>整个站点</b>静默丢弃（只留一行日志），
     * 内存里退回该类型的默认站点（token 为空）；此后站点设置界面一保存，磁盘上的真实配置就被默认值
     * 覆盖，access_token 无声消失。2026-09-17 用户实例就是这样丢的：日志里查不到任何报错。
     *
     * <p>接受对象形式后，下次 TLM 保存时会用数组形式重写，文件自愈成规范形状。
     *
     * @param arrayModels 父类的数组形式 codec（{@code protected}，需在 Serializer 子类内取）
     * @param singleModel 父类的单条目 codec（纯字符串或 {@code {name, reasoning}} 对象），同为 protected
     */
    public static Codec<Map<String, LLMOpenAISite.ModelEntry>> tolerantModels(
            Codec<Map<String, LLMOpenAISite.ModelEntry>> arrayModels,
            Codec<LLMOpenAISite.ModelEntry> singleModel) {
        Codec<Map<String, LLMOpenAISite.ModelEntry>> objectModels =
                Codec.unboundedMap(Codec.STRING, singleModel).xmap(models -> {
                    // 与 TLM 自身一致：模型 id 取条目名（父类把 list 转 map 时用的就是 name）
                    Map<String, LLMOpenAISite.ModelEntry> out = new LinkedHashMap<>(models.size());
                    models.values().forEach(entry -> out.put(entry.name(), entry));
                    return out;
                }, models -> {
                    Map<String, LLMOpenAISite.ModelEntry> out = new LinkedHashMap<>(models.size());
                    models.forEach(out::put);
                    return out;
                });
        // 编码永远走数组分支（TLM 规范形状），对象形式只用于读取宽容。
        // 不用 Either::left —— 它与实例访问器 Either.left()（返回 Optional）同名，方法引用歧义
        return Codec.either(arrayModels, objectModels)
                .xmap(either -> either.map(m -> m, m -> m), map -> Either.left(map));
    }
}
