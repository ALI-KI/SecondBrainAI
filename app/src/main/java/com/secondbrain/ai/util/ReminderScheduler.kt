package com.secondbrain.ai.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.secondbrain.ai.MainActivity
import com.secondbrain.ai.R
import com.secondbrain.ai.data.BrainDatabase
import com.secondbrain.ai.data.BrainTask
import com.secondbrain.ai.data.TaskStatus

/** Inexact alarms work without the privileged exact-alarm permission. */
object ReminderScheduler {
    private val kinds = listOf(ReminderPlan.FOLLOW_UP, ReminderPlan.DUE)
    private fun alarmIntent(context: Context, id: String, kind: String) =
        Intent(context, ReminderReceiver::class.java).apply {
            action = "${context.packageName}.REMINDER.$kind"
            data = Uri.parse("secondbrain://reminder/${Uri.encode(id)}/$kind")
        }
    private fun pending(context: Context, id: String, kind: String): PendingIntent =
        PendingIntent.getBroadcast(context, "$id|$kind".hashCode(), alarmIntent(context,id,kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelLegacy(context: Context, id: String, alarm: AlarmManager) {
        // v0.2 / v0.3 used one alarm with id.hashCode(). Remove it during upgrades.
        val legacy = PendingIntent.getBroadcast(context,id.hashCode(),Intent(context,ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (legacy != null) { alarm.cancel(legacy);legacy.cancel() }
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }
    fun schedule(context: Context, t: BrainTask) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Clear old alarms and already shown notifications after edits (including changed dates).
        kinds.forEach { kind ->
            val pi = pending(context,t.id,kind)
            alarm.cancel(pi)
            NotificationManagerCompat.from(context).cancel("${t.id}:$kind",0)
        }
        cancelLegacy(context,t.id,alarm)
        ReminderPlan.pending(t,System.currentTimeMillis()).forEach { slot ->
            val pi = PendingIntent.getBroadcast(context,"${t.id}|${slot.kind}".hashCode(),
                alarmIntent(context,t.id,slot.kind).apply {
                    putExtra("task_id",t.id)
                    putExtra("task_title",t.title)
                    putExtra("kind",slot.kind)
                    putExtra("scheduled_at",slot.at)
                },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,slot.at,pi)
        }
    }
    fun cancel(context: Context, id: String) {
        val alarm=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        kinds.forEach { kind ->
            val pi=pending(context,id,kind)
            alarm.cancel(pi)
            pi.cancel()
            NotificationManagerCompat.from(context).cancel("$id:$kind",0)
        }
        cancelLegacy(context,id,alarm)
    }
}

class ReminderReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id=intent.getStringExtra("task_id") ?: return
        val kind=intent.getStringExtra("kind") ?: return
        val at=intent.getLongExtra("scheduled_at",-1L)
        // Avoid notifications for old/rescheduled/completed tasks.
        val db=BrainDatabase(context)
        val task=try { db.task(id) } finally { db.close() }
        if(task==null || !TaskStatus.isOpen(task.status) ||
            ReminderPlan.timestamp(task,kind)!=at || at < 0L) return
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("brain_reminders","متابعات العقل الثاني",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label=if(kind==ReminderPlan.DUE)"موعد استحقاق مهمة" else "متابعة مطلوبة"
        val notification=NotificationCompat.Builder(context,"brain_reminders")
            .setSmallIcon(R.drawable.ic_stat_brain).setContentTitle(label)
            .setContentText(task.title).setAutoCancel(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        NotificationManagerCompat.from(context).notify("$id:$kind",0,notification)
    }
}

class BootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val db=BrainDatabase(context)
        try { db.tasks().forEach { ReminderScheduler.schedule(context,it) } }
        finally { db.close() }
    }
}
