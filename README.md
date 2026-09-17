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

> 关于注释里的 `maica4tlm`：它是本项目的前身——一个把 MAICA 的 WebSocket 协议转成 OpenAI
> HTTP 接口、供原版 TLM 直连的 Python 适配器（暂未公开；如有兴趣欢迎开 issue 交流）。本模组的 Java 端
> `maica`/`mtts` 站点是它「甩开 Python、直接在玩家客户端说 MAICA 原生协议」的重写版，协议
> 逻辑（wsclient/auth/protocol/emotions/audio）逐条移植自它。当前主链路**不依赖它**；下文
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
（详见下方调试表）。access_token 从 MAICA 的 register 流程获得（DCC 账号）——
**v0.4.0 起这一步通常是免做的**，见下文「玩家免手动：自动登录」。

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
  控制：上传触发器表（REST `POST /trigger`——session=-1 下 query 内联的 trigger 字段被后端静默
  忽略，只能预上传）+ 握手下发 `enable_mt` + 收集触发器帧。
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

## 目前的状态

- ✅ M1 `player_relay`：编译通过，**单人实测通过**
- ✅ M3a `maica` LLM 站点 + `mtts` TTS 站点：**游戏内实测通过**（v0.2.0，文本+语音）
- ✅ M3b MTrigger 工具调用（v0.3.0）：**游戏内实测通过**（2026-09-17：换模式/好感度/记忆/称呼）
- ✅ v0.4.0 自动登录：**游戏内实测通过**（2026-09-17；期间揪出「/register 不校验凭据」与
  「昵称≠登录用户名」两个坑，legality 预校验 + 登录拒绝快速失败均已落地）
- ⚠️ 编辑器类型保留 mixin（同 jar）：编译通过，**尚未专门验证**（在游戏内站点编辑器里
  对 maica 条目点一次保存即可验证 api_type 不被洗）
-  尚未做：情绪→动画

## 调试

| 现象 | 说明 |
|---|---|
| 启动日志有 `announced N relay-capable local site(s): [...]` | 客户端自报的可中继站点，N=0 说明本机没配 `player_relay` 站点 |
| **llm.json 被"还原"、token 消失** | TLM 的站点设置界面开关会把**内存里的整表**写回 llm.json。若某次加载时文件解码失败（JSON 语法错、headers 值不是字符串、`models` 写成对象等），内存就是默认值——此时点一下开关，磁盘文件即被默认值覆盖。规则：**游戏运行时别改 llm.json；改坏了先修文件、重启游戏、别碰站点开关**。本模组自己的 `maica`/`player_relay` 站点 codec 已对 `models` 两种形状宽容，但 headers 值仍必须是字符串 |
| `Reference map 'maidllmlocal.refmap.json' ... could not be read` | **无害**。NeoForge 生产用官方映射，refmap 只在开发期有意义；TLM、`maidttslocal`、`conflict_fix` 都不带 refmap |
| 女仆不回复 / 报连接错误 | 看服务端 `latest.log`。主人离线时会用服务端那份 `url`，服务端没配有效 url 就会失败 |
| mixin 报错找不到注入点 | TLM 重构了 `LLMOpenAIClient.chat`。这是**有意为之**（`defaultRequire: 1`），比静默失效好 |
| 自动登录没有任何动静 | 依次检查：① `config/maidllmlocal/maica_account.json` 里 username/password 非空（模板在**开游戏**时生成；没生成=客户端没加载本模组）；② 本机 `llm.json` 的 `maica.secret_key` 是否**已有值**（手工配置优先，模组不插手，需先清空）；③ 换 token 发生在**进世界**那一刻，改完文件要重新进一次世界 |
| 聊天栏报「MAICA 自动登录失败」 | 后面拼的是服务端原话：密码错 / ToS 未接受 / 邮箱未验证最常见（**register 接口不验证凭据，这类错误现在在 legality 核验这一步就被拦下，不会先假成功后聊天才炸**）。按提示改完账号文件重新进世界即可；同一份错误凭据本会话内不会反复重试 |
| 「Invalid username/email or password」但密码明明没错 | 后端对标识符是 **SQL 精确等值匹配** `SqlUser.username` 或 `SqlUser.email` 两列之一，**从不匹配 `nickname`**。Flarum 的「显示昵称」和「登录用户名」是两列——填的若是**昵称**（或大小写/拼写与登录名有出入）就会「查无此人」，与密码错同一句话（防枚举）。去论坛资料页确认**登录用户名**，或直接用注册邮箱 |
| 聊天时报「MAICA 登录被拒 `maica_login_*`」 | WS auth 阶段的拒绝（多见于手工填的 token 属于另一个账号/节点，或账号被 MAS 客户端占用）。v0.4.0 起立即失败并附人话处置建议，不再 30 秒超时吐日志黑话 |
| 管理员在站点编辑器里保存了 `maica` 站点 | v0.4.0 起类型会被保住（`LLMSiteEditorScreenMixin`）；更早版本里这一手会把 api_type 洗成 `openai`，需从样例配置重建 |

构建见 `BUILD.md`。