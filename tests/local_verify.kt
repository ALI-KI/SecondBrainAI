import com.secondbrain.ai.data.*
import com.secondbrain.ai.util.*
import com.secondbrain.ai.ai.WorkspaceRouter
import com.secondbrain.ai.ai.SuggestedDateTime
import com.secondbrain.ai.voice.VoiceCommand
fun main() {
 val w=Workspace("view","فيو")
 val p=Project("tower","view","برج")
 val t=BrainTask("1","متابعة تقرير",workspaceId="view",projectId="tower",status="wait",followUpAt=4000000L,dueAt=8000000L)
 check(ReminderPlan.pending(t,1000000L).map{it.kind}==listOf("followup","due"))
 check(ReminderPlan.pending(t,5000000L).single().kind=="due")
 check(ReminderPlan.pending(t.copy(status="done"),0L).isEmpty())
 check(ReminderPlan.timestamp(t,"bad")==null)
 check(TaskChanges.describe(t,t.copy()).isEmpty())
 check(TaskChanges.describe(t,t.copy(owner="م. أحمد")).size==1)
 val r=ReportEngine.generate(w,listOf(t),listOf(p),0L)
 check("متابعة تقرير" in r)
 val snap=Snapshot("id","view","weekly",r,1L,ReportEngine.snapshotItems(listOf(t),"view"))
 check(ReportEngine.compare(snap,listOf(t.copy(status="done"))).changed.size==1)
 check(WorkspaceRouter.route("اشتري طلبات البيت", "view").workspaceId=="personal")
 check(WorkspaceRouter.route("متابعة RWDI في برج فيو", "personal").workspaceId=="view")
 check(WorkspaceRouter.route("أتصل بالمورد الأحد", "view").needsReview)
 check(WorkspaceRouter.route("إرسال ملف فيو وحاجات البيت", "view").workspaceId==null)
 check(VoiceCommand.extract("تواصل مع المقاول الأحد وزع المهام") == ("تواصل مع المقاول الأحد" to true))
 check(VoiceCommand.extract("توزيع المهام الأسبوع القادم").second==false)
 check(SuggestedDateTime.parse("2026-11-08T11:00",java.time.ZoneId.of("Asia/Riyadh"))!=null)
 check(SuggestedDateTime.parse("غير محدد")==null)
 println("PASS: planner/history/report + workspace safety + voice-command assertions")
}
