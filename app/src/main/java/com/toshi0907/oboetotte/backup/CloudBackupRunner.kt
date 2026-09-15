package com.toshi0907.oboetotte.backup

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * クラウド自動バックアップの実処理。[CloudBackupWorker](定期実行)と設定画面の
 * 「今すぐバックアップ」ボタン(手動実行)の両方から共通で呼ばれる。[CloudBackupSettings]で
 * 選択されたSAFフォルダへ[BackupManager.export]と同じ内容のZIPを日時付きファイル名で1件作成し、
 * [CloudBackupSettings.getRetentionCount]件を超える古いバックアップファイルを削除する。
 */
object CloudBackupRunner {
    private const val TAG = "CloudBackup"
    private const val BACKUP_FILE_PREFIX = "oboetotte_backup_"

    // 手動実行(今すぐバックアップ)と定期実行(CloudBackupWorker)が同時に走ると、片方が
    // 作成したばかりのバックアップをもう片方のpruneOldBackupsが削除してしまい、それでも
    // 実行した側はSUCCESSを記録してしまう(実際には保持件数分のバックアップが揃っていない
    // のに成功と報告される)おそれがある。ファイル作成〜エクスポート〜古いバックアップの
    // 削除までを1つのMutexで直列化し、常にどちらか一方だけがこのひとまとまりの処理を
    // 実行するようにする。
    private val mutex = Mutex()

    suspend fun run(context: Context): Boolean = withContext(Dispatchers.IO) {
        val folderUri = CloudBackupSettings.getFolderUri(context)
        if (folderUri == null) {
            Log.w(TAG, "保存先フォルダが未設定のためクラウドバックアップをスキップします")
            CloudBackupSettings.recordResult(context, CloudBackupResult.FAILURE, System.currentTimeMillis())
            return@withContext false
        }
        val folder = DocumentFile.fromTreeUri(context, folderUri)
        if (folder == null || !folder.canWrite()) {
            // フォルダを提供していたアプリのアンインストールや権限の失効などで書き込めなくなった場合。
            Log.e(TAG, "保存先フォルダに書き込めません: $folderUri")
            CloudBackupSettings.recordResult(context, CloudBackupResult.FAILURE, System.currentTimeMillis())
            return@withContext false
        }
        mutex.withLock { runLocked(context, folder) }
    }

    private suspend fun runLocked(context: Context, folder: DocumentFile): Boolean {
        var file: DocumentFile? = null
        var exported = false
        try {
            val zoned = ZonedDateTime.now()
            val fileName = "$BACKUP_FILE_PREFIX%04d%02d%02d_%02d%02d%02d.zip".format(
                zoned.year, zoned.monthValue, zoned.dayOfMonth,
                zoned.hour, zoned.minute, zoned.second
            )
            val created = folder.createFile("application/zip", fileName)
                ?: throw IOException("バックアップファイルを作成できませんでした")
            file = created
            BackupManager.export(context, created.uri)
            exported = true
            pruneOldBackups(context, folder)
            CloudBackupSettings.recordResult(context, CloudBackupResult.SUCCESS, System.currentTimeMillis())
            true
        } catch (e: CancellationException) {
            // 呼び出し元のコルーチン(ViewModelのクリアやWorkManagerによる停止)がキャンセルされた
            // だけなので、通常の失敗として記録せずそのまま伝播させる(refreshTaskWidget等、
            // 既存コードの`CancellationException`は特別扱いする方針と同じ)。
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "クラウドバックアップに失敗しました", e)
            CloudBackupSettings.recordResult(context, CloudBackupResult.FAILURE, System.currentTimeMillis())
            false
        } finally {
            if (!exported) {
                // エクスポートが完了しなかった場合、作成済みの空/不完全なファイルを残すと
                // 次回以降の保持件数の計算に混ざってしまうため削除しておく。
                file?.let {
                    try {
                        it.delete()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "失敗したバックアップファイルの削除に失敗しました", e)
                    }
                }
            }
        }
    }

    /** 保持件数を超える分を、生成したファイル名の降順(=新しい順)で削除する。 */
    private fun pruneOldBackups(context: Context, folder: DocumentFile) {
        val retention = CloudBackupSettings.getRetentionCount(context)
        folder.listFiles()
            .filter { it.name?.startsWith(BACKUP_FILE_PREFIX) == true && it.name?.endsWith(".zip") == true }
            // DocumentFile.lastModified()はSAFプロバイダによっては未対応で0を返すことがあり、
            // その場合ソート順が不定になって今作成したばかりのファイルが削除されうる。
            // ファイル名はゼロ埋めの日時を埋め込んで生成しているため、辞書順=時系列順になる。
            .sortedByDescending { it.name.orEmpty() }
            .drop(retention)
            .forEach { it.delete() }
    }
}
