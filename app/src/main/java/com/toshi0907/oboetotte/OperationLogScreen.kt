package com.toshi0907.oboetotte

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toshi0907.oboetotte.data.OperationLog
import com.toshi0907.oboetotte.oplog.OperationLogger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

/**
 * 設定画面の「操作ログ」ボタンから遷移する全画面。タスクの追加・削除・完了や設定変更などの
 * 操作ログ([OperationLog])を新しい順に一覧表示する。記録は
 * [com.toshi0907.oboetotte.oplog.OperationLogger]が各操作の実行時に行い、保存期間を過ぎた
 * ものは自動削除される。
 */
@Composable
fun OperationLogScreen(
    logs: List<OperationLog>,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← 戻る")
                }
                Text(text = "操作ログ", style = MaterialTheme.typography.headlineMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "直近${OperationLogger.RETENTION_MONTHS}ヶ月分の操作を新しい順に表示します(${logs.size}件)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showClearConfirm = true }, enabled = logs.isNotEmpty()) {
                    Text("ログを消去")
                }
            }
            if (logs.isEmpty()) {
                Text(
                    text = "まだ操作ログはありません",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs, key = { it.id }) { log ->
                        OperationLogRow(log)
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("操作ログを消去") },
            text = { Text("すべての操作ログを消去します。よろしいですか?") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClear()
                }) {
                    Text("消去")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

@Composable
private fun OperationLogRow(log: OperationLog) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                LOG_TIME_FORMAT.format(Instant.ofEpochMilli(log.timestamp).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                log.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(log.action, style = MaterialTheme.typography.bodyMedium)
        if (log.detail.isNotEmpty()) {
            Text(
                log.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
