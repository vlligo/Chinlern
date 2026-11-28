package com.example.chinlern

import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import com.example.chinlern.texts.TextLibrary
import com.example.chinlern.texts.TextRecall
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TextRecallStoreTest {
    @Test fun keepsSchedulesSeparateAndTheOriginalRevisionSurvivesEditing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recall-" + UUID.randomUUID()
        val storage = ProfileStorage.open(context, name)
        val library = TextLibrary(storage)
        val original = library.save("Original", "我喜欢学习。")
        val selection = library.select(original, 0, original.text.length)
        var now = 1_800_000_000_000L
        val recall = TextRecall(storage, library) { now }
        val target = recall.add(selection)
        assertEquals(target, recall.add(selection))
        assertEquals(listOf(target), recall.queue().due)
        val edited = library.save("Edited", "她喜欢看书。", previous = original)
        assertEquals(selection, recall.selection(target))
        val second = recall.add(library.select(edited, 0, edited.text.length))
        assertNotEquals(target.id, second.id)

        val saved = recall.record(target, "hint-attempt", Fsrs.Rating.EASY, assisted = true)
        assertEquals(1, saved.assistedReviews)
        assertEquals(1, saved.reviews)
        assertTrue(saved.due in (now + 1)..(now + 600_000))
        assertEquals(saved, recall.record(target, "hint-attempt", Fsrs.Rating.EASY, assisted = true))
        try {
            recall.record(target, "stale-attempt", Fsrs.Rating.GOOD, assisted = false)
            fail("A stale attempt overwrote the schedule")
        } catch (_: IllegalArgumentException) { }

        val restored = TextRecall(ProfileStorage.open(context, name), library) { now }
        assertEquals(listOf(second), restored.queue().due)
        now = saved.due
        val revisited = restored.queue().due.single { it.id == target.id }
        val unaided = restored.record(revisited, "unaided", Fsrs.Rating.GOOD, assisted = false)
        assertEquals(2, unaided.reviews)
        assertEquals(1, unaided.assistedReviews)
        val other = ProfileStorage.open(context, "$name-other")
        assertTrue(TextRecall(other, TextLibrary(other)) { now }.queue().targets.isEmpty())
        library.delete(edited)
        assertTrue(restored.queue().targets.isEmpty())
        assertNull(restored.selection(unaided))
    }

    @Test fun rejectsBlankOverlimitAndSplitGraphemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = ProfileStorage.open(context, "recall-invalid-" + UUID.randomUUID())
        val library = TextLibrary(storage)
        val recall = TextRecall(storage, library) { 1L }
        val document = library.save("Fixture", "。水\uFE00" + "字".repeat(401))
        for (selection in listOf(library.select(document, 0, 1), library.select(document, 1, 2),
            library.select(document, 3, document.text.length))) {
            try {
                recall.add(selection)
                fail("Invalid fragment accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(recall.queue().targets.isEmpty())
    }
}
