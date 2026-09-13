package com.toshi0907.oboetotte.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * [AppUpdateChecker]が見つけた新しいAPKをダウンロードし、システムのパッケージインストーラーを
 * 起動して更新インストールを行う。ダウンロード先はfilesDir/updates/配下で、
 * `attachment/AttachmentStorage`と同様にFileProvider経由でContent URIを発行する
 * (`res/xml/file_paths.xml`で`updates/`を公開)。
 */
object AppUpdateInstaller {
    private const val UPDATES_DIR_NAME = "updates"
    private const val APK_FILE_NAME = "oboetotte-update.apk"
    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    fun directory(context: Context): File =
        File(context.filesDir, UPDATES_DIR_NAME).apply { mkdirs() }

    /**
     * [url]からAPKをダウンロードして保存する。ブロッキングI/Oを行うため、呼び出し元が
     * `Dispatchers.IO`上で呼び出すことを想定する(`attachment/AttachmentStorage`と同じ方針)。
     * 接続が不安定な環境で無期限にブロックしないよう、[AppUpdateChecker.fetch]と同様に
     * 接続・読み取りタイムアウトを設定する。
     */
    fun download(context: Context, url: String): File {
        val file = File(directory(context), APK_FILE_NAME)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        return file
    }

    /**
     * API 26以降、自身のパッケージインストーラーからのインストールにはこの権限確認が必要。
     * 許可が無い場合は[unknownSourcesSettingsIntent]で設定画面へ誘導する。
     */
    fun hasInstallPermission(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}")
        )

    /** [file]をFileProvider経由で公開し、ACTION_VIEWでパッケージインストーラーを起動するIntent。 */
    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
