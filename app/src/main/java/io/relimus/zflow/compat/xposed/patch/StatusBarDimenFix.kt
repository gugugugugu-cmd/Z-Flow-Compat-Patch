package io.relimus.zflow.compat.xposed.patch

import android.app.Activity
import android.content.res.Resources
import android.view.Display
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.relimus.zflow.compat.xposed.util.PatchLog
import io.relimus.zflow.compat.xposed.util.PatchSettings
import io.relimus.zflow.compat.xposed.util.Reflect
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通用补丁：在小窗（虚拟屏）上把「框架 status_bar_height」归零。
 *
 * ## 解决什么
 *
 * 一批应用会直接读框架尺寸来预留状态栏高度，与运行时 inset 无关：
 *
 * ```
 * int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
 * return ctx.getResources().getDimensionPixelSize(id);     // ← 本补丁的 hook 目标
 * ```
 *
 * 真机上这截被系统状态栏覆盖，看不出问题；虚拟屏上没有状态栏，这截就裸露成一条与内容
 * 无关的空白（用户俗称「宽额头」）。起点读书即为此类。
 *
 * ## 做法
 *
 * hook `Resources.getDimensionPixelSize(int)`，当入参是 `android:dimen/status_bar_height`
 * **且当前不在主屏**时返回 0。
 *
 * 相比 hook 具体应用的混淆方法，这里用的是**框架 API，跨版本稳定**，对任何"读框架状态栏
 * 尺寸"的应用都生效，不需要逐个逆向。
 *
 * ## 边界
 *
 * - 只对加入了作用域的应用生效。
 * - 只覆盖 `getDimensionPixelSize` 这一条路径；用 `getDimension` /
 *   `getDimensionPixelOffset` / 硬编码 dp 的应用不受影响。
 * - 主屏行为完全不变（`currentDisplayId == DEFAULT_DISPLAY` 时直接返回）。
 */
object StatusBarDimenFix {

    private const val NAME = "StatusBarDimenFix"

    /** 当前所在显示设备；由 Activity.onResume 更新。默认主屏 → 不改任何行为。 */
    @Volatile
    private var currentDisplayId: Int = Display.DEFAULT_DISPLAY

    /** `android:dimen/status_bar_height` 在本进程资源里的 id；-1 = 尚未解析。 */
    @Volatile
    private var statusBarHeightResId: Int = -1

    /** 保证同一进程内只装一次。 */
    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule, classLoader: ClassLoader, pkg: String) {
        if (installed.get()) {
            PatchLog.debug("$NAME: 本进程已安装，跳过 (pkg=$pkg)")
            return
        }
        if (!PatchSettings.isEnabled(null, PatchSettings.KEY_STATUSBAR_DIMEN_FIX)) {
            PatchLog.info("$NAME: 开关已关闭，跳过安装")
            return
        }

        PatchLog.info("$NAME: 开始安装（pkg=$pkg）")

        val activityHooked = hookActivityResume(module, classLoader)
        val resourcesHooked = hookGetDimensionPixelSize(module)

        if (activityHooked || resourcesHooked) {
            installed.set(true)
            PatchLog.info(
                "$NAME: 安装完成 —— Activity.onResume=$activityHooked " +
                    "Resources.getDimensionPixelSize=$resourcesHooked"
            )
        } else {
            PatchLog.warn("$NAME: 两个 hook 点都未装上，补丁未生效")
        }
    }

    /**
     * 记录应用当前所在显示设备。
     * 每个 Activity 进入前台时刷新一次，避免每次取尺寸都去查 Display。
     */
    private fun hookActivityResume(module: XposedModule, classLoader: ClassLoader): Boolean {
        val activityClass = Reflect.findClass("android.app.Activity", classLoader) ?: run {
            PatchLog.debug("$NAME: android.app.Activity 未找到")
            return false
        }
        val onResume = Reflect.findMethodExact(activityClass, "onResume") ?: run {
            PatchLog.debug("$NAME: Activity.onResume 未找到")
            return false
        }

        return runCatching {
            module.hook(onResume).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                val displayId = runCatching { activity?.display?.displayId }.getOrNull()
                if (displayId != null && displayId != currentDisplayId) {
                    PatchLog.debug("$NAME: 前台显示设备切换 $currentDisplayId → $displayId")
                    currentDisplayId = displayId
                }
                result
            }
            PatchLog.debug("$NAME: 已挂钩 Activity.onResume（跟踪前台显示设备）")
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 Activity.onResume 失败", it)
        }.getOrDefault(false)
    }

    private fun hookGetDimensionPixelSize(module: XposedModule): Boolean {
        val resourcesClass = Reflect.findClass("android.content.res.Resources", null) ?: run {
            PatchLog.debug("$NAME: Resources 类未找到")
            return false
        }
        val methods = Reflect.findMethods(resourcesClass, "getDimensionPixelSize")
        if (methods.isEmpty()) {
            PatchLog.debug("$NAME: Resources.getDimensionPixelSize 未找到")
            return false
        }
        PatchLog.debug("$NAME: getDimensionPixelSize 重载数=${methods.size}")

        var ok = false
        for (m in methods) {
            // 只关心 (int) 这个重载
            if (m.parameterCount != 1 || m.parameterTypes[0] != Int::class.javaPrimitiveType) {
                PatchLog.debug("$NAME: 跳过重载 (${m.parameterTypes.joinToString { it.simpleName }})")
                continue
            }
            val hooked = runCatching {
                module.hook(m).intercept { chain -> handle(chain) }
                true
            }.onFailure {
                PatchLog.warn("$NAME: 挂钩 Resources.getDimensionPixelSize(int) 失败", it)
            }.getOrDefault(false)
            ok = ok || hooked
        }

        if (ok) PatchLog.debug("$NAME: 已挂钩 Resources.getDimensionPixelSize(int)")
        return ok
    }

    private fun handle(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()

        if (currentDisplayId == Display.DEFAULT_DISPLAY) return result
        if (!PatchSettings.isEnabled(null, PatchSettings.KEY_STATUSBAR_DIMEN_FIX)) return result

        val res = chain.thisObject as? Resources ?: return result
        val id = chain.getArg(0) as? Int ?: return result

        val targetId = resolveStatusBarHeightResId(res)
        if (targetId == 0) return result
        if (id != targetId) return result

        PatchLog.debug("$NAME: displayId=$currentDisplayId 非主屏，status_bar_height $result → 0")
        return 0
    }

    /** 解析并缓存 `android:dimen/status_bar_height` 的 id。 */
    private fun resolveStatusBarHeightResId(res: Resources): Int {
        val cached = statusBarHeightResId
        if (cached >= 0) return cached

        val id = runCatching {
            res.getIdentifier("status_bar_height", "dimen", "android")
        }.getOrDefault(0)

        if (id == 0) {
            PatchLog.debug("$NAME: 本进程资源中不存在 android:dimen/status_bar_height")
        } else {
            PatchLog.debug("$NAME: 解析到 status_bar_height 资源 id=0x${Integer.toHexString(id)}")
        }
        statusBarHeightResId = id
        return id
    }
}
