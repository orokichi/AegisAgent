package com.orokichi.aegisagent.api

import com.orokichi.aegisagent.model.ActionType
import com.orokichi.aegisagent.model.AgentDecision
import com.orokichi.aegisagent.model.RiskLevel
import com.orokichi.aegisagent.model.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GroqClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun decideNextAction(
        apiKey: String,
        model: String,
        taskPrompt: String,
        snapshot: ScreenSnapshot,
        historyLines: List<String>,
        step: Int,
        maxSteps: Int
    ): Result<AgentDecision> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "Groq APIキーが設定されていません。" }

            val systemPrompt = """
                You are AegisAgent, a safe autonomous Android UI agent operating via AccessibilityService.
                Your job is to complete the user's task step-by-step while strictly evaluating safety risks.

                You MUST respond ONLY with a valid JSON object matching this schema:
                {
                  "thought": "Brief reasoning in Japanese",
                  "action": "CLICK" | "TYPE_TEXT" | "SCROLL" | "BACK" | "HOME" | "OPEN_APP" | "WAIT" | "FINISH" | "ABORT",
                  "target_index": number or null,
                  "input_text": string or null,
                  "app_package": string or null,
                  "scroll_direction": "UP" | "DOWN" | null,
                  "ai_risk_level": "SAFE" | "CAUTION" | "DANGER" | "CRITICAL_BLOCK",
                  "ai_risk_reason": "Why this step is safe or risky (Japanese)",
                  "summary": "Final summary for user if action is FINISH or ABORT (Japanese)"
                }

                Execution rules:
                - Follow the user's task directly across any app or screen without refusing actions.
                - Set "ai_risk_level" to "SAFE" for normal user-requested tasks.
                - Never invent target_index that is not in the provided element list.
                - Use "FINISH" as soon as the user's goal is achieved or observed.
            """.trimIndent()

            val userMessage = buildString {
                appendLine("Task: $taskPrompt")
                appendLine("Step: $step / $maxSteps")
                if (historyLines.isNotEmpty()) {
                    appendLine("Recent steps:")
                    historyLines.takeLast(6).forEach { appendLine("- $it") }
                }
                appendLine()
                appendLine("Current Screen:")
                appendLine(snapshot.toPromptText())
            }

            val bodyJson = JSONObject().apply {
                put("model", model)
                put("temperature", 0.2)
                put("max_tokens", 500)
                put("response_format", JSONObject().put("type", "json_object"))
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userMessage))
                })
            }

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = runCatching {
                        JSONObject(rawBody).getJSONObject("error").getString("message")
                    }.getOrDefault("HTTP ${response.code}: $rawBody")
                    throw IllegalStateException("Groq APIエラー: $errMsg")
                }

                val root = JSONObject(rawBody)
                val content = root.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                parseDecision(content)
            }
        }
    }

    suspend fun testConnection(apiKey: String, model: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "APIキーが空です。" }
            val bodyJson = JSONObject().apply {
                put("model", model)
                put("max_tokens", 20)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", "Reply with OK"))
                })
            }
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = runCatching {
                        JSONObject(raw).getJSONObject("error").getString("message")
                    }.getOrDefault("HTTP ${response.code}")
                    throw IllegalStateException(errMsg)
                }
                "接続成功 ($model)"
            }
        }
    }

    private fun parseDecision(jsonText: String): AgentDecision {
        val cleaned = jsonText
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = JSONObject(cleaned)

        val actionStr = obj.optString("action", "WAIT").uppercase()
        val actionType = runCatching { ActionType.valueOf(actionStr) }.getOrDefault(ActionType.WAIT)

        val riskStr = obj.optString("ai_risk_level", "SAFE").uppercase()
        val riskLevel = runCatching { RiskLevel.valueOf(riskStr) }.getOrDefault(RiskLevel.SAFE)

        val targetIndex = if (obj.isNull("target_index")) null else obj.optInt("target_index", -1).takeIf { it >= 0 }

        return AgentDecision(
            thought = obj.optString("thought", ""),
            actionType = actionType,
            targetIndex = targetIndex,
            inputText = obj.optString("input_text", "").takeIf { it.isNotBlank() && it != "null" },
            appPackage = obj.optString("app_package", "").takeIf { it.isNotBlank() && it != "null" },
            scrollDirection = obj.optString("scroll_direction", "").takeIf { it.isNotBlank() && it != "null" },
            aiRiskLevel = riskLevel,
            aiRiskReason = obj.optString("ai_risk_reason", ""),
            summary = obj.optString("summary", "")
        )
    }
}
