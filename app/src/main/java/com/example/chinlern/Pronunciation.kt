package com.example.chinlern

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class Pronunciation(private val activity: Activity, private val status: (String?) -> Unit,
    private val startExternal: (Intent) -> Unit = { activity.startActivity(it) }) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initialized = false
    private var pendingDevice: Pair<String, Boolean>? = null
    private var closed = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingHanping: Runnable? = null
    private var hanpingBusyUntil = 0L
    init {
        tts = TextToSpeech(activity) { result ->
            if (closed) return@TextToSpeech
            initialized = true
            if (result == TextToSpeech.SUCCESS) {
                val available = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = available >= TextToSpeech.LANG_AVAILABLE
                // Prefer a downloaded Chinese voice when the engine offers one.
                tts?.voices?.firstOrNull { it.locale.language == "zh" && !it.isNetworkConnectionRequired && it.locale.country == "CN" }?.let { tts?.voice = it }
            }
            status(if (ready) null else "Китайский голос недоступен. Добавьте его в настройках озвучивания или выберите Hanping.")
            val waiting = pendingDevice
            pendingDevice = null
            if (ready && waiting != null) playText(waiting.first, waiting.second)?.let(status)
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Required by the Android callback interface")
            override fun onError(utteranceId: String?) {
                activity.runOnUiThread {
                    if (!activity.isDestroyed) status("Голос не смог воспроизвести запись. Проверьте загрузку китайского голоса и попробуйте снова.")
                }
            }
        })
    }
    fun play(word: Word, provider: String, slow: Boolean): String? {
        if (closed) return null
        if (provider == "hanping") {
            pendingDevice = null
            tts?.stop()
            pendingHanping?.let { handler.removeCallbacks(it) }
            val wait = (hanpingBusyUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (wait > 0) {
                pendingHanping = Runnable {
                    pendingHanping = null
                    launchHanping(word)?.let { status(it) }
                }.also { handler.postDelayed(it, wait) }
                return null
            }
            return launchHanping(word)
        }
        return playText(word.hanzi, slow)
    }

    internal fun playText(text: String, slow: Boolean): String? {
        if (closed) return null
        pendingHanping?.let { handler.removeCallbacks(it) }; pendingHanping = null
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) {
            return "Этот фрагмент слишком длинный для озвучивания."
        }
        if (!initialized) {
            pendingDevice = text to slow
            status("Подготавливаем китайский голос…")
            return null
        }
        if (!ready) return "Китайский голос ещё не готов или не установлен. Откройте настройки озвучивания."
        tts?.setSpeechRate(if (slow) 0.65f else 0.9f)
        return if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chinlearn-device") == TextToSpeech.SUCCESS) null
        else "Не удалось воспроизвести звук. Проверьте настройки голоса."
    }
    private fun launchHanping(word: Word): String? {
        // No completion callback is provided by Hanping. Space requests conservatively;
        // rapid repeats keep only the latest request instead of overlapping audio sessions.
        hanpingBusyUntil = android.os.SystemClock.elapsedRealtime() + 1200L + Answers.tones(word).size * 700L
        return try {
                startExternal(Intent("com.hanpingchinese.cmn.intent.action.PLAY")
                    .setPackage("com.embermitre.hanping.app.pro")
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    // Hanping 10.5 requires both written-form fields to be non-empty, even for audio.
                    // Reuse the supplied form when a separate traditional form is unavailable.
                    .putExtra("s", word.hanzi).putExtra("t", word.hanzi).putExtra("p", HanpingPinyin.encode(word.numbered)).putExtra("LANG", "cmn")
                    .putExtra("_SOURCE", "chinlearn"))
                null
            } catch (_: ActivityNotFoundException) { "Hanping не найден. Установите его или выберите системный голос." }
            catch (_: SecurityException) { "Эта версия Hanping не разрешает внешний запуск звука. Выберите системный голос." }
    }
    fun stop() { pendingDevice = null; pendingHanping?.let { handler.removeCallbacks(it) }; pendingHanping = null; tts?.stop() }
    fun close() { closed = true; stop(); tts?.shutdown() }
}
