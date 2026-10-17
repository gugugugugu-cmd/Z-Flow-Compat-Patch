package io.relimus.zflow.compat.ui

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import io.relimus.zflow.compat.BuildConfig
import io.relimus.zflow.compat.R
import io.relimus.zflow.compat.service.RemotePrefsBridge

/**
 * 设置页。
 *
 * 写入分两路：
 * - 本地 SharedPreferences：给本模块应用自己看（开关界面状态）。
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

    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings, rootKey)

            // 同步当前值：本地没有时以框架远程偏好为准
            syncFromRemote()

            findPreference<Preference>("about")?.summary =
                getString(R.string.pref_about_summary) +
                    "\n\n包名: " + BuildConfig.APPLICATION_ID +
                    "\n版本: " + BuildConfig.VERSION_NAME
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences
                ?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences
                ?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        /** 启动时从框架远程偏好拉一次，避免重装/清数据后开关显示成默认值。 */
        private fun syncFromRemote() {
            val remote = RemotePrefsBridge.get(requireContext()) ?: return
            val editor = PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()

            for (key in PATCH_KEYS) {
                val value = remote.getBoolean(key, true)
                findPreference<SwitchPreferenceCompat>(key)?.isChecked = value
                editor.putBoolean(key, value)
            }
            for (key in LOG_KEYS) {
                val value = remote.getBoolean(key, defaultOf(key))
                findPreference<SwitchPreferenceCompat>(key)?.isChecked = value
                editor.putBoolean(key, value)
            }
            editor.apply()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences,
            key: String?,
        ) {
            if (key == null) return
            if (key !in PATCH_KEYS && key !in LOG_KEYS) return

            val value = sharedPreferences.getBoolean(key, defaultOf(key))
            val ok = RemotePrefsBridge.putBoolean(requireContext(), key, value)
            // 写到框架才算生效；失败时明确提示，避免"看着打开了其实没生效"
            if (!ok) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_title)
                    .setMessage(getString(R.string.settings_write_failed))
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

            /** 默认值：补丁默认开，详细日志默认关，写框架日志默认开。 */
            fun defaultOf(key: String): Boolean = when (key) {
                "verbose_log" -> false
                "log_to_framework" -> true
                else -> true
            }
        }
    }
}
