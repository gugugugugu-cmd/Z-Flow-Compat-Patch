# 微信版本适配指南 —— 以 8.0.76 为例的完整思路

> 本文是"微信升级后补丁失效怎么办"的操作手册。8.0.76 适配（2026-10-24）的完整
> 分析结论见 [README 第 25-29 节](../README.md)；本文只讲**方法论**：如何用不变的
> 锚点重新定位会变的混淆名，如何验证语义没漂移，以及代码里到底要改哪几行。
>
> 分析工具：MT 管理器 MCP（`mt mcp`）。没有 MCP 时用任意反编译工具（jadx /
> MT 管理器手动）完成同样的步骤即可，本文每一步都标注了对应的工具调用。

---

## 0. 核心原则：锚点不变量

微信每个版本的混淆名（`ui.bl`、`u0`、`y0`、`L0`、`j`…）都可能整体洗牌，
**不要记名字，要记锚点**。以下东西跨版本稳定，是所有定位工作的入口：

| 锚点类型 | 已知实例 | 为什么稳定 |
| --- | --- | --- |
| 日志字符串 | `[trySetListViewMargin]`、`setStatusBarMaskView height origin: %d, new: %d isInMultiWindowMode:%s`、`[trySetParentViewPadding]`、`saveTopMargin topMargin: %d, needRead: %b`、`getTopHeight statusHeight:%s`、`WeUIToolHelper`、`[fixStatusBarHeight]` | 微信自己排障要用，不会删 |
| SharedPreferences 键 | `Main_top_marign`（微信原始拼写就是 marign）、`Main_need_read_top_margin` | 迁移成本高，拼写错误也要兼容 |
| 反射资源名 | `status_bar_height`、`com.android.internal.R$dimen` | 反射目标，改了就取不到值 |
| 框架方法名 | `onConfigurationChanged`、`getContext`、`isInMultiWindowMode`、`getLayoutParams` | 系统 API，永不混淆 |
| 方法签名 | `(Landroid/content/Context;I)I`、`(II)V` | 混淆不改签名 |
| 大类类名 | `MainUI`、`ConversationListView`、`BaseAddressUIFragment`、`FindMoreFriendsUI`、`NewChattingTabUI`、`LauncherUI` | 低于 65536 个类不触发重混淆，稳定集合 |
| 日志 tag | `MicroMsg.MainUI`、`MicroMM.ConversationListView` | 同日志字符串 |

反例：`ui.ol` / `ui.bl`（工具类）、`H0` / `u0`（布局方法）、`I0` / `u0()`（通讯录高度）、
`w7` / `l7`（重试 Runnable）——这些**每个版本都必须重新定位**。

---

## 1. 版本差异速查表（持续维护）

适配新版本前先更新这张表，代码改动基本就是把新名字填进候选列表。

| 用途 | 8.0.5x | 8.0.76 | 8.0.79 |
| --- | --- | --- | --- |
| WeUIToolHelper 工具类 | `ui.gl` / `ui.gk` / `ui.gm` | **`ui.bl`** | `ui.ol` |
| 状态栏高度主入口 | 同签名 | `bl.i(Context, int)` | `ol.i(Context, int)` |
| ActionBar 高度 | 同签名 | `bl.a(Context)` | `ol.a(Context)` |
| 首页读高度 → 写布局 | — | `MainUI.y0()` → `u0(II)` | `MainUI.K0()` → `H0(II)` |
| 多窗口标志字段 | — | `MainUI.H` | `MainUI.H` |
| 首页异步重试 | — | `conversation.l7` | `conversation.w7` |
| 通讯录顶部总高度 | — | `BaseAddressUIFragment.u0()I` | `…I0()I` |
| 发现页顶部总高度 | — | `FindMoreFriendsUI.L0()I`（**8.0.76 起有缓存分支**） | 无缓存分支 |
| 聊天容器顶部高度 | — | `NewChattingTabUI.j()I`（读缓存） | 未知/无 |
| 缓存写入者 | — | `ConversationListView.v(IZ)` | 同名 |
| 缓存读取者集合 | — | 通讯录 + 发现 + 聊天容器 | 仅通讯录 |

---

## 2. 五步定位法

### 第一步：开工作区

```
mt_apk_workspace_open(path="微信_<版本>.apk", temporary=false)
```

注意：文件名含中文时，JSON 里用 `\u5fae\u4fe1` 转义（Windows curl 直传 UTF-8
会编码错乱导致 `PATH_NOT_FOUND`）。保存返回的 `workspaceId`，后面所有调用都用它。

### 第二步：定位状态栏工具类（WeUIToolHelper）

**入口锚点：反射字符串 `status_bar_height`。**

```
mt_apk_search(target="smali", query="status_bar_height", prefix="com.tencent.mm.ui")
```

命中若干类，找那个**类体很小、全静态方法、且同时出现 `WeUIToolHelper` 日志 tag**
的抽象类（8.0.79=`ui.ol`，8.0.76=`ui.bl`）。然后读类轮廓核对方法族：

```
mt_apk_dex_read_class_outline(locator="dex_class:Lcom/tencent/mm/ui/bl;")
```

需要确认存在（名字可能变，签名不会变）：

| 方法签名 | 语义（看 interestingStrings） |
| --- | --- |
| `(Context, int) → int` | 主入口，字符串含 `[fixStatusBarHeight]`、`[getStatusBarHeight]` |
| `(Context) → int` | 薄包装，invoke 计数 ≤ 3 |
| `(Context, int) → int`（另一个） | 反射 `com.android.internal.R$dimen`，字符串含 `status_bar_height` |
| `(Context) → int`（另一个） | ActionBar 高度，字符串含 `getActionBarHeight` |

### 第三步：定位首页链路

**入口锚点：日志串 `trySetListViewMargin`。**

```
mt_apk_search(target="dex_strings", query="trySetListViewMargin")
```

命中两个类：`MainUI`（同步路径）和重试 Runnable（8.0.76=`conversation.l7`）。然后：

1. 读 `MainUI` 轮廓，**按日志串找方法**：`[trySetListViewMargin] statusHeight` 所在
   方法 = 读高度入口（`y0`/`K0`）；`setListMargin` + `setStatusBarMaskView` 所在
   方法 = 布局写入者（`u0`/`H0`）；
2. **不要依赖方法名**：代码里对布局写入者按 `(II)V` 签名唯一定位。用轮廓确认
   `MainUI` 已声明方法里 `(II)V` 只有一个（8.0.76 实测仅 `u0`）；
3. 读两个方法的 smali 还原逻辑（`mt_apk_read_text`，locator 用
   `dex_method:L…;->u0(II)V`）。重点核对三件事：
   - 守卫条件：`当前值 > 0 && H == false → 跳过`，`H == true → 强制清零`；
   - 调用门：读高度入口是否 `status <= 0 时不调用布局方法`（8.0.76 是；
   这正是双行残留的根源，见 README §26）；
   - `H` 字段类型（`Z`）与写入点（`onConfigurationChanged` / tab 创建）；
4. 交叉验证调用闭包（防止漏掉别的写入者）：

```
mt_apk_dex_xref(locator="dex_method:L…MainUI;->u0(II)V", methodResolution="dispatch")
mt_apk_dex_xref(locator="dex_field:L…MainUI;->H:Z", methodResolution="not_applicable")
```

### 第四步：定位 `Main_top_marign` 缓存生态

**入口锚点：偏好键 `Main_top_marign`。**

```
mt_apk_search(target="dex_strings", query="Main_top_marign")
```

对每个命中类读轮廓，找同时满足"返回 `int`、无参、interestingInvokes 含
工具类 `->a` 与 `->i`、字符串含 `getTopHeight`"的方法 = 读取者；字符串含
`saveTopMargin` 的 = 写入者。**每个版本重新数一遍读取者集合**——8.0.79 只有
通讯录，8.0.76 变成通讯录 + 发现 + 聊天容器三个，这类"缓存读者扩容"是版本间
最常见的漂移。

### 第五步：填表改代码

代码里只有四处跟版本相关，其余逻辑（虚拟屏判定、开关、Tinker、日志）不动：

```text
app/src/main/java/io/relimus/zflow/compat/xposed/patch/WeChatStatusBarFix.kt
├── CANDIDATE_CLASSES   ← 把新工具类名插到列表第一位，旧的保留兜底
├── TARGET_METHOD / FALLBACK_METHOD  ← 签名变了才动（至今没变过：i / h）
├── TOP_HEIGHT_TARGETS  ← 按第四步结果增删（类名稳定，改方法名候选）
└── （不需要改）布局写入者按 (II)V 签名定位、触发器是框架方法
    onConfigurationChanged、字段 H —— 都是稳定锚点
```

---

## 3. 验证清单

1. **安装层**：LSPosed 日志出现
   `命中候选类 com.tencent.mm.ui.<新名>` +
   `已挂钩 <新名>#i —— 虚拟屏仅首页会话列表高度归零` +
   每个缓存读取者一条 `已挂钩…`。任何一条缺失 → 对应定位失败，开详细日志看探测过程；
2. **功能层**（README §29）：
   - 小窗冷启动：首页单行标题，通讯录/发现首项不被标题栏覆盖；
   - 小窗 ↔ 全屏来回 ≥ 5 次：每次切回小窗首页只有一行；详细日志应出现
     `首页清零 margin/mask（临时 H=true 走微信强制清零分支）`；
   - 通讯录/发现展开后缩回不重新变宽；
   - 回全屏后顶部恢复正常状态栏高度（清零不能污染主屏）；
3. **回归层**：设置里关闭微信补丁开关 → 强停微信 → 小窗行为应与不装模块一致。

## 4. 排障速查

| 症状 | 结论 | 动作 |
| --- | --- | --- |
| 日志 `未找到可用目标方法` | 工具类或两个方法签名都变了 | 重做第二步 |
| `已挂钩`有但布局无变化 | 挂到了非主入口（重载命中错方法） | 看详细日志的候选方法签名 |
| 首页好了但通讯录变宽 | 读取者集合变了（扩容/改名） | 重做第四步 |
| 切回小窗仍偶发双行 | `onConfigurationChanged` 未触发或 `H` 字段改名 | 查 `无法唯一定位 (II)V` / `字段 H 不可用` 日志 |
| `(II)V 无法唯一定位` | MainUI 新增了同签名方法 | 读轮廓找出正确那个，代码改为按日志串白名单定位 |

## 5. 已验证过但不应重复采用的方案

历史教训（细节见 README §14、§26）：

- 仅 hook `h(Context)` 薄包装 —— 8.0.79 实证主界面不走它；
- 对所有非主屏 `i()` 统一返回 0 —— 通讯录/发现首项被标题栏覆盖；
- 只挂布局写入者（H0/u0）等它被调用来清残留 —— 小窗里它**永远不会被调用**
  （`status <= 0` 时调用方提前 return），必须由 `onConfigurationChanged` 主动触发；
- 清空 `Main_top_marign` 缓存 —— 动了微信全局状态，主屏行为可能被破坏。
