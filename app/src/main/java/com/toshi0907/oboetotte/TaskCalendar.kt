package com.toshi0907.oboetotte

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.ui.theme.Green40
import com.toshi0907.oboetotte.ui.theme.Green80
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * カレンダーの1日分に表示する1件。[isProjected]がtrueのものは、繰り返しタスクの将来の予定を
 * 表示用に計算しただけの仮想的なもので、DBには存在しない([task]は元になった現在未完了のタスク)。
 */
data class CalendarEntry(val task: Task, val dueAt: Long, val isProjected: Boolean)

/** 繰り返しの将来の予定を計算する際の、1タスクあたりの反復回数の上限(無限ループ防止の保険)。 */
private const val MAX_PROJECTION_STEPS = 10_000

/** 月を移動するのに必要な、左右スワイプの最小移動量。 */
private val SWIPE_THRESHOLD = 48.dp

private val SUPPORTED_REPEAT_RULES = setOf(
    RepeatRule.DAILY,
    RepeatRule.WEEKLY,
    RepeatRule.WEEKLY_DAYS,
    RepeatRule.MONTHLY
)

/**
 * [tasks]のうち期限が[rangeStart]〜[rangeEndInclusive]に入るものと、繰り返しタスクの将来の予定
 * (今日〜[today]の1年後まで)を日付ごとにまとめる。将来の予定は、実際に完了時に次回分を生成する
 * 処理と同じ[RepeatRule.nextDueAt]で現在の期限から順に計算するため、表示される日付は実際に
 * 生成される日付と一致する。[tasks]は呼び出し側で絞り込み済み(未完了・期限あり等)であること。
 */
fun buildCalendarEntries(
    tasks: List<Task>,
    rangeStart: LocalDate,
    rangeEndInclusive: LocalDate,
    today: LocalDate,
    zone: ZoneId
): Map<LocalDate, List<CalendarEntry>> {
    val startMillis = rangeStart.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMillis = rangeEndInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val projectionFrom = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val projectionUntil = today.plusYears(1).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val limit = minOf(endMillis, projectionUntil)

    val entries = mutableListOf<CalendarEntry>()
    for (task in tasks) {
        val dueAt = task.dueAt ?: continue
        if (dueAt in startMillis until endMillis) entries += CalendarEntry(task, dueAt, isProjected = false)

        val rule = task.repeatRule ?: continue
        if (rule !in SUPPORTED_REPEAT_RULES) continue
        val daysOfWeek = RepeatRule.parseDaysOfWeek(task.repeatDaysOfWeek)
        if (rule == RepeatRule.WEEKLY_DAYS && daysOfWeek.isEmpty()) continue

        var current = dueAt
        var steps = 0
        while (steps++ < MAX_PROJECTION_STEPS) {
            val next = RepeatRule.nextDueAt(current, rule, daysOfWeek)
            if (next <= current || next >= limit) break
            current = next
            // 期限切れのまま残っている繰り返しタスクの場合、過去の日付にも次回以降の分が並んでしまうため、
            // 将来の予定として表示するのは今日以降の分だけにする。
            if (next >= startMillis && next >= projectionFrom) {
                entries += CalendarEntry(task, next, isProjected = true)
            }
        }
    }
    return entries
        .sortedBy { it.dueAt }
        .groupBy { Instant.ofEpochMilli(it.dueAt).atZone(zone).toLocalDate() }
}

private val DAY_OF_WEEK_LABELS = listOf("日", "月", "火", "水", "木", "金", "土")

private fun LocalDate.dayOfWeekLabel(): String = DAY_OF_WEEK_LABELS[dayOfWeek.value % 7]

/**
 * マス内の文字のスタイル。fontSizeだけを指定するとテーマ既定の大きなlineHeight(約24sp)が残り、
 * 小さいマスの中で文字の下半分が切れてしまうため、lineHeightもfontSizeに合わせて中央寄せにする。
 */
private val CELL_ENTRY_FONT_SIZE = 10.sp

private val CellEntryTextStyle = TextStyle(
    fontSize = CELL_ENTRY_FONT_SIZE,
    lineHeight = CELL_ENTRY_FONT_SIZE,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )
)

private val CellDateTextStyle = CellEntryTextStyle.copy(fontSize = 11.sp, lineHeight = 11.sp)

/**
 * メイン画面のカレンダー表示。[tasks]には全タスクを渡し、表示対象(未完了・期限ありの親タスクで
 * [selectedListId]のリスト絞り込みに合致するもの)はここで絞り込む。日付をタップすると、その日の
 * タスク一覧をボトムシートで表示し、各行のタップで[onViewTask](確認ダイアログ)を呼ぶ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCalendar(
    tasks: List<Task>,
    selectedListId: Long?,
    nowMillis: Long,
    onViewTask: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    var displayedMonth by rememberSaveable { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }

    val firstOfMonth = displayedMonth.atDay(1)
    // 日曜始まり(DayOfWeek.valueは月曜=1〜日曜=7なので、7で割った余りが日曜からのオフセット)。
    val leadingDays = firstOfMonth.dayOfWeek.value % 7
    val weekCount = (leadingDays + displayedMonth.lengthOfMonth() + 6) / 7
    val gridStart = firstOfMonth.minusDays(leadingDays.toLong())
    val gridEnd = gridStart.plusDays(weekCount * 7L - 1)

    val candidates = remember(tasks, selectedListId) {
        tasks.filter {
            it.parentTaskId == null && !it.isDone && it.dueAt != null && it.matchesListFilter(selectedListId)
        }
    }
    val entriesByDate = remember(candidates, gridStart, gridEnd, today) {
        buildCalendarEntries(candidates, gridStart, gridEnd, today, zone)
    }

    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    var dragTotal by remember { mutableFloatStateOf(0f) }

    // 左右スワイプで月を移動する(左へスワイプで翌月、右へスワイプで前月)。ドラッグがタッチスロップを
    // 超えた時点でマスのタップはキャンセルされるため、スワイプで日付のボトムシートが開くことはない。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(swipeThresholdPx) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onDragEnd = {
                        when {
                            dragTotal <= -swipeThresholdPx -> displayedMonth = displayedMonth.plusMonths(1)
                            dragTotal >= swipeThresholdPx -> displayedMonth = displayedMonth.minusMonths(1)
                        }
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragTotal += dragAmount
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { displayedMonth = displayedMonth.minusMonths(1) }) { Text("‹") }
            Text(
                text = "${displayedMonth.year}年${displayedMonth.monthValue}月",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { displayedMonth = displayedMonth.plusMonths(1) }) { Text("›") }
            TextButton(
                onClick = { displayedMonth = YearMonth.from(today) },
                enabled = displayedMonth != YearMonth.from(today)
            ) { Text("今月") }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            DAY_OF_WEEK_LABELS.forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = dayOfWeekColor(index),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            repeat(weekCount) { week ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    repeat(7) { dayIndex ->
                        val date = gridStart.plusDays(week * 7L + dayIndex)
                        CalendarDayCell(
                            date = date,
                            inMonth = YearMonth.from(date) == displayedMonth,
                            isToday = date == today,
                            entries = entriesByDate[date].orEmpty(),
                            nowMillis = nowMillis,
                            onClick = { selectedDate = date },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }

    selectedDate?.let { date ->
        // 前後の月にはみ出した日付もタップできるため、表示中の範囲外の日付でも正しく一覧を出せるよう
        // その日だけを対象に計算し直す。
        val dayEntries = remember(candidates, date, today) {
            buildCalendarEntries(candidates, date, date, today, zone)[date].orEmpty()
        }
        ModalBottomSheet(onDismissRequest = { selectedDate = null }) {
            CalendarDaySheetContent(
                date = date,
                entries = dayEntries,
                nowMillis = nowMillis,
                onViewTask = onViewTask
            )
        }
    }
}

@Composable
private fun dayOfWeekColor(sundayBasedIndex: Int): Color = when (sundayBasedIndex) {
    0 -> MaterialTheme.colorScheme.error
    6 -> if (isSystemInDarkTheme()) Color(0xFF90CAF9) else Color(0xFF1565C0)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * カレンダーの1マス。表示できる件数は固定せず、マスの実際の高さと1件あたりの高さ(文字サイズの
 * 端末設定も反映するためspからdpへ換算)から計算し、入りきらない場合は最後の1行を「+N」にする。
 */
@Composable
private fun CalendarDayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    entries: List<CalendarEntry>,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .clipToBounds()
            .clickable(onClick = onClick)
            .padding(1.dp)
    ) {
        val density = LocalDensity.current
        val dateLabelHeight = with(density) { 11.sp.toDp() } + 6.dp
        val lineHeight = with(density) { CELL_ENTRY_FONT_SIZE.toDp() } + 5.dp
        val maxLines = ((maxHeight - dateLabelHeight) / lineHeight).toInt().coerceAtLeast(0)
        val shownCount = if (entries.size > maxLines) (maxLines - 1).coerceAtLeast(0) else entries.size
        val hiddenCount = entries.size - shownCount

        Column(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (inMonth) 1f else 0.4f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dateLabelHeight),
                contentAlignment = Alignment.Center
            ) {
                val labelModifier = if (isToday) {
                    Modifier
                        .size(dateLabelHeight - 2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                }
                Box(modifier = labelModifier, contentAlignment = Alignment.Center) {
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = CellDateTextStyle,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            entries.take(shownCount).forEach { entry ->
                CalendarEntryLabel(
                    entry = entry,
                    nowMillis = nowMillis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(lineHeight)
                        .padding(vertical = 1.dp)
                )
            }
            if (hiddenCount > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(lineHeight),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "+$hiddenCount",
                        style = CellEntryTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
}

/** カレンダーのマス内・ボトムシートの表示に使う、1件分の色。 */
private data class EntryColors(val container: Color, val content: Color)

@Composable
private fun entryColors(entry: CalendarEntry, nowMillis: Long): EntryColors {
    val scheme = MaterialTheme.colorScheme
    val green = if (isSystemInDarkTheme()) Green80 else Green40
    return when {
        entry.isProjected -> EntryColors(Color.Transparent, scheme.primary)
        entry.task.isOverdue(nowMillis) -> EntryColors(scheme.errorContainer, scheme.onErrorContainer)
        entry.task.isDueToday(nowMillis) -> EntryColors(green.copy(alpha = 0.2f), green)
        else -> EntryColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
}

@Composable
private fun CalendarEntryLabel(entry: CalendarEntry, nowMillis: Long, modifier: Modifier = Modifier) {
    val colors = entryColors(entry, nowMillis)
    val shape = RoundedCornerShape(2.dp)
    // 将来の予定は背景なし(透明)で、文字色と「↻」で区別する。
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.container),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = if (entry.isProjected) "↻${entry.task.title}" else entry.task.title,
            style = CellEntryTextStyle,
            color = colors.content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = 1.dp)
        )
    }
}

@Composable
private fun CalendarDaySheetContent(
    date: LocalDate,
    entries: List<CalendarEntry>,
    nowMillis: Long,
    onViewTask: (Task) -> Unit
) {
    val zone = ZoneId.systemDefault()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "${date.monthValue}月${date.dayOfMonth}日(${date.dayOfWeekLabel()})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (entries.isEmpty()) {
            Text(
                text = "タスクはありません",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        LazyColumn {
            items(entries, key = { "${it.task.id}-${it.dueAt}" }) { entry ->
                val colors = entryColors(entry, nowMillis)
                val time = Instant.ofEpochMilli(entry.dueAt).atZone(zone)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onViewTask(entry.task) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "%02d:%02d".format(time.hour, time.minute),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp)
                    )
                    Text(
                        text = entry.task.title,
                        color = when {
                            entry.isProjected -> colors.content
                            entry.task.isOverdue(nowMillis) -> MaterialTheme.colorScheme.error
                            entry.task.isDueToday(nowMillis) -> colors.content
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (entry.isProjected) {
                        Text(
                            text = "↻ 予定",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.content,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
