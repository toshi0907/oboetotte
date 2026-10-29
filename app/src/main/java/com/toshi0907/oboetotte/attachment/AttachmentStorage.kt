package com.toshi0907.oboetotte.attachment

import android.content.ContentResolver
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
    private const val MAX_EXTENSION_LENGTH = 10

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
     * [uri]の内容を端末内にコピーする。呼び出し元([TaskViewModel]・共有受信)が`Dispatchers.IO`上で
     * 呼び出すことを想定したブロッキングI/O。
     *
     * [uri]は他アプリから共有Intent経由で渡されることがあり、`openInputStream`はアプリ自身の権限で
     * 実行されるため、[isAllowedSourceUri]を満たさないUri(`file://`等の`content://`以外のスキームや、
     * 自アプリのContentProviderを指す`content://`)は内部ファイルの持ち出しに悪用されうるので
     * コピーせずnullを返す。コピー中の例外はそのまま呼び出し元へ投げる(途中まで書き込んだファイルは削除する)。
     */
    fun copyToStorage(context: Context, uri: Uri): CopiedFile? {
        if (!isAllowedSourceUri(context, uri)) return null
        val resolver = context.contentResolver
        val originalName = queryFileName(context, uri) ?: uri.lastPathSegment ?: "file"
        val extension = safeExtension(originalName)
        val storedFileName = if (extension.isNotEmpty()) {
            "${UUID.randomUUID()}.$extension"
        } else {
            UUID.randomUUID().toString()
        }
        val destination = file(context, storedFileName)
        val input = resolver.openInputStream(uri) ?: return null
        try {
            input.use { inStream ->
                destination.outputStream().use { outStream -> inStream.copyTo(outStream) }
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
        return CopiedFile(
            fileName = originalName,
            storedFileName = storedFileName,
            mimeType = resolver.getType(uri),
            sizeBytes = destination.length()
        )
    }

    /**
     * 添付ファイルのコピー元として受け付けてよいUriか。`content://`のみを許可し、そのうち
     * 自アプリのFileProvider(`${applicationId}.fileprovider`)や自アプリが提供するその他の
     * ContentProviderを指すものは拒否する。
     */
    private fun isAllowedSourceUri(context: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = providerAuthority(uri.authority) ?: return false
        if (isOwnFileProviderAuthority(authority, context.packageName)) return false
        @Suppress("DEPRECATION")
        val ownerPackage = context.packageManager.resolveContentProvider(authority, 0)?.packageName
        return ownerPackage != context.packageName
    }

    /**
     * Uriのauthority部分から、ContentResolverが実際にプロバイダの解決に使う値を取り出す。
     * ContentResolverは`userId@authority`形式の先頭のユーザーIDを取り除いて解決するため、
     * 同様に`@`より後ろを使う(`0@<自アプリのauthority>`のような指定で拒否を回避されないようにする)。
     */
    internal fun providerAuthority(rawAuthority: String?): String? =
        rawAuthority?.substringAfterLast('@')?.takeIf { it.isNotEmpty() }

    internal fun isOwnFileProviderAuthority(authority: String, packageName: String): Boolean =
        authority.equals(packageName + FILE_PROVIDER_AUTHORITY_SUFFIX, ignoreCase = true)

    /**
     * 保存用ファイル名に付ける拡張子。共有元のContentProviderが返す`DISPLAY_NAME`は任意の文字列に
     * なりうる(`a./x`のようにパス区切りを含むと[file]の検証で例外になる)ため、英数字のみ・
     * [MAX_EXTENSION_LENGTH]文字以内の場合だけ採用し、それ以外は拡張子なしにフォールバックする。
     */
    internal fun safeExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.length in 1..MAX_EXTENSION_LENGTH && extension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            extension
        } else {
            ""
        }
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
