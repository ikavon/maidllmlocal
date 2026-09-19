# 设计文档（维护者向）

> 本文面向想了解原理、修改代码、或排查深层问题的人。**玩家请直接看 [README](../README.md)。**

## 解决什么

服务器上所有玩家只能共用服务端配置的那一份供给 —— 成本与隐私都不合理，改站点还要求 OP 权限。
装了这个模组后，每个玩家的女仆用**玩家自己**的 `llm.json`：自己的 key、自己的供应方、自己的模型。

如果**只有服务端**装、玩家没装，行为与原版完全一致（见下方「兜底」）。

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

> 关于注释里的 `maica4tlm`：它是本项目的前身——一个把 MAICA 的 WebSocket 协议转成 OpenAI
> HTTP 接口、供原版 TLM 直连的 Python 适配器（暂未公开；如有兴趣欢迎开 issue 交流）。本模组的 Java 端
> `maica`/`mtts` 站点是它「甩开 Python、直接在玩家客户端说 MAICA 原生协议」的重写版，协议
> 逻辑（wsclient/auth/protocol/emotions/audio）逐条移植自它。当前主链路**不依赖它**；本文
> 偶尔提到的端点（如 `127.0.0.1:8100`）是它作为可选独立部署的残留路径，不用也完全不影响。

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

`headers` 里的 `target_lang`（`zh`/`en`）决定 MAICA 的回复语言——**值必须是字符串**（写成
布尔或数字会让整个站点解码失败）。`models` 仅为游戏内能选出模型而存在，MAICA 后端不使用它，
且**必须是数组形式**（`["daa4"]`）——TLM 的 codec 只认数组，对象形式同样会静默丢弃整个站点
（详见 [DEBUG.md](DEBUG.md)）。access_token 从 MAICA 的 register 流程获得（DCC 账号）——
**v0.4.0 起这一步通常是免做的**，见下方「玩家免手动：自动登录」。

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

**默认关闭**，且**服务端与客户端两端都要为真**才真正生效——这是一道刻意保留的权限边界：
MTrigger 会改世界状态（好感度写进 TLM 系统、记忆持久化进女仆 NBT、`setTask` 换工作模式），
在别人的服务器上这属于管理员的领地，默认关、要显式开。两端各自的最简开法：

- **服务端（管理员领地，保持手动）**：服务端 `llm.json` 的 `maica` 站点 `headers` 加
  `"enable_mt": "true"`。控制：把触发器落到女仆实体 + 每轮向 MAICA 注入「长期记忆 + 当前好感度」
  上下文。这是管理员对「AI 动女仆实体」的最终否决权，一次部署级配置。
- **客户端（普通玩家，v0.4.0 起免翻 JSON）**：在 `maica_account.json` 里加 `"enable_mt": true`
  即可——模组会把它并进本机站点 headers（`target_lang` 同理）。**换没换到 token 都生效、密码删了
  也生效**：已有 token 时走独立的 headers 同步步，改完账号文件重新进一次世界即可，不必重取 token。
  控制：上传触发器表（REST `POST /trigger`——session=-1 下 query 内联的 trigger 字段到不了 MTrigger
  管线，只能预上传；表**按会话号存**，托管模式下上传到托管号，见下）+ 握手下发 `enable_mt`
  + 收集触发器帧。
- 只开一端时另一侧日志会有 warn，功能静默降级为纯聊天（服务端没开时客户端别开，否则白付一轮
  MTrigger 后处理的延迟）。

> **计划中的收敛（v0.5 候选，未实现）**：让服务端把「本服哪些站点已开 mt」随能力握手下发，
> 客户端自动跟随；届时玩家的 `enable_mt` 会从「要配的开」降级为「不想让 AI 动我女仆时的 opt-out」，
> 对普通玩家零配置。代价是响应包加字段（registrar 版本再 bump）+ 两端兼容判断，属协议面改动，
> 与发布节奏一并规划。

自建节点如果 REST 基地址推导不对（官方节点是 `wss://主机/websocket` ↔ `https://主机/api`），
在客户端 headers 里加 `"http_base": "https://你的节点/api"` 覆盖。

## 玩家免手动：自动登录（v0.4.0 新增）

普通玩家不该为了用女仆聊天去跑脚本、翻 JSON、要 OP 权限。**v0.4.0 把「拿 token」整个内藏进模组**：
玩家只写一个账号文件，token 由模组自动换、自动回填。

```
config/maidllmlocal/maica_account.json   ← 玩家唯一要碰的文件（模组启动时不存在则自动生成模板）
    { "username": "DCC注册邮箱（推荐）或论坛登录用户名", "password": "DCC密码" }
    ⚠️ 别填 MC 游戏名或论坛昵称——后端按注册邮箱/登录用户名精确匹配（详见调试表）
              │
   玩家进世界 → 模组后台: 发现节点 → POST /register → 拿 access_token → GET /legality 验证
              │
   自动回填到**本机** config/touhou_little_maid/sites/{llm,tts}.json 的 secret_key，
   并把本机这两条站点 enabled=true → 重新向服务端上报能力 → 聊天栏提示成功
```

对玩家的实际动作：**填一次账号密码，进世界，之后聊天栏看到「MAICA 自动登录成功」就能直接聊**。
不必知道 token 是什么、长什么样、填在哪个字段；MP 普通玩家也不需要任何权限（写的是自己游戏目录的文件）。

三条设计约束：

- **手工优先**：只要本机 `llm.json` 的 `maica` 站点已有 `secret_key`，模组就完全不插手——自动登录只填空位。
- **原位写回**：文件只动 `maica`/`mtts` 那一个条目的 `secret_key`/`enabled`（外加账号文件指定的
  `target_lang`/`enable_mt`），其余站点、其余字段、JSON 格式一概不碰。
- **条目缺失会整条创建**：其实通常用不上——装了本模组后，TLM 自己启动时就会把 `maica`/`mtts`
  默认条目写进两端配置（注册的 serializer 自动带出 defaultSite）。但万一文件里真没有（手删过、
  旧文件），回填时模组会用与 TLM 写盘同一条 codec 路径创建完整条目。**「配置里完全没写 MAICA」
  也能一步到位**，样例 `tlm_config/` 只是给管理员抄自定义字段用的参考。
- **先验证、后回填**：`/register` 是纯加密接口，**不校验账号密码对错**（API 文档原话），密码错
  照样发一个 token。所以拿到 token 先调 `GET /legality` 做一次真登录核验，通过才回填并提示成功
  （提示会带论坛用户名，即"以谁的身份登录"）；被拒则不写文件，聊天栏直接给出服务端原话 + 处置建议。
- **Fail2Ban 纪律**：进一次世界至多一次 register + 一次 legality（网络层重试封顶），
  同一份被拒凭据本会话内不再重试（改过账号文件才会再试）—— 服务端 20 次失败锁账号 600 秒
  （HTTP/WS 共享计数），密码里带首尾空格会被 trim（手填 JSON 的常见误输入）。

成功换到 token 后密码就没用了（MAICA 的 token 是静态凭据、不过期）。**对外分享整合包配置前，
`maica_account.json` 与两个 sites 文件一样属于必须掏空的东西**。

可选：账号文件里加 `"target_lang": "en"` 切回复语言、`"enable_mt": true` 上传触发器表
（MTrigger 仍要服务端同键开启才真正生效，见上文）。留空/不写则不改站点已有值。

## `chat_session` 托管模式（v0.5.0 新增，实验性）

`maica` 站点的 `headers` 加 `"chat_session": "1"`（`1`-`9`）即把会话交给后端托管：后端自己
记历史、开 MFocus 与存档 RAG，前端每轮只发这一条用户消息的纯文本。默认的 `-1` 是现状 ——
历史由 TLM 前端自持、随请求整包发出。

一句话分工：**`-1` 是我们把上下文喂给后端；托管是后端替我们保管上下文。**

| | `-1`（默认） | 托管（`1`-`9`） |
|---|---|---|
| query 内容 | 整个 OpenAI 消息数组 | 最后一条 user 的纯文本（含 L2 场景包装） |
| system 人设 | 我们发的 system 生效 | **后端覆盖**，我们的 system 被丢弃 |
| savefile / 存档 RAG | 被后端 `prompt_writable` 总闸屏蔽 | 生效 |
| 现实时间注入（MFocus） | 屏蔽 | 生效 |
| 聊天内容 | 只留在玩家本机历史里 | **后端持久化**（README 已向玩家披露） |

这个开关的实际用途是**跨前端联动**——让女仆和 MAS 里的莫妮卡是同一个人、共享记忆。
完整设计、源码实锤与实机结论见 [CROSSFRONTEND.md](CROSSFRONTEND.md)。三处容易踩的：

- **两端都要设，且必须同号**：服务端那份控制「注入与执行」（场景包装
  [`MaicaScene`](../src/main/java/com/maidllmlocal/maica/MaicaScene.java) + 女仆 NBT 里的长期
  记忆并进 user 消息）；客户端那份控制「连接行为」（query 怎么发、每轮附不附 savefile）。
  两头不同号 = 一个人被劈成两半，各聊各的。
- **人设改挂 user 消息**：托管下 system 会被后端覆盖，所以人设/记忆/场景全部并进最后一条
  user 正文，客户端只取这一条发出。因此**托管模式下 TLM 人设卡里的话术不生效**，只有
  场景包装里那句「身处 Minecraft 世界、在这里有一具身体」在替它说话。
- **MTrigger 的表按会话号存**（v0.5.0 修）：后端的触发器表严格对照 `chat_session` 号，
  托管会话不会回退用 0 号的表。此前客户端把上传写死成 `-1` 号、且托管时干脆不上传，
  于是"托管 + `enable_mt`"表现成**什么都没发生**——后端照跑 MTrigger agent，却无工具可调。
  现在按实际会话号上传。⚠️ 这一处是**源码推断驱动**的修复，尚未进游戏实测。

样例配置：[`tlm_config/llm_site_maica_hosted.json`](../tlm_config/llm_site_maica_hosted.json)。

## 目前的状态

- ✅ M1 `player_relay`：编译通过，**单人实测通过**
- ✅ M3a `maica` LLM 站点 + `mtts` TTS 站点：**游戏内实测通过**（v0.2.0，文本+语音）
- ✅ M3b MTrigger 工具调用（v0.3.0）：**游戏内实测通过**（2026-09-17：换模式/好感度/记忆/称呼）
- ✅ v0.4.0 自动登录：**游戏内实测通过**（2026-09-17；期间揪出「/register 不校验凭据」与
  「昵称≠登录用户名」两个坑，legality 预校验 + 登录拒绝快速失败均已落地）
- ✅ 编辑器类型保留 mixin（同 jar）：**游戏内实测通过**（2026-09-17，编辑器保存后 api_type 不再被洗）
- ✅ v0.4.0 账号文件 `enable_mt`/`target_lang` 同步（含删密码后）：**游戏内实测通过**
- ✅ v0.5.0 `chat_session` 托管模式 + 跨前端联动（场景包装 / 首访标志 / MAS 交接）：
  **游戏内实测通过**（2026-09-18，实验 A 与实验 B 双双通过、B 定为主路径，措辞经一轮返工；
  实测构建的版本字符串还是 0.4.0，源码与本题一致。细节见 [CROSSFRONTEND.md](CROSSFRONTEND.md)）
-  尚未做：情绪→动画
