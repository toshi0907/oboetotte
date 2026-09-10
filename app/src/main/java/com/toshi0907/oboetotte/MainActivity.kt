package com.toshi0907.oboetotte

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.ui.theme.OboetotteTheme

class MainActivity : ComponentActivity() {
    private val taskViewModel: TaskViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OboetotteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val tasks by taskViewModel.tasks.collectAsState()
                    TaskScreen(
                        tasks = tasks,
                        onAddTask = taskViewModel::addTask,
                        onToggleDone = taskViewModel::toggleDone,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun TaskScreen(
    tasks: List<Task>,
    onAddTask: (String) -> Unit,
    onToggleDone: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(text = "Oboetotte", style = MaterialTheme.typography.headlineMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("タスクを入力") }
                )
                Button(
                    onClick = {
                        onAddTask(input)
                        input = ""
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("追加")
                }
            }

            LazyColumn {
                items(tasks, key = { it.id }) { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = task.isDone,
                            onCheckedChange = { onToggleDone(task) }
                        )
                        Text(
                            text = task.title,
                            textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                            color = if (task.isDone) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TaskScreenPreview() {
    OboetotteTheme {
        TaskScreen(
            tasks = listOf(
                Task(id = 1, title = "牛乳を買う"),
                Task(id = 2, title = "掃除機をかける", isDone = true)
            ),
            onAddTask = {},
            onToggleDone = {}
        )
    }
}
