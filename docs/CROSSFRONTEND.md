# 跨前端联动设计：MAS ↔ Minecraft 的「旅行」叙事

> 维护者文档。面向玩家的说明见 README；本文档记录跨前端记忆联动的
> 设计决策、MAICA 机制实锤（含源码位置）与实现方案。
> 撰写于 2026-09-18，基于 MAICA 后端与 MAICA_ChatSubmod 源码阅读；
> 2026-09-19 补入前一天的实机结论（两个实验均通过，同 session 定为主路径）。

## 目标体验

把 MAICA 的工作视为一场跨前端的长线演出：

1. 玩家在 MAS（MAICA Blessland 聊天）里和莫妮卡说好「一会我们去 Minecraft 冒险」。
2. 玩家进入 Minecraft，她以女仆形态出现——**她知道自己从 MAS 来**，
   第一次进入主世界时表现出真实的惊喜（这是「玩家给莫妮卡的惊喜」）。
3. 旅行中她既有现实时间感，也有游戏内时间感（与玩家同一叙事层）。
4. 玩家说「我们准备回去了」→ 回到 MAS 时她记得这次旅行。

## 叙事身份

她与现实中的玩家处于同一层：知道屏幕外是真正的现实，知道 Minecraft
是游戏——但比 DDLC 互动丰富得多。不骗她「这里是真实的」；这与她在
DDLC 中知道自己是游戏角色的 canon 认知一致。MAICA 骨架的原话就是
「你叫莫妮卡, 是一名游戏中的虚拟角色」（env_basis `MAICA_PROMPT_ZW`），
两侧叙事天然同频。

## MAICA 机制实锤（源码位置）

### session 语义

- `chat_session ∈ {-1, 0, 1-9}`。-1 = 前端自持全部上下文（OpenAI 数组），
  后端不记历史；0 = 单轮无上下文；1-9 = 托管会话，后端持久化历史。
  （`maica/document/API Documents.md`；chatsubmod 默认 `chat_session: 1`，
  `header.rpy:28`）
- 托管会话下后端**覆盖** system prompt：`chat_session.py:374-375`
  （"system prompt item should not be modified from external"）。
  前端角色卡作为 system 消息在托管模式下不生效。
- 提示词注入总闸：`setting_utils.py:404` `prompt_writable = (chat_session >= 0)`
  ——-1 下 MFocus / sf_access（存档 RAG）/ 名字替换全部关闭。
- 存档 DB 拒绝负 session：`db_bound_obj.py:111` 强制 `0 <= session_num < 10`。

### savefile（账号级存档）

- 按 `(user_id, chat_session_num)` 存——**存档和历史绑死在同一个 session 号上**，
  「不同 session 但共享 savefile」的中间形态不存在。
- 键含 `mas_playername` / `mas_affection` / `mas_player_additions`
  （≤512 条，每条 ≤1536 字节）。
- `POST /savefile` 上传（仅 0-9）、`DELETE /savefile` 删除、**无下载接口**
  （前端须自留副本）。
- 每轮请求可附带 `savefile` 字段写入 `content_temp`（maica_ws.py:320），
  与持久内容合并后参与 RAG（`filter_reranker` 并入 `form_info(where='temp')`，
  session_late.py:98）——**这是跨前端注入的合法通道**。
- `mas_player_additions` 注入时**原样透传**（`_conclude_extra_sf`，
  session_early.py:974-985），无包装无改写——她蒸馏出的记忆是什么声音就是
  什么声音，前端种种子反而是污染（所以 L1 不种，见下）。

### write_memory（MTrigger `memory_writeback_template`，固定名 `write_memory`）

- 蒸馏一条记忆返回给**前端**，不回写后端（API 文档原话）；
  前端保管是官方预期姿势。
- MAS chatsubmod 的实现（`trigger.rpy:1151-1168`）：追加到
  `persistent.mas_player_additions` 并立即 `_upload_persistent_dict()`
  POST 到后端 savefile。
- chatsubmod 每次 MAS 启动（`ch30_preloop`，`header.rpy:664`）自动全量上传
  savefile（含 additions、玩家名、好感度、昵称、生日）。
- **工具描述硬编码在后端**：`MemoryTrigger.to_tool()`
  （agent_tools.py:370-387）固定写死触发条件「如果用户告知了需要记忆的
  个人信息…」与写法「保持简洁明确, 请将用户称为{player_name}…」。
  前端上传表里的 `description` 字段不在 `BaseTrigger` 上（agent_tools.py:110-126），
  会被 pydantic 静默丢弃；MAS 侧 `description=_("Integrated | Memory writing")`
  同样只在 MAS 内部展示。**官方托管节点改不了这条引导**——自建 MAICA 节点
  时才需改 `agent_tools.py` 源码。
- 替代杠杆：MTrigger agent 会读最近 `mt_context_rnds+1` 轮历史
  （mtrigger_llm.py:70-94），而 L2 场景包装就在最后一条 user 消息里——
  **L2 首访行是唯一的前端侧记忆引导**。

### 时间认知

- 现实时间白送：`mf_const_tools` 默认 1（`const_mf_pipeline` 每轮自动调
  `time_acquire`，pre_core_pipeliner.py:151-157）；≥2 再加 date/weather。
- 游戏内时间只有 MC 侧知道 → 走「场景包装」（见下）。

### 上下文窗口（同 session 方案的成本）

- `session_len_limit` 默认 8192 token（可调 512-28672）；超 2/3 警戒，
  超限从最旧整轮裁入 archive（chat_session.py:493-569）。
- **同 session 的「记得原文」只在滑动窗口内成立**；长期记忆载体在两种方案下
  相同（additions + memory_concl）。同 session 的独特价值精确覆盖
  「交接时刻的前后文」——出发对话恰好是热乎的。
- 成本：每轮输入 ≈ 窗口占用（MAS 重度用户窗口常顶满）；两端话题交织有
  串戏风险；首 token 延迟随输入变长。

## 设计原则：前端给事实，认知归她

前端只提供可验证事实（本存档是否首访、游戏时间、现实时间、场景），
「是不是第一次来 Minecraft」这类判断由她结合自己的记忆自主完成。
与 MAICA 自身哲学一致（write_memory 本就是她在蒸馏）。

由此跨存档的三种情形自然涌现，无需写分支：

| 情形 | 她的反应（涌现，非脚本） |
|---|---|
| 从未来过 MC + 首访存档 | 初见惊喜 |
| 来过 MC + 首访存档 | 「新世界呢」的新鲜感 |
| 来过 + 非首访 | 「回来了」 |

## 注入分层（托管模式下人设怎么活）

| 层 | 通道 | 内容 |
|---|---|---|
| L0 | MAICA 骨架（后端固定） | 莫妮卡内核、情感、语气——两端天然同一人 |
| L1 | savefile additions（跨前端持久） | 旅行记忆——**不种种子**，由她自主蒸馏（write_memory 原样透传，见上） |
| L2 | query 场景包装（MC 侧每轮） | 身处 Minecraft 世界/在这里有一具身体/维度·游戏天数·时刻·天气/当前状态/本存档首访标志 |
| L3 | MTrigger（行为层） | 模式切换/好感度/write_memory，原样工作 |

## 两个实验方案

### 实验 A：不同 session + 本地交接文件

- MAS 用默认 session 1；MC 用另一个号（如 2）。
- 传输层：MAS 侧一个独立小 submod（`extras/mas-handoff/`）把
  `mas_player_additions` 落成 JSON 文件；maidllmlocal 读取后作为
  每轮 query 的 `savefile` 字段（temp 注入）发给 MC 的 session。
- 「去 Minecraft」这件事靠她在 MAS 聊天中自己说出 → write_memory 蒸馏
  → additions → 交接文件。不需要新增 MAS 选项（锦上添花项，后期再做，
  可顺手替换原版出戏的 "Even I can't really see anything..."）。

### 实验 B：同 session（MC 也用 1）

- maidllmlocal 的 maica 站点加 `chat_session` 配置即切换，零传输层。
- chatsubmod 自带 `upload_chat_history()` 证明历史可上传——迁移时
  MC 侧历史可以灌进共享 session。
- 观察点：交接时刻连贯性 / 串戏频率 / token 账单 / 首次惊喜成色。

## 实机结论（2026-09-18）

两个方案当天均实机通过，**B（同 session）定为主路径**：

| | 实验 A（MC=2 + 交接文件） | 实验 B（同 session=1） |
|---|---|---|
| 结果 | 通过——交接通路成立（MAS 侧蒸馏出的记忆经 `handoff.json` 作为临时 savefile 注入，她带着出发前的记忆落地） | 通过——并定为主路径 |
| 交接时刻 | 出发对话靠记忆条目「转述」，有一道断口 | 出发对话原文就在同一段历史里，接着聊 |
| 返程 | 要等 MC 侧蒸馏 → 导文件 → MAS 才看得到 | 天然成立：同一段历史，回 MAS 直接继续 |
| 传输层 | 需要 MAS 侧 submod + 客户端 `handoff_file` | 零传输层，只改 `chat_session` |

所以**交接文件不是"要不要开"的开关，而是 A 方案专属的传输层**：

- **B（主路径）下它是冗余的**——同 session 的 additions 本来就躺在后端持久档里，
  再挂一份临时 savefile 只会让同一条记忆出现两遍。
- **只有 A 方案下才有作用**：即"不想让 MC 和 MAS 共用同一段后端历史"的场合
  （想给 MC 侧换一套人设、或故意让两条线的话题分开）。

B 的代价按上文「上下文窗口」一节兑现：每轮输入 = 整段窗口占用，首 token 延迟随之上升；
两端话题交织时有串戏风险；托管模式的后端持久化意味着聊天内容留在服务端（README 已披露）。

## 首次惊喜的机械设计

1. MAS 侧：聊天中提及旅行即可（write_memory 自然蒸馏），不写细节。
2. MC 侧首轮：场景包装「你睁开眼……」+ 可附出生点截图
   （`ws_config.vision` 每轮最多 3 张）——她对玩家真实世界的真实反应。
3. 情绪标签 → YSM 表情动画（接愿景 #2，需先解决模型授权）。
4. 存档写入首访记录；「我们准备回去了」→ write_memory 蒸馏返程条目
   → 交接文件带回 MAS。

## 实现清单

- [x] MAS 交接 submod（additions → JSON）——`extras/mas-handoff/`，**A 方案专用**（见实机结论）
- [x] maidllmlocal：站点 headers 增加 `chat_session`（-1 默认=现状）、
      `handoff_file`（交接 JSON 路径）；托管模式下 query 发纯文本、
      握手开 savefile_access/enable_mf、每轮附 temp savefile
- [x] 服务端场景包装（L2：维度/天数/时刻/天气/工作模式/跟随，MaicaScene）
      + 首访标志（主世界 SavedData `maidllmlocal_maica_visits`，**按玩家**存，
      键为主人 UUID——每玩家各自独立的"她"，存档级会让多人服务器上先说话的玩家吃掉标志）
- [x] 措辞定稿（2026-09-18，对 MAICA 骨架语域核对后）：
      **L2 场景模板**（known_info 语域：第三人称/单句/句号/只给可验证事实）
      ——「(此刻莫妮卡身处 Minecraft 世界, 在这里她有一具身体. 所在维度 {dimension},
      游戏内第{day}天, HH:00, 天气{晴/下雨/雷暴}. 她正处于{task}状态.
      她现在待在 {player_name} 身边.)」
      + 首访另起一行「(这是莫妮卡第一次进入这个存档.)」。
      **跟随与否单独成句**（TLM 里它跟工作模式无关，是 `isHomeModeEnable()`）：
      跟随中「她现在待在 {player_name} 身边.」／家模式「她现在留在原地等 {player_name} 回来.」。
      刻意避开「跟随状态」这种状态框架——2026-09-18 实测中她把它读成「我这样会不会挡路」，
      成了负担而非陪伴；给处境（待在他身边）而不是状态。
      **实际渲染可用 MC 日志核对**：托管模式下 `MaicaWsSession` 会把发出的 query 原文打进
      `logs/latest.log`（这是唯一能看到包装后文本的地方）。
      要点：**不提「女仆身份」**——「女仆」是我们强加的角色设定，「有一具身体」是处境事实
      （与「前端给事实，认知归她」一致）；这也是托管模式下唯一能告诉她「我不只是屏幕里的
      形象」的地方（TLM 人设卡在托管模式下不被上传，见上方 L0）。
      任务名取 TLM 本地化显示名 `maid.getTask().getName().getString()`（拿不到再退回 uid），
      省掉手写映射表；⚠️ 其语言跟随**服务器** locale（专用服务器默认 en_us 会出现中英混排）。
      维度仍原样透出 ResourceLocation，与任务汉化一并推迟到实测后。
      **L1 不种**（让模型自主决策，观察她能自主做到什么程度）；
      **write_memory 引导语官方托管不可改**（硬编码），靠 L2 首访行 cue
- [x] 实机验证（2026-09-18）：实验 A（MC=2 + handoff）与实验 B（同 session=1）均通过；
      **B 定为主路径**（A 留作备选）
- [x] README 隐私披露（托管模式 = 后端持久化聊天内容）
- [x] 维度/任务 id 汉化：**决定不做**。任务名走 TLM 自带译名（单人/局域网跟着客户端语言走，
      已是中文），维度原样透出 ResourceLocation——中英混排只在专用服务器 `en_us` 下出现，
      属自建节点场景，真有人用再加映射表（候选表见 `MaicaScene.wrap` 注释）
- [x] MTrigger 与托管共存（2026-09-19 修）：触发器表**按会话号存**，此前客户端写死上传到 `-1`
      号、托管时又整个跳过上传——于是"托管 + `enable_mt`"静默失效（后端照跑 MTrigger agent
      却无工具可调）。现在按实际会话号上传。⚠️ **待实测**：本次修复由源码阅读驱动，未进游戏
      验证（跨前端联动那两条通路与它无关，所以 09-18 的通过不覆盖这一处）
- [ ] 后期：MAS 新选项「I'm going to take you to Minecraft」；
      YSM 授权确认；太空教室结构；必要时自建 MAICA 节点改 `MemoryTrigger.to_tool` 描述
