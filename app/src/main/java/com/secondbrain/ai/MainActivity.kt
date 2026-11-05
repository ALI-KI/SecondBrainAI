package com.secondbrain.ai

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.secondbrain.ai.util.ReminderScheduler
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.secondbrain.ai.data.AttachmentInfo
import com.secondbrain.ai.data.BrainDatabase
import com.secondbrain.ai.data.BrainTask
import com.secondbrain.ai.ui.SecondBrainApp
import com.secondbrain.ai.voice.LiveSpeechController
import com.secondbrain.ai.voice.VoiceCommand
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity:ComponentActivity(){
    private lateinit var db:BrainDatabase
    private var refreshTrigger by mutableIntStateOf(0)
    private var recording by mutableStateOf(false)
    // The visible text is shared between the voice controller and the editable assistant field.
    private var dictationText by mutableStateOf("")
    private var dictationPartial by mutableStateOf("")
    private var dictationListening by mutableStateOf(false)
    private var dictationNotice by mutableStateOf("")
    private var distributeRequests by mutableIntStateOf(0)
    private var distributeAfterStop = false
    private var speechController: LiveSpeechController? = null
    private var recorder:MediaRecorder?=null
    private var voiceFile:File?=null
    private var attachTarget:String?=null

    // App lock: the device credential (fingerprint / face / PIN / pattern) guards the UI.
    private var lockEnabled by mutableStateOf(false)
    private var unlocked by mutableStateOf(true)
    private var lastStopAt=0L
    private var pendingLockAction:String?=null
    private val settingsPrefs get()=getSharedPreferences("secondbrain_settings",0)

    private val credentialLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val ok=result.resultCode==Activity.RESULT_OK
        when(pendingLockAction){
            "unlock"->if(ok)unlocked=true else message("لم يتم فتح القفل")
            "enable"->if(ok)setLock(true) else message("لم يتم تفعيل القفل")
            "disable"->if(ok)setLock(false) else message("لم يتم إيقاف القفل")
        }
        pendingLockAction=null
    }
    private val audioPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok)startRecording() else message("يلزم إذن الميكروفون لتسجيل الصوت")
    }
    private val dictationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok) beginDictation() else dictationNotice="اسمح للميكروفون بالإملاء الفوري من إعدادات أندرويد"
    }
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok) ReminderScheduler.rescheduleAll(this)
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
        lockEnabled=settingsPrefs.getBoolean("app_lock",false)
        // Do not restore a previously unlocked session after Android kills/recreates the process.
        // A fresh Activity always requires a new device-credential confirmation.
        unlocked=!lockEnabled
        pendingLockAction=null
        applySecureFlag()
        db=BrainDatabase(this)
        ReminderScheduler.ensureChannel(this)
        dictationText=savedInstanceState?.getString("assistant_draft").orEmpty()
        // Only the very first creation may import a shared item; a rotation re-delivers the same intent.
        if(savedInstanceState==null)importSharedIntent(intent)
        setContent {
            if(lockEnabled&&!unlocked) LockScreen(onUnlock={requestCredential("unlock")})
            else SecondBrainApp(db=db,trigger=refreshTrigger,recording=recording,
                dictationText=dictationText,dictationPartial=dictationPartial,
                dictationListening=dictationListening,dictationNotice=dictationNotice,
                distributeRequests=distributeRequests,
                onDictationChange={dictationText=it;dictationPartial=""},
                onDictationToggle={toggleDictation()},
                onQueueDistribution={queueDistribution()},
                onRecord={toggleRecording()},onAttach={id->attachTarget=id;filePicker.launch(arrayOf("*/*"))},
                onShare={text->
                    val send=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)}
                    startActivity(Intent.createChooser(send,"مشاركة ملخص الإدارة"))
                },onBackup={backupPicker.launch("SecondBrain_backup_${System.currentTimeMillis()}.zip")},
                onOpenAttachment={a->openAttachment(a)},
                lockEnabled=lockEnabled,onLockToggle={on->requestCredential(if(on)"enable" else "disable")},
                onReminderTest={testNotificationNow()},onReminderMinute={testReminderInMinute()},
                onNotificationSettings={openReminderSettings()},onExactAlarmSettings={openExactAlarmSettings()})
        }
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            // Ask once: tasks can be saved even when the user declines.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if(lockEnabled&&!unlocked)requestCredential("unlock")
    }
    override fun onResume() {
        super.onResume()
        if (::db.isInitialized) {
            // Don't clear already delivered notifications every time the user returns to the app.
            // Only reschedule if the exact-alarm permission changed since the last foreground visit.
            val exact = ReminderScheduler.exactAllowed(this)
            val previous = settingsPrefs.getBoolean("exact_alarm_previously_allowed", exact)
            if (previous != exact) ReminderScheduler.rescheduleAll(this)
            settingsPrefs.edit().putBoolean("exact_alarm_previously_allowed", exact).apply()
            refreshTrigger++
        }
    }
    private fun testNotificationNow() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            message("اسمح بالإشعارات، ثم اضغط اختبار الآن مرة أخرى")
        } else if (!ReminderScheduler.notificationsAllowed(this)) {
            message("الإشعارات مغلقة؛ افتح إعدادات الإشعارات والصوت")
        } else if (!ReminderScheduler.showTestNow(this)) message("تعذر عرض الإشعار؛ راجع إعدادات الهاتف")
        else message("تم إرسال إشعار تجريبي — راجع الصوت ولوحة الإشعارات")
    }
    private fun testReminderInMinute() {
        if (!ReminderScheduler.notificationsAllowed(this)) { testNotificationNow(); return }
        ReminderScheduler.scheduleTest(this)
        message(if (ReminderScheduler.exactAllowed(this))
            "تم جدولة اختبار بعد دقيقة؛ اقفل الشاشة للتجربة" else
            "تم جدولة الاختبار؛ قد يتأخر حتى تفعل إذن المنبهات والتذكيرات")
    }
    private fun openReminderSettings() {
        ReminderScheduler.ensureChannel(this)
        try { startActivity(ReminderScheduler.channelSettingsIntent(this)) }
        catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)) }
    }
    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) try { startActivity(ReminderScheduler.exactPermissionIntent(this)) }
        catch (_: Exception) { message("افتح إعدادات التطبيقات ← وصول خاص ← المنبهات والتذكيرات") }
        else message("الجدولة الدقيقة متاحة على إصدار أندرويد الحالي")
    }
    override fun onStart(){
        super.onStart()
        if(lockEnabled&&unlocked&&lastStopAt>0&&SystemClock.elapsedRealtime()-lastStopAt>60_000L){
            unlocked=false;requestCredential("unlock")
        }
    }
    override fun onStop(){lastStopAt=SystemClock.elapsedRealtime();super.onStop()}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);importSharedIntent(intent)}

    // ---- app lock ----
    private fun requestCredential(action:String){
        val manager=getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent=manager.createConfirmDeviceCredentialIntent("Second Brain AI","أكّد هويتك للمتابعة")
        if(intent==null){ // the phone has no secure screen lock
            if(action=="unlock"){setLock(false);message("تم إيقاف قفل التطبيق لأن الهاتف بلا قفل شاشة")}
            else message("فعّل قفل شاشة (بصمة أو PIN أو نمط) من إعدادات الهاتف أولًا")
            return
        }
        pendingLockAction=action
        credentialLauncher.launch(intent)
    }
    private fun setLock(on:Boolean){
        settingsPrefs.edit().putBoolean("app_lock",on).apply()
        lockEnabled=on;unlocked=true
        applySecureFlag()
        message(if(on)"تم تفعيل قفل التطبيق" else "تم إيقاف قفل التطبيق")
    }
    /** While the lock is on, hide the app preview in Recents and block screenshots. */
    private fun applySecureFlag(){
        if(lockEnabled)window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ---- attachments ----
    private fun openAttachment(a:AttachmentInfo){
        val file=File(a.path)
        if(!file.exists()){message("الملف غير موجود على الجهاز");return}
        try{
            val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
            val view=Intent(Intent.ACTION_VIEW).apply{
                setDataAndType(uri,a.mime.ifBlank{"*/*"})
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(view)
        }catch(e:Exception){message("لا يوجد تطبيق على الهاتف يفتح هذا النوع من الملفات")}
    }

    private fun importSharedIntent(intent:Intent?){
        if(intent?.action!=Intent.ACTION_SEND)return
        val text=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        @Suppress("DEPRECATION")
        val uri=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)
                  else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if(text.isBlank()&&uri==null)return
        val fileName=if(uri!=null)db.displayName(uri) else ""
        val title=text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(90)
            ?: fileName.ifBlank { "مرفق جديد من المشاركة" }
        val t=BrainTask(id=UUID.randomUUID().toString(),title=title,description=text,workspaceId="personal",status="inbox")
        db.putTask(t)
        var attachFailed=false
        if(uri!=null)try{db.addAttachment(t.id,uri)}catch(_:Exception){attachFailed=true}
        refreshTrigger++
        message(if(attachFailed)"تم حفظ المشاركة لكن تعذر حفظ المرفق" else "تم حفظ المشاركة في الوارد المحلي")
    }

    // ---- live speech -> editable text -> task distribution ----
    private fun toggleDictation() {
        if(dictationListening){speechController?.stop();return}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)
            !=PackageManager.PERMISSION_GRANTED){
            dictationPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else beginDictation()
    }
    private fun queueDistribution() {
        if(dictationListening){
            distributeAfterStop=true
            speechController?.stop()
        }else{
            distributeAfterStop=false
            distributeRequests++
        }
    }
    private fun beginDictation() {
        if(!LiveSpeechController.onDeviceAvailable(this) &&
            !settingsPrefs.getBoolean("allow_system_speech",false)) {
            AlertDialog.Builder(this)
                .setTitle("خصوصية الإملاء الفوري")
                .setMessage("هذا الهاتف لا يوفر حاليًا محرك تعرف صوتي محليًا للتطبيق. يمكن استخدام خدمة التعرف الصوتي الخاصة بالنظام، لكنها قد تعالج الصوت عبر الإنترنت لدى مزود الهاتف (وليس OpenAI API). هل توافق؟")
                .setPositiveButton("أوافق") { _, _ ->
                    settingsPrefs.edit().putBoolean("allow_system_speech",true).apply()
                    beginDictation()
                }
                .setNegativeButton("إلغاء",null)
                .show()
            return
        }
        speechController?.destroy()
        dictationNotice=""
        speechController=LiveSpeechController(this,
            settingsPrefs.getBoolean("allow_system_speech",false),
            onFinal={ segment ->
                val (cleaned,distribute)=VoiceCommand.extract(segment)
                if(cleaned.isNotBlank()) {
                    dictationText=(dictationText.trimEnd()+" "+cleaned).trim()
                }
                dictationPartial=""
                if(distribute) queueDistribution()
            },
            onPartial={dictationPartial=it},
            onActive={ active ->
                dictationListening=active
                if(!active && distributeAfterStop){
                    distributeAfterStop=false
                    distributeRequests++
                }
            },
            onErrorMessage={dictationNotice=it}
        )
        speechController?.start()
    }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("assistant_draft",dictationText)
        super.onSaveInstanceState(outState)
    }

    // ---- optional audio-file recording (different from live dictation) ----
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
                    description="تسجيل صوتي محفوظ محليًا، ويمكنك تعديل عنوان المهمة وتفاصيلها.",workspaceId="personal",status="inbox")
                db.putTask(task);db.linkExistingAttachment(task.id,file,"تسجيل صوتي $label.m4a","audio/mp4")
                refreshTrigger++;message("تم حفظ التسجيل في الوارد")
            }
        }catch(e:Exception){recorder?.release();recorder=null;file?.delete();message("التسجيل لم يكتمل، أعد المحاولة")}
        finally {voiceFile=null}
    }
    override fun onDestroy(){
        speechController?.destroy()
        // Rotation/screen-size changes are handled without destroying this Activity (see manifest).
        // If Android destroys it for any other reason, finalize and link the recording before DB close.
        if(recording)stopRecording()
        if(::db.isInitialized)db.close()
        super.onDestroy()
    }
    private fun message(text:String){Toast.makeText(this,text,Toast.LENGTH_SHORT).show()}
}

@Composable
private fun LockScreen(onUnlock:()->Unit){
    Box(Modifier.fillMaxSize().background(Color(0xFF07131D)),contentAlignment=Alignment.Center){
        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("Second Brain AI",color=Color(0xFFFFDDA4),fontSize=22.sp,fontWeight=FontWeight.ExtraBold)
            Text("التطبيق مقفل. استخدم البصمة أو رمز الهاتف لفتحه.",color=Color(0xFF9CB0BC),fontSize=13.sp,textAlign=TextAlign.Center)
            Button(onClick=onUnlock,colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE5AC55),contentColor=Color(0xFF07131D))){
                Text("فتح القفل",fontWeight=FontWeight.Bold)
            }
        }
    }
}
