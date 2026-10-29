package com.example.chinlern

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class Pronunciation(private val activity: Activity, private val status: (String?) -> Unit,
    private val startExternal: (Intent) -> Unit = { activity.startActivity(it) }) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingHanping: Runnable? = null
    private var hanpingBusyUntil = 0L
    init {
        tts = TextToSpeech(activity) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val available = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = available >= TextToSpeech.LANG_AVAILABLE
                // Prefer a downloaded Chinese voice when the engine offers one.
                tts?.voices?.firstOrNull { it.locale.language == "zh" && !it.isNetworkConnectionRequired && it.locale.country == "CN" }?.let { tts?.voice = it }
            }
            status(if (ready) null else "Китайский голос недоступен. Добавьте его в настройках озвучивания или выберите Hanping.")
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
        if (provider == "hanping") {
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
        pendingHanping?.let { handler.removeCallbacks(it) }; pendingHanping = null
        if (!ready) return "Китайский голос ещё не готов или не установлен. Откройте настройки озвучивания."
        tts?.setSpeechRate(if (slow) 0.65f else 0.9f)
        return if (tts?.speak(word.hanzi, TextToSpeech.QUEUE_FLUSH, null, "chinlearn-word") == TextToSpeech.SUCCESS) null
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
    fun lookup(word: Word, pleco: Boolean): String? = try {
        val uri = if (pleco) "plecoapi://x-callback-url/s?q=${Uri.encode(word.hanzi)}" else "hanping://search?text=${Uri.encode(word.hanzi)}"
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(if (pleco) "com.pleco.chinesesystem" else "com.embermitre.hanping.app.pro"))
        null
    } catch (_: ActivityNotFoundException) { "Словарь не установлен." }
      catch (_: SecurityException) { "Не удалось открыть словарь." }
    fun stop() { pendingHanping?.let { handler.removeCallbacks(it) }; pendingHanping = null; tts?.stop() }
    fun close() { stop(); tts?.shutdown() }
}
