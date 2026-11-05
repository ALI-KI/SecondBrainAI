package com.secondbrain.ai.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure local reporting: no network, no AI, no external dependencies. */
object ReportEngine {
    private fun date(ms: Long?): String = ms?.let {
        SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(it))
    } ?: "غير محدد"

    fun generate(workspace: Workspace, tasks: List<BrainTask>, projects: List<Project>, time: Long): String {
        val scoped = tasks.filter { it.workspaceId == workspace.id }
        val projectsById = projects.associateBy { it.id }
        return buildString {
            appendLine("*تقرير متابعة الأعمال — ${workspace.name}*")
            appendLine("التاريخ: ${date(time)}")
            appendLine()
            if (scoped.isEmpty()) appendLine("لا توجد مهام مسجلة في هذه المساحة.")
            for ((projectName, entries) in scoped.groupBy { projectsById[it.projectId]?.name ?: "بنود عامة" }) {
                appendLine("*$projectName*")
                for (task in entries.sortedBy { it.dueAt ?: Long.MAX_VALUE }) {
                    appendLine("• ${task.title}")
                    appendLine("  الحالة: ${TaskStatus.label(task.status)} | الاستحقاق: ${date(task.dueAt)}")
                    if (task.owner.isNotBlank()) appendLine("  المسؤول/جهة المتابعة: ${task.owner}")
                    if (task.followUpAt != null) appendLine("  المتابعة القادمة: ${date(task.followUpAt)}")
                    if (task.description.isNotBlank()) appendLine("  التفاصيل: ${task.description}")
                }
                appendLine()
            }
            append("*تنبيه:* مبني على آخر بيانات مسجلة داخل التطبيق، وقد تحتاج البنود القديمة إلى مراجعة.")
        }
    }

    fun snapshotItems(tasks: List<BrainTask>, workspaceId: String): List<SnapshotItem> =
        tasks.filter { it.workspaceId == workspaceId }.map { SnapshotItem(it.id, it.title, it.status, it.updatedAt) }

    fun compare(snapshot: Snapshot, current: List<BrainTask>): SnapshotDiff {
        val old = snapshot.items.associateBy { it.id }
        val scoped = current.filter { it.workspaceId == snapshot.workspaceId }
        val currentIds = scoped.map { it.id }.toSet()
        val changed = mutableListOf<String>()
        val added = mutableListOf<String>()
        var unchanged = 0
        for (task in scoped) {
            val past = old[task.id]
            if (past == null) added.add(task.title)
            else if (past.status != task.status)
                changed.add("${task.title}: ${TaskStatus.label(past.status)} ← ${TaskStatus.label(task.status)}")
            else unchanged++
        }
        val removed = snapshot.items.filter { it.id !in currentIds }.map { it.title }
        return SnapshotDiff(changed, added, removed, unchanged)
    }
}
