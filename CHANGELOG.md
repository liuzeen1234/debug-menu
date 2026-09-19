# 更新日志（Changelog）

本项目所有值得记录的改动都会写在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
每个游戏版本单独出包，产物名形如 `debug-menu-mc1.20.4-<版本>.jar`。

## [1.0.1] - 2026-09-19

### 新增

- **`/showmods` 命令**：把当前实例加载的 Mod 逐条打印到聊天框。
  - 分类标注：**功能**（绿色）、**库/内置**（灰色 `[库]`，如加载器 / Java / Minecraft / `fabric-*` 系列）、**未生效**（金黄 `[未生效·仅XX端]`，运行端与当前端不匹配）。
  - `/showmods` 默认只列功能与未生效项，隐藏库/内置；`/showmods all` 列出全部。
  - 显示名优先用通过 `DebugMenuApi.setModDisplayName` 注册的名字，否则回退 Fabric 识别出的 Mod 名，末尾附 `[mod id]`。
  - 追加扫描 `mods/` 目录，报告磁盘上存在但未生效的 jar：`.disabled` 标 `[已禁用]`（淡紫）、普通 jar 但未加载标 `[未加载]`（红）。识别不出 mod 名时回退显示从 `.class` 推断的顶层 Java 包名，再不行才显示文件名。
- **`/showshaders` 命令**：扫描 `shaderpacks/` 目录，逐条列出光影包并标出当前启用的光影。
  - 内部含 `shaders/` 目录判为光影包（`[光影]`），否则 `[非光影]`；显示名取文件名/文件夹名。
  - 当前启用光影从 Iris（`config/iris.properties`）或 OptiFine（`optionsshaders.txt`）读取。
  - 尊重光影总开关：Iris 的 `enableShaders=false` 视为未启用（`shaderPack=` 残留不再误报）；OptiFine 关闭态（`(internal)`/`OFF`/`none`）同样按未启用处理。
  - 通过反射调用 Iris 公开 API `IrisApi.getInstance().isShaderPackInUse()` 识别**加载/编译失败**：配置选了某光影但 Iris 报告未生效时，标红 `[未生效·可能加载失败]`。
  - 先判断运行时是否存在光影加载器（Iris/Oculus/OptiFine）：无加载器时忽略配置残留，不再把任何光影标为启用。
- 中英文 README 补充上述两条命令的说明。

### 变更

- Mod 版本从 `1.0.0` 升至 `1.0.1`。

### 修复

- 修复 `/showshaders` 在光影被全局禁用、或光影加载器（Iris）被禁用/未加载时，仍读取配置残留而误报"启用中"的问题。

## [1.0.0] - 2026-09

首个版本。核心功能：

- 统一的调试开关菜单，按 Mod ID 分组、支持折叠与滚动，并开放 API 供其他 Mod 注册自己的调试条目（布尔开关、数值滑条、多状态开关、二级/条件显示、自定义分组显示名）。
- HUD 覆盖层：实体血量显示（含可选 NBT 详情，检测距离可配置）与手持物品信息（含进阶模式）。
- 玩家行为实时日志，覆盖战斗、移动、物品交互与客户端输入。
- 控制台日志转发到游戏聊天框，可在菜单中开关并设置最低级别；内置"生成测试日志"按钮。
- 配置持久化到 `config/debug-menu.json`。
- 多版本支持：同一份源码可构建 Minecraft 1.20.1 / 1.20.4（`-Pmc` 选择目标）。

[1.0.1]: https://github.com/liuzeen1234/debug_menu/releases/tag/v1.0.1
[1.0.0]: https://github.com/liuzeen1234/debug_menu/releases/tag/v1.0.0
