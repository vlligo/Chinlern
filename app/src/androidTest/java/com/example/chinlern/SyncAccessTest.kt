package com.example.chinlern

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncAccessTest {
    @Test fun accessSurvivesRecreationAndDisableWithoutLeakingAcrossAccounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_device_sync", Context.MODE_PRIVATE).edit().clear().commit()
        val first = SyncAccess(context, "first")
        first.rememberPreviousAccess()
        assertTrue(SyncAccess(context, "first").enabled)
        assertFalse(SyncAccess(context, "second").hasAccess)
        first.disable()
        val restored = SyncAccess(context, "first")
        assertTrue(restored.hasAccess)
        assertFalse(restored.enabled)
        restored.rememberPreviousAccess()
        assertFalse(restored.enabled)
        assertTrue(restored.enable(""))
        assertTrue(restored.enabled)
    }
    @Test fun priorSuccessfulSyncMigratesWithoutPassword() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_device_sync", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("chinlearn_user_returning", Context.MODE_PRIVATE).edit().clear()
            .putLong("lastSyncedAt", 1234).putString("packs", "{}").commit()
        assertTrue(StudyStore(context, "returning").syncAccess.enabled)
        assertTrue(StudyStore(context, "returning").syncAccess.hasAccess)
    }
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
        if (password == null) assertFalse(access.enable("")) else assertTrue(access.enable(""))
    }
}
