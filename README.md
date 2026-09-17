# MaidLLMLocal — 每个玩家用自己的 LLM 配置

把 [TouhouLittleMaid](https://github.com/TartaricAcid/TouhouLittleMaid)（车万女仆）的 **LLM 请求**
从「服务端发 HTTP」改成「发给女仆主人的客户端，由那台机器用自己的配置发出去」。

```
        原本                              现在
  ┌────────────                  ┌────────────
  │ 服务端      │──HTTP──> LLM     │ 服务端      │──封包──> 玩家客户端 ──HTTP──> 玩家自己的端点
  │ 全局一份站点 │                  │ 只转发      │         （自己的 key/供应方）
  └────────────┘                  ────────────┘
```

## 解决什么

服务器上所有玩家只能共用服务端配置的那一份供给 —— 成本与隐私都不合理，改站点还要求 OP 权限。
装了这个模组后，每个玩家的女仆用**玩家自己**的 `llm.json`：自己的 key、自己的供应方、自己的模型。

如果**只有服务端**装、玩家没装，行为与现在完全一致（见下方"兜底"）。

## 原理（为什么改动这么小）

关键事实：TLM 的 `AvailableSites.{LLM,TTS}_SITES` 在**客户端与服务端各自读本机**
`config/touhou_little_maid/sites/llm.json`，而服务端下发的 `SyncAISitesMessage` 只喂编辑器 GUI、
**不覆盖**它。所以「每玩家一份配置」在**文件层早已成立** —— 缺的只是"谁发这次 HTTP"。

于是本模组只做两件事：

1. 注册一个 `api_type = player_relay` 的站点类型（走 TLM 官方扩展点
   `ILittleMaid.registerAIChatSerializer`，不是 mixin）。
2. 一个极小的 mixin 把 `LLMOpenAIClient.chat()` 里那**一次** `httpClient.sendAsync` 换成
   "发给主人客户端、等客户端回包"。TLM 原有的请求构造、响应解析、工具循环、token 计数、气泡、
   TTS **全部零改动照跑** —— 我们只是让那个 `CompletableFuture` 由客户端回包来完成。

> 那行请求体是 `BodyPublishers.ofString(GSON.toJson(...))` 内联的，`HttpRequest` 取不回 body 字符串。
> 所以还额外把这一行换成我们自己的 `BodyPublisher` 实现，中继时用 `bodyPublisher()` 类型安全地读回来
> —— 比 ThreadLocal 干净，也比反射稳。

## 安装

1. jar 丢进 `mods/`（服务端与客户端都要装才能生效）。
2. **两端各**在自己那份 `config/touhou_little_maid/sites/llm.json` 里加一个**同名**的
   `player_relay` 站点，内容见 `tlm_config/llm_site_player_relay.json`：

   | | `url` | `secret_key` | `models` |
   |---|---|---|---|
   | **客户端** | 自己的本机端点，如 `http://127.0.0.1:8100/v1/chat/completions` | 自己的 | 与端点支持的模型名一致 |
   | **服务端** | 服务端的兜底供给 | 服务端的 | 同上 |

   - 站点 **id 必须两端同名**：服务端只把这个 id 发给客户端，客户端按它在自己本机 llm.json 里查真实的 url/key。
   - `icon` 用内置的 `misc.png` —— 自定义 id 没有自己的贴图，写别的名字会显示成缺失贴图。
   - `models` 里的名字要两端一致：服务端从这里供女仆挑选，客户端把选中的名字原样发给自己端点。
3. 游戏内给女仆选这个站点即生效。

## 兜底：任何失败都退回服务端站点

主人离线 / 客户端没装模组 / 本机没配这个站点 / 客户端发不出去 / 超时（15s）——
一律改用**服务端自己那份 llm.json** 的 url 与 key 重发。服务器因此始终保有可用性，
不装模组的玩家体验完全不变。

> 注意一个刻意的区分：客户端**真的收到了应答**（哪怕是 4xx/5xx）时会原样回传、**不回退** ——
> 否则会变成"玩家自己的端点报错，却悄悄拿服务端的 key 又试了一遍"。

## 谁替你花钱：同意机制

中继让**服务器**决定你的客户端要向你的 LLM 端点发什么内容。密钥不会泄漏（客户端只把 key 发给
**自己配置的** URL，服务端下发的包里只有站点 id 和请求体，不含 url/key），但"被别人的服务器花我的额度"
是真实风险。

所以同意机制做得很自然：**客户端只会上报本机已启用、且 api_type 是 `player_relay` 的站点。**
不想为某个服务器出钱，就不要在本机配这个站点 —— 服务器收不到它，自然走自己的供给。
不需要额外的开关或界面。

## `maica` 站点：TLM 直接说 MAICA 协议（v0.2.0 新增）

除了通用的 `player_relay`（OpenAI 兼容端点），模组还内置第二种站点类型 **`api_type = maica`**：
女仆的聊天直接走 [MAICA](https://github.com/Mon1-innovation/MAICA) 官方后端
（`wss://maicadev.monika.love/websocket`），不再需要本机跑任何 Python 适配器。

```
TLM 服务端: MaicaSite → MaicaClient（不自己联网）
   ──封包──> 玩家客户端: 本机维持一条到 MAICA 的 WebSocket 长连接
             （握手/参数/流式聚合/断线 reconn 续传/sping 心跳，移植自 maica4tlm 的 wsclient）
   ──封包──> 服务端合成回复 → 气泡/TTS/历史全部由 TLM 原有代码驱动
```

与 `player_relay` 的两点关键差异：

- **没有服务端兜底**。MAICA 是单账号单连接，且每玩家用各自的账号 —— access_token 只留在玩家
  本机，服务端同名站点的 `secret_key` 留空。任何失败（主人离线/没配站点/WS 断了）都直接显示
  失败气泡，这是设计意图：服务端本来就不该碰玩家的 MAICA 账号。
- **站点功能由模组原生实现**：session=-1 自带消息（≤10 轮/16KB 自动裁剪，保 system 人设）、
  MAICA 的情绪标签 `[开心]` 自动清洗、`[player]` 自动替换玩家名、状态码按 status 白名单分级
  （不按 HTTP 4xx 区间判错——MAICA 的 code 只是"像"RFC9110，400 段是警告不是错误）。

配置：两端 `llm.json` 各加一个同名站点（样例 `tlm_config/llm_site_maica.json`）：

| | `url` | `secret_key` |
|---|---|---|
| **客户端** | `wss://maicadev.monika.love/websocket` | **自己的 MAICA access_token** |
| **服务端** | 同上 | 留空 `""`（codec 要求字段存在，但永远用不到） |

`headers` 里的 `target_lang`（`zh`/`en`）决定 MAICA 的回复语言。`models` 仅为游戏内能选出模型
而存在，MAICA 后端不使用它。access_token 从 MAICA 的 register 流程获得（DCC 账号）。

### `mtts` 站点：MAICA 官方语音合成

与 `maica` 配套的 TTS 站点类型 **`api_type = mtts`**：女仆的配音直接由 MTTS 合成，
不再需要本机 GPT-SoVITS 或 Python 适配器。

- 配置：两端 `tts.json` 各加同名站点（样例 `tlm_config/tts_site_mtts.json`），客户端填
  access_token（与 maica LLM 站点**同一个**），服务端留空。
- 情绪联动：LLM 那一轮的主导情绪标签（`[开心]` 等 28 词表）会自动传给 MTTS 的 `emotion`
  参数，语音语气跟随文本情绪。
- 音频格式：官方节点 `lossless=false` 直接返回 mp3 透传给 TLM；自部署节点若回 wav 会报错
  （Java 侧不转码）。

### MTrigger：女仆不只是会说（v0.3.0 新增）

`api_type = maica` 站点支持 MAICA 原生的 **MTrigger 工具调用**：后端在每轮对话后决策
「角色该做点什么」，回传机器可读的动作帧，模组把它**直接落到女仆实体上**
（不走 TLM 的 OpenAI 工具循环——那会把工具结果再喂回 LLM 产生第二轮，双倍延迟双倍额度，
且 MAICA 不认识 tool 角色消息）。

三种动作：

| 触发器 | 效果 |
|---|---|
| `alter_affection` | 好感度增减，写进 TLM 好感度系统（增量取整、每轮钳制 ±5） |
| `write_memory` | 长期记忆写回：后端蒸馏出一句记忆，存进女仆 NBT（50 条 FIFO，随存档持久） |
| `switch_work_task` | 切换女仆工作模式（耕作/喂食等，选项来自当前注册的任务列表） |

**默认关闭**。开启方法：两端 `llm.json` 的站点 `headers` 里各加 `"enable_mt": "true"`：

- **客户端**的开关控制：上传触发器表（REST `POST /trigger`——session=-1 下 query 内联的
  trigger 字段会被后端静默忽略，只能预上传）+ 握手下发 `enable_mt` + 收集触发器帧
- **服务端**的开关控制：把触发器落到女仆实体 + 每轮向 MAICA 注入「长期记忆 + 当前好感度」
  上下文——这是服务器管理员对「AI 动女仆实体」的最终否决权
- 只开一端时另一侧日志会有 warn，功能静默降级为纯聊天

自建节点如果 REST 基地址推导不对（官方节点是 `wss://主机/websocket` ↔ `https://主机/api`），
在客户端 headers 里加 `"http_base": "https://你的节点/api"` 覆盖。

## 目前的状态

- ✅ M1 `player_relay`：编译通过，**单人实测通过**
- ✅ M3a `maica` LLM 站点 + `mtts` TTS 站点：**游戏内实测通过**（v0.2.0，文本+语音）
- ⚠️ M3b MTrigger 工具调用（v0.3.0）：编译通过，**尚未进游戏实测**
-  尚未做：情绪→动画

## 调试

| 现象 | 说明 |
|---|---|
| 启动日志有 `announced N relay-capable local site(s): [...]` | 客户端自报的可中继站点，N=0 说明本机没配 `player_relay` 站点 |
| **llm.json 被"还原"、token 消失** | TLM 的站点设置界面开关会把**内存里的整表**写回 llm.json。若某次加载时文件解码失败（JSON 语法错、headers 值不是字符串等），内存就是默认值——此时点一下开关，磁盘文件即被默认值覆盖。规则：**游戏运行时别改 llm.json；改坏了先修文件、重启游戏、别碰站点开关** |
| `Reference map 'maidllmlocal.refmap.json' ... could not be read` | **无害**。NeoForge 生产用官方映射，refmap 只在开发期有意义；TLM、`maidttslocal`、`conflict_fix` 都不带 refmap |
| 女仆不回复 / 报连接错误 | 看服务端 `latest.log`。主人离线时会用服务端那份 `url`，服务端没配有效 url 就会失败 |
| mixin 报错找不到注入点 | TLM 重构了 `LLMOpenAIClient.chat`。这是**有意为之**（`defaultRequire: 1`），比静默失效好 |

构建见 `BUILD.md`。