package com.maidllmlocal.maica;

import java.util.List;

/**
 * 一轮 MAICA 对话的完整结果：聚合文本 + 本轮收到的 MTrigger 调用。
 *
 * <p>续传（reconn）恢复出来的轮次只有文本——后端的续传缓冲不保存触发器帧，
 * 那一轮的触发器会丢失。这是可接受的降级：触发器是善后方，不是回复本体。
 */
public record MaicaRoundResult(String text, List<MaicaTrigger> triggers) {
    public MaicaRoundResult {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
    }
}
