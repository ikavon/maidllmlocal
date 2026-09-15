package com.maidllmlocal.maica;

import com.github.tartaricacid.touhoulittlemaid.ai.service.Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.FieldDescriptor;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.TTSSiteFormLayout;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * MTTS 站点在游戏内编辑器里的表单布局。
 *
 * <p>MTTS 没有 GPT-SoVITS 那些额外字段（参考音频/切分方法都用不上——音色由 MTTS 账号侧决定），
 * 通用字段（url/key/enabled）由编辑器基类处理，这里没有专属字段。不能图省事返回
 * {@code TTSGptSovitsFormLayout}：它的 {@code buildSite} 会把编辑结果存成 GPT-SoVITS 站点，
 * 站点类型就被改掉了。
 */
public class MttsFormLayout extends TTSSiteFormLayout {

    public MttsFormLayout(TTSSite sourceSite) {
        super(sourceSite);
    }

    @Override
    public List<FieldDescriptor> getFieldDescriptors() {
        return List.of();
    }

    @Override
    public TTSSite buildSite(Function<String, String> values, Map<String, String> headers,
                             Consumer<Component> errorConsumer) {
        MttsSite source = (MttsSite) this.sourceSite;
        String url = orDefault(values.apply(Site.URL), source.url());
        String secretKey = orDefault(values.apply(Site.SECRET_KEY), source.secretKey());
        return new MttsSite(source.id(), source.icon(), url, source.enabled(), secretKey, headers);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
