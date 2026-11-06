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
 * Displays partial speech recognition directly in the editable assistant text box.
 * No recording is saved. The on-device engine is tried first where available;
 * language-unavailable failures fall back to another Arabic locale and then,
 * with explicit user consent, to the system recognition service.
 */
class LiveSpeechController(
    private val context: Context,
    private val allowSystemRecognizer: Boolean,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onActive: (Boolean) -> Unit,
    private val onErrorMessage: (String) -> Unit,
    private val onSystemConsentRequired: (String) -> Unit = {},
    private val onRecognitionUnavailable: (String) -> Unit = {},
    private val forceSystemRecognizer: Boolean = false
) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var wantsListening = false
    private var systemEngine = forceSystemRecognizer
    private var languageIndex = 0
    private var busyRetries = 0
    private var silenceRestarts = 0
    private var lastPartial = ""
    private var lastFinal = ""
    private val languages = listOf("ar-EG", "ar-SA", "ar")
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
            onSystemConsentRequired("لا يوجد محرك إملاء محلي. هل تسمح باستخدام محرك التعرف الصوتي الخاص بالهاتف؟ قد يحتاج الإنترنت.")
            return
        }
        systemEngine = forceSystemRecognizer || !onDeviceAvailable(context)
        if (systemEngine && !allowSystemRecognizer) {
            onSystemConsentRequired("التعرف الصوتي المحلي غير مناسب. هل تسمح بتجربة محرك التعرف الخاص بالهاتف؟ قد يحتاج الإنترنت.")
            return
        }
        if (systemEngine && !anyAvailable(context)) {
            onErrorMessage("لا توجد خدمة تعرف صوتي مفعّلة. ثبّت أو فعّل خدمة التعرف الصوتي في إعدادات الهاتف، أو استخدم زر ميكروفون لوحة المفاتيح.")
            return
        }
        wantsListening = true
        languageIndex = 0
        busyRetries = 0
        silenceRestarts = 0
        onActive(true)
        createRecognizerAndListen()
    }

    fun stop() {
        if (!wantsListening) return
        wantsListening = false
        cancelRestart()
        try { recognizer?.stopListening() } catch (_: Exception) {}
        pendingStop = Runnable {
            if (lastPartial.isNotBlank() && lastPartial != lastFinal) onFinal(lastPartial)
            lastPartial = ""
            onPartial("")
            onActive(false)
        }.also { handler.postDelayed(it, 2200L) }
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages[languageIndex])
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languages[languageIndex])
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !systemEngine)
    }

    private fun createRecognizerAndListen() {
        if (!wantsListening) return
        try {
            // Destroy before creating a replacement to avoid ERROR_RECOGNIZER_BUSY (8).
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = if (!systemEngine && onDeviceAvailable(context) && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer?.setRecognitionListener(this)
            startSegment()
        } catch (e: Exception) {
            stopEverything()
            onErrorMessage("تعذر بدء التعرف الصوتي: ${e.message ?: "خدمة غير متاحة"}")
        }
    }

    private fun startSegment() {
        if (!wantsListening) return
        lastPartial = ""
        onPartial("")
        try { recognizer?.startListening(buildIntent()) }
        catch (e: Exception) {
            stopEverything()
            onErrorMessage("تعذر فتح الميكروفون للتعرف الصوتي: ${e.message ?: "خطأ غير معروف"}")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() { silenceRestarts = 0; busyRetries = 0 }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onPartialResults(results: Bundle?) {
        if (!wantsListening) return
        val candidate = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty().trim()
        if (candidate.isNotBlank()) {
            lastPartial = candidate
            onPartial(candidate)
        }
    }

    override fun onResults(results: Bundle?) {
        clearStopTimeout()
        val finalText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty().trim().ifEmpty { lastPartial }
        lastPartial = ""
        onPartial("")
        if (finalText.isNotBlank()) {
            lastFinal = finalText
            onFinal(finalText)
        }
        if (wantsListening) {
            busyRetries = 0
            scheduleRestart(750L, recreate = false)
        } else onActive(false)
    }

    override fun onError(error: Int) {
        clearStopTimeout()
        if (!wantsListening) {
            if (lastPartial.isNotBlank() && lastPartial != lastFinal) onFinal(lastPartial)
            lastPartial = ""
            onPartial("")
            onActive(false)
            return
        }
        // Preserve any useful words already recognized, even when a service fails.
        if (lastPartial.isNotBlank() && lastPartial != lastFinal) {
            lastFinal = lastPartial
            onFinal(lastPartial)
        }
        lastPartial = ""
        onPartial("")
        when (error) {
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                busyRetries = 0
                if (languageIndex < languages.lastIndex) {
                    languageIndex++
                    onErrorMessage("جارٍ تجربة لغة عربية بديلة (${languages[languageIndex]})…")
                    scheduleRestart(750L, recreate = true)
                } else if (!systemEngine) {
                    if (allowSystemRecognizer) {
                        systemEngine = true
                        languageIndex = 0
                        onErrorMessage("اللغة غير مدعومة محليًا؛ جارٍ تجربة محرك الهاتف…")
                        scheduleRestart(900L, recreate = true)
                    } else {
                        stopEverything()
                        onSystemConsentRequired("المحرك المحلي موجود لكنه لا يدعم العربية المطلوبة (رمز $error). هل تسمح باستخدام خدمة التعرف الصوتي الخاصة بالهاتف؟ قد تحتاج الإنترنت.")
                    }
                } else fail("محرك التعرف الصوتي لا يدعم العربية على هذا الهاتف (رمز $error).", true)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT -> {
                if (busyRetries < 2) {
                    busyRetries++
                    onErrorMessage("جارٍ إعادة تهيئة الميكروفون (المحاولة $busyRetries)…")
                    scheduleRestart(1000L * busyRetries + 400L, recreate = true)
                } else fail("محرك التعرف الصوتي غير متاح أو مشغول (رمز $error).", true)
            }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (silenceRestarts++ < 3) scheduleRestart(900L, recreate = true)
                else fail("لم ألتقط كلامًا واضحًا. تأكد من إذن الميكروفون ثم اضغط بدء الإملاء مجددًا.")
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("إذن الميكروفون غير مسموح. افتح إعدادات التطبيق وفعّل الميكروفون.")
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> fail("خدمة التعرف الصوتي تحتاج اتصالًا بالإنترنت. تحقق من الاتصال أو استخدم الكتابة.")
            else -> fail("تعذر تشغيل محرك الإملاء (رمز $error). جرّب خدمة تعرف صوتي أخرى من إعدادات الهاتف.")
        }
    }

    private fun fail(message: String, offerRecording: Boolean = false) {
        stopEverything()
        onErrorMessage(message)
        if (offerRecording) onRecognitionUnavailable(message)
    }

    private fun scheduleRestart(delayMs: Long, recreate: Boolean) {
        cancelRestart()
        pendingRestart = Runnable {
            pendingRestart = null
            if (!wantsListening) return@Runnable
            if (recreate) createRecognizerAndListen() else startSegment()
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun cancelRestart() {
        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = null
    }

    private fun clearStopTimeout() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
    }

    private fun stopEverything() {
        wantsListening = false
        cancelRestart()
        clearStopTimeout()
        onPartial("")
        onActive(false)
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    fun destroy() { stopEverything() }
}
