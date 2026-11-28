package com.example.chinlern

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Navigation only: external dictionaries do not return definitions to the app. */
class ExternalDictionary(private val activity: Activity) {
    fun lookup(text: String, pleco: Boolean): String? = try {
        val uri = if (pleco) "plecoapi://x-callback-url/s?q=${Uri.encode(text)}" else "hanping://search?text=${Uri.encode(text)}"
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(if (pleco) "com.pleco.chinesesystem" else "com.embermitre.hanping.app.pro"))
        null
    } catch (_: ActivityNotFoundException) { "Словарь не установлен." }
      catch (_: SecurityException) { "Не удалось открыть словарь." }
}
