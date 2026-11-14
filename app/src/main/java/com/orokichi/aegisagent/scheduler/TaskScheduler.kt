package com.orokichi.aegisagent.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.orokichi.aegisagent.data.SettingsStore
import com.orokichi.aegisagent.model.ScheduledTask
import java.util.Calendar

object TaskScheduler {
    fun rescheduleAll(context: Context) {
        val tasks = SettingsStore(context).getScheduledTasks()
        tasks.forEach { task ->
            if (task.enabled) {
                scheduleTask(context, task)
            } else {
                cancelTask(context, task.id)
            }
        }
    }

    fun scheduleTask(context: Context, task: ScheduledTask) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = TaskAlarmReceiver.ACTION_RUN_SCHEDULED_TASK
            putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, task.id)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextMillis = computeNextTriggerMillis(task) ?: return
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }

        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMillis, pi)
        }
    }

    fun cancelTask(context: Context, taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = TaskAlarmReceiver.ACTION_RUN_SCHEDULED_TASK
        }
        val pi = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    fun isWithinTimeWindow(task: ScheduledTask, now: Calendar = Calendar.getInstance()): Boolean {
        val curMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMins = task.hour * 60 + task.minute
        val endMins = task.windowEndHour * 60 + task.windowEndMinute
        return if (startMins <= endMins) {
            curMins in startMins..endMins
        } else {
            curMins >= startMins || curMins <= endMins
        }
    }

    private fun computeNextTriggerMillis(task: ScheduledTask): Long? {
        if (task.daysOfWeek.isEmpty()) return null
        val now = Calendar.getInstance()
        for (offset in 0..7) {
            val candidate = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, task.hour)
                set(Calendar.MINUTE, task.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dow = candidate.get(Calendar.DAY_OF_WEEK)
            if (dow in task.daysOfWeek && candidate.timeInMillis > now.timeInMillis + 5_000L) {
                return candidate.timeInMillis
            }
        }
        return null
    }
}
