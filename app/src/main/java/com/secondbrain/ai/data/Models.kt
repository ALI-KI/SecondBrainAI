package com.secondbrain.ai.data

data class Workspace(val id: String, val name: String, val description: String = "")
data class Project(val id: String, val workspaceId: String, val name: String)
data class Topic(val id: String, val projectId: String, val name: String)
data class BrainTask(
    val id: String, val title: String, val description: String = "", val workspaceId: String = "view",
    val projectId: String = "", val topicId: String = "", val status: String = "todo",
    val priority: String = "normal", val owner: String = "", val dueAt: Long? = null,
    val followUpAt: Long? = null, val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
data class SnapshotItem(val id: String, val title: String, val status: String, val updatedAt: Long)
data class Snapshot(val id: String, val workspaceId: String, val title: String, val text: String,
                    val createdAt: Long, val items: List<SnapshotItem>)
data class SnapshotDiff(val changed: List<String>, val added: List<String>,
                        val removed: List<String>, val unchangedCount: Int)
object TaskStatus {
    val labels = linkedMapOf("inbox" to "وارد جديد", "todo" to "مطلوب مني",
        "progress" to "قيد التنفيذ", "wait" to "بانتظار رد", "approval" to "بانتظار اعتماد",
        "blocked" to "معطل", "done" to "مكتمل", "cancelled" to "ملغى")
    fun label(status: String) = labels[status] ?: status
    fun isOpen(status: String) = status != "done" && status != "cancelled"
}

data class AttachmentInfo(val id: String, val taskId: String, val name: String, val path: String, val mime: String)

object TaskPriority {
    val labels = linkedMapOf("low" to "منخفضة", "normal" to "عادية", "high" to "عالية", "critical" to "حرجة")
    fun label(priority: String) = labels[priority] ?: priority
}

/** Pure Kotlin: describes only real changes between two versions of a task (used for the history log). */
object TaskChanges {
    private fun stamp(ms: Long?): String = ms?.let {
        java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US).format(java.util.Date(it))
    } ?: "غير محدد"
    private fun dash(s: String) = s.ifBlank { "—" }

    fun describe(old: BrainTask, new: BrainTask): List<String> {
        val out = mutableListOf<String>()
        if (old.status != new.status) out += "الحالة: ${TaskStatus.label(old.status)} ← ${TaskStatus.label(new.status)}"
        if (old.title != new.title) out += "تعديل العنوان"
        if (old.owner != new.owner) out += "المسؤول: ${dash(old.owner)} ← ${dash(new.owner)}"
        if (old.dueAt != new.dueAt) out += "الاستحقاق: ${stamp(old.dueAt)} ← ${stamp(new.dueAt)}"
        if (old.followUpAt != new.followUpAt) out += "المتابعة: ${stamp(old.followUpAt)} ← ${stamp(new.followUpAt)}"
        if (old.priority != new.priority) out += "الأولوية: ${TaskPriority.label(old.priority)} ← ${TaskPriority.label(new.priority)}"
        if (old.workspaceId != new.workspaceId || old.projectId != new.projectId || old.topicId != new.topicId) out += "نقل إلى مساحة/مشروع/موضوع آخر"
        if (old.description != new.description) out += "تحديث التفاصيل"
        return out
    }
}
