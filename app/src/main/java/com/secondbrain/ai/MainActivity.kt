package com.secondbrain.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.secondbrain.ai.data.BrainDatabase
import com.secondbrain.ai.data.BrainTask
import com.secondbrain.ai.ui.SecondBrainApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity:ComponentActivity(){
    private lateinit var db:BrainDatabase
    private var refreshTrigger by mutableIntStateOf(0)
    private var recording by mutableStateOf(false)
    private var recorder:MediaRecorder?=null
    private var voiceFile:File?=null
    private var attachTarget:String?=null

    private val audioPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok)startRecording() else message("يلزم إذن الميكروفون لتسجيل الصوت")
    }
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(!ok)message("يمكن تفعيل الإشعارات من إعدادات الهاتف لاحقًا")
    }
    private val backupPicker=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri:Uri? ->
        if(uri!=null)try { db.exportBackup(uri);message("تم إنشاء نسخة محلية ZIP") }
        catch(e:Exception){message("تعذر إنشاء النسخة: ${e.message}")}
    }
    private val filePicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? ->
        val target=attachTarget
        if(uri!=null&&target!=null)try{
            db.addAttachment(target,uri);refreshTrigger++;message("تم حفظ المرفق محليًا")
        }catch(e:Exception){message("تعذر حفظ المرفق: ${e.message}")}
        attachTarget=null
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        db=BrainDatabase(this)
        importSharedIntent(intent)
        setContent {
            SecondBrainApp(db=db,trigger=refreshTrigger,recording=recording,
                onRecord={toggleRecording()},onAttach={id->attachTarget=id;filePicker.launch(arrayOf("*/*"))},
                onShare={text->
                    val send=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)}
                    startActivity(Intent.createChooser(send,"مشاركة ملخص الإدارة"))
                },onBackup={backupPicker.launch("SecondBrain_backup_${System.currentTimeMillis()}.zip")})
        }
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            // Ask once: tasks can be saved even when the user declines.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);importSharedIntent(intent)}
    private fun importSharedIntent(intent:Intent?){
        if(intent?.action!=Intent.ACTION_SEND)return
        val text=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        @Suppress("DEPRECATION")
        val uri=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)
                  else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if(text.isBlank()&&uri==null)return
        val t=BrainTask(id=UUID.randomUUID().toString(),title=text.lineSequence().firstOrNull()?.take(90)?.ifBlank { "مرفق جديد من المشاركة" }
            ?: "مرفق جديد من المشاركة",description=text,workspaceId="personal",status="inbox")
        db.putTask(t)
        if(uri!=null)try{db.addAttachment(t.id,uri)}catch(_:Exception){}
        refreshTrigger++
        message("تم حفظ المشاركة في الوارد المحلي")
    }
    private fun toggleRecording(){if(recording)stopRecording() else if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)}
    private fun startRecording(){
        try{
            val file=File(db.voiceDir(),"voice_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(this)else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(file.absolutePath)
            r.prepare();r.start();recorder=r;voiceFile=file;recording=true
            message("جارٍ تسجيل الصوت محليًا")
        }catch(e:Exception){recording=false;recorder?.release();recorder=null;message("تعذر بدء التسجيل: ${e.message}")}
    }
    private fun stopRecording(){
        recording=false
        val file=voiceFile
        try{
            recorder?.stop();recorder?.release();recorder=null
            if(file!=null&&file.exists()&&file.length()>0){
                val label=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("ar")).format(Date())
                val task=BrainTask(id=UUID.randomUUID().toString(),title="تسجيل صوتي — $label",
                    description="تسجيل صوتي محفوظ محليًا في مجلد Voice، ويمكنك تعديل عنوان المهمة وتفاصيلها.",workspaceId="personal",status="inbox")
                db.putTask(task);db.linkExistingAttachment(task.id,file)
                refreshTrigger++;message("تم حفظ التسجيل في الوارد ومجلد Voice")
            }
        }catch(e:Exception){recorder?.release();recorder=null;file?.delete();message("التسجيل لم يكتمل، أعد المحاولة")}
        finally {voiceFile=null}
    }
    override fun onDestroy(){
        if(recording){try{recorder?.stop()}catch(_:Exception){};recorder?.release()}
        if(::db.isInitialized)db.close()
        super.onDestroy()
    }
    private fun message(text:String){Toast.makeText(this,text,Toast.LENGTH_SHORT).show()}
}
