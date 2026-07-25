# PICO Room Planner — 完整运行与开发手册（给另一台设备的你 / Kimi)

这份文档的目标是：**在全新设备上读一遍就能把整套系统跑起来，并能安全地修改任何代码。**
内容按"先跑起来 → 再理解系统 → 再动手改"的顺序组织。

---

## 0. 这个包里有什么

```
TestFull/
├── app/                        # Android 应用（PICO Spatial SDK, Kotlin + Compose）
│   ├── src/main/...            # 全部源码（见 §8 代码地图）
│   └── build.gradle.kts        # 含 BuildConfig 注入（ai.api.* → AI_API_*）
├── editor-asset/src/           # Showcase 场景源资产（Gradle 构建出 .bundle）
├── models/                     # 家具模型库（glb + 每个模型一个 sidecar .json）
│                               # 和表面纹理（jpg/png + sidecar .json）
├── tools/                      # 离线工具（Python）
│   ├── glb-rescale.py          # 量尺寸 / 把真实尺寸烘进 GLB
│   ├── glb-shrink-textures.py  # 把 GLB 内嵌贴图缩到 ≤1024（防模拟器 OOM）
│   └── seed-bounds-cache.py    # 离线计算包围盒并推送 .bounds-cache.json
├── model-manager/              # 设备文件管理网页工具（Node，零依赖）
├── floor-plan-tool/            # 平面图网页小工具（独立于 app）
├── docs/                       # Figma 远程 Dev Mode 方案
├── gradle/, gradlew*           # Gradle Wrapper（会自行下载 Gradle）
├── build.gradle.kts, settings.gradle.kts, gradle.properties
├── local.properties.example    # 复制成 local.properties 后改路径（见 §2）
├── push-model.sh / .cmd        # 老的单模型推送脚本（现在推荐 Model Manager）
├── RUNBOOK.md                  # 本文件
└── .git/                       # 完整 git 历史（可以查每个改动为什么发生）
```

不包含（需要你自行安装，见 §1)：JDK、Android SDK、PICO SDK 与模拟器本体、Gradle 发行版。
不包含的几处是故意的：`build/`（构建产物，重新生成即可）、`models/*.zip`（未转换的原始模型存档，
体积大；当前 models/ 目录里的文件已是"真实尺寸 + 贴图≤1024"的成品）。

---

## 1. 前置环境（一次性安装）

| 需要 | 用途 | 安装方式 |
|---|---|---|
| JDK 17 | 构建 Android 应用 | 直接用 Android Studio 自带的 JBR；或任意 JDK 17+。构建时设 `JAVA_HOME` |
| Android SDK (platform-tools, API 36) | adb、编译 | Android Studio → SDK Manager |
| PICO SDK 0.13.x + pico-cli + Emulator bundle | 空间 SDK 依赖与模拟器 | PICO 开发者官网下载；装完 `pico-cli emulator doctor` 自检 |
| Python 3.9+ 与 `Pillow`、`numpy` | tools/ 三个脚本 | `pip install pillow numpy` |
| Node.js 18+ | model-manager 网页工具 | 官方安装包 |

模拟器安装与启动（装完 PICO SDK 后）：

```bash
pico-cli emulator doctor      # 环境自检
pico-cli emulator install     # 下载模拟器镜像
pico-cli emulator create      # 创建 AVD（默认 Pico_Emulator_0_13, RAM 6144MB）
pico-cli emulator start       # 启动（首次冷启动很慢，几分钟）
pico-cli emulator status      # 确认 ADB online: yes
```

> **AVD 放哪**：默认在 `C:\Users\<你>\AppData\Local\PICO\sdk\avd`。创建需要 ~12GB 磁盘。
> C 盘不够就挂到别的盘：把该目录移到 D 盘后做 junction：
> `mklink /J "C:\Users\<你>\AppData\Local\PICO\sdk\avd" "D:\pico-avd"`(cmd 管理员窗口执行）。
> **RAM**:PICO 运行时基线自己就要 ~3.6GB,6144MB 的默认 AVD 能跑本应用（含 1 个重模型峰值）;
> 想同时摆多张大床建议 8192MB（改 AVD 目录下 config.ini 与 hardware-qemu.ini 的 hw.ramSize 后重启）。

---

## 2. 配置 local.properties

```bash
cp local.properties.example local.properties
# 编辑 sdk.dir（你的 Android SDK 路径）和 spatial.tools.dir（你的 PICO 工具路径）
```

`ai.api.*` 三项是 AI 布置功能的 OpenAI 兼容端点（示例里是当前可用的 relay 与 key,`gpt-5`)。
它们会在构建时注入 `BuildConfig.AI_API_BASE / AI_API_KEY / AI_API_MODEL`——**改了要重新构建**。
想换模型：`ai.api.model=gpt-4o-mini`（快但布局质量差）或任何 relay 上的 OpenAI 兼容模型。

---

## 3. 构建、安装、启动

Git Bash（Windows）下：

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"   # 按你的 JBR 路径改
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain

pico-cli app install app/build/outputs/apk/debug/app-debug.apk
ADB="/c/Users/Taven/AppData/Local/Android/Sdk/platform-tools/adb.exe"   # 按你的 adb 路径改
"$ADB" shell am force-stop com.example.testfull      # pico-cli launch 不会重启已运行的 app
pico-cli app launch com.example.testfull
```

> 单元测试全程不需要模拟器；`:app:testDebugUnitTest` 全绿即代码健康。

---

## 4. 家具模型管线（models/ 里的东西怎么来的、怎么加）

设备上的模型目录：`/sdcard/Android/data/com.example.testfull/files/models/`。
**推送方式**（三选一）:
- `node model-manager/server.js` 后开 http://localhost:8931，网页拖放上传（推荐，也能删/清空）;
- `./push-model.sh <file.glb>`;
- 手动 `adb push file /sdcard/Android/data/com.example.testfull/files/models/`(Git Bash 下务必先
  `export MSYS_NO_PATHCONV=1`，否则 `/sdcard` 会被改写成 `C:/Program Files/Git/sdcard`)。

**新模型入库两步走**（重要！否则尺寸错、还可能崩）:
```bash
python tools/glb-shrink-textures.py new.glb          # 1) 内嵌贴图缩到 ≤1024（防 LMK 杀进程）
python tools/glb-rescale.py measure new.glb          # 2) 量真实包围盒（含节点变换）
python tools/glb-rescale.py bake new.glb 宽 深 高     # 3) 把真实尺寸烘进 GLB（米）
# 4) 给 new.json 写 sidecar（schema 见 §4.1）,geometry 填 bake 后的实际值
```
推送到设备后，在 app 的 Furniture 面板点 Scan 即可；AI 也会把它列入目录。

### 4.1 家具 sidecar JSON(schema_version 1，全部可空；空字段会被"蒸馏"剔除不进 prompt)

```json
{
  "schema_version": 1,
  "identity": {"id": "bed-001", "name": "圆枕软包床", "description": "…", "status": "draft"},
  "classification": {"category": "bed", "room_types": ["bedroom"]},
  "geometry": {"width_m": 2.008, "depth_m": 1.414, "height_m": 0.698},
  "appearance": {"colors": [], "materials": []},
  "style_assessment": {"scores": {"style.nordic": {"score": 0.6}, "style.chinese": {"score": null}}},
  "placement": {"support_surface": "floor", "against_wall": null,
                "front_clearance_m": null, "side_clearance_m": null},
  "notes": null
}
```
- `geometry.*` 是**真实尺寸**(app 用它算 defaultScale；若与 GLB 实测一致则 scale=1，即现状）。
- `classification.room_types` 供 AI 分区摆放；`style_assessment.scores` 供风格匹配（0~1)。
- app 还会显示每个模型文件大小，供判断加载轻重。

### 4.2 纹理（表面皮肤）

纹理 = 图片（png/jpg/webp，建议 1024² POT,sRGB)+ 同名 `.json`:
```json
{"schema_version":1, "type":"surface_texture",
 "identity":{"id":"red-brick-wall","name":"Red brick"},
 "classification":{"surfaces":["wall"], "styles":["industrial","loft"]},
 "maps":{"base_color":"red-brick-wall.jpg","normal":"red-brick-wall_n.jpg"},
 "material":{"roughness":0.95,"metallic":0.0},
 "tiling":{"meters_per_tile":1.0}}
```
- 命名约定：`<名>.jpg` + 可选法线 `<名>_n.jpg`（无 sidecar 时也会自动关联）。
- `surfaces` 限制可贴的面（wall/floor/ceiling/door/window);AI 换肤也强制遵守。
- 窗可以用 RGBA（框不透明、玻璃半透明）。

### 4.3 包围盒缓存（必须做，否则首次 Scan/AI 会逐个原生测量，慢且可能崩）

```bash
python tools/seed-bounds-cache.py --models-dir models --package com.example.testfull --push
```
它离线算好每个模型的包围盒并推送 `.bounds-cache.json`（按设备文件 mtime 校验）。
**模型变动后要重跑一次**(mtime 变了缓存才失效）。

---

## 5. AI 系统怎么工作（改它之前先读）

入口：AI Arrange 面板（或调试钩子）。流程：

1. `buildCatalog` 量好库（缓存优先）→ 每个模型得到 center/halfExtents/bottomOffset/defaultScale/details（蒸馏后的 sidecar)。
2. `buildArrangementMessages` 组 prompt:
   - **system preprompt**：室内设计师角色 + 空间词汇表（next to=0.05–0.3m 缝隙、against wall、around X 且朝向 X、facing、opposite、corner、走道≥0.6m、门前≥1m)+ 房间语义（闭合墙环=一个房间；卧室/浴室/客厅/餐厅/办公的典型组成）+ 家具数据规则（schema v1 字段含义、null=未知、可复用同名模型）+ 风格规则（modern/cozy)+ 输出 schema + 示例。
   - **user**：结构化 ROOM JSON(bounds/walls(带 id)/openings(door|window, 所属墙， 中心， 宽）/已在场的家具（带尺寸）)+ 完整 LIBRARY（名称 + 真实尺寸 + DETAILS)+ 可选 TEXTURES（面/风格/DETAILS)+ 用户请求 + 规则。
3. POST `{base}/chat/completions`(`response_format: json_object`, temperature 0.4, 读超时 180s;gpt-5 约 90s)。
4. `parseAiLayout` 容错解析 → `resolveAiPlacements` 物理校验：
   - 未知模型跳过；位置按"半对角线+基础边距"夹回房间多边形内（45° 旋转也不穿墙）;
   - 与已接受项 XZ 包围盒相交 → 沿远离方向 0.05m 步进推开（tolerance=0，推不开才跳过，最多 4m);
   - AI 的 scale × 模型 defaultScale = 有效 scale（校验与生成共用）。
   - 纹理选择 `resolveAiTextures`（名称模糊归一化匹配 + surfaces 强制）。
5. 应用：有纹理则**先重建房间并等待完成**，再清空旧家具，逐个 `placeFromAi` 生成（全包围盒碰撞体 + CCD + 0.08m 抬升落下）。状态栏报告 placed/adjusted/skipped/notes。

改 prompt 在 `AiArranger.kt` 的 `buildArrangementMessages`；改校验在 `resolveAiPlacements` / `separateFromBoxes`。

---

## 6. 调试手段（都非常用）

- **调试钩子**(debug 包专属）：往设备写文件即触发，无需点 UI:
  ```bash
  adb shell "echo 'give me a modern bedroom' > /sdcard/Android/data/com.example.testfull/files/ai_test_prompt.txt && chmod 666 /sdcard/Android/data/com.example.testfull/files/ai_test_prompt.txt"
  # 触发一次完整 AI 布置。chmod 666 必须（app 读不到 660 的文件，会读到空串并删除）
  adb shell "echo 'place:bed-001' > ...同路径... && chmod 666 ..."   # 模拟"选中模型"（加载 ghost 并开摆放）
  ```
- **日志**(user 版镜像不可见 D 级日志，所以关键日志都打的是 W):`adb logcat -d | grep -E "HomeStage|AiArranger|ModelLibrary|PlacementController|TextureLibrary"`。
  全请求（ROOM JSON）与 AI 原文都在 `AiArranger` 标签里。
- **截图**:`adb exec-out screencap -p > shot.png`。
- **单元测试**:`./gradlew.bat :app:testDebugUnitTest`(PlacementMath/AiArranger/ModelLibrary/TextureLibrary/FloorPlanModel 五个测试类）。
- **Model Manager**:`node model-manager/server.js` → http://localhost:8931（传/删/清空设备 models 目录）。

---

## 7. 模拟器生存指南（真机没有这些破事）

- **启动必弹"没有响应"**:x86 模拟器用 Houdini 翻译 ARM 空间运行时，冷启动卡 >5s 触发启动 ANR。
  **点"等待"**（不是"关闭应用")，一次就好。
- **LMK（低内存杀手）**：内存紧张时内核连系统进程一起杀（logcat 见 `Zygote: Process X exited due to signal 9`)。
  应对：贴图 ≤1024(§4)、AI 生成前释放 ghost（代码已做）、大模型一个个来、必要时 `pico-cli emulator stop/start`。
- **性能**：正常 ~1–15 FPS;`FRAME_TOO_SLOW` 刷屏是常态不是病。
- **不要在应用运行时强推覆盖安装后立即交互**，先 force-stop 再 launch。
- **save-state/快照**删了没关系（冷启动一次），`sdcard.img/userdata` 别删（有你的模型和 app)。

---

## 8. 代码地图（改哪里，看这里）

`app/src/main/java/com/example/testfull/`:

- `content/HomeStage.kt` — 总装配。状态（plan/模型/纹理/AI)、四个面板的挂点与位置、摆放 HUD 的跟随
  （150ms 限频 + 抖动死区 + 远距离瞬移，防 ANR)、房间重建流程、AI 编排 `runAiArrange`、调试钩子。
- `content/FloorPlanDesigner.kt` — **平面图编辑器面板**（墙/门/窗绘制、属性 Inspector、环境切换、虚拟行走）。
- `content/FurniturePanels.kt` — 另外三个面板：FurnitureLibrary（选模型/缩放/清空）、PlacementHud（跟随
  视线的摆放 HUD)、AiArrange(prompt/预设/纹理槽）。PanelFrame 统一外壳。
- `content/ObjectPlacement.kt` — 摆放引擎 `PlacementController`：瞄准射线 + ghost 预览（真实尺寸、面向
  用户、重叠时滑开）、drop/AI 生成共用的 `spawnPlaced`（每物体独立 ShapeResource!)、**选择代际计数**
  （防快速连点产生幽灵实体）、`dropInFlight` 防并发加载。
- `content/AiArranger.kt` — prompt 构建、JSON 解析、物理校验（夹取/推开）、纹理解析、HTTP、模型目录
  （含 defaultScale 计算与 bounds 缓存读取）。
- `content/ModelLibrary.kt` — 模型扫描、sidecar 读取与"蒸馏"（递归剔除 null/空，保留决策字段）、
  intended-size 解析、defaultScale 计算、bounds 缓存读写、实体可视包围盒测量。
- `content/TextureLibrary.kt` — 纹理扫描/sidecar 解析/TextureCache。
- `content/GeneratedRoom.kt` — 由平面图生成房间（墙体 solids、门窗洞口、地板/天花/灯光、每面材质）。
- `content/FloorPlanModel.kt` — 平面图数据与几何（`demoFloorPlan()` 默认户型在这改！)、夹取工具。
- `platform/LaunchActivity.kt`, `platform/SpatialApplication.kt`, `Main.kt` — 启动壳。
- `AndroidManifest.xml` — `pico.spatial.stage.*` 元数据（style: 0 自动/1 Mixed/2 Progressive/3 Full, immersion)。

测试：`app/src/test/java/com/example/testfull/content/*Test.kt`。

---

## 9. SDK/环境的事实清单（都是踩过的坑，别再踩）

1. `MeshResource.load(path, FROM_STORAGE)` **不支持 GLB**(FORMAT_UNSUPPORTED)；量包围盒用
   `Entity.getVisualBounds(relativeTo = entity, recursive = true)`(**必须主线程**；且不含场景根节点
   自身变换——烘尺寸时要烘到顶点/子节点，别只改根）。
2. `Entity.loadSuspend` 大模型（>10MB）内存峰值 ~2×,**连续/并发加载会触发 LMK**；先释放旧 ghost 再加载。
3. 每个掉落物必须**独立 ShapeResource/材质**，共享句柄会被 SDK 提前关闭（"not shape resource id: 0")。
4. SDK 会随实体销毁关闭其材质 —— **不要把材质缓存了复用到别的实体**(ghost 材质必须每次新建）。
5. `ModelComponent.materials` getter 每次都回 native 拉取 —— 一次取值用到底，别在循环里反复读（ANR)。
6. 面板表面每次 Transform 变更都整面重绘 —— HUD 更新务必限频。
7. user 版镜像 logcat 看不到 `Log.d`，诊断日志用 `Log.w`。
8. `CollisionComponent` 无 per-shape 偏移，用 `ShapeResource.offsetByTranslation(center)`（中间体记得 close)。
9. glTF 根节点变换会被 SDK 折进实体 TransformComponent;`spawnPlaced` 显式 setScale 会覆盖它。

---

## 10. 常见任务食谱

- **加家具**:§4 两步处理 + sidecar + 推送 + seed 缓存。
- **改默认户型**:`FloorPlanModel.kt` 的 `demoFloorPlan()`；注意 `FloorPlanModelTest` 里有关联断言。
- **换 AI 模型/端点/key**:`local.properties` 的 `ai.api.*` + 重新构建安装。
- **换默认纹理**:AI Arrange 面板五个槽位循环选择 → Apply textures（会重建房间、清空家具）。
- **调面板位置/尺寸**:`HomeStage.kt` 初始定位（room-plan/furniture-library/ai-arrange 的坐标与 yaw)
  和 `FurniturePanels.kt` 的 `PanelFrame(width, height)`;HUD 跟随参数在同文件顶部常量区。
- **AI 改 prompt**:`AiArranger.kt` 的 `buildArrangementMessages`；改完跑 `AiArrangerTest`。
- **全新设备首跑顺序**:§1 环境 → §2 配置 → §3 构建安装 → `pico-cli emulator start` → §4.3 seed 缓存
  → app 里 Furniture → Scan → 开玩。
```
