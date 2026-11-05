package com.secondbrain.ai.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import com.secondbrain.ai.ai.ApiKeyVault
import com.secondbrain.ai.ai.BudgetGuard
import com.secondbrain.ai.ai.SmartApiClient
import org.json.JSONObject
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import com.secondbrain.ai.R
import com.secondbrain.ai.data.*
import com.secondbrain.ai.util.ReminderScheduler
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.Calendar
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.ui.platform.LocalContext

private val BG=Color(0xFF07131D)
private val PANEL=Color(0xFF0E202D)
private val PANEL_LIGHT=Color(0xFF1B3443)
private val GOLD=Color(0xFFE5AC55)
private val GOLD_PALE=Color(0xFFFFDDA4)
private val TEXT=Color(0xFFF4F0E8)
private val MUTED=Color(0xFF9CB0BC)
private val GREEN=Color(0xFF46C79B)
private val RED=Color(0xFFEC7776)
private val LINE=Color(0xFF344A59)
private val rounded=RoundedCornerShape(18.dp)

private fun fmt(millis: Long?): String = millis?.let { SimpleDateFormat("dd MMM yyyy",Locale("ar")).format(Date(it)) } ?: "غير محدد"
private fun iso(millis: Long?): String = millis?.let { SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(it)) } ?: ""
private fun fromIso(s: String): Long? = try { LocalDate.parse(s.trim()).atTime(9,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch(_: Exception) { null }

private fun fmtDateTime(millis: Long): String = SimpleDateFormat("EEE dd MMM yyyy • hh:mm a",Locale("ar")).format(Date(millis))
private fun at9(c: Calendar): Calendar = c.apply{set(Calendar.HOUR_OF_DAY,9);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}
private fun tomorrowAt9(): Long = at9(Calendar.getInstance().apply{add(Calendar.DAY_OF_YEAR,1)}).timeInMillis
private fun nextSundayAt9(): Long {
    val c=at9(Calendar.getInstance())
    do { c.add(Calendar.DAY_OF_YEAR,1) } while(c.get(Calendar.DAY_OF_WEEK)!=Calendar.SUNDAY)
    return c.timeInMillis
}
/** Native date dialog followed by a native time dialog; returns the chosen instant in local time. */
private fun pickDateTime(context: Context, initial: Long?, onPicked: (Long) -> Unit) {
    val base=Calendar.getInstance().apply{timeInMillis=initial?:(System.currentTimeMillis()+3_600_000L)}
    DatePickerDialog(context,{_,y,m,d->
        TimePickerDialog(context,{_,h,min->
            val chosen=Calendar.getInstance().apply{set(y,m,d,h,min,0);set(Calendar.MILLISECOND,0)}
            onPicked(chosen.timeInMillis)
        },base.get(Calendar.HOUR_OF_DAY),base.get(Calendar.MINUTE),false).show()
    },base.get(Calendar.YEAR),base.get(Calendar.MONTH),base.get(Calendar.DAY_OF_MONTH)).show()
}

@Composable
fun SecondBrainApp(db: BrainDatabase, trigger: Int, recording: Boolean, onRecord: () -> Unit,
                   onAttach: (String) -> Unit, onShare: (String) -> Unit, onBackup: () -> Unit,
                   onOpenAttachment: (AttachmentInfo) -> Unit, lockEnabled: Boolean, onLockToggle: (Boolean) -> Unit) {
    var screen by remember { mutableStateOf("home") }
    val smartClient=remember { SmartApiClient(db.contextForScheduler()) }
    var revision by remember { mutableIntStateOf(0) }
    val workspaces= remember(revision,trigger) { db.workspaces() }
    val projects= remember(revision,trigger) { db.projects() }
    val topics= remember(revision,trigger) { db.topics() }
    val tasks= remember(revision,trigger) { db.tasks() }
    var editor by remember { mutableStateOf<BrainTask?>(null) }
    var creatingTask by remember { mutableStateOf(false) }
    var newWorkspace by remember { mutableStateOf(false) }
    var newProjectIn by remember { mutableStateOf<String?>(null) }
    var newTopicIn by remember { mutableStateOf<String?>(null) }
    var activeWorkspace by remember { mutableStateOf("all") }
    val prefs=remember { db.rootFolder(); dbContextPreferences(db) }
    var smartMode by remember { mutableStateOf(prefs.getBoolean("smart_mode",false)) }
    fun reload(){revision++}
    fun saveTask(t: BrainTask){db.putTask(t);ReminderScheduler.schedule(db.contextForScheduler(),t);reload()}
    MaterialTheme(colorScheme=darkColorScheme(background=BG,surface=PANEL,primary=GOLD,onPrimary=BG,onSurface=TEXT,onBackground=TEXT)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(containerColor=BG, bottomBar={
                NavigationBar(containerColor=Color(0xFF091C27),tonalElevation=0.dp) {
                    listOf(Triple("home","⌂","الرئيسية"),Triple("workspaces","◈","المشروعات"),
                        Triple("tasks","☷","المهام"),Triple("reports","▤","التقارير"),Triple("settings","⚙","المزيد")
                    ).forEach { (key, symbol, label) ->
                        NavigationBarItem(selected=screen==key,onClick={screen=key},icon={Text(symbol,fontSize=23.sp,color=if(screen==key)GOLD else MUTED)},
                            label={Text(label,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(indicatorColor=Color(0xFF3B3027),selectedTextColor=GOLD,unselectedTextColor=MUTED))
                    }
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).background(BG)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=18.dp, vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.brain_logo),"شعار العقل الثاني",Modifier.size(46.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text("Second Brain AI",color=GOLD_PALE,fontWeight=FontWeight.ExtraBold,fontSize=16.sp)
                            Text("العقل الثاني • مساعدك التنفيذي",color=MUTED,fontSize=11.sp) }
                        Text("+",color=GOLD,fontSize=29.sp,modifier=Modifier.clip(RoundedCornerShape(12.dp)).clickable{creatingTask=true}.padding(horizontal=9.dp))
                    }
                    when(screen) {
                        "home" -> HomeScreen(tasks,workspaces,projects,smartMode,onMode={smartMode=it;prefs.edit().putBoolean("smart_mode",it).apply()},
                            onCreate={creatingTask=true},onRecord=onRecord,recording=recording,
                            onTask={editor=it},onWorkspace={activeWorkspace=it;screen="tasks"},onReports={screen="reports"},onAssistant={screen="assistant"})
                        "workspaces" -> WorkspaceScreen(workspaces,projects,tasks,onSelect={activeWorkspace=it;screen="tasks"},
                            onNew={newWorkspace=true},onProject={newProjectIn=it})
                        "tasks" -> TasksScreen(tasks,workspaces,projects,activeWorkspace,
                            onWorkspace={activeWorkspace=it},onTask={editor=it},onCreate={creatingTask=true},
                            onNewProject={newProjectIn=if(activeWorkspace=="all") "" else activeWorkspace},
                            onNewTopic={newTopicIn=""})
                        "reports" -> ReportScreen(db,workspaces,tasks,projects,revision,onRefresh={reload()},onShare=onShare,smartMode=smartMode,api=smartClient)
                        "assistant" -> AssistantScreen(db,smartMode,smartClient,onSaved={reload()},onGoSettings={screen="settings"})
                        "settings" -> SettingsScreen(db,smartMode,onToggle={smartMode=it;prefs.edit().putBoolean("smart_mode",it).apply()},onBackup=onBackup,onAssistant={screen="assistant"},lockEnabled=lockEnabled,onLockToggle=onLockToggle)
                    }
                }
            }
            if(creatingTask||editor!=null) TaskEditor(task=editor,workspaces=workspaces,projects=projects,topics=topics,
                onDismiss={creatingTask=false;editor=null},onSave={t->saveTask(t);creatingTask=false;editor=null},
                onAttach={id->onAttach(id)},
                history=editor?.let { db.events(it.id) } ?: emptyList(),
                attachments=editor?.let { db.attachmentInfos(it.id) } ?: emptyList(),
                onOpenAttachment=onOpenAttachment,onDeleteAttachment={a->db.deleteAttachment(a.id);reload()},
                onDelete={id->db.deleteTask(id);reload();editor=null})
            if(newWorkspace) NameDialog("مساحة عمل جديدة",onClose={newWorkspace=false},onSave={name ->db.addWorkspace(name);reload();newWorkspace=false})
            newProjectIn?.let { w -> ParentNameDialog("مشروع جديد","المساحة",workspaces.map{it.id to it.name},w,
                onClose={newProjectIn=null},onSave={name,parent->db.addProject(name,parent);reload();newProjectIn=null}) }
            newTopicIn?.let { p -> ParentNameDialog("موضوع جديد","المشروع",
                projects.map{pr->pr.id to ((workspaces.firstOrNull{it.id==pr.workspaceId}?.name?:"")+" › "+pr.name)},p,
                emptyMessage="أنشئ مشروعًا أولًا، ثم أضف الموضوع إليه.",
                onClose={newTopicIn=null},onSave={name,parent->db.addTopic(name,parent);reload();newTopicIn=null}) }
        }
    }
}

// Access to context remains strictly local; a narrow adapter avoids passing Activity into UI state.
private fun dbContextPreferences(db:BrainDatabase) = db.contextForScheduler().getSharedPreferences("secondbrain_settings",0)

@Composable private fun GoldButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier){Button(onClick=onClick,modifier=modifier,
    colors=ButtonDefaults.buttonColors(containerColor=GOLD,contentColor=BG),shape=RoundedCornerShape(12.dp)) {Text(text,fontWeight=FontWeight.Bold)} }
@Composable private fun LineButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier){OutlinedButton(onClick=onClick,modifier=modifier,
    border=BorderStroke(1.dp,LINE),colors=ButtonDefaults.outlinedButtonColors(contentColor=TEXT),shape=RoundedCornerShape(12.dp)){Text(text)} }
@Composable private fun Panel(modifier:Modifier=Modifier, content:@Composable ColumnScope.()->Unit){
    Column(modifier.clip(rounded).background(Brush.linearGradient(listOf(Color(0xFF183141),Color(0xFF0E202C),Color(0xFF0B1C27))))
        .border(0.75.dp,Color(0xFF344B58),rounded).padding(14.dp),content=content)
}
@Composable private fun Heading(title:String,detail:String="") { Row(Modifier.fillMaxWidth().padding(bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
    Text(title,color=TEXT,fontWeight=FontWeight.Bold,fontSize=17.sp,modifier=Modifier.weight(1f));if(detail.isNotBlank())Text(detail,color=MUTED,fontSize=11.sp)
} }
@Composable private fun TaskLine(task:BrainTask,projects:List<Project>,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF122B39))
        .border(1.dp,Color(0xFF324B58),RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(12.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(8.dp).clip(CircleShape).background(when(task.status){"done"->GREEN;"wait","approval"->Color(0xFFB59BE8);"blocked"->RED;else->GOLD}))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)){
            Text(task.title,fontWeight=FontWeight.Bold,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(listOfNotNull(projects.firstOrNull{it.id==task.projectId}?.name,TaskStatus.label(task.status),iso(task.dueAt).ifBlank{null}).joinToString(" • "),fontSize=10.sp,color=MUTED)
        }
        Text("‹",color=GOLD,fontSize=25.sp)
    }
}
@Composable private fun Metric(label:String,number:Int,symbol:String,tint:Color,modifier:Modifier=Modifier){
    Row(modifier.clip(rounded).background(Brush.linearGradient(listOf(tint.copy(alpha=.25f),PANEL))).border(1.dp,LINE,rounded).padding(14.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text("$number",fontSize=26.sp,fontWeight=FontWeight.ExtraBold);Text(label,fontSize=11.sp,color=Color(0xFFD6DFE3))}
        Text(symbol,color=tint,fontSize=23.sp)
    }
}

@Composable private fun HomeScreen(tasks:List<BrainTask>,workspaces:List<Workspace>,projects:List<Project>,smart:Boolean,
    onMode:(Boolean)->Unit,onCreate:()->Unit,onRecord:()->Unit,recording:Boolean,onTask:(BrainTask)->Unit,
    onWorkspace:(String)->Unit,onReports:()->Unit,onAssistant:()->Unit){
    val open=tasks.filter{TaskStatus.isOpen(it.status)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=17.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp)).background(Color(0xFF081B26))
            .border(1.dp,Color(0xFF9A7043),RoundedCornerShape(21.dp))) {
            Column {
                Box(Modifier.fillMaxWidth().height(116.dp)) {
                    Image(painterResource(R.drawable.mountain_header),contentDescription="جبال وشروق الشمس",contentScale=ContentScale.Crop,
                        modifier=Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x7706141C),Color.Transparent,Color(0xFF091925)))))
                    Column(Modifier.align(Alignment.TopEnd).padding(top=17.dp,end=16.dp)) {
                        Text("مرحبًا بك",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
                        Text("ماذا تريد أن تسجل الآن؟",fontSize=11.sp,color=Color(0xFFFFE3BA))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                    if(smart)GoldButton("✧ الوضع الذكي",onClick={onMode(false)},Modifier.weight(1f))
                    else LineButton("✧ الوضع الذكي",onClick={onMode(true)},Modifier.weight(1f))
                    if(!smart)GoldButton("▱ الوضع العادي",onClick={onMode(false)},Modifier.weight(1f))
                    else LineButton("▱ الوضع العادي",onClick={onMode(false)},Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(20.dp));Heading("نظرة سريعة","مباشر من بياناتك")
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Metric("مهام اليوم",open.count { it.dueAt!=null&&it.dueAt<=System.currentTimeMillis()+86400000 },"▦",GREEN,Modifier.weight(1f))
            Metric("بانتظار رد",open.count { it.status=="wait"||it.status=="approval" },"◷",Color(0xFFB49ADF),Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Metric("تحتاج قرار",open.count { it.status=="blocked"||it.status=="approval" },"♧",GOLD,Modifier.weight(1f))
            Metric("متأخرة",open.count{it.dueAt!=null&&it.dueAt<System.currentTimeMillis()},"⚠",RED,Modifier.weight(1f))
        }
        Spacer(Modifier.height(21.dp));Heading("أولويات اليوم")
        Panel(Modifier.fillMaxWidth()) {
            if(open.isEmpty())Text("لا توجد مهام مفتوحة. أضف أول مهمة من الزر أدناه.",color=MUTED,fontSize=12.sp)
            else open.take(5).forEach { TaskLine(it,projects){onTask(it)} }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                LineButton("＋ إضافة",onCreate)
                Box(Modifier.size(72.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF29505B),Color(0xFF102530),Color(0xFF0A1A24))))
                    .border(2.5.dp,GOLD,CircleShape).clickable(onClick=onRecord),contentAlignment=Alignment.Center){
                    Text(if(recording)"■" else "🎙",fontSize=32.sp,color=GOLD_PALE)
                }
                LineButton("✧ محادثة",onAssistant)
            }
            Text(if(recording)"جارٍ التسجيل — اضغط مرة أخرى للإيقاف" else "تسجيل صوتي محلي بدون اتصال سحابي",fontSize=10.sp,color=MUTED,
                modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
        }
        Spacer(Modifier.height(21.dp));Heading("مساحات العمل")
        workspaces.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(bottom=8.dp).clip(rounded).background(Brush.linearGradient(listOf(Color(0xFF193B4A),PANEL)))
                .border(1.dp,LINE,rounded).clickable{onWorkspace(w.id)}.padding(15.dp),verticalAlignment=Alignment.CenterVertically){
                Text("◈",color=GOLD,fontSize=27.sp);Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)){Text(w.name,fontWeight=FontWeight.Bold);Text(w.description,color=MUTED,fontSize=10.sp)}
                Text("${tasks.count{it.workspaceId==w.id&&TaskStatus.isOpen(it.status)}}",color=GOLD,fontWeight=FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(22.dp))
        FooterCredit()
        Spacer(Modifier.height(14.dp))
    }
}

@Composable private fun WorkspaceScreen(workspaces:List<Workspace>,projects:List<Project>,tasks:List<BrainTask>,
    onSelect:(String)->Unit,onNew:()->Unit,onProject:(String)->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(17.dp)) {
        Heading("مساحات العمل","بياناتك محفوظة على جهازك")
        workspaces.forEach { w ->
            val tint=when(w.id){"view"->GOLD;"tech"->Color(0xFFC88DEB);"consult"->Color(0xFF98B7C8);"personal"->Color(0xFFDEB06E);else->GOLD}
            Column(Modifier.fillMaxWidth().padding(bottom=11.dp).clip(rounded)
                .background(Brush.horizontalGradient(listOf(tint.copy(alpha=.25f),PANEL,Color(0xFF0B1B27))))
                .border(1.dp,if(w.id=="view")GOLD.copy(alpha=.8f) else LINE,rounded).padding(15.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha=.14f)),contentAlignment=Alignment.Center) {
                        Text(when(w.id){"view"->"▣";"tech"->"⌘";"consult"->"◇";"personal"->"♧";else->"◈"},fontSize=28.sp,color=tint)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)){
                        Text(w.name,fontSize=17.sp,fontWeight=FontWeight.ExtraBold)
                        Text(w.description,color=MUTED,fontSize=11.sp)
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text("${projects.count{it.workspaceId==w.id}} مشروع • ${tasks.count{it.workspaceId==w.id}} مهمة",color=MUTED,fontSize=11.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                    GoldButton("فتح المساحة",{onSelect(w.id)})
                    LineButton("＋ مشروع",{onProject(w.id)})
                }
            }
        }
        LineButton("＋ إنشاء مساحة جديدة",onNew,Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Heading("آخر النشاطات")
        Panel(Modifier.fillMaxWidth()) {
            tasks.sortedByDescending { it.updatedAt }.take(4).forEach { t ->
                Text("◈  ${t.title}",fontSize=12.sp,color=TEXT,modifier=Modifier.padding(vertical=7.dp))
                HorizontalDivider(color=LINE.copy(alpha=.55f))
            }
            if(tasks.isEmpty())Text("ستظهر هنا أحدث المهام والتحديثات المسجلة.",color=MUTED,fontSize=12.sp)
        }
        Spacer(Modifier.height(20.dp))
        FooterCredit()
    }
}

@Composable private fun TasksScreen(tasks:List<BrainTask>,workspaces:List<Workspace>,projects:List<Project>,selectedWorkspace:String,
    onWorkspace:(String)->Unit,onTask:(BrainTask)->Unit,onCreate:()->Unit,onNewProject:()->Unit,onNewTopic:()->Unit){
    var query by remember { mutableStateOf("") };var status by remember { mutableStateOf("all") }
    val shown=tasks.filter { (selectedWorkspace=="all"||it.workspaceId==selectedWorkspace) &&
        (status=="all"||it.status==status) && (query.isBlank()||it.title.contains(query,true)||it.description.contains(query,true)) }
    Column(Modifier.fillMaxSize().padding(horizontal=17.dp)) {
        Heading("المهام والمتابعات","${shown.size} بند")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {GoldButton("＋ مهمة",onCreate);LineButton("＋ مشروع",onNewProject);LineButton("＋ موضوع",onNewTopic)}
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("بحث محلي في المهام")},singleLine=true,
            colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=GOLD,unfocusedBorderColor=LINE))
        LazyRow(contentPadding=PaddingValues(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            item { FilterChip(selectedWorkspace=="all",{onWorkspace("all")},label={Text("الكل")}) }
            items(workspaces){w->FilterChip(selectedWorkspace==w.id,{onWorkspace(w.id)},label={Text(w.name)})}
        }
        LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            item{FilterChip(status=="all",{status="all"},label={Text("كل الحالات")})}
            items(TaskStatus.labels.toList()) {(id,name)->FilterChip(status==id,{status=id},label={Text(name)})}
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding=PaddingValues(bottom=24.dp)) {
            if(shown.isEmpty()) item {Text("لا توجد مهام مطابقة.",color=MUTED,modifier=Modifier.padding(25.dp))}
            items(shown,key={it.id}){t->TaskLine(t,projects){onTask(t)}}
        }
    }
}

@Composable private fun TaskEditor(task:BrainTask?,workspaces:List<Workspace>,projects:List<Project>,topics:List<Topic>,
    onDismiss:()->Unit,onSave:(BrainTask)->Unit,onAttach:(String)->Unit,onDelete:(String)->Unit,history:List<String>,attachments:List<AttachmentInfo>,
    onOpenAttachment:(AttachmentInfo)->Unit,onDeleteAttachment:(AttachmentInfo)->Unit){
    var title by remember(task?.id){mutableStateOf(task?.title?:"")};var details by remember(task?.id){mutableStateOf(task?.description?:"")}
    var workspace by remember(task?.id){mutableStateOf(task?.workspaceId?:workspaces.firstOrNull()?.id.orEmpty())}
    var project by remember(task?.id){mutableStateOf(task?.projectId?:"")};var topic by remember(task?.id){mutableStateOf(task?.topicId?:"")}
    var status by remember(task?.id){mutableStateOf(task?.status?:"todo")};var priority by remember(task?.id){mutableStateOf(task?.priority?:"normal")}
    var owner by remember(task?.id){mutableStateOf(task?.owner?:"")};var due by remember(task?.id){mutableStateOf(task?.dueAt)}
    var follow by remember(task?.id){mutableStateOf(task?.followUpAt)}
    val context=LocalContext.current
    var confirmDelete by remember{mutableStateOf(false)}
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest=onDismiss){Surface(shape=RoundedCornerShape(21.dp),color=PANEL,modifier=Modifier.fillMaxWidth().heightIn(max=690.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
            Heading(if(task==null)"إضافة مهمة جديدة" else "تعديل ومتابعة المهمة")
            OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text("عنوان المهمة *")},singleLine=true)
            OutlinedTextField(details,{details=it},Modifier.fillMaxWidth(),label={Text("التفاصيل وآخر المستجدات")},minLines=2,maxLines=5)
            Text("مساحة العمل",color=MUTED,fontSize=12.sp)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(workspaces){w->FilterChip(workspace==w.id,{workspace=w.id;project="";topic=""},label={Text(w.name,fontSize=11.sp)})}}
            val possibleProjects=projects.filter{it.workspaceId==workspace}
            if(possibleProjects.isNotEmpty()){
                Text("المشروع",color=MUTED,fontSize=12.sp)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    item{FilterChip(project.isEmpty(),{project="";topic=""},label={Text("عام")})}
                    items(possibleProjects){p->FilterChip(project==p.id,{project=p.id;topic=""},label={Text(p.name,fontSize=11.sp)})}
                }
            }
            val possibleTopics=topics.filter{it.projectId==project}
            if(possibleTopics.isNotEmpty()){
                Text("الموضوع",color=MUTED,fontSize=12.sp)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {items(possibleTopics){t->FilterChip(topic==t.id,{topic=t.id},label={Text(t.name,fontSize=11.sp)})}}
            }
            Text("الحالة",color=MUTED,fontSize=12.sp)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(TaskStatus.labels.toList()){(id,name)->FilterChip(status==id,{status=id},label={Text(name,fontSize=11.sp)})}}
            Text("الأولوية",color=MUTED,fontSize=12.sp)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(listOf("low" to "منخفضة","normal" to "عادية","high" to "عالية","critical" to "حرجة")){(id,name)->FilterChip(priority==id,{priority=id},label={Text(name)})}}
            OutlinedTextField(owner,{owner=it},Modifier.fillMaxWidth(),label={Text("المسؤول / الشخص المنتظر منه الرد")})
            DateTimeField("موعد الاستحقاق",due,{pickDateTime(context,due){due=it}},{due=null})
            DateTimeField("موعد المتابعة (تذكير)",follow,{pickDateTime(context,follow){follow=it}},{follow=null})
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                item{AssistChip({follow=System.currentTimeMillis()+3_600_000L},{Text("تذكير بعد ساعة",fontSize=11.sp)})}
                item{AssistChip({follow=tomorrowAt9()},{Text("بكرة 9 ص",fontSize=11.sp)})}
                item{AssistChip({follow=nextSundayAt9()},{Text("الأحد القادم 9 ص",fontSize=11.sp)})}
            }
            if(task!=null){
                LineButton("📎 إضافة مرفق من الجهاز",{onAttach(task.id)})
                attachments.forEach{a->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text("📎 ${a.name}",color=GOLD_PALE,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.weight(1f).clickable{onOpenAttachment(a)}.padding(vertical=6.dp))
                    Text("✕",color=RED,fontSize=16.sp,modifier=Modifier.clickable{onDeleteAttachment(a)}.padding(8.dp))
                }}
                if(history.isNotEmpty()){
                    Text("سجل الإجراءات",fontWeight=FontWeight.Bold,color=GOLD)
                    history.take(6).forEach { Text("• $it",color=MUTED,fontSize=11.sp) }
                }
            }
            if(error.isNotBlank())Text(error,color=RED,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                GoldButton("حفظ",{
                    if(title.isBlank())error="عنوان المهمة مطلوب"
                    else onSave(BrainTask(
                    id=task?.id?:UUID.randomUUID().toString(),title=title.trim(),description=details,
                    workspaceId=workspace,projectId=project,topicId=topic,status=status,priority=priority,
                    owner=owner,dueAt=due,followUpAt=follow,createdAt=task?.createdAt?:System.currentTimeMillis()))})
                LineButton("إلغاء",onDismiss)
                if(task!=null) LineButton("حذف",{confirmDelete=true})
                if(confirmDelete&&task!=null) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("حذف المهمة؟")},
                    text={Text("ستُحذف المهمة وسجلها ومرفقاتها من الجهاز نهائيًا.")},
                    confirmButton={TextButton({onDelete(task.id)}){Text("حذف",color=RED)}},
                    dismissButton={TextButton({confirmDelete=false}){Text("إلغاء")}},containerColor=PANEL)
            }
        }
    }}
}

@Composable private fun DateTimeField(label:String,value:Long?,onPick:()->Unit,onClear:()->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp,LINE,RoundedCornerShape(12.dp))
        .clickable(onClick=onPick).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){
            Text(label,color=MUTED,fontSize=11.sp)
            Text(if(value==null)"غير محدد — اضغط للاختيار" else fmtDateTime(value),color=if(value==null)MUTED else TEXT,fontSize=13.sp)
        }
        if(value!=null)Text("✕",color=RED,fontSize=16.sp,modifier=Modifier.clickable(onClick=onClear).padding(8.dp))
    }
}

/** Name + explicit parent (workspace for a project, project for a topic) so nothing is attached to a guess. */
@Composable private fun ParentNameDialog(title:String,parentLabel:String,parents:List<Pair<String,String>>,initial:String,
    emptyMessage:String="",onClose:()->Unit,onSave:(String,String)->Unit){
    var name by remember { mutableStateOf("") }
    // An explicit chip selection is required unless the caller opened a specific existing parent.
    var parent by remember(initial) { mutableStateOf(parents.firstOrNull{it.first==initial}?.first ?: "") }
    AlertDialog(onDismissRequest=onClose,title={Text(title)},containerColor=PANEL,
        text={
            if(parents.isEmpty()) Text(emptyMessage.ifBlank{"لا توجد عناصر متاحة."},color=MUTED)
            else Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                OutlinedTextField(name,{name=it},label={Text("الاسم")},singleLine=true)
                Text("اختر $parentLabel صراحةً قبل الحفظ",color=MUTED,fontSize=12.sp)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    items(parents){(id,label)->FilterChip(parent==id,{parent=id},label={Text(label,fontSize=11.sp)})}
                }
            }
        },
        confirmButton={TextButton(enabled=name.isNotBlank()&&parent.isNotBlank(),onClick={onSave(name.trim(),parent)}){Text("حفظ",color=GOLD)}},
        dismissButton={TextButton(onClick=onClose){Text("إلغاء")}})
}

@Composable private fun NameDialog(title:String,onClose:()->Unit,onSave:(String)->Unit){
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onClose,title={Text(title)},text={OutlinedTextField(name,{name=it},label={Text("الاسم")})},
        confirmButton={TextButton(onClick={if(name.isNotBlank())onSave(name.trim())}){Text("حفظ",color=GOLD)}},
        dismissButton={TextButton(onClick=onClose){Text("إلغاء")}},containerColor=PANEL)
}

@Composable private fun ReportScreen(db:BrainDatabase,workspaces:List<Workspace>,tasks:List<BrainTask>,projects:List<Project>,revision:Int,
    onRefresh:()->Unit,onShare:(String)->Unit,smartMode:Boolean,api:SmartApiClient){
    var selected by remember{mutableStateOf("view")};var report by remember{mutableStateOf("")}
    var comparison by remember{mutableStateOf<String?>(null)}
    var aiBusy by remember{mutableStateOf(false)}
    var aiError by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    val snapshots=remember(revision,selected){db.snapshots(selected)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(17.dp)){
        Heading("تقرير الإدارة","نصي فقط • واتساب")
        Panel(Modifier.fillMaxWidth()) {
            Text("مساحة العمل",color=MUTED,fontSize=12.sp)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                items(workspaces){w->FilterChip(selected==w.id,{selected=w.id;report=""},label={Text(w.name,fontSize=11.sp)})}
            }
            Spacer(Modifier.height(8.dp))
            GoldButton("✧ إنشاء ملخص محلي",{
                workspaces.find{it.id==selected}?.let { report=ReportEngine.generate(it,tasks,projects,System.currentTimeMillis()) }
            },Modifier.fillMaxWidth())
            if(smartMode) {
                Spacer(Modifier.height(8.dp))
                LineButton(if(aiBusy)"جارٍ تحسين التقرير..." else "✦ صياغة إدارية ذكية بالـ API",{
                    if(report.isNotBlank()&&!aiBusy){
                        aiBusy=true;aiError=""
                        scope.launch {
                            try { report=api.ask(report.take(4500),instructions="أعد صياغة التقرير الإداري التالي بصيغة واتساب عربية مختصرة ومنظمة. لا تخترع معلومات أو حالات غير مسجلة. أخرج النص النهائي فقط.") }
                            catch(e:Exception){aiError=e.message.orEmpty()}
                            finally{aiBusy=false}
                        }
                    }
                },Modifier.fillMaxWidth())
                if(aiError.isNotBlank())Text(aiError,color=RED,fontSize=11.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(report,{report=it},Modifier.fillMaxWidth().heightIn(min=240.dp),label={Text("نص التقرير — قابل للتعديل")})
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                LineButton("↗ مشاركة",{if(report.isNotBlank())onShare(report)},Modifier.weight(1f))
                GoldButton("▤ حفظ Snapshot",{
                    if(report.isNotBlank()){
                        val w=workspaces.first{it.id==selected};db.saveSnapshot(Snapshot(UUID.randomUUID().toString(),selected,
                            "تقرير ${w.name} — ${fmt(System.currentTimeMillis())}",report,System.currentTimeMillis(),ReportEngine.snapshotItems(tasks,selected)))
                        onRefresh()
                    }
                },Modifier.weight(1f))
            }
            Text("التقرير المحلي لا يحتاج API. تحسين الصياغة اختياري في الوضع الذكي ويرسل نص التقرير فقط؛ فتح المشاركة لا يؤكد الإرسال.",color=MUTED,fontSize=10.sp)
        }
        Spacer(Modifier.height(18.dp));Heading("الأرشيف التنفيذي","${snapshots.size} إصدار محفوظ")
        snapshots.forEach { s->
            Panel(Modifier.fillMaxWidth().padding(bottom=10.dp)) {
                Text(s.title,fontWeight=FontWeight.Bold,fontSize=13.sp)
                Text("${fmt(s.createdAt)} • ${s.items.size} بند محفوظ",fontSize=11.sp,color=MUTED)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    LineButton("عرض",{comparison=s.text})
                    LineButton("مقارنة بالحالي",{
                        val diff=ReportEngine.compare(s,tasks)
                        comparison=buildString{
                            appendLine("مقارنة مع ${s.title}")
                            appendLine("تغيرت الحالة: ${diff.changed.size}")
                            appendLine("بنود جديدة: ${diff.added.size}")
                            appendLine("لم تتغير الحالة: ${diff.unchangedCount}")
                            appendLine("محذوفة: ${diff.removed.size}")
                            diff.changed.forEach{appendLine("• $it")}
                            diff.added.forEach{appendLine("+ $it")}
                            diff.removed.forEach{appendLine("- $it")}
                        }
                    })
                }
            }
        }
    }
    comparison?.let{value->AlertDialog(onDismissRequest={comparison=null},title={Text("Executive Snapshot")},text={Text(value,modifier=Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick={comparison=null}){Text("إغلاق",color=GOLD)}},containerColor=PANEL)}
}

@Composable private fun SettingsScreen(db:BrainDatabase,smart:Boolean,onToggle:(Boolean)->Unit,onBackup:()->Unit,onAssistant:()->Unit,
    lockEnabled:Boolean,onLockToggle:(Boolean)->Unit){
    val ctx=db.contextForScheduler()
    val vault=remember{ApiKeyVault(ctx)}
    val budget=remember{BudgetGuard(ctx)}
    var keyInput by remember{mutableStateOf("")}
    var hasKey by remember{mutableStateOf(vault.exists())}
    var keyNotice by remember{mutableStateOf("")}
    val (count,bytes)=remember { db.storageUsage() }
    val legacyUnmigrated=remember { db.unresolvedLegacyAttachmentCount() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(17.dp)) {
        Heading("الإعدادات","خصوصيتك أولًا")
        Panel(Modifier.fillMaxWidth()){
            Text("نظام التشغيل",fontWeight=FontWeight.Bold,fontSize=17.sp)
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){
                Text(if(smart)"الوضع الذكي (API اختياري)" else "الوضع العادي",fontWeight=FontWeight.Bold)
                Text("التبديل لا يؤثر على بياناتك",color=MUTED,fontSize=11.sp)
            };Switch(checked=smart,onCheckedChange=onToggle,colors=SwitchDefaults.colors(checkedThumbColor=BG,checkedTrackColor=GOLD))}
            Text(if(smart)"تُرسل المعلومات فقط عند ضغط أمر تحليل أو محادثة أو تفريغ صوت." else "لن يرسل الوضع العادي أي طلبات إلى API.",fontSize=12.sp,color=GOLD_PALE)
            Spacer(Modifier.height(8.dp))
            GoldButton("✦ فتح المساعد الذكي",onAssistant,Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(13.dp))
        Panel(Modifier.fillMaxWidth()){
            Text("قفل التطبيق",fontWeight=FontWeight.Bold,fontSize=17.sp)
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){
                Text(if(lockEnabled)"القفل مفعّل" else "القفل غير مفعّل",fontWeight=FontWeight.Bold)
                Text("بصمة أو رمز الهاتف عند الفتح، وبعد دقيقة من الخروج من التطبيق",color=MUTED,fontSize=11.sp)
            };Switch(checked=lockEnabled,onCheckedChange=onLockToggle,colors=SwitchDefaults.colors(checkedThumbColor=BG,checkedTrackColor=GOLD))}
            Text("عند التفعيل تُخفى معاينة التطبيق في قائمة التطبيقات الأخيرة ويُمنع تصوير الشاشة. القفل يحمي فتح التطبيق فقط ولا يشفّر ملفات البيانات.",color=MUTED,fontSize=11.sp)
        }
        Spacer(Modifier.height(13.dp))
        Panel(Modifier.fillMaxWidth()){
            Text("التخزين المحلي",fontWeight=FontWeight.Bold,fontSize=17.sp)
            Text("الموقع: ${db.rootFolder().absolutePath}",fontSize=10.sp,color=MUTED)
            Text("عدد الملفات: $count • الحجم: ${"%.2f".format(bytes/1048576.0)} MB",fontSize=12.sp)
            Text("البيانات في ملف SQLite داخل مساحة التطبيق الخاصة، والمرفقات والتسجيلات في مجلدي Attachments وVoice داخلها (لا تظهر في تطبيق الملفات). للوصول إليها أنشئ نسخة ZIP.",fontSize=12.sp,color=MUTED)
            if(legacyUnmigrated>0) Text("تنبيه: يوجد $legacyUnmigrated مرفقات قديمة لم يكتمل نقلها. احتفظ بالنسخة القديمة من البيانات حتى تراجع هذه الملفات.",fontSize=12.sp,color=RED)
            Text("لا تشفير إضافيًا لبياناتك، ولا سحابة، والنسخ الاحتياطي التلقائي معطّل. يتم حذف مساحة التطبيق عند إزالته.",fontSize=11.sp,color=MUTED)
            Spacer(Modifier.height(8.dp))
            LineButton("↓ إنشاء نسخة ZIP محلية بدون تشفير",onBackup,Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(13.dp))
        Panel(Modifier.fillMaxWidth()){
            Text("اتصال OpenAI API",fontWeight=FontWeight.Bold,fontSize=16.sp)
            Text(if(hasKey)"مفتاح API محفوظ على الجهاز ومحمِي بـ Android Keystore" else "لا يوجد مفتاح API محفوظ",color=GOLD_PALE,fontSize=12.sp)
            OutlinedTextField(keyInput,{keyInput=it},Modifier.fillMaxWidth(),label={Text("الصق مفتاح API الخاص بك")},
                visualTransformation=PasswordVisualTransformation(),singleLine=true)
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                GoldButton("حفظ المفتاح",{
                    try {vault.save(keyInput);keyInput="";hasKey=true;keyNotice="تم حفظ المفتاح محليًا"}
                    catch(e:Exception){keyNotice=e.message.orEmpty()}
                },Modifier.weight(1f))
                LineButton("مسح المفتاح",{vault.clear();keyInput="";hasKey=false;keyNotice="تم حذف مفتاح API"},Modifier.weight(1f))
            }
            if(keyNotice.isNotBlank())Text(keyNotice,color=GOLD_PALE,fontSize=11.sp)
            Spacer(Modifier.height(9.dp))
            Text("المصروف المحلي التقديري هذا الشهر: ${"%.3f".format(Locale.US,budget.usedSar())} من ${budget.internalLimitSar.toInt()} ريال (هامش للوصول إلى 20 ريال)",color=TEXT,fontSize=12.sp)
            Text("ملاحظة: الرصيد المستهلك محليًا تقديري وليس فاتورة المزود؛ حدد سقفًا عند مزود API وأوقف إعادة التعبئة التلقائية. التطبيق لا يضمن سقف الفاتورة وحده.",color=MUTED,fontSize=11.sp)
            Text("التطبيق يتصل مباشرةً بـ OpenAI بدون خادم وسيط. وجود المفتاح على الهاتف مخاطرة مالية متبقية إذا تم اختراقه.",color=GOLD_PALE,fontSize=10.sp)
        }
        Spacer(Modifier.height(18.dp))
        FooterCredit()
    }
}


@Composable private fun FooterCredit(){
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        HorizontalDivider(color=GOLD.copy(alpha=.34f),thickness=.7.dp)
        Spacer(Modifier.height(9.dp))
        Text("© 2026 جميع الحقوق محفوظة — محمود الجمصي",color=GOLD_PALE,fontSize=10.sp,textAlign=TextAlign.Center)
        Text("Mahmoud Elgamsy   |   جوال: 056228419",color=MUTED,fontSize=10.sp,textAlign=TextAlign.Center)
    }
}

@Composable private fun AssistantScreen(db:BrainDatabase,smart:Boolean,api:SmartApiClient,onSaved:()->Unit,onGoSettings:()->Unit){
    var text by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var proposed by remember { mutableStateOf<List<BrainTask>>(emptyList()) }
    var selectedSpace by remember { mutableStateOf("view") }
    val scope=rememberCoroutineScope()
    val spaces=remember { db.workspaces() }
    val keyReady=remember { ApiKeyVault(db.contextForScheduler()).exists() }
    val lastVoice=remember { db.voiceDir().listFiles()?.filter { it.isFile&&it.extension.lowercase()=="m4a" }?.maxByOrNull { it.lastModified() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(17.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Heading("المساعد التنفيذي الذكي", "AI عند طلبك فقط")
        Panel(Modifier.fillMaxWidth()) {
            Text("خصوصية وتحكم",fontWeight=FontWeight.Bold,color=GOLD_PALE)
            Text("يتم إرسال النص الذي تختاره فقط عند الضغط على زر التحليل. كل المهام والنتائج تُحفظ على الجهاز؛ لا ترفع قاعدة البيانات أو الملفات تلقائيًا.",color=MUTED,fontSize=11.sp)
            if(!smart) Text("الوضع العادي مفعل: شغّل الوضع الذكي أولًا.",color=GOLD,fontSize=12.sp)
            if(!keyReady) LineButton("ضبط مفتاح API",onGoSettings)
        }
        Text("مساحة العمل المقترحة للمهام الجديدة",color=MUTED,fontSize=12.sp)
        LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            items(spaces){w ->FilterChip(selectedSpace==w.id,{selectedSpace=w.id},label={Text(w.name,fontSize=11.sp)})}
        }
        OutlinedTextField(text,{text=it},Modifier.fillMaxWidth().heightIn(min=140.dp),
            label={Text("اكتب طلبك أو النص المستخرج من التسجيل")},placeholder={Text("تابع مع الاستشاري الأحد وبعت عرض السعر لإبراهيم التويم...")})
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            LineButton(if(loading)"جارٍ المعالجة..." else "✦ استخرج المهام",{
                if(!loading&&smart&&text.isNotBlank()) {
                    loading=true;error="";answer="";proposed=emptyList()
                    scope.launch{
                        try {
                            val raw=api.ask(text.take(4500),instructions="""
                                أنت نظام استخراج مهام عربية. أرجع كائن JSON فقط بدون Markdown وفق الصيغة:
                                {"tasks":[{"title":"","description":"","status":"todo","priority":"normal","owner":"","due_date":"YYYY-MM-DD","follow_up_date":"YYYY-MM-DD"}],"summary":""}.
                                استخرج ما يصل إلى 8 مهام مختلفة. الحالات المسموحة todo,progress,wait,approval,blocked,done.
                                لا تختلق مواعيد أو أسماء؛ ترك الحقول غير المؤكدة فارغة. تواريخ نسبية بالاعتماد على تاريخ اليوم الحالي.
                                التاريخ المحلي اليوم ${LocalDate.now()}.
                            """.trimIndent())
                            val json=JSONObject(raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
                            val arr=json.optJSONArray("tasks")
                            val output=mutableListOf<BrainTask>()
                            if(arr!=null)for(i in 0 until minOf(arr.length(),8)){
                                val x=arr.optJSONObject(i)?:continue
                                val title=x.optString("title").trim().take(120)
                                if(title.isEmpty())continue
                                val status=x.optString("status").takeIf{it in TaskStatus.labels}?:"todo"
                                val priority=x.optString("priority").takeIf{it in listOf("low","normal","high","critical")}?:"normal"
                                output.add(BrainTask(id=UUID.randomUUID().toString(),title=title,description=x.optString("description").take(1500),
                                    workspaceId=selectedSpace,status=status,priority=priority,owner=x.optString("owner").take(120),
                                    dueAt=fromIso(x.optString("due_date")),followUpAt=fromIso(x.optString("follow_up_date"))))
                            }
                            proposed=output;answer=json.optString("summary")
                            if(output.isEmpty())error="لم تُستخرج مهام مؤكدة؛ يمكنك تعديل النص والمحاولة مرة أخرى."
                        }catch(e:Exception){error=e.message.orEmpty()}
                        finally{loading=false}
                    }
                }
            },Modifier.weight(1f))
            GoldButton(if(loading)"جارٍ الرد..." else "المحادثة",{
                if(!loading&&smart&&text.isNotBlank()) {
                    loading=true;error="";proposed=emptyList()
                    scope.launch{
                        try {
                            val relevant=db.tasks().filter{it.workspaceId==selectedSpace}.take(25)
                                .joinToString("\n"){"${it.title} | ${TaskStatus.label(it.status)} | ${it.owner.take(40)} | ${iso(it.dueAt)}"}.take(4500)
                            answer=api.ask(text.take(3000),relevant)
                        }catch(e:Exception){error=e.message.orEmpty()}
                        finally{loading=false}
                    }
                }
            },Modifier.weight(1f))
        }
        if(lastVoice!=null) LineButton(if(loading)"جارٍ التفريغ..." else "🎙 تفريغ آخر تسجيل محفوظ إلى نص",{
            if(!loading&&smart){
                loading=true;error=""
                scope.launch{
                    try{text=api.transcribe(lastVoice)}catch(e:Exception){error=e.message.orEmpty()}
                    finally{loading=false}
                }
            }
        },Modifier.fillMaxWidth())
        if(!smart) Text("الوضع العادي لا يستخدم API؛ لن يحدث أي اتصال مدفوع.",color=GOLD_PALE,fontSize=12.sp)
        if(error.isNotBlank()) Text(error,color=RED,fontSize=12.sp)
        if(answer.isNotBlank())Panel(Modifier.fillMaxWidth()){
            Heading("رد المساعد")
            Text(answer,color=TEXT,fontSize=13.sp)
        }
        if(proposed.isNotEmpty())Panel(Modifier.fillMaxWidth()) {
            Heading("مراجعة قبل الحفظ", "${proposed.size} مهام مقترحة")
            proposed.forEach { task ->
                Text("• ${task.title}",color=TEXT,fontWeight=FontWeight.Bold,fontSize=13.sp,modifier=Modifier.padding(top=7.dp))
                Text("${TaskStatus.label(task.status)} | ${iso(task.dueAt).ifBlank { "بدون موعد" }} | ${task.owner}",color=MUTED,fontSize=10.sp)
            }
            Spacer(Modifier.height(12.dp))
            GoldButton("✓ اعتماد وحفظ ${proposed.size} مهام محليًا",{
                proposed.forEach { t-> db.putTask(t);ReminderScheduler.schedule(db.contextForScheduler(),t) }
                proposed=emptyList();answer="تم الحفظ في الذاكرة المحلية.";onSaved()
            },Modifier.fillMaxWidth())
            LineButton("إلغاء المقترحات",{proposed=emptyList()},Modifier.fillMaxWidth())
        }
        FooterCredit()
    }
}
