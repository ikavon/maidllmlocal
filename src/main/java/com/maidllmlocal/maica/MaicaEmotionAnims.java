package com.maidllmlocal.maica;

import java.util.Map;

/**
 * 情绪 → 轮盘动画名的映射（阶段 1 临时实现：借官方 default 模型的 extra 槽验证链路）。
 *
 * <p>槽位语义（default 模型）：extra1 挥手 / extra2 拍手 / extra3 YES / extra4 NO /
 * extra5 耶 / extra6 摆手 / extra7 跳舞。全是<b>一次性</b>动作，播完自动回默认（已实测），
 * 正好用来验证「聊天 → 情绪 → 播动作」链路，不需要 stop 管理。
 *
 * <p>自研莫妮卡模型落地后，这里换成语义化槽名（如 {@code expression_happy}），
 * 表情动画改 {@code loop:true} 实现"说话期间保持"，届时才需要 stop 逻辑。
 * 28 种情绪先粗归并到现有动作，归并表也是后面表情规格书的初稿输入。
 */
public final class MaicaEmotionAnims {

    private static final Map<String, String> ZH_TO_ANIM = Map.ofEntries(
            // 喜悦族 → 拍手；更激动的 → 跳舞
            Map.entry("开心", "extra2"),
            Map.entry("笑", "extra2"),
            Map.entry("微笑", "extra2"),
            Map.entry("可爱", "extra2"),
            Map.entry("激动", "extra7"),
            Map.entry("得意", "extra7"),
            // 打招呼/轻松 → 挥手
            Map.entry("轻松", "extra1"),
            Map.entry("感动", "extra1"),
            Map.entry("宠爱", "extra1"),
            // 惊讶族 → 耶
            Map.entry("惊喜", "extra5"),
            Map.entry("惊讶", "extra5"),
            Map.entry("急切", "extra5"),
            // 肯定/否定
            Map.entry("严肃", "extra3"),
            Map.entry("生气", "extra4"),
            Map.entry("不满", "extra4"),
            Map.entry("厌恶", "extra4"),
            // 消极/为难 → 摆手
            Map.entry("伤心", "extra6"),
            Map.entry("担心", "extra6"),
            Map.entry("沉重", "extra6"),
            Map.entry("害怕", "extra6"),
            Map.entry("尴尬", "extra6"),
            Map.entry("害羞", "extra6"),
            Map.entry("脸红", "extra6")
    );

    private MaicaEmotionAnims() {
    }

    /** 主导情绪（中文词）→ 轮盘动画名；无映射返回 {@code null}（不播）。 */
    public static String animFor(String zhEmotion) {
        return zhEmotion == null ? null : ZH_TO_ANIM.get(zhEmotion);
    }
}
