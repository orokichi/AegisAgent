package com.orokichi.aegisagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orokichi.aegisagent.agent.AgentEngine
import com.orokichi.aegisagent.data.SettingsStore
import com.orokichi.aegisagent.model.SafetyMode
import com.orokichi.aegisagent.model.ScheduledTask
import com.orokichi.aegisagent.scheduler.TaskScheduler
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var tasks by remember { mutableStateOf(store.getScheduledTasks()) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun saveAndRefresh(updated: List<ScheduledTask>) {
        store.saveScheduledTasks(updated)
        TaskScheduler.rescheduleAll(context)
        tasks = updated
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("定期タスク実行", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "指定した時間帯・曜日に自律型AIタスクを自動起動します。安全装置により画面ロック中は自動スキップされ、危険操作はブロックされます。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showAddDialog = true },
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("新しい定期タスクを追加")
                    }
                }
            }
        }

        items(tasks, key = { it.id }) { task ->
            val timeRange = String.format(
                "%02d:%02d 〜 %02d:%02d",
                task.hour, task.minute, task.windowEndHour, task.windowEndMinute
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "実行時間帯: $timeRange · ${task.safetyMode.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Switch(
                            checked = task.enabled,
                            onCheckedChange = { checked ->
                                saveAndRefresh(tasks.map {
                                    if (it.id == task.id) it.copy(enabled = checked) else it
                                })
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(task.prompt, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "最終状態: ${task.lastStatus}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                AgentEngine.startTask(
                                    context = context,
                                    taskTitle = "テスト: ${task.name}",
                                    taskPrompt = task.prompt,
                                    maxStepsOverride = task.maxSteps,
                                    safetyModeOverride = task.safetyMode
                                )
                            }
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("今すぐテスト実行")
                        }
                        IconButton(
                            onClick = {
                                TaskScheduler.cancelTask(context, task.id)
                                saveAndRefresh(tasks.filterNot { it.id == task.id })
                            }
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "削除")
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddScheduleDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { newTask ->
                saveAndRefresh(listOf(newTask) + tasks)
                showAddDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddScheduleDialog(
    onDismiss: () -> Unit,
    onAdd: (ScheduledTask) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var startHour by remember { mutableIntStateOf(8) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(9) }
    var strictMode by remember { mutableStateOf(false) }
    var selectedDays by remember {
        mutableStateOf(
            setOf(
                Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
                Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
            )
        )
    }

    val dayLabels = listOf(
        Calendar.MONDAY to "月",
        Calendar.TUESDAY to "火",
        Calendar.WEDNESDAY to "水",
        Calendar.THURSDAY to "木",
        Calendar.FRIDAY to "金",
        Calendar.SATURDAY to "土",
        Calendar.SUNDAY to "日"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定期タスクを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("タスク名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("実行内容（AIへの指示）") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startHour.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { startHour = it.coerceIn(0, 23) } },
                        label = { Text("開始時") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = startMinute.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { startMinute = it.coerceIn(0, 59) } },
                        label = { Text("開始分") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endHour.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { endHour = it.coerceIn(0, 23) } },
                        label = { Text("終了時") },
                        modifier = Modifier.weight(1f)
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayLabels.forEach { (dow, label) ->
                        FilterChip(
                            selected = dow in selectedDays,
                            onClick = {
                                selectedDays = if (dow in selectedDays) selectedDays - dow else selectedDays + dow
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("厳格セーフモードで実行", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = strictMode, onCheckedChange = { strictMode = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && prompt.isNotBlank()) {
                        onAdd(
                            ScheduledTask(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                prompt = prompt.trim(),
                                hour = startHour,
                                minute = startMinute,
                                windowEndHour = endHour,
                                windowEndMinute = startMinute,
                                daysOfWeek = selectedDays,
                                enabled = true,
                                safetyMode = if (strictMode) SafetyMode.STRICT else SafetyMode.UNRESTRICTED
                            )
                        )
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
