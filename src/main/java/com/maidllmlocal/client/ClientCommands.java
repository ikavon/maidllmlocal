package com.maidllmlocal.client;

import com.maidllmlocal.client.gui.MaicaConfigScreen;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 玩家用的游戏内命令：{@code /maidllmlocal set} —— 打开 MAICA 设置界面。
 *
 * <h2>为什么做成"客户端命令"</h2>
 * 它只在玩家自己的客户端注册（{@code RegisterClientCommandsEvent} 只在逻辑客户端触发），
 * 由此得到三个正合需求的性质：
 * <ul>
 *   <li><b>任何服务器都能用</b>，包括完全没装本模组的原版服 —— 这条命令根本没进过服务端；</li>
 *   <li><b>不需要 OP</b>：它改的是玩家自己游戏目录里的配置，与服务器权限无关；</li>
 *   <li>命令消息不会发给服务端（{@code ClientCommandHandler} 执行成功即不回传）。</li>
 * </ul>
 * 这正是这个界面必须独立于 TLM 站点编辑器的原因 —— 那个只有 OP2 能开
 * （{@code GameModeUtil.canEditSite}），塞进去等于"只有管理员能配"。
 *
 * <p>⚠️ 命令体里<b>不要</b>碰 {@code getServer()}/{@code getLevel()}：客户端
 * {@code CommandSourceStack} 是特殊实现，在专用服务器上拿不到东西。
 *
 * <p>命令名与主类那个服务端命令 {@code /maidllmlocal_debug} <b>不同根</b>（同名会冲突）。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientCommands {

    private ClientCommands() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> set =
                Commands.literal("set").executes(ctx -> openScreen());
        event.getDispatcher().register(Commands.literal("maidllmlocal").then(set));
    }

    private static int openScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            // 理论到不了（聊天命令要先在世界里），但不能因此崩
            return 0;
        }
        // parent 传当前屏幕：在 HUD 上时它是 null，于是 ESC 直接回到游戏
        minecraft.setScreen(new MaicaConfigScreen(minecraft.screen));
        return 1;
    }
}