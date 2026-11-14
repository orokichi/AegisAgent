package com.orokichi.aegisagent.model

import android.graphics.Rect

enum class RiskLevel(val label: String, val severity: Int) {
    SAFE("安全", 0),
    CAUTION("注意", 1),
    DANGER("危険（要確認）", 2),
    CRITICAL_BLOCK("重大危険（強制停止）", 3)
}

enum class SafetyMode(val label: String, val description: String) {
    UNRESTRICTED("制限なし（なんでもあり）", "操作のブロックや事前確認を一切行わず、電源ボタン（画面オフ）での緊急キルのみ有効にします"),
    STRICT("厳格セーフモード", "決済・送信・設定変更・削除などは即ストップし、注意レベルでも確認を求めます"),
    STANDARD("標準セーフモード", "重大な危険操作は完全ブロック、送信・購入・削除などは実行前に承認を求めます"),
    OBSERVE_ONLY("観測専用（操作禁止）", "画面の読み取りとAI分析のみ行い、実際のタップや文字入力は一切行いません")
}

enum class AgentRunState(val label: String) {
    IDLE("待機中"),
    RUNNING("自律実行中"),
    WAITING_APPROVAL("安全承認待ち"),
    EMERGENCY_STOPPED("緊急停止済み"),
    COMPLETED("タスク完了"),
    BLOCKED("安全装置により停止"),
    ERROR("エラー停止")
}

enum class ActionType(val label: String) {
    CLICK("タップ"),
    TYPE_TEXT("文字入力"),
    SCROLL("スクロール"),
    BACK("戻る"),
    HOME("ホーム"),
    OPEN_APP("アプリ起動"),
    WAIT("待機"),
    FINISH("完了"),
    ABORT("中断")
}

data class UiElementNode(
    val index: Int,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isPassword: Boolean,
    val bounds: Rect
) {
    fun displayLabel(): String {
        val parts = listOfNotNull(
            text.takeIf { it.isNotBlank() },
            contentDesc.takeIf { it.isNotBlank() && it != text },
            viewId.substringAfterLast('/').takeIf { it.isNotBlank() }
        )
        return parts.joinToString(" | ").ifBlank { className.substringAfterLast('.') }
    }
}

data class ScreenSnapshot(
    val packageName: String,
    val elements: List<UiElementNode>,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toPromptText(maxElements: Int = 70): String {
        val sb = StringBuilder()
        sb.appendLine("Package: $packageName")
        sb.appendLine("Interactive & Visible Elements:")
        elements.take(maxElements).forEach { el ->
            val flags = buildList {
                if (el.isClickable) add("clickable")
                if (el.isEditable) add("editable")
                if (el.isPassword) add("PASSWORD")
            }.joinToString(",")
            sb.appendLine("[${el.index}] (${flags.ifBlank { "text" }}) ${el.displayLabel()}")
        }
        return sb.toString()
    }
}

data class AgentDecision(
    val thought: String,
    val actionType: ActionType,
    val targetIndex: Int? = null,
    val inputText: String? = null,
    val appPackage: String? = null,
    val scrollDirection: String? = null,
    val aiRiskLevel: RiskLevel = RiskLevel.SAFE,
    val aiRiskReason: String = "",
    val summary: String = ""
)

data class SafetyVerdict(
    val riskLevel: RiskLevel,
    val allowed: Boolean,
    val requiresApproval: Boolean,
    val reason: String,
    val matchedRules: List<String> = emptyList()
)

data class AuditLogEntry(
    val id: String,
    val timestamp: Long,
    val step: Int,
    val taskTitle: String,
    val packageName: String,
    val actionLabel: String,
    val thought: String,
    val riskLevel: RiskLevel,
    val outcome: String // EXECUTED, BLOCKED, APPROVED, DENIED, STOPPED, INFO
)

data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val windowEndHour: Int,
    val windowEndMinute: Int,
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7), // Calendar.SUNDAY..SATURDAY
    val enabled: Boolean = true,
    val maxSteps: Int = 12,
    val safetyMode: SafetyMode = SafetyMode.STRICT,
    val lastRunAt: Long = 0L,
    val lastStatus: String = "未実行"
)

data class PendingApproval(
    val step: Int,
    val decision: AgentDecision,
    val verdict: SafetyVerdict,
    val targetDescription: String,
    val packageName: String
)

object GroqModels {
    val presets = listOf(
        "llama-3.3-70b-versatile" to "Llama 3.3 70B Versatile（推奨・高精度）",
        "meta-llama/llama-4-scout-17b-16e-instruct" to "Llama 4 Scout 17B（高速・新世代）",
        "llama-3.1-8b-instant" to "Llama 3.1 8B Instant（超高速）",
        "qwen-qwq-32b" to "Qwen QwQ 32B（推論特化）"
    )
}
