package io.relimus.zflow.compat.xposed.patch

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.Display
import io.github.libxposed.api.XposedModule
import io.relimus.zflow.compat.xposed.util.PatchLog
import io.relimus.zflow.compat.xposed.util.PatchSettings
import io.relimus.zflow.compat.xposed.util.Reflect
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信小窗双标题栏修复（per-app 补丁）。
 *
 * ## 问题
 *
 * 微信在小窗（虚拟屏）里顶部会出现两条相同的标题栏：上层可点击，下层点不动；
 * 通讯录/发现等 tab 页顶部被拉宽或功能项被顶出屏幕。
 *
 * 反编译微信 8.0.79 确认：主界面（微信 tab / 通讯录 / 发现）的状态栏高度全部来自
 * WeUIToolHelper 的 `int i(Context, int)`（8.0.79 为 `com.tencent.mm.ui.ol`，
 * 8.0.76 为 `com.tencent.mm.ui.bl`）：
 *
 * `ol/bl.i` 内部：
 *
 * 1. 先尝试从 `WindowInsets` 类取状态栏高度，取不到为 0；
 * 2. 若该值 < 25dp，反射读 `com.android.internal.R$dimen.status_bar_height`
 *    （真机 124px）；
 * 3. 微信自己的 `[fixStatusBarHeight]` 用
 *    `displayHeight - windowVisibleFrame.height` 估算真实状态栏高度，
 *    但只接受"比系统值更大、且差距小于 100px"的修正值：
 *    - 主屏：估算 ≈ 124 → 与系统值一致，正常；
 *    - 虚拟屏：估算 ≈ 0 → `if-le` 不采用 → 仍返回 124px；
 *
 * 于是小窗里微信把 124px 的“幽灵状态栏高度”注入 tab 页布局 → 顶部出现
 * 双标题栏 / 空额头 / 功能项被顶下去 / 不可点击的第二行。
 *
 * ## 首页布局链路与“切回小窗后双行残留”的根因（8.0.76 实测反编译）
 *
 * ```
 * MainUI.y0()                          ← 8.0.79 为 K0()，m0/onConfigurationChanged 触发
 *   status = bl.i(ctx, -1)             ← 幽灵高度入口
 *   if (status <= 0) { post(l7); return; }   ← 归零补丁生效后永远走这里！
 *   else u0(margin, status)            ← 8.0.79 为 H0()
 * conversation.l7.run()                ← 8.0.79 为 w7，重试 3 次
 *   status = bl.i(ctx, -1)
 *   if (status <= 0) { retry/give up; return; }   ← 同样永远不调 u0
 *   else u0(margin, status)
 * ```
 *
 * `u0(int,int)` 是列表 topMargin 和状态栏 mask 高度的唯一写入者，带守卫：
 * **当前值 > 0 且 H=false（isInMultiWindowMode）时整体跳过**；H=true 时无条件清零。
 * 虚拟屏上 `isInMultiWindowMode()` 为 false（它是独立屏幕，不是平台多窗口）。
 *
 * 于是：全屏阶段 u0 写入 margin=actionBar / mask=124 → 切回小窗后 bl.i 被归零
 * → y0/l7 全部提前 return，u0 一次都不会被调用 → 全屏残留的 124px mask 永久留在
 * 首页 → 双行状态栏。旧补丁挂在 u0（H0）上的“临时置 H=true”钩子因此是死代码：
 * 它等待的调用永远不会发生。这就是“多次小窗-全屏-小窗切换后偶尔双行”的根源。
 *
 * ## 根治做法
 *
 * 1. hook `bl/ol.i(Context, int)`：虚拟屏 + 首页（conversation.*）调用返回 0
 *    （消除幽灵高度本身）；
 * 2. hook `MainUI.onConfigurationChanged`（框架方法名，永不混淆；跨屏切换必触发）：
 *    微信自己的逻辑跑完后，若仍在虚拟屏，主动调用一次布局方法 `u0(0,0)`，
 *    调用期间临时把字段 H 置 true，让微信自己的“多窗口强制清零”分支把
 *    topMargin/mask 归零（与冷启动小窗的正确布局一致），随后恢复 H；
 * 3. 兜底 hook `u0`（按 `(II)V` 签名在 MainUI 内唯一定位，不依赖混淆名）：
 *    虚拟屏上被调用时同样临时置 H=true，防止显示切换竞态期间写入全屏值。
 *
 * 主屏（DEFAULT_DISPLAY）一律放行原逻辑。
 *
 * ## 通讯录 / 发现 / 聊天容器（8.0.76 变化）
 *
 * 8.0.76 里 `Main_top_marign`（微信原始拼写）全屏缓存的读取者从通讯录一个变成三个：
 *
 * - `BaseAddressUIFragment.u0()`（8.0.79 为 I0）：ActionBar + statusHeight/缓存；
 * - `FindMoreFriendsUI.L0()`：同构逻辑，8.0.79 无缓存分支、8.0.76 新增；
 * - `NewChattingTabUI.j()`：返回纯状态栏高度（不加 ActionBar），也读缓存。
 *
 * 虚拟屏上分别拦截：前两者只返回微信自己的 ActionBar 高度，后者返回 0。
 *
 * ## 混淆名会随版本变化
 *
 * 类名（`ui.bl` / `ui.ol`）与方法名（`i` / `u0` / `L0` …）都是混淆产物。补丁按
 * “候选列表 + 签名扫描”双路探测，升级后新名字未收录时静默跳过（行为与不装补丁
 * 一致）。打开设置里的**详细日志**可看到完整探测过程。新版本适配流程见 README。
 */
object WeChatStatusBarFix {

    private const val NAME = "WeChatStatusBarFix"

    /**
     * 候选类名：混淆名随版本变化，按顺序尝试，命中即止。
     *
     * - `ui.bl`：8.0.76（当前实测版本）命中，`i(Context,int)` 为状态栏高度主入口；
     * - `ui.ol`：8.0.79 命中，同签名；
     * - `ui.gl` / `ui.gk` / `ui.gm`：8.0.5x 及更早版本的历史名，保留兜底。
     */
    private val CANDIDATE_CLASSES = listOf(
        "com.tencent.mm.ui.bl",
        "com.tencent.mm.ui.ol",
        "com.tencent.mm.ui.gl",
        "com.tencent.mm.ui.gk",
        "com.tencent.mm.ui.gm",
    )

    /** 目标方法名：`int i(Context, int)` —— 微信全站状态栏高度主入口 */
    private const val TARGET_METHOD = "i"

    /** 兜底方法名：`int h(Context)` —— 旧版本 / 次级页面入口 */
    private const val FALLBACK_METHOD = "h"

    /** 微信 Tinker 热更新加载器；补丁 dex 会替换 base.apk 里的工具类。 */
    private const val TINKER_LOADER = "com.tencent.tinker.loader.TinkerLoader"

    /** 每个实际 ClassLoader 只安装一次，避免 base 与 Tinker 回调重复挂钩。 */
    private val hookedLoaders = ConcurrentHashMap.newKeySet<ClassLoader>()

    /** TinkerLoader.tryLoad 仅需挂一次。 */
    private val tinkerHookInstalled = AtomicBoolean(false)

    /** MainUI 布局方法（`(II)V`）与 getContext 的解析缓存（Optional 允许缓存"未找到"）。 */
    private val marginSetterCache = ConcurrentHashMap<Class<*>, Optional<Method>>()
    private val getContextCache = ConcurrentHashMap<Class<*>, Optional<Method>>()

    /** 跨屏切换后的延迟兜底清零：等微信自身的 y0/l7 重试链全部跑完。 */
    private const val HOME_RESET_DELAY_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * `Main_top_marign` 全屏缓存的读取者（8.0.76）。虚拟屏上拦截，主屏放行。
     *
     * @param actionBarOnly true = 返回值是“ActionBar + 顶部偏移”的总高度，拦截后只保留
     *   ActionBar 部分（工具类 `a(Context)`）；false = 返回值是纯状态栏高度，拦截后归 0。
     */
    private data class TopHeightTarget(
        val className: String,
        val label: String,
        val methodNames: List<String>,
        val actionBarOnly: Boolean,
    )

    private val TOP_HEIGHT_TARGETS = listOf(
        TopHeightTarget(
            "com.tencent.mm.ui.contact.address.BaseAddressUIFragment",
            "通讯录",
            methodNames = listOf("u0", "I0"), // 8.0.76 / 8.0.79
            actionBarOnly = true,
        ),
        TopHeightTarget(
            "com.tencent.mm.ui.FindMoreFriendsUI",
            "发现",
            methodNames = listOf("L0"), // 8.0.76 新增缓存分支
            actionBarOnly = true,
        ),
        TopHeightTarget(
            "com.tencent.mm.ui.NewChattingTabUI",
            "聊天容器",
            methodNames = listOf("j"),
            actionBarOnly = false,
        ),
    )

    fun install(module: XposedModule, classLoader: ClassLoader, pkg: String) {
        if (pkg != "com.tencent.mm") return

        if (!PatchSettings.isEnabled(null, PatchSettings.KEY_WECHAT_STATUSBAR_FIX)) {
            PatchLog.info("$NAME: 开关已关闭，跳过安装")
            return
        }

        installForLoader(module, classLoader, "base")
        hookTinkerLoader(module, classLoader)
    }

    private fun installForLoader(module: XposedModule, classLoader: ClassLoader, source: String): Boolean {
        if (!hookedLoaders.add(classLoader)) {
            PatchLog.debug("$NAME: classLoader 已处理，跳过 source=$source")
            return true
        }

        PatchLog.info("$NAME: 开始探测目标 source=$source loader=${classLoader.javaClass.name}")
        for (className in CANDIDATE_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            if (clazz == null) {
                PatchLog.debug("$NAME: 候选类 $className 未在 source=$source 找到")
                continue
            }
            PatchLog.debug("$NAME: source=$source 命中候选类 $className loader=${clazz.classLoader.javaClass.name}")
            logCandidateMethods(clazz, className)
            val method = resolveTarget(clazz, className) ?: continue
            if (hookMethod(module, method, className)) {
                hookHomeLayoutReset(module, classLoader, source)
                hookTopHeightGetters(module, classLoader, source, clazz)
                return true
            }
        }

        hookedLoaders.remove(classLoader)
        PatchLog.warn("$NAME: source=$source 未找到可用目标方法")
        return false
    }

    /**
     * 微信在 onPackageReady 之后才加载 Tinker 补丁 dex。base.apk 中的工具类虽能挂钩，
     * 但运行时由 DelegateLastClassLoader 中的补丁类替代，因此必须在 tryLoad 返回后
     * 对当前线程 contextClassLoader 再安装一次。
     */
    private fun hookTinkerLoader(module: XposedModule, classLoader: ClassLoader) {
        if (!tinkerHookInstalled.compareAndSet(false, true)) return
        val loaderClass = Reflect.findClass(TINKER_LOADER, classLoader) ?: run {
            tinkerHookInstalled.set(false)
            PatchLog.debug("$NAME: 未找到 $TINKER_LOADER，无法追踪热更新 ClassLoader")
            return
        }
        val tryLoad = Reflect.findMethods(loaderClass, "tryLoad").firstOrNull { it.parameterCount == 1 } ?: run {
            tinkerHookInstalled.set(false)
            PatchLog.debug("$NAME: 未找到 TinkerLoader.tryLoad(Application)")
            return
        }

        runCatching {
            module.hook(tryLoad).intercept { chain ->
                val result = chain.proceed()
                val appContext = chain.getArg(0) as? Context
                val patchLoader = runCatching { appContext?.classLoader }.getOrNull()
                    ?: Thread.currentThread().contextClassLoader
                if (patchLoader != null) {
                    PatchLog.info("$NAME: Tinker 加载完成，重新探测 loader=${patchLoader.javaClass.name}")
                    installForLoader(module, patchLoader, "tinker")
                }
                result
            }
            PatchLog.info("$NAME: 已挂钩 TinkerLoader.tryLoad，等待热更新类加载完成")
        }.onFailure {
            tinkerHookInstalled.set(false)
            PatchLog.warn("$NAME: 挂钩 TinkerLoader.tryLoad 失败", it)
        }
    }

    /**
     * 首页残留清零，两个钩子配合（见类注释“根治做法”）：
     *
     * 1. `MainUI.onConfigurationChanged` —— 框架方法名永不混淆，且跨屏切换必然触发。
     *    微信自身逻辑（更新 H、调 y0）跑完后仍在虚拟屏的话，post 一个清零任务；
     * 2. `(II)V` 布局方法 —— 按签名在 MainUI 内唯一定位（8.0.76 为 u0、8.0.79 为 H0），
     *    兜底处理“显示切换竞态期间带着全屏参数被调用”的情况。
     *
     * 清零本身复用微信 H=true 的“多窗口强制清零”分支：临时把 H 置 true、调用
     * `u0(0,0)`、恢复 H。冷启动小窗的正确布局（margin=0/mask=0）与此一致。
     */
    private fun hookHomeLayoutReset(
        module: XposedModule,
        classLoader: ClassLoader,
        source: String,
    ) {
        val className = "com.tencent.mm.ui.conversation.MainUI"
        val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: run {
            PatchLog.debug("$NAME: source=$source 未找到首页类 $className")
            return
        }

        // 1) 显示切换触发器
        val onConfig = Reflect.findMethodExact(
            clazz, "onConfigurationChanged", Configuration::class.java
        )
        if (onConfig == null) {
            PatchLog.debug("$NAME: source=$source 未找到首页 onConfigurationChanged")
        } else {
            runCatching {
                module.hook(onConfig).intercept { chain ->
                    val result = chain.proceed()
                    val mainUi = chain.thisObject
                    if (mainUi != null && isVirtualDisplay(mainUi)) {
                        scheduleHomeReset(mainUi)
                    }
                    result
                }
                PatchLog.info("$NAME: source=$source 已挂钩首页 onConfigurationChanged，跨屏切换后主动清零")
            }.onFailure {
                PatchLog.warn("$NAME: source=$source 挂钩首页 onConfigurationChanged 失败", it)
            }
        }

        // 2) u0/H0 兜底（签名定位，不依赖混淆名）
        val setter = findMarginSetter(clazz)
        if (setter == null) {
            PatchLog.debug("$NAME: source=$source 未能在 $className 唯一定位 (II)V 布局方法，跳过兜底钩子")
            return
        }
        runCatching {
            module.hook(setter).intercept { chain ->
                val mainUi = chain.thisObject ?: return@intercept chain.proceed()
                if (!isVirtualDisplay(mainUi) ||
                    !PatchSettings.isEnabled(null, PatchSettings.KEY_WECHAT_STATUSBAR_FIX)
                ) {
                    return@intercept chain.proceed()
                }
                val old = Reflect.getField(mainUi, "H") as? Boolean
                if (old == null || !Reflect.setField(mainUi, "H", true)) {
                    return@intercept chain.proceed()
                }
                try {
                    PatchLog.debug("$NAME: 布局方法被调用，临时 H=true 强制清零 margin/mask")
                    chain.proceed()
                } finally {
                    Reflect.setField(mainUi, "H", old)
                }
            }
            PatchLog.info("$NAME: source=$source 已挂钩首页 ${setter.name}(II)，竞态期间强制清零")
        }.onFailure {
            PatchLog.warn("$NAME: source=$source 挂钩首页 ${setter.name}(II) 失败", it)
        }
    }

    /**
     * post 到主线程执行清零：立即一次 + 延迟一次（等 y0/l7 的异步重试链跑完）。
     * 执行时复查仍在虚拟屏，避免与用户的下一次切屏竞争。
     */
    private fun scheduleHomeReset(mainUi: Any) {
        val doReset = Runnable {
            if (!isVirtualDisplay(mainUi)) return@Runnable
            if (!PatchSettings.isEnabled(null, PatchSettings.KEY_WECHAT_STATUSBAR_FIX)) return@Runnable
            forceClearHomeMargins(mainUi)
        }
        mainHandler.post(doReset)
        mainHandler.postDelayed(doReset, HOME_RESET_DELAY_MS)
    }

    /** 借微信自己的 H=true 分支把列表 topMargin 与状态栏 mask 高度清零。 */
    private fun forceClearHomeMargins(mainUi: Any) {
        val setter = findMarginSetter(mainUi.javaClass) ?: return
        val old = Reflect.getField(mainUi, "H") as? Boolean
        if (old == null || !Reflect.setField(mainUi, "H", true)) {
            PatchLog.debug("$NAME: 首页字段 H 不可用，跳过清零")
            return
        }
        try {
            PatchLog.debug("$NAME: 首页清零 margin/mask（临时 H=true 走微信强制清零分支）")
            Reflect.callMethod(mainUi, setter, 0, 0)
        } finally {
            Reflect.setField(mainUi, "H", old)
        }
    }

    /** 在 MainUI 的已声明方法里按 `(II)V` 签名唯一定位布局方法（u0/H0）。 */
    private fun findMarginSetter(clazz: Class<*>): Method? {
        return marginSetterCache.getOrPut(clazz) {
            val intPrimitive = Int::class.javaPrimitiveType
            val matches = clazz.declaredMethods.filter { m ->
                m.parameterCount == 2 &&
                    m.parameterTypes[0] == intPrimitive &&
                    m.parameterTypes[1] == intPrimitive &&
                    m.returnType == Void.TYPE
            }
            when (matches.size) {
                1 -> Optional.of(matches[0])
                0 -> {
                    PatchLog.debug("$NAME: ${clazz.name} 内无 (II)V 布局方法")
                    Optional.empty()
                }
                else -> {
                    PatchLog.warn("$NAME: ${clazz.name} 内有 ${matches.size} 个 (II)V 方法，无法唯一定位")
                    Optional.empty()
                }
            }
        }.orElse(null)
    }

    /**
     * 拦截 `Main_top_marign` 缓存读取者（通讯录 / 发现 / 聊天容器）。
     * 虚拟屏上：actionBarOnly 的目标只返回微信 ActionBar 高度（忽略缓存与幽灵状态栏），
     * 其余返回 0；主屏放行原逻辑。
     */
    private fun hookTopHeightGetters(
        module: XposedModule,
        classLoader: ClassLoader,
        source: String,
        helperClass: Class<*>,
    ) {
        val actionBarHeight = Reflect.findMethodExact(helperClass, "a", Context::class.java)
        if (actionBarHeight == null) {
            PatchLog.debug(
                "$NAME: ${helperClass.name} 无 a(Context)，" +
                    "actionBarOnly 目标在虚拟屏上将放行原逻辑"
            )
        }

        for (target in TOP_HEIGHT_TARGETS) {
            val clazz = runCatching { Class.forName(target.className, false, classLoader) }.getOrNull() ?: run {
                PatchLog.debug("$NAME: source=$source 未找到${target.label}类 ${target.className}")
                continue
            }
            val method = target.methodNames.firstNotNullOfOrNull { Reflect.findMethodExact(clazz, it) }
            if (method == null) {
                PatchLog.debug("$NAME: source=$source ${target.label}无候选方法 ${target.methodNames}")
                continue
            }
            runCatching {
                module.hook(method).intercept { chain ->
                    val fragment = chain.thisObject ?: return@intercept chain.proceed()
                    val displayId = displayIdOf(fragment)
                    if (displayId == null || displayId == Display.DEFAULT_DISPLAY ||
                        !PatchSettings.isEnabled(null, PatchSettings.KEY_WECHAT_STATUSBAR_FIX)
                    ) {
                        return@intercept chain.proceed()
                    }

                    if (!target.actionBarOnly) {
                        PatchLog.debug(
                            "$NAME: displayId=$displayId ${target.label}返回纯状态栏高度 → 0"
                        )
                        return@intercept 0
                    }

                    val getContext = getContextCache.getOrPut(fragment.javaClass) {
                        Optional.ofNullable(Reflect.findMethod(fragment.javaClass, "getContext", 0))
                    }.orElse(null)
                    val ctx = Reflect.callMethod(fragment, getContext) as? Context
                    if (ctx == null || actionBarHeight == null) {
                        return@intercept chain.proceed()
                    }
                    val height = Reflect.callStatic(actionBarHeight, ctx) as? Int
                        ?: return@intercept chain.proceed()

                    PatchLog.debug(
                        "$NAME: displayId=$displayId ${target.label}顶部总高度 → 仅 ActionBar $height"
                    )
                    height
                }
                PatchLog.info(
                    "$NAME: source=$source 已挂钩${target.label} ${clazz.simpleName}#${method.name}()" +
                        if (target.actionBarOnly) "（仅保留 ActionBar 高度）" else "（状态栏高度归 0）"
                )
            }.onFailure {
                PatchLog.warn("$NAME: source=$source 挂钩${target.label} ${method.name} 失败", it)
            }
        }
    }

    /** 优先精确匹配 `i(Context, int)`，失败再尝试 `h(Context)`。 */
    private fun resolveTarget(clazz: Class<*>, className: String): Method? {
        val intPrimitive = Int::class.javaPrimitiveType ?: run {
            PatchLog.debug("$NAME: 无法取得 int 原始类型，跳过精确匹配")
            return null
        }
        // 主入口：int i(Context, int)。第二个参数为 fallback 值（主界面传 -1）。
        val exact = Reflect.findMethodExact(
            clazz, TARGET_METHOD, Context::class.java, intPrimitive
        )
        if (exact != null) {
            PatchLog.debug("$NAME: 精确命中主入口 $className#$TARGET_METHOD(Context, int)")
            return exact
        }
        PatchLog.debug("$NAME: $className 无精确方法 $TARGET_METHOD(Context, int)，尝试兜底 $FALLBACK_METHOD")

        // 兜底：int h(Context)（旧版本 / 次级页面入口）
        val fallback = Reflect.findMethodExact(clazz, FALLBACK_METHOD, Context::class.java)
        if (fallback != null) {
            PatchLog.debug("$NAME: 兜底命中 $className#$FALLBACK_METHOD(Context)")
            return fallback
        }
        PatchLog.debug("$NAME: $className 中不存在可取的状态栏高度方法（$TARGET_METHOD / $FALLBACK_METHOD）")
        return null
    }

    private fun hookMethod(module: XposedModule, method: Method, className: String): Boolean {
        return runCatching {
            module.hook(method).intercept { chain ->
                val ctx = chain.getArg(0) as? Context
                if (ctx == null) {
                    PatchLog.debug("$NAME: 参数非 Context，放行")
                    return@intercept chain.proceed()
                }
                patch(ctx, chain)
            }
            PatchLog.info("$NAME: 已挂钩 $className#${method.name} —— 虚拟屏仅首页会话列表高度归零")
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 $className#${method.name} 失败", it)
        }.getOrDefault(false)
    }

    /**
     * 判定与改写。返回 0 表示"虚拟屏上不存在状态栏"；否则一律放行原逻辑。
     *
     * 注意这里用**实时查询** `Context.getDisplay()`，而不是缓存"前台显示设备"。
     * 微信被多任务上滑搬进小窗时 Activity 不会重新 resume，缓存值会停在主屏，
     * 导致判定失效。
     *
     * 微信所有主界面调用点传入的都是 Activity Context（MMFragment.getContext()），
     * `ctx.display` 能实时反映窗口当前所在显示设备，两条进入小窗的路径都覆盖。
     */
    private fun patch(ctx: Context, chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
        val displayId = runCatching { ctx.display?.displayId }.getOrNull()
        if (displayId == null) {
            PatchLog.debug("$NAME: 无法取得 displayId，放行（保守策略）")
            return chain.proceed()
        }
        if (displayId == Display.DEFAULT_DISPLAY) {
            PatchLog.debug("$NAME: displayId=$displayId 为主屏，放行")
            return chain.proceed()
        }
        // 开关在进程内缓存，这里再确认一次（设置可能被用户改过）
        if (!PatchSettings.isEnabled(ctx, PatchSettings.KEY_WECHAT_STATUSBAR_FIX)) {
            PatchLog.debug("$NAME: displayId=$displayId 但开关已关闭，放行")
            return chain.proceed()
        }

        // i(Context,int) 是全局入口：通讯录、发现和首页都会调用。冷启动小窗时若全部归零，
        // 通讯录/发现首项会被标题栏覆盖；它们需要保留微信原始高度。只有首页的
        // MainUI / conversation 调用需要归零，消除透明的第二行标题栏。
        val caller = Throwable().stackTrace.firstOrNull { frame ->
            val name = frame.className
            name.startsWith("com.tencent.mm.ui.conversation.") ||
                name == "com.tencent.mm.ui.conversation.MainUI"
        }
        if (caller == null) {
            PatchLog.debug("$NAME: displayId=$displayId 非主屏但非首页调用 → 放行")
            return chain.proceed()
        }

        PatchLog.debug(
            "$NAME: displayId=$displayId 首页调用 ${caller.className}#${caller.methodName} → 状态栏高度置 0"
        )
        return 0
    }

    /** Fragment/Activity 当前是否位于虚拟屏（非默认显示设备）。无法判定时按主屏处理。 */
    private fun isVirtualDisplay(target: Any): Boolean {
        val displayId = displayIdOf(target)
        return displayId != null && displayId != Display.DEFAULT_DISPLAY
    }

    /** 取宿主 Fragment 的 Context 再取 displayId；结果缓存 getContext 解析。 */
    private fun displayIdOf(target: Any): Int? {
        val getContext = getContextCache.getOrPut(target.javaClass) {
            Optional.ofNullable(Reflect.findMethod(target.javaClass, "getContext", 0))
        }.orElse(null) ?: run {
            PatchLog.debug("$NAME: ${target.javaClass.name} 无 getContext，按主屏处理")
            return null
        }
        val ctx = Reflect.callMethod(target, getContext) as? Context ?: return null
        return runCatching { ctx.display?.displayId }.getOrNull()
    }

    /** 详细日志：打印候选类里所有同名方法的签名。 */
    private fun logCandidateMethods(clazz: Class<*>, className: String) {
        if (!PatchLog.verbose) return
        val methods = Reflect.findMethods(clazz, TARGET_METHOD) +
            Reflect.findMethods(clazz, FALLBACK_METHOD)
        if (methods.isEmpty()) {
            PatchLog.debug("$NAME: $className 内无名为 $TARGET_METHOD/$FALLBACK_METHOD 的方法")
            return
        }
        methods.forEach { m ->
            val params = m.parameterTypes.joinToString(", ") { it.simpleName }
            PatchLog.debug(
                "$NAME: 候选方法 $className#${m.name}($params) " +
                    "returns=${m.returnType.simpleName} static=${java.lang.reflect.Modifier.isStatic(m.modifiers)}"
            )
        }
    }
}
