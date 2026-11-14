package com.orokichi.aegisagent.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orokichi.aegisagent.accessibility.AegisAccessibilityService
import com.orokichi.aegisagent.agent.AgentEngine
import com.orokichi.aegisagent.data.SettingsStore
import com.orokichi.aegisagent.model.AgentRunState
import com.orokichi.aegisagent.model.RiskLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val isConnected by AegisAccessibilityService.isConnected.collectAsStateWithLifecycle()
    val runState by AgentEngine.runState.collectAsStateWithLifecycle()
    val statusMsg by AgentEngine.statusMessage.collectAsStateWithLifecycle()
    val logs by AgentEngine.liveLogs.collectAsStateWithLifecycle()
    val pendingApproval by AgentEngine.pendingApproval.collectAsStateWithLifecycle()

    var taskPrompt by remember { mutableStateOf("") }
    val hasApiKey = settings.groqApiKey.isNotBlank()
    val isActive = runState == AgentRunState.RUNNING || runState == AgentRunState.WAITING_APPROVAL

    val quickPrompts = listOf(
        "天気アプリを開いて今日の天気を確認して",
        "Chromeを開いてGoogleで最新ニュースを検索して",
        "設定を開かずに今の画面の内容を要約して"
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 緊急停止・ステータスヒーローカード
        item {
            val containerColor = when (runState) {
                AgentRunState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                AgentRunState.WAITING_APPROVAL -> MaterialTheme.colorScheme.tertiaryContainer
                AgentRunState.EMERGENCY_STOPPED, AgentRunState.BLOCKED, AgentRunState.ERROR ->
                    MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = containerColor),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isActive) Icons.Rounded.Shield else Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = runState.label,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "安全モード: ${settings.safetyMode.label}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = statusMsg,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))

                    // いつでも押せる緊急停止ボタン
                    Button(
                        onClick = {
                            AgentEngine.emergencyStop(context, "ダッシュボードの緊急停止ボタンが押されました")
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Icon(Icons.Rounded.StopCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isActive) "今すぐ緊急停止 (STOP)" else "緊急停止システム（待機中）",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. 危険操作の承認カード（待機中のみ）
        if (pendingApproval != null) {
            val approval = pendingApproval!!
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "セキュリティセーブ：承認が必要です",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("操作: ${approval.decision.actionType.label}", style = MaterialTheme.typography.bodyMedium)
                        Text("対象: ${approval.targetDescription}", style = MaterialTheme.typography.bodyMedium)
                        Text("アプリ: ${approval.packageName}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text(approval.verdict.reason, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { AgentEngine.resolveApproval(context, false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("停止・拒否")
                            }
                            Button(
                                onClick = { AgentEngine.resolveApproval(context, true) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("1回だけ許可")
                            }
                        }
                    }
                }
            }
        }

        // 3. セットアップガイド（未接続・未設定時）
        if (!isConnected || !hasApiKey) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("初期セットアップ", style = MaterialTheme.typography.titleMedium)
                        if (!isConnected) {
                            FilledTonalButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.AccessibilityNew, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("ユーザー補助で「AegisAgent」をONにする")
                            }
                        }
                        if (!hasApiKey) {
                            FilledTonalButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Key, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Groq Console APIキーを設定する")
                            }
                        }
                    }
                }
            }
        }

        // 4. 自律タスク即時実行パネル
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("自律タスクを実行", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = taskPrompt,
                        onValueChange = { taskPrompt = it },
                        label = { Text("スマホにやらせたい操作を入力") },
                        placeholder = { Text("例: 今日の天気を調べて教えて") },
                        minLines = 2,
                        maxLines = 4,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        quickPrompts.forEach { preset ->
                            AssistChip(
                                onClick = { taskPrompt = preset },
                                label = { Text(preset, maxLines = 1) }
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (taskPrompt.isNotBlank()) {
                                AgentEngine.startTask(
                                    context = context,
                                    taskTitle = taskPrompt.take(24),
                                    taskPrompt = taskPrompt
                                )
                            }
                        },
                        enabled = taskPrompt.isNotBlank() && !isActive,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("安全装置付きで自律実行を開始")
                    }
                }
            }
        }

        // 5. リアルタイム実行ログ
        item {
            Text(
                text = "実行・セキュリティログ (${logs.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (logs.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "まだ実行ログはありません。タスクを実行するとAIの思考と安全装置の判定がここに記録されます。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(logs.take(25), key = { it.id }) { log ->
                val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.JAPAN) }
                val isBlocked = log.outcome in setOf("BLOCKED", "DENIED", "STOPPED")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isBlocked) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${if (log.step > 0) "Step ${log.step} · " else ""}${log.outcome} (${log.riskLevel.label})",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (log.riskLevel >= RiskLevel.DANGER) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                text = fmt.format(Date(log.timestamp)),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(log.actionLabel, style = MaterialTheme.typography.titleSmall)
                        if (log.thought.isNotBlank()) {
                            Text(log.thought, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
