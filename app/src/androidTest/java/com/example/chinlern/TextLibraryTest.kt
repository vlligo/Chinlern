package com.example.chinlern

import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import com.example.chinlern.data.VocabularyReference
import com.example.chinlern.texts.TextLibrary
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TextLibraryTest {
    @Test fun readingPositionsAndTypographyAreProfileLocalAndPositionsFollowRevisions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = "reader-position-" + UUID.randomUUID()
        val storage = ProfileStorage.open(context, profile)
        val texts = TextLibrary(storage)
        val first = texts.save("Reading", "第一行。\n第二行。")
        texts.saveReadingPosition(first, 5)
        texts.readingStyle = TextLibrary.ReadingStyle(size = 34, spacing = 12)
        val second = texts.save("Reading", "第二行。\n第三行。", previous = first)
        val restored = TextLibrary(ProfileStorage.open(context, profile))
        assertEquals(5, restored.readingPosition(first))
        assertEquals(0, restored.readingPosition(second))
        assertEquals(TextLibrary.ReadingStyle(34, 12), restored.readingStyle)
        restored.saveReadingPosition(second, 3)
        restored.saveReadingPosition(first, 0)
        assertEquals(0, restored.readingPosition(first))
        assertEquals(3, restored.readingPosition(second))
        val other = TextLibrary(ProfileStorage.open(context, "$profile-other"))
        assertEquals(0, other.readingPosition(second))
        assertEquals(TextLibrary.ReadingStyle(), other.readingStyle)
        restored.delete(second)
        assertEquals(0, restored.readingPosition(second))
    }

    @Test fun annotatedRevisionsKeepNotesSeparateAndDeduplicateRepeatedGlosses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = "reader-annotated-" + UUID.randomUUID()
        val storage = ProfileStorage.open(context, profile)
        val library = TextLibrary(storage)
        val markup = "{图书馆|tu2 shu1 guan3|библиотека}。\n{图书馆|tu2 shu1 guan3|библиотека}。"
        val original = library.saveAnnotated("Library", markup, "Source", null, "Перевод первой версии.")
        assertEquals("图书馆。\n图书馆。", original.text)
        assertEquals(2, original.wordNotes.size)
        assertEquals(1, Regex("библиотека").findAll(storage.getString("textLibraryV1", "").orEmpty()).count())

        val revised = library.saveAnnotated("Library", "{图书馆|túshūguǎn}。", "Source", original)
        val restored = TextLibrary(ProfileStorage.open(context, profile))
        assertEquals(listOf(original, revised), restored.revisions(original.id))
        assertEquals("Перевод первой версии.", restored.revisions(original.id).first().translation)
        assertEquals("", restored.documents().single().translation)
        assertEquals("", revised.wordNotes.single().gloss)
        assertTrue(TextLibrary(ProfileStorage.open(context, "$profile-other")).documents().isEmpty())
    }

    @Test fun pinyinPreferencesAndChoicesAreLocalAndRevisionScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = "reader-pinyin-" + UUID.randomUUID()
        val library = TextLibrary(ProfileStorage.open(context, profile))
        val original = library.save("Readings", "行。行。")
        assertFalse(library.inlinePinyin)
        library.inlinePinyin = true
        library.choosePinyin(library.select(original, 0, 1), "xíng")
        library.choosePinyin(library.select(original, 2, 3), "háng")
        val revised = library.save("Readings", "行。行。", previous = original)
        val restored = TextLibrary(ProfileStorage.open(context, profile))
        assertTrue(restored.inlinePinyin)
        assertEquals(listOf("xíng", "háng"), restored.pinyinChoices(original).values.toList())
        assertTrue(restored.pinyinChoices(revised).isEmpty())
        val other = TextLibrary(ProfileStorage.open(context, "$profile-other"))
        assertFalse(other.inlinePinyin)
        assertTrue(other.pinyinChoices(original).isEmpty())
        restored.choosePinyin(restored.select(original, 0, 1), null)
        assertEquals(listOf("háng"), restored.pinyinChoices(original).values.toList())
        restored.delete(revised)
        assertTrue(restored.pinyinChoices(original).isEmpty())
    }

    @Test fun revisionsKeepOriginalTextAndContextsAcrossReloadAndProfileIsolation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reader-test-${UUID.randomUUID()}"
        val storage = ProfileStorage.open(context, name)
        storage.edit().putString("progress", "untouched").commit()
        val texts = TextLibrary(storage)
        val document = texts.save("Example", "第一句。\n我学习。 ", "Source")
        val start = document.text.indexOf("学习")
        val selected = texts.select(document, start, start + 2)
        assertEquals("我学习。", selected.sentence)
        val reference = VocabularyReference("pack", "word")
        texts.annotate(selected, reference); texts.annotate(selected, reference)
        assertEquals(1, texts.savedContexts(document).size)
        val updated = texts.save("Edited", "完全不同。", previous = document)
        assertTrue(texts.savedContexts(updated).isEmpty())
        val restored = TextLibrary(ProfileStorage.open(context, name))
        assertEquals(document, restored.revisions(document.id).first())
        assertEquals(updated, restored.documents().single())
        assertEquals(1, restored.savedContexts(document).size)
        val contextLink = restored.savedContextLinks(document.id).single()
        assertEquals(selected, restored.contextSelection(document.id, contextLink))
        assertNull(restored.contextSelection(document.id, contextLink.copy(quote = "错误")))
        assertEquals("untouched", storage.getString("progress", null))
        assertTrue(TextLibrary(ProfileStorage.open(context, "$name-other")).documents().isEmpty())
        restored.delete(updated)
        assertTrue(restored.documents().isEmpty())
    }
    @Test fun staleEditsAndSplitSupplementaryCharactersAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texts = TextLibrary(ProfileStorage.open(context, "reader-test-${UUID.randomUUID()}"))
        val original = texts.save("Unicode", "𠀀学习")
        assertEquals("𠀀", texts.select(original, 0, 2).quote)
        try { texts.select(original, 1, 2); fail("Split surrogate accepted") } catch (_: IllegalArgumentException) { }
        texts.save("Changed", "新文本", previous = original)
        try { texts.save("Stale", "旧文本", previous = original); fail("Stale edit accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(2, texts.revisions(original.id).size)
    }

    @Test fun cardAndOccurrenceSurviveTogetherAndARejectedSaveDoesNotChangeTheLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = "reader-" + UUID.randomUUID()
        val store = StudyStore(context, profile)
        val library = store.textLibrary
        val document = library.save("Fixture", "他在图书馆。")
        val selection = library.select(document, 2, 5)
        val word = Word("reader-card", "图书馆", "túshūguǎn", "tu2 shu1 guan3", listOf("library"), listOf("水", "火", "木"),
            example = document.text, examplePinyin = "Tā zài túshūguǎn.", exampleTranslation = "Он в библиотеке.")
        store.addWordToPack(store.activePackId, word, selection)
        val saved = StudyStore(context, profile)
        assertEquals(word, saved.words.single { it.id == word.id })
        assertTrue(word.id in saved.selected)
        assertEquals(listOf("图书馆 — 他在图书馆。"), saved.textLibrary.savedContexts(document))

        val before = saved.syncSnapshot()
        try {
            saved.addWordToPack(saved.activePackId, word.copy(id = "retry"), selection)
            fail("A duplicate card was created")
        } catch (_: IllegalArgumentException) { }
        saved.textLibrary.delete(document)
        try {
            saved.addWordToPack(saved.activePackId, word.copy(id = "deleted", hanzi = "圖書館"), selection)
            fail("A card was saved with a deleted source")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, StudyStore(context, profile).syncSnapshot())
    }

    @Test fun legacyRevisionAndEditsMadeDuringDownloadStayAttachedToTheOriginalSpans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = "reader-merge-" + UUID.randomUUID()
        val id = "legacy"
        val storage = ProfileStorage.open(context, "chinlearn_user_$profile")
        storage.edit().putString("textLibraryV1", """{"legacy":{"revisions":[{"revision":1,"title":"Legacy","text":"第一。","source":""}],"annotations":[]}}""").commit()
        val local = StudyStore(context, profile)
        val original = local.textLibrary.documents().single { it.id == id }
        val base = local.savedTextSyncSnapshot()
        val peer = StudyStore(context, "$profile-peer")
        peer.applyTextSync(emptyMap(), base, "first")
        val downloaded = peer.textLibrary.save("Cloud", "远程。", previous = peer.textLibrary.documents().single())
        val uploaded = local.textLibrary.save("Local", "𠀀。", previous = original)
        val pending = local.savedTextSyncSnapshot()
        val remote = com.example.chinlern.texts.TextSync.merge(base, pending, peer.savedTextSyncSnapshot())

        var notifications = 0
        local.onSyncDataChanged = { notifications++ }
        val selection = local.textLibrary.select(uploaded, 0, 2)
        local.textLibrary.annotate(selection, VocabularyReference("default", "water"))
        local.textLibrary.saveReadingPosition(uploaded, 2)
        local.textRecall.add(selection)
        assertEquals(3, notifications)
        assertTrue(local.applyTextSync(pending, remote, "second"))

        assertEquals(listOf(original.title, downloaded.title, uploaded.title), local.textLibrary.revisions(id).map { it.title })
        val saved = local.textLibrary.resolve(uploaded)!!
        assertEquals(3, saved.revision)
        assertEquals(2, local.textLibrary.readingPosition(saved))
        assertEquals(listOf("𠀀 — 𠀀。"), local.textLibrary.savedContexts(saved))
        assertEquals(saved, local.textRecall.selection(local.textRecall.queue().targets.single())!!.document)
        local.textLibrary.saveReadingPosition(uploaded, 3)
        assertEquals(3, local.textLibrary.readingPosition(saved))
        assertEquals(0, local.textLibrary.readingPosition(downloaded))
        assertEquals(remote, StudyStore(context, profile).textSyncBase())
        assertTrue(local.inspectBackup(local.exportBackup()).packs.keys.none { it == id })
    }
}
