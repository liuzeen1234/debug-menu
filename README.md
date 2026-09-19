# Debug Menu（调试菜单）

*[English](README_en.md)*

一个独立的 Minecraft Fabric 调试工具 Mod：把常用的调试开关、HUD 覆盖层和玩家行为日志集中到一个可滚动的菜单里，同时开放 API 让其他 Mod 把自己的调试开关挂进来。

- Minecraft：1.20.4（默认）/ 1.20.1（同一份源码，构建时选择目标版本）
- Fabric Loader：>= 0.15.0（依赖 Fabric API）
- Java：17
- 环境：客户端 + 服务端
- 作者：liuzeen1234（liuzeen1234@qq.com）
- 许可：MIT

## 功能

### 统一的调试菜单

按下自定义按键（默认未绑定，需在「选项 → 按键绑定 → 调试菜单」中设置）打开调试功能菜单。菜单会自动读取所有已注册的调试开关，按 Mod ID 分组展示；开关较多时支持滚动。没有任何注册项时，菜单只显示自带的 HUD 设置入口。

### HUD 覆盖层

- **实体血量显示**（画面右上角）：显示准星所指实体的名称与血量，格式 `[名称][当前/最大]`；非生物实体显示 `[名称][-/-]`。可开启详细 NBT 信息，NBT 由客户端向服务端请求后缓存展示。检测距离可配置（1~256，默认 128）。
- **手持物品信息**（画面左上角）：显示主手物品名称与数量。开启进阶模式后额外显示耐久度和完整 NBT 标签（缩放渲染，长行自动折行）。

### 玩家行为实时日志

打开后把玩家行为写入日志（logger 名 `DebugMenu`），覆盖范围：

- 战斗与状态：攻击、受伤、死亡、饥饿值变化
- 移动与姿态：跳跃、移动、疾跑 / 潜行 / 游泳 / 飞行状态切换
- 物品与交互：丢弃物品、快捷栏切换、使用物品、右键方块、破坏方块
- 客户端输入：键盘按键、鼠标点击与滚轮、界面打开与关闭

### 列出已加载 Mod：`/showmods`

在聊天框输入命令，把当前实例加载的 Mod 逐条打印出来：

- `/showmods`：只列出**功能 Mod**（绿色）和**未生效 Mod**，隐藏库/内置项。
- `/showmods all`：列出**全部**，含灰色标注的库/内置项（`[库]`）。

每行格式为 `[标签] 显示名 [mod id]`：

- **显示名**：优先用通过 `DebugMenuApi.setModDisplayName` 在 API 中注册的名字；未注册时回退到 Fabric 识别出的 Mod 名（`fabric.mod.json` 的 `name`）。末尾始终附上 mod id 便于精确辨识。
- **分类标注**：
  - **功能 Mod**（绿色，无前缀）——普通功能 Mod。
  - **库/内置**（灰色，`[库]`）——加载器、Java、Minecraft、Fabric API 系列（`fabric-*`）等基础库；默认隐藏，`/showmods all` 才显示。
  - **未生效**（金黄，`[未生效·仅XX端]`）——元数据声明的运行端与当前端不匹配（如仅服务端的 Mod 出现在客户端），虽被列出但在当前端不起作用，与已加载的功能 Mod 区分开。

列表按「功能 → 库 → 未生效」排序，同类按 mod id 字典序，末尾附一行数量汇总。该命令为纯客户端命令（`fabric-command-api-v2`），单机与联机均可用。

此外两种用法都会额外扫描游戏目录下的 `mods/` 文件夹，报告磁盘上存在但**未生效**的 jar（运行时 Mod 列表看不到这些）：

- **已禁用**（淡紫，`[已禁用]`）——文件名以 `.disabled` 结尾、被手动禁用的 jar。
- **未加载**（红色，`[未加载]`）——普通 `.jar` 但其 id 不在运行时已加载集合里，通常是加载失败、依赖缺失或非 Fabric jar。

扫描会读取 jar 内的 `fabric.mod.json` 取出名字、版本与 id（`[标签] 名字 版本 [id] (文件名)`）；识别不出 mod 名时（非 Fabric jar / 无 `fabric.mod.json` / 解析失败），退化为显示从 jar 内 `.class` 推断出的顶层 Java 包名（如 `com.example`，标注 `(包名)`），连包名都推断不出时才只显示文件名。没有任何禁用/未加载项时不输出该段。

### 识别光影包：`/showshaders`

扫描游戏目录下的 `shaderpacks/` 文件夹，把识别到的光影包逐条打印到聊天框，并标出当前启用的光影：

- 遍历目录下的 `.zip` 和子文件夹，内部含 `shaders/` 目录的判为光影包，标 `[光影]`（淡紫）；否则标 `[非光影]`（灰，可能是误放的其它文件）。
- 光影包没有统一的元数据名，显示名取**文件名/文件夹名**（`.zip` 去后缀），文件夹形式额外标 `(文件夹)`。
- 当前启用的光影从 Iris（`config/iris.properties`）或 OptiFine（`optionsshaders.txt`）读取；命中的那项行首标绿色 `[启用中]`，末尾附一行 `当前启用: xxx`。未启用时显示"当前未启用光影"；配置里写了但目录中找不到对应文件时会提示。
- 会尊重光影**总开关**：Iris 里 `shaderPack=` 只记录"上次选中"的光影，全局禁用时不会清空，因此判定时还会读 `enableShaders`——为 `false` 时一律视为未启用，避免"已禁用却显示启用中"。OptiFine 的关闭态（`(internal)` / `OFF` / `none`）同样按未启用处理。
- 会先看**光影加载器是否在场**：只有运行时确实加载了 Iris（或 Oculus）/ OptiFine 时，光影才可能生效。若没有任何加载器（比如 Iris 被禁用为 `.jar.disabled`），则忽略配置里残留的 `shaderPack=`，不再把任何光影标为启用，末尾提示"未检测到光影加载器，光影不会生效；配置中残留选择: X"。
- 会识别**加载/编译失败**：装有 Iris 时，会通过反射调用 Iris 公开 API（`IrisApi.getInstance().isShaderPackInUse()`）查询光影是否**真的在生效**——该接口在光影包编译失败时返回 false。若配置选了某光影、文件也在，但 Iris 报告未生效，则那行标红 `[未生效·可能加载失败]`，末尾提示"已选择 X，但 Iris 报告未生效（建议查 logs/latest.log）"。用反射是为了不硬依赖 Iris。OptiFine 无等价 API，加载器在场但无法查询运行时状态时退回按配置（选中即视为启用）。

同为纯客户端命令（`fabric-command-api-v2`）。

### 配置持久化

所有开关状态与 HUD 设置写入 `config/debug-menu.json`，修改即时保存，重进游戏后保留。

## 给其他 Mod 开发者的 API

在自己 Mod 的初始化阶段注册开关，调试菜单会自动生成对应 UI：

```java
DebugMenuApi.register(new DebugToggleEntry(
    "my-mod",                    // 所属 Mod ID（用于分组）
    "my-mod:feature_debug",      // 唯一标识
    "功能调试",                   // 菜单显示名
    () -> myDebugEnabled,        // getter
    v -> { myDebugEnabled = v; saveConfig(); }   // setter
));
```

其他可用方法：

- `DebugMenuApi.registerAll(Collection<DebugToggleEntry>)` 批量注册
- `DebugMenuApi.isEnabled(String key)` 在自己的代码里查询开关状态
- `DebugMenuApi.getEntries()` / `getEntriesByMod()` / `getEntry(key)` 读取已注册条目

注册表基于 `CopyOnWriteArrayList`，可安全地跨线程读取。

### 自定义分组显示名

菜单默认按 `modId` 分组并把 `modId` 当作分组标题。想让标题显示更友好的名字，在初始化时登记一次即可：

```java
DebugMenuApi.setModDisplayName("my-mod", "我的模组");
```

- 对该 `modId` 下的所有条目（布尔开关 / 数值滑条 / 多状态开关）统一生效，只需调用一次。
- 未登记时标题回退显示 `modId`，完全向后兼容。
- 分组、折叠、查找等仍以 `modId` 为键，改显示名不影响这些逻辑。
- 传入 `null` 或空白串会清除已登记的显示名（回退到 `modId`）；`getModDisplayName(modId)` 可读取当前显示名（未登记则返回 `modId`）。

### 数值条目（滑条，支持服务端同步）

需要一个受 min/max/step 约束的整数值时，用 `DebugValueEntry` 注册，菜单会渲染成滑条：

```java
DebugMenuApi.registerValue(new DebugValueEntry(
    "my-mod",                // 所属 Mod ID
    "my-mod:spawn_rate",     // 唯一标识
    "刷新速率",               // 菜单显示名
    0, 100,                  // min / max（含）
    () -> spawnRate,         // getter
    v -> { spawnRate = v; saveConfig(); }   // setter（内部已 clamp 到 [min, max]）
));
```

关键约定：

- **两侧注册**：同一个 `key` 需要在**客户端与服务端各注册一次**。客户端那条服务于 UI（滑条本地显示、发包），服务端那条服务于收到同步包后在服务端主线程执行。两侧靠相同的 `key` 对应。
- **side 标注**：每条 entry 用 `DebugValueEntry.Side`（`CLIENT` / `SERVER` / `BOTH`）标注服务哪一侧。单人环境下客户端与内置服务端在同一 JVM，用 side 过滤可避免 UI 重复渲染、回写更新错对象。只有当两侧 getter/setter 指向**同一份状态**时才用 `BOTH`。
- **权限**：服务端应用来自客户端的数值前会做权限校验，默认要求玩家权限等级 `>= 2`。可在完整构造里传入自定义 `BiPredicate<ServerPlayerEntity, Integer>` 覆盖。
- **可选项**：完整构造支持自定义步长（`step > 0`）与单位后缀（如 `"格"`、`"%"`）。
- 其他方法：`registerAllValues(...)` 批量注册、`getValueEntry(key)` / `getValueEntry(key, side)` 查询、`getValueEntriesByMod(side)` 按 Mod 分组（按 side 过滤并去重）。

### 二级 / 条件显示开关

可以让一个开关只在满足条件时才出现在菜单里，用于折叠出「父开关 → 子选项」的层级结构。通过给 `DebugToggleEntry` 传入可见性谓词实现：

```java
// 仅当父布尔开关 my-mod:feature 开启时显示
DebugMenuApi.register(new DebugToggleEntry(
    "my-mod", "my-mod:detail", "细节子选项",
    () -> detailOn, v -> { detailOn = v; save(); },
    DebugMenuApi.visibleWhenEnabled("my-mod:feature")));

// 仅当父多状态开关 my-mod:mode 处于「高级」或「专家」状态时显示
DebugMenuApi.register(new DebugToggleEntry(
    "my-mod", "my-mod:expert_opt", "专家选项",
    () -> expertOn, v -> { expertOn = v; save(); },
    DebugMenuApi.visibleWhenOption("my-mod:mode", "高级", "专家")));
```

- `visibleWhenEnabled(parentKey)`：父布尔开关开启时才显示；父 key 不存在视为未开启。
- `visibleWhenOption(parentKey, states...)`：父多状态开关的当前状态命中 `states` 之一时才显示；父 key 不存在或状态不匹配则不显示。

### 多状态开关（状态名自定义）

除了开/关两态的布尔开关，还可以注册**有多个状态、状态名完全自定义**的开关（例如语言选择）。菜单会把它渲染成一个按钮，点击时在各状态之间循环切换：

```java
DebugMenuApi.registerOption(new DebugOptionEntry(
    "my-mod",                              // 所属 Mod ID（用于分组）
    "my-mod:language",                     // 唯一标识
    "语言",                                 // 菜单显示名
    java.util.List.of("English", "简体中文", "日本語"),  // 状态名列表（顺序即循环顺序）
    () -> currentLanguage,                 // getter：返回当前状态名
    v -> { currentLanguage = v; saveConfig(); }        // setter：写入新状态名
));
```

说明：

- 状态以**名称（String）**为准，`getter` 应返回状态列表中的一个；若返回无效名称（或 `null`），菜单会退回到第一个状态，不会崩溃。
- 多状态开关是**纯客户端**概念（与布尔开关一致），状态只在客户端切换，不涉及服务端同步。
- 其他方法：`registerAllOptions(...)` 批量注册、`getSelectedOption(String key)` 查询当前状态名、`getOptionEntries()` / `getOptionEntriesByMod()` / `getOptionEntry(key)` 读取已注册条目。

## 构建

默认目标是 `gradle.properties` 中 `default_mc` 指定的版本（当前为 **1.20.4**），直接构建和启动开发客户端即可：

```powershell
./gradlew build
./gradlew runClient
```

要切到别的版本，用 `-Pmc`（PowerShell 下建议加引号）：

```powershell
./gradlew build "-Pmc=1.20.1"
./gradlew runClient "-Pmc=1.20.1"
```

产物在 `build/libs/` 下，文件名带游戏版本，例如 `debug-menu-mc1.20.4-1.0.0.jar`。

> 编译固定使用 JDK 17（见 `gradle.properties` 的 `org.gradle.java.home` 与 `build.gradle` 的 toolchain）。若换机器导致 JDK 17 路径不同，改 `gradle.properties` 里那一行即可。

各目标版本对应的 Yarn 映射与 Fabric API 版本集中在 `build.gradle` 的 `supportedVersions` 里，新增版本只需加一条记录。

## 许可

本项目基于 [MIT License](LICENSE) 发布。

```
MIT License

Copyright (c) 2026 liuzeen1234

特此免费授予任何获得本软件及相关文档文件（以下简称"软件"）副本的人不受限制地
处置该软件的权利，包括但不限于使用、复制、修改、合并、发布、分发、再许可和/或
出售软件副本，以及允许获得软件的人这样做，但须满足以下条件：

上述版权声明和本许可声明应包含在软件的所有副本或主要部分中。

本软件按"原样"提供，不附带任何明示或暗示的担保，包括但不限于对适销性、特定用途
适用性和非侵权性的担保。在任何情况下，作者或版权持有人均不对任何索赔、损害或其他
责任负责，无论是在合同诉讼、侵权行为或其他方面，由软件或软件的使用或其他交易
引起、产生或与之相关。
```

> 上述中文翻译仅供参考，具有法律效力的版本为仓库根目录下的英文 [LICENSE](LICENSE) 文件。
