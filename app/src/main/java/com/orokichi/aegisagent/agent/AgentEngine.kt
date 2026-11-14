package com.orokichi.aegisagent.agent

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.orokichi.aegisagent.MainActivity
import com.orokichi.aegisagent.accessibility.AegisAccessibilityService
import com.orokichi.aegisagent.api.GroqClient
import com.orokichi.aegisagent.data.SettingsStore
import com.orokichi.aegisagent.model.ActionType
import com.orokichi.aegisagent.model.AgentDecision
import com.orokichi.aegisagent.model.AgentRunState
import com.orokichi.aegisagent.model.AuditLogEntry
import com.orokichi.aegisagent.model.PendingApproval
import com.orokichi.aegisagent.model.RiskLevel
import com.orokichi.aegisagent.model.SafetyMode
import com.orokichi.aegisagent.model.ScreenSnapshot
import com.orokichi.aegisagent.safety.SafetyGuard
import com.orokichi.aegisagent.scheduler.TaskAlarmReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

object AgentEngine {
    const val CHANNEL_ID = "aegis_agent_status"
    private const val NOTIF_ID = 7001

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val groqClient = GroqClient()
    private var currentJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null

    val runState = MutableStateFlow(AgentRunState.IDLE)
    val currentTaskTitle = MutableStateFlow("")
    val statusMessage = MutableStateFlow("待機中")
    val liveLogs = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val pendingApproval = MutableStateFlow<PendingApproval?>(null)

    fun isActive(): Boolean =
        runState.value == AgentRunState.RUNNING || runState.value == AgentRunState.WAITING_APPROVAL

    fun startTask(
        context: Context,
        taskTitle: String,
        taskPrompt: String,
        maxStepsOverride: Int? = null,
        safetyModeOverride: SafetyMode? = null,
        onFinished: ((String) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        val settings = SettingsStore(appCtx)

        if (isActive()) {
            emergencyStop(appCtx, "新しいタスク開始のため前のタスクを停止しました")
        }

        val service = AegisAccessibilityService.instance
        if (service == null) {
            runState.value = AgentRunState.ERROR
            statusMessage.value = "ユーザー補助サービスが有効になっていません。"
            onFinished?.invoke("ユーザー補助未有効")
            return
        }

        if (settings.groqApiKey.isBlank()) {
            runState.value = AgentRunState.ERROR
            statusMessage.value = "Groq APIキーが設定されていません。設定画面から登録してください。"
            onFinished?.invoke("APIキー未設定")
            return
        }

        val effectiveSafetyMode = safetyModeOverride ?: settings.safetyMode
        val prevMode = settings.safetyMode
        if (safetyModeOverride != null) {
            settings.safetyMode = safetyModeOverride
        }

        val maxSteps = maxStepsOverride ?: settings.maxStepsDefault
        val guard = SafetyGuard(settings)

        currentTaskTitle.value = taskTitle
        runState.value = AgentRunState.RUNNING
        statusMessage.value = "自律エージェント起動中..."
        pendingApproval.value = null
        showRunningNotification(appCtx, taskTitle, "実行中（タップまたは停止ボタンで即停止）")

        currentJob = scope.launch {
            val history = mutableListOf<String>()
            var finalStatus = "完了"
            try {
                for (step in 1..maxSteps) {
                    if (!isActive) break

                    statusMessage.value = "ステップ $step/$maxSteps: 画面を解析中..."
                    val snapshot = service.captureSnapshot()

                    val result = groqClient.decideNextAction(
                        apiKey = settings.groqApiKey,
                        model = settings.groqModel,
                        taskPrompt = taskPrompt,
                        snapshot = snapshot,
                        historyLines = history,
                        step = step,
                        maxSteps = maxSteps
                    )

                    if (!isActive) break

                    val decision = result.getOrElse { err ->
                        logStep(appCtx, step, taskTitle, snapshot.packageName, "APIエラー", err.message ?: "", RiskLevel.CAUTION, "ERROR")
                        runState.value = AgentRunState.ERROR
                        statusMessage.value = err.message ?: "Groq通信エラー"
                        finalStatus = "エラー: ${err.message}"
                        return@launch
                    }

                    val targetDesc = decision.targetIndex?.let { idx ->
                        snapshot.elements.firstOrNull { it.index == idx }?.displayLabel()
                    } ?: decision.appPackage ?: decision.scrollDirection ?: "-"

                    val verdict = guard.evaluate(decision, snapshot)

                    if (!verdict.allowed) {
                        if (verdict.requiresApproval) {
                            runState.value = AgentRunState.WAITING_APPROVAL
                            statusMessage.value = "安全確認待ち: ${verdict.reason}"
                            val deferred = CompletableDeferred<Boolean>()
                            approvalDeferred = deferred
                            pendingApproval.value = PendingApproval(
                                step = step,
                                decision = decision,
                                verdict = verdict,
                                targetDescription = targetDesc,
                                packageName = snapshot.packageName
                            )
                            showRunningNotification(appCtx, taskTitle, "安全確認待ち: ${decision.actionType.label}")

                            val approved = deferred.await()
                            pendingApproval.value = null
                            approvalDeferred = null

                            if (!approved || !isActive) {
                                logStep(
                                    appCtx, step, taskTitle, snapshot.packageName,
                                    "${decision.actionType.label}: $targetDesc",
                                    verdict.reason, verdict.riskLevel, "DENIED"
                                )
                                runState.value = AgentRunState.BLOCKED
                                statusMessage.value = "危険操作が拒否されたため停止しました。"
                                finalStatus = "安全停止（ユーザー拒否）"
                                return@launch
                            } else {
                                runState.value = AgentRunState.RUNNING
                                logStep(
                                    appCtx, step, taskTitle, snapshot.packageName,
                                    "${decision.actionType.label}: $targetDesc",
                                    "ユーザー承認により実行", verdict.riskLevel, "APPROVED"
                                )
                            }
                        } else {
                            logStep(
                                appCtx, step, taskTitle, snapshot.packageName,
                                "${decision.actionType.label}: $targetDesc",
                                verdict.reason, verdict.riskLevel, "BLOCKED"
                            )
                            runState.value = AgentRunState.BLOCKED
                            statusMessage.value = verdict.reason
                            finalStatus = "自動ブロック停止"
                            return@launch
                        }
                    }

                    // 実行
                    if (!isActive) break
                    val done = executeDecision(service, decision, snapshot)
                    logStep(
                        appCtx, step, taskTitle, snapshot.packageName,
                        "${decision.actionType.label}: $targetDesc",
                        decision.thought, verdict.riskLevel,
                        if (decision.actionType == ActionType.FINISH) "FINISHED" else "EXECUTED"
                    )
                    history.add("Step $step: ${decision.actionType.name} ($targetDesc) -> ${decision.thought}")

                    if (decision.actionType == ActionType.FINISH) {
                        runState.value = AgentRunState.COMPLETED
                        statusMessage.value = decision.summary.ifBlank { "タスクを完了しました。" }
                        finalStatus = "完了"
                        return@launch
                    }
                    if (decision.actionType == ActionType.ABORT || !done) {
                        runState.value = AgentRunState.ERROR
                        statusMessage.value = decision.summary.ifBlank { "操作を実行できないため中断しました。" }
                        finalStatus = "中断"
                        return@launch
                    }

                    delay(1400L)
                }

                if (isActive && runState.value == AgentRunState.RUNNING) {
                    runState.value = AgentRunState.COMPLETED
                    statusMessage.value = "最大ステップ数（$maxSteps）に達したため安全に停止しました。"
                    finalStatus = "上限到達停止"
                }
            } finally {
                if (safetyModeOverride != null) {
                    settings.safetyMode = prevMode
                }
                cancelNotification(appCtx)
                onFinished?.invoke(finalStatus)
            }
        }
    }

    fun emergencyStop(context: Context, reason: String = "緊急停止ボタンにより即座に停止しました") {
        val appCtx = context.applicationContext
        approvalDeferred?.complete(false)
        approvalDeferred = null
        pendingApproval.value = null
        currentJob?.cancel()
        currentJob = null
        runState.value = AgentRunState.EMERGENCY_STOPPED
        statusMessage.value = reason
        logStep(
            appCtx, 0, currentTaskTitle.value.ifBlank { "システム" },
            "-", "緊急停止", reason, RiskLevel.CRITICAL_BLOCK, "STOPPED"
        )
        cancelNotification(appCtx)
    }

    fun resolveApproval(context: Context, approved: Boolean) {
        approvalDeferred?.complete(approved)
        if (!approved) {
            statusMessage.value = "危険操作を拒否しました。"
        }
    }

    fun loadLogsFromStore(context: Context) {
        liveLogs.value = SettingsStore(context).getAuditLogs()
    }

    private fun executeDecision(
        service: AegisAccessibilityService,
        decision: AgentDecision,
        snapshot: ScreenSnapshot
    ): Boolean {
        return when (decision.actionType) {
            ActionType.CLICK -> decision.targetIndex?.let { service.performClickOnIndex(it) } ?: false
            ActionType.TYPE_TEXT -> {
                val idx = decision.targetIndex ?: return false
                val text = decision.inputText ?: return false
                service.performTypeTextOnIndex(idx, text)
            }
            ActionType.SCROLL -> service.performScroll(decision.scrollDirection ?: "DOWN")
            ActionType.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionType.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            ActionType.OPEN_APP -> decision.appPackage?.let { service.launchAppByPackage(it) } ?: false
            ActionType.WAIT -> true
            ActionType.FINISH -> true
            ActionType.ABORT -> false
        }
    }

    private fun logStep(
        context: Context,
        step: Int,
        taskTitle: String,
        pkg: String,
        actionLabel: String,
        thought: String,
        risk: RiskLevel,
        outcome: String
    ) {
        val entry = AuditLogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            step = step,
            taskTitle = taskTitle,
            packageName = pkg,
            actionLabel = actionLabel,
            thought = thought,
            riskLevel = risk,
            outcome = outcome
        )
        SettingsStore(context).appendAuditLog(entry)
        liveLogs.value = listOf(entry) + liveLogs.value.take(149)
    }

    private fun showRunningNotification(context: Context, title: String, content: String) {
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, TaskAlarmReceiver::class.java).apply {
                action = TaskAlarmReceiver.ACTION_EMERGENCY_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("AegisAgent: $title")
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "緊急停止 STOP", stopIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun cancelNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }
}
