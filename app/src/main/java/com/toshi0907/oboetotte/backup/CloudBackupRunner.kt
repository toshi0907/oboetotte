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
 * 選択されたSAFフォルダへ、[CloudBackupSettings.getRetentionCount]件-1件になるまで古い
 * バックアップファイルを先に削除したうえで、[BackupManager.export]と同じ内容のZIPを
 * 日時付きファイル名で1件作成する。
 */
object CloudBackupRunner {
    private const val TAG = "CloudBackup"

    // ローカルの手動エクスポート(MainActivity)は"oboetotte_backup_"というプレフィックスの
    // 同形式のファイル名を使う。ユーザーが手動エクスポートの保存先としてクラウド自動バック
    // アップと同じフォルダを選んだ場合、両者を区別できないとpruneOldBackupsが手動エクスポート
    // したファイルまで削除しかねないため、クラウド自動バックアップ専用のプレフィックスにする。
    private const val BACKUP_FILE_PREFIX = "oboetotte_cloud_backup_"

    // 手動実行(今すぐバックアップ)と定期実行(CloudBackupWorker)が同時に走ると、片方が
    // 作成したばかりのバックアップをもう片方のpruneOldBackupsが削除してしまい、それでも
    // 実行した側はSUCCESSを記録してしまう(実際には保持件数分のバックアップが揃っていない
    // のに成功と報告される)おそれがある。古いバックアップの削除〜ファイル作成〜エクスポート
    // までを1つのMutexで直列化し、常にどちらか一方だけがこのひとまとまりの処理を実行する
    // ようにする。
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
        return try {
            // 新規バックアップを作成する前に、保持件数-1件になるまで古いバックアップを削除して
            // おく。削除を新規作成・エクスポートの後回しにすると、その間だけ保持件数+1件分の
            // 保存領域が一時的に必要になり、保存先の空き容量やクォータが保持件数ちょうどしか
            // 無い場合(＝保持件数の上限に達している場合)に新規ファイルの作成やエクスポートが
            // 失敗してしまうため、先に削除して1件分の空きを確保してから作成する。
            val retention = CloudBackupSettings.getRetentionCount(context)
            pruneOldBackups(folder, retention - 1)

            // 秒までの精度だと、同一秒内に連続実行(「今すぐバックアップ」の連打等)された場合に
            // SAFプロバイダがファイル名を"...(1).zip"のように重複回避してリネームすることがあり、
            // その場合pruneOldBackupsの文字列比較順(space<periodのため"(1)"付きの方が古い扱いに
            // なる)で実際には新しいはずのファイルが古いと誤判定され削除されうる。ミリ秒まで含めて
            // 衝突の可能性を実質無くす。
            val zoned = ZonedDateTime.now()
            val fileName = "$BACKUP_FILE_PREFIX%04d%02d%02d_%02d%02d%02d%03d.zip".format(
                zoned.year, zoned.monthValue, zoned.dayOfMonth,
                zoned.hour, zoned.minute, zoned.second, zoned.nano / 1_000_000
            )
            val created = folder.createFile("application/zip", fileName)
                ?: throw IOException("バックアップファイルを作成できませんでした")
            file = created
            BackupManager.export(context, created.uri)
            exported = true
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

    /**
     * [keepCount]件を超える分を、生成したファイル名の降順(=新しい順)で削除する。
     * [keepCount]が0以下の場合は全件削除する。
     */
    private fun pruneOldBackups(folder: DocumentFile, keepCount: Int) {
        folder.listFiles()
            .filter { it.name?.startsWith(BACKUP_FILE_PREFIX) == true && it.name?.endsWith(".zip") == true }
            // DocumentFile.lastModified()はSAFプロバイダによっては未対応で0を返すことがあり、
            // その場合ソート順が不定になって今作成したばかりのファイルが削除されうる。
            // ファイル名はゼロ埋めの日時を埋め込んで生成しているため、辞書順=時系列順になる。
            .sortedByDescending { it.name.orEmpty() }
            .drop(keepCount.coerceAtLeast(0))
            .forEach { it.delete() }
    }
}
