package com.toshi0907.oboetotte.update

import com.toshi0907.oboetotte.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * GitHubの`latest-debug`リリース(公開リポジトリのため認証不要)を見て、現在実行中のビルドより
 * 新しいAPKが公開されているかどうかを判定する。`versionCode`/`versionName`は固定値
 * (`app/build.gradle.kts`参照)で、CIは`main`への毎pushで`latest-debug`タグを上書きするだけの
 * ため、ビルド時に埋め込んだ単調増加するビルド番号([BuildConfig.BUILD_NUMBER]、GitHub Actions上の
 * `GITHUB_RUN_NUMBER`)と、リリース本文に含まれる`ビルド番号: <n>`
 * (`.github/workflows/android-build.yml`の`${{ github.run_number }}`)を比較する方式を取る。
 * コミットSHA同士の比較では「等しいか」しか分からず新旧の順序を判定できない
 * (ローカルビルドのSHAがたまたま`latest-debug`と異なるだけで「新しい」と誤判定しうる)ため、
 * 単調増加する値での大小比較にしている。
 */
object AppUpdateChecker {
    private const val RELEASE_API_URL =
        "https://api.github.com/repos/toshi0907/oboetotte/releases/tags/latest-debug"
    private val BUILD_NUMBER_REGEX = Regex("ビルド番号: (\\d+)")

    sealed interface Result {
        /** [downloadUrl]はAPKアセットの直接ダウンロードURL、[buildNumber]は新しいビルドの番号。 */
        data class UpdateAvailable(val downloadUrl: String, val buildNumber: Int) : Result
        data object UpToDate : Result

        /** ネットワークエラーやビルド番号が不明(0以下)など、判定できなかった場合。 */
        data object CheckFailed : Result
    }

    /**
     * ブロッキングI/Oを行うため、呼び出し元が`Dispatchers.IO`上で呼び出すことを想定する
     * (`attachment/AttachmentStorage`と同じ方針)。
     */
    fun check(): Result {
        val currentBuildNumber = BuildConfig.BUILD_NUMBER
        if (currentBuildNumber <= 0) return Result.CheckFailed

        return try {
            val json = JSONObject(fetch(RELEASE_API_URL))
            val latestBuildNumber = BUILD_NUMBER_REGEX.find(json.optString("body"))
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: return Result.CheckFailed
            if (latestBuildNumber <= currentBuildNumber) {
                Result.UpToDate
            } else {
                val assets = json.getJSONArray("assets")
                val apkUrl = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                    ?.getString("browser_download_url")
                    ?: return Result.CheckFailed
                Result.UpdateAvailable(apkUrl, latestBuildNumber)
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
