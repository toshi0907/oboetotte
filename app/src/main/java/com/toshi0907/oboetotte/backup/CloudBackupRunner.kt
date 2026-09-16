package com.toshi0907.oboetotte.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * クラウド自動バックアップの実処理。[CloudBackupWorker](定期実行)と設定画面の
 * 「今すぐバックアップ」ボタン(手動実行)の両方から共通で呼ばれる。[BackupManager.export]と
 * 同じ内容のZIPをまず端末内の一時ファイルへ書き出し、それが成功した場合のみ
 * [CloudBackupSettings.getRetentionCount]件-1件になるまで保存先フォルダの古いバックアップ
 * ファイルを削除してから、選択されたSAFフォルダへ日時付きファイル名でコピーする。
 */
object CloudBackupRunner {
    private const val TAG = "CloudBackup"
    private const val STAGING_FILE_NAME = "cloud_backup_staging.zip"

    // ローカルの手動エクスポート(MainActivity)は"oboetotte_backup_"というプレフィックスの
    // 同形式のファイル名を使う。ユーザーが手動エクスポートの保存先としてクラウド自動バック
    // アップと同じフォルダを選んだ場合、両者を区別できないとpruneOldBackupsが手動エクスポート
    // したファイルまで削除しかねないため、クラウド自動バックアップ専用のプレフィックスにする。
    private const val BACKUP_FILE_PREFIX = "oboetotte_cloud_backup_"

    // 手動実行(今すぐバックアップ)と定期実行(CloudBackupWorker)が同時に走ると、片方が
    // 作成したばかりのバックアップをもう片方のpruneOldBackupsが削除してしまい、それでも
    // 実行した側はSUCCESSを記録してしまう(実際には保持件数分のバックアップが揃っていない
    // のに成功と報告される)おそれがある。エクスポート〜古いバックアップの削除〜ファイル
    // 作成までを1つのMutexで直列化し、常にどちらか一方だけがこのひとまとまりの処理を
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
        val staging = File(context.cacheDir, STAGING_FILE_NAME)
        return try {
            // まずZIPの実体を端末内の一時ファイルへ書き出す。保存先フォルダにはまだ一切
            // 触れないため、ここで失敗しても既存の古いバックアップはそのまま残る。
            BackupManager.export(context, Uri.fromFile(staging))

            // ローカルでの書き出しが確実に成功した後、保持件数-1件になるまで古いバックアップ
            // を削除して保存先フォルダに1件分の空きを確保する。この削除を新規ファイルの
            // 作成前ではなく確認前に行うと、保持件数ちょうどの状態から次のバックアップを
            // 取る際に一時的に保持件数+1件分の保存領域が必要になり、保存先の空き容量や
            // クォータが保持件数ちょうどしか無い場合(＝保持件数の上限に達している場合)に
            // 失敗してしまう。この削除をZIPの書き出し前(エクスポートの成否が分かる前)に
            // 行うと、DBの読み込みやZIP生成自体が失敗した際に古いバックアップを削除しただけで
            // 新しいものが用意できず、既存のバックアップを失ってしまう。ZIPの書き出しを
            // 済ませた後に削除することで、この失敗パターンは避けられる(なお、削除後の
            // 保存先への新規ファイル作成・コピー自体がネットワークエラー等で失敗した場合は、
            // その回に限り保持件数より1件少ない状態になりうるが、これは保存先の容量が
            // 保持件数ちょうどしか無い状況で新規ファイルを追加する以上、原理的に避けられない)。
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
            val output = context.contentResolver.openOutputStream(created.uri)
                ?: throw IOException("バックアップファイルへ書き込めませんでした")
            output.use { out -> staging.inputStream().use { it.copyTo(out) } }
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
            staging.delete()
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
