package com.toshi0907.oboetotte.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val isDone: Boolean = false,
    val dueAt: Long? = null,
    val listId: Long? = null,
    val parentTaskId: Long? = null,
    val repeatRule: String? = null,
    val repeatDaysOfWeek: String? = null,
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int? = null,
    val notifyOnArrival: Boolean = false,
    val notifyOnDeparture: Boolean = false,
    val url: String? = null,
    val memo: String? = null,
    val seriesId: Long? = null,
    val autoSnoozeMinutes: Long? = null,
    // trueの場合、期限日時通知(ReminderReceiver)を実際に表示できた時点で自動的にタスクを完了扱いにする
    // (通知されること自体が重要で、完了操作の確認は不要なタスク向け)。この場合、通知には「完了」/「スヌーズ」
    // ボタンを出さず、オートスヌーズ(autoSnoozeMinutes)も併用しない(TaskViewModel.updateTaskがnullへ正規化する)。
    val notifyOnlyMode: Boolean = false,
    // 通知時に使うバイブレーションパターン(VibrationPattern.id)。nullならパターンを使わず、
    // 通知チャンネル標準のバイブレーションのまま(詳細はdocs/architecture/vibration-patterns.md)。
    val vibrationPatternId: Long? = null
)

/**
 * 添付ファイルの紐付け先ID。繰り返しタスクが完了して次回分が生成されても添付ファイルが
 * 引き継がれ、かつ同じ繰り返しシリーズの全インスタンス(過去の完了済み分を含む)で
 * 追加・削除が連動するよう、[TaskAttachment.taskId]にはタスク自身の[Task.id]ではなく
 * この値を使う。[seriesId]が未設定(単発タスク、またはまだ一度も完了していない繰り返し
 * タスクの初回インスタンス)であれば自分自身の[Task.id]がそのままシリーズの識別子になる。
 */
fun Task.attachmentGroupId(): Long = seriesId ?: id
