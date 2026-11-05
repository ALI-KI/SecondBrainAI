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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.secondbrain.ai.MainActivity
import com.secondbrain.ai.R
import com.secondbrain.ai.data.BrainDatabase
import com.secondbrain.ai.data.BrainTask
import com.secondbrain.ai.data.TaskStatus

/** Uses inexact alarms deliberately, without privileged exact-alarm permissions. */
object ReminderScheduler {
    fun schedule(context:Context,t:BrainTask){
        val alarm=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i=Intent(context,ReminderReceiver::class.java).apply{
            putExtra("task_id",t.id);putExtra("task_title",t.title)
        }
        val pi=PendingIntent.getBroadcast(context,t.id.hashCode(),i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Cancel the previous alarm whenever a task is moved, completed, or rescheduled.
        alarm.cancel(pi)
        if(!TaskStatus.isOpen(t.status))return
        val next=listOfNotNull(t.followUpAt,t.dueAt).filter{it>System.currentTimeMillis()}.minOrNull() ?: return
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pi)
    }
}

class ReminderReceiver: BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel("brain_reminders","متابعات العقل الثاني",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val id=intent.getStringExtra("task_id")?:return
        // A notification must never reappear for a task that was already closed/deleted.
        val db=BrainDatabase(context)
        val pending=db.tasks().any { it.id==id && TaskStatus.isOpen(it.status) }
        db.close()
        if(!pending)return
        val message=intent.getStringExtra("task_title")?:"لديك مهمة تحتاج متابعة"
        val notification=NotificationCompat.Builder(context,"brain_reminders")
            .setSmallIcon(R.drawable.brain_logo).setContentTitle("تذكير من العقل الثاني")
            .setContentText(message).setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        NotificationManagerCompat.from(context).notify(intent.getStringExtra("task_id")?.hashCode()?:1,notification)
    }
}

class BootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED)return
        val db=BrainDatabase(context)
        db.tasks().forEach{ ReminderScheduler.schedule(context,it) }
        db.close()
    }
}
