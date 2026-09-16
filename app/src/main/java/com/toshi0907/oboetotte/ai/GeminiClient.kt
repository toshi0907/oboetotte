package com.toshi0907.oboetotte.ai

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini API(`generateContent`)への問い合わせ。[com.toshi0907.oboetotte.update.AppUpdateChecker]と
 * 同じ方針で追加ライブラリ(Google AI SDK)は使わず、標準ライブラリの`HttpURLConnection`+`org.json`
 * で実装する。ブロッキングI/Oを行うため、呼び出し元が`Dispatchers.IO`上で呼び出すことを想定する。
 */
object GeminiClient {
    private const val ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    /**
     * 通知発火時(BroadcastReceiverのgoAsync)から呼ぶ際の接続・読み込みタイムアウト(各4秒、
     * 合計最大8秒程度)。BroadcastReceiverがgoAsyncで実行を継続できる時間は限られるため、
     * 通知の表示自体を長時間ブロックしないよう短めに設定している。
     */
    const val NOTIFICATION_TIMEOUT_MILLIS = 4_000

    /** 設定画面の「AIテスト実行」から呼ぶ際のタイムアウト(通知経由より余裕を持たせる)。 */
    const val TEST_TIMEOUT_MILLIS = 15_000

    sealed interface Result {
        data class Success(val text: String) : Result
        data class Failure(val message: String) : Result
    }

    fun generateContent(apiKey: String, model: String, prompt: String, timeoutMillis: Int): Result {
        return try {
            val url = URL(ENDPOINT_TEMPLATE.format(model, apiKey))
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.doOutput = true

                val requestBody = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
                            }
                        )
                    )
                }
                connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    return Result.Failure("HTTP $responseCode")
                }
                val responseText = connection.inputStream.use { it.bufferedReader().readText() }
                val text = JSONObject(responseText)
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                if (text.isNullOrBlank()) Result.Failure("空の応答でした") else Result.Success(text)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: "通信エラーが発生しました")
        }
    }
}
