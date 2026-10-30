package com.toshi0907.oboetotte.notification

import android.content.Context
import android.content.SharedPreferences

/** 連続追跡方式([LocationTrackingService])の更新間隔をSharedPreferencesで永続化する。 */
object LocationTrackingSettings {
    const val MIN_INTERVAL_MINUTES = 1
    const val MAX_INTERVAL_MINUTES = 60
    const val DEFAULT_INTERVAL_MINUTES = 15

    private const val PREFS_NAME = "location_tracking_settings"

    // 廃止したGeofencing API/連続追跡方式の切り替え設定のキー。旧バージョンからの移行時に削除する。
    private const val KEY_LEGACY_MODE = "mode"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"
    private const val KEY_LEGACY_GEOFENCES_REMOVED = "legacy_geofences_removed"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)

    /** [minutes]は[MIN_INTERVAL_MINUTES]〜[MAX_INTERVAL_MINUTES]の範囲に丸めてから保存する。 */
    fun setIntervalMinutes(context: Context, minutes: Int) {
        val clamped = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        prefs(context).edit().putInt(KEY_INTERVAL_MINUTES, clamped).apply()
    }

    /** 旧バージョンでGeofencing APIへ登録したジオフェンスの一括解除が済んでいるか。 */
    fun isLegacyGeofencesRemoved(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEGACY_GEOFENCES_REMOVED, false)

    fun markLegacyGeofencesRemoved(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_LEGACY_GEOFENCES_REMOVED, true)
            .remove(KEY_LEGACY_MODE)
            .apply()
    }
}
