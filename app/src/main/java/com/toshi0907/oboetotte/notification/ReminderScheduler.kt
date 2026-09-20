package com.toshi0907.oboetotte.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.toshi0907.oboetotte.data.Task

object ReminderScheduler {
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_IS_TEST = "is_test"
    const val EXTRA_IS_SNOOZE = "is_snooze"
    const val EXTRA_IS_AUTO_SNOOZE = "is_auto_snooze"
    const val EXTRA_DUE_AT = "due_at"
    private const val TEST_REQUEST_CODE = -1
    const val TEST_DELAY_SECONDS = 5L

    /**
     * 通知の識別に使うタグ。期限日時通知(ReminderReceiver)と位置情報通知(GeofenceReceiver)は
     * 通知ID自体はどちらも`taskId.toInt()`で揃えているが(「完了」ボタンでの消去用途)、タグを
     * 分けることで、同じタスクに両方のリマインダーを設定していて両方が発火した場合でも、
     * 片方がもう片方を上書きせず別々の通知として両方とも表示される。
     */
    const val NOTIFICATION_TAG_DUE = "due"
    const val NOTIFICATION_TAG_LOCATION = "location"

    data class SnoozeOption(val label: String, val minutes: Long)

    val SNOOZE_OPTIONS = listOf(
        SnoozeOption("15分後", 15),
        SnoozeOption("30分後", 30),
        SnoozeOption("1時間後", 60),
        SnoozeOption("3時間後", 180),
        SnoozeOption("1日後", 24 * 60)
    )

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * [task]の状態に応じてアラームを立て直す/キャンセルする。`updateTask`・`toggleDone`・
     * `addTask`・`addSubtask`・[com.toshi0907.oboetotte.notification.BootReceiver]など、
     * タスクの状態が変わりうる箇所から都度呼ばれ、常にDBの状態とアラームの登録状態を同期させる。
     *
     * `dueAt`が過去(期限到達後)の場合、通常は通知済みとみなしキャンセルするだけでよいが、
     * [Task.autoSnoozeMinutes]が設定されたタスクは「未完了のまま指定間隔で再通知を繰り返す」
     * ループの途中である可能性がある(`dueAt`自体はオートスヌーズでは書き換えないため、
     * 期限到達後は常にこの条件に該当する)。ここで無条件にキャンセルしてしまうと、端末再起動時の
     * [BootReceiver]の再スケジュールや、期限日時を変えないまま他の項目だけを編集した場合の
     * `updateTask`経由の呼び出しのたびに、進行中のオートスヌーズが理由なく止まってしまう
     * (アラーム本体・手動スヌーズ・オートスヌーズはいずれも同じ[pendingIntentFor]のPendingIntent
     * を共有しているため)。そのため、期限到達後かつオートスヌーズが設定済みの未完了タスクは
     * キャンセルせず、「今から指定間隔後」を基準にオートスヌーズを立て直す。
     */
    fun schedule(context: Context, task: Task) {
        val dueAt = task.dueAt
        if (dueAt == null || task.isDone) {
            cancel(context, task.id)
            return
        }
        if (dueAt <= System.currentTimeMillis()) {
            val autoSnoozeMinutes = task.autoSnoozeMinutes
            if (autoSnoozeMinutes != null && autoSnoozeMinutes > 0) {
                scheduleAutoSnooze(context, task.id, autoSnoozeMinutes)
            } else {
                cancel(context, task.id)
            }
            return
        }
        if (!canScheduleExactAlarms(context)) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            dueAt,
            pendingIntentFor(context, task.id)
        )
    }

    fun cancel(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntentFor(context, taskId))
    }

    /**
     * スヌーズ用。タスクの`dueAt`は書き換えず、通知だけを「タップ時刻+[minutes]分後」に
     * 再スケジュールする。アラーム本体と同じ[pendingIntentFor]を使うため、次に
     * [schedule]/[cancel]が呼ばれれば通常どおり上書き・キャンセルされる。
     */
    fun scheduleSnooze(context: Context, taskId: Long, minutes: Long): Boolean {
        if (!canScheduleExactAlarms(context)) return false

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + minutes * 60_000,
            pendingIntentFor(context, taskId, isSnooze = true)
        )
        return true
    }

    /**
     * オートスヌーズ用。[com.toshi0907.oboetotte.data.Task.autoSnoozeMinutes]が設定された
     * タスクの通知([ReminderReceiver])が発火した直後に、未完了のままであれば
     * [minutes]分後に自動で再通知するためのアラームを登録する。アラーム本体・手動スヌーズ
     * ([scheduleSnooze])と同じ[pendingIntentFor]のPendingIntent(taskIdをrequestCodeとする
     * 枠)を再利用するため、dueAtの変更・タスク完了による[schedule]/[cancel]や、手動スヌーズが
     * 呼ばれれば通常どおり上書き・キャンセルされる。これにより、手動でスヌーズを選んだ場合は
     * その時刻を基準に次回のオートスヌーズが計算し直される(手動スヌーズが優先される)。
     */
    fun scheduleAutoSnooze(context: Context, taskId: Long, minutes: Long): Boolean {
        if (!canScheduleExactAlarms(context)) return false

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + minutes * 60_000,
            pendingIntentFor(context, taskId, isAutoSnooze = true)
        )
        return true
    }

    /** [TEST_DELAY_SECONDS]秒後にテスト通知を発火させる。本番と同じAlarmManager経由の経路を検証する。 */
    fun scheduleTestNotification(context: Context): Boolean {
        if (!canScheduleExactAlarms(context)) return false

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_IS_TEST, true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            TEST_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + TEST_DELAY_SECONDS * 1000,
            pendingIntent
        )
        return true
    }

    /**
     * [isSnooze]は[EXTRA_IS_SNOOZE]としてIntentに載せ、[ReminderReceiver]が通知履歴の
     * トリガ条件を「期限到達」「スヌーズ」のどちらとして記録するかの判定に使う。
     * [FLAG_UPDATE_CURRENT]により、既存の[PendingIntent](同じtaskId=同じrequestCode)の
     * extrasもこの値で上書きされる。
     */
    private fun pendingIntentFor(
        context: Context,
        taskId: Long,
        isSnooze: Boolean = false,
        isAutoSnooze: Boolean = false
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_IS_SNOOZE, isSnooze)
            putExtra(EXTRA_IS_AUTO_SNOOZE, isAutoSnooze)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知の「スヌーズ」ボタン用。Androidの通知は表示できるアクションボタンが最大3個程度に
     * 制限されるため、[SNOOZE_OPTIONS]の件数分ボタンを並べる方式はやめ、1つの「スヌーズ」
     * ボタンから[SnoozePickerActivity](透明な背景でダイアログのみ表示)を起動し、そこで
     * 分数を選ばせる。リクエストコードは[openUrlPendingIntent]の`+9`と衝突しないよう`+5`を使う。
     */
    fun snoozePickerPendingIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, SnoozePickerActivity::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            taskId.toInt() * 10 + SNOOZE_PICKER_REQUEST_CODE_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知の「完了」ボタン用。[CompleteReceiver]宛で、通知アクションを起動する他のコンポーネントとは異なるため衝突しない。
     * [dueAt]を渡すと(期限日時通知[ReminderReceiver]からの呼び出し)、通知を表示した時点の期限を
     * [EXTRA_DUE_AT]としてIntentに載せる。[CompleteReceiver]はこれを完了処理直前のタスクの現在の
     * `dueAt`と突き合わせ、繰り返しタスクの完了等で既に次回分の期限に進んでいた場合に、古い通知の
     * 「完了」を誤って新しい期限のタスクへ適用してしまわないようにする。位置情報通知
     * ([LocationReminderNotifier])には期限の概念が無いため`null`のまま呼び出し、従来通り
     * タスクが存在すれば完了扱いとする。
     */
    fun completePendingIntent(context: Context, taskId: Long, dueAt: Long? = null): PendingIntent {
        val intent = Intent(context, CompleteReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            if (dueAt != null) {
                putExtra(EXTRA_DUE_AT, dueAt)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知の「リンクを開く」ボタン用。タスクの[url]をブラウザ等で開くACTION_VIEWの[PendingIntent]。
     * リクエストコードは[snoozePickerPendingIntent]の`+5`と衝突しないよう`taskId.toInt() * 10 + 9`を使う。
     */
    fun openUrlPendingIntent(context: Context, taskId: Long, url: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            taskId.toInt() * 10 + OPEN_URL_REQUEST_CODE_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val SNOOZE_PICKER_REQUEST_CODE_OFFSET = 5
    private const val OPEN_URL_REQUEST_CODE_OFFSET = 9
}
