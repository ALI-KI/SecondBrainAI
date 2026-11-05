package com.secondbrain.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Live Android dictation; no app-owned audio file is created.
 * Prefers Android's on-device recognizer. A system recognizer is used ONLY
 * if the activity obtained an explicit opt-in because it may use online services.
 * Final segments are appended once; partial segments are shown transiently.
 */
class LiveSpeechController(
    private val context: Context,
    private val allowSystemRecognizer: Boolean,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onActive: (Boolean) -> Unit,
    private val onErrorMessage: (String) -> Unit
) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var wantsListening = false
    private var lastPartial = ""
    private var lastFinal = ""
    private var silenceRestarts = 0
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null
    private var pendingStop: Runnable? = null

    companion object {
        fun onDeviceAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        fun anyAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun start() {
        if (wantsListening) return
        if (!onDeviceAvailable(context) && !allowSystemRecognizer) {
            onErrorMessage("التفريغ المحلي غير متوفر. يلزم موافقتك على خدمة التعرف الصوتي في الهاتف.")
            return
        }
        if (!onDeviceAvailable(context) && !anyAvailable(context)) {
            onErrorMessage("لا توجد خدمة تعرف صوتي على هذا الهاتف")
            return
        }
        try {
            recognizer = if (onDeviceAvailable(context) && Build.VERSION.SDK_INT >= 31)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(this)
            wantsListening = true
            silenceRestarts = 0
            onActive(true)
            startSegment()
        } catch (e: Exception) {
            stopEverything()
            onErrorMessage("تعذر بدء الإملاء المباشر: ${e.message}")
        }
    }

    fun stop() {
        if (!wantsListening) return
        wantsListening = false
        pendingRestart?.let { handler.removeCallbacks(it) }
        recognizer?.stopListening()
        pendingStop = Runnable {
            if (lastPartial.isNotBlank()) onFinal(lastPartial)
            lastPartial = ""
            onPartial("")
            onActive(false)
        }.also { handler.postDelayed(it, 2000L) }
    }

    private fun startSegment() {
        lastPartial = ""
        onPartial("")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDeviceAvailable(context))
        }
        try { recognizer?.startListening(intent) }
        catch (e: Exception) {
            stopEverything()
            onErrorMessage("تعذر استكمال الإملاء: ${e.message}")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() { silenceRestarts = 0 }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onPartialResults(results: Bundle?) {
        val candidate = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
        if (candidate.isNotBlank()) {
            lastPartial = candidate
            onPartial(candidate)
        }
    }

    override fun onResults(results: Bundle?) {
        clearStopTimeout()
        val finalText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            .ifEmpty { lastPartial }
        lastPartial = ""
        onPartial("")
        if (finalText.isNotBlank()) {
            lastFinal = finalText
            onFinal(finalText)
        }
        if (wantsListening) queueRestart() else onActive(false)
    }

    override fun onError(error: Int) {
        clearStopTimeout()
        val pendingText = lastPartial
        lastPartial = ""
        onPartial("")
        // The recognizer may fail after emitting a usable partial sentence.
        if (pendingText.isNotBlank() && pendingText != lastFinal) {
            lastFinal = pendingText
            onFinal(pendingText)
        }
        val canRetry = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (wantsListening && canRetry && silenceRestarts < 2) {
            silenceRestarts++
            queueRestart()
        } else {
            wantsListening = false
            onActive(false)
            if (!canRetry) onErrorMessage("توقف محرك الإملاء (رمز $error). جرّب تشغيل الميكروفون مجددًا.")
        }
    }

    private fun queueRestart() {
        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = Runnable { if (wantsListening) startSegment() }
            .also { handler.postDelayed(it, 350L) }
    }

    private fun clearStopTimeout() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
    }

    private fun stopEverything() {
        wantsListening = false
        pendingRestart?.let { handler.removeCallbacks(it) }
        clearStopTimeout()
        onPartial("")
        onActive(false)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    fun destroy() { stopEverything() }
}
