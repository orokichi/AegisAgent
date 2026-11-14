package com.orokichi.aegisagent.safety

import com.orokichi.aegisagent.data.SettingsStore
import com.orokichi.aegisagent.model.ActionType
import com.orokichi.aegisagent.model.AgentDecision
import com.orokichi.aegisagent.model.RiskLevel
import com.orokichi.aegisagent.model.SafetyMode
import com.orokichi.aegisagent.model.SafetyVerdict
import com.orokichi.aegisagent.model.ScreenSnapshot

class SafetyGuard(private val settings: SettingsStore) {

    private val financePackageKeywords = listOf(
        "paypay", "rakuten.pay", "merpay", "dpayment", "aupay", "linepay",
        "wallet", "bank", "bk.", "mufg", "smbc", "mizuho", "sbisec", "rakuten-sec",
        "paypal", "coincheck", "bitflyer", "binance", "metamask", "suica", "pasmo", "icoca"
    )

    private val criticalSystemPackages = listOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"
    )

    private val criticalTextKeywords = listOf(
        "初期化", "ファクトリーリセット", "factory reset", "全てのデータを消去",
        "アンインストール", "uninstall", "ユーザー補助", "accessibility",
        "デバイス管理", "device admin", "管理者権限", "送金する", "振込を実行",
        "暗証番号", "セキュリティコード", "cvv", "cvc", "クレジットカード番号"
    )

    private val dangerActionKeywords = listOf(
        "購入", "注文を確定", "注文する", "決済", "支払う", "お支払い", "課金",
        "送信", "送信する", "返信する", "投稿", "ツイート", "ポスト",
        "削除", "完全に削除", "ゴミ箱", "消去", "退会", "解約", "申し込む", "登録を解除",
        "許可", "同意して続行", "承認", "ブロック", "通報",
        "purchase", "buy now", "checkout", "pay now", "place order", "confirm order",
        "send", "submit", "post", "delete", "remove", "erase", "transfer", "unsubscribe"
    )

    private val cautionActionKeywords = listOf(
        "設定", "編集", "変更", "保存", "ログアウト", "サインアウト", "ダウンロード", "インストール",
        "settings", "edit", "save", "logout", "sign out", "download", "install", "share", "共有"
    )

    fun evaluate(decision: AgentDecision, snapshot: ScreenSnapshot): SafetyVerdict {
        val mode = settings.safetyMode
        val pkg = snapshot.packageName.lowercase()
        val matched = mutableListOf<String>()

        // 1. 観測専用モード: 画面操作を一切禁止
        if (mode == SafetyMode.OBSERVE_ONLY &&
            decision.actionType in setOf(
                ActionType.CLICK,
                ActionType.TYPE_TEXT,
                ActionType.SCROLL,
                ActionType.OPEN_APP
            )
        ) {
            return SafetyVerdict(
                riskLevel = RiskLevel.CRITICAL_BLOCK,
                allowed = false,
                requiresApproval = false,
                reason = "観測専用モードが有効なため、画面操作（${decision.actionType.label}）を停止しました。",
                matchedRules = listOf("OBSERVE_ONLY_MODE")
            )
        }

        // 2. 完了・中断・待機・戻るは安全扱い（ただしAIがDANGER以上と評価した場合を除く）
        if (decision.actionType in setOf(ActionType.FINISH, ActionType.ABORT, ActionType.WAIT)) {
            return SafetyVerdict(
                riskLevel = RiskLevel.SAFE,
                allowed = true,
                requiresApproval = false,
                reason = "制御アクション（${decision.actionType.label}）のため安全です。"
            )
        }

        var ruleRisk = RiskLevel.SAFE

        // 3. 金融・決済アプリ保護
        if (settings.blockFinanceApps && financePackageKeywords.any { pkg.contains(it) }) {
            ruleRisk = RiskLevel.CRITICAL_BLOCK
            matched += "FINANCE_APP_BLOCK($pkg)"
        }

        // 4. パッケージインストーラー・権限コントローラー保護
        if (settings.blockSystemSettings && criticalSystemPackages.any { pkg.contains(it) }) {
            ruleRisk = RiskLevel.CRITICAL_BLOCK
            matched += "SYSTEM_CRITICAL_PKG($pkg)"
        }

        // 5. Android設定アプリ保護
        if (settings.blockSystemSettings && pkg == "com.android.settings") {
            if (decision.actionType in setOf(ActionType.CLICK, ActionType.TYPE_TEXT)) {
                if (ruleRisk.severity < RiskLevel.DANGER.severity) {
                    ruleRisk = RiskLevel.DANGER
                }
                matched += "ANDROID_SETTINGS_GUARD"
            }
        }

        // 6. ターゲット要素の解析
        val targetNode = decision.targetIndex?.let { idx ->
            snapshot.elements.firstOrNull { it.index == idx }
        }
        val targetText = buildString {
            if (targetNode != null) {
                append(targetNode.text).append(" ")
                append(targetNode.contentDesc).append(" ")
                append(targetNode.viewId).append(" ")
            }
            append(decision.inputText ?: "").append(" ")
            append(decision.appPackage ?: "")
        }.lowercase()

        if (targetNode?.isPassword == true) {
            ruleRisk = RiskLevel.CRITICAL_BLOCK
            matched += "PASSWORD_FIELD_BLOCK"
        }

        if (criticalTextKeywords.any { targetText.contains(it.lowercase()) }) {
            ruleRisk = RiskLevel.CRITICAL_BLOCK
            matched += "CRITICAL_KEYWORD_MATCH"
        }

        if (ruleRisk.severity < RiskLevel.DANGER.severity &&
            dangerActionKeywords.any { targetText.contains(it.lowercase()) }
        ) {
            ruleRisk = RiskLevel.DANGER
            matched += "DANGER_KEYWORD_MATCH"
        }

        if (ruleRisk.severity < RiskLevel.CAUTION.severity &&
            cautionActionKeywords.any { targetText.contains(it.lowercase()) }
        ) {
            ruleRisk = RiskLevel.CAUTION
            matched += "CAUTION_KEYWORD_MATCH"
        }

        // 7. OPEN_APPのターゲット先が金融アプリや設定の場合もチェック
        if (decision.actionType == ActionType.OPEN_APP) {
            val openPkg = (decision.appPackage ?: "").lowercase()
            if (settings.blockFinanceApps && financePackageKeywords.any { openPkg.contains(it) }) {
                ruleRisk = RiskLevel.CRITICAL_BLOCK
                matched += "OPEN_FINANCE_APP_BLOCK($openPkg)"
            }
        }

        // 8. ルール判定とAI自身のリスク評価の厳しい方を採用
        val effectiveRisk = if (decision.aiRiskLevel.severity > ruleRisk.severity) {
            matched += "AI_RISK_ESCALATION(${decision.aiRiskLevel.name})"
            decision.aiRiskLevel
        } else {
            ruleRisk
        }

        val detailReason = buildString {
            if (matched.isNotEmpty()) {
                append("検知ルール: ${matched.joinToString(", ")}")
            }
            if (decision.aiRiskReason.isNotBlank()) {
                if (isNotEmpty()) append(" / ")
                append("AI評価: ${decision.aiRiskReason}")
            }
            if (isEmpty()) append("通常の操作範囲内と判定されました。")
        }

        return when (effectiveRisk) {
            RiskLevel.CRITICAL_BLOCK -> SafetyVerdict(
                riskLevel = RiskLevel.CRITICAL_BLOCK,
                allowed = false,
                requiresApproval = false,
                reason = "重大な危険操作を検知したため自動停止しました（$detailReason）",
                matchedRules = matched
            )
            RiskLevel.DANGER -> {
                if (mode == SafetyMode.STRICT) {
                    SafetyVerdict(
                        riskLevel = RiskLevel.DANGER,
                        allowed = false,
                        requiresApproval = true,
                        reason = "厳格モードにより危険操作を一時停止しました。続行するには承認が必要です（$detailReason）",
                        matchedRules = matched
                    )
                } else {
                    SafetyVerdict(
                        riskLevel = RiskLevel.DANGER,
                        allowed = false,
                        requiresApproval = true,
                        reason = "外部送信・購入・削除・重要変更の可能性があるため実行前に確認が必要です（$detailReason）",
                        matchedRules = matched
                    )
                }
            }
            RiskLevel.CAUTION -> {
                if (mode == SafetyMode.STRICT) {
                    SafetyVerdict(
                        riskLevel = RiskLevel.CAUTION,
                        allowed = false,
                        requiresApproval = true,
                        reason = "厳格モードにより注意レベルの操作（設定変更・保存等）に確認を求めています（$detailReason）",
                        matchedRules = matched
                    )
                } else {
                    SafetyVerdict(
                        riskLevel = RiskLevel.CAUTION,
                        allowed = true,
                        requiresApproval = false,
                        reason = detailReason,
                        matchedRules = matched
                    )
                }
            }
            RiskLevel.SAFE -> SafetyVerdict(
                riskLevel = RiskLevel.SAFE,
                allowed = true,
                requiresApproval = false,
                reason = detailReason,
                matchedRules = matched
            )
        }
    }
}
