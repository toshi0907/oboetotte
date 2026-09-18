package com.toshi0907.oboetotte.update

import android.content.Context
import android.content.SharedPreferences

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

    /**
     * [recordCheckedAt]による変更をリッスンする。バックグラウンドの定期チェック
     * ([AppUpdateCheckWorker])による更新は、アプリのプロセスが生きている間、呼び出し元
     * (`TaskViewModel`)の同期処理を経由しないため、SharedPreferences側の変更を直接購読して
     * StateFlowに反映する用途で使う(`backup/CloudBackupSettings.addLastResultChangeListener`と
     * 同じ考え方)。SharedPreferencesは内部でリスナーをWeakReferenceとしてしか保持しないため、
     * 呼び出し元は[listener]自体を(ラムダをその場で渡すのではなく)フィールド等で保持し続けること。
     */
    fun addLastCheckedAtChangeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeLastCheckedAtChangeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
