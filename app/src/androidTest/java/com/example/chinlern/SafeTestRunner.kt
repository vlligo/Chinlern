package com.example.chinlern

import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Destructive fixtures are never allowed to run against a person's library. */
class SafeTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.startsWith("sdk_gphone")
        val readOnlyPhoneSmoke = arguments.getString("phoneSmoke") == "true" &&
            arguments.getString("class") == "com.example.chinlern.PhoneSmokeTest"
        check(emulator || readOnlyPhoneSmoke) {
            "Data-resetting tests are restricted to emulators. The personal-device library was not touched."
        }
        super.onCreate(arguments)
    }
}
