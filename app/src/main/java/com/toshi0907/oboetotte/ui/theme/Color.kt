package com.toshi0907.oboetotte.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/** 期日が当日のタスクの強調表示に使う緑系の色。ダイナミックカラーの影響を受けないよう固定値。 */
val Green80 = Color(0xFF8FD68A)
val Green40 = Color(0xFF2E7D32)

/**
 * 期限切れタスクの強調表示に使う赤系の色(Material3のデフォルトerrorカラー相当)。
 * ダイナミックカラーの影響を受けないよう固定値。ウィジェット(Jetpack Glance)では
 * `MaterialTheme.colorScheme.error`を参照できないため、メイン画面([TaskTreeRow])とは
 * 別にこちらを使う。
 */
val Red80 = Color(0xFFFFB4AB)
val Red40 = Color(0xFFBA1A1A)
