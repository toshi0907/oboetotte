package com.toshi0907.oboetotte.update

import android.content.Context

/**
 * 直近の更新チェックが行われた時刻(epoch millis)をSharedPreferencesで永続化する。
 * アプリ起動時の自動チェック・設定画面の「更新を確認」ボタン・バックグラウンドの
 * 定期チェック([AppUpdateCheckWorker])のいずれで行われたチェックもここに記録し、
 * 設定画面の「アップデート」セクションに「最終確認」として表示する。
 */
object AppUpdateCheckSettings {
    private const val PREFS_NAME = "app_update_check_settings"
    private const val KEY_LAST_CHECKED_AT = "last_checked_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastCheckedAt(context: Context): Long? {
        val value = prefs(context).getLong(KEY_LAST_CHECKED_AT, -1L)
        return value.takeIf { it >= 0L }
    }

    fun recordCheckedAt(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_LAST_CHECKED_AT, millis).apply()
    }
}
