package com.toshi0907.oboetotte.backup

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
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
        try {
            val zoned = ZonedDateTime.now()
            val fileName = "$BACKUP_FILE_PREFIX%04d%02d%02d_%02d%02d%02d.zip".format(
                zoned.year, zoned.monthValue, zoned.dayOfMonth,
                zoned.hour, zoned.minute, zoned.second
            )
            val file = folder.createFile("application/zip", fileName)
                ?: throw IOException("バックアップファイルを作成できませんでした")
            BackupManager.export(context, file.uri)
            pruneOldBackups(context, folder)
            CloudBackupSettings.recordResult(context, CloudBackupResult.SUCCESS, System.currentTimeMillis())
            true
        } catch (e: Exception) {
            Log.e(TAG, "クラウドバックアップに失敗しました", e)
            CloudBackupSettings.recordResult(context, CloudBackupResult.FAILURE, System.currentTimeMillis())
            false
        }
    }

    /** 保持件数を超える分を、更新日時の古い順に削除する。 */
    private fun pruneOldBackups(context: Context, folder: DocumentFile) {
        val retention = CloudBackupSettings.getRetentionCount(context)
        folder.listFiles()
            .filter { it.name?.startsWith(BACKUP_FILE_PREFIX) == true && it.name?.endsWith(".zip") == true }
            .sortedByDescending { it.lastModified() }
            .drop(retention)
            .forEach { it.delete() }
    }
}
