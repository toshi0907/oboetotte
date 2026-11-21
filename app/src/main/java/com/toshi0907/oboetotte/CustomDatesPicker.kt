package com.toshi0907.oboetotte

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

private val PICKER_DAY_OF_WEEK_LABELS = listOf("日", "月", "火", "水", "木", "金", "土")

/** 日付を「12/1(月)」の形式で表示する。 */
internal fun formatShortDateWithDay(date: LocalDate): String =
    "${date.monthValue}/${date.dayOfMonth}(${PICKER_DAY_OF_WEEK_LABELS[date.dayOfWeek.value % 7]})"

/**
 * 繰り返し「不定期」([RepeatRule.CUSTOM_DATES])の日付を複数選択する月カレンダー。Material3の
 * DatePickerは単一日・期間の選択にしか対応しないため自作している。[today]より前の日付は選択できず、
 * [RepeatRule.MAX_CUSTOM_DATES]件に達したら未選択の日付は追加できない(選択済みの解除は可能)。
 * [keptPastDate](編集前の期限の日付)だけは過去でも選択・解除できる(期限切れの今回分を残すため)。
 */
@Composable
fun CustomDatesPicker(
    selected: Set<LocalDate>,
    today: LocalDate,
    onSelectedChange: (Set<LocalDate>) -> Unit,
    modifier: Modifier = Modifier,
    keptPastDate: LocalDate? = null
) {
    val earliestMonth = YearMonth.from(keptPastDate?.takeIf { it < today } ?: today)
    var displayedMonth by remember {
        mutableStateOf(YearMonth.from(selected.minOrNull() ?: today).coerceAtLeast(earliestMonth))
    }
    val limitReached = selected.size >= RepeatRule.MAX_CUSTOM_DATES

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { displayedMonth = displayedMonth.minusMonths(1) },
                // 過去の日付は選択できないため、今月(期限切れの今回分があればその月)より前には戻らない。
                enabled = displayedMonth > earliestMonth
            ) { Text("‹") }
            Text(
                text = "${displayedMonth.year}年${displayedMonth.monthValue}月",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { displayedMonth = displayedMonth.plusMonths(1) }) { Text("›") }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            PICKER_DAY_OF_WEEK_LABELS.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        val firstDay = displayedMonth.atDay(1)
        // 日曜始まりのグリッドにするため、1日の曜日(日曜=0)ぶん前から並べる。
        val leadingBlanks = firstDay.dayOfWeek.value % 7
        val cellCount = leadingBlanks + displayedMonth.lengthOfMonth()
        val weekCount = (cellCount + 6) / 7
        repeat(weekCount) { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { dayIndex ->
                    val dayOfMonth = week * 7 + dayIndex - leadingBlanks + 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayOfMonth in 1..displayedMonth.lengthOfMonth()) {
                            val date = displayedMonth.atDay(dayOfMonth)
                            val isSelected = date in selected
                            val enabled = (date >= today || date == keptPastDate) && (isSelected || !limitReached)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else Color.Transparent
                                    )
                                    .clickable(enabled = enabled) {
                                        onSelectedChange(if (isSelected) selected - date else selected + date)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        date == today -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = "選択中: ${selected.size}件(最大${RepeatRule.MAX_CUSTOM_DATES}件)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (selected.isNotEmpty()) {
            // 選択済みの日付。タップで解除する。
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(selected.sorted(), key = { it.toString() }) { date ->
                    AssistChip(
                        onClick = { onSelectedChange(selected - date) },
                        label = { Text("${formatShortDateWithDay(date)} ×") }
                    )
                }
            }
        }
    }
}
