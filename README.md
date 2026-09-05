# Debug Menu（调试菜单）

*[English](README_en.md)*

一个独立的 Minecraft Fabric 调试工具 Mod：把常用的调试开关、HUD 覆盖层和玩家行为日志集中到一个可滚动的菜单里，同时开放 API 让其他 Mod 把自己的调试开关挂进来。

- Minecraft：1.20.1（默认）/ 1.20.4（同一份源码，构建时选择目标版本）
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

## 构建

默认目标是 `gradle.properties` 中 `default_mc` 指定的版本（当前为 **1.20.1**），直接构建和启动开发客户端即可：

```powershell
./gradlew build
./gradlew runClient
```

要切到别的版本，用 `-Pmc`（PowerShell 下建议加引号）：

```powershell
./gradlew build "-Pmc=1.20.4"
./gradlew runClient "-Pmc=1.20.4"
```

产物在 `build/libs/` 下，文件名带游戏版本，例如 `debug-menu-mc1.20.1-1.0.0.jar`。

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
