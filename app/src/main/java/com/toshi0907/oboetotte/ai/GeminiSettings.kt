package com.toshi0907.oboetotte.ai

import android.content.Context

/**
 * 選択可能なGeminiのモデル。無料枠のあるFlash系のみを候補にする。[name]をそのまま
 * SharedPreferencesのキーの値として保存するため、既存の値との互換のためenum定数名を変更しないこと。
 */
enum class GeminiModel(val apiName: String, val label: String) {
    GEMINI_2_0_FLASH("gemini-2.0-flash", "Gemini 2.0 Flash"),
    GEMINI_2_5_FLASH("gemini-2.5-flash", "Gemini 2.5 Flash"),
    GEMINI_2_5_FLASH_LITE("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite");

    companion object {
        val DEFAULT = GEMINI_2_0_FLASH

        fun fromName(value: String?): GeminiModel = entries.find { it.name == value } ?: DEFAULT
    }
}

/**
 * AI連携(Gemini)のAPIキー・使用モデルをSharedPreferencesで永続化する。リポジトリはpublicなため
 * APIキーはコード・ビルド設定に一切含めず、ユーザー自身が発行したキーを設定画面で入力し
 * 端末内にのみ保存する運用にしている。
 */
object GeminiSettings {
    private const val PREFS_NAME = "gemini_settings"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, apiKey: String?) {
        prefs(context).edit().putString(KEY_API_KEY, apiKey?.trim()?.ifBlank { null }).apply()
    }

    fun getModel(context: Context): GeminiModel = GeminiModel.fromName(prefs(context).getString(KEY_MODEL, null))

    fun setModel(context: Context, model: GeminiModel) {
        prefs(context).edit().putString(KEY_MODEL, model.name).apply()
    }
}
