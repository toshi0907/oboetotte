package com.toshi0907.oboetotte.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.toshi0907.oboetotte.attachment.AttachmentStorage
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.SavedLocation
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskAttachment
import com.toshi0907.oboetotte.data.TaskList
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * ローカルバックアップの実体。エクスポート形式はZIP(`oboetotte_backup_*.zip`)で、
 * [ENTRY_JSON]エントリにタスク等のメタデータ([FORMAT_VERSION]管理のJSON、内容は従来と同じ)、
 * [ENTRY_ATTACHMENTS_DIR]/配下に添付ファイルの実体を格納する。インポート時はZIPのマジックナンバー
 * (先頭4バイト)で自動判別しており、添付機能追加前の(添付を含まない)プレーンJSON形式の
 * バックアップファイルも引き続き読み込める。
 */
object BackupManager {
    private const val FORMAT_VERSION = 2
    private const val ENTRY_JSON = "backup.json"
    private const val ENTRY_ATTACHMENTS_DIR = "attachments"
    private const val ATTACHMENT_STAGING_DIR_NAME = "attachments_import_staging"
    private const val ATTACHMENT_BACKUP_DIR_NAME = "attachments_import_backup"
    private const val TAG = "BackupManager"
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    suspend fun export(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val lists = db.taskListDao().getAll().first()
        val tasks = db.taskDao().getAll().first()
        val savedLocations = db.savedLocationDao().getAll().first()
        val attachments = db.taskAttachmentDao().getAll().first()

        val json = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put(
                "lists",
                JSONArray(
                    lists.map { list ->
                        JSONObject().apply {
                            put("id", list.id)
                            put("name", list.name)
                        }
                    }
                )
            )
            put(
                "tasks",
                JSONArray(
                    tasks.map { task ->
                        JSONObject().apply {
                            put("id", task.id)
                            put("title", task.title)
                            put("isDone", task.isDone)
                            put("dueAt", task.dueAt ?: JSONObject.NULL)
                            put("listId", task.listId ?: JSONObject.NULL)
                            put("parentTaskId", task.parentTaskId ?: JSONObject.NULL)
                            put("repeatRule", task.repeatRule ?: JSONObject.NULL)
                            put("repeatDaysOfWeek", task.repeatDaysOfWeek ?: JSONObject.NULL)
                            put("locationName", task.locationName ?: JSONObject.NULL)
                            put("latitude", task.latitude ?: JSONObject.NULL)
                            put("longitude", task.longitude ?: JSONObject.NULL)
                            put("radiusMeters", task.radiusMeters ?: JSONObject.NULL)
                            put("notifyOnArrival", task.notifyOnArrival)
                            put("notifyOnDeparture", task.notifyOnDeparture)
                            put("url", task.url ?: JSONObject.NULL)
                            put("memo", task.memo ?: JSONObject.NULL)
                        }
                    }
                )
            )
            put(
                "savedLocations",
                JSONArray(
                    savedLocations.map { location ->
                        JSONObject().apply {
                            put("id", location.id)
                            put("name", location.name)
                            put("latitude", location.latitude)
                            put("longitude", location.longitude)
                            put("radiusMeters", location.radiusMeters)
                        }
                    }
                )
            )
            put(
                "attachments",
                JSONArray(
                    attachments.map { attachment ->
                        JSONObject().apply {
                            put("id", attachment.id)
                            put("taskId", attachment.taskId)
                            put("fileName", attachment.fileName)
                            put("storedFileName", attachment.storedFileName)
                            put("mimeType", attachment.mimeType ?: JSONObject.NULL)
                            put("sizeBytes", attachment.sizeBytes)
                            put("createdAt", attachment.createdAt)
                        }
                    }
                )
            )
        }

        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("出力先を開けませんでした")
        output.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(ENTRY_JSON))
                zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                attachments.forEach { attachment ->
                    val file = AttachmentStorage.file(context, attachment.storedFileName)
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry("$ENTRY_ATTACHMENTS_DIR/${attachment.storedFileName}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    suspend fun import(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("ファイルを開けませんでした")

        val json: JSONObject
        val attachmentFiles = mutableMapOf<String, ByteArray>()

        if (isZip(bytes)) {
            var jsonText: String? = null
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == ENTRY_JSON -> jsonText = zip.readBytes().toString(Charsets.UTF_8)
                        entry.name.startsWith("$ENTRY_ATTACHMENTS_DIR/") && !entry.isDirectory -> {
                            val storedFileName = entry.name.removePrefix("$ENTRY_ATTACHMENTS_DIR/")
                            // パストラバーサルを狙った不正なエントリ名なら例外を投げてインポートを中断する。
                            AttachmentStorage.file(context, storedFileName)
                            attachmentFiles[storedFileName] = zip.readBytes()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            json = JSONObject(jsonText ?: throw IOException("バックアップの形式が不正です"))
        } else {
            json = JSONObject(bytes.toString(Charsets.UTF_8))
        }

        val listsJson = json.getJSONArray("lists")
        val lists = (0 until listsJson.length()).map { i ->
            val obj = listsJson.getJSONObject(i)
            TaskList(id = obj.getLong("id"), name = obj.getString("name"))
        }

        val tasksJson = json.getJSONArray("tasks")
        val tasks = (0 until tasksJson.length()).map { i ->
            val obj = tasksJson.getJSONObject(i)
            Task(
                id = obj.getLong("id"),
                title = obj.getString("title"),
                isDone = obj.getBoolean("isDone"),
                dueAt = if (obj.isNull("dueAt")) null else obj.getLong("dueAt"),
                listId = if (obj.isNull("listId")) null else obj.getLong("listId"),
                parentTaskId = if (obj.isNull("parentTaskId")) null else obj.getLong("parentTaskId"),
                repeatRule = if (obj.isNull("repeatRule")) null else obj.getString("repeatRule"),
                repeatDaysOfWeek = if (obj.isNull("repeatDaysOfWeek")) null else obj.getString("repeatDaysOfWeek"),
                locationName = if (obj.isNull("locationName")) null else obj.getString("locationName"),
                latitude = if (obj.isNull("latitude")) null else obj.getDouble("latitude"),
                longitude = if (obj.isNull("longitude")) null else obj.getDouble("longitude"),
                radiusMeters = if (obj.isNull("radiusMeters")) null else obj.getInt("radiusMeters"),
                notifyOnArrival = obj.optBoolean("notifyOnArrival", false),
                notifyOnDeparture = obj.optBoolean("notifyOnDeparture", false),
                url = if (obj.isNull("url")) null else obj.getString("url"),
                memo = if (obj.isNull("memo")) null else obj.getString("memo")
            )
        }

        // 旧形式のバックアップにはsavedLocations/attachmentsキーが無いため、
        // optJSONArrayで無ければ空扱いにする。
        val savedLocationsJson = json.optJSONArray("savedLocations")
        val savedLocations = if (savedLocationsJson == null) {
            emptyList()
        } else {
            (0 until savedLocationsJson.length()).map { i ->
                val obj = savedLocationsJson.getJSONObject(i)
                SavedLocation(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    latitude = obj.getDouble("latitude"),
                    longitude = obj.getDouble("longitude"),
                    radiusMeters = obj.getInt("radiusMeters")
                )
            }
        }

        val attachmentsJson = json.optJSONArray("attachments")
        val attachments = if (attachmentsJson == null) {
            emptyList()
        } else {
            (0 until attachmentsJson.length()).map { i ->
                val obj = attachmentsJson.getJSONObject(i)
                val storedFileName = obj.getString("storedFileName")
                // パストラバーサルを狙った不正な値なら例外を投げてインポートを中断する。
                AttachmentStorage.file(context, storedFileName)
                TaskAttachment(
                    id = obj.getLong("id"),
                    taskId = obj.getLong("taskId"),
                    fileName = obj.getString("fileName"),
                    storedFileName = storedFileName,
                    mimeType = if (obj.isNull("mimeType")) null else obj.getString("mimeType"),
                    sizeBytes = obj.getLong("sizeBytes"),
                    createdAt = obj.getLong("createdAt")
                )
            }
        }

        // 添付ファイルのメタデータ(JSON)と実体(ZIPエントリ)が1対1で対応していることを検証する。
        // 一方にしか無い場合、実体の無い添付やインポートされない孤立ファイルが生まれてしまう。
        val declaredNames = attachments.map { it.storedFileName }
        if (declaredNames.toSet().size != declaredNames.size) {
            throw IOException("バックアップの形式が不正です(添付ファイルのstoredFileNameが重複しています)")
        }
        if (declaredNames.toSet() != attachmentFiles.keys) {
            throw IOException("バックアップの形式が不正です(添付ファイルのメタデータと実体が一致しません)")
        }

        // ここまでのバリデーションをすべて通過した後にのみ、既存データの削除・新データの反映を行う。
        // DBとファイルシステムは別々のリソースであり単一のトランザクションにできないため、
        // 失敗した場合に「より復元しやすい」順序で処理する。
        // 1. 添付ファイルの実体をまず一時ディレクトリへ書き込む(ディスク容量不足等のI/O失敗は
        //    ここで起きるため、失敗すれば既存のDB・添付ファイルには一切触れずに済む)。
        // 2. 添付ディレクトリの入れ替え(ファイル単位ではなくディレクトリ単位のrenameTo。
        //    同一ボリューム上でのディレクトリ名の付け替えのみで完了する軽い操作で、失敗の
        //    可能性は低い)を、DBの更新より先に行う。この時点で失敗してもDBは未着手のまま。
        // 3. 最後にDBをトランザクションで置き換える。DB側が失敗した場合(Room側で自動的に
        //    ロールバックされる)は、2で入れ替えたディレクトリも明示的に元へ戻す。
        val stagingDir = File(context.filesDir, ATTACHMENT_STAGING_DIR_NAME).apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            attachmentFiles.forEach { (storedFileName, fileBytes) ->
                File(stagingDir, storedFileName).writeBytes(fileBytes)
            }

            val attachmentsDir = AttachmentStorage.directory(context)
            val backupDir = File(context.filesDir, ATTACHMENT_BACKUP_DIR_NAME)
            backupDir.deleteRecursively()
            if (!attachmentsDir.renameTo(backupDir)) {
                throw IOException("添付ファイルディレクトリの入れ替えに失敗しました")
            }
            if (!stagingDir.renameTo(attachmentsDir)) {
                // 失敗時は退避しておいた旧ディレクトリを元の名前に戻す。この「復旧」自体が
                // 失敗する可能性もゼロではないが、同一ボリューム上のディレクトリ名の
                // 付け替えのみであり実際に失敗する見込みは極めて低いため、失敗時はLogに
                // 残すのみとする(端末のストレージ破損など、アプリ側での対処が困難な状況)。
                if (!backupDir.renameTo(attachmentsDir)) {
                    Log.e(TAG, "添付ディレクトリの復旧に失敗しました: $backupDir")
                }
                throw IOException("添付ファイルディレクトリの入れ替えに失敗しました")
            }

            try {
                val db = AppDatabase.getInstance(context)
                db.withTransaction {
                    db.taskDao().deleteAll()
                    db.taskListDao().deleteAll()
                    db.savedLocationDao().deleteAll()
                    db.taskAttachmentDao().deleteAll()
                    db.taskListDao().insertAll(lists)
                    db.taskDao().insertAll(tasks)
                    db.savedLocationDao().insertAll(savedLocations)
                    db.taskAttachmentDao().insertAll(attachments)
                }
            } catch (e: Exception) {
                // DB側が失敗した場合は、直前で入れ替えたディレクトリを元に戻す
                // (新しい添付ディレクトリの内容はstagingDirへ戻し、finallyで削除する)。
                if (!attachmentsDir.renameTo(stagingDir) || !backupDir.renameTo(attachmentsDir)) {
                    Log.e(TAG, "DB更新失敗後の添付ディレクトリの復旧に失敗しました: $backupDir")
                }
                throw e
            }

            backupDir.deleteRecursively()
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun isZip(bytes: ByteArray): Boolean {
        if (bytes.size < ZIP_MAGIC.size) return false
        return ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }
    }
}
