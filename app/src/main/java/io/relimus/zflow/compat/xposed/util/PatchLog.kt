package io.relimus.zflow.compat.xposed.util

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * 补丁日志。
 *
 * 分两级：
 * - 常规级（[info]/[warn]/[error]）：始终输出，用于报告"补丁是否装上、是否命中"这类结论。
 * - 详细级（[debug]/[trace]）：只有打开"详细日志"开关后才输出。
 *
 * 之所以要分开，是因为这些补丁依赖**反编译得到的混淆名**，应用一升级就可能全部失效。
 * 详细日志把"探测了哪些候选类 / 每个候选类的结果 / 为什么最终判定生效或跳过"完整写进
 * LSPosed 日志，用户升级应用后只要看日志就能判断是混淆名变了还是别的原因。
 */
object PatchLog {

    const val TAG = "ZFlowCompat"

    /** 由模块入口在初始化时注入的框架接口；为 null 时只写 logcat。 */
    @Volatile
    private var module: XposedModule? = null

    /** 详细日志开关（由模块设置经远程偏好同步过来）。 */
    @Volatile
    var verbose: Boolean = false

    /** 是否把日志同时写入框架（LSPosed）日志。 */
    @Volatile
    var logToFramework: Boolean = true

    /** 进程标识，避免多进程日志混在一起时分不清来源。 */
    private val processTag: String by lazy {
        val name = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.app.Application.getProcessName()
            } else {
                null
            }
        }.getOrNull()
        name ?: "pid-${android.os.Process.myPid()}"
    }

    fun attach(module: XposedModule) {
        this.module = module
    }

    /** 详细日志，仅 [verbose] 打开时输出。 */
    fun debug(msg: String) {
        if (!verbose) return
        write(Log.DEBUG, msg, null, force = false)
    }

    /** 结论级信息，始终输出。 */
    fun info(msg: String) {
        write(Log.INFO, msg, null, force = true)
    }

    fun warn(msg: String, tr: Throwable? = null) {
        write(Log.WARN, msg, tr, force = true)
    }

    fun error(msg: String, tr: Throwable? = null) {
        write(Log.ERROR, msg, tr, force = true)
    }

    /**
     * 详细日志的"步骤"形式：把一次探测的输入与结果成对打出来，便于对照。
     */
    fun step(name: String, detail: String) {
        if (!verbose) return
        write(Log.DEBUG, "$name: $detail", null, force = false)
    }

    private fun write(priority: Int, msg: String, tr: Throwable?, force: Boolean) {
        val line = "[$processTag] $msg"
        // logcat：详细日志也写，方便用 adb logcat 抓
        runCatching {
            if (tr == null) Log.println(priority, TAG, line)
            else Log.println(priority, TAG, "$line\n${Log.getStackTraceString(tr)}")
        }

        if (!logToFramework) return
        // 框架日志：详细级别只有在开关打开时才写，避免刷屏
        if (!force && !verbose) return
        runCatching {
            module?.log(priority, TAG, line, tr)
        }
    }
}
