package com.secondbrain.ai.voice

/** Explicit spoken distribution command, only when it ends a completed speech segment. */
object VoiceCommand {
    private val distribution = Regex("(?:^|[\\s،.!؟]+)(?:وزع|وزّع|وزعي|وزعلي)\\s+(?:المهام|المهمة)\\s*[،.!؟]*$", RegexOption.IGNORE_CASE)
    fun extract(text: String): Pair<String, Boolean> {
        val match = distribution.find(text.trim()) ?: return text.trim() to false
        return text.trim().substring(0, match.range.first).trim() to true
    }
}
