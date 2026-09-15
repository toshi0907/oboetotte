package com.toshi0907.oboetotte.notification

import android.content.Context
import android.content.SharedPreferences

/**
 * 位置情報リマインダーの確認方式。[GEOFENCING_API]はGoogle Play servicesのGeofencing APIに
 * 内外判定を任せる従来方式(電池消費は少ないが、端末側の評価間隔を間引かれることがあり、
 * 遷移の検知が数時間単位で遅れる「遅延キャッチアップ」が起こりうる)。[CONTINUOUS_TRACKING]は
 * [LocationTrackingService]が一定間隔で自前に位置を取得し続け、検知の速さ・正確さを優先する
 * 代わりに電池消費が増える方式。
 */
enum class LocationTrackingMode {
    GEOFENCING_API,
    CONTINUOUS_TRACKING
}

/** [LocationTrackingMode]・連続追跡方式の更新間隔をSharedPreferencesで永続化する。 */
object LocationTrackingSettings {
    const val MIN_INTERVAL_MINUTES = 1
    const val MAX_INTERVAL_MINUTES = 60
    const val DEFAULT_INTERVAL_MINUTES = 15

    private const val PREFS_NAME = "location_tracking_settings"
    private const val KEY_MODE = "mode"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context): LocationTrackingMode {
        val name = prefs(context).getString(KEY_MODE, null)
        return LocationTrackingMode.entries.find { it.name == name } ?: LocationTrackingMode.GEOFENCING_API
    }

    fun setMode(context: Context, mode: LocationTrackingMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)

    /** [minutes]は[MIN_INTERVAL_MINUTES]〜[MAX_INTERVAL_MINUTES]の範囲に丸めてから保存する。 */
    fun setIntervalMinutes(context: Context, minutes: Int) {
        val clamped = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        prefs(context).edit().putInt(KEY_INTERVAL_MINUTES, clamped).apply()
    }
}
