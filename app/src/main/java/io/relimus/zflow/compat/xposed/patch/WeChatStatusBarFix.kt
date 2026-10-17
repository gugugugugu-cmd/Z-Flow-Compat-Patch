package io.relimus.zflow.compat.xposed.patch

import android.content.Context
import android.view.Display
import io.github.libxposed.api.XposedModule
import io.relimus.zflow.compat.xposed.util.PatchLog
import io.relimus.zflow.compat.xposed.util.PatchSettings
import io.relimus.zflow.compat.xposed.util.Reflect
import java.lang.reflect.Method

/**
 * 微信小窗双标题栏修复（per-app 补丁）。
 *
 * ## 问题
 *
 * 微信在小窗（虚拟屏）里顶部会出现两条相同的标题栏：上层可点击，下层点不动。
 * 反编译定位到 `ConversationListView` 的滚动偏移计算：
 *
 * ```
 * int statusBarHeight = gl.h(context);     // ← 本补丁的 hook 目标
 * int actionBarTop = this.E;
 * if (actionBarTop <= 0 && !this.J) {
 *     actionBarTop = statusBarHeight;      // ActionBar 贴顶时按状态栏高度兜底
 * }
 * this.s = gl.a(context) + actionBarTop;   // scrollOffset
 * setSelectionFromTop(1, this.s);          // 面板头部据此上移
 * ```
 *
 * 主屏上 ActionBar 被状态栏推下来（`E = 88`），兜底不触发，`s` 正确，任务栏面板完全藏住。
 * 虚拟屏上没有状态栏，ActionBar 永远贴顶（`E = 0`），兜底每次都注入一个不存在的状态栏
 * 高度 → `s` 偏大 136px → 任务栏面板底部那条 bar 露在屏内。因为面板逻辑上已关闭，
 * 所以不接收触摸，看起来就是"重复了一层、还点不动"。
 *
 * ## 做法
 *
 * hook 微信的状态栏高度入口，**非主屏时返回 0**：
 * - 虚拟屏：`E = 0` → 兜底拿到 0 → `s = gl.a` = ActionBar 高度 → 面板完全藏住
 * - 主屏：`E = 88 ≠ 0` → 兜底不触发 → 返回值用不到 → **正常使用零影响**
 *
 * ## 混淆名会随版本变化
 *
 * 类名 `com.tencent.mm.ui.gl` 与方法名 `h` 都是混淆产物。微信升级后可能整体改名，
 * 补丁会"静默跳过"（行为与不装补丁一致）。打开设置里的**详细日志**后，
 * LSPosed 日志会完整打印探测过程，可直接判断是类名变了还是方法名变了。
 */
object WeChatStatusBarFix {

    private const val NAME = "WeChatStatusBarFix"

    /**
     * 候选类名：混淆名随版本变化，按顺序尝试，命中即止。
     *
     * - `ui.ol`：8.0.79（当前实测版本）命中，方法名仍为 `h(Context)`，
     *   内部走 `ml.a(ctx, android:dimen/status_bar_height)` → `ol.i` 换算。
     * - `ui.gl`：8.0.5x 及更早版本的历史名。
     * - `ui.gk` / `ui.gm`：邻近版本兜底。
     */
    private val CANDIDATE_CLASSES = listOf(
        "com.tencent.mm.ui.ol",
        "com.tencent.mm.ui.gl",
        "com.tencent.mm.ui.gk",
        "com.tencent.mm.ui.gm",
    )

    /** 目标方法名：`int h(Context)` */
    private const val TARGET_METHOD = "h"

    fun install(module: XposedModule, classLoader: ClassLoader, pkg: String) {
        if (pkg != "com.tencent.mm") return

        if (!PatchSettings.isEnabled(null, PatchSettings.KEY_WECHAT_STATUSBAR_FIX)) {
            PatchLog.info("$NAME: 开关已关闭，跳过安装")
            return
        }

        PatchLog.info("$NAME: 开始探测目标（微信进程）")
        PatchLog.debug("$NAME: 候选类共 ${CANDIDATE_CLASSES.size} 个 -> ${CANDIDATE_CLASSES.joinToString()}")

        var installed = false
        for (className in CANDIDATE_CLASSES) {
            val clazz = Reflect.findClass(className, classLoader)
            if (clazz == null) {
                PatchLog.debug("$NAME: 候选类 $className 未找到（可能已改名）")
                continue
            }
            PatchLog.debug("$NAME: 命中候选类 $className")

            // 详细日志：把这个类里所有同名方法签名打出来，方便确认参数是否变了
            logCandidateMethods(clazz, className)

            val method = resolveTarget(clazz, className) ?: continue
            val ok = hookMethod(module, method, className)
            if (ok) {
                installed = true
                break
            }
        }

        if (!installed) {
            PatchLog.warn(
                "$NAME: 未找到可用的目标方法，补丁未生效。" +
                    "若微信刚升级，多半是混淆名变化——请查看上面的详细日志定位。"
            )
        }
    }

    /** 优先精确匹配 `h(Context)`，失败再按"名字 + 单个 Context 参数"宽松匹配。 */
    private fun resolveTarget(clazz: Class<*>, className: String): Method? {
        val exact = Reflect.findMethodExact(clazz, TARGET_METHOD, Context::class.java)
        if (exact != null) {
            PatchLog.debug("$NAME: 精确命中 $className#$TARGET_METHOD(Context)")
            return exact
        }
        PatchLog.debug("$NAME: $className 无精确方法 $TARGET_METHOD(Context)，尝试宽松匹配")

        val loose = Reflect.findMethods(clazz, TARGET_METHOD).firstOrNull { m ->
            m.parameterCount == 1 && Context::class.java.isAssignableFrom(m.parameterTypes[0])
        }
        if (loose != null) {
            PatchLog.debug("$NAME: 宽松命中 $className#$TARGET_METHOD(${loose.parameterTypes[0].name})")
        } else {
            PatchLog.debug("$NAME: $className 中不存在可取的状态栏高度方法 $TARGET_METHOD")
        }
        return loose
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
            PatchLog.info("$NAME: 已挂钩 $className#$TARGET_METHOD —— 非主屏时状态栏高度归零")
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 $className#$TARGET_METHOD 失败", it)
        }.getOrDefault(false)
    }

    /**
     * 判定与改写。返回 0 表示"虚拟屏上不存在状态栏"；否则一律放行原逻辑。
     *
     * 注意这里用**实时查询** `Context.getDisplay()`，而不是缓存"前台显示设备"。
     * 微信被多任务上滑搬进小窗时 Activity 不会重新 resume，缓存值会停在主屏，
     * 导致判定失效。
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

        PatchLog.debug("$NAME: displayId=$displayId 非主屏 → 状态栏高度置 0")
        return 0
    }

    /** 详细日志：打印候选类里所有同名方法的签名。 */
    private fun logCandidateMethods(clazz: Class<*>, className: String) {
        if (!PatchLog.verbose) return
        val methods = Reflect.findMethods(clazz, TARGET_METHOD)
        if (methods.isEmpty()) {
            PatchLog.debug("$NAME: $className 内无名为 $TARGET_METHOD 的方法")
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
