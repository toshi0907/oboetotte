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
    val aiPrompt: String? = null,
    val aiCachedResponse: String? = null,
    val autoSnoozeMinutes: Long? = null,
    val aiUseWebSearch: Boolean = false,
    // マップの組み込みツール(google_maps)は使用不可のため、UIからtrueにする経路は無い。
    // カラム自体は既存端末のデータ・バックアップとの互換性のためだけに残しており、
    // 過去にtrueだったタスクもTaskViewModel.updateTaskで編集・保存すればfalseに正規化される。
    val aiUseMaps: Boolean = false,
    val aiUseUrlContext: Boolean = false,
    // GeminiClient.Sourceのtitle/uri/originを持つJSON配列文字列(例: `[{"title":"...","uri":"...","origin":"WEB_SEARCH"}]`)。
    // グラウンディングを使わなかった/出典が無かった場合はnull。
    val aiCachedSources: String? = null
)

/**
 * 添付ファイルの紐付け先ID。繰り返しタスクが完了して次回分が生成されても添付ファイルが
 * 引き継がれ、かつ同じ繰り返しシリーズの全インスタンス(過去の完了済み分を含む)で
 * 追加・削除が連動するよう、[TaskAttachment.taskId]にはタスク自身の[Task.id]ではなく
 * この値を使う。[seriesId]が未設定(単発タスク、またはまだ一度も完了していない繰り返し
 * タスクの初回インスタンス)であれば自分自身の[Task.id]がそのままシリーズの識別子になる。
 */
fun Task.attachmentGroupId(): Long = seriesId ?: id
