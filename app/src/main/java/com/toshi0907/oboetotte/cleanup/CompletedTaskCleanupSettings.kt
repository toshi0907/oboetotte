package com.toshi0907.oboetotte.cleanup

import android.content.Context
import android.content.SharedPreferences

/**
 * 完了済みタスクの自動削除([CompletedTaskCleanup])の設定(有効/無効・完了から削除までの日数)を
 * SharedPreferencesで永続化する。既定は有効・30日。
 */
object CompletedTaskCleanupSettings {
    const val MIN_RETENTION_DAYS = 1
    const val MAX_RETENTION_DAYS = 3650
    const val DEFAULT_RETENTION_DAYS = 30

    private const val PREFS_NAME = "completed_task_cleanup_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RETENTION_DAYS = "retention_days"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getRetentionDays(context: Context): Int =
        prefs(context).getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)

    /** [days]は[MIN_RETENTION_DAYS]〜[MAX_RETENTION_DAYS]の範囲に丸めてから保存する。 */
    fun setRetentionDays(context: Context, days: Int) {
        val clamped = days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
        prefs(context).edit().putInt(KEY_RETENTION_DAYS, clamped).apply()
    }
}
