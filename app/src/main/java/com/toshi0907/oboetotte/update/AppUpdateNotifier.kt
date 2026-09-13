package com.toshi0907.oboetotte.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.R

/**
 * バックグラウンドの定期チェック([AppUpdateCheckWorker])が新しいビルドを見つけた際に表示する
 * 通知チャンネル・通知の組み立てを担う(`notification/LocationReminderNotifier`と同様の構成)。
 * 同じコミット(=同じビルド)に対しては1度だけ通知し、定期チェックのたびに同じ内容で
 * 繰り返し通知しないよう、最後に通知したコミットSHAをSharedPreferencesに記録する。
 */
object AppUpdateNotifier {
    const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 1
    private const val PREFS_NAME = "app_update_notifier"
    private const val KEY_LAST_NOTIFIED_COMMIT_SHA = "last_notified_commit_sha"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** [commitSha]について既に通知済みなら`false`。呼び出し元([AppUpdateCheckWorker])はこの場合通知をスキップする。 */
    fun shouldNotify(context: Context, commitSha: String): Boolean =
        prefs(context).getString(KEY_LAST_NOTIFIED_COMMIT_SHA, null) != commitSha

    /** @return 実際に[NotificationManagerCompat.notify]を呼んだかどうか(権限が無ければfalse)。 */
    fun showNotification(context: Context, commitSha: String): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "アプリの更新",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "新しいバージョンが利用可能になったときの通知"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("新しいバージョンが利用可能です")
            .setContentText("タップしてアプリを開き、設定画面から更新してください")
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        prefs(context).edit().putString(KEY_LAST_NOTIFIED_COMMIT_SHA, commitSha).apply()
        return true
    }
}
