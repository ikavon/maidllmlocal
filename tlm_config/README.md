# tlm_config —— 站点配置样例（v0.4.0 起多数人已用不上）

**普通玩家（客户端）**：不需要拷贝这里的任何东西。
只要 `config/maidllmlocal/maica_account.json` 里有 DCC 账号密码，模组会自动换 token
并回填本机站点配置（见 [docs/DESIGN.md](../docs/DESIGN.md) 的「玩家免手动：自动登录」）。

**服务器管理员（服务端）**：服务端 `llm.json`/`tts.json` 里其实也会**自动出现**
`maica`/`mtts` 条目（模组的 serializer 注册后 TLM 会把默认站点写进表），你只需在游戏内
站点设置里把它们**启用**；样例仅在以下情况有用：

- 自建 MAICA 节点，要改 `url`
- 要设置 `headers`：`target_lang`（zh/en）、`enable_mt`、`chat_session`（跨前端联动，见下）、`http_base`
- 跨前端联动（实验性，见 [docs/CROSSFRONTEND.md](../docs/CROSSFRONTEND.md)）：想让女仆和 MAS
  里的莫妮卡共享记忆，**只要两端都设同一个 `chat_session`**——怎么配见下一节。
  `handoff_file` 属于**备选路线**（MC 用另一个 session 号）才需要的东西，主路径用不上
- 想要不同的站点 id / 模型列表

## 跨前端联动（实验性）：headers 怎么配

完整样例见 [`llm_site_maica_hosted.json`](llm_site_maica_hosted.json)——**那是客户端那份**，
演示的就是主路径（`chat_session` 一行）。`chat_session` **不能**走 `maica_account.json`
免手改（账号文件只并 `target_lang`/`enable_mt` 两个键），必须直接编辑 `llm.json`。

**主路径只需要 `chat_session` 同号**：零传输层，不用装 MAS 侧 submod，也不用 `handoff_file`。
`handoff_file` 只在**备选路线**（MC 用另一个 session 号 + MAS 侧 submod）里出现，
服务端那份压根不读这个键。三处 header 的分工：

| header | 服务端那份 `llm.json` | 客户端那份 `llm.json` |
|---|---|---|
| `chat_session` | 控制**注入与执行**：`≥0` 才走托管注入路径（场景包装 + 女仆 NBT 记忆） | 控制**连接行为**：query 怎么发、每轮附不附 savefile。**两侧必须同号** |
| `handoff_file` | 不读 | **仅备选路线**（MC 用另一个 session 号）需要：MAS 交接 `handoff.json` 的绝对路径；空 = 不启用交接 |
| `enable_mt` | MTrigger 的**注入与执行**——管理员对「AI 动女仆实体」有最终否决权 | 触发器表的**上传与收集**——关了后端根本不发触发器帧 |

## 字段纪律（违反 = 站点被 TLM 静默丢弃，详见 [docs/DEBUG.md](../docs/DEBUG.md)）

| 字段 | 规则 |
|---|---|
| `models` | **必须是数组**：`["daa4"]`。写成对象 `{"daa4":{...}}` 会让整条站点消失 |
| `headers` 的值 | **必须全是字符串**：`"enable_mt": "true"`，不能写裸 `true` |
| `secret_key` | 服务端留空 `""`；客户端留空则由自动登录回填。⚠️ 别把 `<占位符>` 原样抄进去——非空值会被当成"手工配置"而跳过自动登录，聊天还会失败 |
| `handoff_file` | **只有备选路线才写**：路径给**绝对路径**，Windows 下反斜杠要转义成 `\\`。⚠️ 指向一个不存在的路径不会报错，只是交接静默失效（客户端日志搜 `maica handoff loaded:` 确认） |
