package com.toshi0907.oboetotte.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** 直近のクラウド自動バックアップの結果。[CloudBackupSettings]に保存する。 */
enum class CloudBackupResult {
    SUCCESS,
    FAILURE
}

/**
 * クラウド自動バックアップ(Storage Access Frameworkで選択したフォルダへの定期ZIPエクスポート)の
 * 設定をSharedPreferencesで永続化する。保存先はDropbox等のクラウドストレージアプリに限らず、
 * SAFのドキュメントプロバイダとして自身を公開しているアプリのフォルダであれば何でも指定できる
 * (Dropbox APIのようなアプリ固有のAPIキー・OAuth認証は不要)。
 */
object CloudBackupSettings {
    const val MIN_RETENTION_COUNT = 1
    const val MAX_RETENTION_COUNT = 90
    const val DEFAULT_RETENTION_COUNT = 7

    /** 保存時刻を明示的に設定していない場合の既定値(午前3時)。 */
    const val DEFAULT_BACKUP_HOUR = 3
    const val DEFAULT_BACKUP_MINUTE = 0

    private const val PREFS_NAME = "cloud_backup_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_RETENTION_COUNT = "retention_count"
    private const val KEY_LAST_BACKUP_AT = "last_backup_at"
    private const val KEY_LAST_BACKUP_RESULT = "last_backup_result"
    private const val KEY_LAST_BACKUP_ERROR = "last_backup_error"
    private const val KEY_BACKUP_HOUR = "backup_hour"
    private const val KEY_BACKUP_MINUTE = "backup_minute"
    private const val KEY_SCHEDULE_VERSION = "schedule_version"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getFolderUri(context: Context): Uri? =
        prefs(context).getString(KEY_FOLDER_URI, null)?.let { Uri.parse(it) }

    fun setFolderUri(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_FOLDER_URI, uri?.toString()).apply()
    }

    fun getRetentionCount(context: Context): Int =
        prefs(context).getInt(KEY_RETENTION_COUNT, DEFAULT_RETENTION_COUNT)

    /** [count]は[MIN_RETENTION_COUNT]〜[MAX_RETENTION_COUNT]の範囲に丸めてから保存する。 */
    fun setRetentionCount(context: Context, count: Int) {
        val clamped = count.coerceIn(MIN_RETENTION_COUNT, MAX_RETENTION_COUNT)
        prefs(context).edit().putInt(KEY_RETENTION_COUNT, clamped).apply()
    }

    fun getBackupHour(context: Context): Int = prefs(context).getInt(KEY_BACKUP_HOUR, DEFAULT_BACKUP_HOUR)

    fun getBackupMinute(context: Context): Int = prefs(context).getInt(KEY_BACKUP_MINUTE, DEFAULT_BACKUP_MINUTE)

    /** [hour]は0〜23、[minute]は0〜59の範囲に丸めてから保存する。 */
    fun setBackupTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(KEY_BACKUP_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_BACKUP_MINUTE, minute.coerceIn(0, 59))
            .apply()
    }

    /**
     * 登録済みの定期実行がどの版の[CloudBackupScheduler]の設定(実行条件・再試行方針)で
     * 登録されたか。未記録(この仕組みの導入前に登録された)場合は0。
     */
    fun getScheduleVersion(context: Context): Int = prefs(context).getInt(KEY_SCHEDULE_VERSION, 0)

    fun setScheduleVersion(context: Context, version: Int) {
        prefs(context).edit().putInt(KEY_SCHEDULE_VERSION, version).apply()
    }

    fun getLastBackupAt(context: Context): Long? {
        val value = prefs(context).getLong(KEY_LAST_BACKUP_AT, -1L)
        return if (value < 0) null else value
    }

    fun getLastBackupResult(context: Context): CloudBackupResult? {
        val name = prefs(context).getString(KEY_LAST_BACKUP_RESULT, null)
        return CloudBackupResult.entries.find { it.name == name }
    }

    /** 直近の実行が失敗した場合の失敗理由(どの段階で何が起きたか)。成功時・未実行時は`null`。 */
    fun getLastBackupError(context: Context): String? =
        prefs(context).getString(KEY_LAST_BACKUP_ERROR, null)

    /**
     * [error]は失敗時の理由(設定画面にそのまま表示する)。成功時は`null`を渡すことで、
     * 以前の失敗理由を消去する。
     */
    fun recordResult(context: Context, result: CloudBackupResult, at: Long, error: String? = null) {
        prefs(context).edit()
            .putLong(KEY_LAST_BACKUP_AT, at)
            .putString(KEY_LAST_BACKUP_RESULT, result.name)
            .putString(KEY_LAST_BACKUP_ERROR, error)
            .apply()
    }

    /**
     * [recordResult]による変更をリッスンする。定期実行(CloudBackupWorker)による結果更新は
     * アプリのプロセスが生きている間、呼び出し元(TaskViewModel)の同期処理を経由しないため、
     * SharedPreferences側の変更を直接購読してStateFlowに反映する用途で使う。
     * SharedPreferencesは内部でリスナーをWeakReferenceとしてしか保持しないため、
     * 呼び出し元は[listener]自体を(ラムダをその場で渡すのではなく)フィールド等で保持し続けること。
     */
    fun addLastResultChangeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeLastResultChangeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
