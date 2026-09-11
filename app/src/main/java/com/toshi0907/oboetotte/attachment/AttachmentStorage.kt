package com.toshi0907.oboetotte.attachment

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.toshi0907.oboetotte.data.TaskAttachment
import java.io.File
import java.util.UUID

/**
 * タスクの添付ファイルの実体(バイト列)を端末内のアプリ専用ストレージ(filesDir/attachments/)に
 * 保存・削除・参照する。Room側([TaskAttachment])はこのディレクトリ直下のファイル名
 * ([TaskAttachment.storedFileName])のみを保持し、選択時に渡されたUriそのものには依存しない
 * (元ファイルが後から削除・移動されても添付は残る)。
 */
object AttachmentStorage {
    private const val DIR_NAME = "attachments"
    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    fun directory(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * [storedFileName]はバックアップのインポート時など端末外で作られたZIP/JSONに由来する値を
     * 扱うことがあるため、パストラバーサル(`../`等でattachmentsディレクトリの外を指す値)を
     * 拒否する。単一のファイル名(パス区切り無し)であることと、正規化後のパスが必ず
     * attachmentsディレクトリの直下に収まることの両方を検証する。
     */
    fun file(context: Context, storedFileName: String): File {
        require(
            storedFileName.isNotBlank() &&
                storedFileName != "." &&
                storedFileName != ".." &&
                '/' !in storedFileName &&
                '\\' !in storedFileName
        ) { "不正な添付ファイル名です: $storedFileName" }
        val dir = directory(context).canonicalFile
        val candidate = File(dir, storedFileName).canonicalFile
        require(candidate.parentFile == dir) { "不正な添付ファイル名です: $storedFileName" }
        return candidate
    }

    /**
     * [uri]の内容を端末内にコピーする。呼び出し元([TaskViewModel])が`Dispatchers.IO`上で
     * 呼び出すことを想定したブロッキングI/O。
     */
    fun copyToStorage(context: Context, uri: Uri): CopiedFile? {
        val resolver = context.contentResolver
        val originalName = queryFileName(context, uri) ?: uri.lastPathSegment ?: "file"
        val extension = originalName.substringAfterLast('.', "")
        val storedFileName = if (extension.isNotEmpty()) {
            "${UUID.randomUUID()}.$extension"
        } else {
            UUID.randomUUID().toString()
        }
        val destination = file(context, storedFileName)
        val input = resolver.openInputStream(uri) ?: return null
        input.use { inStream ->
            destination.outputStream().use { outStream -> inStream.copyTo(outStream) }
        }
        return CopiedFile(
            fileName = originalName,
            storedFileName = storedFileName,
            mimeType = resolver.getType(uri),
            sizeBytes = destination.length()
        )
    }

    fun delete(context: Context, storedFileName: String) {
        file(context, storedFileName).delete()
    }

    /** [FileProvider]経由でファイルを外部アプリに公開し、ACTION_VIEWで開くIntentを組み立てる。 */
    fun openIntent(context: Context, attachment: TaskAttachment): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
            file(context, attachment.storedFileName)
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") return null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index)
                }
            }
        return null
    }

    data class CopiedFile(
        val fileName: String,
        val storedFileName: String,
        val mimeType: String?,
        val sizeBytes: Long
    )
}
