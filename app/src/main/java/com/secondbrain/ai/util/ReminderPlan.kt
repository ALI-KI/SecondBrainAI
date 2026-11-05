package com.secondbrain.ai.util

import com.secondbrain.ai.data.BrainTask
import com.secondbrain.ai.data.TaskStatus

/** Pure planning layer: a due date and a follow-up are *different* reminders, not one earliest alarm. */
data class ReminderSlot(val kind: String, val at: Long)
object ReminderPlan {
    const val FOLLOW_UP = "followup"
    const val DUE = "due"
    fun pending(task: BrainTask, now: Long): List<ReminderSlot> {
        if (!TaskStatus.isOpen(task.status)) return emptyList()
        return buildList {
            task.followUpAt?.takeIf { it > now }?.let { add(ReminderSlot(FOLLOW_UP,it)) }
            task.dueAt?.takeIf { it > now }?.let { add(ReminderSlot(DUE,it)) }
        }.sortedBy { it.at }
    }
    fun timestamp(task: BrainTask, kind: String): Long? = when (kind) {
        FOLLOW_UP -> task.followUpAt
        DUE -> task.dueAt
        else -> null
    }
}

