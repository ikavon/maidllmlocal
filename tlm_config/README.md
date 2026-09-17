# tlm_config —— 站点配置样例（v0.4.0 起多数人已用不上）

**普通玩家（客户端）**：不需要拷贝这里的任何东西。
只要 `config/maidllmlocal/maica_account.json` 里有 DCC 账号密码，模组会自动换 token
并回填本机站点配置（见 [docs/DESIGN.md](../docs/DESIGN.md) 的「玩家免手动：自动登录」）。

**服务器管理员（服务端）**：服务端 `llm.json`/`tts.json` 里其实也会**自动出现**
`maica`/`mtts` 条目（模组的 serializer 注册后 TLM 会把默认站点写进表），你只需在游戏内
站点设置里把它们**启用**；样例仅在以下情况有用：

- 自建 MAICA 节点，要改 `url`
- 要设置 `headers`：`target_lang`（zh/en）、`enable_mt`（MTrigger 服务端侧开关）、`http_base`
- 想要不同的站点 id / 模型列表

## 字段纪律（违反 = 站点被 TLM 静默丢弃，详见 [docs/DEBUG.md](../docs/DEBUG.md)）

| 字段 | 规则 |
|---|---|
| `models` | **必须是数组**：`["daa4"]`。写成对象 `{"daa4":{...}}` 会让整条站点消失 |
| `headers` 的值 | **必须全是字符串**：`"enable_mt": "true"`，不能写裸 `true` |
| `secret_key` | 服务端留空 `""`；客户端留空则由自动登录回填。⚠️ 别把 `<占位符>` 原样抄进去——非空值会被当成"手工配置"而跳过自动登录，聊天还会失败 |
