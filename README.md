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

## 目前的状态

- ✅ 编译通过；mixin 的两处注入点已对**实际编译依赖的 jar**逐字节核对
- ⚠️ **尚未进游戏实测**（需要在整合包里跑一次）
-  尚未做：`maica` 原生站点（ActionQL），见 `maica4tlm` 那边的计划

## 调试

| 现象 | 说明 |
|---|---|
| 启动日志有 `announced N relay-capable local site(s): [...]` | 客户端自报的可中继站点，N=0 说明本机没配 `player_relay` 站点 |
| `Reference map 'maidllmlocal.refmap.json' ... could not be read` | **无害**。NeoForge 生产用官方映射，refmap 只在开发期有意义；TLM、`maidttslocal`、`conflict_fix` 都不带 refmap |
| 女仆不回复 / 报连接错误 | 看服务端 `latest.log`。主人离线时会用服务端那份 `url`，服务端没配有效 url 就会失败 |
| mixin 报错找不到注入点 | TLM 重构了 `LLMOpenAIClient.chat`。这是**有意为之**（`defaultRequire: 1`），比静默失效好 |

构建见 `BUILD.md`。