package com.secondbrain.ai.ai

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local estimate only. API provider's billing is authoritative; no hard invoice guarantee. */
class BudgetGuard(context: Context) {
    private val preferences=context.getSharedPreferences("secondbrain_budget",Context.MODE_PRIVATE)
    private val month:String get()=SimpleDateFormat("yyyy-MM",Locale.US).format(Date())
    private fun key()="spend_${month}"
    val sarPerUsd=3.76
    val internalLimitSar=15.0 // 20 SAR monthly requested, leave 5 SAR margin for taxes / estimate error.
    fun usedSar():Double=preferences.getFloat(key(),0f).toDouble()
    @Synchronized fun maySpend(estimatedSar:Double):Boolean=estimatedSar>0 && usedSar()+estimatedSar<=internalLimitSar
    @Synchronized fun record(estimatedSar:Double){preferences.edit().putFloat(key(),(usedSar()+estimatedSar).toFloat()).apply()}
    fun estimateTextSar(inputCharacters:Int,outputTokens:Int=900):Double {
        // Conservative, context-dependent estimate: ~2 characters per token for mixed Arabic/English.
        val inputTokens=inputCharacters.coerceAtLeast(0)/2.0
        return (inputTokens*0.10+outputTokens*0.50)/1_000_000.0*sarPerUsd*1.4
    }
    fun estimateAudioSar(durationSeconds:Long):Double = durationSeconds.coerceAtLeast(1)/60.0*0.0045*sarPerUsd*1.25
}
