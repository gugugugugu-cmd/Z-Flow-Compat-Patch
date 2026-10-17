package io.relimus.zflow.compat.ui

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import io.relimus.zflow.compat.BuildConfig
import io.relimus.zflow.compat.R
import io.relimus.zflow.compat.service.RemotePrefsBridge

/**
 * 设置页。
 *
 * 写入分两路：
 * - 本地 SharedPreferences：给本模块应用自己看（例如"关于"页展示状态）。
 * - 框架远程偏好：让被 hook 的目标进程读到开关（这是真正生效的那一路）。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings, rootKey)

            PreferenceManager.getDefaultSharedPreferences(requireContext())

            // 同步当前值：本地没有时以远程偏好为准
            syncFromRemote()

            findPreference<Preference>("about")?.apply {
                summary = getString(R.string.pref_about_summary) +
                    "\n\n包名: ${BuildConfig.APPLICATION_ID}\n版本: ${BuildConfig.VERSION_NAME}"
            }
        }

        /**
         * 启动时从框架远程偏好拉一次，避免用户重装或清数据后开关显示成默认值。
         */
        private fun syncFromRemote() {
            val remote = RemotePrefsBridge.get(requireContext()) ?: return
            val local = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val editor = local.edit()

            for (key in PATCH_KEYS) {
                val value = remote.getBoolean(key, true)
                findPreference<androidx.preference.SwitchPreferenceCompat>(key)?.isChecked = value
                editor.putBoolean(key, value)
            }
            for (key in LOG_KEYS) {
                val def = key == "log_to_framework"
                val value = remote.getBoolean(key, def)
                findPreference<androidx.preference.SwitchPreferenceCompat>(key)?.isChecked = value
                editor.putBoolean(key, value)
            }
            editor.apply()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (key == null) return
            if (key !in PATCH_KEYS && key !in LOG_KEYS) return

            val default = if (key in LOG_KEYS) key == "log_to_framework" else true
            val value = prefs?.getBoolean(key, default) ?: return
            val ok = RemotePrefsBridge.putBoolean(requireContext(), key, value)
            // 写到框架才算生效；失败时提示用户，避免"看着打开了其实没生效"
            if (!ok) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_title)
                    .setMessage("未能写入框架设置：请确认 LSPosed 已激活本模块，且框架版本支持远程偏好。当前修改仅保存在本地。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }

        private companion object {
            val PATCH_KEYS = listOf(
                "wechat_statusbar_fix",
                "statusbar_dimen_fix",
                "telegram_pip_disable",
            )
            val LOG_KEYS = listOf(
                "verbose_log",
                "log_to_framework",
            )
        }
    }
}
