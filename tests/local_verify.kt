import com.secondbrain.ai.data.*
import com.secondbrain.ai.util.*
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
 println("PASS: 9 planner/history/report assertions")
}
