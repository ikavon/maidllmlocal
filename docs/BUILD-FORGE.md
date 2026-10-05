# BUILD-FORGE —— forge-1.20 分支的构建、部署与冒烟清单

> 本文件只存在于 `forge-1.20` 分支。主线(NeoForge 1.21.1)构建见根目录 `BUILD.md`。
> 分支基线:main v0.6.1(情绪→轮盘动画临时关闭),功能全量对齐。

## 分支定位

- 目标运行时:**AFoP Continue 整合包**(DCC 服务器同款,地址见私下渠道)——
  Forge **47.4.16** / MC **1.20.1** / Java **17** / TLM **1.5.3-forge+mc1.20.1**(包内自带,
  与我们编译依赖是同一个文件)。
- 包内**无 YSM**——本 mod 只硬依赖 TLM(mods.toml),无影响。
- 版本号约定:`<主线版本>-forge.N`,当前 `0.6.1-forge.0`。

## 构建

```bash
export JAVA_HOME="C:/Program Files/Zulu/zulu-17"   # FG6 要 JDK 17(主线是 21,别混)
./gradlew build
```

- **发布产物 = `build/libs/maidllmlocal-<ver>-all.jar`**(jarJar 合并产物,内含
  MixinExtras 0.4.1 JiJ)。**不带 `-all` 的主 jar 缺 MixinExtras,不能用。**
- `libs/` 里的 TLM jar 从 AFoP 的 `versions/AFoP/mods/` 拷贝,文件名 `+`→`_`
  (flatDir 坐标解析不认 `+`),详见 `libs/README.txt`(本地文件,不入库)。
- 首次构建约 8 分钟(FG6 下载+反编译 MC);之后十几秒。
- Gradle 8.8(腾讯镜像)——**FG6 不兼容 Gradle 9**,wrapper 别跟主线一起升。

## 部署与网络协商(⚠️ 与主线行为差异)

Forge 的 SimpleChannel 在登录握手时**强制两端 channel 集合与协议版本一致**:

1. **服务端 + 主人客户端都必须装**(relay 架构本来如此);
2. **只装客户端 → 进不去没装本 mod 的服务器**(握手拒绝)。给 AFoP 装 jar 后,
   在 DCC 服主装服务端之前,**别想用这个实例进 DCC 服**;要进就先移除 jar;
3. 反之,装了本 mod 的服务器也会**拒绝没装的玩家**——所以公测分发方式是
   「服主装服务端 + 整合包更新给全体玩家带上 jar」,两端同换;
4. 改任何包结构 → bump `NetworkInit.PROTOCOL_VERSION` → 同样两端同换
   (主线用 optional 包避免这事,Forge 没有 optional 语义,见 `RelayCapabilityPackage` 注释)。
5. **C2S 32KB 协议硬限制**(1.20.1 vanilla,NeoForge 1.21 没有):大于 24KB 的
   C2S 包(relay 响应/MAICA 文本/MTTS 音频)由 `NetworkInit.sendToServer` 自动分片
   (`FragmentPackage`),服务端 `FragmentAssembler` 重组,业务代码无感知。

## 冒烟清单(下次开工从这里继续,对应移植计划 Step 7)

开发环境(不碰 AFoP,窗口隔离,日志在 `run/logs/`):

```bash
export JAVA_HOME="C:/Program Files/Zulu/zulu-17"
./gradlew runClient
```

- [ ] 启动无 mixin error(`defaultRequire:1`,失配会明确报错不会静默)
- [ ] mod 列表可见 MaidLLMLocal 0.6.1-forge.0
- [ ] 进世界,女仆可聊天(relay 全链路:TLM→RelayHub→ClientRelayHandler→本机 HTTP→回包)
- [ ] `/maidllmlocal set` 设置界面:打开、保存、重进保留(注意 1.20.1 背景渲染)
- [ ] MAICA 轮次(WS 连接、记忆/好感度注入、MTrigger 执行)
- [ ] MTTS 语音回路(**重点验证分片路径**:音频>24KB 必走 FragmentPackage)
- [ ] 登录/登出清理无泄漏(含 `NetworkInit.clearPlayer` 分片流清理)

真实环境(AFoP,单人世界;测完记得移 jar 才能进 DCC 服):
把 `-all.jar` 拷进 `versions/AFoP/mods/`,PCL 启动,同上清单。
AFoP 的 EMF 已 JiJ MixinExtras 0.5.3 > 我们的 0.4.1 → FML 取高版跳过我们那份,
正好顺带验证 ranged [0.4.1,) 声明(启动日志应无 MixinExtras 重复告警)。

## 移植时对过账的差异点(改代码前必读)

| 主线(NeoForge 1.21.1) | 本分支(Forge 1.20.1) |
|---|---|
| PayloadRegistrar + StreamCodec | SimpleChannel + 手写 encode/decode(`NetBuf`) |
| `IPayloadContext.player()`(泛型 Player) | `ctx.getSender()`(已是 ServerPlayer,**不能再 instanceof 模式匹配**,恒真报错) |
| registrar.optional() + hasChannel 探测 | 无 optional;整通道版本协商,注释见 RelayCapabilityPackage |
| C2S 无 32KB 限制 | 32767 硬限 → 分片层(FragmentPackage/Assembler) |
| `@Mod(dist=CLIENT)` 双入口 | `FMLEnvironment.dist` 分流(`fml.loading` 包,不是 `fml`) |
| SavedData Factory + HolderLookup | `computeIfAbsent(loader, factory, name)` —— **loader 在前** |
| Screen.render 自动画背景 | 需显式 `renderBackground(guiGraphics)`(单参) |
| MixinExtras 平台自带 | JiJ `jarJar.enable()` + ranged(FG6 JiJ 是 opt-in,忘 enable 会静默出无 JiJ 的 jar) |
| parchment 映射 | official(SRG 运行时,reobfJar + refmap 桥接;两个 mixin 均 remap=false) |
| `ResourceLocation.fromNamespaceAndPath` | 47.4.x 反向移植,**存在**,可直接用(forge_version_range 因此收 [47.4,)) |

## 与主线的双向维护

- 两边网络层已结构性分叉:**业务逻辑改动**(hub/handler/maica/*)可 cherry-pick,
  **network/ 下的包类**基本要手工搬(编码方式不同,record 字段与语义可对照搬)。
- main 的 gradle.properties 开着 configuration-cache/parallel,FG6 不兼容——
  cherry-pick 时别把它带过来。
- 此仓库 commit 一律不带 Claude 署名。
