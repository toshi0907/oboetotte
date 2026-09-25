package com.toshi0907.oboetotte.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.toshi0907.oboetotte.data.VibrationPattern

/**
 * タスクごとのバイブレーションパターン([VibrationPattern])を再生する。
 *
 * Android 8.0以降、通知のバイブレーションは通知チャンネル単位で作成時に固定され、通知ごとに
 * パターンを変えられない。そのためパターンを使うタスクの通知は、バイブレーションを無効にした
 * 専用チャンネル([customVibrationChannelId])に投稿した上で、[play]で[Vibrator]を直接鳴らす
 * (標準のバイブレーションとパターンが重ならないようにするため)。
 *
 * 着信モード(サイレント・マナー)に関わらず常に振動させる仕様のため、用途はアラーム扱い
 * (`USAGE_ALARM`)で再生する。
 */
object VibrationPatternPlayer {
    /** 設定画面の入力値の下限・上限(ms)。 */
    const val MIN_ON_MS = 10L
    const val MIN_OFF_MS = 10L
    const val MAX_DURATION_MS = 60_000L

    /**
     * 入力値として妥当かどうか。オフ時間は0(=継続時間の間ずっと振動)か[MIN_OFF_MS]以上とする
     * (極端に短い値だとタイミング配列の要素数が膨大になるため)。
     */
    fun isValid(onMs: Long, offMs: Long, durationMs: Long): Boolean =
        onMs >= MIN_ON_MS &&
            (offMs == 0L || offMs >= MIN_OFF_MS) &&
            durationMs in 1..MAX_DURATION_MS

    /**
     * [VibrationEffect.createWaveform]に渡すタイミング配列(先頭は待ち時間0、以降オン/オフを交互)を
     * 組み立てる。合計が[VibrationPattern.durationMs]を超える周期は、その時点で打ち切る。
     */
    fun buildTimings(onMs: Long, offMs: Long, durationMs: Long): LongArray {
        val timings = mutableListOf(0L)
        if (onMs <= 0 || durationMs <= 0) return timings.toLongArray()
        if (offMs <= 0) {
            timings.add(durationMs)
            return timings.toLongArray()
        }
        var elapsed = 0L
        var vibrating = true
        while (elapsed < durationMs) {
            val segment = minOf(if (vibrating) onMs else offMs, durationMs - elapsed)
            timings.add(segment)
            elapsed += segment
            vibrating = !vibrating
        }
        // 最後がオフ区間で終わる場合、そのオフ区間は振動に影響しないため取り除く。
        if (timings.size % 2 == 1 && timings.size > 1) timings.removeAt(timings.lastIndex)
        return timings.toLongArray()
    }

    fun play(context: Context, pattern: VibrationPattern) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val timings = buildTimings(pattern.onMs, pattern.offMs, pattern.durationMs)
        if (timings.size < 2) return
        val effect = VibrationEffect.createWaveform(timings, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** 元のチャンネルIDに対応する「バイブレーション無効」版のチャンネルID。 */
    fun customVibrationChannelId(baseChannelId: String): String = "${baseChannelId}_custom_vibration"

    /**
     * [baseChannelId]に対応するバイブレーション無効の専用チャンネルを作成し、そのIDを返す。
     * 重要度は元のチャンネルと同じ`IMPORTANCE_HIGH`(ヘッドアップ表示)にそろえる。
     */
    fun ensureCustomVibrationChannel(
        notificationManager: NotificationManager,
        baseChannelId: String,
        baseName: String
    ): String {
        val channelId = customVibrationChannelId(baseChannelId)
        val channel = NotificationChannel(
            channelId,
            "$baseName(バイブレーションパターン使用時)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "タスクにバイブレーションパターンを設定した場合の通知。振動はアプリがパターンに沿って別途鳴らします"
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
        return channelId
    }
}
