package com.example.chinlern

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProfileStorageTest {
    @Test fun largeRestoredProfileSupportsStartupChecksAndFullReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "large-migration-test-${UUID.randomUUID()}"
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val largeValue = "学习𠀀".repeat(650_000)
        assertTrue(legacy.edit().putString("packs", largeValue).putInt("step", 8).commit())

        val restored = ProfileStorage.open(context, name)
        assertTrue(restored.contains("packs"))
        assertFalse(restored.contains("missing"))
        assertTrue("A large value must round-trip through a single-key read",
            largeValue == restored.getString("packs", null))
        assertTrue("A large value must round-trip through a full profile read",
            largeValue == restored.all["packs"])
        assertEquals(8, restored.all["step"])

        assertTrue(restored.edit().putString("draft", "新").commit())
        val reopened = ProfileStorage.open(context, name)
        assertTrue(largeValue == reopened.getString("packs", null))
        assertEquals("新", reopened.getString("draft", null))
    }

    @Test fun legacyImportPreservesAllTypesAndNeverReimportsAfterEditsOrClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test-${UUID.randomUUID()}"
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        legacy.edit().putString("packs", "{}").putString("draft", "水").putInt("step", 8)
            .putLong("due", 1234567890123).putBoolean("audio", false).putFloat("float", 0.5f)
            .putStringSet("selected", setOf("one", "two")).commit()
        val original = legacy.all.toMap()
        val migrated = ProfileStorage.open(context, name)
        assertEquals(original, migrated.all)
        migrated.edit().putString("packs", "{\"updated\":{}}").putString("draft", "new")
            .putInt("step", 9).remove("float").commit()
        assertEquals(original, legacy.all)
        val reopened = ProfileStorage.open(context, name)
        assertEquals(9, reopened.getInt("step", 0))
        assertEquals("new", reopened.getString("draft", null))
        assertFalse(reopened.contains("float"))
        assertEquals(setOf("one", "two"), reopened.getStringSet("selected", null))
        reopened.edit().clear().commit()
        assertTrue(ProfileStorage.open(context, name).all.isEmpty())
        assertEquals(original, legacy.all)
    }

    @Test fun independentHandlesSeeCommittedChangesAndProfilesDoNotLeak() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test-${UUID.randomUUID()}"
        val first = ProfileStorage.open(context, name)
        val second = ProfileStorage.open(context, name)
        first.edit().putString("packs", "snapshot").putString("draft", "matching draft").commit()
        assertEquals("snapshot", second.getString("packs", null))
        assertEquals("matching draft", second.getString("draft", null))
        assertTrue(ProfileStorage.open(context, "$name-other").all.isEmpty())
    }

    @Test fun aProfileHandleCanReadAndWriteAfterItsConnectionIsEvicted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "eviction-test-${UUID.randomUUID()}"
        val profile = ProfileStorage.open(context, name)
        profile.edit().putString("draft", "水").commit()
        repeat(4) { index ->
            val other = ProfileStorage.open(context, "$name-$index")
            assertNull(other.getString("draft", null))
            other.edit().putString("draft", "$index").commit()
        }
        assertEquals("水", profile.getString("draft", null))
        profile.edit().putString("draft", "火").commit()
        assertEquals("火", ProfileStorage.open(context, name).getString("draft", null))
        assertEquals("0", ProfileStorage.open(context, "$name-0").getString("draft", null))
    }
}
