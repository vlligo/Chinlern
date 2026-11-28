package com.example.chinlern

import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import com.example.chinlern.dictionary.DictionaryBookmarks
import com.example.chinlern.dictionary.OfflineDictionary
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DictionaryBookmarksTest {
    @Test fun bookmarksAreProfileScopedAndKeepReadingsSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bookmarks-" + UUID.randomUUID()
        val bookmarks = DictionaryBookmarks(ProfileStorage.open(context, name))
        val xing = DictionaryBookmarks.Reference("行", "行", "xing2")
        val hang = xing.copy(pinyin = "hang2")
        assertTrue(bookmarks.toggle(xing))
        assertTrue(bookmarks.toggle(hang))
        val restored = DictionaryBookmarks(ProfileStorage.open(context, name))
        assertEquals(listOf(hang, xing), restored.all())
        assertFalse(restored.toggle(xing.copy(pinyin = "Xing2")))
        assertEquals(listOf(hang), restored.all())
        val russian = hang.copy(source = OfflineDictionary.Source.RU_WIKTIONARY)
        assertTrue(restored.toggle(russian))
        assertEquals(listOf(russian, hang), restored.all())
        assertTrue(DictionaryBookmarks(ProfileStorage.open(context, "$name-other")).all().isEmpty())
    }
}
