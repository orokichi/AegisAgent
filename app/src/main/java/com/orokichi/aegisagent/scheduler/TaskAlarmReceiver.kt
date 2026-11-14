package com.orokichi.aegisagent.scheduler

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.orokichi.aegisagent.agent.AgentEngine
import com.orokichi.aegisagent.data.SettingsStore

class TaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                TaskScheduler.rescheduleAll(context)
            }
            ACTION_EMERGENCY_STOP -> {
                AgentEngine.emergencyStop(context, "通知の緊急停止ボタンから停止しました")
            }
            ACTION_APPROVE_ACTION -> {
                AgentEngine.resolveApproval(context, true)
            }
            ACTION_DENY_ACTION -> {
                AgentEngine.resolveApproval(context, false)
            }
            ACTION_RUN_SCHEDULED_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
                val store = SettingsStore(context)
                val tasks = store.getScheduledTasks()
                val task = tasks.firstOrNull { it.id == taskId && it.enabled } ?: return

                // 次回分を先に予約
                TaskScheduler.scheduleTask(context, task)

                if (!TaskScheduler.isWithinTimeWindow(task)) {
                    updateTaskStatus(store, task.id, "時間帯外のためスキップ")
                    return
                }

                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked) {
                    updateTaskStatus(store, task.id, "画面ロック中のため安全スキップ")
                    return
                }

                AgentEngine.startTask(
                    context = context,
                    taskTitle = "定期: ${task.name}",
                    taskPrompt = task.prompt,
                    maxStepsOverride = task.maxSteps,
                    safetyModeOverride = task.safetyMode
                ) { resultStatus ->
                    updateTaskStatus(store, task.id, resultStatus)
                }
            }
        }
    }

    private fun updateTaskStatus(store: SettingsStore, taskId: String, status: String) {
        val updated = store.getScheduledTasks().map {
            if (it.id == taskId) it.copy(lastRunAt = System.currentTimeMillis(), lastStatus = status) else it
        }
        store.saveScheduledTasks(updated)
    }

    companion object {
        const val ACTION_RUN_SCHEDULED_TASK = "com.orokichi.aegisagent.ACTION_RUN_SCHEDULED_TASK"
        const val ACTION_EMERGENCY_STOP = "com.orokichi.aegisagent.ACTION_EMERGENCY_STOP"
        const val ACTION_APPROVE_ACTION = "com.orokichi.aegisagent.ACTION_APPROVE_ACTION"
        const val ACTION_DENY_ACTION = "com.orokichi.aegisagent.ACTION_DENY_ACTION"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
