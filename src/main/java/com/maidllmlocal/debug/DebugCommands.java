package com.maidllmlocal.debug;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Comparator;
import java.util.Map;

/**
 * 情绪→动画链路的实机验证命令（临时调试设施，验证完可整类删除）。
 *
 * <p>回答三个问题：
 * <ol>
 *   <li>{@code anim extraN} —— 轮盘槽位是否可播、参数是槽位名还是显示名；</li>
 *   <li>{@code var <key> <value>} —— 写 {@code roamingVars} 后手动置脏能不能让客户端
 *       拿到新值（{@code SyncYsmMaidDataMessage} 只在 {@code rouletteAnimDirty} 时发出）；</li>
 *   <li>{@code vars} —— 回读服务端 map，配合客户端表现确认 YSM 是否读 {@code v.roaming.*}。</li>
 * </ol>
 */
public final class DebugCommands {
    private static final double SEARCH_RADIUS = 16.0;

    private DebugCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("maidllmlocal_debug")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("anim")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    EntityMaid maid = nearestMaid(ctx.getSource().getPlayerOrException());
                                    if (maid == null) {
                                        return fail(ctx.getSource(), "附近 16 格内没有女仆");
                                    }
                                    String name = StringArgumentType.getString(ctx, "name");
                                    maid.playRouletteAnim(name);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "playRouletteAnim(\"" + name + "\") on " + maid.getName().getString()), false);
                                    return 1;
                                })))
                .then(Commands.literal("animstop")
                        .executes(ctx -> {
                            EntityMaid maid = nearestMaid(ctx.getSource().getPlayerOrException());
                            if (maid == null) {
                                return fail(ctx.getSource(), "附近 16 格内没有女仆");
                            }
                            maid.stopRouletteAnim();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "stopRouletteAnim() on " + maid.getName().getString()), false);
                            return 1;
                        }))
                .then(Commands.literal("var")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("value", FloatArgumentType.floatArg())
                                        .executes(ctx -> {
                                            EntityMaid maid = nearestMaid(ctx.getSource().getPlayerOrException());
                                            if (maid == null) {
                                                return fail(ctx.getSource(), "附近 16 格内没有女仆");
                                            }
                                            String key = StringArgumentType.getString(ctx, "key");
                                            float value = FloatArgumentType.getFloat(ctx, "value");
                                            maid.roamingVars.put(key, value);
                                            // 同步包只在 rouletteAnimDirty 时发出；手动置脏强推一帧同步
                                            maid.roamingVarsUpdateFlag++;
                                            maid.rouletteAnimDirty = true;
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "roamingVars[\"" + key + "\"]=" + value + " (flag="
                                                            + maid.roamingVarsUpdateFlag + ") on " + maid.getName().getString()), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("vars")
                        .executes(ctx -> {
                            EntityMaid maid = nearestMaid(ctx.getSource().getPlayerOrException());
                            if (maid == null) {
                                return fail(ctx.getSource(), "附近 16 格内没有女仆");
                            }
                            StringBuilder sb = new StringBuilder("roamingVars of ").append(maid.getName().getString()).append(":");
                            if (maid.roamingVars.isEmpty()) {
                                sb.append(" <empty>");
                            }
                            for (Map.Entry<String, Float> e : maid.roamingVars.entrySet()) {
                                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                            return 1;
                        })));
    }

    private static int fail(net.minecraft.commands.CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private static EntityMaid nearestMaid(Player player) {
        AABB box = player.getBoundingBox().inflate(SEARCH_RADIUS);
        return player.level().getEntitiesOfClass(EntityMaid.class, box).stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }
}
