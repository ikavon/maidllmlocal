package com.maidllmlocal.client;

import java.util.Map;

/**
 * 服务端下发的 MTrigger 状态：「这些站点在<b>服务端那边</b>开了 enable_mt」。
 *
 * <p>服务端在收到 {@link com.maidllmlocal.network.RelayHelloPackage} 后会回一个
 * {@link com.maidllmlocal.network.RelayCapabilityPackage}，内容存到这里。
 *
 * <h2>它解决什么问题</h2>
 * {@code enable_mt} 是<b>双端</b>的：服务端那半决定它会不会真的执行触发器
 * （{@code MaicaClient} 里没开就丢弃客户端回传的触发器并打一条 warn）。而玩家看不到服务端那份
 * 配置，非常容易"自己这半开了、服务端那半没开" —— 表现为白付一轮 MTrigger 后处理延迟、
 * 什么都不生效，且没有任何提示。有了这份状态：
 * <ul>
 *   <li><b>跟随</b>：账号文件里 {@code enable_mt} 留空（{@code null}）的玩家自动按服务端值走，
 *       从"要配的开"降为"想关才配"（opt-out）；</li>
 *   <li><b>可见</b>：设置界面的状态面板能如实写出"服务端：开 / 关 / 不知道"。</li>
 * </ul>
 *
 * <p>{@link #get} 返回 {@code null} 表示<b>不知道</b>（服务端没装本模组、包是可选的老客户端、
 * 或尚未收到），此时<b>不跟随</b>——保持既有的"默认关"行为，绝不凭空把开关打开。
 *
 * <p>故意<b>不加 {@code @OnlyIn(Dist.CLIENT)}</b>：本类只持有一个 Map、不碰任何客户端专用 API，
 * 而引用它的包处理器在 common 包里（专用服务器上永远走不到），不加注解可以让类加载干净利落。
 * 读写都在主线程：写入来自 {@code enqueueWork} 过的包处理器，读取来自登录/界面。
 */
public final class ServerMtState {

    private static volatile Map<String, Boolean> flags = Map.of();
    private static volatile boolean known;

    private ServerMtState() {
    }

    /** 收到服务端下发时调用（主线程）。 */
    public static void set(Map<String, Boolean> received) {
        flags = Map.copyOf(received);
        known = true;
    }

    /** 离开世界时清掉，免得把上一个服务器的状态带进下一个。 */
    public static void clear() {
        flags = Map.of();
        known = false;
    }

    /** 服务端是否回过状态。false = 不知道，此时一切跟随逻辑都不生效。 */
    public static boolean isKnown() {
        return known;
    }

    /**
     * @return 该站点在服务端是否开了 MTrigger；{@code null} = 不知道（调用方不要跟随）
     */
    public static Boolean get(String siteId) {
        return known ? flags.get(siteId) : null;
    }

    /** 供状态面板展示的只读快照。 */
    public static Map<String, Boolean> snapshot() {
        return flags;
    }
}