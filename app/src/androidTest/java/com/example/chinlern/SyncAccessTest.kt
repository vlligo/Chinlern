package com.example.chinlern

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncAccessTest {
    @Test fun defaultsOffWrongPasswordStaysOffAndDisablePersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("chinlearn_device_sync", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val access = SyncAccess(context)
        assertFalse(access.enabled)
        assertFalse(access.enable("wrong-password"))
        assertFalse(SyncAccess(context).enabled)
        val password = InstrumentationRegistry.getArguments().getString("syncPassword")
        if (password != null) {
            assertTrue(access.enable(password))
            assertTrue(SyncAccess(context).enabled)
        }
        access.disable()
        assertFalse(SyncAccess(context).enabled)
        assertFalse(access.enable(""))
    }
}
