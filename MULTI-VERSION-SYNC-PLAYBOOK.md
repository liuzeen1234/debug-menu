# 多 MC 版本分支同步 · 通用操作手册

把「两条 MC 版本移植分支」评估并合并成「一份源码多版本构建」的可复用流程。
适用场景：Fabric（或类似）mod 有 `main` 与另一个版本分支（如 `1.20.1`），想把功能同步到一起。

> 核心认知：**「一个 jar 同时兼容多版本」不可行**（Fabric 固有约束）。
> 可行的目标是**「一份源码 + 版本开关 → 分别编译出多个 jar」**。全程围绕这个目标。

---

## 阶段 0 · 摸清两分支关系（先诊断，别急着合）

```bash
git branch -a                                  # 有哪些分支
git log --oneline <A>..<B>                      # B 比 A 多的提交
git log --oneline <B>..<A>                      # A 比 B 多的提交（判断是否双向分叉）
git diff --stat <A> <B>                         # 文件级差异总览
git show <commit> -s --stat                     # 逐个关键提交看改了啥
```

判断落到哪一类，决定后续策略：

- **超集关系**（一条分支包含另一条全部提交）→ 合并几乎零冲突，首选方案 A。
- **双向分叉**（两边各有独有提交）→ 合并会有冲突，需逐个评估，或考虑 Stonecutter / 多平台模板。

同时确认核心业务逻辑两边是否已一致：
```bash
git diff <A> <B> -- <核心业务目录>              # 为空说明只差版本适配层
```

---

## 阶段 1 · 判断「单源码多构建」是否可行（关键闸门）

逐条核对下面这些「跨版本地雷」，命中越多越难：

| 检查项 | 怎么查 | 影响 |
|---|---|---|
| 是否有 **Mixin** | 搜 `@Mixin` / `mixins.json` | **最致命**：Mixin 按精确方法签名注入，跨版本签名一变即崩。无 Mixin 大大加分 |
| 是否用到**跨版本改名/改签名的 API** | 对比两分支 `build.gradle` 的 yarn 版本；搜可疑调用 | 编译期直接报错（如 `renderBackground` 单参 vs 四参） |
| **fabric-api / loader** 版本差异 | 对比两分支 `build.gradle` | 需按目标各选各的，不能混用 |
| **mod.json 版本区间** | 看 `depends.minecraft` | 需变量化，不能写死单一区间 |

判定：
- 无 Mixin、跨版本 API 差异点少且可封装 → **方案 A（单源码多构建）可行**。
- 有 Mixin 且签名跨版本变化、或改名 API 大面积 → 优先 **Stonecutter/预处理** 或 **多平台模板**。

---

## 阶段 2 · 三种落地方案与选择

| 方案 | 做法 | 适用 | 成本 |
|---|---|---|---|
| **A 单仓库多构建** | 一份源码 + `-Pmc` 开关，分别编译出多个 jar | 无 Mixin、差异可封装、想一条主线 | 首次低、长期低 |
| **B 多分支多构建** | 每个版本一条分支各出 jar | 想保持各分支纯净、版本差异大 | 每次同步各做一遍，易漂移 |
| **C Stonecutter / 多平台模板** | 预处理注释或 common+version module | 版本多、差异深（含 Mixin） | 改造量大 |

本手册重点是**方案 A** 的执行。

---

## 阶段 3 · 方案 A 执行步骤

### 3.1 前置：改造 build 为多版本（若目标分支已改造好可跳过）

`build.gradle` 用一个版本 map + `-Pmc` 参数选择目标：
```gradle
ext.supportedVersions = [
  '1.20.1': [ minecraft:'1.20.1', yarn:'1.20.1+build.10', loader:'0.15.11', fabricApi:'0.92.2+1.20.1', dependency:'~1.20.1' ],
  '1.20.4': [ minecraft:'1.20.4', yarn:'1.20.4+build.3',  loader:'0.15.11', fabricApi:'0.97.1+1.20.4', dependency:'~1.20.4' ],
]
def mcTarget = (project.findProperty('mc') ?: project.property('default_mc')).toString()
def target = supportedVersions[mcTarget] ?: { throw new GradleException("Unsupported: ${mcTarget}") }()
base { archivesName = "your-mod-mc${target.minecraft}" }   // 产物名带版本，避免互相覆盖
```
- `gradle.properties` 提供 `default_mc` 与 `mod_version`。
- `fabric.mod.json` 里 `minecraft` 依赖用 `${minecraft_dependency}` 占位，在 `processResources` 里 expand。

### 3.2 处理跨版本 API 差异（两种通用手法）

1. **签名变化 → 封装绕开**：不直接调那个会变的方法，自建一个工具类用两版本都稳定的底层 API 实现（例：自绘背景代替 `renderBackground`）。
2. **方法删除/新增 → 两个签名都写，都不加 `@Override`**：让它们都成为普通重载方法，编译期不校验父类；运行期由 MC 回调实际存在的那个（例：两套 `mouseScrolled`）。

### 3.3 合并（安全姿势：先不提交）

```bash
git status --short                 # 确认工作区干净
git checkout main
git merge origin/<version-branch> --no-commit --no-ff   # 停在提交前，便于检查
git status --short                 # 查看合并进来/有无冲突
```
- 有冲突：逐个解决（重点在版本适配文件，如 Screen、build.gradle）。
- 若希望 main 默认目标不变，改回 `gradle.properties` 的 `default_mc`。

### 3.4 全新构建验证两个版本（关键）

```bash
./gradlew clean                    # 先清掉旧产物，确保是全新构建
./gradlew build -Pmc=1.20.1        # 目标 A
./gradlew build -Pmc=1.20.4        # 目标 B（切目标时不要再 clean，否则删掉上一个 jar）
ls -lhT build/libs/*.jar           # 确认两个 jar 都存在且时间戳是刚生成的
```
- 两个目标都要各自 `compileJava` + 打包通过。**编译成功才算跨版本兼容验证通过**——IDE 平时只按一套 mapping 索引，看不出另一版本的问题。
- 有条件再各跑一次 `runClient -Pmc=<版本>` 进游戏确认运行期正常。

### 3.5 落地

确认无误后再提交（提交不易回退，需明确决定）：
```bash
git commit                         # 完成 merge commit
```
放弃则：`git merge --abort` 恢复原样。

---

## 阶段 4 · 长期维护防护

单源码多版本最大的隐患是**未来新增代码可能在某个版本编不过/崩**，且只有编译该目标时才暴露。建议：

- **提交前双版本编译**成硬性习惯（两个 `build -Pmc=...` 都过）。
- 配一个「保存 Java 文件即自动双版本 `compileJava`」的钩子/CI 作业兜底。
- **谨慎引入 Mixin**：一旦加 Mixin，跨版本签名差异会把方案 A 拖回不可行区，需重新评估。
- 在 README 记录「本项目为何能单仓库多构建、以及什么改动会破坏它」。

---

## 一页速查（Checklist）

```
[ ] git log/diff 摸清分支关系（超集？双向分叉？核心逻辑是否一致）
[ ] 排雷：有无 Mixin / 跨版本改名 API / fabric-api·loader 差异 / mod.json 区间
[ ] 选方案：A 单源码多构建 / B 多分支 / C Stonecutter
[ ] build.gradle 多版本化（-Pmc + 版本 map + 产物名带版本 + mod.json 变量化）
[ ] 跨版本 API 差异用「封装」或「双签名不加 @Override」处理
[ ] git merge --no-commit --no-ff，检查/解冲突，设回 default_mc
[ ] gradlew clean，再逐个 build -Pmc=<版本>，确认两个 jar 全新生成
[ ] （可选）runClient 各版本进游戏验证运行期
[ ] 确认后 git commit；不满意 git merge --abort
[ ] 加双版本编译兜底（hook/CI），README 记录约束
```
