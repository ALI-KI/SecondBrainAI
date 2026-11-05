package com.secondbrain.ai

import com.secondbrain.ai.ai.WorkspaceRouter
import com.secondbrain.ai.ai.SuggestedDateTime
import java.time.ZoneId
import com.secondbrain.ai.voice.VoiceCommand
import org.junit.Assert.*
import org.junit.Test

class VoiceRoutingTest {
    @Test fun personalCannotBeRoutedToViewByAi() {
        val r=WorkspaceRouter.route("هات حاجات للبيت وفكرني بفاتورة شقتنا", "view")
        assertEquals("personal",r.workspaceId)
        assertFalse(r.needsReview)
    }
    @Test fun viewWorkHasOwnWorkspace() {
        val r=WorkspaceRouter.route("تابع برج فيو مع RWDI", "personal")
        assertEquals("view",r.workspaceId)
    }
    @Test fun ambiguousWorkspaceRequiresExplicitChoice() {
        val r=WorkspaceRouter.route("كلّم المهندس يوم الأحد", "view")
        assertTrue(r.needsReview)
        val mixed=WorkspaceRouter.route("خلص ملف فيو واشتري طلبات البيت", "view")
        assertNull(mixed.workspaceId)
        assertTrue(mixed.needsReview)
    }
    @Test fun mixedRequestRoutesPerTaskNotPerRecording() {
        val tasks=listOf("جيب طلبات البيت بكرة" to "view", "راجع RWDI بخصوص برج فيو الأحد" to "personal")
        val classified=tasks.map{ (text,wrongGuess)->WorkspaceRouter.route(text,wrongGuess).workspaceId }
        assertEquals(listOf("personal","view"),classified)
    }
    @Test fun parsesDateAndTimeWithoutGuessing() {
        val zone=ZoneId.of("Asia/Riyadh")
        assertNotNull(SuggestedDateTime.parse("2026-11-08T11:00",zone))
        assertNotNull(SuggestedDateTime.parse("2026-11-08",zone))
        assertNull(SuggestedDateTime.parse("",zone))
        assertNull(SuggestedDateTime.parse("الأحد",zone))
    }
    @Test fun spokenCommandOnlyWhenEnding() {
        assertEquals("فكرني أجيب حاجات للبيت" to true,VoiceCommand.extract("فكرني أجيب حاجات للبيت وزع المهام"))
        assertEquals("" to true,VoiceCommand.extract("وزّع المهمة"))
        assertEquals("كيفية توزيع المهام غدًا" to false,VoiceCommand.extract("كيفية توزيع المهام غدًا"))
    }
}
