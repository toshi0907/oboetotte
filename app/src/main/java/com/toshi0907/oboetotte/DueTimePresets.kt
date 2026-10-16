package com.toshi0907.oboetotte

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 編集画面の期限クイック選択「当日朝/昼/夕/夜」「明日の朝/昼/夕/夜」の時間帯。[defaultMinuteOfDay]は0時からの分数。
 */
enum class DueTimeSlot(val label: String, val prefsKey: String, val defaultMinuteOfDay: Int) {
    MORNING("朝", "morning", 7 * 60),
    NOON("昼", "noon", 12 * 60),
    EVENING("夕", "evening", 17 * 60),
    NIGHT("夜", "night", 20 * 60)
}

/**
 * [DueTimeSlot]ごとの時刻(0時からの分数)をSharedPreferencesで永続化する。
 * 既定は朝7:00/昼12:00/夕17:00/夜20:00。時間帯同士の前後関係はチェックしない。
 */
object DueTimePresetSettings {
    private const val PREFS_NAME = "due_time_preset_settings"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMinuteOfDay(context: Context, slot: DueTimeSlot): Int =
        prefs(context).getInt(slot.prefsKey, slot.defaultMinuteOfDay).coerceIn(0, 24 * 60 - 1)

    fun getAll(context: Context): Map<DueTimeSlot, Int> =
        DueTimeSlot.entries.associateWith { getMinuteOfDay(context, it) }

    fun setTime(context: Context, slot: DueTimeSlot, hour: Int, minute: Int) {
        val minuteOfDay = hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
        prefs(context).edit().putInt(slot.prefsKey, minuteOfDay).apply()
    }
}

/** 0時からの分数を「07:00」形式の文字列にする。 */
fun formatMinuteOfDay(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/**
 * [now](epoch millis)と同じ日(端末のローカルタイムゾーン)の[minuteOfDay]時点のepoch millisを返す。
 * DST切り替え日に存在しない時刻は`ZonedDateTime`の規則どおり後ろにずらされる。
 */
fun todayAtMinuteOfDay(now: Long, minuteOfDay: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    dayAtMinuteOfDay(now, minuteOfDay, 0, zone)

/** [now](epoch millis)の翌日(端末のローカルタイムゾーン)の[minuteOfDay]時点のepoch millisを返す。 */
fun tomorrowAtMinuteOfDay(now: Long, minuteOfDay: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    dayAtMinuteOfDay(now, minuteOfDay, 1, zone)

private fun dayAtMinuteOfDay(now: Long, minuteOfDay: Int, plusDays: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(plusDays)
        .atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
