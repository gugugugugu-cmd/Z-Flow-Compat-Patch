package io.relimus.zflow.compat.xposed.patch

import android.content.Context
import android.view.Display
import io.github.libxposed.api.XposedModule
import io.relimus.zflow.compat.xposed.util.PatchLog
import io.relimus.zflow.compat.xposed.util.PatchSettings
import io.relimus.zflow.compat.xposed.util.Reflect
import java.lang.reflect.Method
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
 * `com.tencent.mm.ui.ol#i(Context, int)`（WeUIToolHelper.getStatusBarHeight）：
 *
 * ```
 * MainUI.K0 / MainUI.C0（微信 tab）、FindMoreFriendsUI.a1/N1（发现）、
 * BaseAddressUIFragment.I0/M0（通讯录）… 全部调用 ol.i(ctx, -1)
 * ```
 *
 * `ol.i` 内部：
 *
 * 1. 先尝试从 `WindowInsets` 类取状态栏高度（`n3.g3.a(1).b`），取不到为 0；
 * 2. 若该值 < 25dp，走 `ol.k` 反射读 `com.android.internal.R$dimen.status_bar_height`
 *    （真机 124px）；
 * 3. 微信自己的 `[fixStatusBarHeight]` 用
 *    `displayHeight - windowVisibleFrame.height` 估算真实状态栏高度：
 *    - 主屏：估算 ≈ 124 → 与系统值一致，正常；
 *    - 虚拟屏：无系统状态栏 → 估算 ≈ 0 → `if-le` 不采用 → 仍返回 124px；
 *
 * 于是小窗里微信把 124px 的“幽灵状态栏高度”注入所有 tab 页布局 → 顶部出现
 * 双标题栏 / 空额头 / 功能项被顶下去 / 不可点击的第二行。
 *
 * ## 旧实现为什么失效
 *
 * 旧实现 hook `ol.h(Context)`。反编译 8.0.79 的 xref 显示 `ol.h` 只被
 * 视频号(Finder)、设置、webview 等**次级页面**调用，主界面三个 tab 全部走
 * `ol.i(Context, int)`。所以旧补丁“挂钩成功但从不触发”——日志里没有任何
 * “非主屏 → 状态栏高度置 0”的运行记录，与用户观察完全吻合。
 *
 * ## 做法
 *
 * hook `ol.i(Context, int)`，非主屏时返回 0：
 * - 小窗：所有主界面 tab 的状态栏高度变为 0 → 不预留幽灵状态栏 → 双标题栏消失；
 * - 主屏：返回值不被使用或与实际一致，行为不变。
 *
 * `ol.i` 的第二个参数在全部主界面调用点都是 `-1`（fallback 值），hook 时不必校验。
 *
 * ## 混淆名会随版本变化
 *
 * 类名 `com.tencent.mm.ui.ol` 与方法名 `i` 都是混淆产物。微信升级后可能整体改名，
 * 补丁会"静默跳过"（行为与不装补丁一致）。打开设置里的**详细日志**后，
 * LSPosed 日志会完整打印探测过程，可直接判断是类名变了还是方法名变了。
 */
object WeChatStatusBarFix {

    private const val NAME = "WeChatStatusBarFix"

    /**
     * 候选类名：混淆名随版本变化，按顺序尝试，命中即止。
     *
     * - `ui.ol`：8.0.79（当前实测版本）命中，`i(Context,int)` 为状态栏高度主入口
     *   （主界面三个 tab 全部走它；`h(Context)` 只是次级页面的薄包装）。
     * - `ui.gl` / `ui.gk` / `ui.gm`：8.0.5x 及更早版本的历史名，保留兜底。
     */
    private val CANDIDATE_CLASSES = listOf(
        "com.tencent.mm.ui.ol",
        "com.tencent.mm.ui.gl",
        "com.tencent.mm.ui.gk",
        "com.tencent.mm.ui.gm",
    )

    /** 目标方法名：`int i(Context, int)` —— 微信全站状态栏高度主入口 */
    private const val TARGET_METHOD = "i"

    /** 兜底方法名：`int h(Context)` —— 旧版本 / 次级页面入口 */
    private const val FALLBACK_METHOD = "h"

    /** 微信 Tinker 热更新加载器；补丁 dex 会替换 base.apk 里的 ui.ol。 */
    private const val TINKER_LOADER = "com.tencent.tinker.loader.TinkerLoader"

    /** 每个实际 ClassLoader 只安装一次，避免 base 与 Tinker 回调重复挂钩。 */
    private val hookedLoaders = ConcurrentHashMap.newKeySet<ClassLoader>()

    /** TinkerLoader.tryLoad 仅需挂一次。 */
    private val tinkerHookInstalled = AtomicBoolean(false)

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
                hookContactTopHeight(module, classLoader, source)
                return true
            }
        }

        hookedLoaders.remove(classLoader)
        PatchLog.warn("$NAME: source=$source 未找到可用目标方法")
        return false
    }

    /**
     * 微信在 onPackageReady 之后才加载 Tinker 补丁 dex。base.apk 中的 ui.ol 虽能挂钩，
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
     * 通讯录单独维护顶部高度：ActionBar + statusHeight / root.getTop +
     * SharedPreferences(Main_top_marign)。展开窗口时缓存被写入，缩回小窗后仍会读取，
     * 因而只有通讯录再次变宽。虚拟屏上直接返回 ActionBar 高度，忽略主屏缓存；
     * 主屏放行原逻辑。
     */
    private fun hookContactTopHeight(
        module: XposedModule,
        classLoader: ClassLoader,
        source: String,
    ) {
        val className = "com.tencent.mm.ui.contact.address.BaseAddressUIFragment"
        val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: run {
            PatchLog.debug("$NAME: source=$source 未找到通讯录类 $className")
            return
        }
        val method = Reflect.findMethodExact(clazz, "I0") ?: run {
            PatchLog.debug("$NAME: source=$source 未找到通讯录 I0()")
            return
        }
        runCatching {
            module.hook(method).intercept { chain ->
                val fragment = chain.thisObject ?: return@intercept chain.proceed()
                val getContext = Reflect.findMethod(fragment.javaClass, "getContext", 0)
                val ctx = Reflect.callMethod(fragment, getContext) as? Context
                val displayId = runCatching { ctx?.display?.displayId }.getOrNull()
                if (displayId == null || displayId == Display.DEFAULT_DISPLAY ||
                    !PatchSettings.isEnabled(ctx, PatchSettings.KEY_WECHAT_STATUSBAR_FIX)
                ) {
                    return@intercept chain.proceed()
                }

                // ol.a(Context) 是微信 ActionBar 高度入口；I0 原逻辑在它上面继续叠加
                // statusHeight / root.getTop / Main_top_marign，正是残留宽度来源。
                val helper = runCatching {
                    Class.forName("com.tencent.mm.ui.ol", false, classLoader)
                }.getOrNull() ?: return@intercept chain.proceed()
                val actionBar = Reflect.findMethodExact(helper, "a", Context::class.java)
                    ?: return@intercept chain.proceed()
                val height = runCatching {
                    actionBar.isAccessible = true
                    actionBar.invoke(null, ctx) as? Int
                }.getOrNull() ?: return@intercept chain.proceed()

                PatchLog.debug(
                    "$NAME: displayId=$displayId 通讯录 I0() → 仅保留 ActionBar 高度 $height"
                )
                height
            }
            PatchLog.info("$NAME: source=$source 已挂钩通讯录 I0()，清除跨窗口 Main_top_marign")
        }.onFailure {
            PatchLog.warn("$NAME: source=$source 挂钩通讯录 I0() 失败", it)
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
            PatchLog.info("$NAME: 已挂钩 $className#${method.name} —— 非主屏仅首页会话列表高度归零")
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

        // ol.i 是全局入口：通讯录、发现和首页都会调用。冷启动小窗时若全部归零，
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
