package io.relimus.zflow.compat.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块应用侧通往框架「远程偏好」的桥。
 *
 * 目标进程里的补丁用 [io.relimus.zflow.compat.xposed.util.PatchSettings] 读这份偏好；
 * 本模块应用负责写。框架负责同步。
 *
 * 连接过程：模块进程启动 → 框架发现模块 → 通过 [io.github.libxposed.service.XposedProvider]
 * 把服务 Binder 递过来 → [XposedServiceHelper] 回调 → 这里拿到 [XposedService] →
 * 取该 group 的远程偏好。
 *
 * 连接是异步的，设置页可能先于服务就绪打开，因此 [get] 允许返回 null，
 * 写入时若尚未连接则返回 false，由 UI 提示用户。
 */
object RemotePrefsBridge {

    private const val TAG = "RemotePrefsBridge"

    /** 与目标进程约定的偏好组名，必须和 PatchSettings.GROUP 一致。 */
    const val GROUP = "compat_settings"

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var listenerRegistered = false

    @Volatile
    private var cached: SharedPreferences? = null

    /** 注册监听（幂等）。拿到服务后立刻取一次远程偏好并缓存。 */
    private fun ensureRegistered() {
        if (listenerRegistered) return
        synchronized(this) {
            if (listenerRegistered) return
            listenerRegistered = true
            runCatching {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        Log.i(TAG, "框架服务已连接: ${service.frameworkName} ${service.frameworkVersion}")
                        this@RemotePrefsBridge.service = service
                        cached = runCatching {
                            service.getRemotePreferences(GROUP)
                        }.onFailure {
                            Log.e(TAG, "获取远程偏好失败", it)
                        }.getOrNull()
                    }

                    override fun onServiceDied(service: XposedService) {
                        Log.w(TAG, "框架服务已断开")
                        if (this@RemotePrefsBridge.service === service) {
                            this@RemotePrefsBridge.service = null
                            cached = null
                        }
                    }
                })
            }.onFailure {
                Log.e(TAG, "注册框架服务监听失败", it)
            }
        }
    }

    /** 取远程偏好；框架未连接时返回 null。 */
    fun get(context: Context): SharedPreferences? {
        ensureRegistered()
        return cached
    }

    /**
     * 写入一个布尔开关并同步到框架。
     * @return true 表示已成功提交到框架；false 表示框架不可用。
     */
    fun putBoolean(context: Context, key: String, value: Boolean): Boolean {
        ensureRegistered()
        val prefs = cached ?: return false
        return runCatching {
            prefs.edit().putBoolean(key, value).commit()
        }.onFailure {
            Log.e(TAG, "写入远程偏好失败: $key=$value", it)
        }.getOrDefault(false)
    }

    /** 框架服务当前是否可用。 */
    fun isConnected(): Boolean = cached != null
}
