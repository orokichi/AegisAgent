package com.orokichi.aegisagent.data

import android.content.Context
import android.content.SharedPreferences
import com.orokichi.aegisagent.model.AuditLogEntry
import com.orokichi.aegisagent.model.RiskLevel
import com.orokichi.aegisagent.model.SafetyMode
import com.orokichi.aegisagent.model.ScheduledTask
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("aegis_agent_prefs", Context.MODE_PRIVATE)

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROQ_API_KEY, value.trim()).apply()

    var groqModel: String
        get() = prefs.getString(KEY_GROQ_MODEL, "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
        set(value) = prefs.edit().putString(KEY_GROQ_MODEL, value.trim()).apply()

    var safetyMode: SafetyMode
        get() {
            val name = prefs.getString(KEY_SAFETY_MODE, SafetyMode.STANDARD.name)
            return runCatching { SafetyMode.valueOf(name ?: SafetyMode.STANDARD.name) }
                .getOrDefault(SafetyMode.STANDARD)
        }
        set(value) = prefs.edit().putString(KEY_SAFETY_MODE, value.name).apply()

    var stopOnScreenOff: Boolean
        get() = prefs.getBoolean(KEY_STOP_ON_SCREEN_OFF, true)
        set(value) = prefs.edit().putBoolean(KEY_STOP_ON_SCREEN_OFF, value).apply()

    var showFloatingStopButton: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_STOP, true)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_STOP, value).apply()

    var blockFinanceApps: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_FINANCE, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_FINANCE, value).apply()

    var blockSystemSettings: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SYSTEM, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_SYSTEM, value).apply()

    var maxStepsDefault: Int
        get() = prefs.getInt(KEY_MAX_STEPS, 15).coerceIn(3, 40)
        set(value) = prefs.edit().putInt(KEY_MAX_STEPS, value.coerceIn(3, 40)).apply()

    var useDynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    var themeMode: String // "system", "light", "dark"
        get() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    fun getScheduledTasks(): List<ScheduledTask> {
        val raw = prefs.getString(KEY_SCHEDULED_TASKS, null) ?: return defaultSampleTasks().also {
            saveScheduledTasks(it)
        }
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val daysArr = o.optJSONArray("daysOfWeek") ?: JSONArray()
                    val days = mutableSetOf<Int>()
                    for (j in 0 until daysArr.length()) {
                        days.add(daysArr.getInt(j))
                    }
                    add(
                        ScheduledTask(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            prompt = o.getString("prompt"),
                            hour = o.getInt("hour"),
                            minute = o.getInt("minute"),
                            windowEndHour = o.optInt("windowEndHour", (o.getInt("hour") + 1) % 24),
                            windowEndMinute = o.optInt("windowEndMinute", o.getInt("minute")),
                            daysOfWeek = if (days.isEmpty()) setOf(1, 2, 3, 4, 5, 6, 7) else days,
                            enabled = o.optBoolean("enabled", true),
                            maxSteps = o.optInt("maxSteps", 12),
                            safetyMode = runCatching {
                                SafetyMode.valueOf(o.optString("safetyMode", SafetyMode.STRICT.name))
                            }.getOrDefault(SafetyMode.STRICT),
                            lastRunAt = o.optLong("lastRunAt", 0L),
                            lastStatus = o.optString("lastStatus", "未実行")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveScheduledTasks(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        tasks.forEach { t ->
            val o = JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("prompt", t.prompt)
                put("hour", t.hour)
                put("minute", t.minute)
                put("windowEndHour", t.windowEndHour)
                put("windowEndMinute", t.windowEndMinute)
                put("daysOfWeek", JSONArray(t.daysOfWeek.toList()))
                put("enabled", t.enabled)
                put("maxSteps", t.maxSteps)
                put("safetyMode", t.safetyMode.name)
                put("lastRunAt", t.lastRunAt)
                put("lastStatus", t.lastStatus)
            }
            arr.put(o)
        }
        prefs.edit().putString(KEY_SCHEDULED_TASKS, arr.toString()).apply()
    }

    fun getAuditLogs(): List<AuditLogEntry> {
        val raw = prefs.getString(KEY_AUDIT_LOGS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        AuditLogEntry(
                            id = o.getString("id"),
                            timestamp = o.getLong("timestamp"),
                            step = o.getInt("step"),
                            taskTitle = o.getString("taskTitle"),
                            packageName = o.getString("packageName"),
                            actionLabel = o.getString("actionLabel"),
                            thought = o.getString("thought"),
                            riskLevel = runCatching {
                                RiskLevel.valueOf(o.optString("riskLevel", RiskLevel.SAFE.name))
                            }.getOrDefault(RiskLevel.SAFE),
                            outcome = o.getString("outcome")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun appendAuditLog(entry: AuditLogEntry) {
        val current = getAuditLogs().toMutableList()
        current.add(0, entry)
        val trimmed = current.take(150)
        val arr = JSONArray()
        trimmed.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("timestamp", e.timestamp)
                put("step", e.step)
                put("taskTitle", e.taskTitle)
                put("packageName", e.packageName)
                put("actionLabel", e.actionLabel)
                put("thought", e.thought)
                put("riskLevel", e.riskLevel.name)
                put("outcome", e.outcome)
            })
        }
        prefs.edit().putString(KEY_AUDIT_LOGS, arr.toString()).apply()
    }

    fun clearAuditLogs() {
        prefs.edit().remove(KEY_AUDIT_LOGS).apply()
    }

    private fun defaultSampleTasks(): List<ScheduledTask> = listOf(
        ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = "朝の天気・予定チェック",
            prompt = "天気アプリまたはGoogleを開いて、今日の井原市の天気と気温を確認して要約する",
            hour = 7,
            minute = 30,
            windowEndHour = 8,
            windowEndMinute = 30,
            daysOfWeek = setOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY
            ),
            enabled = false,
            maxSteps = 10,
            safetyMode = SafetyMode.STRICT
        )
    )

    companion object {
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_SAFETY_MODE = "safety_mode"
        private const val KEY_STOP_ON_SCREEN_OFF = "stop_on_screen_off"
        private const val KEY_FLOATING_STOP = "floating_stop"
        private const val KEY_BLOCK_FINANCE = "block_finance"
        private const val KEY_BLOCK_SYSTEM = "block_system"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SCHEDULED_TASKS = "scheduled_tasks"
        private const val KEY_AUDIT_LOGS = "audit_logs"
    }
}
