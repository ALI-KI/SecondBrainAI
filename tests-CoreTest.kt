import com.secondbrain.ai.data.*

fun main() {
    val w=Workspace("view","فيو المتحدة")
    val p=Project("tower","view","برج فيو تاور")
    val a=BrainTask("1","مراجعة نفق الرياح",workspaceId="view",projectId="tower",status="wait")
    val b=BrainTask("2","مهمة أخرى",workspaceId="personal",status="todo")
    val text=ReportEngine.generate(w,listOf(a,b),listOf(p),0L)
    check("مراجعة نفق الرياح" in text)
    check("بانتظار رد" in text)
    check("مهمة أخرى" !in text)
    val snap=Snapshot("s","view","تقرير",text,1L,ReportEngine.snapshotItems(listOf(a),"view"))
    val diff=ReportEngine.compare(snap,listOf(a.copy(status="done"),BrainTask("3","بند جديد",workspaceId="view")))
    check(diff.changed.size==1)
    check(diff.added.size==1)
    check(diff.unchangedCount==0)
    val stable=snap.text
    a.copy(status="done")
    check(snap.text==stable)
    println("PASS: local report scoped correctly, labels, snapshot comparison, immutability")
}
