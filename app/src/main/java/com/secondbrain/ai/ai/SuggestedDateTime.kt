package com.secondbrain.ai.ai

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Parse dates returned by AI only after it explicitly resolves relative Arabic dates. */
object SuggestedDateTime {
    fun parse(value: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val source = value?.trim().orEmpty()
        if (source.isBlank() || source.equals("null", ignoreCase = true)) return null
        return runCatching {
            if (source.length == 10) LocalDate.parse(source).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
            else LocalDateTime.parse(source.replace(' ', 'T').take(16)).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
