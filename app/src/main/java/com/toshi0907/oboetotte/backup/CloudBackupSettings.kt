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

    private const val PREFS_NAME = "cloud_backup_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_RETENTION_COUNT = "retention_count"
    private const val KEY_LAST_BACKUP_AT = "last_backup_at"
    private const val KEY_LAST_BACKUP_RESULT = "last_backup_result"

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

    fun getLastBackupAt(context: Context): Long? {
        val value = prefs(context).getLong(KEY_LAST_BACKUP_AT, -1L)
        return if (value < 0) null else value
    }

    fun getLastBackupResult(context: Context): CloudBackupResult? {
        val name = prefs(context).getString(KEY_LAST_BACKUP_RESULT, null)
        return CloudBackupResult.entries.find { it.name == name }
    }

    fun recordResult(context: Context, result: CloudBackupResult, at: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_BACKUP_AT, at)
            .putString(KEY_LAST_BACKUP_RESULT, result.name)
            .apply()
    }
}
