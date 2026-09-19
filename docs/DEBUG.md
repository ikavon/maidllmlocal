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
| **想看「她这一轮到底收到了什么」**（托管模式） | 客户端 `logs/latest.log` 搜 `maica query (session=`，后面就是发出的**纯文本原文**（含场景包装）。托管模式下这是唯一能看到包装后文本的地方——TLM 历史里没有它，后端存的历史也取不回来 |
| 托管模式设了，但她说得像个陌生人 / 完全不记得 MAS 那边 | 检查**两端** `llm.json` 的 `maica.headers.chat_session` 是否**同号**（服务端管注入、客户端管连接）。两种只设一端的情形不一样：**客户端设了、服务端没设**最糟——客户端只发最后一条 user 纯文本，而人设与场景包装都在服务端侧，等于给她一条没有任何处境的裸消息；**服务端设了、客户端没设**则退回普通 `-1` 模式（聊天本身正常，只是不共享、也不落后端历史） |
| 她不知道自己在 Minecraft / 首访惊喜没出现 | 服务端那份没设 `chat_session`（或设成了 `-1`）。场景包装在服务端侧，只看服务端 headers |
| 想重测「首访」 | 首访标志存在**主世界**的 `saves/<存档>/data/maidllmlocal_maica_visits.dat`（**按玩家**记，键是主人 UUID）。删掉这个文件即全体重置；只想重置自己则编辑该文件 |
| `handoff_file` 配了却没效果 | 不报错、只是静默失效：路径必须是**绝对路径**且指向 MAS 侧真实存在的 `handoff.json`（Windows 反斜杠写 `\\`）。客户端日志搜 `maica handoff loaded:` 确认读到了几条；搜不到就是没读到 |
| 她说话中英混排（如「她正处于Follow状态」） | 任务名取 TLM 本地化显示名，语言跟随**服务器** locale：单人/局域网跟客户端走（中文），专用服务器默认 `en_us`。已知、暂不处理（见 CROSSFRONTEND.md 实现清单） |
| 配了 `enable_mt`，但好感度/记忆/换模式一次都没发生过 | 触发器表是**按会话号存**的，三步依次查：① 两端 `enable_mt` 是否都为真（服务端是管理员的否决权，只开一端也白搭）；② 客户端日志搜 `maica trigger table uploaded to`——搜不到或被 `rejected by` 就是表没上去（表没上去后端不发触发器帧，且**不报错**）；③ 表里的会话号必须与 query 用的号一致（0.5.0 起托管模式自动跟随 `chat_session`，之前版本托管下必然失效） |
