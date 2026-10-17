# Z-Flow 兼容性补丁（独立版）

从 [Z-Flow](https://github.com/ldbsmh/Z-Flow) 中把**兼容性修复补丁单独拆出来**，做成一个轻量 Xposed 模块。
不含小窗本体功能，只做三件修复 —— 因为小窗功能本身还在 Z-Flow 里更新，没必要为了这几个补丁去重编整个小窗 App。

## 包含的三个补丁

| 补丁 | 修复内容 | 生效方式 |
| --- | --- | --- |
| **微信小窗标题栏重复修复** | 修正微信在小窗中标题栏重复出现 | 把微信加入 LSPosed 作用域；开关切换后切回微信前台即生效 |
| **小窗顶部空白修复** | 修正部分应用在小窗顶部预留状态栏高度导致空白（如起点读书） | 把目标应用加入 LSPosed 作用域；开关切换后切回应用前台即生效 |
| **关闭 Telegram 画中画** | 修正 Telegram 系应用在小窗中只剩角落一个小画面 | 把目标应用加入 LSPosed 作用域。注意：主屏上也会一并失去画中画 |

## 技术要点

- **框架 API：libxposed API 102**（`io.github.libxposed:api` / `:service`，`minApiVersion=102`）。
  用的是现代 Xposed API，不是老的 `de.robv.android.xposed`。
- **工具链**：AGP 9.2.1 + Gradle 9.4.1 + compileSdk 37。
  因为 libxposed 102 的 AAR 声明了 `minCompileSdk=37`。
- **设置同步**：走 libxposed 的 *remote preferences*，模块 App 写、被 hook 的进程读。
  LSPosed 会把改动推给已加载的目标进程并触发变更回调，所以开关**不用重启目标应用**，切回前台即可生效。

## 详细日志（应对混淆变化）

微信等应用更新后混淆名会变，补丁可能因此失效。模块内置了一套可开关的详细日志：

- **详细日志**（默认关）：把每一步探测过程写进 LSPosed 日志 ——
  候选类列表、每个候选类命中的方法名与签名、判定依据、最终选中的目标、失败原因与堆栈。
- **日志输出到框架**（默认开）：关掉后只保留 Warning/Error。

日志统一前缀 `[ZFlowCompat]`，在 LSPosed 管理器 → 日志里过滤这个前缀即可。
开详细日志后切回目标应用前台，就能看到完整探测过程；据此更新补丁里的候选名列表。

## 构建

推送到 `main`/`master` 分支即触发 GitHub Actions 自动构建，产物在 Actions 的 Artifacts 里。
本地构建：

```bash
./gradlew :app:assembleRelease
```

## 安装

1. 安装 APK，在 LSPosed 管理器里启用本模块。
2. 在模块作用域里勾选目标应用（微信 / 起点读书 / Telegram 系）。
3. 重启目标应用。
4. 打开本 App 的界面，按需开关三个补丁。

## 与 Z-Flow 的关系

本仓库是从 Z-Flow 中提取的补丁集合，**不替代** Z-Flow。两个模块可以共存，但三个补丁的开关需各自管理。

Z-Flow 上游已通过 [PR #1](https://github.com/ldbsmh/Z-Flow/pull/1) 移除这三项修复，把功能完整移交给本模块。上游合并后，Z-Flow 不再提供这三个开关；此前开启过修复的用户，请在本模块中重新开启。

## 许可

本项目是 Z-Flow 的衍生作品，沿用其许可证：**GNU General Public License v3.0 or later**（见 [LICENSE](LICENSE)）。

- Copyright (C) 2021-2022 sunshine0523
- Copyright (C) 2023 DtHnAme
- Copyright (C) 2024-2026 relimus

本程序为自由软件：你可以根据 GNU 通用公共许可证（由自由软件基金会发布）的条款，按第 3 版或（由你选择）任何更新版本重新分发和/或修改。本程序的分发是希望它有用，但没有任何保证；甚至没有对适销性或特定用途适用性的默示保证。详见 GNU 通用公共许可证。

