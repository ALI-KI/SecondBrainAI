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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.secondbrain.ai.MainActivity
import com.secondbrain.ai.R
import com.secondbrain.ai.data.BrainDatabase
import com.secondbrain.ai.data.BrainTask
import com.secondbrain.ai.data.TaskStatus

/** Local reminder engine: never needs OpenAI, a server or an internet connection. */
object ReminderScheduler {
    // New channel ID matters: Android does not let an app increase the importance of an existing channel.
    const val CHANNEL_ID = "brain_reminders_audible_v1"
    private const val TEST_ID = "__notification_test__"
    private const val TEST_KIND = "test"
    private val kinds = listOf(ReminderPlan.FOLLOW_UP, ReminderPlan.DUE)

    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val channel = NotificationChannel(CHANNEL_ID, "تذكيرات المهام بصوت", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "إشعارات متابعة المهام والمواعيد مع نغمة واهتزاز"
            enableVibration(true)
            enableLights(true)
            setSound(sound, attributes)
        }
        manager.createNotificationChannel(channel)
    }
    fun channelSoundEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance >= NotificationManager.IMPORTANCE_DEFAULT && channel.sound != null
    }
    fun channelSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        }
    fun exactPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}"))

    private fun alarmIntent(context: Context, id: String, kind: String) =
        Intent(context, ReminderReceiver::class.java).apply {
            action = "${context.packageName}.REMINDER.$kind"
            data = Uri.parse("secondbrain://reminder/${Uri.encode(id)}/$kind")
        }
    private fun pending(context: Context, id: String, kind: String): PendingIntent =
        PendingIntent.getBroadcast(context, "$id|$kind".hashCode(), alarmIntent(context,id,kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelLegacy(context: Context, id: String, alarm: AlarmManager) {
        val legacy = PendingIntent.getBroadcast(context,id.hashCode(),Intent(context,ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (legacy != null) { alarm.cancel(legacy);legacy.cancel() }
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }
    private fun setAlarm(context: Context, alarm: AlarmManager, at: Long, pi: PendingIntent) {
        if (at <= System.currentTimeMillis()) return
        // Exact only with explicit Android permission; otherwise a best-effort inexact alarm.
        if (exactAllowed(context)) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                return
            } catch (_: SecurityException) { /* permission may have been revoked */ }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
    fun schedule(context: Context, t: BrainTask) {
        ensureChannel(context)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
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
                    putExtra("kind",slot.kind)
                    putExtra("scheduled_at",slot.at)
                },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            setAlarm(context,alarm,slot.at,pi)
        }
    }
    fun cancel(context: Context, id: String) {
        val alarm=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        kinds.forEach { kind ->
            val pi=pending(context,id,kind)
            alarm.cancel(pi);pi.cancel()
            NotificationManagerCompat.from(context).cancel("$id:$kind",0)
        }
        cancelLegacy(context,id,alarm)
    }
    fun rescheduleAll(context: Context) {
        ensureChannel(context)
        val db = BrainDatabase(context)
        try { db.tasks().forEach { schedule(context,it) } } finally { db.close() }
    }
    fun showTestNow(context: Context): Boolean = postNotification(context,"تجربة إشعار العقل الثاني",
        "ده اختبار لصوت الإشعارات والاهتزاز — مش مهمة جديدة", "brain_test_now",0)
    fun scheduleTest(context: Context) {
        ensureChannel(context)
        val at=System.currentTimeMillis()+60_000L
        val pi=PendingIntent.getBroadcast(context,"$TEST_ID|$TEST_KIND".hashCode(),
            alarmIntent(context,TEST_ID,TEST_KIND).apply {
                putExtra("task_id",TEST_ID);putExtra("kind",TEST_KIND);putExtra("scheduled_at",at)
            },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setAlarm(context,context.getSystemService(Context.ALARM_SERVICE) as AlarmManager,at,pi)
    }
    fun postNotification(context: Context, title: String, body: String, tag: String, id: Int): Boolean {
        ensureChannel(context)
        if (!notificationsAllowed(context)) return false
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_brain)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(open)
            .setDefaults(android.app.Notification.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(context).notify(tag,id,notification) }
        catch (_:SecurityException) { return false }
        return true
    }
}

class ReminderReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id=intent.getStringExtra("task_id") ?: return
        val kind=intent.getStringExtra("kind") ?: return
        if (id=="__notification_test__" && kind=="test") {
            ReminderScheduler.postNotification(context,"اختبار التذكير بعد دقيقة",
                "التنبيه يعمل حتى لو التطبيق في الخلفية", "brain_test_delayed",0)
            return
        }
        val at=intent.getLongExtra("scheduled_at",-1L)
        val db=BrainDatabase(context)
        val task=try { db.task(id) } finally { db.close() }
        if(task==null || !TaskStatus.isOpen(task.status) ||
            ReminderPlan.timestamp(task,kind)!=at || at < 0L) return
        val label=if(kind==ReminderPlan.DUE) "حان موعد استحقاق المهمة" else "حان موعد المتابعة"
        ReminderScheduler.postNotification(context,label,task.title,"$id:$kind",0)
    }
}

class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        ReminderScheduler.rescheduleAll(context)
    }
}
