package com.orokichi.aegisagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orokichi.aegisagent.agent.AgentEngine
import com.orokichi.aegisagent.data.SettingsStore
import com.orokichi.aegisagent.model.SafetyMode

@Composable
fun SecurityScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }

    var safetyMode by remember { mutableStateOf(store.safetyMode) }
    var blockFinance by remember { mutableStateOf(store.blockFinanceApps) }
    var blockSystem by remember { mutableStateOf(store.blockSystemSettings) }
    var stopOnScreenOff by remember { mutableStateOf(store.stopOnScreenOff) }
    var floatingStop by remember { mutableStateOf(store.showFloatingStopButton) }
    val logs by AgentEngine.liveLogs.collectAsStateWithLifecycle()

    val blockedLogs = logs.filter { it.outcome in setOf("BLOCKED", "DENIED", "STOPPED") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Security, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("セキュリティセーブ装置", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Groq AIの自己評価とローカルの決定論的ルールエンジンの二重チェックで、危険な操作を確実にストップします。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("安全保護レベル", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SafetyMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = safetyMode == mode,
                                onClick = {
                                    safetyMode = mode
                                    store.safetyMode = mode
                                }
                            )
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text(mode.label, style = MaterialTheme.typography.titleSmall)
                                Text(mode.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("セーフティスイッチ", style = MaterialTheme.typography.titleMedium)

                    ToggleRow(
                        title = "電源ボタン（画面オフ）で即タスクキル",
                        subtitle = "電源ボタンを押して画面が消灯すると、実行中のタスクを即座に完全停止します",
                        checked = stopOnScreenOff,
                        onCheckedChange = {
                            stopOnScreenOff = it
                            store.stopOnScreenOff = it
                        }
                    )
                    ToggleRow(
                        title = "フローティング緊急停止ボタン",
                        subtitle = "自律動作中、画面最前面に赤いSTOPボタンを表示",
                        checked = floatingStop,
                        onCheckedChange = {
                            floatingStop = it
                            store.showFloatingStopButton = it
                        }
                    )
                    ToggleRow(
                        title = "金融・決済アプリを自動ブロック（制限ありモード時）",
                        subtitle = "制限なしモードでは無効。厳格/標準モード時にブロックします",
                        checked = blockFinance,
                        onCheckedChange = {
                            blockFinance = it
                            store.blockFinanceApps = it
                        }
                    )
                    ToggleRow(
                        title = "システム設定・権限・削除を保護（制限ありモード時）",
                        subtitle = "制限なしモードでは無効。厳格/標準モード時にブロックします",
                        checked = blockSystem,
                        onCheckedChange = {
                            blockSystem = it
                            store.blockSystemSettings = it
                        }
                    )
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ブロック・停止履歴 (${blockedLogs.size})", style = MaterialTheme.typography.titleMedium)
                if (logs.isNotEmpty()) {
                    TextButton(onClick = {
                        store.clearAuditLogs()
                        AgentEngine.loadLogsFromStore(context)
                    }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("履歴クリア")
                    }
                }
            }
        }

        if (blockedLogs.isEmpty()) {
            item {
                Text(
                    "ブロックまたは緊急停止された履歴はありません。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp)
                )
            }
        } else {
            items(blockedLogs.take(20), key = { it.id }) { log ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("${log.outcome} · ${log.actionLabel}", style = MaterialTheme.typography.titleSmall)
                        Text(log.thought, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
