# 调试手册

> 出问题先查这里。维护背景见 [DESIGN.md](DESIGN.md)。

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
