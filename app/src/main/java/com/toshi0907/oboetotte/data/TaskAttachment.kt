package com.toshi0907.oboetotte.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * タスクに添付したファイルのメタデータ。実体のバイト列は[storedFileName]をファイル名として
 * [com.toshi0907.oboetotte.attachment.AttachmentStorage]経由で端末内(filesDir/attachments/)に
 * 保存する。[fileName]はユーザーに見せる元のファイル名、[storedFileName]は衝突しないよう
 * UUIDベースで生成した保存用のファイル名。
 */
@Entity(tableName = "task_attachments")
data class TaskAttachment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val fileName: String,
    val storedFileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis()
)
