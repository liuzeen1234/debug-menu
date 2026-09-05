# 1.20.1 / 1.20.4 功能同步方案

本文档记录 `debug_menu` 项目在 `1.20.1` 分支与 `main` 分支之间同步功能的分析结果与执行计划。

## 背景

- `main` = Minecraft **1.20.4**（初始版本）
- `1.20.1` 分支 = 在 `main` 基础上多出 6 个提交，是 `main` 的**超集**

`1.20.1` 分支不仅支持了 1.20.1，还顺带引入了一整套新功能，并把构建改造成了「一份源码多版本」的结构。

## `1.20.1` 分支比 `main` 多出的功能

### 1. 调试数值条目系统（滑条）
在原有布尔开关（`DebugToggleEntry`）基础上新增整数数值条目，在菜单里渲染成滑条。

- `api/DebugValueEntry.java`（新增）：数值条目模型，含 `min/max/step`、单位后缀、权限校验（默认 OP 等级 ≥2）、`Side`（CLIENT/SERVER/BOTH）侧别标注
- `api/DebugMenuApi.java`：新增 `registerValue` / `registerAllValues` / `getValueEntries` / `getValueEntriesByMod(side)` / `getValueEntry(key, side)` 等 API；`hasEntries()` 计入数值条目
- `client/DebugValueSliderWidget.java`（新增）：滑条 UI 控件
- `client/DebugMenuScreen.java`：渲染滑条、分组显示
- `config/DebugMenuConfig.java`（新增）：数值配置持久化

### 2. 网络同步（客户端 ↔ 服务端）
- `network/DebugValueRequestC2SPacket.java`（新增）：客户端请求
- `network/DebugValueSyncC2SPacket.java`（新增）：客户端 → 服务端改值，含权限校验
- `network/DebugValueSyncS2CPacket.java`（新增）：服务端 → 客户端回写
- `DebugMenuMod.java` / `client/DebugMenuClient.java`：注册收发包处理器
- `Side` 侧别过滤，解决单人游戏（客户端与内置服务端同 JVM）同 key 出现两条条目导致的错乱/重复渲染

### 3. 多版本支持与构建/兼容改造
- `build.gradle` + `gradle.properties`：`-Pmc=1.20.1 / 1.20.4` 切换编译目标，产物名带版本号，`fabric.mod.json` mc 依赖变量化
- `client/ScreenCompat.java`（新增）：跨版本兼容层，自绘背景绕开 `renderBackground` 签名差异
- `DebugMenuScreen` 两套 `mouseScrolled` 签名兼容两个版本
- `run-client.sh`、`.gitattributes` 等辅助脚本

## 方案对比

### 方案 A（推荐）：合并 `1.20.1` 到 `main`，让 main 成为单仓库多版本代码库
一份源码 + `-Pmc` 开关，分别编译产出两个 jar。

```
git checkout main
git merge origin/1.20.1
# 若希望 main 默认仍为 1.20.4，合并后把 gradle.properties 的 default_mc 改回 1.20.4
```

- 优点：一次 merge 到位（1.20.1 是 main 超集，几乎无冲突）；功能 + 网络同步 + 多版本构建全拿到；后续「改一次 = 两版本生效」，长期维护成本最低
- 代价：main 里会永久带上 `ScreenCompat`、双 `mouseScrolled` 这类跨版本兼容样板代码

### 方案 B：只 cherry-pick 功能到 main，main 保持纯 1.20.4
```
git checkout main
git cherry-pick f4a4d29   # 数值条目系统 + 网络同步
git cherry-pick 3a490bf   # Side 侧别过滤修复
```

- 优点：main 代码保持纯净单版本
- 代价：`DebugMenuScreen.java` 大概率有冲突需手工解；长期双分支并行，每次功能同步要各做一遍，容易漂移

## 兼容性与维护成本对比

| 维度 | 方案 A | 方案 B |
|---|---|---|
| 支持版本 | 一份代码覆盖 1.20.1 + 1.20.4 | main 仅 1.20.4，1.20.1 靠另一分支 |
| 跨版本 API 差异 | 已内建兼容层，机制现成 | main 不引入兼容层，代码更干净但无跨版本能力 |
| 功能改一次 | 改一处，两版本同时生效 | 两条分支各改一次 |
| 首次同步成本 | 低（一次 merge） | 较高（cherry-pick + 解冲突） |
| 加新版本（如 1.21） | map 里加一行 | 再开一条分支 |
| 回归风险 | 需两个版本都测 | main 改动天然隔离 |

## 关于「单个 jar 同时兼容两版本」的澄清

> **单个 jar 无法同时兼容 1.20.1 和 1.20.4** —— 这是 Fabric 生态固有约束（yarn 映射不同、MC API 破坏性变更、fabric-api 钉死版本、mod.json 单区间），对任何 Fabric 项目都成立。

方案 A **不是**做「一个 jar 通吃」，而是「一份源码 → 分别编译出两个 jar」。两者不冲突。

debug_menu 能走单仓库多构建的关键原因：

- **本项目没有任何 Mixin**（纯 API + Screen），避开了「Mixin 按精确签名注入、跨版本签名一变就崩」这一最致命的坑
- 对 MC 的接触面只有 Screen/DrawContext 少数几处，且已用兼容层 / 双签名重载处理妥当

## 未来风险与防护

- **风险**：一旦以后给 debug_menu **新增 Mixin**，或用到某个跨版本改名的 API，单源码就可能在某个版本编译不过或运行崩溃。这类错误只有在真正用 `-Pmc=<该版本>` 编译时才暴露，平时 IDE 只按一套 mapping 索引，看不出来。
- **防护**：每次改动后两个目标都编译验证；建议加一个「保存 Java 文件即自动双版本编译」的 hook 作为长期兜底。

## 验证记录

- `./gradlew build -Pmc=1.20.1` → BUILD SUCCESSFUL（Minecraft 1.20.1 / fabric-api 0.92.2+1.20.1）
- `./gradlew build -Pmc=1.20.4` → BUILD SUCCESSFUL（Minecraft 1.20.4 / fabric-api 0.97.1+1.20.4）
- 产物：`debug-menu-mc1.20.1-1.0.0.jar`、`debug-menu-mc1.20.4-1.0.0.jar` 各约 63K
- `-Pmc=1.20.4` 客户端已实际启动进入游戏，mod 界面（DebugMenuScreen）正常运行

（本项目实际使用本地 Gradle 8.8 + JDK17 启动，命令见 `run-client.sh`）

## 结论

推荐 **方案 A**：`1.20.1` 分支本就是为多版本设计，合并成本最低且功能最完整，两个目标已实测可构建、1.20.4 可运行。走方案 A 后应配套「双版本编译」关卡，以尽早暴露未来新增代码的跨版本兼容问题。
