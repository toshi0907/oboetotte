package com.toshi0907.oboetotte.widget

import androidx.compose.ui.graphics.Color

/**
 * ウィジェットの背景色パターン。ウィジェットごとに[TaskWidgetConfigureActivity]で個別に選択でき、
 * [TaskWidget]が[androidx.glance.appwidget.state.PreferencesGlanceStateDefinition]経由で
 * ウィジェットインスタンスごとの選択値を読み出す。[name]をそのままPreferencesのキーの値として
 * 保存するため、既存の値との互換のためenum定数名を変更しないこと。
 */
enum class WidgetBackground(val label: String, val color: Color?, val textColor: Color?) {
    /** 背景を描画しない(従来の見た目)。文字色も上書きせず端末のデフォルトに委ねる。 */
    TRANSPARENT("透過", null, null),
    WHITE("白", Color.White, Color.Black),
    BLACK("黒", Color.Black, Color.White);

    companion object {
        val DEFAULT = TRANSPARENT

        fun fromName(value: String?): WidgetBackground = entries.find { it.name == value } ?: DEFAULT
    }
}
