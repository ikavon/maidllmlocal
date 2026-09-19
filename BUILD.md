# MaidLLMLocal — 构建与部署

> 运行时调试表见 [docs/DEBUG.md](docs/DEBUG.md)；设计原理见 [docs/DESIGN.md](docs/DESIGN.md)。

把 TLM(车万女仆) 的 **LLM 通道**从「服务端发 HTTP」改成「把请求发给女仆主人的客户端，客户端用自己的配置发出去」。
和 `maidttslocal` 解决 TTS 的思路同源，两者叠加即「每玩家完整的本机 LLM + TTS」。

## 前置

1. **JDK 21**（完整 JDK，要 javac）。本机在这：
   ```
   C:\Users\Username\AppData\Roaming\.minecraft\runtime\java-runtime-delta
   ```
   ⚠️ 系统默认 `java` 是 1.8 JRE、gradle 自带的只有 JDK 17，都不够。
2. **TLM jar 放进 `libs/`**（`compileOnly` 引用，不打包进本 mod）：
   ```
   touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar
   ```
   从整合包 `versions/<名>/mods/` 拷。**不放就编译失败。**

## 构建

```bash
export JAVA_HOME="C:/Users/Username/AppData/Roaming/.minecraft/runtime/java-runtime-delta"
./gradlew build
```
产物 `build/libs/maidllmlocal-0.1.0.jar`。首次会下 MC+NeoForge（~1-2GB），之后几十秒。

> `neo_version=21.1.233` 是**对齐整合包运行时实测版本**的（见 `latest.log` 的 mod 列表）。
> 用高于运行时的版本编译有可能引用到对方不存在的 API —— `maidttslocal` 用的 21.1.244 就有这个潜在隐患。

## 部署

1. jar 丢进 `versions/<整合包名>/mods/`（与 TLM 并排）。
2. 服务端与客户端各在自己那份
   `config/touhou_little_maid/sites/llm.json` 里加一个**同名**的 `player_relay` 站点，
   见 `tlm_config/llm_site_player_relay.json`。
   - **客户端**：`url` 填自己的本机端点（如 maica4tlm 的 `http://127.0.0.1:8100/v1/chat/completions`），`secret_key` 填自己的。
   - **服务端**：`url`/`secret_key` 填兜底供给；主人离线或未同意中继时用它。
3. 游戏内给女仆选这个站点即生效。

## 调试

- 日志关键字：`maidllmlocal`
- 启动时客户端会打印 `announced N relay-capable local site(s): [...]` —— 列出它愿意中继的本机站点 id。
- 这个警告是**无害**的，正常工作的模组也会有：
  `Reference map 'maidllmlocal.refmap.json' ... could not be read. If this is a development environment you can ignore this message`
  （NeoForge 生产环境用官方映射，refmap 只在开发期有意义；TLM 自己、`maidttslocal`、`conflict_fix` 都不带 refmap）
- **mixin 失配会明确报错**（`defaultRequire: 1`），不会静默退化成"不中继"。TLM 若重构
  `LLMOpenAIClient.chat`，会看到 mixin 找不到注入点而崩溃 —— 那是设计如此，比悄悄失效好。
