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
