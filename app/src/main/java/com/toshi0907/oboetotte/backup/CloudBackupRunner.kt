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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * クラウド自動バックアップの実処理。[CloudBackupWorker](定期実行)と設定画面の
 * 「今すぐバックアップ」ボタン(手動実行)の両方から共通で呼ばれる。[BackupManager.export]と
 * 同じ内容のZIPをまず端末内の一時ファイルへ書き出し、それが成功した場合のみ
 * [CloudBackupSettings.getRetentionCount]件-1件になるまで保存先フォルダの古いバックアップ
 * ファイルを削除してから、選択されたSAFフォルダへ日時付きファイル名でコピーする。
 * 保存先フォルダへの書き込みは、プロバイダの一時的な失敗に備えて最大[MAX_WRITE_ATTEMPTS]回まで
 * 待機を挟みつつ再試行する。
 */
object CloudBackupRunner {
    private const val TAG = "CloudBackup"
    private const val STAGING_FILE_NAME = "cloud_backup_staging.zip"

    // ローカルの手動エクスポート(MainActivity)は"oboetotte_backup_"というプレフィックスの
    // 同形式のファイル名を使う。ユーザーが手動エクスポートの保存先としてクラウド自動バック
    // アップと同じフォルダを選んだ場合、両者を区別できないとpruneOldBackupsが手動エクスポート
    // したファイルまで削除しかねないため、クラウド自動バックアップ専用のプレフィックスにする。
    private const val BACKUP_FILE_PREFIX = "oboetotte_cloud_backup_"

    // pCloud・Dropbox等のクラウドストレージアプリが提供するSAFのドキュメントプロバイダは、
    // そのアプリのプロセスが起動していない状態から初めて呼ばれた際、プロセスの起動やフォルダ
    // 情報の読み込みが間に合わずにフォルダのフラグ(書き込み可否)やファイル作成の要求に正しく
    // 応答できないことがある(手動実行で「1回目は失敗・2回目は成功」、定期実行ではプロセスが
    // 毎回停止しているため常に失敗、という症状)。保存先フォルダへの書き込み一式(書き込み可否の
    // 確認〜古いバックアップの削除〜ファイル作成・コピー)を、待機を挟みつつ数回まで再試行する。
    private const val MAX_WRITE_ATTEMPTS = 3
    private val RETRY_DELAYS_MILLIS = longArrayOf(5_000L, 15_000L)

    // 手動実行(今すぐバックアップ)と定期実行(CloudBackupWorker)が同時に走ると、片方が
    // 作成したばかりのバックアップをもう片方のpruneOldBackupsが削除してしまい、それでも
    // 実行した側はSUCCESSを記録してしまう(実際には保持件数分のバックアップが揃っていない
    // のに成功と報告される)おそれがある。エクスポート〜古いバックアップの削除〜ファイル
    // 作成までを1つのMutexで直列化し、常にどちらか一方だけがこのひとまとまりの処理を
    // 実行するようにする。
    private val mutex = Mutex()

    /** 失敗した段階を表す説明([message])付きで投げ、設定画面に表示する失敗理由とする。 */
    private class BackupStepException(message: String, cause: Throwable? = null) : IOException(message, cause)

    suspend fun run(context: Context): Boolean = withContext(Dispatchers.IO) {
        val folderUri = CloudBackupSettings.getFolderUri(context)
        if (folderUri == null) {
            Log.w(TAG, "保存先フォルダが未設定のためクラウドバックアップをスキップします")
            CloudBackupSettings.recordResult(
                context, CloudBackupResult.FAILURE, System.currentTimeMillis(), "保存先フォルダが未設定です"
            )
            return@withContext false
        }
        mutex.withLock { runLocked(context, folderUri) }
    }

    private suspend fun runLocked(context: Context, folderUri: Uri): Boolean {
        val staging = File(context.cacheDir, STAGING_FILE_NAME)
        try {
            // まずZIPの実体を端末内の一時ファイルへ書き出す。保存先フォルダにはまだ一切
            // 触れないため、ここで失敗しても既存の古いバックアップはそのまま残る。
            try {
                BackupManager.export(context, Uri.fromFile(staging))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return recordFailure(context, "バックアップデータの作成に失敗しました", e)
            }

            // 失敗した試行で作成されたものの削除できなかった(または作成に成功したか不明な)
            // ファイル名。空/不完全なファイルを後続の試行のpruneOldBackupsが正常なバックアップと
            // して数えてしまうと、その分だけ正常な古いバックアップが余分に削除されるため、
            // 保持件数の計算から除外した上で削除を再試行する。
            val orphanNames = mutableSetOf<String>()
            var lastError: Exception? = null
            for (attempt in 1..MAX_WRITE_ATTEMPTS) {
                if (attempt > 1) {
                    delay(RETRY_DELAYS_MILLIS[(attempt - 2).coerceAtMost(RETRY_DELAYS_MILLIS.size - 1)])
                }
                try {
                    writeToFolder(context, folderUri, staging, orphanNames)
                    CloudBackupSettings.recordResult(context, CloudBackupResult.SUCCESS, System.currentTimeMillis())
                    return true
                } catch (e: CancellationException) {
                    // 呼び出し元のコルーチン(ViewModelのクリアやWorkManagerによる停止)がキャンセルされた
                    // だけなので、通常の失敗として記録せずそのまま伝播させる(refreshTaskWidget等、
                    // 既存コードの`CancellationException`は特別扱いする方針と同じ)。
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "クラウドバックアップの書き込みに失敗しました($attempt/$MAX_WRITE_ATTEMPTS回目)", e)
                    lastError = e
                }
            }
            // 全試行が失敗した場合も、残った不完全なファイルを次回以降の実行に持ち越さないよう
            // 最後にもう一度削除を試みる。
            if (orphanNames.isNotEmpty()) {
                try {
                    DocumentFile.fromTreeUri(context, folderUri)?.let { deleteOrphans(it, orphanNames) }
                } catch (e: Exception) {
                    Log.w(TAG, "不完全なバックアップファイルの削除に失敗しました", e)
                }
            }
            val error = lastError ?: IOException("不明なエラー")
            val reason = if (error is BackupStepException) error.message.orEmpty() else "保存先への書き込みに失敗しました"
            return recordFailure(context, "$reason(${MAX_WRITE_ATTEMPTS}回試行)", error)
        } finally {
            staging.delete()
        }
    }

    private fun recordFailure(context: Context, reason: String, error: Exception): Boolean {
        Log.e(TAG, "クラウドバックアップに失敗しました: $reason", error)
        // 下位の例外(プロバイダが投げたもの等)の内容も併記し、原因の切り分けに使えるようにする。
        val underlying = if (error is BackupStepException) error.cause else error
        val detail = underlying?.let { ": ${it.javaClass.simpleName}${it.message?.let { m -> " $m" }.orEmpty()}" }.orEmpty()
        CloudBackupSettings.recordResult(
            context, CloudBackupResult.FAILURE, System.currentTimeMillis(), reason + detail
        )
        return false
    }

    /**
     * 一時ファイル[staging]の内容を保存先フォルダ[folderUri]へ新規ファイルとして書き込む。
     * 失敗した場合は作成途中のファイルを削除した上で、どの段階で失敗したかを表す
     * [BackupStepException]等を投げる(呼び出し元が再試行する)。作成途中のファイルを削除
     * できなかった場合は、そのファイル名を[orphanNames]に追加する。
     */
    private fun writeToFolder(context: Context, folderUri: Uri, staging: File, orphanNames: MutableSet<String>) {
        // 再試行のたびにDocumentFileを作り直し、プロバイダから最新のフォルダ情報を取得し直す。
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw BackupStepException("保存先フォルダを開けませんでした")
        if (!folder.canWrite()) {
            // フォルダを提供していたアプリのアンインストールや権限の失効、またはプロバイダの
            // 起動直後で書き込み可否を正しく返せなかった場合。
            throw BackupStepException("保存先フォルダに書き込めません")
        }

        // ローカルでの書き出しが確実に成功した後、保持件数-1件になるまで古いバックアップ
        // を削除して保存先フォルダに1件分の空きを確保する。この削除を新規ファイルの
        // 作成・書き込みの後に行うと、保持件数ちょうどの状態から次のバックアップを
        // 取る際に一時的に保持件数+1件分の保存領域が必要になり、保存先の空き容量や
        // クォータが保持件数ちょうどしか無い場合(＝保持件数の上限に達している場合)に
        // 失敗してしまう。この削除をZIPの書き出し前(エクスポートの成否が分かる前)に
        // 行うと、DBの読み込みやZIP生成自体が失敗した際に古いバックアップを削除しただけで
        // 新しいものが用意できず、既存のバックアップを失ってしまう。ZIPの書き出しを
        // 済ませた後に削除することで、この失敗パターンは避けられる(なお、削除後の
        // 保存先への新規ファイル作成・コピー自体がネットワークエラー等で失敗した場合は、
        // その回に限り保持件数より1件少ない状態になりうるが、これは保存先の容量が
        // 保持件数ちょうどしか無い状況で新規ファイルを追加する以上、原理的に避けられない)。
        // 再試行時に繰り返し呼ばれても、保持件数-1件より多い分だけを消すので結果は変わらない。
        val retention = CloudBackupSettings.getRetentionCount(context)
        try {
            deleteOrphans(folder, orphanNames)
            pruneOldBackups(folder, retention - 1, orphanNames)
        } catch (e: Exception) {
            throw BackupStepException("古いバックアップの削除に失敗しました", e)
        }

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
        val created = try {
            folder.createFile("application/zip", fileName)
        } catch (e: Exception) {
            // 例外が投げられても、プロバイダ側ではファイルが作成済みの可能性がある。
            orphanNames.add(fileName)
            throw BackupStepException("バックアップファイルを作成できませんでした", e)
        } ?: throw BackupStepException("バックアップファイルを作成できませんでした")

        var written = false
        try {
            val output = context.contentResolver.openOutputStream(created.uri)
                ?: throw BackupStepException("バックアップファイルへ書き込めませんでした")
            output.use { out -> staging.inputStream().use { it.copyTo(out) } }
            written = true
        } catch (e: BackupStepException) {
            throw e
        } catch (e: Exception) {
            throw BackupStepException("バックアップファイルへ書き込めませんでした", e)
        } finally {
            if (!written) {
                // 書き込みが完了しなかった場合、作成済みの空/不完全なファイルを残すと
                // 次回以降の保持件数の計算に混ざってしまうため削除しておく。
                val deleted = try {
                    created.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "失敗したバックアップファイルの削除に失敗しました", e)
                    false
                }
                if (!deleted) {
                    // プロバイダが重複回避でリネームしている場合もあるため、実際の名前を優先する。
                    orphanNames.add(created.name ?: fileName)
                }
            }
        }
    }

    /**
     * [keepCount]件を超える分を、生成したファイル名の降順(=新しい順)で削除する。
     * [keepCount]が0以下の場合は全件削除する。[excludedNames]のファイルは数えず、削除もしない。
     */
    private fun pruneOldBackups(folder: DocumentFile, keepCount: Int, excludedNames: Set<String>) {
        folder.listFiles()
            .filter { it.name?.startsWith(BACKUP_FILE_PREFIX) == true && it.name?.endsWith(".zip") == true }
            // 失敗した試行が残した不完全なファイルは正常なバックアップとして数えない。
            .filterNot { it.name in excludedNames }
            // DocumentFile.lastModified()はSAFプロバイダによっては未対応で0を返すことがあり、
            // その場合ソート順が不定になって今作成したばかりのファイルが削除されうる。
            // ファイル名はゼロ埋めの日時を埋め込んで生成しているため、辞書順=時系列順になる。
            .sortedByDescending { it.name.orEmpty() }
            .drop(keepCount.coerceAtLeast(0))
            .forEach { it.delete() }
    }

    /** [orphanNames]のファイルの削除を試み、削除できた(または既に存在しない)ものを集合から除く。 */
    private fun deleteOrphans(folder: DocumentFile, orphanNames: MutableSet<String>) {
        if (orphanNames.isEmpty()) return
        val existing = folder.listFiles().filter { it.name in orphanNames }.associateBy { it.name }
        orphanNames.removeAll { name ->
            val file = existing[name] ?: return@removeAll true
            try {
                file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "不完全なバックアップファイルの削除に失敗しました: $name", e)
                false
            }
        }
    }
}
