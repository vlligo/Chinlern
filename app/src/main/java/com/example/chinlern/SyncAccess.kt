package com.example.chinlern

import android.content.Context
import java.security.MessageDigest

/** Device-local opt-in. This convenience lock is not a server authorization secret. */
class SyncAccess(context: Context) {
    private val prefs = context.getSharedPreferences("chinlearn_device_sync", Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)

    fun enable(password: String): Boolean {
        val actual = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        val verifier = BuildConfig.SYNC_PASSWORD_SHA256
        if (!verifier.matches(Regex("[0-9a-fA-F]{64}"))) return false
        val expected = verifier.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (!MessageDigest.isEqual(actual, expected)) return false
        return prefs.edit().putBoolean("enabled", true).commit()
    }

    fun disable() { prefs.edit().putBoolean("enabled", false).commit() }
}
