package com.example.chinlern

import android.content.Context
import java.security.MessageDigest

/** Account-scoped remembered access, separate from the user's on/off switch. */
class SyncAccess(context: Context, private val userId: String? = null) {
    private val prefs = context.getSharedPreferences("chinlearn_device_sync", Context.MODE_PRIVATE)
    private val scope = userId ?: "guest"
    val hasAccess: Boolean get() = prefs.getBoolean("unlocked:$scope", false)
    val enabled: Boolean get() = hasAccess && prefs.getBoolean("enabled:$scope", false)

    init {
        // The old flag was device-wide. Claim it once for the signed-in account only.
        if (userId != null && prefs.getBoolean("enabled", false)) {
            prefs.edit().putBoolean("unlocked:$scope", true).putBoolean("enabled:$scope", true)
                .remove("enabled").commit()
        }
    }

    fun enable(password: String): Boolean {
        if (hasAccess) return resume()
        val actual = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        val verifier = BuildConfig.SYNC_PASSWORD_SHA256
        if (!verifier.matches(Regex("[0-9a-fA-F]{64}"))) return false
        val expected = verifier.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (!MessageDigest.isEqual(actual, expected)) return false
        return rememberPreviousAccess()
    }

    /** Successful prior sync (local history or the authenticated owner's cloud library) is proof of access. */
    fun rememberPreviousAccess(): Boolean {
        if (hasAccess) return true // Never undo an explicit choice to turn syncing off.
        return prefs.edit().putBoolean("unlocked:$scope", true).putBoolean("enabled:$scope", true).commit()
    }

    fun resume(): Boolean = hasAccess && prefs.edit().putBoolean("enabled:$scope", true).commit()
    fun disable() { prefs.edit().putBoolean("enabled:$scope", false).commit() }
}
