package com.secondbrain.ai

import com.secondbrain.ai.data.BrainTask
import com.secondbrain.ai.data.Project
import com.secondbrain.ai.data.ReportEngine
import com.secondbrain.ai.data.Snapshot
import com.secondbrain.ai.data.TaskChanges
import com.secondbrain.ai.data.Workspace
import com.secondbrain.ai.util.ReminderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val view = Workspace("view", "فيو المتحدة")
    private val tower = Project("tower", "view", "برج فيو تاور")
    private val waiting = BrainTask("1", "مراجعة نفق الرياح", workspaceId = "view", projectId = "tower", status = "wait")
    private val other = BrainTask("2", "مهمة أخرى", workspaceId = "personal", status = "todo")

    @Test fun reportIsScopedToWorkspaceAndLabelsStatus() {
        val text = ReportEngine.generate(view, listOf(waiting, other), listOf(tower), 0L)
        assertTrue("مراجعة نفق الرياح" in text)
        assertTrue("بانتظار رد" in text)
        assertFalse("مهمة أخرى" in text)
    }

    @Test fun snapshotComparisonAndImmutability() {
        val text = ReportEngine.generate(view, listOf(waiting, other), listOf(tower), 0L)
        val snap = Snapshot("s", "view", "تقرير", text, 1L, ReportEngine.snapshotItems(listOf(waiting), "view"))
        val diff = ReportEngine.compare(snap, listOf(waiting.copy(status = "done"), BrainTask("3", "بند جديد", workspaceId = "view")))
        assertEquals(1, diff.changed.size)
        assertEquals(1, diff.added.size)
        assertEquals(0, diff.unchangedCount)
        val frozen = snap.text
        waiting.copy(status = "done")
        assertEquals(frozen, snap.text)
    }

    @Test fun savingWithoutChangesLogsNothing() {
        assertTrue(TaskChanges.describe(waiting, waiting.copy()).isEmpty())
    }

    @Test fun historyRecordsOnlyRealChanges() {
        val changed = waiting.copy(status = "done", owner = "م. علاء", dueAt = 1_000_000L)
        val lines = TaskChanges.describe(waiting, changed)
        assertEquals(3, lines.size)
        assertTrue(lines.any { it.startsWith("الحالة") && "بانتظار رد" in it && "مكتمل" in it })
        assertTrue(lines.any { it.startsWith("المسؤول") })
        assertTrue(lines.any { it.startsWith("الاستحقاق") })
    }

    @Test fun movingBetweenProjectsIsLogged() {
        val lines = TaskChanges.describe(waiting, waiting.copy(projectId = "other"))
        assertEquals(1, lines.size)
    }
    @Test fun separateFollowUpAndDueAlertsRemainScheduled() {
        val t = waiting.copy(status="todo", followUpAt=4_000_000L, dueAt=8_000_000L)
        val slots = ReminderPlan.pending(t,1_000_000L)
        assertEquals(2,slots.size)
        assertEquals("followup",slots[0].kind)
        assertEquals(4_000_000L,slots[0].at)
        assertEquals("due",slots[1].kind)
        assertEquals(8_000_000L,slots[1].at)
    }

    @Test fun reminderPlanSkipsClosedAndPastDates() {
        val t = waiting.copy(status="todo",followUpAt=1_000L,dueAt=4_000L)
        assertEquals(1,ReminderPlan.pending(t,2_000L).size)
        assertEquals("due",ReminderPlan.pending(t,2_000L).single().kind)
        assertTrue(ReminderPlan.pending(t.copy(status="done"),0L).isEmpty())
        assertTrue(ReminderPlan.pending(t.copy(status="cancelled"),0L).isEmpty())
        assertEquals(null,ReminderPlan.timestamp(t,"unknown"))
    }

}
