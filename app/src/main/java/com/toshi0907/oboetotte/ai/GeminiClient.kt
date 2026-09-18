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

    /** 出典の取得元ツール。 */
    enum class SourceOrigin { WEB_SEARCH, URL_CONTEXT }

    /** グラウンディングツール使用時に応答へ付く出典。[title]が取得できない場合は[uri]をそのまま使う。 */
    data class Source(val title: String, val uri: String, val origin: SourceOrigin)

    /** [Task.aiCachedSources]へ保存するJSON配列文字列へ変換する。出典が無ければnull。 */
    fun encodeSources(sources: List<Source>): String? {
        if (sources.isEmpty()) return null
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject().apply {
                    put("title", source.title)
                    put("uri", source.uri)
                    put("origin", source.origin.name)
                }
            )
        }
        return array.toString()
    }

    /** [encodeSources]の逆変換。壊れたJSONや不明な`origin`は無視・[SourceOrigin.WEB_SEARCH]扱いにする。 */
    fun decodeSources(json: String?): List<Source> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val uri = obj.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val origin = SourceOrigin.entries.find { it.name == obj.optString("origin") } ?: SourceOrigin.WEB_SEARCH
                Source(title = obj.optString("title").takeIf { it.isNotBlank() } ?: uri, uri = uri, origin = origin)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    sealed interface Result {
        data class Success(val text: String, val sources: List<Source> = emptyList()) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * [useWebSearch]/[useUrlContext]は、それぞれGemini APIの組み込みツール
     * `google_search`(Web検索によるグラウンディング)・`url_context`(プロンプト中のURLの
     * 内容を取得してコンテキストに使う)に対応する。
     */
    fun generateContent(
        apiKey: String,
        model: String,
        prompt: String,
        timeoutMillis: Int,
        useWebSearch: Boolean = false,
        useUrlContext: Boolean = false
    ): Result {
        return try {
            val url = URL(ENDPOINT_TEMPLATE.format(model, apiKey))
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.doOutput = true

                val tools = JSONArray().apply {
                    if (useWebSearch) put(JSONObject().put("google_search", JSONObject()))
                    if (useUrlContext) put(JSONObject().put("url_context", JSONObject()))
                }

                val requestBody = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
                            }
                        )
                    )
                    if (tools.length() > 0) {
                        put("tools", tools)
                    }
                }
                connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    return Result.Failure("HTTP $responseCode")
                }
                val responseText = connection.inputStream.use { it.bufferedReader().readText() }
                val candidate = JSONObject(responseText).optJSONArray("candidates")?.optJSONObject(0)
                val text = candidate
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                if (text.isNullOrBlank()) Result.Failure("空の応答でした") else {
                    Result.Success(text, extractSources(candidate))
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: "通信エラーが発生しました")
        }
    }

    /**
     * `google_search`は`groundingMetadata.groundingChunks`、`url_context`は
     * `urlContextMetadata.urlMetadata`に出典情報を返す。フィールド名の解釈を誤っていても
     * (`opt*`系のみ使用のため)例外にはならず、単に出典が空になるだけで応答本文自体は失われない。
     */
    private fun extractSources(candidate: JSONObject?): List<Source> {
        if (candidate == null) return emptyList()
        val sources = mutableListOf<Source>()

        candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
            for (i in 0 until chunks.length()) {
                val chunk = chunks.optJSONObject(i) ?: continue
                val web = chunk.optJSONObject("web")
                val uri = web?.optString("uri")?.takeIf { it.isNotBlank() } ?: continue
                val title = web.optString("title")?.takeIf { it.isNotBlank() }
                sources.add(Source(title = title ?: uri, uri = uri, origin = SourceOrigin.WEB_SEARCH))
            }
        }

        candidate.optJSONObject("urlContextMetadata")?.optJSONArray("urlMetadata")?.let { entries ->
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                // urlRetrievalStatusが取得失敗(ERROR/UNSAFE等)でもretrievedUrlは返るため、
                // 実際にコンテキストとして使われたSUCCESSの場合のみ出典として扱う。
                if (entry.optString("urlRetrievalStatus") != "URL_RETRIEVAL_STATUS_SUCCESS") continue
                val uri = entry.optString("retrievedUrl")?.takeIf { it.isNotBlank() } ?: continue
                sources.add(Source(title = uri, uri = uri, origin = SourceOrigin.URL_CONTEXT))
            }
        }

        return sources
    }
}
