# 莫妮卡 YSM 模型制作详细计划

> 2026-09-20 定稿。基线：YSM 官方 default 模型（CC0）+ Blockbench MCP 工具链（97 工具实测）。
> 前置文档：DESIGN.md「目前的状态」（阶段 0/1 工程链路）。本文件是阶段 2/3 的施工蓝图。

## 0. 总原则

1. **美术不阻塞工程**：先用 default 模型验证表情链路（阶段 1 已落码），莫妮卡本体是内容替换。
2. **规格书先行**：UV 网格、表情参数表在建模前定死，避免返工。
3. **一切改动可回滚**：Blockbench 内每个里程碑 `save_checkpoint`；导出物全部进 git（本仓库或独立资源仓库）。

## 1. 工具链已验证事实（2026-09-20 实测）

### 能用

| 能力 | 工具 | 实测结论 |
|---|---|---|
| 导入 geo json | `from_geo_json` | ✅ 但**必须先 create_project（format=bedrock）**，且只收 http(s) URL，不收本地路径。本地起 `python -m http.server 8123` 解决 |
| 贴图导入 | `create_texture(data=http URL)` | ✅ |
| 贴图应用 | `apply_texture(applyTo=all)` | ✅ 172 个 cube 一次赋完；注意名字要带扩展名（`default.png` 不是 `default`） |
| 结构侦察 | `list_outline` / `find_elements_by_criteria` | ✅ 组层级、cube 计数、按正则搜骨骼 |
| 建动画 | `create_animation` | ✅ 骨骼名=组名；名字自动加 `animation.` 前缀 |
| 动画预览 | `risky_eval` | MCP 无「选动画/设时间」工具，须 eval：`Animation.all.find(...)` → `select()` → `Timeline.time=t` → `Animator.preview()` |
| 截图自检 | `set_camera_angle` + `capture_screenshot` | ✅ 相机持久，正面是 **-Z 方向**（相机放 `[0, y, -距离]`） |
| 导出 | `export_model(codec_id=bedrock)` | ✅ 干净回环 format_version 1.12.0 |
| 回滚 | `save_checkpoint` / `undo` | ✅ |

### 坑

- **`risky_eval` 每次调用都产生一条 undo 记录**（"Agent executed code"），预览动画也会污染撤销栈。批量操作尽量合并成一次 eval。
- `risky_eval` 在**无 project 打开时直接报错**（finishEdit undefined）。
- GBK 控制台：Python 读带中文的路径要在 bash 里 `cd` 进去再用相对路径。

### 待验证（建模前必须补测）

- [ ] `modify_cube` 改 UV（uv_offset / 逐面 UV）在 bedrock 格式的行为
- [ ] `place_cube` 加脸部薄片 + scale 0 隐藏的预览效果
- [ ] 动画 loop:true 在 YSM 游戏内的保持行为（default extra6/7 实测播完回默认，loop 行为以自研模型实测为准）

## 2. 素材基线（default 模型实测数据）

源目录：`<整合包>/config/yes_steve_model/builtin/default/`

### 骨架

64 组 / 172 cube / 0 mesh。主干：
`Root > MAllBody > AllBody > {UpBody > UpperBody > {AllHead > MHead > Head > Face, Arm, ...}, DownBody > {Leg, Skirt}}`

### 表情骨骼链（实测 pivot / cube 参数）

```
Head > Face > Eyes
  ├─ Eyebrow > {Left,Right}Eyebrow        # 眨眼时 position Y -1.5
  └─ Eyelid > {Left,Right}Eyelid          # 眨眼时 scale Y 1→0→1（官方做法，不是旋转！）
       └─ {Left,Right}EyesBase
            └─ {Left,Right}Pupil          # 0.6×1×1 薄片，uv (55,48) 附近
```

- 眨眼官方实现（`main.animation.json > pre_parallel1`，4s 循环）：眉毛 position Y 0→-1.5→0，眼睑 scale Y 1→0→0→1，catmullrom 插值，关键帧在 0 / 0.0833 / 0.2083 / 0.2917
- 脸部 cube 全部是**逐面 UV**（非 box UV），眼睛区域大致在 128×128 贴图的 x∈[49,56], y∈[41,48]
- `sleep` 动画也动 Eyelid/Eyebrow → 睡觉闭眼与眨眼同通道，表情动画设计要避让（见 §4 叠加规则）

### 动画清单

| 文件 | 条数 | 说明 |
|---|---|---|
| `tlm.animation.json` | **15（必修）** | beg/chair/gomoku/bookshelf/computer/keyboard/picnic/game_win/game_lost/broom/statue/garage_kit/use_mainhand:gohei/hold_offhand$…picnic_basket/chair$…moto。缺了对应状态 T-pose |
| `main.animation.json` | 50 | idle/walk/run/jump/sneak/swim/fly/sit/ride/boat/climb/attacked/death/sleep/…  + parallel0-7 并行动画系统 + pre_parallel* |
| `extra.animation.json` | 8 | extra0-7 轮盘动作（诹访大舞/挥手/拍手/YES/NO/耶/摆手/跳舞） |
| arm/fp.arm/tac/carryon/parcool/swem/slashblade/im/iss | — | 联动动画，**首版不带** |

### ysm.json 结构要点

- `properties.extra_animation.extra0..7`：槽位值填动画名，支持 `#控制器名` 指向动画控制器；`extra_animation_buttons` 定义配置 GUI（default 用它做头饰开关，读 `v.roaming.*`——注意 roamingVars 在女仆侧是死代码，此功能只对玩家模型有效）
- `properties.height_scale/width_scale`：0.7（碰撞箱缩放）
- `files.player.model` 支持多模型文件（main + arm 分离）；`texture` 数组支持多贴图切换（default/blue）
- `preview_animation: gui`、`gui_foreground/background`：模型选择界面预览

## 3. 表情系统规格（阶段 2 核心交付物）

### 3.1 情绪归并（28 → 6+1）

| 语义槽名 | 归并的 MAICA 情绪 | 表现 |
|---|---|---|
| `expression_happy` | 开心/兴奋/憧憬/喜欢 | 眉毛上扬+眼睑眯起（笑眼）+嘴角上扬薄片 |
| `expression_smile` | 微笑/温柔/安心 | 眉毛微降+眼睑微眯 |
| `expression_shy` | 害羞/脸红/尴尬 | 脸红薄片 scale 0→1 + 视线（Pupil position）下移 |
| `expression_surprised` | 惊讶/困惑 | 眉毛上挑+眼睑睁大（scale>1 或上移） |
| `expression_sad` | 难过/失落/担心 | 眉毛八字（外端 position Y 负）+眼睑半垂 |
| `expression_angry` | 生气/不满 | 眉毛内端下压（rotation Z）+眼睑半眯 |
| 中性 | 其余全部 | 不播表情动画 / stopRouletteAnim |

### 3.2 实现通道分工

| 部件 | 通道 | 做法 |
|---|---|---|
| 眉毛 | 骨骼 position/rotation | 每表情 1 组关键帧，loop:true 保持 |
| 眼睑 | 骨骼 **position**（禁用 scale，见 §3.3 避让表） | 眨眼走 scale、表情走 position，不同通道叠加 |
| 瞳孔 | 骨骼 position | 视线方向/大小变化 |
| 嘴 | **脸部薄片 cube + scale 0/1 切换** | 每种嘴型一个薄片，动画里切换显隐（YSM 惯用法，无 UV 关键帧的替代） |
| 脸红 | 同上 | 薄片 + 半透明贴图 |

### 3.3 叠加规则（避让清单，2026-09-20 已逐骨骼核对定稿）

**系统动画占用的通道（表情动画禁用）**：

| 骨骼通道 | 占用方 | 关键帧内容 |
|---|---|---|
| `Left/RightEyelid` **scale** | `pre_parallel1`（眨眼，常驻）+ `sleep`（=0 闭眼）+ `statue`（=1）+ `garage_kit`（左=0/右=1 单眨眼） | — |
| `Eyebrow` **position** | `pre_parallel1`（眨眼 Y-1.5）+ `sleep`（Y-1）+ `statue` | — |
| `Left/RightEyebrow` **position** | `statue`（=0）+ `garage_kit`（左 Y-1.2 挑眉） | — |

**表情动画可用通道（互不冲突）**：

| 部件 | 表情用通道 | 说明 |
|---|---|---|
| 眼睑 | `Left/RightEyelid` **position Y** | 半垂=下移、睁大=上移；绝不写 scale（否则与眨眼相乘、与 sleep 打架） |
| 眉毛 | `Left/RightEyebrow` **rotation**（八字=Z 外旋、愤怒=内端下压用 Z）+ 整体幅度可用 `Eyebrow` **rotation** | 绝不写 position（眨眼/sleep 都在用） |
| 瞳孔 | `Pupil` **position** | 无任何系统动画占用，自由用（视线下移=害羞等） |

- 眨眼走 `pre_parallel1`（并行系统），表情走轮盘槽 → 不同动画文件、不同优先级；同一骨骼上 scale（眨眼）与 position（表情）在 Bedrock 动画系统里是**相加/独立求值**，叠加安全
- `sleep`/`statue`/`garage_kit` 期间若触发表情：这些状态动画与表情动画在 position/rotation 通道不重叠，最坏情况是雕像闭眼时再叠一个眼睑下移，视觉可接受
- 说话期间保持：动画 loop:true；说完 `stopRouletteAnim()` 回中性（工程侧已在 MaicaEmotionAnims 实现，映射表换语义化槽名即可）

## 4. 莫妮卡模型本体（阶段 3 施工顺序）

### 4.1 几何

1. 复制 default 骨架全量保留（骨骼名一个不改——15 条 tlm 动画靠名字对骨骼）
2. 调比例：莫妮卡特征 = 高马尾、刘海、校服（灰 blazer + 棕毛衣背心 + 红丝带 + 深蓝裙）
3. 头发是最大工作量：default 是短发，马尾需新建骨骼链挂 `Head` 下（命名 `HairPonytail*` 自定义，不进 tlm 动画就不用避让）
4. 辨识度优先级（记忆结论）：**眼睛+眉毛 > 发型轮廓 > 服装**

### 4.2 贴图

- 128×128 起步（与 default 同分辨率，UV 可对照参考）
- **UV 网格在建模前定死**：脸部表情区预留空白 atlas 区（参考 default 眼区 x∈[49,56] y∈[41,48] 的密度）
- 底稿可 Python/PIL 程序化生成（平色+描边），再手工修
- 嘴型/脸红薄片共用小区域 atlas，每个 4×2 px 量级

### 4.3 动画制作顺序（依赖排序）

1. **先复制 default 的 tlm.animation.json 15 条原样继承**（骨骼同名则直接可用）→ 游戏内过一遍 15 状态确认无 T-pose
2. main.animation.json 基础集：idle/walk/run/sit/sleep/attacked/death（先继承 default，后续再调）
3. 表情动画 6+1（本计划核心新增，写进独立 `expression.animation.json`）
4. extra 轮盘动作：首版可只保留 extra0 配置位，其余后补

### 4.4 打包

```
custom/DDLC_Monika/           # 或 .ysm 打包
├─ ysm.json                   # metadata 写清：DDLC 同人、非官方、非商业、基于 YSM CC0 default
├─ models/main.json
├─ animations/{main,tlm,extra,expression}.animation.json
├─ textures/default.png
└─ lang/zh_cn.json            # 轮盘槽显示名
```

### 4.5 验收清单

- [ ] 游戏内 15 个 TLM 状态无 T-pose（下棋/看书/坐椅/手办/雕像/御币/野餐篮/moto 逐项过）
- [ ] 6 表情 × 与眨眼叠加无穿模/无通道打架
- [ ] 说话期间表情保持、说完回中性
- [ ] 走路/跑步中发表情不骨折（表情只动脸部骨骼，与 main 动画正交）
- [ ] 模型选择界面 preview_animation 正常
- [ ] ysm.json metadata 合规声明齐全

## 5. 里程碑

| 里程碑 | 内容 | 前置 |
|---|---|---|
| M2a ✅ | 表情规格书（§3 表格定稿 + 避让表，2026-09-20 完成） | 本文档 + default 三动画通道核对 |
| M2b | default 模型上加 2 表情（happy/sad）游戏内实测 | M2a + 阶段 1 游戏实测通过 |
| M3a | 莫妮卡几何（骨架复制+比例+发型） | — |
| M3b | 贴图 + UV | M3a |
| M3c | 动画继承 + 游戏内 15 状态验收 | M3a |
| M4 | 表情移植 + 全量验收 + 合规声明 | M2b + M3b/M3c |

## 6. 风险

| 风险 | 缓解 |
|---|---|
| 表情与眨眼通道叠加行为不符合预期 | M2b 先用 default 实测，最坏情况表情也走 scale（与眨眼相乘，视觉上可接受） |
| 「像不像」AI 判断不了 | 每个里程碑截图给用户肉眼验收 |
| 发量大导致 cube 数膨胀 | 对照 default 172 cube，目标 <400 |
| loop 动画在 YSM 的停止行为未实测 | 阶段 1 游戏实测时顺带验证（extra6/7 是 loop） |
