package com.toshi0907.oboetotte.update

import com.toshi0907.oboetotte.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * GitHubの`latest-debug`リリース(公開リポジトリのため認証不要)を見て、現在実行中のビルドより
 * 新しいAPKが公開されているかどうかを判定する。`versionCode`/`versionName`は固定値
 * (`app/build.gradle.kts`参照)で、CIは`main`への毎pushで`latest-debug`タグを上書きするだけの
 * ため、ビルド時に埋め込んだgitコミットSHA([BuildConfig.GIT_COMMIT_SHA])と、リリース本文に
 * 含まれる`コミット: <sha>`(`.github/workflows/android-build.yml`の`${{ github.sha }}`)を
 * 比較する方式を取る。
 */
object AppUpdateChecker {
    private const val RELEASE_API_URL =
        "https://api.github.com/repos/toshi0907/oboetotte/releases/tags/latest-debug"
    private val COMMIT_REGEX = Regex("コミット: ([0-9a-f]{7,40})")

    sealed interface Result {
        /** [downloadUrl]はAPKアセットの直接ダウンロードURL、[commitSha]は新しいビルドのコミットSHA。 */
        data class UpdateAvailable(val downloadUrl: String, val commitSha: String) : Result
        data object UpToDate : Result

        /** ネットワークエラーやビルド情報が不明("unknown")など、判定できなかった場合。 */
        data object CheckFailed : Result
    }

    /**
     * ブロッキングI/Oを行うため、呼び出し元が`Dispatchers.IO`上で呼び出すことを想定する
     * (`attachment/AttachmentStorage`と同じ方針)。
     */
    fun check(): Result {
        val currentSha = BuildConfig.GIT_COMMIT_SHA
        if (currentSha.isBlank() || currentSha == "unknown") return Result.CheckFailed

        return try {
            val json = JSONObject(fetch(RELEASE_API_URL))
            val latestSha = COMMIT_REGEX.find(json.optString("body")).let { it?.groupValues?.get(1) }
                ?: return Result.CheckFailed
            if (currentSha == latestSha) {
                Result.UpToDate
            } else {
                val assets = json.getJSONArray("assets")
                val apkUrl = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                    ?.getString("browser_download_url")
                    ?: return Result.CheckFailed
                Result.UpdateAvailable(apkUrl, latestSha)
            }
        } catch (e: Exception) {
            Result.CheckFailed
        }
    }

    // GitHub APIはUser-Agentヘッダが無いリクエストを拒否するため必ず付与する。
    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "oboetotte-app-update-checker")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            return connection.inputStream.use { it.bufferedReader().readText() }
        } finally {
            connection.disconnect()
        }
    }
}
