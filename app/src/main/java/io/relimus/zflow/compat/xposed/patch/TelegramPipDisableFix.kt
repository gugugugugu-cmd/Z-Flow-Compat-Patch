package io.relimus.zflow.compat.xposed.patch

import android.app.Activity
import android.app.PictureInPictureParams
import io.github.libxposed.api.XposedModule
import io.relimus.zflow.compat.xposed.util.PatchLog
import io.relimus.zflow.compat.xposed.util.PatchSettings
import io.relimus.zflow.compat.xposed.util.Reflect
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 关闭 Telegram 系应用的画中画（PiP）。
 *
 * ## 问题
 *
 * Telegram 播放视频时，上滑离开会进系统 PiP；此时再用任务卡上滑到右上角开小窗，
 * 这个**已处于 PiP 的任务**被整个搬到虚拟屏，PiP 状态一起带过去。
 * 虚拟屏上没有系统 UI，PiP 窗口退化成「角落里一个很小的画面 + 其余空白」。
 *
 * ## 为什么是「整个关掉」而不是「只在小窗里关」
 *
 * PiP 是在**主屏**上、用户上滑去最近任务的那一刻就进了的；等应用落到虚拟屏时
 * 已经处于 PiP。应用侧既无法预知「马上要被开小窗」，也没有退出的 API
 * （公开 SDK 里没有 `exitPictureInPictureMode`）。
 *
 * 所以只能退而求其次：**让这些应用根本不进 PiP**。
 * 代价是主屏上也不再有小窗播放——用设置里的开关可以随时恢复。
 *
 * ## 为什么 hook 参数而不是 hook 进入调用
 *
 * Android 12+ 上 Telegram 走的是**系统自动进入**：
 *
 * ```
 * PipUtils.useAutoEnterInPictureInPictureMode()  →  return SDK_INT >= 31   // 恒 true
 * manualEnterPictureInPictureModeInternal()
 *     if (useAutoEnterInPictureInPictureMode()) return;      // ← 直接返回
 *     Activity.enterPictureInPictureMode(params)             // ← 在 API 31+ 永不执行
 * ```
 *
 * 也就是说在目标设备上 Telegram **压根不调用** `enterPictureInPictureMode`，
 * 拦它没有意义。唯一有效的开关是 `PictureInPictureParams` 里的
 * `autoEnterEnabled`——Telegram 自己在 `resetPictureInPictureParams` 里也是这么关的。
 *
 * 因此做法是：把 `setPictureInPictureParams` 收到的参数换成
 * `autoEnterEnabled = false` 的空参数。
 *
 * 另外仍然拦一刀 `enterPictureInPictureMode`，用于覆盖 API < 31 上的手动路径，属于兜底。
 *
 * ## 只对 Telegram 系生效
 *
 * 不按包名判断（fork 的包名千奇百怪），而是检测进程里是否存在 Telegram 的
 * PiP 类 `org.telegram.messenger.pip.PipActivityHandler`。
 */
object TelegramPipDisableFix {

    private const val NAME = "TelegramPipDisable"

    /** 用 Telegram 自己的类名做识别，覆盖官方版与各种 fork。 */
    private const val TELEGRAM_PIP_CLASS = "org.telegram.messenger.pip.PipActivityHandler"

    private val installed = AtomicBoolean(false)

    /** 空参数 + 关闭自动进入；构造一次复用。 */
    private val disabledParams: PictureInPictureParams by lazy {
        val builder = PictureInPictureParams.Builder()
        // setAutoEnterEnabled 是 API 31 新增的；低版本上不做这一步
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(false)
        }
        builder.build()
    }

    fun install(module: XposedModule, classLoader: ClassLoader, pkg: String) {
        if (installed.get()) return
        if (!PatchSettings.isEnabled(null, PatchSettings.KEY_TELEGRAM_PIP_DISABLE)) {
            PatchLog.info("$NAME: 开关已关闭，跳过安装")
            return
        }

        // 识别：进程里是否存在 Telegram 的 PiP 类
        val marker = Reflect.findClass(TELEGRAM_PIP_CLASS, classLoader)
        if (marker == null) {
            PatchLog.debug("$NAME: pkg=$pkg 非 Telegram 系（未找到 $TELEGRAM_PIP_CLASS），跳过")
            return
        }
        PatchLog.info("$NAME: pkg=$pkg 确认为 Telegram 系，开始安装")

        val activityClass = Reflect.findClass("android.app.Activity", classLoader) ?: run {
            PatchLog.warn("$NAME: android.app.Activity 未找到")
            return
        }

        var ok = false

        // 主手段：把 PiP 参数换成「不允许自动进入」
        val setParams = Reflect.findMethodExact(
            activityClass, "setPictureInPictureParams", PictureInPictureParams::class.java
        )
        if (setParams != null) {
            ok = runCatching {
                module.hook(setParams).intercept { chain ->
                    val activity = chain.thisObject as? Activity
                    if (activity == null) return@intercept chain.proceed()
                    if (!enabled()) {
                        PatchLog.debug("$NAME: 开关已关闭，放行")
                        return@intercept chain.proceed()
                    }
                    PatchLog.debug(
                        "$NAME: setPictureInPictureParams → autoEnterEnabled=false " +
                            "(activity=${activity.javaClass.name})"
                    )
                    // 用关闭自动进入的参数替换原参数，其余链路照走
                    chain.proceed(arrayOf<Any?>(disabledParams))
                }
                PatchLog.debug("$NAME: 已挂钩 setPictureInPictureParams")
                true
            }.onFailure {
                PatchLog.warn("$NAME: 挂钩 setPictureInPictureParams 失败", it)
            }.getOrDefault(false)
        } else {
            PatchLog.debug("$NAME: setPictureInPictureParams 未找到")
        }

        // 兜底：API < 31 上的手动进入路径（目标设备不会走到）
        val enterMethods = Reflect.findMethods(activityClass, "enterPictureInPictureMode")
        PatchLog.debug("$NAME: enterPictureInPictureMode 重载数=${enterMethods.size}")
        enterMethods.forEach { m ->
            val hooked = runCatching {
                module.hook(m).intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    PatchLog.debug(
                        "$NAME: enterPictureInPictureMode(${m.parameterTypes.joinToString { it.simpleName }}) " +
                            "→ 拒绝进入"
                    )
                    // 返回 false 表示"未进入 PiP"
                    false
                }
                true
            }.onFailure {
                PatchLog.warn("$NAME: 挂钩 enterPictureInPictureMode 失败", it)
            }.getOrDefault(false)
            ok = ok || hooked
        }

        if (ok) {
            installed.set(true)
            PatchLog.info("$NAME: 安装完成 —— 本进程不再进入画中画")
        } else {
            PatchLog.warn("$NAME: 未装上任何 hook 点，补丁未生效")
        }
    }

    private fun enabled(): Boolean =
        PatchSettings.isEnabled(null, PatchSettings.KEY_TELEGRAM_PIP_DISABLE)
}
